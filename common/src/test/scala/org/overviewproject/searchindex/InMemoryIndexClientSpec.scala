package org.overviewproject.searchindex

import java.util.concurrent.ExecutionException
import org.elasticsearch.common.settings.ImmutableSettings
import org.elasticsearch.index.query.QueryBuilders
import org.elasticsearch.indices.IndexMissingException
import org.specs2.mutable.{After,Specification}
import scala.concurrent.{Await,Future}
import scala.concurrent.duration.Duration

import org.overviewproject.tree.orm.Document // FIXME should be model

class InMemoryIndexClientSpec extends Specification {
  import scala.concurrent.ExecutionContext.Implicits.global
  sequential

  trait BaseScope extends After {
    class TestInMemoryIndexClient extends InMemoryIndexClient {
      def publicClientFuture = clientFuture
      def preInitClient = await(connect)
    }
    lazy val indexClient = new TestInMemoryIndexClient()

    private val awaitDuration = Duration(2, "s")
    def await[T](future: Future[T]): T = Await.result(future, Duration.Inf)

    def createIndex(name: String) = {
      val settings = ImmutableSettings.settingsBuilder
        .put("index.store.type", "memory")
        .put("index.number_of_shards", 1)
        .put("index.number_of_replicas", 0)

      indexClient.preInitClient.admin.indices.prepareCreate(name)
        .setSettings(settings)
        .execute.get
    }

    def createAlias(index: String, alias: String) = {
      indexClient.preInitClient.admin.indices.prepareAliases
        .addAlias("documents_v2", "documents")
        .execute.get
    }

    def aliasExists(index: String, alias: String) = {
      val exists = indexClient.preInitClient
        .admin.indices.prepareExistsAliases(alias)
        .execute.get.isExists

      if (exists) {
        val aliases = indexClient.preInitClient
          .admin.indices.prepareGetAliases(alias)
          .execute.get.getAliases

        aliases.containsKey(index)
      } else {
        false
      }
    }

    def buildDocument(id: Long, documentSetId: Long) = Document(
      id=id,
      documentSetId=documentSetId,
      description=s"description$id",
      title=Some(s"moo$id"),
      text=Some(s"foo$id bar baz")
    )

    override def after = indexClient.close
  }

  "InMemorySearchIndex" should {
    "#addDocumentSet" should {
      "create documents_v1 if there is no documents alias" in new BaseScope {
        await(indexClient.addDocumentSet(234L))

        aliasExists("documents_v1", "documents") must beEqualTo(true)
      }

      "not create documents_v1 if there is a documents alias" in new BaseScope {
        createIndex("documents_v2")
        createAlias("documents_v2", "documents")

        await(indexClient.addDocumentSet(234L))

        aliasExists("documents_v1", "documents_234") must beEqualTo(false)
        aliasExists("documents_v1", "documents") must beEqualTo(false)
      }

      "use documents_vN if it is what the documents alias points to" in new BaseScope {
        createIndex("documents_v2")
        createAlias("documents_v2", "documents")

        await(indexClient.addDocumentSet(234L))

        aliasExists("documents_v2", "documents_234") must beEqualTo(true)
      }

      "create an alias that filters by document set" in new BaseScope {
        await(indexClient.addDocumentSet(234L))
        await(indexClient.addDocuments(Seq(buildDocument(123L, 234L), buildDocument(124L, 235L))))
        await(indexClient.refresh())

        aliasExists("documents_v1", "documents_234") must beEqualTo(true)

        val resultsFuture = indexClient.publicClientFuture.map { client =>
          client.prepareSearch("documents_234")
            .setTypes("document")
            .setQuery(QueryBuilders.queryString("*:*"))
            .setSize(2)
            .addField("id")
            .execute().get()
        }

        val ids = await(resultsFuture)
          .getHits
          .getHits
          .map(_.field("id").value[Object].toString.toLong)
          .toSeq
          
        ids must beEqualTo(Seq(123L))
      }
    }

    "#removeDocumentSet" should {
      "remove the index alias" in new BaseScope {
        await(indexClient.addDocumentSet(234L))
        await(indexClient.removeDocumentSet(234L))

        aliasExists("documents_v1", "documents_234") must beEqualTo(false)
      }

      "delete associated documents" in new BaseScope {
        await(indexClient.addDocumentSet(234L))
        await(indexClient.addDocuments(Seq(buildDocument(123L, 234L), buildDocument(124L, 235L))))
        await(indexClient.refresh)
        await(indexClient.removeDocumentSet(234L))

        val resultsFuture = indexClient.publicClientFuture.map { client =>
          client.prepareSearch("documents")
            .setTypes("document")
            .setQuery(QueryBuilders.queryString("*:*"))
            .setSize(2)
            .addField("id")
            .execute().get()
        }

        val ids = await(resultsFuture)
          .getHits
          .getHits
          .map(_.field("id").value[Object].toString.toLong)
          .toSeq
          
        ids must beEqualTo(Seq(124L))
      }
    }

    "#searchForIds" should {
      "find a document" in new BaseScope {
        await(indexClient.addDocumentSet(234L))
        await(indexClient.addDocuments(Seq(buildDocument(123L, 234L), buildDocument(124L, 234L))))
        await(indexClient.refresh())
        val ids = await(indexClient.searchForIds(234L, "foo123"))

        ids must beEqualTo(Seq(123L))
      }

      "not find a document in a different document set" in new BaseScope {
        await(indexClient.addDocumentSet(234L))
        await(indexClient.addDocumentSet(235L))
        await(indexClient.addDocuments(Seq(buildDocument(123L, 234L))))
        await(indexClient.refresh())
        val ids = await(indexClient.searchForIds(235L, "foo123"))

        ids must beEqualTo(Seq())
      }

      "not find a document when the query does not match" in new BaseScope {
        await(indexClient.addDocumentSet(234L))
        await(indexClient.addDocuments(Seq(buildDocument(123L, 234L))))
        await(indexClient.refresh())
        val ids = await(indexClient.searchForIds(234L, "foo124"))

        ids must beEqualTo(Seq())
      }

      "find multiple documents" in new BaseScope {
        await(indexClient.addDocumentSet(234L))
        await(indexClient.addDocuments(Seq(buildDocument(123L, 234L), buildDocument(124L, 234L))))
        await(indexClient.refresh())
        val ids = await(indexClient.searchForIds(234L, "bar"))

        ids must containTheSameElementsAs(Seq(123L, 124L))
      }

      "find multiple pages of documents" in new BaseScope {
        await(indexClient.addDocumentSet(234L))
        await(indexClient.addDocuments(Seq(buildDocument(123L, 234L), buildDocument(124L, 234L))))
        await(indexClient.refresh())
        val ids = await(indexClient.searchForIds(234L, "bar", scrollSize=1))

        ids must containTheSameElementsAs(Seq(123L, 124L))
      }
    }
  }
}
