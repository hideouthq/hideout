package gg.hideouthq.core.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManifestCodecTest {
    private val record = oneSetRecord(
        installId = "install-1",
        gameRoot = GameRoot("/games/Deadlock"),
        source = InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, 721_908, 1_829_854, "0332f45e5015186cb47219853f499a25")),
        title = "Nicole over Pocket",
        variant = "Default",
        archive = ArchiveIdentity("/downloads/nicole_over_pocket.zip", 30_396_766L, "a".repeat(64)),
        number = 5,
        files = listOf(SetFile(VpkPart.Directory, FileImage(1_000L, "b".repeat(64))), SetFile(VpkPart.Chunk(0), FileImage(2_000L, "c".repeat(64)))),
    )

    private val hex64 = "a".repeat(64)

    private fun fileJson(part: String = "dir") = """{"part":"$part","sizeBytes":1,"sha256":"$hex64"}"""

    private fun orderJson(gameRoot: String = "/g", ids: List<String> = listOf("i")) = """{"gameRoot":"$gameRoot","ids":[${ids.joinToString(",") { "\"$it\"" }}]}"""

    private fun profilesJson(enabled: List<String>, orders: List<String>) =
        """{"active":"main","profiles":[{"id":"main","name":"Main","colour":"Sage","enabled":[${enabled.joinToString(",") { "\"$it\"" }}],"orders":[${orders.joinToString(",")}]}]}"""

    private fun recordJson(
        installId: String = "i",
        gameRoot: String = "/g",
        submissionKind: String = "Mod",
        submissionId: String = "721908",
        fileId: String = "1829854",
        fileMd5: String = "0332f45e5015186cb47219853f499a25",
        archivePath: String = "/downloads/a.zip",
        archiveSha256: String = hex64,
        first: Int? = 5,
        files: String = fileJson(),
        twoSets: Boolean = false,
    ): String {
        val placement = first?.let { """{"type":"On","first":$it}""" } ?: """{"type":"Off"}"""
        val another = """,{"submissionKind":"$submissionKind","submissionId":$submissionId,"fileId":${fileId}0,"variant":null,"origin":{"type":"Imported","sizeBytes":1,
            "sha256":"$hex64"},"componentKey":"b/pak01_dir.vpk","files":[${fileJson()}]}"""
        return """{"installId":"$installId","gameRoot":"$gameRoot","title":"T","placement":$placement,"sets":[{"submissionKind":"$submissionKind",
            "submissionId":$submissionId,"fileId":$fileId,"fileMd5":"$fileMd5","variant":null,"origin":{"type":"Downloaded","archivePath":"$archivePath","archiveSizeBytes":1,
            "archiveSha256":"$archiveSha256"},"componentKey":"pak05_dir.vpk","files":[$files]}${if (twoSets) another else ""}]}"""
    }

    private fun manifestJson(vararg records: String, enabled: List<String> = listOf("i"), orders: List<String> = listOf(orderJson())) =
        """{"schemaVersion":$ManifestSchemaVersion,"records":[${records.joinToString(",")}],"downloads":[],"gameConfigs":[],"profiles":${profilesJson(enabled, orders)},"originals":[],"repairChoices":[]}"""

    private fun decoded(text: String) = assertIs<ManifestDecode.Ok>(ManifestCodec.decode(text)).manifest

    private fun roundTrips(manifest: LibraryManifest) = assertEquals(ManifestDecode.Ok(manifest), ManifestCodec.decode(ManifestCodec.encode(manifest)))

    private val token = ChangeToken("t0k3n")

    private val main = ProfileId("main")

    private val image = FileImage(4_096L, "d".repeat(64))

    private val created = OwnedDownload(record.remoteIdentity, record.archive)

    private val off = record.placed(Placement.Off)

    private val second = oneSetRecord("install-2", record.gameRoot, InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, 9, 9, null)), "X", null, record.archive, 7, listOf(directory(FileImage(1, hex64))))

    @Test
    fun aManifestOfModsOnAndOffRoundTripsThroughJson() {
        roundTrips(LibraryManifest(records = listOf(record)))
        roundTrips(LibraryManifest(records = listOf(off)))
        roundTrips(LibraryManifest())
    }

    @Test
    fun theHandWrittenRecordShapeDecodesAndNamesItsFilesFromItsPlacement() {
        val on = decoded(manifestJson(recordJson())).records.single()
        assertEquals(SubmissionIdentity(SubmissionKind.Mod, 721_908, 1_829_854, "0332f45e5015186cb47219853f499a25"), on.remoteIdentity)
        assertEquals(Placement.On(Slot(5)), on.placement)
        assertEquals(listOf(SetFile(VpkPart.Directory, FileImage(1, hex64))), on.only.files)
        assertEquals(listOf("game/citadel/addons/pak05_dir.vpk"), on.files.map { it.relativePath(on) })
        val parked = decoded(manifestJson(recordJson(first = null), enabled = emptyList())).records.single()
        assertEquals(Placement.Off, parked.placement)
        assertEquals(listOf("game/citadel/hideout_off/mod-721908/${parked.only.key.offStem(parked.installId)}_dir.vpk"), parked.files.map { it.relativePath(parked) })
    }

    @Test
    fun aRecordsPlacementIsRequiredAndItsSetsSitInOneLoadingFolder() {
        val text = manifestJson(recordJson())
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(text.replace(""""placement":{"type":"On","first":5},""", "")), "a record that lost its placement fails rather than reading as off")
        listOf(0, 991).forEach { assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(manifestJson(recordJson(first = it))), "first $it") }
        listOf(99, 100, 990).forEach { assertIs<ManifestDecode.Ok>(ManifestCodec.decode(manifestJson(recordJson(first = it))), "first $it") }
        assertIs<ManifestDecode.Ok>(ManifestCodec.decode(manifestJson(recordJson(first = 98, twoSets = true))))
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(manifestJson(recordJson(first = 99, twoSets = true))), "a second set past 99")
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(manifestJson(recordJson(first = 198, twoSets = true))), "a second set past hideout_addons2's 99")
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(text.replace(""""type":"On"""", """"type":"Elsewhere"""")), "a placement this code does not know")
    }

    @Test
    fun aSetsFilesAreItsDirectoryThenItsChunksInOrderEachByAPartThisCodeKnows() {
        assertIs<ManifestDecode.Ok>(ManifestCodec.decode(manifestJson(recordJson(files = listOf("dir", "000", "002").joinToString(",") { fileJson(it) }))))
        val cases = mapOf(
            "no directory" to fileJson("000"),
            "the directory after a chunk" to fileJson("000") + "," + fileJson("dir"),
            "chunks out of order" to listOf("dir", "002", "000").joinToString(",") { fileJson(it) },
            "a part twice" to fileJson() + "," + fileJson(),
            "an unknown part" to fileJson() + "," + fileJson("abc"),
            "a file name for a part" to fileJson("pak05_dir.vpk"),
            "a part that climbs out" to fileJson("../x"),
            "no file" to "",
        )
        for ((case, files) in cases) assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(manifestJson(recordJson(files = files))), case)
    }

    @Test
    fun everyOriginRoundTrips() {
        val downloaded = record.only
        val imported = downloaded.copy(source = InstalledSource.Local("k", "mine.zip"), originalName = null, origin = SetOrigin.Imported(FileImage(4_096L, "d".repeat(64))))
        val adopted = downloaded.copy(origin = SetOrigin.Adopted(MigrationId("dmm-1")))
        for (set in listOf(downloaded, imported, adopted)) roundTrips(LibraryManifest(records = listOf(record.copy(sets = listOf(set)))))
        assertEquals(record.archive, downloaded.download)
        assertNull(imported.download, "an imported set names no download")
        assertNull(adopted.download, "an adopted set names no download")
        assertFailsWith<IllegalArgumentException>("a migration id is safe in a file name") { MigrationId("../dmm") }
        val text = ManifestCodec.encode(LibraryManifest(records = listOf(record.copy(sets = listOf(adopted)))))
        assertTrue(""""origin":{"type":"Adopted","migration":"dmm-1"}""" in text, text)
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(text.replace(""""migration":"dmm-1"""", """"migration":"DMM 1"""")), "an unsafe migration id")
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(text.replace(""""type":"Adopted"""", """"type":"Copied"""")), "an origin this code does not know")
    }

    @Test
    fun theUsersEditOfASetsFileRoundTripsByPartAndIsOtherBytesInOneOfItsFiles() {
        val edited = record.copy(sets = listOf(record.only.copy(edits = mapOf(VpkPart.Chunk(0) to image))))
        roundTrips(LibraryManifest(records = listOf(edited)))
        val text = ManifestCodec.encode(LibraryManifest(records = listOf(edited)))
        assertTrue(""""edits":[{"part":"000","sizeBytes":4096,"sha256":"${"d".repeat(64)}"}]""" in text, text)
        assertFalse(""""edits"""" in ManifestCodec.encode(LibraryManifest(records = listOf(record))), "a set no one edited writes no edits")
        val twice = text.replace(""""edits":[""", """"edits":[{"part":"000","sizeBytes":1,"sha256":"$hex64"},""")
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(twice), "a part edited twice")
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(text.replace(""""part":"000","sizeBytes":4096""", """"part":"001","sizeBytes":4096""")), "a part the set does not have")
        assertFailsWith<IllegalArgumentException>("an edit is other bytes") { record.only.copy(edits = mapOf(VpkPart.Directory to record.only.files.first().image)) }
    }

    @Test
    fun aLocalFileKeepsTheSubmissionItIsKnownByInTheSameSchema() {
        val known = record.only.copy(source = InstalledSource.Local("k", "pak34_dir.vpk", SubmissionRef(SubmissionKind.Mod, 717_740)), originalName = null, origin = SetOrigin.Adopted(MigrationId("dmm-1")))
        val manifest = LibraryManifest(records = listOf(record.copy(sets = listOf(known))))
        val text = ManifestCodec.encode(manifest)
        assertEquals(ManifestDecode.Ok(manifest), ManifestCodec.decode(text))
        assertTrue(""""submissionKind":"Mod","submissionId":717740,"localId":"k"""" in text && "fileId" !in text, text)
        val before = ManifestCodec.encode(LibraryManifest(records = listOf(record.copy(sets = listOf(known.copy(source = InstalledSource.Local("k", "pak34_dir.vpk")))))))
        val source = decoded(before).records.single().sets.single().source
        assertNull(assertIs<InstalledSource.Local>(source).submission, "a manifest written without one has none")
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(text.replace(""""submissionId":717740,""", "")), "a kind without its submission")
    }

    @Test
    fun anotherSchemaIsUnsupportedNotBestEffortAndTextWithoutOneIsMalformed() {
        for (version in (1 until ManifestSchemaVersion) + (ManifestSchemaVersion + 1)) {
            assertEquals(ManifestDecode.Unsupported(version), ManifestCodec.decode("""{"schemaVersion":$version,"somethingNew":{}}"""), "schema $version")
        }
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode("{not json"), "not JSON")
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(""), "nothing")
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode("""{"records":[]}"""), "no schema")
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode("""{"schemaVersion":"$ManifestSchemaVersion","records":[]}"""), "schema as text")
    }

    @Test
    fun aBadRecordFailsTheWholeManifestRatherThanDroppingItself() {
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(manifestJson(recordJson(installId = "good"), recordJson(installId = "bad", fileMd5 = "m"))))
    }

    @Test
    fun duplicateInstallIdsAreRejected() {
        val duplicate = manifestJson(recordJson(installId = "i"), recordJson(installId = "i", gameRoot = "/g2", submissionId = "3"))
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(duplicate))
    }

    @Test
    fun theOffFolderIsNamedOnlyByTheValidatedSubmission() {
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(manifestJson(recordJson(first = null, submissionId = "\"../../../outside\""), enabled = emptyList())))
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(manifestJson(recordJson(first = null, submissionKind = "../outside"), enabled = emptyList())))
    }

    @Test
    fun identitiesMustBeRealGameBananaIds() {
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(manifestJson(recordJson(submissionId = "0"))), "zero submission")
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(manifestJson(recordJson(fileId = "-1"))), "negative file")
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(manifestJson(recordJson(fileMd5 = "m"))), "short md5")
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(manifestJson(recordJson(fileMd5 = "0332F45E5015186CB47219853F499A25"))), "uppercase md5")
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(manifestJson(recordJson(archiveSha256 = "a"))), "archive sha")
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(manifestJson(recordJson(submissionKind = "Tool"))), "unknown kind")
    }

    @Test
    fun rootsAndArchivePathsMustBePlainAbsolutePaths() {
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(manifestJson(recordJson(gameRoot = "games/Deadlock"))), "relative root")
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(manifestJson(recordJson(gameRoot = "/games/../etc"))), "dotted root")
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(manifestJson(recordJson(gameRoot = "/games/Deadlock/"))), "trailing separator")
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(manifestJson(recordJson(archivePath = "../a.zip"))), "relative archive")
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(manifestJson(recordJson(archivePath = "/downloads/a\\u0000.zip"))), "NUL archive")
        val steam = """C:\\Program Files (x86)\\Steam\\steamapps\\common\\Deadlock"""
        val windows = recordJson(gameRoot = steam, archivePath = """D:\\Downloads\\a.zip""")
        assertIs<ManifestDecode.Ok>(ManifestCodec.decode(manifestJson(windows, orders = listOf(orderJson(steam)))), "Windows paths")
    }

    @Test
    fun twoModsOnInOneGameCannotHoldOneNumberAndModsOffHoldNone() {
        val ids = listOf(orderJson(ids = listOf("a", "b")))
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(manifestJson(recordJson(installId = "a"), recordJson(installId = "b", submissionId = "2"), enabled = listOf("a", "b"), orders = ids)), "two on at 05")
        val overlapping = recordJson(installId = "b", submissionId = "2", first = 4, twoSets = true)
        assertIs<ManifestDecode.Ok>(ManifestCodec.decode(manifestJson(recordJson(installId = "a", first = 6), overlapping, enabled = listOf("a", "b"), orders = listOf(orderJson(ids = listOf("b", "a"))))), "04-05 beside 06")
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(manifestJson(recordJson(installId = "a"), overlapping, enabled = listOf("a", "b"), orders = ids)), "a block of 04-05 over 05")
        assertIs<ManifestDecode.Ok>(ManifestCodec.decode(manifestJson(recordJson(installId = "a", first = null), recordJson(installId = "b", submissionId = "2", first = null), enabled = emptyList(), orders = ids)), "two off hold no number")
        val twoGames = manifestJson(
            recordJson(installId = "a"), recordJson(installId = "b", gameRoot = "/other"), enabled = listOf("a", "b"), orders = listOf(orderJson(ids = listOf("a")), orderJson("/other", listOf("b"))),
        )
        assertIs<ManifestDecode.Ok>(ManifestCodec.decode(twoGames), "the same number in two games is two different files")
    }

    @Test
    fun aGameHoldsOneRecordOfEachModSoNoTwoOffFoldersMeet() {
        val ids = listOf(orderJson(ids = listOf("a", "b")))
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(manifestJson(recordJson(installId = "a"), recordJson(installId = "b", first = null), enabled = listOf("a"), orders = ids)))
        assertFailsWith<IllegalArgumentException>("a pending install of a mod the game holds") {
            LibraryManifest(records = listOf(record), pending = PendingChange.Install(off.copy(installId = "other"), InstallStep.Staging, setOf(main)))
        }
    }

    @Test
    fun aFileGameBananaPublishesWithoutAnMd5StillHasAnIdentity() {
        val unpublished = record.copy(sets = listOf(record.only.copy(source = InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, 721_908, 1_829_854, null)))))
        roundTrips(LibraryManifest(records = listOf(unpublished)))
        val absent = recordJson().replace(""""fileMd5":"0332f45e5015186cb47219853f499a25",""", "")
        assertEquals(SubmissionIdentity(SubmissionKind.Mod, 721_908, 1_829_854, null), decoded(manifestJson(absent)).records.single().remoteIdentity)
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(manifestJson(recordJson(fileMd5 = ""))), "an empty MD5 is not an absent one")
    }

    private fun pendingInstall(step: InstallStep, installId: String = "k2x7q") = PendingChange.Install(off.copy(installId = installId), step, setOf(main))

    @Test
    fun downloadsReceiptsAndEveryPendingInstallStepRoundTrip() {
        val receipt = GameConfigReceipt(record.gameRoot, written = image.copy(sizeBytes = 4_200L), HideoutLines(mods = true, customGames = false))
        val chunk = pendingInstall(InstallStep.Staging).record.files[1].name
        val steps = listOf(InstallStep.Staging, InstallStep.Publishing, InstallStep.Releasing, InstallStep.RollingBack(setOf(chunk)))
        steps.forEach { roundTrips(LibraryManifest(downloads = listOf(created), gameConfigs = listOf(receipt), pending = pendingInstall(it))) }
        listOf(true, false).flatMap { mods -> listOf(true, false).map { HideoutLines(mods, it) } }.forEach { roundTrips(LibraryManifest(pending = PendingChange.ConfigEdit(record.gameRoot, ConfigTarget.GameInfo(it), "q7w2", from = image, to = image.copy(sizeBytes = 4_200L)))) }
        listOf(null, image).forEach { roundTrips(LibraryManifest(pending = PendingChange.ConfigEdit(record.gameRoot, ConfigTarget.Autoexec, "q7w3", from = it, to = image.copy(sizeBytes = 4_200L)))) }
        roundTrips(LibraryManifest(gameConfigs = listOf(GameConfigReceipt(record.gameRoot, written = null, HideoutLines(mods = false, customGames = false)))))
        roundTrips(LibraryManifest(gameConfigs = listOf(GameConfigReceipt(record.gameRoot, written = null, HideoutLines(mods = false, customGames = true)))))
        roundTrips(LibraryManifest(gameConfigs = listOf(receipt.copy(game = "pak01:${"e".repeat(64)}"))))
        assertTrue(""""published":["$chunk"]""" in ManifestCodec.encode(LibraryManifest(pending = pendingInstall(InstallStep.RollingBack(setOf(chunk))))), "a rollback names the off files it proved")
    }

    @Test
    fun aDownloadsRetentionIsRequiredAndRoundTrips() {
        roundTrips(LibraryManifest(downloads = listOf(created.copy(retention = DownloadRetention.DiscardAfterInstall))))
        val text = ManifestCodec.encode(LibraryManifest(downloads = listOf(created)))
        assertTrue(""""retention":"Keep"""" in text, text)
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(text.replace(""","retention":"Keep"""", "")), "a download says whether it stays")
    }

    @Test
    fun aSetNamesItsComponentAndAPendingInstallItsProfiles() {
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(manifestJson(recordJson().replace(""""componentKey":"pak05_dir.vpk",""", ""))), "a set without its component")
        val text = ManifestCodec.encode(LibraryManifest(records = listOf(second), pending = pendingInstall(InstallStep.Staging)))
        assertTrue(""","profiles":["main"]""" in text, text)
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(text.replace(""","profiles":["main"]""", "")), "an install journal without the profiles it is on in")
    }

    @Test
    fun aPendingInstallIsOffAndCannotTakeAnIdAnotherRecordHasOrNameAnUnsafeTemporary() {
        assertFailsWith<IllegalArgumentException>("a new mod placed on") { PendingChange.Install(record.copy(installId = "k"), InstallStep.Staging, setOf(main)) }
        assertFailsWith<IllegalArgumentException>("same install id") {
            LibraryManifest(records = listOf(second), pending = pendingInstall(InstallStep.Staging, installId = second.installId))
        }
        assertFailsWith<IllegalArgumentException>("traversal in the temporary suffix") { pendingInstall(InstallStep.Staging, installId = "../x") }
        assertFailsWith<IllegalArgumentException>("uppercase or separators") { pendingInstall(InstallStep.Staging, installId = "A/b") }
        assertFailsWith<IllegalArgumentException>("rolling back a file the set does not have") { pendingInstall(InstallStep.RollingBack(setOf("pak09_dir.vpk"))) }
        assertFailsWith<IllegalArgumentException>("unsafe config token") { PendingChange.ConfigEdit(record.gameRoot, ConfigTarget.GameInfo(HideoutLines(mods = true, customGames = false)), "../gameinfo", image, image) }
        assertEquals("${off.files[0].name}.hideout-k2x7q", pendingInstall(InstallStep.Staging).temporaryName(off.files[0]))
    }

    @Test
    fun aGameInfoEditNamesItsIntentAndAFileThatWasThere() {
        val edit = ManifestCodec.encode(LibraryManifest(pending = PendingChange.ConfigEdit(record.gameRoot, ConfigTarget.GameInfo(HideoutLines(mods = false, customGames = false)), "q7w2", image, image)))
        assertTrue(""","intent":"Vanilla"""" in edit)
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(edit.replace(""","intent":"Vanilla"""", "")), "an edit without its intent")
        assertFailsWith<IllegalArgumentException>("gameinfo.gi is always there before an edit") {
            PendingChange.ConfigEdit(record.gameRoot, ConfigTarget.GameInfo(HideoutLines(mods = true, customGames = false)), "q7w2", null, image)
        }
    }

    @Test
    fun receiptsAndDownloadsMustBeUnambiguous() {
        val receipt = GameConfigReceipt(record.gameRoot, written = image, HideoutLines(mods = true, customGames = false))
        assertFailsWith<IllegalArgumentException>("two receipts for one game") { LibraryManifest(gameConfigs = listOf(receipt, receipt.copy(written = null))) }
        assertFailsWith<IllegalArgumentException>("one archive claimed twice") { LibraryManifest(downloads = listOf(created, created.copy(source = created.source.copy(fileId = 7)))) }
        assertFailsWith<IllegalArgumentException>("not a sha256") { FileImage(1, "abc") }
        assertFailsWith<IllegalArgumentException>("negative size") { FileImage(-1, "a".repeat(64)) }
    }

    @Test
    fun appWideModIdsParseIntoSubmissions() {
        assertEquals(SubmissionRef(SubmissionKind.Mod, 628_739), SubmissionRef.parse("mod-628739"))
        assertEquals(SubmissionRef(SubmissionKind.Sound, 7), SubmissionRef.parse("sound-7"))
        assertEquals("mod-628739", SubmissionRef(SubmissionKind.Mod, 628_739).modId)
        listOf("mod-0", "mod--1", "mod-01", "mod-", "tool-5", "mod-12a", "MOD-5", "mod-99999999999999999999").forEach {
            assertEquals(null, SubmissionRef.parse(it), it)
        }
        assertEquals(SubmissionRef(SubmissionKind.Mod, 721_908), record.remoteIdentity.ref)
    }

    @Test
    fun anAnchorIsTheFilesOwnNamePlusTheToken() {
        assertEquals("pak05_dir.vpk.hideout-t0k3n", token.anchorName(record.files.first()))
        assertEquals("${off.only.key.offStem(off.installId)}_dir.vpk.hideout-t0k3n", token.anchorName(off.files.first()))
        assertEquals("${off.only.key.offStem(off.installId)}_000.vpk.hideout-t0k3n-new", token.temporaryName(off.files[1]))
        assertFailsWith<IllegalArgumentException>("a token with a separator") { ChangeToken("a/b") }
        assertFailsWith<IllegalArgumentException>("a token with a dot") { ChangeToken("..") }
    }

    @Test
    fun everyRelocateStepRoundTripsBoundToTheRecordItCommitsAndRenamesByPart() {
        val local = off.withOnly { copy(source = InstalledSource.Local("abc", "pak05_dir.vpk"), origin = SetOrigin.Imported(FileImage(1, hex64))) }
        RelocateStep.entries.forEach { step ->
            val change = PendingChange.Relocate(local, token, step, off)
            roundTrips(LibraryManifest(records = listOf(change.committed), pending = change))
        }
        assertEquals(local.files.map { it.part to it.image }, off.files.map { it.part to it.image })
        assertTrue(local.files.zip(off.files).all { (from, to) -> from.name != to.name }, "the off names follow the set's new key")
        assertFailsWith<IllegalArgumentException>("a move of a mod that is on") { PendingChange.Relocate(local.placed(Placement.On(Slot(5))), token, RelocateStep.Linking, record) }
        assertFailsWith<IllegalArgumentException>("a move that changes a part's bytes") {
            PendingChange.Relocate(local, token, RelocateStep.Linking, off.withOnly { copy(files = files.map { it.copy(image = image) }) })
        }
        assertFailsWith<IllegalArgumentException>("linking while the manifest already holds the target") {
            LibraryManifest(records = listOf(off), pending = PendingChange.Relocate(local, token, RelocateStep.Linking, off))
        }
        assertFailsWith<IllegalArgumentException>("cleanup while the manifest still holds the source") {
            LibraryManifest(records = listOf(local), pending = PendingChange.Relocate(local, token, RelocateStep.Cleanup, off))
        }
        assertFailsWith<IllegalArgumentException>("a move of a set the manifest does not hold") {
            LibraryManifest(pending = PendingChange.Relocate(local, token, RelocateStep.Releasing, off))
        }
        val moving = LibraryManifest(records = listOf(local), pending = PendingChange.Relocate(local, token, RelocateStep.Linking, off))
        assertEquals(listOf(local, local, off), moving.namedRecords(), "a move names the record and where it goes")
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(ManifestCodec.encode(moving).replace(""""token":"t0k3n"""", """"token":"../x"""")), "an unsafe token")
    }

    @Test
    fun everyUninstallStepRoundTripsWithOneOutcomePerFile() {
        val files = mapOf("pak05_dir.vpk" to RemovalOutcome.Removed, "pak05_000.vpk" to RemovalOutcome.Preserved("pak05_000.vpk changed"))
        val settled = UninstallStep.Settled(files, mapOf(record.archive.path to RemovalOutcome.Absent))
        listOf(UninstallStep.Anchoring, UninstallStep.Releasing, settled).forEach { step ->
            roundTrips(LibraryManifest(records = listOf(record), downloads = listOf(created), pending = PendingChange.Uninstall(record, listOf(record.archive), token, step)))
        }
        val keepDownload = settled.copy(archives = emptyMap())
        roundTrips(LibraryManifest(records = listOf(record), pending = PendingChange.Uninstall(record, emptyList(), token, keepDownload)))
        val offFiles = off.files.associate { it.name to RemovalOutcome.Removed }
        roundTrips(LibraryManifest(records = listOf(off), pending = PendingChange.Uninstall(off, emptyList(), token, UninstallStep.Settled(offFiles, emptyMap()))))
        assertFailsWith<IllegalArgumentException>("a settled uninstall missing a file") {
            PendingChange.Uninstall(record, emptyList(), token, UninstallStep.Settled(mapOf("pak05_dir.vpk" to RemovalOutcome.Removed), emptyMap()))
        }
        assertFailsWith<IllegalArgumentException>("an off mod's outcomes under its on names") { PendingChange.Uninstall(off, emptyList(), token, keepDownload) }
        assertFailsWith<IllegalArgumentException>("an archive outcome without the archive") { PendingChange.Uninstall(record, emptyList(), token, settled) }
        assertFailsWith<IllegalArgumentException>("another record's archive") {
            PendingChange.Uninstall(record, listOf(ArchiveIdentity("/downloads/other.zip", 1, "e".repeat(64))), token, UninstallStep.Anchoring)
        }
        assertFailsWith<IllegalArgumentException>("one download twice") { PendingChange.Uninstall(record, listOf(record.archive, record.archive), token, UninstallStep.Anchoring) }
        assertFailsWith<IllegalArgumentException>("an uninstall of a set the manifest does not hold exactly") {
            LibraryManifest(records = listOf(off), pending = PendingChange.Uninstall(record, emptyList(), token, UninstallStep.Anchoring))
        }
        assertFailsWith<IllegalArgumentException>("deleting a download the manifest does not register") {
            LibraryManifest(records = listOf(record), pending = PendingChange.Uninstall(record, listOf(record.archive), token, UninstallStep.Anchoring))
        }
        val text = ManifestCodec.encode(LibraryManifest(records = listOf(record), pending = PendingChange.Uninstall(record, emptyList(), token, keepDownload)))
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(text.replace(""""reason":"pak05_000.vpk changed"""", """"reason":null""")), "a preserved file without a reason")
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(text.replace(""""archives":[]""", """"archives":["${record.archive.path}"]""")), "a download outcome missing")
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(text.replace(""""archives":[]""", """"archives":["/downloads/other.zip"]""")), "a download the mod does not have")
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(text.replace(""""archives":[],""", "")), "the downloads that go are named")
    }

    @Test
    fun everyArchiveRemovalStepRoundTripsAndNamesARegisteredDownload() {
        val discard = created.copy(retention = DownloadRetention.DiscardAfterInstall)
        val steps = listOf(ArchiveStep.Anchoring, ArchiveStep.Releasing) + listOf(RemovalOutcome.Removed, RemovalOutcome.Absent, RemovalOutcome.Preserved("it changed")).map { ArchiveStep.Settled(it) }
        steps.forEach { step ->
            roundTrips(LibraryManifest(records = listOf(record), downloads = listOf(discard), pending = PendingChange.ArchiveRemoval(discard, record.installId, token, step)))
            roundTrips(LibraryManifest(downloads = listOf(created), pending = PendingChange.ArchiveRemoval(created, null, token, step)))
        }
        assertEquals(emptySet(), PendingChange.ArchiveRemoval(created, null, token, ArchiveStep.Anchoring).gameRoots, "it touches no game")
        assertFailsWith<IllegalArgumentException>("a download the manifest does not register") {
            LibraryManifest(pending = PendingChange.ArchiveRemoval(created, null, token, ArchiveStep.Anchoring))
        }
        assertFailsWith<IllegalArgumentException>("a registration that changed since") {
            LibraryManifest(downloads = listOf(discard), pending = PendingChange.ArchiveRemoval(created, null, token, ArchiveStep.Anchoring))
        }
        val text = ManifestCodec.encode(LibraryManifest(downloads = listOf(created), pending = PendingChange.ArchiveRemoval(created, null, token, ArchiveStep.Settled(RemovalOutcome.Removed))))
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(text.replace(""""outcome":{"kind":"Removed"}""", """"outcome":null""")), "settled without an outcome")
    }

    @Test
    fun everyArrangeStepRoundTripsBoundToTheRecordsAndBookItCommits() {
        val parked = second.placed(Placement.Off)
        val moves = listOf(Move(record, record.placed(Placement.On(Slot(8)))), Move(parked, parked.placed(Placement.On(Slot(5)))))
        val after = ProfileBook.main(moves.map { it.target })
        MoveStep.entries.forEach { step ->
            val change = PendingChange.Arrange(token, moves, after, step)
            roundTrips(LibraryManifest(records = change.committed, pending = change))
        }
        assertEquals(listOf("pak08_dir.vpk", "pak08_000.vpk"), moves[0].target.files.map { it.name }, "every part and its bytes stay; only the number changes")
        assertEquals(listOf("game/citadel/addons/pak05_dir.vpk"), moves[1].target.files.map { it.relativePath(moves[1].target) }, "an off mod turned on takes the number the other one frees")
        assertFailsWith<IllegalArgumentException>("cleanup while the manifest still holds the sources") {
            LibraryManifest(records = listOf(record, parked), pending = PendingChange.Arrange(token, moves, after, MoveStep.Cleanup))
        }
        assertFailsWith<IllegalArgumentException>("a book the files it commits would not follow") {
            LibraryManifest(records = listOf(record, parked), pending = PendingChange.Arrange(token, moves, ProfileBook.main(listOf(record, parked)), MoveStep.Anchoring))
        }
        assertFailsWith<IllegalArgumentException>("two moving mods on one number") {
            PendingChange.Arrange(token, listOf(Move(record, record.placed(Placement.On(Slot(8)))), Move(parked, parked.placed(Placement.On(Slot(8))))), after, MoveStep.Anchoring)
        }
        assertFailsWith<IllegalArgumentException>("nothing moves") { PendingChange.Arrange(token, emptyList(), after, MoveStep.Anchoring) }
        val twoParts = parked.copy(sets = parked.sets + parked.only.copy(componentKey = "b/pak01_dir.vpk"))
        assertFailsWith<IllegalArgumentException>("an off mod's parts in another order move no file") {
            PendingChange.Arrange(token, listOf(Move(twoParts, twoParts.placed(Placement.Off, twoParts.sets.reversed()))), ProfileBook.main(listOf(twoParts)), MoveStep.Anchoring)
        }
        val stationary = oneSetRecord("install-3", record.gameRoot, InstalledSource.Remote(record.remoteIdentity.copy(submissionId = 10)), record.title, null, record.archive, 6, listOf(directory(image)))
        val crowded = listOf(Move(record, record.placed(Placement.On(Slot(6)))))
        assertFailsWith<IllegalArgumentException>("a number a mod that stays on holds") {
            LibraryManifest(records = listOf(record, stationary), pending = PendingChange.Arrange(token, crowded, ProfileBook.main(crowded.map { it.target } + stationary), MoveStep.Anchoring))
        }
        val away = parked.copy(gameRoot = GameRoot("/other"))
        val games = listOf(Move(record, record.placed(Placement.On(Slot(8)))), Move(away, away.placed(Placement.On(Slot(8)))))
        val spanning = PendingChange.Arrange(token, games, ProfileBook.main(games.map { it.target }), MoveStep.Linking)
        roundTrips(LibraryManifest(records = listOf(record, away), pending = spanning))
        assertEquals(setOf(record.gameRoot, away.gameRoot), spanning.gameRoots, "one arrangement spans every game")
        val text = ManifestCodec.encode(LibraryManifest(records = listOf(record, parked), pending = PendingChange.Arrange(token, moves, after, MoveStep.Linking)))
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(text.replace(""""placement":{"type":"On","first":5},"order"""", """"placement":{"type":"On","first":8},"order"""")), "two moving mods on one number")
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(text.replace(""""order":["${parked.only.key.value}"]""", """"order":["000000000000"]""")), "an order of a set the source does not have")
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(text.replace(""""order":["${parked.only.key.value}"]""", """"order":[]""")), "an order that leaves a set out")
    }

    @Test
    fun everyAdoptStepRoundTripsWithItsSourcesBookAndTarget() {
        val migration = MigrationId("m-20261021")
        fun adopted(installId: String, source: InstalledSource, placement: Placement) = ManifestRecord(
            installId, record.gameRoot, "Adopted $installId", placement, listOf(InstalledSet(source, null, null, SetOrigin.Adopted(migration), null, "pak07_dir.vpk", listOf(directory(FileImage(9L, "e".repeat(64)))))),
        )
        val claimed = Adoption(adopted("claimed", InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, 696_085, 1_500_000, null)), placedAt(7)), listOf(CitadelPath("addons/pak07_dir.vpk")))
        val parked = Adoption(adopted("parked", InstalledSource.Local("k3", "637275_Percentage Rename.vpk"), Placement.Off), listOf(CitadelPath("addons/637275_Percentage Rename.vpk")))
        val book = ProfileBook.main(listOf(record, claimed.record, parked.record))
        val plan = AdoptionPlan(record.gameRoot, migration, listOf(claimed, parked), book, book.withOrder(record.gameRoot, listOf("parked", "install-1", "claimed")))
        AdoptStep.entries.forEach { step -> roundTrips(LibraryManifest(records = listOf(record), pending = PendingChange.Adopt(plan, step))) }

        assertTrue(claimed.claimed && !parked.claimed)
        assertEquals(listOf(parked), plan.moving)
        assertEquals(listOf("hideout_off/local-k3/${parked.record.only.key.offStem("parked")}_dir.vpk"), parked.record.files.map { it.citadelPath(parked.record).value }, "an off mod comes into its own off folder")
        assertFailsWith<IllegalArgumentException>("records the manifest commits only when the journal clears") {
            LibraryManifest(records = listOf(record, claimed.record), pending = PendingChange.Adopt(plan, AdoptStep.Releasing))
        }
        assertFailsWith<IllegalArgumentException>("a book that leaves out a record the import commits") {
            LibraryManifest(records = listOf(record), pending = PendingChange.Adopt(plan.copy(book = ProfileBook.main(listOf(record, claimed.record))), AdoptStep.Linking))
        }
        assertFailsWith<IllegalArgumentException>("a claimed number a mod on holds") {
            LibraryManifest(records = listOf(record.placed(Placement.On(Slot(7)))), pending = PendingChange.Adopt(plan.copy(book = ProfileBook.main(listOf(record.placed(Placement.On(Slot(7))), claimed.record, parked.record))), AdoptStep.Linking))
        }
        assertFailsWith<IllegalArgumentException>("a record moved in part") {
            Adoption(claimed.record.withOnly { copy(files = files + SetFile(VpkPart.Chunk(0), FileImage(1, "f".repeat(64)))) }, listOf(CitadelPath("addons/pak07_dir.vpk"), CitadelPath("addons/x_000.vpk")))
        }
        assertFailsWith<IllegalArgumentException>("a mod adopted on that does not already load there") { Adoption(adopted("moved", InstalledSource.Local("k4", "x.vpk"), placedAt(9)), listOf(CitadelPath("grimoire/pak09_dir.vpk"))) }
        assertFailsWith<IllegalArgumentException>("nothing in hideout_off is another manager's") { Adoption(parked.record, listOf(CitadelPath("hideout_off/mod-1/pak01_dir.vpk"))) }
        assertFailsWith<IllegalArgumentException>("staging is never adopted from") { Adoption(parked.record, listOf(CitadelPath("addons/.dmm-clear/pak03_dir.vpk"))) }
        assertFailsWith<IllegalArgumentException>("an off record in the active profile") { plan.copy(book = book.membership(book.active, setOf("parked"), true)) }
    }

    @Test
    fun aRecordsOptionalFieldsRoundTripAndOlderRecordsHaveNone() {
        roundTrips(LibraryManifest(records = listOf(record.copy(sets = listOf(record.only.copy(version = VersionPosition(1, 3)))))))
        roundTrips(LibraryManifest(records = listOf(record.copy(reviewed = setOf(1_829_860L, 1_829_854L)))))
        roundTrips(LibraryManifest(records = listOf(record.copy(keptBeside = setOf("werecat", "faun")))))
        val older = decoded(manifestJson(recordJson())).records.single()
        assertNull(older.version)
        assertNull(older.reviewed)
        assertEquals(emptySet(), older.keptBeside)
    }

    @Test
    fun thePartsARecordLeftOutRoundTripAndOnlyAsPartsOfItsOwnFilesThatItLacks() {
        roundTrips(LibraryManifest(records = listOf(record.copy(declined = mapOf(1_829_854L to setOf("sounds/pak06_dir.vpk", "extra/pak07_dir.vpk"))))))
        assertEquals(emptyMap(), decoded(manifestJson(recordJson())).records.single().declined, "a record without the field left nothing out")
        fun withDeclined(json: String) = manifestJson(recordJson().removeSuffix("}") + ""","declined":$json}""")
        assertEquals(mapOf(1_829_854L to setOf("sounds/pak06_dir.vpk")), decoded(withDeclined("""{"1829854":["sounds/pak06_dir.vpk"]}""")).records.single().declined)
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(withDeclined("""{"1829854":["pak05_dir.vpk"]}""")), "an installed part is not left out")
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(withDeclined("""{"42":["sounds/pak06_dir.vpk"]}""")), "nor a part of a file the mod does not have")
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(withDeclined("""{"1829854":[]}""")), "a file with nothing left out is absent")
    }

    @Test
    fun aRepairedSetItsRetainedOriginalAndItsRepairChoiceRoundTrip() {
        val original = RetainedOriginal("orig-1", record.installId, record.only.source, record.only.componentKey, record.only.files)
        val derivation = SetDerivation("orig-1", record.only.files.map { it.image }, listOf(RuleIdentity("root-motion-attachment-frame", 1)), "hideout-kv3-1+resource-1+vpk-1", "build:6796", 1, 14)
        val repaired = record.withOnly { copy(files = files.map { it.copy(image = it.image.copy(sha256 = "e".repeat(64))) }, derivation = derivation) }
        roundTrips(LibraryManifest(records = listOf(repaired), originals = listOf(original), repairChoices = mapOf(record.installId to RepairChoice.Original)))
        val stale = LibraryManifest(records = listOf(record), repairChoices = mapOf(record.installId to RepairChoice.Original, "gone" to RepairChoice.Repaired))
        assertEquals(mapOf(record.installId to RepairChoice.Original), decoded(ManifestCodec.encode(stale)).repairChoices, "a mod no longer installed leaves the repair choices")
        val text = ManifestCodec.encode(LibraryManifest(records = listOf(repaired), originals = listOf(original)))
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(text.replace(""""id":"orig-1"""", """"id":"orig-2"""")), "a repaired set names an original that is not kept")
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(text.replace("""{"part":"000",""", """{"part":"zzz",""")), "an original's part this code does not know")
    }

    private fun twoProfiles(records: List<ManifestRecord>): ProfileBook {
        val main = ProfileBook.main(records)
        val ranked = Profile(ProfileId("ranked"), "Ranked", ProfileColour.Clay, setOf(records.last().installId), main.activeProfile.orders.mapValues { (_, ids) -> ids.reversed() })
        return main.copy(profiles = main.profiles + ranked)
    }

    @Test
    fun savedChoicesRoundTrip() {
        val book = twoProfiles(listOf(record, second))
        val saved = book.copy(active = ProfileId("ranked")).withOrder(record.gameRoot, listOf("install-1", "install-2"))
        roundTrips(LibraryManifest(records = listOf(record, second), profiles = book, savedChoices = SavedChoices(saved)))
        assertFalse("savedChoices" in ManifestCodec.encode(LibraryManifest(records = listOf(record))), "absent when nothing is saved")
    }

    private val twoFiles = record.copy(
        sets = listOf(
            record.only.copy(originalName = "nicole_over_pocket.zip", version = VersionPosition(1, 3)),
            InstalledSet(
                InstalledSource.Remote(record.remoteIdentity.copy(fileId = 1_829_855, fileMd5 = null)), "nicole_hat.zip", "Hat",
                SetOrigin.Downloaded(ArchiveIdentity("/downloads/nicole_hat.zip", 4_000L, "e".repeat(64))), VersionPosition(3, 3), "hat/pak01_dir.vpk",
                listOf(SetFile(VpkPart.Directory, FileImage(300L, "f".repeat(64))), SetFile(VpkPart.Chunk(0), FileImage(700L, "1".repeat(64)))),
            ),
        ),
    )

    @Test
    fun aModOfSeveralFilesRoundTripsWithEachFilesOwnPartsAndOneFirstNumber() {
        roundTrips(LibraryManifest(records = listOf(twoFiles)))
        roundTrips(LibraryManifest(records = listOf(twoFiles.placed(Placement.Off))))
        val text = ManifestCodec.encode(LibraryManifest(records = listOf(twoFiles)))
        assertTrue(""""fileName":"nicole_hat.zip"""" in text && """"placement":{"type":"On","first":5}""" in text && "\"number\"" !in text, text)
        assertTrue(""""files":[{"part":"dir","sizeBytes":1000,"sha256":"""" in text && "pak05" !in text, "a file is its part and bytes, and no name is stored: $text")
    }

    @Test
    fun aModsSetsComeFromOneModOnceEachAndTheirKeysDiffer() {
        val (first, second) = twoFiles.sets
        assertFailsWith<IllegalArgumentException>("another mod's file") {
            twoFiles.copy(sets = listOf(first, second.copy(source = InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, 9, 9, null)))))
        }
        assertFailsWith<IllegalArgumentException>("no set") { twoFiles.copy(sets = emptyList()) }
        val local = InstalledSet(InstalledSource.Local("k", "mine.zip"), null, null, SetOrigin.Imported(image), null, "mine.vpk", record.only.files)
        assertFailsWith<IllegalArgumentException>("the same local component twice") { ManifestRecord("l", record.gameRoot, "Mine", placedAt(5), listOf(local, local)) }
        val twoLocalComponents = ManifestRecord("l", record.gameRoot, "Mine", placedAt(5), listOf(local.copy(componentKey = "a.vpk"), local.copy(componentKey = "b.vpk")))
        assertEquals(listOf("a.vpk", "b.vpk"), twoLocalComponents.sets.map { it.componentKey }, "one local archive may own several components")
        assertFailsWith<IllegalArgumentException>("two mods on one number") {
            LibraryManifest(records = listOf(twoFiles, oneSetRecord("x", record.gameRoot, InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, 9, 9, null)), "X", null, record.archive, 6, listOf(directory(FileImage(1, hex64))))))
        }
    }

    @Test
    fun twoComponentsOfOneSourceNeedDistinctComponentKeysAndRoundTrip() {
        val componentA = record.only.copy(componentKey = "A/pak01_dir.vpk")
        val componentB = record.only.copy(componentKey = "B/pak01_dir.vpk", files = listOf(directory(FileImage(1, hex64))))
        roundTrips(LibraryManifest(records = listOf(record.copy(sets = listOf(componentA, componentB)))))
    }

    @Test
    fun aReplacementKeepsItsKeptSetsByKeyAndChecksThem() {
        val (hood, hat) = twoFiles.sets
        val swapped = twoFiles.copy(sets = listOf(hat, hood))
        val swap = PendingChange.Replace(twoFiles, swapped, setOf(hood.key, hat.key), token, ReplaceStep.Staging)
        assertEquals(twoFiles.files.map { it.name }.toSet(), swap.going.map { it.name }.toSet(), "both kept sets of a mod that is on are renamed")
        assertEquals(twoFiles.filesOf(hat)[1], swap.renamedFrom(swapped.filesOf(hat)[1]))
        ReplaceStep.entries.forEach { step ->
            roundTrips(LibraryManifest(records = listOf(if (step == ReplaceStep.Cleanup) swapped else twoFiles), pending = swap.copy(step = step)))
        }
        val added = InstalledSet(
            InstalledSource.Remote(record.remoteIdentity.copy(fileId = 7)), "new.zip", "New", SetOrigin.Downloaded(ArchiveIdentity("/downloads/new.zip", 9L, "9".repeat(64))), null, "pak06_dir.vpk",
            listOf(directory(FileImage(9L, "9".repeat(64)))),
        )
        val partly = PendingChange.Replace(twoFiles, twoFiles.copy(sets = listOf(hood, added)), setOf(hood.key), token, ReplaceStep.Releasing)
        assertEquals(twoFiles.filesOf(hat), partly.going, "a kept set whose names stay is left alone")
        assertEquals(listOf("pak06_dir.vpk"), partly.coming.map { it.name })
        assertEquals(null, partly.renamedFrom(partly.coming.single()), "a new set comes from its temporary")
        assertEquals("pak06_dir.vpk.hideout-t0k3n-new", partly.token.temporaryName(partly.coming.single()))
        roundTrips(LibraryManifest(records = listOf(twoFiles), pending = partly))
        val parked = twoFiles.placed(Placement.Off)
        val offPartly = PendingChange.Replace(parked, parked.copy(sets = listOf(added, hood)), setOf(hood.key), token, ReplaceStep.Staging)
        assertEquals(parked.filesOf(hat), offPartly.going, "a kept set of an off mod keeps its names wherever it moves")
        assertEquals(parked.copy(sets = listOf(added, hood)).filesOf(added), offPartly.coming)
        assertFailsWith<IllegalArgumentException>("a kept set whose bytes changed") {
            PendingChange.Replace(twoFiles, twoFiles.copy(sets = listOf(hood.copy(files = hood.files.map { it.copy(image = image) }), added)), setOf(hood.key), token, ReplaceStep.Staging)
        }
        assertFailsWith<IllegalArgumentException>("a kept set the source does not have") { PendingChange.Replace(twoFiles, twoFiles.copy(sets = listOf(hood, added)), setOf(added.key), token, ReplaceStep.Staging) }
        assertFailsWith<IllegalArgumentException>("a replacement that turns the mod off") { PendingChange.Replace(twoFiles, parked, setOf(hood.key, hat.key), token, ReplaceStep.Staging) }
        val text = ManifestCodec.encode(LibraryManifest(records = listOf(twoFiles), pending = partly))
        assertTrue(""""kept":["${hood.key.value}"]""" in text, text)
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(text.replace(""""kept":["${hood.key.value}"]""", """"kept":["${added.key.value}"]""")), "a kept key the source lacks")
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(text.replace(""","kept":["${hood.key.value}"]""", "")), "a replacement says which sets it keeps")
        val other = oneSetRecord("x", record.gameRoot, InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, 9, 9, null)), "X", null, record.archive, 7, listOf(directory(FileImage(1, hex64))))
        assertFailsWith<IllegalArgumentException>("new files on another mod's number") {
            LibraryManifest(records = listOf(twoFiles, other), pending = PendingChange.Replace(twoFiles, twoFiles.copy(sets = twoFiles.sets + added), setOf(hood.key, hat.key), token, ReplaceStep.Staging))
        }
    }

    @Test
    fun aReinstallKeepsWhatEachFileThatGoesHeldDurably() {
        val changed = FileImage(300L, "2".repeat(64))
        val found = mapOf("pak06_dir.vpk" to changed, "pak06_000.vpk" to null)
        val reinstall = PendingChange.Replace(twoFiles, twoFiles, setOf(twoFiles.sets[0].key), token, ReplaceStep.Staging, found, aside = "Hood 2026-10-31")
        assertEquals(changed, reinstall.held(twoFiles.filesOf(twoFiles.sets[1])[0]))
        assertNull(reinstall.held(twoFiles.filesOf(twoFiles.sets[1])[1]), "a missing file held nothing")
        ReplaceStep.entries.forEach { step -> roundTrips(LibraryManifest(records = listOf(twoFiles), pending = reinstall.copy(step = step))) }
        val text = ManifestCodec.encode(LibraryManifest(records = listOf(twoFiles), pending = reinstall))
        assertTrue(""""found":[{"name":"pak06_000.vpk"},{"name":"pak06_dir.vpk","image":{"sizeBytes":300,"sha256":"${"2".repeat(64)}"}}],"aside":"Hood 2026-10-31"""" in text, text)
        val plain = ManifestCodec.encode(LibraryManifest(records = listOf(twoFiles), pending = reinstall.copy(found = emptyMap(), aside = null)))
        assertTrue("found" !in plain && "aside" !in plain, "every other change is written as before")
        assertFailsWith<IllegalArgumentException>("what the user edited is set aside") { reinstall.copy(aside = null) }
        assertFailsWith<IllegalArgumentException>("only an edit is") { reinstall.copy(found = mapOf("pak06_000.vpk" to null)) }
        assertIs<ManifestDecode.Malformed>(ManifestCodec.decode(text.replace(""""found":[{"name":"pak06_000.vpk"}""", """"found":[{"name":"pak05_dir.vpk"}""")), "a kept file never went")
        assertFailsWith<IllegalArgumentException>("what a file held is not what it was recorded with") { reinstall.copy(found = mapOf("pak06_dir.vpk" to FileImage(300L, "f".repeat(64)))) }

        val beside = reinstall.copy(found = found + ("pak06_001.vpk" to changed))
        assertEquals(mapOf("pak06_001.vpk" to changed), beside.strays, "a chunk the edit added goes with it")
        roundTrips(LibraryManifest(records = listOf(twoFiles), pending = beside))
        assertFailsWith<IllegalArgumentException>("a file beside the set is found by what it holds") { reinstall.copy(found = found + ("pak06_001.vpk" to null)) }
    }

    @Test
    fun everyRestoreStepRoundTripsBoundToTheRecordsItCommits() {
        val target = listOf(off, second)
        val kept = RetainedOriginal("kept", second.installId, second.only.source, second.only.componentKey, listOf(SetFile(VpkPart.Directory, image)))
        val change = PendingChange.Revert(
            record.gameRoot, listOf(record), target, token, ReplaceStep.Staging, ProfileBook.main(target), SavedChoices(twoProfiles(target)), listOf(kept), setOf("kept"),
            mapOf(second.installId to RepairChoice.Original), absent = setOf("game/citadel/addons/pak05_000.vpk"), missing = setOf("game/citadel/addons/pak07_dir.vpk"),
        )
        for (step in ReplaceStep.entries) {
            val cleanup = step == ReplaceStep.Cleanup
            roundTrips(
                LibraryManifest(
                    records = if (cleanup) target else listOf(record), profiles = if (cleanup) change.book else ProfileBook.main(listOf(record)),
                    savedChoices = change.choices.takeIf { cleanup }, originals = listOf(kept), pending = change.copy(step = step),
                ),
            )
        }
        assertEquals(listOf(record) + target, LibraryManifest(records = listOf(record), originals = listOf(kept), pending = change).namedRecords().drop(1))
        assertFailsWith<IllegalArgumentException>("cleanup while the manifest still holds the Library before") {
            LibraryManifest(records = listOf(record), originals = listOf(kept), pending = change.copy(step = ReplaceStep.Cleanup))
        }
        assertFailsWith<IllegalArgumentException>("an original placed for it but not registered") { LibraryManifest(records = listOf(record), pending = change) }
        assertFailsWith<IllegalArgumentException>("a book that does not follow what it commits") { LibraryManifest(records = listOf(record), originals = listOf(kept), pending = change.copy(book = ProfileBook.main(listOf(record)))) }
        assertFailsWith<IllegalArgumentException>("a missing file the Library never had") { change.copy(absent = setOf("game/citadel/addons/pak07_dir.vpk")) }
        assertFailsWith<IllegalArgumentException>("two mods it commits on one number") { change.copy(target = listOf(record, second.placed(Placement.On(Slot(5))))) }
    }

    @Test
    fun aBackupHoldsOneGamesLibraryAndRoundTrips() {
        val elsewhere = second.copy(installId = "elsewhere", gameRoot = GameRoot("/other/Deadlock"))
        val records = listOf(record, second, elsewhere)
        val receipt = GameConfigReceipt(record.gameRoot, written = image, HideoutLines(mods = true, customGames = false))
        val manifest = LibraryManifest(records = records, downloads = listOf(created), gameConfigs = listOf(receipt), profiles = twoProfiles(records), repairChoices = mapOf(record.installId to RepairChoice.Original, "elsewhere" to RepairChoice.Repaired))
        val backup = manifest.backupOf(record.gameRoot)
        assertEquals(listOf(record, second), backup.records)
        assertEquals(twoProfiles(listOf(record, second)).profiles.map { it.orders }, backup.profiles.profiles.map { it.orders }, "each profile orders that game's mods only")
        assertEquals(emptySet(), backup.profiles.profiles.last().enabled, "a mod of another game is not in it")
        assertEquals(mapOf(record.installId to RepairChoice.Original), backup.repairChoices)
        assertEquals(emptyList(), backup.downloads)
        assertEquals(emptyList(), backup.gameConfigs)
        roundTrips(backup)
    }

    @Test
    fun aBackupsOwnRecordRoundTripsAndNothingElseReadsAsOne() {
        val info = BackupInfo(BackupId("b4ck"), 1_761_400_000_000, BackupReason.BeforeUpdates, mods = 2)
        val text = BackupCodec.encode(info)
        assertEquals(info, BackupCodec.decode(info.id, text))
        assertNull(BackupCodec.decode(info.id, text.replace(""""schemaVersion":$ManifestSchemaVersion""", """"schemaVersion":12""")), "a backup of schema 12 is retired")
        assertNull(BackupCodec.decode(info.id, "not json"))
    }
}
