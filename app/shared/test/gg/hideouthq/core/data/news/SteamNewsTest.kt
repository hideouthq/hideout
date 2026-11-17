package gg.hideouthq.core.data.news

import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.model.NewsKind
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SteamNewsTest {
    @Test
    fun postsAreSortedNewestFirstTaggedByKindAndReadAsParagraphsWithTheirFirstPicture() {
        val posts = parseSteamNews(Answer)
        assertEquals(listOf("2", "1"), posts.map { it.id })
        val (patch, announcement) = posts
        assertEquals(NewsKind.PatchNotes, patch.kind)
        assertEquals("Minor Update - 10-05-2026", patch.title)
        assertEquals(listOf("[ General ]", "- Troopers walk at their usual speed", "• First", "• Second & third"), patch.body)
        assertEquals(null, patch.imageUrl)
        assertEquals(NewsKind.Announcement, announcement.kind)
        assertEquals("https://clan.akamai.steamstatic.com/images/45164767/abc.png", announcement.imageUrl)
        assertEquals(listOf("Rat King arrives. Read more"), announcement.body)
        assertEquals("$SteamNewsPage/view/1", announcement.url)
        assertEquals(1_791_000_000, announcement.postedAt)
    }

    @Test
    fun aPicturesSourceAttributeAndLongTextAreReadToo() {
        assertEquals("https://clan.akamai.steamstatic.com/images/1/x.png", firstImage("""[p][img src="{STEAM_CLAN_IMAGE}/1/x.png"][/img][/p]"""))
        assertEquals(null, firstImage("""[img]http://example.com/x.png[/img]"""))
        val long = parseSteamNews(answer(item("9", "Long", "[p]${"word ".repeat(100)}[/p]", 5))).single()
        assertTrue(long.excerpt.length <= 221 && long.excerpt.endsWith("…"))
    }

    @Test
    fun savedPostsShowAtOnceAndAreFetchedAgainOnlyOnceAnHourOld() = runTest {
        var requests = 0
        var failing = false
        val client = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    requests++
                    assertEquals("1422450", request.url.parameters["appid"])
                    assertEquals("steam_community_announcements", request.url.parameters["feeds"])
                    if (failing) respond("", HttpStatusCode.ServiceUnavailable) else respond(Answer, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
        val storage = MemoryStorage()
        var now = Instant.fromEpochSeconds(1_791_100_000)
        val clock = object : Clock {
            override fun now(): Instant = now
        }
        val first = CoroutineScope(backgroundScope.coroutineContext + Job())
        SteamNewsRepository(client, storage, first, clock).start()
        runCurrent()
        assertEquals(1, requests, "nothing saved, so it fetches at once")
        first.cancel()

        now += 30.minutes
        val reopened = SteamNewsRepository(client, storage, backgroundScope, clock).apply { start() }
        runCurrent()
        assertEquals(listOf("2", "1"), reopened.posts.value.map { it.id }, "the saved posts show without a fetch")
        assertEquals(1, requests)
        failing = true
        advanceTimeBy(30.minutes + 1.minutes)
        assertEquals(2, requests, "fetched again once an hour old")
        assertEquals(listOf("2", "1"), reopened.posts.value.map { it.id }, "a failed fetch keeps what was shown")
        advanceTimeBy(1.hours)
        assertEquals(3, requests, "and every hour after")
        client.close()
    }

    private companion object {
        val Answer = answer(
            item("1", "Rat King arrives.", "[p][img]{STEAM_CLAN_LOC_IMAGE}/45164767/abc.png[/img][/p][p]Rat King arrives. [url=https://x.test]Read more[/url][/p]", 1_791_000_000),
            item("2", "Minor Update - 10-05-2026", "[p][b]\\\\[ General ][/b][/p][p][/p][p]- Troopers walk at their usual speed[/p][list][*]First[*]Second &amp; third[/list]", 1_791_100_000, patchNotes = true),
            """{"gid":"3","contents":"No title","date":1}""",
        )

        fun item(gid: String, title: String, contents: String, date: Long, patchNotes: Boolean = false) =
            """{"gid":"$gid","title":"$title","url":"https://steamstore-a.akamaihd.net/news/externalpost/steam_community_announcements/$gid","contents":"$contents","date":$date${if (patchNotes) ""","tags":["patchnotes"]""" else ""}}"""

        fun answer(vararg items: String) = """{"appnews":{"appid":1422450,"newsitems":[${items.joinToString(",")}]}}"""
    }
}
