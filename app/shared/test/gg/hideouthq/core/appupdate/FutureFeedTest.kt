package gg.hideouthq.core.appupdate

import gg.hideouthq.core.data.fake.MemoryStorage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/** Feeds a later release might publish, signed and read by this build's real parser, and what this build then offers. */
class FutureFeedTest {
    private val url = "https://example.test/download/releases.json"
    private val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val sha = "b".repeat(64)
    private val appImage = """"appimage":{"url":"https://example.test/Hideout-1.4.0-x86_64.AppImage","size":1000,"sha256":"$sha"}"""
    private val pkg = ReleasePackage(Offered, PackageFormat.AppImage, "https://example.test/Hideout-1.4.0-x86_64.AppImage", 1000, sha)

    private fun sign(text: String): String =
        Base64.getEncoder().encodeToString(Signature.getInstance("Ed25519").run { initSign(keys.private); update(text.encodeToByteArray()); sign() })

    private val verify: (ByteArray, String) -> Boolean = { bytes, signature ->
        Signature.getInstance("Ed25519").run { initVerify(keys.public); update(bytes); verify(Base64.getDecoder().decode(signature)) }
    }

    private fun feed(latestEntry: String, schema: Int = 1) =
        """{"schema":$schema,"latest":"$Offered","notesUrl":"$NotesUrl","versions":[$latestEntry,{"version":"$Running","date":"2026-10-01","kind":"fixes"}]}"""

    private suspend fun TestScope.offered(feed: String, signature: String = sign(feed)): AppUpdateState {
        val client = HttpClient(MockEngine) {
            engine { addHandler { request -> respond(if (request.url.toString() == url) feed else signature) } }
        }
        val source = SignedReleaseFeed(client, url, MemoryStorage(), Clock.System, verify)
        val updates = Rig(this).updates(source = source)
        updates.start()
        return updates.state.first { it.lastCheckFailed || it.phase is AppUpdatePhase.Ready || it.phase is AppUpdatePhase.GitHubOnly }
    }

    @Test
    fun aKindThisBuildDoesNotKnowStillUpdates() = runTest {
        val state = offered(feed("""{"version":"$Offered","date":"2026-11-02","kind":"security","download":{$appImage}}"""))
        assertEquals(AppUpdatePhase.Ready(pkg, RestartStage.Idle), state.phase)
        assertEquals(ReleaseKind.Fixes, state.feed?.versions?.first()?.kind)
    }

    @Test
    fun aBrokenHighlightStillUpdatesAndKeepsTheRestOfTheNotes() = runTest {
        val highlights = """"highlights":[{"title":"Video","video":"a.mp4"},{"title":"Collections","text":"Hand-picked sets."}]"""
        val state = offered(feed("""{"version":"$Offered","date":"2026-11-02","kind":"feature",$highlights,"download":{$appImage}}"""))
        assertEquals(AppUpdatePhase.Ready(pkg, RestartStage.Idle), state.phase)
        assertEquals(listOf("Collections"), state.feed?.versions?.first()?.highlights?.map { it.title })
    }

    @Test
    fun notesThatCannotBeReadAtAllStillUpdate() = runTest {
        val state = offered(feed("""{"version":"$Offered","date":"November","kind":"feature","new":"many","download":{$appImage}}"""))
        assertEquals(AppUpdatePhase.Ready(pkg, RestartStage.Idle), state.phase)
        assertEquals(listOf(Running), state.feed?.versions?.map { it.version })
    }

    @Test
    fun unknownFieldsEverywhereStillUpdate() = runTest {
        val entry = """{"version":"$Offered","date":"2026-11-02","kind":"feature","channel":"stable","minimumOs":{"windows":"10"},
            "download":{$appImage,"flatpak":{"ref":"gg.hideouthq.Hideout"},"mirrors":["https://mirror.test"]},
            "new":[{"area":"Stats","text":"Better.","icon":"star"}],"highlights":[{"title":"T","text":"X","alt":"A","video":"a.mp4"}]}"""
        val text = feed(entry).replace("""{"schema":1,""", """{"schema":1,"signedBy":"release-2027","channels":["stable","beta"],""")
        val state = offered(text)
        assertEquals(AppUpdatePhase.Ready(pkg, RestartStage.Idle), state.phase)
        assertEquals(listOf(ReleaseEntry("Stats", "Better.")), state.feed?.versions?.first()?.new)
    }

    @Test
    fun aNewerSchemaPointsAtGitHub() = runTest {
        val state = offered(feed("""{"version":"$Offered","released":1793577600,"download":[{"format":"appimage"}]}""", schema = 2))
        assertEquals(AppUpdatePhase.GitHubOnly(Offered, NotesUrl), state.phase)
        assertEquals(false, state.lastCheckFailed)
    }

    @Test
    fun aNewerSchemaWithoutAValidSignatureIsStillRefused() = runTest {
        val text = feed("""{"version":"$Offered"}""", schema = 2)
        val state = offered(text, signature = sign("$text "))
        assertTrue(state.lastCheckFailed)
        assertEquals(AppUpdatePhase.Idle, state.phase)
    }
}
