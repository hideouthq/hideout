package gg.hideouthq.library.compat

import kotlin.math.abs
import kotlin.math.sqrt

internal class RuleContext(val game: (String) -> ByteArray?, val set: (String) -> ByteArray? = { null }, val above: (String) -> Boolean = { false })

internal data class FieldEdit(val block: Int, val field: String, val before: Kv3, val after: Kv3)

internal sealed interface RuleOutcome {
    data object NotAffected : RuleOutcome

    class Repairable(val edits: List<FieldEdit>, val apply: (ModelDocument) -> Unit) : RuleOutcome

    data class Unrepairable(val reason: String) : RuleOutcome

    data class Declined(val reason: String) : RuleOutcome
}

internal interface ModelRule {
    val id: String
    val revision: Int

    fun assess(model: ModelDocument, context: RuleContext): RuleOutcome
}

internal class ModelDocument(val path: String, val file: ResourceFile) {
    private val decoded = HashMap<Int, Kv3Document>()
    private val changed = LinkedHashMap<Int, Kv3Document>()

    val replaced: Map<Int, Kv3Document> get() = changed

    fun fork(): ModelDocument = ModelDocument(path, file).also {
        it.decoded.putAll(decoded)
        it.changed.putAll(changed)
    }

    fun blocks(type: String): List<ResourceBlock> = file.blocks(type)

    fun kv3(block: ResourceBlock): Kv3Document = changed[block.index] ?: decoded.getOrPut(block.index) {
        val payload = file.payload(block)
        if (!Kv3Binary.isKv3(payload)) throw Kv3Failure("block ${block.type} is not KV3")
        Kv3Binary.decode(payload)
    }

    fun replace(block: ResourceBlock, document: Kv3Document) {
        changed[block.index] = document
    }

    fun data(): Kv3.Obj {
        val block = blocks("DATA").singleOrNull() ?: throw Kv3Failure("the model does not have exactly one DATA block")
        return kv3(block).root as? Kv3.Obj ?: throw Kv3Failure("the DATA block is not an object")
    }

    fun write(): ByteArray = ResourceContainer.rewrite(file, changed.mapValues { Kv3Binary.encode(it.value) })
}

internal data class Quat(val x: Double, val y: Double, val z: Double, val w: Double) {
    operator fun times(b: Quat): Quat = Quat(
        w * b.x + x * b.w + y * b.z - z * b.y,
        w * b.y - x * b.z + y * b.w + z * b.x,
        w * b.z + x * b.y - y * b.x + z * b.w,
        w * b.w - x * b.x - y * b.y - z * b.z,
    )

    val conjugate: Quat get() = Quat(-x, -y, -z, w)

    fun near(other: Quat, tolerance: Double): Boolean =
        abs(x - other.x) <= tolerance && abs(y - other.y) <= tolerance && abs(z - other.z) <= tolerance && abs(w - other.w) <= tolerance

    fun sameRotation(other: Quat, tolerance: Double): Boolean = near(other, tolerance) || near(Quat(-other.x, -other.y, -other.z, -other.w), tolerance)

    companion object {
        val Identity = Quat(0.0, 0.0, 0.0, 1.0)
    }
}

internal class AxisRotation private constructor(val quat: Quat, private val source: IntArray, private val sign: IntArray) {
    fun rotate(v: List<Kv3.Number>): List<Kv3.Number> = List(3) { i -> negate(v[source[i]], sign[i] < 0) }

    private fun negate(n: Kv3.Number, flip: Boolean): Kv3.Number = if (!flip) n else when (n.type) {
        Kv3Type.Float -> n.copy(bits = n.bits xor 0x80000000L)
        Kv3Type.Double -> n.copy(bits = n.bits xor Long.MIN_VALUE)
        Kv3Type.DoubleZero -> n.copy(type = Kv3Type.Double, bits = Long.MIN_VALUE)
        Kv3Type.DoubleOne -> n.copy(type = Kv3Type.Double, bits = java.lang.Double.doubleToRawLongBits(-1.0))
        else -> throw Kv3Failure("a vector component of type ${n.type} cannot be negated in place")
    }

    companion object {
        private val Exact = doubleArrayOf(0.0, 0.5, -0.5, 1.0, -1.0, sqrt(0.5), -sqrt(0.5))

        fun of(q: Quat): AxisRotation? {
            fun snap(c: Double): Double? = Exact.firstOrNull { abs(it - c) <= 1e-4 }
            val exact = Quat(snap(q.x) ?: return null, snap(q.y) ?: return null, snap(q.z) ?: return null, snap(q.w) ?: return null)
            if (abs(exact.x * exact.x + exact.y * exact.y + exact.z * exact.z + exact.w * exact.w - 1.0) > 1e-9) return null
            val (x, y, z, w) = exact
            val m = arrayOf(
                doubleArrayOf(1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)),
                doubleArrayOf(2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)),
                doubleArrayOf(2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)),
            )
            val source = IntArray(3)
            val sign = IntArray(3)
            for (i in 0 until 3) {
                val nonzero = (0 until 3).filter { abs(m[i][it]) > 0.5 }
                if (nonzero.size != 1 || (0 until 3).any { it != nonzero[0] && abs(m[i][it]) > 1e-9 } || abs(abs(m[i][nonzero[0]]) - 1.0) > 1e-9) return null
                source[i] = nonzero[0]
                sign[i] = if (m[i][nonzero[0]] > 0) 1 else -1
            }
            if (source.distinct().size != 3) return null
            return AxisRotation(exact, source, sign)
        }
    }
}

/**
 * Root-motion attachments compiled before the October 2026 update keep the root bone's inverse rotation as their own
 * and their offsets in the root's frame; the current game expects them in the model's frame, with the old pivot
 * becoming the identity.
 */
internal object RootMotionAttachmentFrame : ModelRule {
    override val id: String = "root-motion-attachment-frame"
    override val revision: Int = 2

    private const val Root = "root_motion"
    private const val PivotTolerance = 1e-4
    private const val MatchRotation = 2e-3

    private class Attachment(
        val block: ResourceBlock,
        val index: Int,
        val name: String,
        val influences: List<String>,
        val count: Long,
        val rotationValue: Kv3,
        val offsetValue: Kv3,
        val weight: Double,
        val rootTransform: Boolean,
    ) {
        val rotation: Quat = vector(rotationValue, 4).let { Quat(it[0], it[1], it[2], it[3]) }
        val offset: List<Double> = vector(offsetValue, 3)
        val root: Boolean get() = count == 1L && influences[0] == Root
        val blended: Boolean get() = count > 1 && Root in influences.take(count.toInt())
    }

    override fun assess(model: ModelDocument, context: RuleContext): RuleOutcome {
        if (!model.path.endsWith(".vmdl_c")) return RuleOutcome.NotAffected
        val mod = try {
            read(model)
        } catch (e: Kv3Failure) {
            return RuleOutcome.Declined(e.message)
        }
        if (mod.attachments.none { it.root || it.blended }) return RuleOutcome.NotAffected
        val axis = mod.axis ?: return RuleOutcome.Declined("the model's root bone is not rotated in whole quarter turns")
        val pivot = axis.quat.conjugate
        val roots = mod.attachments.filter { it.root }
        val canonical = roots.filter { it.rotation.near(pivot, PivotTolerance) }
        val vanilla = context.game(model.path)?.let { bytes ->
            try {
                read(ModelDocument(model.path, ResourceContainer.read(bytes)))
            } catch (e: Kv3Failure) {
                return RuleOutcome.Declined("the game's own model could not be read: ${e.message}")
            }
        } ?: return RuleOutcome.Declined("the game has no model at this path to compare with")
        val gameAxis = vanilla.axis ?: return RuleOutcome.Declined("the game's root bone is not rotated in whole quarter turns")
        if (!gameAxis.quat.sameRotation(axis.quat, 1e-9)) return RuleOutcome.Declined("the game's root bone is rotated differently from the model's")
        val gameRoots = vanilla.attachments.filter { it.root }
        if (gameRoots.any { it.rotation.near(gameAxis.quat.conjugate, PivotTolerance) }) return RuleOutcome.Declined("the game's own model still uses the old pivots")
        if (gameRoots.none { it.rotation.sameRotation(Quat.Identity, PivotTolerance) }) return RuleOutcome.Declined("the game's own model does not show the current pivots")
        val current = gameRoots.groupBy { it.name }
        fun converted(a: Attachment) = axis.quat * a.rotation
        fun matchesGame(rotation: Quat, offset: List<Double>, game: Attachment) =
            rotation.sameRotation(game.rotation, MatchRotation) && offset.indices.all { abs(offset[it] - game.offset[it]) <= 0.01 + 1e-4 * abs(game.offset[it]) }
        fun inGameFrame(a: Attachment) = current[a.name].orEmpty().any { matchesGame(a.rotation, a.offset, it) }
        fun hasCurrentRotation(a: Attachment) = current[a.name].orEmpty().any { a.rotation.sameRotation(it.rotation, MatchRotation) }
        fun inOldFrame(a: Attachment) = !hasCurrentRotation(a) && current[a.name].orEmpty().any { converted(a).sameRotation(it.rotation, MatchRotation) }

        if (canonical.isEmpty()) {
            val old = roots.filter { inOldFrame(it) }
            // An attachment the game keeps as an identity pivot that is an identity in the model too is the current compiler's signature.
            val newPivots = roots.any { a -> a.rotation.sameRotation(Quat.Identity, PivotTolerance) && current[a.name].orEmpty().any { it.rotation.sameRotation(Quat.Identity, PivotTolerance) } }
            return when {
                old.isNotEmpty() -> RuleOutcome.Unrepairable("${old.map { it.name }.distinct().sorted()} match the game's attachments only in the old frame, without the old pivot to prove the whole model is")
                mod.attachments.any { it.blended } -> RuleOutcome.Declined("a root attachment blends the root with other bones")
                newPivots || roots.all { inGameFrame(it) } -> RuleOutcome.NotAffected
                else -> RuleOutcome.Declined("which frame the root attachments are in cannot be told")
            }
        }
        fun conversionMatchesEveryCandidate(a: Attachment): Boolean {
            val candidates = current[a.name].orEmpty()
            if (candidates.isEmpty()) return false
            val rotation = converted(a)
            val offset = axis.rotate(numbers(a.offsetValue, 3)).map { it.real }
            return candidates.all { matchesGame(rotation, offset, it) }
        }
        val mixed = roots.filter {
            it !in canonical && (hasCurrentRotation(it) ||
                it.rotation.sameRotation(Quat.Identity, PivotTolerance) && !conversionMatchesEveryCandidate(it))
        }
        if (mixed.isNotEmpty()) return RuleOutcome.Unrepairable("${mixed.map { it.name }.distinct().sorted()} look converted already while others keep the old pivot")
        val pivotNames = canonical.map { it.name }.toSet()
        val inconsistent = roots.filter { it.name in pivotNames && it !in canonical }
        if (inconsistent.isNotEmpty()) return RuleOutcome.Unrepairable("${inconsistent.map { it.name }.distinct().sorted()} keep the old pivot in some meshes but not others")
        mod.attachments.firstOrNull { it.blended }?.let { return RuleOutcome.Unrepairable("${it.name} blends the root with other bones") }
        roots.firstOrNull { it.rootTransform }?.let { return RuleOutcome.Unrepairable("${it.name} uses the root transform flag") }
        roots.firstOrNull { abs(it.weight - 1.0) > 1e-6 }?.let { return RuleOutcome.Unrepairable("${it.name} does not weigh the root fully") }
        if (abs(mod.rootScale - 1.0) > PivotTolerance) return RuleOutcome.Unrepairable("the root bone is scaled")
        if (mod.externalMeshes) return RuleOutcome.Declined("the model refers to meshes outside it")

        val edits = roots.flatMap { a ->
            val rotation = rotated(a.rotationValue, axis.quat)
            val offset = withComponents(a.offsetValue, axis.rotate(numbers(a.offsetValue, 3)))
            listOf(
                "m_vInfluenceRotations" to (a.rotationValue to rotation),
                "m_vInfluenceOffsets" to (a.offsetValue to offset),
            ).flatMap { (field, values) ->
                list(values.first).zip(list(values.second)).mapIndexedNotNull { i, (before, after) ->
                    if (before == after) null else FieldEdit(a.block.index, "m_attachments/${a.index}/value/$field/0/$i", before, after)
                }
            }
        }
        return RuleOutcome.Repairable(edits) { target -> convert(target, axis) }
    }

    private class Read(val attachments: List<Attachment>, val axis: AxisRotation?, val rootScale: Double, val externalMeshes: Boolean)

    private fun read(model: ModelDocument): Read {
        val data = model.data()
        val skeleton = data["m_modelSkeleton"] as? Kv3.Obj ?: throw Kv3Failure("the model has no skeleton")
        val names = strings(skeleton["m_boneName"] ?: throw Kv3Failure("the skeleton has no bone names"))
        val parents = list(skeleton["m_nParent"] ?: throw Kv3Failure("the skeleton has no parents")).map { integer(it) }
        val rotations = list(skeleton["m_boneRotParent"] ?: throw Kv3Failure("the skeleton has no rotations"))
        if (parents.size != names.size || rotations.size != names.size) throw Kv3Failure("the skeleton's lists differ in length")
        val roots = names.indices.filter { names[it] == Root }
        val externalMeshes = (data["m_refMeshes"] as? Kv3.List)?.items?.isNotEmpty() ?: (data["m_refMeshes"] != null && data["m_refMeshes"] !is Kv3.List)
        var axis: AxisRotation? = null
        var scale = 1.0
        if (roots.isNotEmpty()) {
            if (roots.size != 1) throw Kv3Failure("the skeleton has more than one root_motion bone")
            val root = roots.single()
            if (parents[root] != -1L) throw Kv3Failure("root_motion is not a root bone")
            val q = vector(rotations[root], 4)
            axis = AxisRotation.of(Quat(q[0], q[1], q[2], q[3]))
            skeleton["m_boneScaleParent"]?.let { scales -> scale = list(scales).getOrNull(root)?.let(::real) ?: throw Kv3Failure("the skeleton's scales are short") }
        }
        val attachments = mutableListOf<Attachment>()
        for (block in model.blocks("MDAT")) {
            val mesh = model.kv3(block).root as? Kv3.Obj ?: throw Kv3Failure("a mesh block is not an object")
            val entries = mesh["m_attachments"] ?: continue
            val seen = mutableSetOf<String>()
            for ((index, item) in list(entries).withIndex()) {
                val entry = item as? Kv3.Obj ?: throw Kv3Failure("an attachment entry is not an object")
                val key = (entry["key"] as? Kv3.Str)?.value ?: throw Kv3Failure("an attachment has no key")
                val value = entry["value"] as? Kv3.Obj ?: throw Kv3Failure("attachment $key has no value")
                val name = (value["m_name"] as? Kv3.Str)?.value ?: throw Kv3Failure("attachment $key has no name")
                if (name != key) throw Kv3Failure("attachment $key is named $name")
                if (!seen.add(name)) throw Kv3Failure("attachment $name is repeated in one mesh")
                val influences = strings(value["m_influenceNames"] ?: throw Kv3Failure("attachment $name has no influences"))
                val count = integer(value["m_nInfluences"] ?: throw Kv3Failure("attachment $name has no influence count"))
                val rotationList = list(value["m_vInfluenceRotations"] ?: throw Kv3Failure("attachment $name has no rotations"))
                val offsetList = list(value["m_vInfluenceOffsets"] ?: throw Kv3Failure("attachment $name has no offsets"))
                val weights = list(value["m_influenceWeights"] ?: throw Kv3Failure("attachment $name has no weights")).map(::real)
                val transforms = list(value["m_bInfluenceRootTransform"] ?: throw Kv3Failure("attachment $name has no root transform flags"))
                val slots = influences.size
                if (slots == 0 || count !in 0L..slots.toLong() || rotationList.size != slots || offsetList.size != slots || weights.size != slots || transforms.size != slots) {
                    throw Kv3Failure("attachment $name has inconsistent influences")
                }
                val rootTransform = (transforms[0] as? Kv3.Bool)?.value ?: throw Kv3Failure("attachment $name has a malformed root transform flag")
                attachments += Attachment(block, index, name, influences, count, rotationList[0], offsetList[0], weights[0], rootTransform)
            }
        }
        return Read(attachments, axis, scale, externalMeshes)
    }

    private fun convert(model: ModelDocument, axis: AxisRotation) {
        for (block in model.blocks("MDAT")) {
            val document = model.kv3(block)
            val mesh = document.root as Kv3.Obj
            if (mesh["m_attachments"] == null) continue
            val next = mesh.replacing("m_attachments") { list ->
                mapItems(list) { item ->
                    val entry = item as Kv3.Obj
                    entry.replacing("value") { value ->
                        val attachment = value as Kv3.Obj
                        val influences = strings(attachment["m_influenceNames"]!!)
                        if (integer(attachment["m_nInfluences"]!!) != 1L || influences[0] != Root) return@replacing attachment
                        attachment
                            .replacing("m_vInfluenceRotations") { rotations -> mapFirst(rotations) { rotation -> rotated(rotation, axis.quat) } }
                            .replacing("m_vInfluenceOffsets") { offsets -> mapFirst(offsets) { offset -> withComponents(offset, axis.rotate(numbers(offset, 3))) } }
                    }
                }
            }
            if (next != mesh) model.replace(block, document.withRoot(next))
        }
    }

    private fun rotated(rotation: Kv3, q: Quat): Kv3 {
        val components = numbers(rotation, 4)
        val r = q * Quat(components[0].real, components[1].real, components[2].real, components[3].real)
        return withComponents(rotation, listOf(r.x, r.y, r.z, r.w).mapIndexed { i, value -> retyped(components[i], value) })
    }
}

/**
 * Models compiled for the game before it renamed the tagged sound binding keep their animation sounds under
 * `CitadelTaggedSoundSettings_t` in `m_keyValueText`; the game now reads `TaggedSoundSettings_t`.
 */
internal object TaggedSoundBinding : ModelRule {
    override val id: String = "tagged-sound-binding-name"
    override val revision: Int = 1

    private const val Old = "CitadelTaggedSoundSettings_t"
    private const val New = "TaggedSoundSettings_t"
    private val Table = Regex("""scripts/tagged_sounds/[a-z0-9_./-]+\.vdata""")

    override fun assess(model: ModelDocument, context: RuleContext): RuleOutcome {
        if (!model.path.endsWith(".vmdl_c")) return RuleOutcome.NotAffected
        val text = try {
            keyText(model)
        } catch (e: Kv3Failure) {
            return RuleOutcome.Declined(e.message)
        } ?: return RuleOutcome.NotAffected
        if (!text.contains(Old)) return RuleOutcome.NotAffected
        val root = try {
            Kv3Text.parse(text) as? Kv3Text.Obj ?: return RuleOutcome.Declined("the model's key values are not an object")
        } catch (e: Kv3Failure) {
            return RuleOutcome.Declined("the model's key values could not be read: ${e.message}")
        }
        val old = root.members.filter { it.key == Old }
        if (old.isEmpty()) return RuleOutcome.Declined("$Old appears in the model's key values but not as a binding")
        if (old.size > 1) return RuleOutcome.Declined("$Old is bound more than once")
        if (root.members.any { it.key == New }) return RuleOutcome.NotAffected
        val binding = old.single()
        val settings = binding.value as? Kv3Text.Obj ?: return RuleOutcome.Declined("$Old is not an object")
        val tables = settings.members.filter { it.key == "m_taggedSounds" }
        if (tables.size != 1 || settings.members.size != 1) return RuleOutcome.Declined("$Old holds something other than one m_taggedSounds list")
        val sounds = tables.single().value as? Kv3Text.Arr ?: return RuleOutcome.Declined("m_taggedSounds is not a list")
        val paths = sounds.items.mapNotNull { item ->
            val reference = item as? Kv3Text.Str ?: return RuleOutcome.Declined("a sound table reference is not a string")
            if (reference.flag != "resource" && reference.flag != "resource_name") return RuleOutcome.Declined("a sound table reference is not a resource")
            when {
                reference.value.isEmpty() -> null
                !Table.matches(reference.value) -> return RuleOutcome.Declined("${reference.value} is not a tagged sound table")
                else -> reference.value
            }
        }
        if (paths.isEmpty()) return RuleOutcome.NotAffected
        for (path in paths) {
            val compiled = path + "_c"
            if (context.above(compiled)) return RuleOutcome.Declined("a mod that loads first replaces the sound table $path")
            val table = context.set(compiled) ?: context.game(compiled)
                ?: return RuleOutcome.Unrepairable("the sound table $path is neither in the mod nor in the game")
            val schema = try {
                val file = ResourceContainer.read(table)
                val data = file.blocks("DATA").singleOrNull() ?: throw Kv3Failure("no DATA block")
                ((Kv3Binary.decode(file.payload(data)).root as? Kv3.Obj)?.get("generic_data_type") as? Kv3.Str)?.value
            } catch (e: Kv3Failure) {
                return RuleOutcome.Declined("the sound table $path could not be read: ${e.message}")
            }
            if (schema != "CTaggedSound_t") return RuleOutcome.Declined("$path is not a CTaggedSound_t table")
        }
        val game = context.game(model.path) ?: return RuleOutcome.Declined("the game has no model at this path to compare with")
        val gameText = try {
            keyText(ModelDocument(model.path, ResourceContainer.read(game)))
        } catch (e: Kv3Failure) {
            return RuleOutcome.Declined("the game's own model could not be read: ${e.message}")
        } ?: return RuleOutcome.Declined("the game's own model has no key values")
        val gameRoot = try {
            Kv3Text.parse(gameText) as? Kv3Text.Obj
        } catch (e: Kv3Failure) {
            null
        } ?: return RuleOutcome.Declined("the game's own key values could not be read")
        if (gameRoot.members.none { it.key == New } || gameRoot.members.any { it.key == Old }) return RuleOutcome.Declined("the game's own model does not use $New")
        val renamed = text.substring(0, binding.keyStart) + New + text.substring(binding.keyEnd)
        val value = (model.data()["m_modelInfo"] as Kv3.Obj)["m_keyValueText"] as Kv3.Str
        val edit = FieldEdit(model.blocks("DATA").single().index, "m_modelInfo/m_keyValueText", value, value.copy(value = renamed))
        return RuleOutcome.Repairable(listOf(edit)) { target -> replaceKeyText(target, text, renamed) }
    }

    private fun keyText(model: ModelDocument): String? {
        val info = model.data()["m_modelInfo"] as? Kv3.Obj ?: return null
        return when (val value = info["m_keyValueText"]) {
            null -> null
            is Kv3.Str -> value.value
            else -> throw Kv3Failure("m_keyValueText is not a string")
        }
    }

    private fun replaceKeyText(model: ModelDocument, before: String, after: String) {
        val block = model.blocks("DATA").single()
        val document = model.kv3(block)
        val root = (document.root as Kv3.Obj).replacing("m_modelInfo") { info ->
            (info as Kv3.Obj).replacing("m_keyValueText") { value ->
                val text = value as Kv3.Str
                if (text.value != before) throw Kv3Failure("the key values changed while being repaired")
                text.copy(value = after)
            }
        }
        val strings = document.strings.map { if (it == before) after else it }
        model.replace(block, Kv3Document(document.version, document.format, document.compression, strings, root, document.hints))
    }
}

internal object ModelRules {
    val all: List<ModelRule> = listOf(RootMotionAttachmentFrame, TaggedSoundBinding)
}

internal fun Kv3.Obj.replacing(name: String, change: (Kv3) -> Kv3): Kv3.Obj {
    var found = false
    val members = members.map { (key, value) ->
        if (key != name) key to value else {
            if (found) throw Kv3Failure("the member $name is repeated")
            found = true
            key to change(value)
        }
    }
    if (!found) throw Kv3Failure("the member $name is missing")
    return copy(members = members)
}

internal fun list(value: Kv3): List<Kv3> = when (value) {
    is Kv3.List -> value.items
    is Kv3.Typed -> value.items
    else -> throw Kv3Failure("a list is expected")
}

private fun mapItems(value: Kv3, change: (Kv3) -> Kv3): Kv3 = when (value) {
    is Kv3.List -> value.copy(items = value.items.map(change))
    is Kv3.Typed -> value.copy(items = value.items.map(change))
    else -> throw Kv3Failure("a list is expected")
}

private fun mapFirst(value: Kv3, change: (Kv3) -> Kv3): Kv3 {
    var first = true
    return mapItems(value) { if (first) change(it).also { first = false } else it }
}

private fun strings(value: Kv3): List<String> = list(value).map { (it as? Kv3.Str)?.value ?: throw Kv3Failure("a list of strings is expected") }

private fun integer(value: Kv3): Long {
    val n = value as? Kv3.Number ?: throw Kv3Failure("an integer is expected")
    if (n.isReal) throw Kv3Failure("an integer is expected")
    return when (n.type) {
        Kv3Type.UInt64 -> if (n.bits < 0) throw Kv3Failure("an integer is out of range") else n.bits
        else -> n.bits
    }
}

private fun real(value: Kv3): Double {
    val n = value as? Kv3.Number ?: throw Kv3Failure("a number is expected")
    if (!n.isReal) throw Kv3Failure("a real number is expected")
    return n.real.also { if (!it.isFinite()) throw Kv3Failure("a number is not finite") }
}

private fun numbers(value: Kv3, size: Int): List<Kv3.Number> {
    val items = list(value)
    if (items.size != size) throw Kv3Failure("a vector of $size is expected")
    return items.map { item -> (item as? Kv3.Number)?.takeIf { it.isReal && it.real.isFinite() } ?: throw Kv3Failure("a vector holds something other than finite reals") }
}

private fun vector(value: Kv3, size: Int): List<Double> = numbers(value, size).map { it.real }

internal fun retyped(like: Kv3.Number, value: Double): Kv3.Number = when (like.type) {
    Kv3Type.Float -> like.copy(bits = java.lang.Float.floatToRawIntBits(value.toFloat()).toLong() and 0xFFFFFFFFL)
    Kv3Type.Double, Kv3Type.DoubleZero, Kv3Type.DoubleOne -> {
        val bits = java.lang.Double.doubleToRawLongBits(value)
        when {
            bits == 0L -> like.copy(type = Kv3Type.DoubleZero, bits = 0)
            value == 1.0 -> like.copy(type = Kv3Type.DoubleOne, bits = java.lang.Double.doubleToRawLongBits(1.0))
            else -> like.copy(type = Kv3Type.Double, bits = bits)
        }
    }
    else -> throw Kv3Failure("a component of type ${like.type} cannot hold a real")
}

private fun withComponents(vector: Kv3, components: List<Kv3.Number>): Kv3 = when (vector) {
    is Kv3.List -> vector.copy(items = components)
    is Kv3.Typed -> {
        if (components.any { it.type != vector.elementType || it.flag != vector.elementFlag }) {
            if (vector.elementType != Kv3Type.Float && vector.elementType != Kv3Type.Double) throw Kv3Failure("a typed vector cannot change its element type")
            vector.copy(items = components.map { if (it.type == vector.elementType) it else it.copy(type = vector.elementType, bits = java.lang.Double.doubleToRawLongBits(it.real)) })
        } else vector.copy(items = components)
    }
    else -> throw Kv3Failure("a vector is expected")
}
