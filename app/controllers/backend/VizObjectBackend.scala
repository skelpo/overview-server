package controllers.backend

import scala.concurrent.Future

import org.overviewproject.models.VizObject
import org.overviewproject.models.tables.VizObjects

trait VizObjectBackend {
  /** Lists all VizObjects for a DocumentSet.
    *
    * When indexedLong or indexedString is Some, it will filter the results.
    */
  def index(vizId: Long, indexedLong: Option[Long]=None, indexedString: Option[String]=None): Future[Seq[VizObject]]

  /** Returns an individual VizObject.
    *
    * Returns `None` if the VizObject does not exist.
    */
  def show(vizObjectId: Long): Future[Option[VizObject]]

  /** Creates a VizObject.
    *
    * Returns an error if the database write fails.
    */
  def create(vizId: Long, attributes: VizObject.CreateAttributes): Future[VizObject]

  /** Modifies a VizObject, and returns the modified version.
    *
    * Returns `None` if the VizObject does not exist.
    *
    * If you do not run this in a transaction, there is a potential race. This
    * method runs an UPDATE and then a SELECT. See
    * https://github.com/slick/slick/issues/963
    */
  def update(id: Long, attributes: VizObject.UpdateAttributes): Future[Option[VizObject]]

  /** Destroys a VizObject.
    *
    * Returns an error if the database write fails.
    */
  def destroy(id: Long): Future[Unit]
}

trait DbVizObjectBackend extends VizObjectBackend { self: DbBackend =>
  override def index(vizId: Long, indexedLong: Option[Long]=None, indexedString: Option[String]=None) = db { session =>
    DbVizObjectBackend.byVizIdAndIndexes(vizId, indexedLong, indexedString)(session)
  }

  override def show(id: Long) = db { session =>
    DbVizObjectBackend.byId(id)(session)
  }

  override def create(vizId: Long, attributes: VizObject.CreateAttributes) = db { implicit session =>
    val id = DbVizObjectBackend.nextVizObjectId(vizId)(session)
    val vizObject = VizObject.build(id, vizId, attributes)
    DbVizObjectBackend.insert(vizObject)(session)
  }

  override def update(id: Long, attributes: VizObject.UpdateAttributes) = db { implicit session =>
    val count = DbVizObjectBackend.update(id, attributes)(session)
    if (count > 0) DbVizObjectBackend.byId(id)(session) else None
  }

  override def destroy(id: Long) = db { implicit session =>
    DbVizObjectBackend.destroy(id)(session)
  }
}

object DbVizObjectBackend {
  import org.overviewproject.database.Slick.simple._

  private lazy val byIdCompiled = Compiled { (id: Column[Long]) =>
    VizObjects.where(_.id === id)
  }

  private lazy val byVizIdCompiled = Compiled { (vizId: Column[Long]) =>
    VizObjects.where(_.vizId === vizId)
  }

  private lazy val byVizIdAndIndexedLongCompiled = Compiled { (vizId: Column[Long], indexedLong: Column[Long]) =>
    VizObjects
      .where(_.vizId === vizId)
      .where(_.indexedLong === indexedLong)
  }

  private lazy val byVizIdAndIndexedStringCompiled = Compiled { (vizId: Column[Long], indexedString: Column[String]) =>
    VizObjects
      .where(_.vizId === vizId)
      .where(_.indexedString === indexedString)
  }

  private lazy val byVizIdAndIndexedLongAndIndexedStringCompiled = Compiled { (vizId: Column[Long], indexedLong: Column[Long], indexedString: Column[String]) =>
    VizObjects
      .where(_.vizId === vizId)
      .where(_.indexedLong === indexedLong)
      .where(_.indexedString === indexedString)
  }

  private lazy val previousIdCompiled = Compiled { (min: Column[Long], max: Column[Long]) =>
    VizObjects
      .where(_.id >= min)
      .where(_.id < max)
      .map(_.id)
      .max
  }

  private lazy val attributesByIdCompiled = Compiled { (id: Column[Long]) =>
    for (v <- VizObjects if v.id === id) yield (v.indexedLong, v.indexedString, v.json)
  }

  private lazy val insertVizObject = (VizObjects returning VizObjects).insertInvoker

  def nextVizObjectId(vizId: Long)(session: Session): Long = {
    val minId = (vizId & 0xffffffff00000000L) | ((vizId & 0xffL) << 6)
    val maxId = minId + 0x100000000L
    val previousId = previousIdCompiled(minId, maxId).run(session)
    previousId.map(_ + 1L).getOrElse(minId)
  }

  def byVizIdAndIndexes(vizId: Long, indexedLong: Option[Long], indexedString: Option[String])(session: Session) = {
    val query = indexedLong match {
      case None => indexedString match {
        case None => byVizIdCompiled(vizId)
        case Some(s) => byVizIdAndIndexedStringCompiled(vizId, s)
      }
      case Some(i) => indexedString match {
        case None => byVizIdAndIndexedLongCompiled(vizId, i)
        case Some(s) => byVizIdAndIndexedLongAndIndexedStringCompiled(vizId, i, s)
      }
    }
    query.list()(session)
  }

  def byId(id: Long)(session: Session) = {
    byIdCompiled(id).firstOption()(session)
  }

  def insert(vizObject: VizObject)(session: Session): VizObject = {
    (insertVizObject += vizObject)(session)
  }

  def update(id: Long, attributes: VizObject.UpdateAttributes)(session: Session): Int = {
    attributesByIdCompiled(id).update(attributes.indexedLong, attributes.indexedString, attributes.json)(session)
  }

  def destroy(id: Long)(session: Session): Unit = {
    byIdCompiled(id).delete(session)
  }
}

object VizObjectBackend extends DbVizObjectBackend with DbBackend