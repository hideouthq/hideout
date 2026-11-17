package gg.hideouthq.core.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class InstallPoliciesTest {
    @Test
    fun everyPakNumberedEntryInAddonsTakesItsNumber() {
        val addons = listOf(
            "pak01_dir.vpk",
            "PAK03_000.VPK",
            "pak4_dir.vpk",
            "pak05_dir.vpk.hideout-k2x7q",
            "pak06_dir.vpk",
            "pak08",
            "631628_pak07_dir.vpk",
            "set-3fa94c0d12be_dir.vpk",
            "readme.txt",
            "pak100_dir.vpk",
            "pak00_dir.vpk",
            "pakage.vpk",
        )
        assertEquals(setOf(1, 3, 4, 5, 6, 8), PakNumbers.taken(addons).map { it.value }.toSet())
    }

    @Test
    fun onlyABarePakDirectoryIsMountedUnderItsNumber() {
        assertEquals(PakNumber(7), PakNumbers.mounted("PAK07_DIR.VPK"))
        listOf("pak07_000.vpk", "631628_pak07_dir.vpk", "pak7_dir.vpk", "pak00_dir.vpk", "set-3fa94c0d12be_dir.vpk").forEach { assertEquals(null, PakNumbers.mounted(it), it) }
    }

    @Test
    fun aPakNumberIsOneOf01To99AndNamesItsSetsFiles() {
        assertEquals("pak07", PakNumber(7).stem)
        assertEquals(PakNumber(9), PakNumber(7) + 2)
        listOf(0, 100, -1).forEach { assertFailsWith<IllegalArgumentException>("$it") { PakNumber(it) } }
    }

    private fun entry(name: String, kind: EntryKind = EntryKind.File, size: Long? = 10, encrypted: Boolean = false, index: Int = 0) =
        ArchiveEntry(index, name, kind, size, encrypted)

    private fun screen(vararg entries: ArchiveEntry, limits: UnpackLimits = UnpackLimits()) =
        screenArchive(entries.mapIndexed { i, e -> e.copy(index = i) }, limits)

    private fun rejection(vararg entries: ArchiveEntry, limits: UnpackLimits = UnpackLimits()): ArchiveRejection =
        assertIs<Screening.Rejected>(screen(*entries, limits = limits)).rejection

    @Test
    fun onlyVpksAreChosenAndTheirPathsAreNormalized() {
        val screened = assertIs<Screening.Accepted>(
            screen(entry("Mod/", EntryKind.Directory), entry("Mod/./pak01_dir.vpk"), entry("readme.txt"), entry("Mod\\Chunks\\pak01_000.VPK"), entry("Mod/pak01_dir.vpk.bak")),
        )
        assertEquals(listOf("Mod/pak01_dir.vpk" to 1, "Mod/Chunks/pak01_000.VPK" to 3), screened.vpks.map { it.path to it.index })
        assertEquals(40L, screened.declaredBytes)
    }

    @Test
    fun namesAreOnlyLabelsSoNoneRefusesTheArchive() {
        val screened = assertIs<Screening.Accepted>(
            screen(entry("../pak01_dir.vpk"), entry("readme/../../escape.txt"), entry("C:\\\\x\\pak02_dir.vpk"), entry("/etc/a\u0000b.vpk"), entry("")),
        )
        assertEquals(listOf("../pak01_dir.vpk", "C:/x/pak02_dir.vpk", "etc/a\u0000b.vpk"), screened.vpks.map { it.path })
    }

    @Test
    fun linksEncryptionAndUnknownEntryTypesRefuseTheArchive() {
        assertEquals(ArchiveRejection(RejectionReason.Link, "pak01_dir.vpk"), rejection(entry("pak01_dir.vpk", kind = EntryKind.Link)))
        assertEquals(ArchiveRejection(RejectionReason.Link, "shortcut"), rejection(entry("pak01_dir.vpk"), entry("shortcut", kind = EntryKind.Link)))
        assertEquals(ArchiveRejection(RejectionReason.Encrypted, "notes.txt"), rejection(entry("pak01_dir.vpk"), entry("notes.txt", encrypted = true)))
        assertEquals(ArchiveRejection(RejectionReason.UnsupportedEntry, "dev"), rejection(entry("pak01_dir.vpk"), entry("dev", kind = EntryKind.Other)))
    }

    @Test
    fun namesThatCollideByCaseOrAsFileAndFolderAreRefused() {
        assertEquals(ArchiveRejection(RejectionReason.DuplicateName, "MOD/PAK01_dir.vpk"), rejection(entry("mod/pak01_dir.vpk"), entry("MOD/PAK01_dir.vpk")))
        assertEquals(ArchiveRejection(RejectionReason.DuplicateName, "a/pak01_dir.vpk"), rejection(entry("a"), entry("a/pak01_dir.vpk")))
        assertIs<Screening.Accepted>(screen(entry("a/", EntryKind.Directory), entry("A", EntryKind.Directory), entry("a/pak01_dir.vpk")), "folders may repeat")
    }

    @Test
    fun declaredSizesAreCappedBeforeAnythingIsWritten() {
        val limits = UnpackLimits(maxEntries = 3, maxVpkBytes = 100, maxTotalBytes = 150)
        assertEquals(ArchiveRejection(RejectionReason.TooLarge, "pak01_dir.vpk"), rejection(entry("pak01_dir.vpk", size = 101), limits = limits))
        assertEquals(ArchiveRejection(RejectionReason.TooLarge, "b.bin"), rejection(entry("pak01_dir.vpk", size = 100), entry("b.bin", size = 51), limits = limits))
        assertEquals(ArchiveRejection(RejectionReason.TooManyEntries, null), rejection(entry("a"), entry("b"), entry("c"), entry("pak01_dir.vpk"), limits = limits))
        assertEquals(ArchiveRejection(RejectionReason.Corrupt, "pak01_dir.vpk"), rejection(entry("pak01_dir.vpk", size = -2)))
        assertIs<Screening.Accepted>(screen(entry("pak01_dir.vpk", size = null)), "an undeclared size is capped while streaming")
    }

    @Test
    fun anArchiveWithoutVpksIsRefusedForWhatItHoldsInstead() {
        assertEquals(ArchiveRejection(RejectionReason.NoVpk, null), rejection(entry("readme.txt"), entry("folder/", EntryKind.Directory), entry("preview.png")))
        assertEquals(ArchiveRejection(RejectionReason.NoVpkConfig, null), rejection(entry("Config/gameinfo.gi"), entry("readme.txt"), entry("extra/font.ttf")))
        assertEquals(ArchiveRejection(RejectionReason.NoVpkCursors, null), rejection(entry("cursors/arrow.bmp"), entry("cursors/busy.ani"), entry("preview.jpg")))
        assertEquals(ArchiveRejection(RejectionReason.NoVpkLooseFiles, null), rejection(entry("models/ivy.vmdl_c"), entry("cursors/arrow.bmp")))
        assertEquals(ArchiveRejection(RejectionReason.NestedArchive, null), rejection(entry("inner.zip"), entry("readme.txt")))
        assertEquals(ArchiveRejection(RejectionReason.NestedArchive, null), rejection(entry("Mod/inner.7z"), entry("__MACOSX/Mod/._inner.7z")))
        assertEquals(ArchiveRejection(RejectionReason.NoVpk, null), rejection(entry("__MACOSX/._readme.txt"), entry(".DS_Store"), entry("readme.txt")))
    }

    @Test
    fun whatAMacAddsBesideTheFilesIsPassedOver() {
        val screened = assertIs<Screening.Accepted>(screen(entry("mod.vpk"), entry("__MACOSX/._mod.vpk"), entry("._mod.vpk"), entry("__MACOSX/.DS_Store"), entry(".DS_Store")))
        assertEquals(listOf("mod.vpk"), screened.vpks.map { it.path })
    }

    @Test
    fun readmesAndTextFilesBesideTheVpksAreNotesUpToTheLimit() {
        val screened = assertIs<Screening.Accepted>(screen(
            entry("pak01_dir.vpk"), entry("README"), entry("docs/Install.MD"), entry("credits.txt"), entry("notes.txt"), entry("more.txt"), entry("preview.png"),
            limits = UnpackLimits(maxNotes = 4),
        ))
        assertEquals(listOf("README", "docs/Install.MD", "credits.txt", "notes.txt"), screened.notes.map { it.path })
    }

    @Test
    fun aNotesTextKeepsItsLinesInEitherEncodingAndSaysWhenItWasCut() {
        assertEquals("Name this pak01.\nThen restart.", noteText("\uFEFFName this pak01.\r\nThen restart.\u0007\r\n".encodeToByteArray(), whole = true))
        assertEquals("Rename it", noteText(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Rename it".toByteArray(Charsets.UTF_16LE), whole = true))
        assertEquals("Rename it", noteText(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "Rename it".toByteArray(Charsets.UTF_16BE), whole = true))
        assertEquals("The first part…", noteText("The first part".encodeToByteArray(), whole = false))
        assertEquals(null, noteText(byteArrayOf(0, 0, 32, 10), whole = true), "an empty readme is no note")
    }

    @Test
    fun onlyTheGamesOwnExecutableAsArgvZeroOrProcessNameIsDeadlock() {
        assertEquals(KnownProcess.Game, knownProcess(argv0 = """S:\common\Deadlock\game\bin\win64\deadlock.exe""", name = null))
        assertEquals(KnownProcess.Game, knownProcess(argv0 = "/games/Deadlock/game/bin/win64/Deadlock.EXE", name = null))
        assertEquals(KnownProcess.Game, knownProcess(argv0 = null, name = "deadlock.exe"))
        assertEquals(null, knownProcess(argv0 = """c:\windows\system32\steam.exe""", name = "steam.exe"))
        assertEquals(null, knownProcess(argv0 = "/usr/bin/python3", name = "python3"))
        assertEquals(null, knownProcess(argv0 = """S:\common\Deadlock\game\bin\win64\deadlock.exe.bak""", name = null))
        assertEquals(null, knownProcess(argv0 = "", name = ""))
    }

    @Test
    fun everyBuildOfTheOtherManagersIsKnownOnLinuxAndWindows() {
        val dmm = KnownProcess.OtherManager(Manager.DeadlockModManager)
        val grimoire = KnownProcess.OtherManager(Manager.Grimoire)
        assertEquals(dmm, knownProcess(argv0 = "/usr/bin/deadlock-mod-manager", name = "deadlock-mod-ma"), "native")
        assertEquals(dmm, knownProcess(argv0 = "/app/share/deadlock-mod-manager/deadlock-mod-manager", name = "deadlock-mod-ma"), "Flatpak")
        assertEquals(dmm, knownProcess(argv0 = null, name = "deadlock-mod-ma"), "the kernel's 15-byte name alone")
        assertEquals(dmm, knownProcess(argv0 = "/usr/bin/deadlock-modmanager", name = "deadlock-modman"), "AUR")
        assertEquals(dmm, knownProcess(argv0 = """C:\Program Files\Deadlock Mod Manager\deadlock-mod-manager.exe""", name = null), "Windows")
        assertEquals(dmm, knownProcess(argv0 = """C:\Program Files\Deadlock Mod Manager\Deadlock Mod Manager.exe""", name = null), "Windows installer name")
        assertEquals(dmm, knownProcess(argv0 = """D:\Tools\Deadlock Mod Manager_1.4.2_x64-portable.exe""", name = null), "Windows portable")
        assertEquals(grimoire, knownProcess(argv0 = "/opt/Grimoire/grimoire", name = "grimoire"), "deb and rpm")
        assertEquals(grimoire, knownProcess(argv0 = "/app/grimoire/grimoire", name = "grimoire"), "Flatpak")
        assertEquals(grimoire, knownProcess(argv0 = "/home/u/Apps/Grimoire-2.3.1.AppImage", name = "Grimoire-2.3.1."), "the AppImage file")
        assertEquals(grimoire, knownProcess(argv0 = "/tmp/.mount_GrimoiAbC123/grimoire", name = "grimoire"), "inside the AppImage")
        assertEquals(grimoire, knownProcess(argv0 = """C:\Users\u\AppData\Local\Programs\Grimoire\Grimoire.exe""", name = null), "Windows")
        assertEquals(null, knownProcess(argv0 = "/usr/bin/bwrap", name = "bwrap"), "Flatpak's sandbox names the manager only among its arguments")
        assertEquals(null, knownProcess(argv0 = "/usr/bin/grimoire-notes", name = "grimoire-notes"))
    }

    private fun names(title: String, vararg files: Pair<String?, String>): List<String?> {
        val named = versionNames(title, files.mapIndexed { index, (description, name) -> VersionSource(index + 1L, description, name) })
        return files.indices.map { named[it + 1L] }
    }

    @Test
    fun aVersionIsNamedByItsDescriptionOrItsCleanedFileName() {
        assertEquals(
            listOf("Blue hair", "Ivy pink", "Bg ivy", "Nicole over pocket", "Archive", "V2 final"),
            names("", "  Blue hair " to "x.zip", null to "ivy_pink_cfda7.zip", "" to "bg_ivy.zip", null to "nicole_over_pocket.7z", null to "archive", null to "v2_final_abcde.rar"),
        )
    }

    @Test
    fun filesThatShareADescriptionFallBackToTheirOwnFileNamesInsteadOfCollidingOnIt() {
        assertEquals(
            listOf("V2 audio", "No fur V2 audio", "V2", "No fur V2"),
            names(
                "Black Beard Dynamo",
                "THE MODEL + THE AUDIO" to "black_beard_dynamo_v2_audio.zip",
                "THE MODEL + THE AUDIO" to "black_beard_dynamo_no_fur_v2_audio.zip",
                "JUST THE MODEL" to "black_beard_dynamo_v2.zip",
                "JUST THE MODEL" to "black_beard_dynamo_no_fur_v2.zip",
            ),
        )
    }

    @Test
    fun theSameFallbackNamesAnyOtherModsFilesByTheWordsItsOwnTitleDoesNotAlreadySay() {
        assertEquals(
            listOf("V2 red", "V2 blue"),
            names("Crimson Falcon Wings", null to "crimson_falcon_wings_v2_red.zip", null to "crimson_falcon_wings_v2_blue.zip"),
            "a qualifier every file shares but the title never says is kept",
        )
    }

    @Test
    fun filenameCleanupPreservesUnicodeVersionsAndDistinctions() {
        val files = listOf("ミク.zip", "pack_v1.2.zip", "pack_v1_2.zip", "hero_blue.zip", "hero-blue.zip")
            .mapIndexed { index, name -> VersionSource(index.toLong(), null, name) }
        assertEquals(listOf("ミク", "Pack V1.2", "Pack V1 2", "hero_blue.zip", "hero-blue.zip"), versionNames("Hero", files).values.toList())
        assertEquals(versionNames("Hero", files), versionNames("Hero", files.reversed()))
        assertEquals(listOf("Blue alt", "Red extra"), names("Red Blue", null to "blue_alt.zip", null to "red_extra.zip"))
        assertEquals(listOf("Red", "Blue"), names("Demo", " MODEL " to "demo_red.zip", "model" to "demo_blue.zip"))
    }

    @Test
    fun aUniqueDescriptionWinsOverTheCleanedFileNameButNeverRepeatsTheTitleItself() {
        assertEquals(
            listOf("Default", "Werewolf Transform", "Lilith hell warden"),
            names("Lilith, the Hell Warden", "Default" to "lilith_hell_warden.zip", "Werewolf Transform" to "lilith_werewolf_transform.zip", null to "lilith_hell_warden_cfda7.zip"),
            "stripping every title word would leave nothing, so none are stripped",
        )
    }
}
