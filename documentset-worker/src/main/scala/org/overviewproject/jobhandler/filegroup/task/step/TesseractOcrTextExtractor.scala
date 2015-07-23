package org.overviewproject.jobhandler.filegroup.task.step

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.io.Source
import scala.language.postfixOps
import scala.util.Try
import scala.util.control.Exception.ultimately
import org.overviewproject.jobhandler.filegroup.task.ShellRunner
import org.overviewproject.jobhandler.filegroup.task.TimeoutGenerator
import org.overviewproject.util.Configuration
import org.overviewproject.util.SupportedLanguages

trait TesseractOcrTextExtractor extends OcrTextExtractor {
  implicit protected val executionContext: ExecutionContext
  protected val shellRunner: ShellRunner
  protected val ocrTimeout: FiniteDuration
  protected val tesseractLocation: String
  protected val fileSystem: FileSystem

  protected trait FileSystem {
    def writeImage(image: BufferedImage): File
    def readText(file: File): String
    def deleteFile(file: File): Boolean
  }

  def extractText(image: BufferedImage, language: String): Future[String] = {
  
    val result = withImageAsTemporaryFile(image) { tempFile =>
      extractTextWithOcr(tempFile, language) { textTempFile =>
        fileSystem.readText(textTempFile)
      }
    }

    result
  }

  private def withImageAsTemporaryFile(image: BufferedImage)(f: File => Future[String]): Future[String] = {
    val storedImage = Future.fromTry(Try { fileSystem.writeImage(image) })
    def callAndDeleteWhenComplete(imageFile: File): Future[String] = {
      val result = f(imageFile)
      result.onComplete {
        case _ => fileSystem.deleteFile(imageFile)
      }

      result
    }

    for {
      imageFile <- storedImage
      result <- callAndDeleteWhenComplete(imageFile)
    } yield result
  }

  private def extractTextWithOcr(imageFile: File, language: String)(f: File => String): Future[String] = {
    // Tesseract needs language specified as a ISO639-2 code. 
    // A language parameter that does not have an appropriate transformation denotes an error
    // and an exception is thrown.
    val iso639_2Code = SupportedLanguages.asIso639_2(language).get
    
    val output = outputFile(imageFile)
    shellRunner.run(tesseractCommand(imageFile.getAbsolutePath, output.getAbsolutePath(), iso639_2Code), ocrTimeout)
      .map { _ =>
        ultimately(fileSystem.deleteFile(output)) {
          f(output)
        }
      }

  }

  private def tesseractCommand(inputFile: String, outputFile: String, language: String): String = {
    val outputBase = outputFile.replace(".txt", "")
    s"$tesseractLocation $inputFile $outputBase -l $language"
  }
  private def outputFile(inputFile: File): File = {
    val inputFilePath = inputFile.getAbsolutePath
    val outputFilePath = inputFilePath.replace(".png", ".txt")
    new File(outputFilePath)
  }

}

object TesseractOcrTextExtractor {

  import scala.concurrent.duration.DurationInt
  import scala.language.postfixOps
  import org.overviewproject.util.Configuration

  def apply(timeoutGenerator: TimeoutGenerator)(implicit executionContext: ExecutionContext): TesseractOcrTextExtractor =
    new TesseractOcrTextExtractorImpl(ShellRunner(timeoutGenerator), executionContext)

  private class TesseractOcrTextExtractorImpl(
    override protected val shellRunner: ShellRunner,
    override implicit protected val executionContext: ExecutionContext) extends TesseractOcrTextExtractor {

    override protected val ocrTimeout = Configuration.getInt("ocr_timeout") millis
    override protected val fileSystem: FileSystem = new OsFileSystem

    override protected val tesseractLocation = Configuration.getString("tesseract_path")

    private class OsFileSystem extends FileSystem {
      override def writeImage(image: BufferedImage): File = {
        val imageFile = File.createTempFile("overview-ocr", ".png")
        ImageIO.write(image, "png", imageFile)

        imageFile
      }

      override def readText(textFile: File): String =
        Source.fromFile(textFile).mkString

      override def deleteFile(file: File): Boolean = file.delete
    }
  }
}