package gg.hideouthq.library

import gg.hideouthq.core.data.CitadelFile
import gg.hideouthq.core.data.EntryState
import gg.hideouthq.core.data.LinkResult
import gg.hideouthq.core.data.Removal
import gg.hideouthq.core.library.CitadelPath
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.Sha256
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NioGameFilesTest {
    private val home: Path = Files.createTempDirectory(Files.createDirectories(Path.of("build", "game-files-tests")), "home").toRealPath()
    private val game = home.resolve("Deadlock")
    private val root = GameRoot(game.toString())
    private val citadel = game.resolve("game/citadel")
    private val files = NioGameFiles()
    private val bytes = ByteArray(2_048) { (it % 13).toByte() }
    private val image = Sha256.image(bytes)

    @AfterTest
    fun clean() = deleteTree(home)

    private fun at(path: String) = CitadelFile(root, CitadelPath(path))

    private fun put(path: String, content: ByteArray = bytes): Path = citadel.resolve(path).also {
        it.parent.createDirectories()
        it.writeBytes(content)
    }

    @Test
    fun anotherManagersNameBecomesAHideoutNameOfTheSameFileInAddonsOrItsOwnOffFolder() = runBlocking {
        val parked = put("addons/637275_Percentage Rename.vpk")
        val profile = put("addons/profile_1726_ranked/pak01_dir.vpk")

        assertEquals(LinkResult.Linked, files.link(at("addons/637275_Percentage Rename.vpk"), at("hideout_off/mod-637275/set-3fa94c0d12be_dir.vpk")))
        assertEquals(LinkResult.Linked, files.link(at("addons/profile_1726_ranked/pak01_dir.vpk"), at("addons/pak18_dir.vpk")))

        assertTrue(Files.isSameFile(parked, citadel.resolve("hideout_off/mod-637275/set-3fa94c0d12be_dir.vpk")))
        assertTrue(Files.isSameFile(profile, citadel.resolve("addons/pak18_dir.vpk")))
        assertEquals(EntryState.Present(image, twin = true), files.inspect(at("addons/pak18_dir.vpk"), at("addons/profile_1726_ranked/pak01_dir.vpk")))
        assertEquals(LinkResult.Taken, files.link(at("addons/637275_Percentage Rename.vpk"), at("addons/pak18_dir.vpk")))
    }

    @Test
    fun onlyAHideoutNameDirectlyInAddonsOrAModsOffFolderIsEverCreated() = runBlocking {
        put("addons/637275_pak06_dir.vpk")
        val refused = listOf(
            "addons/my_skin.vpk", "addons/pak1_dir.vpk", "addons/.disabled/pak01_dir.vpk", "addons/profile_x/pak01_dir.vpk", "grimoire/pak05_dir.vpk", "hideout_off/mod-1/x_dir.vpk",
            "hideout_off/pak01_dir.vpk", "pak01_dir.vpk", "hideout_off/mod-1/pak01_dir.vpk", "addons/set-3fa94c0d12be_dir.vpk",
        )
        for (target in refused) {
            assertIs<LinkResult.Failed>(files.link(at("addons/637275_pak06_dir.vpk"), at(target)), target)
            assertTrue(!citadel.resolve(target).exists(), "$target was not made")
        }
        assertTrue(!citadel.resolve("addons/.disabled").exists() && !citadel.resolve("addons/profile_x").exists() && !citadel.resolve("grimoire").exists())
    }

    @Test
    fun aLinkOrJunctionOnTheWayIsNeverFollowed() = runBlocking {
        val elsewhere = home.resolve("replays").createDirectories()
        elsewhere.resolve("pak01_dir.vpk").writeBytes(bytes)
        citadel.resolve("addons").createDirectories()
        Files.createSymbolicLink(citadel.resolve("addons/replays"), elsewhere)

        assertIs<LinkResult.Failed>(files.link(at("addons/replays/pak01_dir.vpk"), at("addons/pak20_dir.vpk")))
        assertIs<EntryState.Unreadable>(files.inspect(at("addons/replays/pak01_dir.vpk"), at("addons/replays/pak01_dir.vpk")))
        assertIs<Removal.Failed>(files.removeTwin(at("addons/replays/pak01_dir.vpk"), at("addons/pak20_dir.vpk"), image))
        assertEquals(EntryState.NotPlain, files.inspect(at("addons/replays"), at("addons/replays")))
        assertContentEquals(bytes, elsewhere.resolve("pak01_dir.vpk").readBytes())
        assertTrue(!citadel.resolve("addons/pak20_dir.vpk").exists())
    }

    @Test
    fun backupsAndStagingFoldersAreNeverEntered() = runBlocking {
        val kept = listOf("addons-backups/addons-backup-2026-09-22/pak09_dir.vpk", "addons/.dmm-clear/pak03_dir.vpk", "addons/.DMM-reorder/pak04_dir.vpk", "addons-backups/.dmm.json")
        kept.forEach { put(it) }
        for (path in kept) {
            assertIs<EntryState.Unreadable>(files.inspect(at(path), at(path)), path)
            assertIs<LinkResult.Failed>(files.link(at(path), at("addons/pak30_dir.vpk")), path)
            assertIs<Removal.Failed>(files.removeTwin(at(path), at(path), image), path)
            assertIs<Removal.Failed>(files.removeExact(at(path), image), path)
            assertContentEquals(bytes, citadel.resolve(path).readBytes(), path)
        }
        assertTrue(!citadel.resolve("addons/pak30_dir.vpk").exists())
    }

    @Test
    fun aManagersProfileFolderGoesWithItsLastFileAndNoOtherFolderDoes() = runBlocking {
        put("addons/profile_x/pak01_dir.vpk")
        put("addons/profile_x/.dmm.json")
        put("addons/profile_y/pak01_dir.vpk")
        put("addons/profile_y/notes.txt")
        put("addons2/profile_z/pak01_dir.vpk")
        put("addons/pak01_dir.vpk")

        assertEquals(Removal.Removed, files.removeExact(at("addons/profile_x/pak01_dir.vpk"), image))
        assertTrue(citadel.resolve("addons/profile_x").exists(), "a folder that still holds a file stays")
        assertEquals(Removal.Removed, files.removeExact(at("addons/profile_x/.dmm.json"), image))
        assertTrue(!citadel.resolve("addons/profile_x").exists())
        assertEquals(Removal.Absent, files.removeExact(at("addons/profile_x/.dmm.json"), image), "asking again finds nothing")

        assertEquals(Removal.Removed, files.removeExact(at("addons/profile_y/pak01_dir.vpk"), image))
        assertTrue(citadel.resolve("addons/profile_y/notes.txt").exists(), "what the import does not know of stays, with its folder")

        assertEquals(Removal.Removed, files.removeExact(at("addons2/profile_z/pak01_dir.vpk"), image))
        assertTrue(!citadel.resolve("addons2/profile_z").exists(), "a profile's overflow folder goes too")

        assertEquals(Removal.Removed, files.removeExact(at("addons/pak01_dir.vpk"), image))
        assertEquals(Removal.Removed, files.removeExact(at("addons/profile_y/notes.txt"), image))
        assertTrue(citadel.resolve("addons").exists(), "Hideout mounts addons, so it stays even empty")
    }

    @Test
    fun aManifestGoesOnlyWhileItHoldsItsCopiedBytes() = runBlocking {
        val manifest = "{\"version\":3,\"mods\":{}}".encodeToByteArray()
        val manifestImage = Sha256.image(manifest)
        val written = put("addons/.dmm.json", manifest)

        assertIs<Removal.Kept>(files.removeExact(at("addons/.dmm.json"), image))
        assertTrue(written.exists())
        assertEquals(Removal.Removed, files.removeExact(at("addons/.dmm.json"), manifestImage))
        assertEquals(Removal.Absent, files.removeExact(at("addons/.dmm.json"), manifestImage))
    }
}
