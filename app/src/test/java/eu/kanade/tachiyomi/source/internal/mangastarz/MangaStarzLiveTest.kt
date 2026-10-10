package eu.kanade.tachiyomi.source.internal.mangastarz

import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class MangaStarzLiveTest {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val source = MangaStarz(client)

    @Test
    fun verifySourceIdentity() {
        source.name shouldBe "Manga Starz"
        source.lang shouldBe "ar"
        source.versionId shouldBe 1
        source.id shouldBe 6058166507489091982L
        source.baseUrl shouldBe "https://starzmanga.com"
    }

    @Test
    fun verifyLivePopularManga() {
        runBlocking {
            val popular = source.getPopularManga(1)
            popular.mangas.shouldNotBeEmpty()
            val first = popular.mangas.first()
            first.title.isNotBlank() shouldBe true
            first.url.shouldStartWith("/")
        }
    }

    @Test
    fun verifyLiveLatestUpdates() {
        runBlocking {
            val latest = source.getLatestUpdates(1)
            latest.mangas.shouldNotBeEmpty()
            val first = latest.mangas.first()
            first.title.isNotBlank() shouldBe true
            first.url.shouldStartWith("/")
        }
    }

    @Test
    fun verifyLiveSearch() {
        runBlocking {
            val popular = source.getPopularManga(1)
            val sample = popular.mangas.first()
            val searchWord = sample.title.split(" ").firstOrNull { it.length > 2 } ?: "manga"
            val search = source.getSearchManga(1, searchWord, FilterList())
            search.mangas.shouldNotBeEmpty()
        }
    }

    @Test
    fun verifyLiveDetailsAndChaptersAndPages() {
        runBlocking {
            val popular = source.getPopularManga(1)
            val sample = popular.mangas.first()

            val detailsResp = client.newCall(source.mangaDetailsRequest(sample)).awaitSuccess()
            val details = source.mangaDetailsParse(detailsResp)
            details.title.isNotBlank() shouldBe true

            val chaptersResp = client.newCall(source.chapterListRequest(sample)).awaitSuccess()
            val chapters = source.chapterListParse(chaptersResp)
            chapters.shouldNotBeEmpty()

            val firstChapter = chapters.first()
            firstChapter.name.isNotBlank() shouldBe true
            firstChapter.url.shouldStartWith("/")

            val pagesResp = client.newCall(source.pageListRequest(firstChapter)).awaitSuccess()
            val pages = source.pageListParse(pagesResp)
            pages.shouldNotBeEmpty()
            pages.first().imageUrl!!.shouldStartWith("http")
        }
    }
}
