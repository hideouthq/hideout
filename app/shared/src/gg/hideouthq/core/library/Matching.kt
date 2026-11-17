package gg.hideouthq.core.library

import gg.hideouthq.core.data.ArchiveIndex
import gg.hideouthq.core.data.FileListing
import gg.hideouthq.core.data.GameBananaClient
import gg.hideouthq.core.data.RemoteFile
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Who a mod is on GameBanana as far as is known: its exact file, only its submission, or neither. A source records a claim of it; [Matching] proves one. */
sealed interface ModIdentity {
    data class File(val identity: SubmissionIdentity) : ModIdentity

    data class Submission(val ref: SubmissionRef) : ModIdentity

    data object None : ModIdentity

    companion object {
        /** The claim of [ref]'s file [fileId] when both are known, of [ref] alone when only it is, else of nothing. */
        fun of(ref: SubmissionRef?, fileId: Long?): ModIdentity = when {
            ref == null -> None
            fileId == null -> Submission(ref)
            else -> File(SubmissionIdentity(ref.kind, ref.id, fileId, fileMd5 = null))
        }
    }
}

val ModIdentity.ref: SubmissionRef?
    get() = when (this) {
        is ModIdentity.File -> identity.ref
        is ModIdentity.Submission -> ref
        ModIdentity.None -> null
    }

/** Who a mod's source claims it is and the title it goes by; [selfReported] when the claim is only what the mod's own files say of themselves. */
data class ClaimedMod(val claim: ModIdentity, val title: String?, val selfReported: Boolean = false)

sealed interface Matched {
    val submission: SubmissionRef?

    /** Every set's exact file, in the mod's set order and all of one submission, and GameBanana's own title for it. */
    data class Pinned(val files: List<SubmissionIdentity>, val title: String?) : Matched {
        override val submission: SubmissionRef get() = files.first().ref
    }

    /** Not every set's file is proved, so the mod is known by its [submission] at most. */
    data class Unpinned(override val submission: SubmissionRef?) : Matched
}

/**
 * Who a mod of [sets] sets is by its source's word alone, asking nobody: a file another manager recorded is that file,
 * as it names the file the mod came from whatever GameBanana says of it now, so every set is that file. Anything less,
 * and a file a mod's own imprint names, is a claim of its submission at most, for [Matching] to prove later.
 */
fun ClaimedMod.matched(sets: Int): Matched = when (val claim = claim) {
    is ModIdentity.File -> if (selfReported) Matched.Unpinned(claim.identity.ref) else Matched.Pinned(List(sets) { claim.identity }, title = null)
    is ModIdentity.Submission -> Matched.Unpinned(claim.ref)
    ModIdentity.None -> Matched.Unpinned(null)
}

/**
 * Proves who local mods are on GameBanana by evidence:
 *  1. A claimed submission's files are read by their archives' indexes, by size and CRC-32, for one file that holds
 *     every set, else for each set on its own: one that holds exactly the VPKs first, else the first that holds them
 *     among others, current files before archived and newest first in each. A mod whose sets do not all pin is known
 *     by its submission when any set pinned.
 *  2. A mod that claims nothing has the files of the first [SearchResults] submissions GameBanana finds for its title
 *     read the same way, and is known by the first of them that holds any of its sets. When none does, and none went
 *     unchecked because the mod's files could not be read, it is known by the first whose own title is the mod's but
 *     for case and spacing. A title never pins a file.
 * GameBanana's listings and searches are asked under [limit], as installs ask, and once each for the life of this one.
 */
internal class Matching(private val gameBanana: GameBananaClient, private val archives: ArchiveIndex, private val limit: Semaphore) {
    private val listings = mutableMapOf<SubmissionRef, FileListing>()
    private val searches = mutableMapOf<String, List<SubmissionRef>?>()

    private var unanswered = false

    /**
     * Who a local mod known to be from [submission], or titled [title] when it is not, is; null when GameBanana did not
     * answer what that needed and nothing was proved. Its sets' files' [sizes] are compared with archives first, and
     * [read] reads every set's prints only once an archive could hold one of them.
     */
    suspend fun identify(submission: SubmissionRef?, title: String, sizes: List<List<Long>>, read: suspend () -> List<List<FilePrint>>?): Matched? {
        unanswered = false
        val matched = if (submission != null) pin(listOf(submission), sizes, read) else titled(title, sizes, read)
        return matched.takeIf { it.submission != null || !unanswered }
    }

    private suspend fun titled(title: String, sizes: List<List<Long>>, read: suspend () -> List<List<FilePrint>>?): Matched {
        val found = searches.getOrPut(title) { limit.withPermit { gameBanana.search(title) }?.take(SearchResults) } ?: emptyList<SubmissionRef>().also { unanswered = true }
        var unread = false
        return pin(found, sizes) { read().also { unread = it == null } }.takeIf { it.submission != null || unread }
            ?: Matched.Unpinned(found.firstOrNull { (listing(it) as? FileListing.Available)?.title?.plain() == title.plain() })
    }

    private suspend fun pin(submissions: List<SubmissionRef>, sizes: List<List<Long>>, read: suspend () -> List<List<FilePrint>>?): Matched {
        var prints: List<List<FilePrint>>? = null
        var known: SubmissionRef? = null
        val allThenEach = (listOf(sizes.indices.toList()) + sizes.indices.map(::listOf)).distinct()
        for (ref in submissions) {
            val listed = listing(ref) as? FileListing.Available ?: continue
            val files = listed.files.sortedByDescending { it.addedAt } + listed.archived.sortedByDescending { it.addedAt }
            val pinned = arrayOfNulls<RemoteFile>(sizes.size)
            for (sets in allThenEach) {
                if (sets.any { sizes[it].isEmpty() }) continue
                val wanted = sets.flatMap { sizes[it] }
                val file = run {
                    var holding: RemoteFile? = null
                    for (file in files) {
                        val vpks = archives.vpks(file)?.takeIf { vpks -> vpks.map { it.sizeBytes }.containsAll(wanted) } ?: continue
                        val own = (prints ?: read()?.also { prints = it } ?: return Matched.Unpinned(known)).let { all -> sets.flatMap { all[it] } }
                        if (vpks.toSet() == own.toSet()) return@run file
                        if (holding == null && vpks.containsAll(own)) holding = file
                    }
                    holding
                } ?: continue
                sets.forEach { pinned[it] = file }
                if (pinned.all { it != null }) return Matched.Pinned(pinned.map { it!!.identity(ref) }, listed.title)
            }
            if (known == null && pinned.any { it != null }) known = ref
        }
        return Matched.Unpinned(known)
    }

    private suspend fun listing(ref: SubmissionRef): FileListing = listings.getOrPut(ref) { ask(ref) }.also { if (it is FileListing.Unavailable) unanswered = true }

    private suspend fun ask(ref: SubmissionRef): FileListing = when (val asked = limit.withPermit { attempt { gameBanana.files(ref) } }) {
        is Attempt.Done -> asked.value
        is Attempt.Failed -> FileListing.Unavailable(asked.reason)
    }

    private fun String.plain() = trim().replace(Spaces, " ").lowercase()

    private companion object {
        const val SearchResults = 5
        val Spaces = Regex("""(?U)\s+""")
    }
}

internal fun RemoteFile.identity(submission: SubmissionRef) = SubmissionIdentity(submission.kind, submission.id, id, md5)
