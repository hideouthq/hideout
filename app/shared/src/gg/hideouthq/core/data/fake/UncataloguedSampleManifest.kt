package gg.hideouthq.core.data.fake

import gg.hideouthq.core.library.ArchiveIdentity
import gg.hideouthq.core.library.FileImage
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.InstalledSet
import gg.hideouthq.core.library.InstalledSource
import gg.hideouthq.core.library.LibraryManifest
import gg.hideouthq.core.library.ManifestRecord
import gg.hideouthq.core.library.OwnedDownload
import gg.hideouthq.core.library.PakNumber
import gg.hideouthq.core.library.Placement
import gg.hideouthq.core.library.ProfileBook
import gg.hideouthq.core.library.SetFile
import gg.hideouthq.core.library.SetOrigin
import gg.hideouthq.core.library.SubmissionIdentity
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.VersionPosition
import gg.hideouthq.core.library.VpkPart
import gg.hideouthq.core.library.relativePath

internal object UncataloguedSampleManifest {
    val root = GameRoot(SampleFolders.Steam)

    private const val Downloads = """C:\Users\Player\Downloads\Hideout"""

    private val lilith = ManifestRecord(
        installId = "lilith",
        gameRoot = root,
        title = "Lilith, the Hell Warden",
        placement = Placement.On(PakNumber(1)),
        sets = listOf(
            InstalledSet(
                source = InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, 551_201, 1_402_101, "a".repeat(32))),
                originalName = "lilith_hell_warden.zip",
                variant = "Default",
                origin = SetOrigin.Downloaded(ArchiveIdentity("""$Downloads\lilith_hell_warden.zip""", 86_000_000L, "a".repeat(64))),
                version = VersionPosition(1, 3),
                componentKey = "pak01_dir.vpk",
                files = listOf(SetFile(VpkPart.Directory, FileImage(900_000L, "a".repeat(64))), SetFile(VpkPart.Chunk(0), FileImage(85_100_000L, "f".repeat(64)))),
            ),
        ),
    )

    private val werecat = ManifestRecord(
        installId = "werecat",
        gameRoot = root,
        title = "Night of the Werecat",
        placement = Placement.On(PakNumber(2)),
        sets = listOf(
            InstalledSet(
                source = InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, 551_202, 1_402_102, "b".repeat(32))),
                originalName = "night_of_the_werecat.zip",
                variant = "Default",
                origin = SetOrigin.Downloaded(ArchiveIdentity("""$Downloads\night_of_the_werecat.zip""", 64_000_000L, "b".repeat(64))),
                version = VersionPosition(1, 2),
                componentKey = "pak02_dir.vpk",
                files = listOf(SetFile(VpkPart.Directory, FileImage(4_000_000L, "b".repeat(64))), SetFile(VpkPart.Chunk(0), FileImage(60_000_000L, "c".repeat(64)))),
            ),
        ),
    )

    private val faunCeleste = ManifestRecord(
        installId = "faun-celeste",
        gameRoot = root,
        title = "Faun Celeste",
        placement = Placement.Off,
        sets = listOf(
            InstalledSet(
                source = InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, 551_203, 1_402_103, "d".repeat(32))),
                originalName = "faun_celeste.zip",
                variant = "Default",
                origin = SetOrigin.Downloaded(ArchiveIdentity("""$Downloads\faun_celeste.zip""", 112_000_000L, "d".repeat(64))),
                version = VersionPosition(1, 1),
                componentKey = "pak03_dir.vpk",
                files = listOf(SetFile(VpkPart.Directory, FileImage(112_000_000L, "d".repeat(64)))),
            ),
        ),
    )

    private val damagedHudlock = ManifestRecord(
        installId = "hudlock",
        gameRoot = root,
        title = "HUDlock 2.0",
        placement = Placement.On(PakNumber(9)),
        sets = listOf(
            InstalledSet(
                source = InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, 551_204, 1_402_104, "e".repeat(32))),
                originalName = null,
                variant = null,
                origin = SetOrigin.Downloaded(ArchiveIdentity("""$Downloads\hudlock_2.zip""", 34_000_000L, "e".repeat(64))),
                version = null,
                componentKey = "pak04_dir.vpk",
                files = listOf(SetFile(VpkPart.Directory, FileImage(34_000_000L, "e".repeat(64)))),
            ),
        ),
    )

    private val intact = listOf(lilith, werecat, faunCeleste)

    private val downloads = listOf(
        OwnedDownload((lilith.sets.single().source as InstalledSource.Remote).identity, checkNotNull(lilith.sets.single().download)),
        OwnedDownload((faunCeleste.sets.single().source as InstalledSource.Remote).identity, checkNotNull(faunCeleste.sets.single().download)),
        OwnedDownload(SubmissionIdentity(SubmissionKind.Mod, 551_240, 1_402_240, null), ArchiveIdentity("""$Downloads\gilded_seven_uninstalled.zip""", 212_000_000L, "c".repeat(64))),
    )

    fun manifest(config: FakeConfig): LibraryManifest = when (config.seed) {
        LibrarySeed.Sample -> (intact + damagedHudlock).let { records ->
            LibraryManifest(records = records, profiles = ProfileBook.main(records).withOrder(root, records.map { it.installId }), downloads = downloads)
        }
        LibrarySeed.Empty -> LibraryManifest()
    }

    fun files(config: FakeConfig): InMemoryGameFiles = InMemoryGameFiles().apply {
        putBytes(root, InMemoryGameFiles.GameInfo, gameInfo(config.gameInfo, grimoire = config.foreign == ForeignSeed.Grimoire).encodeToByteArray())
        FakeManagerData(config.foreign).layOut(this, root)
        config.autoexec?.let { putBytes(root, InMemoryGameFiles.Autoexec, it.encodeToByteArray()) }
        if (config.seed != LibrarySeed.Sample) return@apply
        for (record in intact) {
            record.files.forEach { file -> putFile(record.gameRoot, file.relativePath(record), file.sizeBytes, file.sha256) }
        }
        addAddonsEntry(root, "unmanaged_skin_dir.vpk")
        downloads.forEach { putArchive(it.archive.path, emptyList(), FileImage(it.archive.sizeBytes, it.archive.sha256)) }
    }

    private fun gameInfo(sample: SampleGameInfo, grimoire: Boolean): String {
        val addons = when (sample) {
            SampleGameInfo.HideoutBlock -> "\t\t\t// Hideout start\r\n\t\t\tGame\t\t\t\tcitadel/addons\r\n\t\t\t// Hideout end\r\n"
            SampleGameInfo.Rewritten -> ""
            SampleGameInfo.AnotherTool -> "\t\t\tGame\t\t\t\tcitadel/addons\r\n"
        }
        return "\"GameInfo\"\r\n{\r\n\tFileSystem\r\n\t{\r\n\t\tSearchPaths\r\n\t\t{\r\n\t\t\tGame_Language\t\tcitadel_*LANGUAGE*\r\n" +
            (if (grimoire) GrimoireMount else "") + addons + "\t\t\tGame\t\t\t\tcitadel\r\n\t\t\tGame\t\t\t\tcore\r\n\t\t}\r\n\t}\r\n}\r\n"
    }
}

fun sampleGameFiles(config: FakeConfig = FakeConfig()): InMemoryGameFiles = UncataloguedSampleManifest.files(config)

fun sampleLibraryManifest(config: FakeConfig = FakeConfig()): LibraryManifest = UncataloguedSampleManifest.manifest(config)

val SampleGameRoot: GameRoot get() = UncataloguedSampleManifest.root
