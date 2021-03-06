package models.export.rows

import org.specs2.mutable.Specification
import org.specs2.specification.Scope
import play.api.libs.iteratee.{Enumerator,Iteratee}
import play.api.libs.json.Json
import play.api.test.{DefaultAwaitTimeout,FutureAwaits}

import com.overviewdocs.metadata.{Metadata,MetadataField,MetadataFieldType,MetadataSchema}
import com.overviewdocs.models.{Document,Tag}

class DocumentsWithStringTagsSpec extends Specification with FutureAwaits with DefaultAwaitTimeout {
  trait BaseScope extends Scope {
    val factory = com.overviewdocs.test.factories.PodoFactory
    def documents: Enumerator[(Document, Seq[Long])]
    val metadataSchema: MetadataSchema = MetadataSchema.empty
    val tags: Seq[Tag] = Seq()
    lazy val rows: Rows = DocumentsWithStringTags(metadataSchema, documents, tags)
    lazy val rowList: List[Array[String]] = await(rows.rows.run(Iteratee.getChunks))
    def headRow = rowList.head
  }

  trait OneDocumentScope extends BaseScope {
    val sampleDocument: Document = factory.document(
      suppliedId="suppliedId",
      title="title",
      text="text",
      url=Some("url"),
      metadataJson=Json.obj()
    )
    val document: (Document,Seq[Long]) = (sampleDocument, Seq())
    override def documents = Enumerator(document)
  }

  "ExportDocumentsWithStringTags" should {
    "export tags" in new OneDocumentScope {
      override val tags = Seq(
        factory.tag(id=2L, name="aaa"), // IDs out of order so we test ordering
        factory.tag(id=3L, name="bbb"),
        factory.tag(id=1L, name="ccc")
      )
      override val document = (sampleDocument, Seq(1L, 3L))
      headRow(4) must beEqualTo("bbb,ccc")
    }

    "export suppliedId" in new OneDocumentScope {
      override val document = (sampleDocument.copy(suppliedId="foobar"), Seq())
      headRow(0) must beEqualTo("foobar")
    }

    "export title" in new OneDocumentScope {
      override val document = (sampleDocument.copy(title="foobar"), Seq())
      headRow(1).toString must beEqualTo("foobar")
    }

    "export url" in new OneDocumentScope {
      override val document = (sampleDocument.copy(url=Some("foobar")), Seq())
      headRow(3) must beEqualTo("foobar")
    }

    "export None url" in new OneDocumentScope {
      override val document = (sampleDocument.copy(url=None), Seq())
      headRow(3) must beEqualTo("")
    }

    "export text" in new OneDocumentScope {
      override val document = (sampleDocument.copy(text="foobar"), Seq())
      headRow(2) must beEqualTo("foobar")
    }

    "export metadata" in new OneDocumentScope {
      override val metadataSchema = MetadataSchema(1, Seq(
        MetadataField("fooField", MetadataFieldType.String),
        MetadataField("barField", MetadataFieldType.String)
      ))
      override val document = (sampleDocument.copy(metadataJson=Json.obj("fooField" -> "foo1", "barField" -> "bar1")), Seq())
      rows.headers.drop(4).take(2) must beEqualTo(Array("fooField", "barField")) // metadata before tags
      headRow.drop(4).take(2) must beEqualTo(Array("foo1", "bar1"))
    }
  }
}
