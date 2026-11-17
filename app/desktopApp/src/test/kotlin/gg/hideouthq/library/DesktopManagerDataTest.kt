package gg.hideouthq.library

import gg.hideouthq.core.data.BundleOpen
import gg.hideouthq.core.data.fake.FakeVpk
import gg.hideouthq.core.library.VpkRead
import gg.hideouthq.preview.sha256Hex
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class DesktopManagerDataTest {
    private val home: Path = Files.createTempDirectory(Files.createDirectories(Path.of("build", "manager-data-tests")), "home").toRealPath()

    @AfterTest
    fun clean() = deleteTree(home)

    private fun put(path: Path, bytes: ByteArray, modified: Long? = null): Path = path.also {
        it.parent.createDirectories()
        it.writeBytes(bytes)
        modified?.let { at -> Files.setLastModifiedTime(it, FileTime.fromMillis(at)) }
    }

    @Test
    fun theMostRecentlyWrittenDmmStateOnLinuxIsRead() = runBlocking {
        val data = home.resolve("xdg-data")
        put(data.resolve("dev.stormix.deadlock-mod-manager/state.json"), "native".encodeToByteArray(), modified = 1_000)
        put(home.resolve(".var/app/dev.stormix.deadlock-mod-manager/data/dev.stormix.deadlock-mod-manager/state.json"), "flatpak".encodeToByteArray(), modified = 3_000)
        put(home.resolve(".var/app/dev.stormix.deadlock-mod-manager/config/dev.stormix.deadlock-mod-manager/state.json"), "flatpak config".encodeToByteArray(), modified = 2_000)
        put(home.resolve(".local/share/dev.stormix.deadlock-mod-manager/state.json"), "unused default".encodeToByteArray(), modified = 9_000)
        val managers = DesktopManagerData(home, mapOf("XDG_DATA_HOME" to data.toString())::get, windows = false)
        assertEquals("flatpak", managers.dmmState()?.decodeToString(), "XDG_DATA_HOME replaces ~/.local/share")
    }

    @Test
    fun onWindowsDmmIsInAppDataAndLocalAppDataAndGrimoireInAppData() = runBlocking {
        val roaming = home.resolve("Roaming")
        val local = home.resolve("Local")
        put(roaming.resolve("dev.stormix.deadlock-mod-manager/state.json"), "roaming".encodeToByteArray(), modified = 5_000)
        put(local.resolve("dev.stormix.deadlock-mod-manager/state.json"), "local".encodeToByteArray(), modified = 6_000)
        put(roaming.resolve("grimoire/mod-metadata.json"), "{}".encodeToByteArray())
        put(home.resolve(".config/grimoire/profiles.json"), "linux".encodeToByteArray())
        val managers = DesktopManagerData(home, mapOf("APPDATA" to roaming.toString(), "LOCALAPPDATA" to local.toString())::get, windows = true)
        assertEquals("local", managers.dmmState()?.decodeToString())
        val grimoire = managers.grimoire(null)
        assertContentEquals("{}".encodeToByteArray(), grimoire?.metadata)
        assertNull(grimoire?.profiles)
    }

    @Test
    fun grimoireIsInTheXdgConfigFolderOrTheFolderTheUserPointsAt() = runBlocking {
        val config = home.resolve("xdg-config")
        put(config.resolve("grimoire/profiles.json"), "[]".encodeToByteArray())
        put(home.resolve("elsewhere/grimoire-data/mod-metadata.json"), "{\"a\":{}}".encodeToByteArray())
        val managers = DesktopManagerData(home, mapOf("XDG_CONFIG_HOME" to config.toString())::get, windows = false)
        assertContentEquals("[]".encodeToByteArray(), managers.grimoire(null)?.profiles)
        assertContentEquals("{\"a\":{}}".encodeToByteArray(), managers.grimoire(home.resolve("elsewhere/grimoire-data").toString())?.metadata)
        assertNull(managers.grimoire(home.resolve("elsewhere").toString()), "a folder holding none of Grimoire's files is not Grimoire's")
        assertNull(DesktopManagerData(home, emptyMap<String, String>()::get, windows = false).grimoire(null))
    }

    @Test
    fun flatpakGrimoireIsInItsOwnConfigFolderAndTheMostRecentlyWrittenGrimoireLeads() = runBlocking {
        val flatpak = home.resolve(".var/app/com.grimoiremods.Grimoire/config/grimoire")
        put(flatpak.resolve("mod-metadata.json"), "flatpak".encodeToByteArray(), modified = 2_000)
        put(flatpak.resolve("profiles.json"), "[\"flatpak\"]".encodeToByteArray(), modified = 2_000)
        val managers = DesktopManagerData(home, emptyMap<String, String>()::get, windows = false)
        assertContentEquals("flatpak".encodeToByteArray(), managers.grimoire(null)?.metadata)

        val native = home.resolve(".config/grimoire")
        put(native.resolve("mod-metadata.json"), "native".encodeToByteArray(), modified = 1_000)
        val older = managers.grimoire(null)
        assertContentEquals("flatpak".encodeToByteArray(), older?.metadata)
        assertContentEquals("[\"flatpak\"]".encodeToByteArray(), older?.profiles)

        put(native.resolve("profiles.json"), "[\"native\"]".encodeToByteArray(), modified = 3_000)
        val newer = managers.grimoire(null)
        assertContentEquals("native".encodeToByteArray(), newer?.metadata, "both files come from the same Grimoire")
        assertContentEquals("[\"native\"]".encodeToByteArray(), newer?.profiles)
    }

    @Test
    fun aBundleIsItsFolderOrItsManifestAndItsFilesNeverLeaveIt() = runBlocking {
        val bundle = home.resolve("deadlock-mods-export-20261021")
        val manifest = put(bundle.resolve("mod-interchange.json"), "{\"format\":\"deadlock-mod-interchange\"}".encodeToByteArray())
        val vpk = FakeVpk.directory(chunkReads = mapOf(0 to 5L), seed = 3)
        put(bundle.resolve("files/0000-a/a_dir.vpk"), vpk)
        put(home.resolve("secret_dir.vpk"), vpk)
        Files.createSymbolicLink(bundle.resolve("files/linked"), home)
        val managers = DesktopManagerData(home, emptyMap<String, String>()::get, windows = false)
        for (picked in listOf(bundle, manifest)) {
            val found = assertIs<BundleOpen.Found>(managers.bundle(picked.toString()))
            assertEquals(bundle.toString(), found.folder)
            assertContentEquals(Files.readAllBytes(manifest), found.manifest)
        }
        assertIs<BundleOpen.Missing>(managers.bundle(home.resolve("nothing").toString()))
        val read = managers.bundleVpk(bundle.toString(), "files/0000-a/a_dir.vpk")
        assertEquals(bundle.resolve("files/0000-a/a_dir.vpk").toString(), read?.location)
        assertEquals(sha256Hex(vpk), read?.sha256)
        assertEquals(mapOf(0 to 5L), assertIs<VpkRead.Parsed>(read?.directory).chunkExtents)
        assertNull(managers.bundleVpk(bundle.toString(), "../secret_dir.vpk"))
        assertNull(managers.bundleVpk(bundle.toString(), "files/linked/secret_dir.vpk"), "a link inside a bundle is never followed")
    }
}
