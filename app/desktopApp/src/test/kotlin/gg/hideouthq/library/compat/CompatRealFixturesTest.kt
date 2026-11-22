package gg.hideouthq.library.compat

import gg.hideouthq.library.measure
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CompatRealFixturesTest {
    private val project = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.firstOrNull { Files.exists(it.resolve("project.yaml")) }

    private fun folder(variable: String, default: String?): Path? =
        (System.getenv(variable)?.let(Path::of) ?: default?.let { project?.resolve(it) })?.takeIf { Files.isDirectory(it) }

    private fun models(): List<Pair<String, ByteArray>> {
        val root = folder("HIDEOUT_COMPAT_FIXTURES", "build/mod-compat-research/fixtures")
        assumeTrue("no compiled model fixtures here; set HIDEOUT_COMPAT_FIXTURES", root != null)
        return Files.walk(root!!).use { paths -> paths.filter { it.isRegularFile() && it.extension == "vmdl_c" }.sorted().toList() }
            .map { root.relativize(it).toString() to it.readBytes() }
    }

    @Test
    fun everyKv3BlockOfEveryFixtureIsWrittenBackExactly() {
        val report = check(models())
        println(report)
        assertTrue(report.blocks > 0)
        assertEquals(emptyList(), report.unsupported)
    }

    @Test
    fun everyModelInEveryModVpkIsWrittenBackExactly() {
        val root = folder("HIDEOUT_COMPAT_VPKS", null)
        assumeTrue("no mod VPKs here; set HIDEOUT_COMPAT_VPKS", root != null)
        val models = mutableListOf<Pair<String, ByteArray>>()
        val vpks = Files.list(root!!).use { paths -> paths.filter { it.isRegularFile() && it.extension == "vpk" && !it.fileName.toString().matches(Regex(".*_[0-9]{3}\\.vpk")) }.sorted().toList() }
        for (vpk in vpks) {
            FileSource.open(vpk).use { dir ->
                val pack = VpkPackages.read(dir)
                for (entry in pack.entries.filter { it.extension == "vmdl_c" }) {
                    models += "${vpk.fileName}:${entry.path}" to VpkPackages.payload(pack, entry, dir, { null })
                }
            }
        }
        val report = check(models)
        println(report)
        assertTrue(report.blocks > 0)
        assertTrue(report.unsupported.all { it.contains("are not padding") || it.contains("data after its blocks") }, report.unsupported.joinToString("\n"))
    }

    private fun game(): GamePackages {
        val root = System.getenv("HIDEOUT_DEADLOCK_ROOT")?.let(Path::of)?.takeIf { Files.isDirectory(it) }
        assumeTrue("no Deadlock installation here; set HIDEOUT_DEADLOCK_ROOT", root != null)
        return openGame(root!!)
    }

    private fun openGame(root: Path) = GamePackages.open(root, GameDirectories(), log = null)

    private fun fixture(relative: String): ByteArray {
        val root = folder("HIDEOUT_COMPAT_FIXTURES", "build/mod-compat-research/fixtures")
        val file = root?.resolve(relative)
        assumeTrue("the fixture $relative is not here", file != null && Files.isRegularFile(file))
        return file!!.readBytes()
    }

    private fun output(name: String, bytes: ByteArray) {
        val folder = System.getenv("HIDEOUT_COMPAT_OUT")?.let(Path::of) ?: return
        Files.createDirectories(folder)
        Files.write(folder.resolve(name), bytes)
    }

    private fun attachments(bytes: ByteArray): Map<Int, Map<String, Pair<List<Long>, List<Long>>>> {
        val file = ResourceContainer.read(bytes)
        return file.blocks("MDAT").associate { block ->
            val mesh = Kv3Binary.decode(file.payload(block)).root as Kv3.Obj
            block.index to list(mesh["m_attachments"]!!).associate { item ->
                val value = (item as Kv3.Obj)["value"] as Kv3.Obj
                val rotation = list(list(value["m_vInfluenceRotations"]!!)[0]).map { (it as Kv3.Number).bits }
                val offset = list(list(value["m_vInfluenceOffsets"]!!)[0]).map { (it as Kv3.Number).bits }
                (value["m_name"] as Kv3.Str).value to (rotation to offset)
            }
        }
    }

    private fun influence(bytes: ByteArray, block: Int, name: String): String {
        val file = ResourceContainer.read(bytes)
        val mesh = Kv3Binary.decode(file.payload(file.blocks.single { it.index == block })).root as Kv3.Obj
        val value = list(mesh["m_attachments"]!!).map { (it as Kv3.Obj)["value"] as Kv3.Obj }.single { (it["m_name"] as Kv3.Str).value == name }
        return (list(value["m_influenceNames"]!!)[0] as Kv3.Str).value
    }

    @Test
    fun silverThugsWerewolfIsRepairedExactlyAgainstTheInstalledGame() {
        val mod = fixture("silver-thug-models/models/heroes_wip/werewolf/werewolf.vmdl_c")
        game().use { game ->
            val path = "models/heroes_wip/werewolf/werewolf.vmdl_c"
            val context = RuleContext(game::resource)
            val verdicts = ModelRepairs.assess(path, mod, context)
            println(verdicts.joinToString("\n") { "${it.rule.id}: ${it.outcome}" })
            assertTrue(verdicts.all { it.outcome is RuleOutcome.Repairable }, verdicts.toString())
            val repaired = assertIs<ResourceRepair.Repaired>(ModelRepairs.repair(path, mod, context))
            output("silver-thug-werewolf.vmdl_c", repaired.bytes)
            val before = ResourceContainer.read(mod)
            val after = ResourceContainer.read(repaired.bytes)
            for ((a, b) in before.blocks.zip(after.blocks)) {
                if (a.type != "MDAT" && a.type != "DATA") assertContentEquals(before.payload(a), after.payload(b), "${a.type}#${a.index} changed")
            }
            val old = attachments(mod)
            val new = attachments(repaired.bytes)
            for ((block, named) in old) for ((name, values) in named) {
                val (rotation, offset) = values
                val (newRotation, newOffset) = new.getValue(block).getValue(name)
                if (influence(mod, block, name) != "root_motion") {
                    assertEquals(values, newRotation to newOffset, "$name is not on the root and must not change")
                    continue
                }
                assertEquals(listOf(offset[2], offset[0], offset[1]), newOffset, "$name's offset")
                val r = rotation.map { java.lang.Float.intBitsToFloat(it.toInt()).toDouble() }
                val q = Quat(0.5, 0.5, 0.5, 0.5) * Quat(r[0], r[1], r[2], r[3])
                assertEquals(listOf(q.x, q.y, q.z, q.w).map { java.lang.Float.floatToRawIntBits(it.toFloat()).toLong() and 0xFFFFFFFFL }, newRotation, "$name's rotation")
            }
            val text = ((Kv3Binary.decode(after.payload(after.blocks("DATA").single())).root as Kv3.Obj)["m_modelInfo"] as Kv3.Obj)["m_keyValueText"] as Kv3.Str
            val original = ((Kv3Binary.decode(before.payload(before.blocks("DATA").single())).root as Kv3.Obj)["m_modelInfo"] as Kv3.Obj)["m_keyValueText"] as Kv3.Str
            assertEquals(original.value.replace("CitadelTaggedSoundSettings_t =", "TaggedSoundSettings_t ="), text.value)
            val again = ModelRepairs.assess(path, repaired.bytes, context)
            assertTrue(again.all { it.outcome == RuleOutcome.NotAffected }, again.toString())
        }
    }

    @Test
    fun vanillaModelsOfTheInstalledGameAreNotAffected() {
        game().use { game ->
            for (path in listOf("models/heroes_wip/werewolf/werewolf.vmdl_c", "models/heroes_staging/ghost/ghost.vmdl_c", "models/heroes_wip/frank/frank.vmdl_c")) {
                val vanilla = game.resource(path) ?: continue
                val verdicts = ModelRepairs.assess(path, vanilla, RuleContext(game::resource))
                assertTrue(verdicts.all { it.outcome == RuleOutcome.NotAffected }, "$path: $verdicts")
            }
        }
    }

    @Test
    fun aRealInstalledModIsCheckedRetainedAndRepairedWithoutTouchingTheGame() {
        val name = System.getenv("HIDEOUT_COMPAT_REAL_SET")
        assumeTrue("no real set named; set HIDEOUT_COMPAT_REAL_SET to a pakNN_dir.vpk in addons", name != null && name.matches(Regex("pak[0-9]{2}_dir\\.vpk")))
        game().close()
        val root = Path.of(System.getenv("HIDEOUT_DEADLOCK_ROOT"))
        val file = root.resolve("game/citadel/addons").resolve(name!!)
        assumeTrue("$file is not there", Files.isRegularFile(file))
        val before = Files.getLastModifiedTime(file) to Files.size(file)
        val image = measure(file)
        val number = name.substring(3, 5).toInt()
        val source = gg.hideouthq.core.library.InstalledSource.Remote(gg.hideouthq.core.library.SubmissionIdentity(gg.hideouthq.core.library.SubmissionKind.Mod, 1, 1, null))
        val set = gg.hideouthq.core.library.InstalledSet(source, null, null, gg.hideouthq.core.library.SetOrigin.Downloaded(gg.hideouthq.core.library.ArchiveIdentity("/tmp/real.zip", 1, "a".repeat(64))), null, name,
            listOf(gg.hideouthq.core.library.SetFile(gg.hideouthq.core.library.VpkPart.Directory, image)))
        val record = gg.hideouthq.core.library.ManifestRecord("real", gg.hideouthq.core.library.GameRoot(root.toString()), "Real", gg.hideouthq.core.library.Placement.On(gg.hideouthq.core.library.Slot(number)), sets = listOf(set))
        val temp = Files.createTempDirectory("hideout-real-compat")
        try {
            val port = NioCompatibility(temp.resolve("originals"), temp.resolve("staging"))
            kotlinx.coroutines.runBlocking {
                val check = assertIs<gg.hideouthq.core.library.CheckResult.Checked>(port.check(record, emptyList(), emptyList())).check
                val setCheck = check.sets.single()
                println("real set $name: findings ${setCheck.findings.map { "${it.rule}:${it.kind}:${it.resource}:${it.detail}" }}")
                assumeTrue("$name has nothing repairable", setCheck.findings.any { it.kind == gg.hideouthq.core.library.FindingKind.Repairable })
                val original = assertIs<gg.hideouthq.core.library.RetainResult.Retained>(port.retain(record, set, "real1")).original
                val derived = assertIs<gg.hideouthq.core.library.DeriveResult.Derived>(port.derive(record, set, original, setCheck, check.basis, emptyList()))
                println("derived: ${derived.derivation}")
                val staged = temp.resolve("staging/${derived.staging.value}/0.vpk")
                output(name, staged.readBytes())
                val unplaced = assertIs<gg.hideouthq.core.library.CheckResult.Failed>(port.check(
                    record.copy(sets = listOf(set.copy(files = listOf(set.files.single().copy(image = measure(staged))), derivation = derived.derivation))),
                    listOf(original), emptyList(),
                ))
                assertEquals("the deployed repaired files do not match their record", unplaced.reason, "staged output is not an applied repair")
                val again = assertIs<gg.hideouthq.core.library.CheckResult.Checked>(port.check(record, emptyList(), emptyList())).check
                assertEquals(setCheck.findings, again.sets.single().findings, "the untouched installed mod still needs its repair")
                FileSource.open(staged).use { dir ->
                    val pack = VpkPackages.read(dir)
                    for (finding in setCheck.findings.filter { it.kind == gg.hideouthq.core.library.FindingKind.Repairable }) {
                        val bytes = VpkPackages.payload(pack, pack.find(finding.resource).single(), dir, { null })
                        openGame(root).use { gamePacks ->
                            val verdicts = ModelRepairs.assess(finding.resource, bytes, RuleContext(gamePacks::resource))
                            assertTrue(verdicts.none { it.outcome is RuleOutcome.Repairable }, "${finding.resource} reads back repaired: $verdicts")
                        }
                    }
                }
            }
        } finally {
            temp.toFile().deleteRecursively()
        }
        assertEquals(before, Files.getLastModifiedTime(file) to Files.size(file), "the installed file is untouched")
        assertEquals(image, measure(file))
    }

    private data class Report(var files: Int = 0, var blocks: Int = 0, val kinds: MutableMap<String, Int> = sortedMapOf(), val unsupported: MutableList<String> = mutableListOf()) {
        override fun toString(): String = "files=$files kv3Blocks=$blocks kinds=$kinds unsupported=${unsupported.size}\n" + unsupported.take(40).joinToString("\n")
    }

    private fun check(models: List<Pair<String, ByteArray>>): Report {
        val report = Report()
        for ((name, bytes) in models) {
            report.files++
            val file = try {
                ResourceContainer.read(bytes)
            } catch (e: Kv3Failure) {
                report.unsupported += "$name: ${e.message}"
                continue
            }
            assertContentEquals(bytes, ResourceContainer.rewrite(file, emptyMap()), "$name is not reproduced by an empty rewrite")
            for (block in file.blocks) {
                val payload = file.payload(block)
                if (!Kv3Binary.isKv3(payload)) continue
                val document = try {
                    Kv3Binary.decode(payload)
                } catch (e: Kv3Failure) {
                    report.unsupported += "$name ${block.type}#${block.index}: ${e.message}"
                    continue
                }
                report.blocks++
                report.kinds.merge("${block.type} v${document.version} c${document.compression}", 1, Int::plus)
                val again = Kv3Binary.decode(Kv3Binary.encode(document))
                assertEquals(document.root, again.root, "$name ${block.type}#${block.index} changed when written back")
                assertEquals(document.strings, again.strings.take(document.strings.size), "$name ${block.type}#${block.index} reordered its strings")
            }
        }
        return report
    }
}
