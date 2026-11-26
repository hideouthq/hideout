package gg.hideouthq.library.compat

import gg.hideouthq.core.library.ArchiveIdentity
import gg.hideouthq.core.library.CheckResult
import gg.hideouthq.core.library.DeriveResult
import gg.hideouthq.core.library.FileImage
import gg.hideouthq.core.library.FindingKind
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.InstalledSet
import gg.hideouthq.core.library.InstalledSource
import gg.hideouthq.core.library.ManifestRecord
import gg.hideouthq.core.library.Slot
import gg.hideouthq.core.library.Placement
import gg.hideouthq.core.library.Provider
import gg.hideouthq.core.library.RetainResult
import gg.hideouthq.core.library.RetainedOriginal
import gg.hideouthq.core.library.RuleIdentity
import gg.hideouthq.core.library.SetCheck
import gg.hideouthq.core.library.SetFile
import gg.hideouthq.core.library.SetOrigin
import gg.hideouthq.core.library.Sha256
import gg.hideouthq.core.library.StagedOriginal
import gg.hideouthq.core.library.SubmissionIdentity
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.VpkFormat
import gg.hideouthq.core.library.VpkPart
import gg.hideouthq.core.library.VpkRead
import gg.hideouthq.core.library.relativePath
import gg.hideouthq.library.compat.SyntheticModels.Identity
import gg.hideouthq.library.compat.SyntheticModels.OldPivot
import gg.hideouthq.library.compat.SyntheticModels.VpkFile
import gg.hideouthq.library.compat.SyntheticModels.attachment
import gg.hideouthq.library.compat.SyntheticModels.data
import gg.hideouthq.library.compat.SyntheticModels.keyValues
import gg.hideouthq.library.compat.SyntheticModels.mesh
import gg.hideouthq.library.compat.SyntheticModels.model
import gg.hideouthq.library.compat.SyntheticModels.table
import gg.hideouthq.library.compat.SyntheticModels.vpk
import gg.hideouthq.preview.sha256Hex
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NioCompatibilityTest {
    private val temp: Path = Files.createTempDirectory("hideout-compat")
    private val game = temp.resolve("Deadlock")
    private val citadel = game.resolve("game/citadel").createDirectories()
    private val addons = citadel.resolve("addons").createDirectories()
    private val data = temp.resolve("data")
    private val port = NioCompatibility(data.resolve("originals"), data.resolve("staging"))
    private val root = GameRoot(game.toString())
    private val modelPath = "models/heroes/test/test.vmdl_c"
    private val texture = ByteArray(5_000) { (it * 31).toByte() }

    @AfterTest
    fun cleanUp() {
        temp.toFile().deleteRecursively()
    }

    private val vanilla = model(listOf(mesh(listOf(attachment("standing_pivot", Identity, doubleArrayOf(0.0, 0.0, 94.0))))))
    private val broken = model(listOf(mesh(listOf(attachment("standing_pivot", OldPivot, doubleArrayOf(0.0, 94.0, 0.0)), attachment("aim", OldPivot, doubleArrayOf(1.0, 2.0, 3.0))))))

    private fun installGame(model: ByteArray = vanilla, extra: List<VpkFile> = emptyList()) {
        val (dir, chunks) = vpk(listOf(VpkFile(modelPath, model, 0), VpkFile("materials/default.vtex_c", ByteArray(64) { 1 }, 1)) + extra)
        citadel.resolve("pak01_dir.vpk").writeBytes(dir)
        chunks.forEach { (index, bytes) -> citadel.resolve("pak01_${index.toString().padStart(3, '0')}.vpk").writeBytes(bytes) }
    }

    private fun installMod(number: Int = 5, model: ByteArray = broken, installId: String = "mod"): ManifestRecord =
        ManifestRecord(installId, root, "Test", Placement.On(Slot(number)), sets = listOf(placeSet(number, listOf(VpkFile(modelPath, model, 0), VpkFile("materials/test/test_color.vtex_c", texture)), installId)))

    private fun placeSet(number: Int, files: List<VpkFile>, installId: String = "mod", component: String = "test/pak01_dir.vpk", submission: Long = number + 100L): InstalledSet {
        val (dir, chunks) = vpk(files)
        val prefix = "pak${number.toString().padStart(2, '0')}"
        val owned = listOf(SetFile(VpkPart.Directory, Sha256.image(dir))) + chunks.map { (index, bytes) -> SetFile(VpkPart.Chunk(index), Sha256.image(bytes)) }
        addons.resolve("${prefix}_dir.vpk").writeBytes(dir)
        chunks.forEach { (index, bytes) -> addons.resolve("${prefix}_${index.toString().padStart(3, '0')}.vpk").writeBytes(bytes) }
        val source = InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, submission, 1, null))
        return InstalledSet(source, null, null, SetOrigin.Downloaded(ArchiveIdentity("/downloads/$installId.zip", 10, "a".repeat(64))), null, component, owned)
    }

    private fun checked(record: ManifestRecord, above: List<Provider> = emptyList()) = runBlocking {
        assertIs<CheckResult.Checked>(port.check(record, emptyList(), above)).check
    }

    private suspend fun repairedRecord(id: String): Pair<ManifestRecord, RetainedOriginal> {
        installGame()
        val record = installMod()
        val set = record.sets.single()
        val check = checked(record)
        val original = assertIs<RetainResult.Retained>(port.retain(record, set, id)).original
        val derived = assertIs<DeriveResult.Derived>(port.derive(record, set, original, check.sets.single(), check.basis, emptyList()))
        record.filesOf(set).forEachIndexed { index, file -> addons.resolve(file.name).writeBytes(data.resolve("staging/${derived.staging.value}/$index.vpk").readBytes()) }
        val files = set.files.zip(derived.vpks.members).map { (file, member) -> file.copy(image = FileImage(member.vpk.sizeBytes, member.vpk.sha256)) }
        return record.copy(sets = listOf(set.copy(files = files, derivation = derived.derivation))) to original
    }

    @Test
    fun aBrokenModelInASplitChunkIsFoundRetainedRepairedAndReadBack() = runBlocking<Unit> {
        installGame()
        val record = installMod()
        val set = record.sets.single()
        val check = checked(record)
        val setCheck = check.sets.single()
        val finding = setCheck.findings.single()
        assertEquals(FindingKind.Repairable, finding.kind)
        assertEquals(RuleIdentity(RootMotionAttachmentFrame.id, RootMotionAttachmentFrame.revision), finding.rule)
        assertEquals(set.files.map { it.image }, setCheck.inputs)

        val original = assertIs<RetainResult.Retained>(port.retain(record, set, "orig1")).original
        val kept = data.resolve("originals/orig1")
        assertContentEquals(addons.resolve("pak05_dir.vpk").readBytes(), kept.resolve("dir.vpk").readBytes())
        assertContentEquals(addons.resolve("pak05_000.vpk").readBytes(), kept.resolve("000.vpk").readBytes())
        assertFalse(data.resolve("originals/.incoming-orig1").exists(), "nothing half-made is left")

        val derived = assertIs<DeriveResult.Derived>(port.derive(record, set, original, setCheck, check.basis, emptyList()))
        assertEquals(listOf(finding.rule), derived.derivation.rules)
        assertEquals(1, derived.derivation.resources)
        assertEquals(finding.edits, derived.derivation.edits)
        assertEquals(setCheck.inputs, derived.derivation.originalFiles)
        assertEquals(check.basis.game, derived.derivation.context)
        assertEquals("test/pak01_dir.vpk", derived.vpks.key, "the set keeps its component")
        assertEquals(listOf(VpkPart.Directory, VpkPart.Chunk(0)), derived.vpks.members.map { it.part })
        assertEquals(listOf("pak05_dir.vpk", "pak05_000.vpk"), derived.vpks.members.map { it.vpk.path })

        val staging = data.resolve("staging/${derived.staging.value}")
        val dir = staging.resolve("0.vpk").readBytes()
        val chunk = staging.resolve("1.vpk").readBytes()
        val originalChunk = addons.resolve("pak05_000.vpk").readBytes()
        assertContentEquals(originalChunk, chunk.copyOf(originalChunk.size), "the chunk keeps every original byte and only grows")
        assertTrue(chunk.size > originalChunk.size)
        assertIs<VpkRead.Parsed>(VpkFormat.read(dir, dir.size.toLong()))
        val pack = VpkPackages.read(ArraySource(dir))
        val repaired = VpkPackages.payload(pack, pack.find(modelPath).single(), ArraySource(dir), { if (it == 0) ArraySource(chunk) else null })
        val context = RuleContext(mapOf(modelPath to vanilla)::get)
        assertTrue(ModelRepairs.assess(modelPath, repaired, context).all { it.outcome == RuleOutcome.NotAffected }, "the staged model is repaired")
        assertContentEquals(texture, VpkPackages.payload(pack, pack.find("materials/test/test_color.vtex_c").single(), ArraySource(dir), { null }), "the texture is the original")
        assertEquals(derived.vpks.members.map { FileImage(it.vpk.sizeBytes, it.vpk.sha256) }, listOf(FileImage(dir.size.toLong(), sha256Hex(dir)), FileImage(chunk.size.toLong(), sha256Hex(chunk))))

        val restored = assertIs<StagedOriginal.Staged>(port.stageOriginal(original, set))
        assertEquals(setCheck.inputs, restored.vpks.members.map { FileImage(it.vpk.sizeBytes, it.vpk.sha256) })
        assertEquals("test/pak01_dir.vpk", restored.vpks.key, "the original files keep the set's component")
        port.discard(derived.staging)
        port.discard(restored.staging)
        assertFalse(staging.exists())

        port.sweep(emptySet())
        assertFalse(kept.exists(), "an original no manifest names is deleted")
    }

    @Test
    fun samePathCopiesAreRepairedIndependentlyInDirectoryOrder() = runBlocking<Unit> {
        installGame()
        val custom = model(listOf(mesh(listOf(attachment("standing_pivot", OldPivot, doubleArrayOf(0.0, 110.0, 0.0))))))
        val inputs = listOf(broken, vanilla, custom)
        val set = placeSet(5, inputs.map { VpkFile(modelPath, it) })
        val record = ManifestRecord("mod", root, "Test", Placement.On(Slot(5)), sets = listOf(set))
        val check = checked(record)
        val findings = check.sets.single().findings
        assertEquals(listOf(0, 2), findings.map { it.occurrence })
        assertTrue(findings.all { it.kind == FindingKind.Repairable })
        val original = assertIs<RetainResult.Retained>(port.retain(record, set, "copies")).original
        val derived = assertIs<DeriveResult.Derived>(port.derive(record, set, original, check.sets.single(), check.basis, emptyList()))
        val context = RuleContext(mapOf(modelPath to vanilla)::get)
        val source = ArraySource(data.resolve("staging/${derived.staging.value}/0.vpk").readBytes())
        val pack = VpkPackages.read(source)
        val entries = pack.find(modelPath)
        assertEquals(inputs.size, entries.size)
        for ((index, input) in inputs.withIndex()) {
            val expected = (ModelRepairs.repair(modelPath, input, context) as? ResourceRepair.Repaired)?.bytes ?: input
            assertContentEquals(expected, VpkPackages.payload(pack, entries[index], source, { null }))
        }
    }

    @Test
    fun aRepairedSetIsCheckedByItsRetainedOriginal() = runBlocking<Unit> {
        val (repaired, original) = repairedRecord("orig2")
        val again = assertIs<CheckResult.Checked>(port.check(repaired, listOf(original), emptyList())).check
        assertEquals(FindingKind.Repairable, again.sets.single().findings.single().kind, "the original still has what the repair answers")
        assertEquals(repaired.sets.single().derivation?.originalFiles, again.sets.single().inputs)
    }

    @Test
    fun deployedRepairedMembersMustStillMatchBeforeOriginalFindingsCountAsApplied() = runBlocking<Unit> {
        val (repaired, original) = repairedRecord("deployed-proof")
        val evidence = assertIs<CheckResult.Checked>(port.check(repaired, listOf(original), emptyList())).check.basis.evidence
        assertEquals(emptySet(), port.changed(evidence))
        for (path in repaired.files.map { addons.resolve(it.name) }) {
            val bytes = path.readBytes()
            path.writeBytes(bytes.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() })
            assertTrue(port.changed(evidence).any { it.location == path.toString() }, "a changed deployed member invalidates an applied repair")
            assertIs<CheckResult.Failed>(port.check(repaired, listOf(original), emptyList()))
            Files.delete(path)
            assertTrue(port.changed(evidence).any { it.location == path.toString() }, "a missing deployed member invalidates an applied repair")
            assertIs<CheckResult.Failed>(port.check(repaired, listOf(original), emptyList()))
            path.writeBytes(bytes)
        }
        assertIs<CheckResult.Checked>(port.check(repaired, listOf(original), emptyList()))
    }

    @Test
    fun aRepairPlannedForAnotherGameOrRulesIsStale() = runBlocking<Unit> {
        installGame()
        val record = installMod()
        val set = record.sets.single()
        val check = checked(record)
        val original = assertIs<RetainResult.Retained>(port.retain(record, set, "orig3")).original
        assertIs<DeriveResult.Stale>(port.derive(record, set, original, check.sets.single(), check.basis.copy(game = "pak01:other"), emptyList()))
        assertIs<DeriveResult.Stale>(port.derive(record, set, original, check.sets.single(), check.basis.copy(rules = emptyList()), emptyList()))
        val otherInputs = check.sets.single().copy(inputs = listOf(FileImage(1, "0".repeat(64)), FileImage(2, "1".repeat(64))))
        assertIs<DeriveResult.Stale>(port.derive(record, set, original, otherInputs, check.basis, emptyList()))
        installGame(model(listOf(mesh(listOf(attachment("standing_pivot", Identity, doubleArrayOf(0.0, 0.0, 95.0)))))))
        assertIs<DeriveResult.Stale>(port.derive(record, set, original, check.sets.single(), check.basis, emptyList()), "the game's files changed under the plan")
        val staging = data.resolve("staging")
        assertTrue(!staging.exists() || Files.list(staging).use { it.count() } == 0L, "no refused derivation leaves staging behind")
    }

    @Test
    fun aRetainedOriginalChangedOutsideHideoutIsNeverUsed() = runBlocking<Unit> {
        installGame()
        val record = installMod()
        val set = record.sets.single()
        val check = checked(record)
        val original = assertIs<RetainResult.Retained>(port.retain(record, set, "orig4")).original
        data.resolve("originals/orig4/000.vpk").writeBytes(ByteArray(10))
        assertIs<DeriveResult.Refused>(port.derive(record, set, original, check.sets.single(), check.basis, emptyList()))
        assertIs<StagedOriginal.Failed>(port.stageOriginal(original, set))
    }

    @Test
    fun aSetChangedSinceItsRecordIsNotRetained() = runBlocking<Unit> {
        installGame()
        val record = installMod()
        addons.resolve("pak05_000.vpk").writeBytes(ByteArray(7))
        assertIs<RetainResult.Failed>(port.retain(record, record.sets.single(), "orig5"))
        assertFalse(data.resolve("originals/orig5").exists())
        val check = checked(record)
        assertEquals(emptyList(), check.sets.single().findings)
        assertTrue(check.sets.single().incomplete != null, "files that are not the recorded ones are not checked")
    }

    @Test
    fun aCopyLoadedFirstDecidesWhatIsChecked() {
        installGame()
        val record = installMod(number = 5)
        val (foreign) = vpk(listOf(VpkFile(modelPath, vanilla)))
        addons.resolve("pak03_dir.vpk").writeBytes(foreign)
        val unmanaged = checked(record, listOf(Provider(3, "game/citadel/addons/pak03_dir.vpk"))).sets.single()
        assertTrue(unmanaged.findings.isNotEmpty() && unmanaged.findings.all { it.kind == FindingKind.Unknown }, "a file Hideout does not manage wins: nothing is claimed")
        val managed = checked(record, listOf(Provider(3, "game/citadel/addons/pak03_dir.vpk", installId = "other"))).sets.single()
        assertEquals(emptyList(), managed.findings, "another of Hideout's mods wins, and its own check covers it")
        val below = checked(record, listOf(Provider(7, "game/citadel/addons/pak03_dir.vpk"))).sets.single()
        assertEquals(FindingKind.Repairable, below.findings.single().kind, "a file that loads later does not hide this one")
        val unknown = checked(record, listOf(Provider.Unreadable)).sets.single()
        assertTrue(unknown.findings.all { it.kind == FindingKind.Unknown })
    }

    @Test
    fun withoutTheGamesFilesNothingIsChecked() = runBlocking<Unit> {
        val record = installMod()
        assertIs<CheckResult.Failed>(port.check(record, emptyList(), emptyList()))
        assertEquals(null, port.game(root))
        installGame()
        assertEquals("pak01:${sha256Hex(citadel.resolve("pak01_dir.vpk").readBytes())}", port.game(root))
    }

    @Test
    fun aCurrentModIsNotAffectedAndAMixedOneNeedsItsAuthor() {
        installGame()
        val current = checked(installMod(model = model(listOf(mesh(listOf(attachment("standing_pivot", Identity, doubleArrayOf(0.0, 0.0, 94.0))))))))
        assertEquals(emptyList(), current.sets.single().findings)
        val mixed = checked(installMod(number = 6, installId = "mixed", model = model(listOf(mesh(listOf(attachment("standing_pivot", OldPivot, doubleArrayOf(0.0, 94.0, 0.0)), attachment("zipline", Identity, doubleArrayOf(0.0, 0.0, 9.0))))))))
        assertEquals(FindingKind.NeedsAuthor, mixed.sets.single().findings.single().kind)
    }

    @Test
    fun derivingRefusesAPlanWithNothingRepairable() = runBlocking<Unit> {
        installGame()
        val record = installMod()
        val set = record.sets.single()
        val check = checked(record)
        val original = assertIs<RetainResult.Retained>(port.retain(record, set, "orig6")).original
        val nothing = SetCheck(set.source, set.componentKey, check.sets.single().inputs, emptyList())
        assertIs<DeriveResult.Refused>(port.derive(record, set, original, nothing, check.basis, emptyList()))
    }

    @Test
    fun theGameIsNamedByItsBytesEvenWhenItsDirectoryKeepsItsLengthAndTime() = runBlocking<Unit> {
        installGame()
        val dir = citadel.resolve("pak01_dir.vpk")
        val time = Files.getLastModifiedTime(dir)
        val before = assertNotNull(port.game(root))
        val check = checked(installMod())
        val bytes = dir.readBytes().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        dir.writeBytes(bytes)
        Files.setLastModifiedTime(dir, time)
        assertEquals(time, Files.getLastModifiedTime(dir))
        assertTrue(port.game(root) != before, "the same length and time with other bytes is another game")
        assertTrue(port.changed(check.basis.evidence).any { it.location == dir.toString() }, "what a check read of it no longer holds")
    }

    @Test
    fun aDirectoryKeptSinceLongAgoIsStillReadAgainOnceItChanges() = runBlocking<Unit> {
        val settled = NioCompatibility(data.resolve("originals"), data.resolve("staging"), now = { Instant.now().plus(Duration.ofDays(1)) })
        installGame()
        val dir = citadel.resolve("pak01_dir.vpk")
        val first = assertNotNull(settled.game(root))
        assertEquals(first, settled.game(root))
        val time = Files.getLastModifiedTime(dir)
        Thread.sleep(20)
        dir.writeBytes(dir.readBytes().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() })
        Files.setLastModifiedTime(dir, time)
        val edited = assertNotNull(settled.game(root))
        assertTrue(edited != first, "an edit in place that keeps the length and time is another game")
        val next = citadel.resolve("pak01_dir.vpk.next")
        next.writeBytes(dir.readBytes().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() })
        Files.move(next, dir, StandardCopyOption.REPLACE_EXISTING)
        val updated = "pak01:${sha256Hex(dir.readBytes())}"
        assertEquals(updated, settled.game(root), "a directory moved into place is another game")
        assertEquals(updated, assertIs<CheckResult.Checked>(settled.check(installMod(), emptyList(), emptyList())).check.basis.game)
    }

    @Test
    fun theGamesFilesAreFoundByTheirOwnNameEvenWhenTwoNamesShareAHash() {
        val one = ByteArray(40) { 1 }
        val two = ByteArray(40) { 2 }
        assertEquals("models/zg9jz0h.vmdl_c".hashCode(), "models/1ro7kxj.vmdl_c".hashCode())
        installGame(extra = listOf(VpkFile("models/zg9jz0h.vmdl_c", one, 0), VpkFile("models/1ro7kxj.vmdl_c", two, 0), VpkFile("models/twice.vmdl_c", one, 0), VpkFile("models/twice.vmdl_c", two, 0)))
        GamePackages.open(game, GameDirectories(), log = null).use { packages ->
            assertContentEquals(one, packages.resource("models/zg9jz0h.vmdl_c"))
            assertContentEquals(two, packages.resource("MODELS/1ro7kxj.vmdl_c"))
            assertEquals(null, packages.resource("models/twice.vmdl_c"), "a name the game holds twice is not one file")
            assertEquals(null, packages.resource("models/absent.vmdl_c"))
        }
    }

    @Test
    fun aGameChunkReplacedAfterACheckIsReadAgainNotFromTheFileItReplaced() = runBlocking<Unit> {
        installGame()
        val record = installMod()
        val first = checked(record)
        assertEquals(FindingKind.Repairable, first.sets.single().findings.single().kind)
        val chunk = citadel.resolve("pak01_000.vpk")
        val replaced = chunk.readBytes().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        val next = citadel.resolve("pak01_000.vpk.new")
        next.writeBytes(replaced)
        Files.move(next, chunk, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        assertTrue(port.changed(first.basis.evidence).any { it.location == chunk.toString() }, "the check's read of the game's chunk no longer holds")
        val second = checked(record).sets.single().findings.single()
        assertEquals(FindingKind.Unknown, second.kind, "the game's model is read from the chunk as it is now")
        assertTrue("CRC32" in second.detail, "and it no longer matches the directory's CRC32: ${second.detail}")
    }

    @Test
    fun aRepairedSetIsCheckedOnlyFromAnOriginalThatStillHoldsItsBytes() = runBlocking<Unit> {
        val (repaired, original) = repairedRecord("orig7")
        assertIs<CheckResult.Checked>(port.check(repaired, listOf(original), emptyList()))
        val kept = data.resolve("originals/orig7/000.vpk")
        kept.writeBytes(kept.readBytes().also { it[0] = (it[0] + 1).toByte() })
        assertIs<CheckResult.Failed>(port.check(repaired, listOf(original), emptyList()), "a kept copy of the right length with other bytes is not the original")
        assertIs<CheckResult.Failed>(port.check(repaired, listOf(original.copy(installId = "someone-else")), emptyList()), "nor is another installation's")
    }

    @Test
    fun anEarlierSetOfTheSameModWinsItsModelAndItsSoundTable() {
        val sounds = "scripts/tagged_sounds/test_anim_sounds.vdata"
        val current = listOf(mesh(listOf(attachment("standing_pivot", Identity, doubleArrayOf(0.0, 0.0, 94.0)))))
        val newBinding = keyValues("\tTaggedSoundSettings_t = { m_taggedSounds = [ resource:\"$sounds\" ] }\n")
        val oldBinding = keyValues("\tCitadelTaggedSoundSettings_t = { m_taggedSounds = [ resource:\"$sounds\" ] }\n")
        installGame(model(current, data(keyValues = newBinding)), extra = listOf(VpkFile("${sounds}_c", table("CTaggedSound_t"), 1)))

        val body = placeSet(5, listOf(VpkFile(modelPath, model(current, data(keyValues = newBinding)))), "two", "body/pak01_dir.vpk", submission = 500)
        val old = placeSet(6, listOf(VpkFile(modelPath, broken, 0)), "two", "old/pak01_dir.vpk", submission = 500)
        val models = ManifestRecord("two", root, "Two", Placement.On(Slot(5)), sets = listOf(body, old))
        val first = Provider(5, "game/citadel/addons/pak05_dir.vpk", "two", body.files[0].image)
        assertEquals(FindingKind.Repairable, checked(models).sets[1].findings.single().kind, "on its own, the later set's model would be repairable")
        assertEquals(emptyList(), checked(models, listOf(first)).sets[1].findings, "the earlier set's model is the one that loads")

        val table = placeSet(7, listOf(VpkFile("${sounds}_c", table("CTaggedSound_t"))), "voice", "table/pak01_dir.vpk", submission = 700)
        val voice = placeSet(8, listOf(VpkFile(modelPath, model(current, data(keyValues = oldBinding)))), "voice", "voice/pak01_dir.vpk", submission = 700)
        val sound = ManifestRecord("voice", root, "Voice", Placement.On(Slot(7)), sets = listOf(table, voice))
        val rule = RuleIdentity(TaggedSoundBinding.id, TaggedSoundBinding.revision)
        assertEquals(FindingKind.Repairable, checked(sound).sets[1].findings.single { it.rule == rule }.kind)
        val shadowed = checked(sound, listOf(Provider(7, "game/citadel/addons/pak07_dir.vpk", "voice", table.files[0].image))).sets[1]
        assertEquals(FindingKind.Unknown, shadowed.findings.single { it.rule == rule }.kind, "the earlier set replaces the sound table, so the binding is not proven")
    }

    @Test
    fun anOffModsEarlierSetWinsItsModelByItsPlaceInTheMod() {
        installGame()
        val body = placeSet(5, listOf(VpkFile(modelPath, vanilla)), "two", "body/pak01_dir.vpk", submission = 500)
        val old = placeSet(6, listOf(VpkFile(modelPath, broken, 0)), "two", "old/pak01_dir.vpk", submission = 500)
        val on = ManifestRecord("two", root, "Two", Placement.On(Slot(5)), sets = listOf(body, old))
        val off = on.placed(Placement.Off)
        for ((from, to) in on.files.zip(off.files)) game.resolve(to.relativePath(off)).also { it.parent.createDirectories(); Files.move(game.resolve(from.relativePath(on)), it) }
        assertEquals(FindingKind.Repairable, checked(off).sets[1].findings.single().kind, "on its own, the later set's model would be repairable")
        val first = Provider(1, off.files.first().relativePath(off), "two", body.files[0].image)
        assertEquals(emptyList(), checked(off, listOf(first)).sets[1].findings, "off, its first set still comes before its second, by its place in the mod")
        assertEquals(FindingKind.Repairable, checked(off, listOf(first.copy(rank = 2))).sets[1].findings.single().kind, "a provider is above a set only by a lower rank")
    }

    @Test
    fun evidenceHoldsExactlyWhileWhatWasReadReadsTheSame() = runBlocking<Unit> {
        installGame()
        val record = installMod()
        val evidence = checked(record).basis.evidence
        assertEquals(emptySet(), port.changed(evidence))
        val dir = addons.resolve("pak05_dir.vpk")
        val bytes = dir.readBytes()
        val at = (0..bytes.size - texture.size).first { start -> (texture.indices).all { bytes[start + it] == texture[it] } }
        dir.writeBytes(bytes.also { it[at + 10] = (it[at + 10] + 1).toByte() })
        assertEquals(emptySet(), port.changed(evidence), "a texture the check never read decides nothing it found")
        val chunk = addons.resolve("pak05_000.vpk")
        chunk.writeBytes(chunk.readBytes().also { it[3] = (it[3] + 1).toByte() })
        assertEquals(setOf(chunk.toString()), port.changed(evidence).map { it.location }.toSet(), "the model it read changed")
        Files.delete(chunk)
        assertEquals(setOf(chunk.toString()), port.changed(evidence).map { it.location }.toSet())
    }

    @Test
    fun aSweepRemovesHalfMadeAndUnnamedCopiesOnly() = runBlocking<Unit> {
        val originals = data.resolve("originals").createDirectories()
        for (name in listOf(".incoming-x1", "gone", "kept")) originals.resolve(name).createDirectories().resolve("dir.vpk").writeBytes(ByteArray(3))
        port.sweep(setOf("kept"))
        assertEquals(listOf("kept"), Files.list(originals).use { paths -> paths.map { it.fileName.toString() }.toList() })
    }
}
