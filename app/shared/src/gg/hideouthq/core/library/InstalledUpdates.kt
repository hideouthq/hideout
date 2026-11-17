package gg.hideouthq.core.library

import gg.hideouthq.core.data.DataError
import gg.hideouthq.core.data.gamebanana.FactsAnswer
import gg.hideouthq.core.data.gamebanana.SubmissionFileOption

/**
 * What Hideout last learned from GameBanana about one installed submission's current files, kept in memory only.
 * [answer] is what the latest check brought back. [targets] are the installed files it asked about: one installed since
 * says nothing yet. [failure] is why the latest check did not come back, when it did not.
 */
data class FilesObservation(val answer: FactsAnswer?, val targets: Set<SubmissionIdentity>, val failure: DataError? = null)

sealed interface SourceState {
    /** Not asked about yet, or installed since the latest check asked. */
    data object NotChecked : SourceState

    /**
     * The observation cannot tell: the latest check did not come back, GameBanana left the submission out or its answer
     * could not be read, or some of its files' archive state or identity could not be read. Never current.
     */
    data object Unknown : SourceState

    /** Still offered under the same id and MD5. Other files offered beside it are not an update. */
    data object Current : SourceState

    /** Still offered under the same id, but an MD5 is missing on one side, so it cannot be compared. */
    data object Unverified : SourceState

    /** Still offered under the same id, with a different MD5 than the one installed. */
    data class Changed(val target: SubmissionFileOption) : SourceState

    /**
     * No longer offered, and [target] passes the single-successor test: the installation's only GameBanana file, from
     * a submission that listed one file when each set was installed, now lists exactly one, with a greater id. A
     * candidate to confirm by its name, not proven lineage.
     */
    data class Successor(val target: SubmissionFileOption) : SourceState

    /** No longer offered, and nothing says which file would replace it. */
    data object Review : SourceState
}

data class SourceUpdate(val installed: SubmissionIdentity, val state: SourceState)

data class Replacement(val from: SubmissionIdentity, val to: SubmissionIdentity)

/**
 * An update pinned to what it was decided against: the installation [installId] in [gameRoot] holding exactly the sets
 * [installed], from those files, archives and components, each replacement to an exact file and MD5. It is to be
 * carried out only while all of that still holds; turning the mod on or off or renumbering it does not change it.
 */
data class UpdatePlan(val gameRoot: GameRoot, val installId: String, val installed: List<SetProvenance>, val replacements: List<Replacement>)

/**
 * One installation's check, file by file in load order. [provenance] is what it holds now, which a [plan] is pinned to.
 * [offered] is every file id its submission lists now, when the check read them all; [kept] when the user kept its files
 * over a review while the submission offered exactly these.
 */
data class InstallationUpdates(
    val gameRoot: GameRoot,
    val installId: String,
    val sources: List<SourceUpdate>,
    val provenance: List<SetProvenance>,
    val offered: Set<Long>? = null,
    val kept: Boolean = false,
) {
    /** Every change known for its files, including where another file keeps the whole from updating unattended. */
    val replacements: List<Replacement> get() = sources.mapNotNull { it.replacement() }

    /**
     * What Update all may do: set only when something changed, every file is current or changed, and each target has
     * an MD5. A file that is unknown, unverified or needs review keeps the whole installation for review.
     */
    val plan: UpdatePlan?
        get() {
            val unattended = sources.all { it.state is SourceState.Current || it.state is SourceState.Changed || it.state is SourceState.Successor }
            val pinned = replacements.takeIf { changes -> changes.isNotEmpty() && changes.all { it.to.fileMd5 != null } }
            return if (unattended && pinned != null) UpdatePlan(gameRoot, installId, provenance, pinned) else null
        }

    private fun SourceUpdate.replacement(): Replacement? {
        val target = when (val state = state) {
            is SourceState.Changed -> state.target
            is SourceState.Successor -> state.target
            else -> return null
        }
        val to = SubmissionIdentity(installed.kind, installed.submissionId, target.id, target.md5)
        return Replacement(installed, to)
    }
}

/**
 * Whether installed GameBanana files are still what their submissions offer. Pure: it reads manifest records and
 * observations, and never downloads or touches a file.
 */
object InstalledUpdates {
    /** Each installation holding GameBanana files, by install id; installations from the user's own files are left out. */
    fun of(records: List<ManifestRecord>, observations: Map<SubmissionRef, FilesObservation>): Map<String, InstallationUpdates> =
        records.mapNotNull { record ->
            val sources = record.sets.map { it.source }.distinct().filterIsInstance<InstalledSource.Remote>()
            if (sources.isEmpty()) return@mapNotNull null
            val observation = observations[sources.first().identity.ref]
            val updates = sources.map { source -> SourceUpdate(source.identity, state(source.identity, record, sources.size, observation)) }
            val offered = observation?.takeIf { it.failure == null }?.let { (it.answer as? FactsAnswer.Read)?.facts }?.takeIf { it.filesComplete }?.files?.mapTo(HashSet()) { it.id }
            record.installId to InstallationUpdates(record.gameRoot, record.installId, updates, record.provenance, offered, kept = offered != null && record.reviewed == offered)
        }.toMap()

    private fun state(installed: SubmissionIdentity, record: ManifestRecord, sourceCount: Int, observation: FilesObservation?): SourceState {
        if (observation == null || installed !in observation.targets) return SourceState.NotChecked
        if (observation.failure != null) return SourceState.Unknown
        val facts = when (val answer = observation.answer) {
            null -> return SourceState.NotChecked
            FactsAnswer.Missing, FactsAnswer.Unreadable -> return SourceState.Unknown
            is FactsAnswer.Read -> answer.facts
        }
        if (!facts.filesComplete) return SourceState.Unknown
        val current = facts.files.firstOrNull { it.id == installed.fileId }
        if (current != null) {
            val md5 = installed.fileMd5 ?: return SourceState.Unverified
            val offered = current.md5 ?: return SourceState.Unverified
            return if (md5 == offered) SourceState.Current else SourceState.Changed(current)
        }
        val successor = facts.files.singleOrNull()?.takeIf { it.id > installed.fileId }
        val listedOne = record.sets.all { it.version?.count == 1 }
        return if (successor != null && sourceCount == 1 && listedOne) SourceState.Successor(successor) else SourceState.Review
    }
}
