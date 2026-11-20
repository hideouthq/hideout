package gg.hideouthq.core.library

enum class FindingKind {
    /** Confirmed broken in a way the rule repairs exactly. */
    Repairable,

    /** Confirmed broken, but repairing it is not safe: the author has to update the mod. */
    NeedsAuthor,

    /** Whether it is broken could not be told. Never counted as fine. */
    Unknown,
}

/** What one rule found in one resource of a set; a resource with nothing to say has no finding. */
data class CompatFinding(val rule: RuleIdentity, val resource: String, val kind: FindingKind, val detail: String, val edits: Int = 0)

/**
 * One set as checked: the files read, [inputs] in the set's order (the retained originals for a repaired set), and
 * every finding. [incomplete] says why some of the set could not be checked at all.
 */
data class SetCheck(
    val source: InstalledSource,
    val componentKey: String,
    val inputs: List<FileImage>,
    val findings: List<CompatFinding>,
    val incomplete: String? = null,
)

/**
 * What one file gave a check: the file at [location], [sizeBytes] long when it was opened, or null when it was not
 * there, the [reads] in the order the check made them, and the SHA-256 of their bytes in that order. Reading the same
 * places again and getting the same digest is the proof that a check would read the same; a timestamp never is.
 * [installId] is the installation the file belongs to, or null for the game's own files and files nobody vouches for.
 */
data class FileEvidence(val location: String, val installId: String?, val sizeBytes: Long?, val reads: List<ReadSpan>, val sha256: String)

data class ReadSpan(val position: Long, val length: Long)

/** Everything a check depended on: the game, the codec and rules it ran, and every byte it read. When any of it changes, its findings no longer hold. */
data class CheckBasis(val game: String, val codec: String, val rules: List<RuleIdentity>, val evidence: Set<FileEvidence>)

/** One installation as last checked, set by set, winner first. */
data class InstallCheck(val installId: String, val basis: CheckBasis, val sets: List<SetCheck>) {
    val findings: List<CompatFinding> get() = sets.flatMap { it.findings }
}

/**
 * A directory file whose copy of a resource wins over every set ranked after it: one of Hideout's sets, the
 * installation's own earlier sets included, belonging to [installId] and recorded as [image], or a `pakNN_dir.vpk` in
 * a loading folder that Hideout does not own and nobody vouches for. [rank] orders them: the slot of a set that is on or
 * of a foreign file, or a set's place in its mod while the mod is off ([ManifestRecord.rank]). [path] is under the
 * game root.
 */
data class Provider(val rank: Int, val path: String, val installId: String? = null, val image: FileImage? = null) {
    val managed: Boolean get() = installId != null

    val name: String get() = path.substringAfterLast('/')

    companion object {
        /** A loading folder could not be listed: anything may load first, so no resource is known to win. */
        val Unreadable: Provider = Provider(0, "")
    }
}

sealed interface CheckResult {
    data class Checked(val check: InstallCheck) : CheckResult

    /** Nothing could be checked; whether the mod works is unknown. */
    data class Failed(val reason: String) : CheckResult
}

sealed interface DeriveResult {
    /** The repaired set is staged as [vpks] in [staging], each repaired file read back from it, with its lineage. */
    data class Derived(val staging: StagingId, val vpks: VpkSet<StagedVpk>, val derivation: SetDerivation) : DeriveResult

    /** What the plan was made for changed: the files, the game or the rules. Checking again shows what holds now. */
    data class Stale(val reason: String) : DeriveResult

    data class Refused(val reason: String) : DeriveResult
}

sealed interface RetainResult {
    data class Retained(val original: RetainedOriginal) : RetainResult

    data class Failed(val reason: String) : RetainResult
}

sealed interface StagedOriginal {
    data class Staged(val staging: StagingId, val vpks: VpkSet<StagedVpk>) : StagedOriginal

    data class Failed(val reason: String) : StagedOriginal
}

/**
 * The platform's half of compatibility checks and repairs: reading compiled resources and the game's own files,
 * writing repaired sets into the installer's staging, and keeping original sets durably in Hideout's own folder.
 * It never writes a game folder; every change to one goes through [ModLibrary] and its journal. Checks and
 * derivations run outside the library's lock and are proven again before anything is placed.
 */
interface CompatibilityPort {
    /** Why checks cannot run on this computer, or null when they can. */
    val unavailable: String?

    /** The rules this build checks with, in the order they apply. */
    val rules: List<RuleIdentity>

    /** The codec revision repaired files are written with. */
    val codec: String

    /** The game's shipped files as an identity that changes when they do, or null when they cannot be read. */
    suspend fun game(root: GameRoot): String?

    /**
     * Checks [record]'s sets as they are now: a repaired set by its retained original from [originals], every other
     * set by its own files. [above] are the files that load before its last set; each set is shadowed by those ranked
     * before it.
     */
    suspend fun check(record: ManifestRecord, originals: List<RetainedOriginal>, above: List<Provider>): CheckResult

    /**
     * Reads again exactly where [evidence] says checks read, from the files as they are now, and returns the evidence
     * that no longer holds: a file that reads differently there, has another length, or appeared or went.
     */
    suspend fun changed(evidence: Set<FileEvidence>): Set<FileEvidence>

    /** Writes [set] repaired from [original] into new staging, refusing anything but exactly what [expected] found. */
    suspend fun derive(record: ManifestRecord, set: InstalledSet, original: RetainedOriginal, expected: SetCheck, basis: CheckBasis, above: List<Provider>): DeriveResult

    /** Copies [set]'s files, as [record] places them now, into Hideout's originals under [id], each checked against its image as it is copied. */
    suspend fun retain(record: ManifestRecord, set: InstalledSet, id: String): RetainResult

    /** Copies [original] into new staging, checked against its images, to be placed again. */
    suspend fun stageOriginal(original: RetainedOriginal, set: InstalledSet): StagedOriginal

    /** Deletes the retained copy [id]; only after the manifest no longer names it. */
    suspend fun release(id: String)

    /** Deletes every retained copy not in [registered], and anything a retention left half made. */
    suspend fun sweep(registered: Set<String>)

    /** Removes staging [derive] or [stageOriginal] made. */
    suspend fun discard(staging: StagingId)

    companion object {
        /** No checks here: every mod stays unchecked, never fine. */
        val Unavailable: CompatibilityPort = object : CompatibilityPort {
            override val unavailable: String = "compatibility checks are not available on this platform"
            override val rules: List<RuleIdentity> = emptyList()
            override val codec: String = "none"
            override suspend fun game(root: GameRoot): String? = null
            override suspend fun check(record: ManifestRecord, originals: List<RetainedOriginal>, above: List<Provider>) = CheckResult.Failed(unavailable)
            override suspend fun changed(evidence: Set<FileEvidence>): Set<FileEvidence> = evidence
            override suspend fun derive(record: ManifestRecord, set: InstalledSet, original: RetainedOriginal, expected: SetCheck, basis: CheckBasis, above: List<Provider>) = DeriveResult.Refused(unavailable)
            override suspend fun retain(record: ManifestRecord, set: InstalledSet, id: String) = RetainResult.Failed(unavailable)
            override suspend fun stageOriginal(original: RetainedOriginal, set: InstalledSet) = StagedOriginal.Failed(unavailable)
            override suspend fun release(id: String) = Unit
            override suspend fun sweep(registered: Set<String>) = Unit
            override suspend fun discard(staging: StagingId) = Unit
        }
    }
}

/** The one label a mod card shows, if any. There is deliberately none for "fine". */
enum class CompatLabel { RepairAvailable, RepairApplied, PartlyRepaired, NeedsAuthorUpdate }

/** A set's repair as committed in the manifest, and whether it still answers the current check. */
data class AppliedRepair(val set: InstalledSet, val derivation: SetDerivation, val current: Boolean)

/**
 * What Library can truthfully say about one installation's compatibility with the game as it is now: the latest
 * check that still holds ([check], or [failed] when checking failed), the repairs its files carry ([applied]), what
 * the user asked for ([choice]) and whether files are changing to match it ([pending]). Nothing in it means fine:
 * no finding is "no known problem", and an installation not yet checked is [checking] or simply unchecked.
 */
data class InstallCompatibility(
    val installId: String,
    val check: InstallCheck? = null,
    val failed: String? = null,
    val checking: Boolean = false,
    val applied: List<AppliedRepair> = emptyList(),
    val choice: RepairChoice? = null,
    /** Its files were changed outside Hideout, so nothing repairs or restores them until the user keeps the change or restores the original. */
    val held: Boolean = false,
    /** Files are being changed to match [choice], or wait for Deadlock to close to be. */
    val pending: RepairWork? = null,
    /** Why the last repair or restore of the installation as it is now did not finish. */
    val problem: String? = null,
    /** Whether [problem] is a restore's, not a repair's. */
    val problemRestoring: Boolean = false,
) {
    /** Problems the files still have: repairable ones not yet repaired, or repaired by a repair that is out of date. */
    val repairable: List<CompatFinding> get() = check?.sets.orEmpty().flatMap { set -> set.findings.filter { it.kind == FindingKind.Repairable && !set.fixed(it) } }

    val repaired: List<CompatFinding> get() = check?.sets.orEmpty().flatMap { set -> set.findings.filter { it.kind == FindingKind.Repairable && set.fixed(it) } }

    private fun SetCheck.fixed(finding: CompatFinding): Boolean =
        applied.any { it.current && it.set.source == source && it.set.componentKey == componentKey && finding.rule in it.derivation.rules }

    val needsAuthor: List<CompatFinding> get() = check?.findings.orEmpty().filter { it.kind == FindingKind.NeedsAuthor }

    val unknown: List<CompatFinding> get() = check?.findings.orEmpty().filter { it.kind == FindingKind.Unknown }

    /** A repair that no longer matches the game or rules, and that checking again cannot refresh. */
    val outdated: List<AppliedRepair> get() = applied.filter { !it.current }

    /**
     * The card's label: only for confirmed problems or repairs in use, never for an unchecked or fine mod, and none
     * once the user chose the original files, since that choice is their answer to what was found.
     */
    fun label(enabled: Boolean): CompatLabel? = when {
        check == null || choice == RepairChoice.Original -> null
        repaired.isNotEmpty() && (repairable.isNotEmpty() || needsAuthor.isNotEmpty()) -> CompatLabel.PartlyRepaired
        repairable.isNotEmpty() -> CompatLabel.RepairAvailable
        repaired.isNotEmpty() -> if (enabled) CompatLabel.RepairApplied else null
        needsAuthor.isNotEmpty() -> CompatLabel.NeedsAuthorUpdate
        else -> null
    }
}

/**
 * What a mod's compatibility chip says, wherever it is shown: its files changing comes first, then a repair or restore
 * that did not finish, then what its current check found. Null says nothing, and never that the mod is fine.
 */
sealed interface CompatStatus {
    /** Its files are queued to change, changing, placed and being checked, or waiting for Deadlock to close. */
    data class Changing(val work: RepairWork) : CompatStatus

    /** The last repair, or with [restoring] restore, of its files as they are now did not finish. */
    data class Unfinished(val restoring: Boolean) : CompatStatus

    data class Found(val label: CompatLabel) : CompatStatus
}

/** The one status every screen shows for this installation; [enabled] is whether the mod is on where it is shown. */
fun InstallCompatibility.status(enabled: Boolean): CompatStatus? = when {
    pending != null -> CompatStatus.Changing(pending)
    problem != null -> CompatStatus.Unfinished(problemRestoring)
    else -> label(enabled)?.let(CompatStatus::Found)
}

enum class FindingOutcome {
    /** A repair in use fixes them. */
    Repaired,

    /** A repair would fix them, and none in use does. */
    Repairable,

    /** Repairing them is not safe, so they are left as the author made them. */
    LeftUnchanged,

    /** Whether they are broken could not be told. */
    Unchecked,
}

/** One rule's findings that came to the same [outcome], in the order the check found them. */
data class RuleFindings(val outcome: FindingOutcome, val rule: RuleIdentity, val findings: List<CompatFinding>)

/** Every finding of the current check, by outcome in [FindingOutcome] order, then by rule in the order the check found them. */
val InstallCompatibility.outcomes: List<RuleFindings>
    get() = listOf(FindingOutcome.Repaired to repaired, FindingOutcome.Repairable to repairable, FindingOutcome.LeftUnchanged to needsAuthor, FindingOutcome.Unchecked to unknown)
        .flatMap { (outcome, found) -> found.groupBy { it.rule }.map { (rule, findings) -> RuleFindings(outcome, rule, findings) } }

/** Why some of the installation could not be checked at all, one reason for each set the check could not read. */
val InstallCompatibility.incomplete: List<String> get() = check?.sets.orEmpty().mapNotNull { it.incomplete }

sealed interface RepairWork {
    /** Whether the original files are being put back, not repaired ones made. */
    val restoring: Boolean

    /** Accepted: its files change when the worker reaches it, one installation at a time. */
    data class Queued(override val restoring: Boolean) : RepairWork

    /** The repair is being made or placed. */
    data object Repairing : RepairWork {
        override val restoring: Boolean = false
    }

    data object Restoring : RepairWork {
        override val restoring: Boolean = true
    }

    /** The new files are placed; nothing is said of them until a check of exactly them lands. */
    data class Verifying(override val restoring: Boolean) : RepairWork

    /** It has to change files the game loads, so it waits for Deadlock to be known closed. */
    data class WaitingForGame(override val restoring: Boolean) : RepairWork
}

data class CompatibilityState(
    val installs: Map<String, InstallCompatibility> = emptyMap(),
    /** Why checks cannot run on this computer; null when they can. */
    val unavailable: String? = null,
    val checking: Boolean = false,
    /** Whether a mod with no saved repair choice is repaired without being asked. */
    val automatic: Boolean = false,
)

/** What makes an installation's check due again when it changes; its content is proven apart, by [CheckBasis.evidence]. */
internal data class CheckKey(val record: ManifestRecord, val originals: List<RetainedOriginal>, val mounted: List<Provider>, val game: String?)
