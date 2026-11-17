package gg.hideouthq.deadlockstats

import gg.hideouthq.core.data.deadlockstats.DateWindow
import gg.hideouthq.core.data.deadlockstats.MatchModeFilter
import gg.hideouthq.core.data.deadlockstats.SteamAccountId
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class DesktopStatsPlatformTest {
    @Test fun yourStatsVisitsAndSearchesSpendOneApiBudget(): Unit = runBlocking {
        val asked = Collections.synchronizedList(mutableListOf<String>())
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            asked += path
            if ("/analytics/" in path) respondError(HttpStatusCode.TooManyRequests, headers = headersOf(HttpHeaders.RetryAfter, "120")) else respond("[]")
        }
        val root = Files.createTempDirectory("stats-platform-test-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val platform = DesktopStatsPlatform("Hideout/test", root.resolve("deadlock-stats").toFile(), httpEngine = { engine })
        val tracked = platform.openTracked(scope)
        val visitor = platform.openVisitor(scope)
        val lookup = platform.openVisitor(scope)
        try {
            val account = SteamAccountId.ofAccountId(555)!!
            assertFails { tracked.heroCounters(account, DateWindow(), MatchModeFilter.All) }
            assertFails { visitor.playerMetrics(DateWindow(), MatchModeFilter.Ranked) }
            assertFails { lookup.itemUsage(account, 1, DateWindow(), MatchModeFilter.All) }
            assertEquals(1, asked.count { "/analytics/" in it }, "the server's two-minute cooldown holds every session's analytics: $asked")
            tracked.close()
            assertEquals(emptyList(), visitor.searchPlayers("Mirai"))
            assertTrue(asked.last().endsWith("/steam-search"), "closing your session leaves a visit's requests going: $asked")
        } finally {
            tracked.close(); visitor.close(); lookup.close()
            scope.cancel()
            platform.close()
            root.toFile().deleteRecursively()
        }
    }
}
