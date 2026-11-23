package gg.hideouthq.nativepackage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.HexFormat
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalPathApi::class)
class BuildSecretsTest {
    private val root = createTempDirectory("build-secrets")
    private val localFile = root / "preview-build.properties"
    private val output = BuildSecretsOutput(root / "kotlin", root / "resources", root / "state")
    private val secrets = output.secrets("gg.example.App")
    private val source = root / "kotlin" / "gg" / "example" / "BuildMask.kt"
    private val master = "a-master-0123456789abcdef0123456789abcdef"
    private val url = "https://previews.example.test"
    private val invite = "an-invite-master-0123456789abcdef0123"

    @AfterTest
    fun cleanUp() = root.deleteRecursively()

    private fun write(vararg environment: Pair<String, String>, version: String = "0.3.0") =
        writeBuildSecretsFiles("gg.example.App", version, localFile, output, mapOf(*environment)::get)

    private fun names() = if (secrets.exists()) secrets.listDirectoryEntries().map { it.name }.sorted() else emptyList()

    private fun builtKey() = Regex("""byteArrayOf\(([-0-9, ]*)\)""").find(source.readText())!!.groupValues[1].split(", ").map { it.toByte() }.toByteArray()

    private fun unmasked(name: String) = mask(builtKey(), name, (secrets / name).readBytes()).decodeToString()

    @Test
    fun aBuildWithNoValuesStillCompilesAMaskAndDropsWhatAnEarlierBuildWrote() {
        write()
        assertEquals(emptyList(), names())
        assertContains(source.readText(), "package gg.example\n")
        assertEquals(32, builtKey().size)
        write(PreviewUrlVariable to url, PreviewMasterVariable to master, InviteMasterVariable to invite, PostHogKeyVariable to "phc_project")
        assertEquals(listOf("invite-key", "posthog-key", "preview-key", "preview-url"), names())
        write()
        assertEquals(emptyList(), names())
    }

    @Test
    fun whatTheAppUnmasksIsTheAddressTheKeysDerivedForThisVersionAndTheCaptureKey() {
        write(PreviewUrlVariable to url, PreviewMasterVariable to master, InviteMasterVariable to invite, PostHogKeyVariable to "phc_project")
        assertEquals(url, unmasked("preview-url"))
        assertEquals(releaseKey(master, "preview", "0.3.0"), unmasked("preview-key"))
        assertEquals(releaseKey(invite, "invite", "0.3.0"), unmasked("invite-key"))
        assertEquals("phc_project", unmasked("posthog-key"))
        val stored = names().map { String((secrets / it).readBytes(), Charsets.ISO_8859_1) }
        for (plain in listOf(url, "previews.example", master, invite, releaseKey(master, "preview", "0.3.0"), releaseKey(invite, "invite", "0.3.0"), "phc_project")) {
            assertTrue(stored.none { plain in it }, plain)
        }
    }

    @Test
    fun theKeyIsBoundToTheVersion() {
        write(PreviewUrlVariable to url, PreviewMasterVariable to master)
        val first = unmasked("preview-key")
        write(PreviewUrlVariable to url, PreviewMasterVariable to master, version = "0.3.1")
        assertNotEquals(first, unmasked("preview-key"))
        assertEquals(releaseKey(master, "preview", "0.3.1"), unmasked("preview-key"))
    }

    @Test
    fun theInviteKeyNeedsNoPreviewHostAndIsBoundToTheVersion() {
        write(InviteMasterVariable to invite)
        assertEquals(listOf("invite-key"), names())
        write(InviteMasterVariable to invite, version = "0.3.1")
        assertEquals(releaseKey(invite, "invite", "0.3.1"), unmasked("invite-key"))
        assertContains(assertFailsWith<IllegalStateException> { write(InviteMasterVariable to "short") }.message.orEmpty(), "shorter than 32")
        assertContains(assertFailsWith<IllegalStateException> { write(InviteMasterVariable to invite, version = "1.4") }.message.orEmpty(), "like 1.4.0")
    }

    @Test
    fun theDerivedKeysMatchTheSharedVectorsTheWorkerChecks() {
        val file = File(checkNotNull(System.getProperty("preview.signing")) { "Run with ./kotlin test, which sets preview.signing from module.yaml" })
        val builds = Json.parseToJsonElement(file.readText()).jsonObject.getValue("builds").jsonArray.map { it.jsonObject }
        assertTrue(builds.isNotEmpty())
        for (v in builds) {
            val text = { name: String -> v.getValue(name).jsonPrimitive.content }
            assertEquals(text("key"), releaseKey(text("master"), "preview", text("build")), text("build"))
        }
    }

    @Test
    fun anUnchangedBuildKeepsItsMaskAndAChangedOneGetsANewOne() {
        write(PreviewUrlVariable to url, PreviewMasterVariable to master)
        val key = builtKey()
        val written = source.getLastModifiedTime()
        write(PreviewUrlVariable to url, PreviewMasterVariable to master)
        assertContentEquals(key, builtKey())
        assertEquals(written, source.getLastModifiedTime())
        write(PreviewUrlVariable to url, PreviewMasterVariable to master, version = "0.4.0")
        assertFalse(key.contentEquals(builtKey()))
    }

    @Test
    fun theEnvironmentComesBeforeTheLocalFile() {
        localFile.writeText("$PreviewUrlVariable=https://file.example.test\n$PreviewMasterVariable=file-master-0123456789abcdef0123456789\n")
        write()
        assertEquals("https://file.example.test", unmasked("preview-url"))
        write(PreviewUrlVariable to " https://environment.example.test ")
        assertEquals("https://environment.example.test", unmasked("preview-url"))
        assertEquals(releaseKey("file-master-0123456789abcdef0123456789", "preview", "0.3.0"), unmasked("preview-key"))
    }

    @Test
    fun anAddressWithoutAMasterOrTheOtherWayRoundStopsTheBuild() {
        for (environment in listOf(arrayOf(PreviewUrlVariable to url), arrayOf(PreviewMasterVariable to master))) {
            assertContains(assertFailsWith<IllegalStateException> { write(*environment) }.message.orEmpty(), "or neither")
        }
        assertContains(assertFailsWith<IllegalStateException> { write(PreviewUrlVariable to url, PreviewMasterVariable to "short") }.message.orEmpty(), "shorter than 32")
        assertContains(assertFailsWith<IllegalStateException> { write(PreviewUrlVariable to url, PreviewMasterVariable to master, version = "1.4") }.message.orEmpty(), "like 1.4.0")
        assertFalse(secrets.exists())
    }

    @Test
    fun invalidCaptureKeysFailWithoutIncludingTheirValue() {
        for (key in listOf("phx_personal", "phc_project\nversion=other", "phc_")) {
            assertEquals("$PostHogKeyVariable must be a PostHog project capture key", assertFailsWith<IllegalStateException> { write(PostHogKeyVariable to key) }.message)
        }
    }

    @Test
    fun theMaskMatchesTheKnownAnswerTheAppUnmasks() {
        assertEquals(
            "0c8de5ff7f34cab65c721b5463a7ecbb05626cc4c011a28c663668fdc20db3ae8ae94f5b57657a3df082c2854d907635b387d5ed658b3988",
            HexFormat.of().formatHex(mask(ByteArray(32) { it.toByte() }, "preview-url", "https://previews.example.test:8443/a-path-past-one-block".encodeToByteArray())),
        )
    }
}
