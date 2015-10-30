package controllers

import java.util.UUID
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.iteratee.Iteratee
import play.api.mvc.{Action,BodyParser,Result}
import scala.concurrent.{Future,blocking}

import com.overviewdocs.database.DeprecatedDatabase
import com.overviewdocs.messages.DocumentSetCommands
import com.overviewdocs.models.{DocumentSet,GroupedFileUpload}
import com.overviewdocs.tree.orm.DocumentSetCreationJob
import com.overviewdocs.tree.Ownership
import com.overviewdocs.util.ContentDisposition
import controllers.auth.Authorities.{anyUser,userOwningDocumentSet}
import controllers.auth.{AuthorizedAction,AuthorizedBodyParser}
import controllers.backend.{DocumentSetBackend,FileGroupBackend,GroupedFileUploadBackend}
import controllers.forms.MassUploadControllerForm
import controllers.iteratees.GroupedFileUploadIteratee
import controllers.util.{MassUploadControllerMethods,JobQueueSender}
import models.orm.stores.DocumentSetCreationJobStore
import models.User

trait MassUploadController extends Controller {
  protected val documentSetBackend: DocumentSetBackend
  protected val fileGroupBackend: FileGroupBackend
  protected val groupedFileUploadBackend: GroupedFileUploadBackend
  protected val storage: MassUploadController.Storage
  protected val messageQueue: JobQueueSender
  protected val uploadIterateeFactory: (GroupedFileUpload,Long) => Iteratee[Array[Byte],Unit]

  /** Calls MassUploadControllerMethods.Create(), returning the result as body.
    *
    * Used in create().
    */
  private def createInnerBodyParser(user: User, guid: UUID): BodyParser[Result] = {
    BodyParser("MassUploadController.createInnerBodyParser") { request =>
      MassUploadControllerMethods.Create(
        user.email,
        None,
        guid,
        fileGroupBackend,
        groupedFileUploadBackend,
        uploadIterateeFactory,
        false
      )(request)
        .map(result => Right(result)) // Doesn't matter which it is...
    }
  }

  /** Checks user auth, then calls createInnerBodyParser().
    *
    * Used in create().
    */
  private def createAuthBodyParser(guid: UUID): BodyParser[Result] = {
    AuthorizedBodyParser(anyUser)(user => createInnerBodyParser(user, guid))
  }

  /** Starts or resumes a file upload. */
  def create(guid: UUID) = Action(createAuthBodyParser(guid))(finishedRequest => finishedRequest.body)

  /** Responds to a HEAD request.
    *
    * There are four possible responses:
    *
    * <ul>
    *   <li><tt>404 Not Found</tt>: the file with the given <tt>guid</tt> is
    *       not in the active file group.</li>
    *   <li><tt>204 No Content</tt>: there is an empty file with the given
    *       <tt>guid</tt>. It may or may not be completely uploaded. This
    *       will include a <tt>Content-Disposition</tt> header.</li>
    *   <li><tt>206 Partial Content</tt>: the file is partially uploaded. This
    *       will include <tt>Content-Disposition</tt> and
    *       <tt>Content-Range</tt> headers.</li>
    *   <li><tt>200 OK</tt>: the file is fully uploaded and non-empty. This
    *       will include <tt>Content-Disposition</tt> and
    *       <tt>Content-Length</tt> headers.</li>
    * </ul>
    *
    * Regardless of the status code, the body will be empty.
    *
    * TODO refactor into MassUploadControllerMethods
    */
  def show(guid: UUID) = AuthorizedAction(anyUser).async { request =>
    def contentDisposition(upload: GroupedFileUpload) = {
      ContentDisposition.fromFilename(upload.name).contentDisposition
    }

    def uploadHeaders(upload: GroupedFileUpload): Seq[(String, String)] = {
      def computeEnd(uploadedSize: Long): Long =
        if (upload.uploadedSize == 0) 0
        else upload.uploadedSize - 1

      Seq(
        (CONTENT_LENGTH, s"${upload.uploadedSize}"),
        (CONTENT_RANGE, s"bytes 0-${computeEnd(upload.uploadedSize)}/${upload.size}"),
        (CONTENT_DISPOSITION, contentDisposition(upload)))
    }

    findUploadInCurrentFileGroup(request.user.email, guid).map(_ match {
      case None => NotFound
      case Some(u) if (u.uploadedSize == 0L) => {
        // You can't send an Ok or PartialContent when Content-Length=0
        NoContent.withHeaders((CONTENT_DISPOSITION, contentDisposition(u)))
      }
      case Some(u) if (u.uploadedSize == u.size) => Ok.withHeaders(uploadHeaders(u): _*)
      case Some(u) => PartialContent.withHeaders(uploadHeaders(u): _*)
    })
  }

  /** Marks the FileGroup as <tt>completed</tt> and kicks off a
    * DocumentSetCreationJob.
    *
    * TODO refactor into MassUploadControllerMethods
    */
  def startClustering = AuthorizedAction(anyUser).async { request =>
    MassUploadControllerForm.new_.bindFromRequest()(request).fold(
      e => Future(BadRequest),
      startClusteringFileGroupWithOptions(request.user.email, _)
    )
  }

  /** Marks the FileGroup as <tt>completed</tt> and kicks off a
    * DocumentSetCreationJob.
    *
    * Does <em>not</em> create a DocumentSet.
    */
  def startClusteringExistingDocumentSet(id: Long) = AuthorizedAction(userOwningDocumentSet(id)).async { request =>
    MassUploadControllerForm.edit.bindFromRequest()(request).fold(
      e => Future(BadRequest),
      startClusteringFileGroupWithDocumentSetAndOptions(request.user.email, id, _)
    )
  }

  /** Cancels the upload and notify the worker to delete all uploaded files
    *
    * TODO refactor into MassUploadControllerMethods
    */
  def cancel = AuthorizedAction(anyUser).async { request =>
    fileGroupBackend.find(request.user.email, None)
      .flatMap(_ match {
        case Some(fileGroup) => fileGroupBackend.destroy(fileGroup.id)
        case None => Future.successful(())
      })
      .map(_ => Accepted)
  }

  private def findUploadInCurrentFileGroup(userEmail: String, guid: UUID): Future[Option[GroupedFileUpload]] = {
    fileGroupBackend.find(userEmail, None)
      .flatMap(_ match {
        case None => Future.successful(None)
        case Some(fileGroup) => groupedFileUploadBackend.find(fileGroup.id, guid)
      })
  }

  private def startClusteringFileGroupWithOptions(userEmail: String,
                                                  options: (String, String, Boolean, String, String)): Future[Result] = {
    val (name, lang, splitDocuments, suppliedStopWords, importantWords) = options

    fileGroupBackend.find(userEmail, None).flatMap(_ match {
      case Some(fileGroup) => {
        for {
          documentSet <- documentSetBackend.create(DocumentSet.CreateAttributes(name), userEmail)
          job <- Future.successful(storage.createMassUploadDocumentSetCreationJob(documentSet.id, fileGroup.id, lang, splitDocuments, suppliedStopWords, importantWords, true))
          _ <- fileGroupBackend.update(fileGroup.id, true)
        } yield Redirect(routes.DocumentSetController.show(documentSet.id))
      }
      case None => Future.successful(NotFound)
    })
  }

  // Yaaaay, copy/pasting! :)
  private def startClusteringFileGroupWithDocumentSetAndOptions(
    userEmail: String,
    documentSetId: Long,
    options: (String, Boolean, String, String)
  ): Future[Result] = {
    val (lang, splitDocuments, suppliedStopWords, importantWords) = options

    def redirect = Redirect(routes.DocumentSetController.show(documentSetId))

    fileGroupBackend.find(userEmail, None).flatMap(_ match {
      case None => Future.successful(redirect)
      case Some(fileGroup) => {
        val job: DocumentSetCreationJob = blocking(DeprecatedDatabase.inTransaction {
          storage.createMassUploadDocumentSetCreationJob(
            documentSetId, fileGroup.id, lang, splitDocuments, suppliedStopWords, importantWords, false)
        })

        fileGroupBackend.update(fileGroup.id, true) // TODO put in transaction
          .map(_ => redirect)
      }
    })
  }
}

/** Controller implementation */
object MassUploadController extends MassUploadController {
  override protected val storage = DatabaseStorage
  override protected val messageQueue = JobQueueSender
  override protected val documentSetBackend = DocumentSetBackend
  override protected val fileGroupBackend = FileGroupBackend
  override protected val groupedFileUploadBackend = GroupedFileUploadBackend
  override protected val uploadIterateeFactory = GroupedFileUploadIteratee.apply _

  trait Storage {
    /** @returns a newly created DocumentSetCreationJob */
    def createMassUploadDocumentSetCreationJob(
      documentSetId: Long,
      fileGroupId: Long,
      lang: String,
      splitDocuments: Boolean,
      suppliedStopWords: String,
      importantWords: String,
      canBeCancelled: Boolean
    ): DocumentSetCreationJob
  }

  object DatabaseStorage extends Storage {
    import com.overviewdocs.tree.orm.DocumentSetCreationJobState.FilesUploaded
    import com.overviewdocs.tree.DocumentSetCreationJobType.FileUpload

    override def createMassUploadDocumentSetCreationJob(
      documentSetId: Long,
      fileGroupId: Long,
      lang: String,
      splitDocuments: Boolean,
      suppliedStopWords: String,
      importantWords: String,
      canBeCancelled: Boolean
    ): DocumentSetCreationJob = {
      val job = DeprecatedDatabase.inTransaction {
        DocumentSetCreationJobStore.insertOrUpdate(
          DocumentSetCreationJob(
            documentSetId = documentSetId,
            fileGroupId = Some(fileGroupId),
            lang = lang,
            splitDocuments = splitDocuments,
            suppliedStopWords = suppliedStopWords,
            importantWords = importantWords,
            state = FilesUploaded,
            jobType = FileUpload,
            canBeCancelled = canBeCancelled
          )
        )
      }

      val command = DocumentSetCommands.AddDocumentsFromFileGroup(
        job.id,
        documentSetId=job.documentSetId,
        fileGroupId=job.fileGroupId.get,
        lang=job.lang,
        splitDocuments=job.splitDocuments
      )
      JobQueueSender.send(command)

      job
    }
  }
}
