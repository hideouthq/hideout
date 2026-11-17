package gg.hideouthq.core.data.fake

import gg.hideouthq.core.library.CheckBasis
import gg.hideouthq.core.library.CheckResult
import gg.hideouthq.core.library.CompatFinding
import gg.hideouthq.core.library.CompatibilityPort
import gg.hideouthq.core.library.DeriveResult
import gg.hideouthq.core.library.FileEvidence
import gg.hideouthq.core.library.FindingKind
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.InstallCheck
import gg.hideouthq.core.library.InstalledSet
import gg.hideouthq.core.library.ManifestRecord
import gg.hideouthq.core.library.Provider
import gg.hideouthq.core.library.ReadSpan
import gg.hideouthq.core.library.RetainResult
import gg.hideouthq.core.library.RetainedOriginal
import gg.hideouthq.core.library.RuleIdentity
import gg.hideouthq.core.library.SetCheck
import gg.hideouthq.core.library.SetDerivation
import gg.hideouthq.core.library.SetFile
import gg.hideouthq.core.library.SetMember
import gg.hideouthq.core.library.Sha256
import gg.hideouthq.core.library.StagedOriginal
import gg.hideouthq.core.library.StagedRef
import gg.hideouthq.core.library.StagedVpk
import gg.hideouthq.core.library.StagingId
import gg.hideouthq.core.library.VpkPart
import gg.hideouthq.core.library.VpkRead
import gg.hideouthq.core.library.VpkSet
import gg.hideouthq.core.library.rank
import gg.hideouthq.core.library.relativePath
import gg.hideouthq.preview.sha256Hex

class FakeCompatibility(private val files: InMemoryGameFiles, var game: String? = "build:fake") : CompatibilityPort {
    override var unavailable: String? = null

    override val rules: List<RuleIdentity> = listOf(Frame, Sound)

    override val codec: String = "fake-codec-1"

    val findings: MutableMap<String, List<CompatFinding>> = mutableMapOf()

    val supplies: MutableMap<String, Set<String>> = mutableMapOf()

    val above: MutableMap<String, List<Provider>> = mutableMapOf()

    var releaseFails: String? = null

    val failing: MutableMap<String, String> = mutableMapOf()

    var refuseDerive: String? = null

    /** Runs as a derivation starts, standing in for the game or files changing at that moment. */
    var beforeDerive: (suspend () -> Unit)? = null

    var beforeCheck: (suspend (ManifestRecord) -> Unit)? = null

    /** Runs as originals are staged to be put back, standing in for the game starting at that moment. */
    var beforeStage: (() -> Unit)? = null

    val checked: MutableList<String> = mutableListOf()
    val derived: MutableList<String> = mutableListOf()
    val released: MutableList<String> = mutableListOf()

    /** Runs as the game's identity is read, standing in for Steam updating the game at that moment. */
    var onGame: (() -> Unit)? = null

    override suspend fun game(root: GameRoot): String? {
        onGame?.invoke()
        return game
    }

    override suspend fun check(record: ManifestRecord, originals: List<RetainedOriginal>, above: List<Provider>): CheckResult {
        beforeCheck?.invoke(record)
        checked += record.installId
        this.above[record.installId] = above
        failing[record.installId]?.let { return CheckResult.Failed(it) }
        val game = game ?: return CheckResult.Failed("the game's own files could not be read")
        val evidence = mutableSetOf(FileEvidence(GameLocation, null, 0, emptyList(), game))
        val sets = record.sets.map { set ->
            val derivation = set.derivation
            val inputs = derivation?.originalFiles ?: set.files.map { it.image }
            val read = record.filesOf(set).mapIndexed { index, file ->
                if (derivation == null) read(gamePlace(record.gameRoot, file.relativePath(record)), record.installId, evidence)
                else read("$OriginalsLocation${derivation.original}/$index", record.installId, evidence)
            }
            if (read.map { it?.size?.toLong() } != inputs.map { it.sizeBytes }) return@map SetCheck(set.source, set.componentKey, inputs, emptyList(), "its files are not the ones recorded")
            var found = read.flatMap { findings[sha256Hex(it!!)].orEmpty() }
            for (provider in above.filter { it.rank < record.rank(set) }) {
                if (provider == Provider.Unreadable) {
                    found = found.map { it.copy(kind = FindingKind.Unknown, detail = "what loads first could not be read") }
                    continue
                }
                val supplied = read(gamePlace(record.gameRoot, provider.path), provider.installId, evidence)?.let { supplies[sha256Hex(it)] }.orEmpty()
                found = found.mapNotNull { finding ->
                    when {
                        finding.resource !in supplied -> finding
                        provider.managed -> null
                        else -> finding.copy(kind = FindingKind.Unknown, detail = "${provider.name} loads first")
                    }
                }
            }
            SetCheck(set.source, set.componentKey, inputs, found)
        }
        return CheckResult.Checked(InstallCheck(record.installId, CheckBasis(game, codec, rules, evidence), sets))
    }

    override suspend fun changed(evidence: Set<FileEvidence>): Set<FileEvidence> = evidence.filterTo(mutableSetOf()) { seen ->
        if (seen.location == GameLocation) seen.sha256 != game
        else places[seen.location]?.let { place -> evidenceOf(seen.location, seen.installId, place()) != seen } ?: true
    }

    private val places = mutableMapOf<String, () -> ByteArray?>()

    private fun gamePlace(root: GameRoot, path: String): String = "${root.path}/$path".also { location -> places[location] = { files.bytes(root, path) } }

    private fun read(location: String, installId: String?, evidence: MutableSet<FileEvidence>): ByteArray? {
        if (location.startsWith(OriginalsLocation)) {
            val (id, index) = location.removePrefix(OriginalsLocation).split('/')
            places[location] = { files.originals[id]?.getOrNull(index.toInt()) }
        }
        val bytes = places.getValue(location)()
        evidence += evidenceOf(location, installId, bytes)
        return bytes
    }

    private fun evidenceOf(location: String, installId: String?, bytes: ByteArray?): FileEvidence =
        if (bytes == null) FileEvidence(location, installId, null, emptyList(), "")
        else FileEvidence(location, installId, bytes.size.toLong(), listOf(ReadSpan(0, bytes.size.toLong())), sha256Hex(bytes))

    override suspend fun derive(record: ManifestRecord, set: InstalledSet, original: RetainedOriginal, expected: SetCheck, basis: CheckBasis, above: List<Provider>): DeriveResult {
        beforeDerive?.invoke()
        derived += record.installId
        if (game != basis.game) return DeriveResult.Stale("the game changed")
        refuseDerive?.let { return DeriveResult.Refused(it) }
        val bytes = files.originals[original.id] ?: return DeriveResult.Refused("the original files are not kept")
        val repairable = expected.findings.filter { it.kind == FindingKind.Repairable }
        if (repairable.isEmpty()) return DeriveResult.Refused("nothing to repair")
        val made = set.files.mapIndexed { index, file -> if (file.part == VpkPart.Directory) bytes[index] + Marker else bytes[index] }
        val staging = files.putStaging(made)
        val resources = repairable.map { it.resource }.distinct().size
        val derivation = SetDerivation(
            original.id, original.imagesFor(set) ?: return DeriveResult.Refused("the original does not match"),
            rules.filter { rule -> repairable.any { it.rule == rule } }, codec, basis.game,
            resources = resources, edits = maxOf(repairable.sumOf { it.edits }, resources),
        )
        return DeriveResult.Derived(staging, VpkSet(set.componentKey, members(set, staging, made)), derivation)
    }

    override suspend fun retain(record: ManifestRecord, set: InstalledSet, id: String): RetainResult {
        val bytes = record.filesOf(set).map { files.bytes(record.gameRoot, it.relativePath(record)) ?: return RetainResult.Failed("${it.name} is missing") }
        if (bytes.map { sha256Hex(it) } != set.files.map { it.image.sha256 }) return RetainResult.Failed("the files changed")
        files.originals[id] = bytes
        return RetainResult.Retained(RetainedOriginal(id, record.installId, set.source, set.componentKey, set.files.mapIndexed { index, file -> SetFile(file.part, Sha256.image(bytes[index])) }))
    }

    override suspend fun stageOriginal(original: RetainedOriginal, set: InstalledSet): StagedOriginal {
        beforeStage?.invoke()
        val bytes = files.originals[original.id] ?: return StagedOriginal.Failed("the original files are not kept")
        val staging = files.putStaging(bytes)
        return StagedOriginal.Staged(staging, VpkSet(set.componentKey, members(set, staging, bytes)))
    }

    override suspend fun release(id: String) {
        releaseFails?.let { throw IllegalStateException(it) }
        files.originals.remove(id)
        released += id
    }

    override suspend fun sweep(registered: Set<String>) {
        files.originals.keys.retainAll(registered)
    }

    override suspend fun discard(staging: StagingId) = files.discard(staging)

    private fun members(set: InstalledSet, staging: StagingId, bytes: List<ByteArray>): List<SetMember<StagedVpk>> = set.files.mapIndexed { index, file ->
        SetMember(file.part, StagedVpk(StagedRef(staging, index), "${file.part.label}.vpk", bytes[index].size.toLong(), sha256Hex(bytes[index]), VpkRead.Unreadable("not read by the fake")))
    }

    companion object {
        val Frame = RuleIdentity("root-motion-attachment-frame", 1)
        val Sound = RuleIdentity("tagged-sound-binding-name", 1)
        private val Marker = "+repaired".encodeToByteArray()
        private const val GameLocation = "game:"
        private const val OriginalsLocation = "originals:"

        fun repairable(resource: String = "models/heroes/test/test.vmdl_c", rule: RuleIdentity = Frame, edits: Int = 4) = CompatFinding(rule, resource, FindingKind.Repairable, "$edits values", edits)

        fun needsAuthor(resource: String = "models/heroes/test/test.vmdl_c", rule: RuleIdentity = Frame) = CompatFinding(rule, resource, FindingKind.NeedsAuthor, "mixed frames")

        fun unknown(resource: String = "models/heroes/test/test.vmdl_c", rule: RuleIdentity = Frame) = CompatFinding(rule, resource, FindingKind.Unknown, "could not tell")
    }
}
