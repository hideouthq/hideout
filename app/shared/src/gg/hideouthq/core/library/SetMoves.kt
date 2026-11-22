package gg.hideouthq.core.library

import gg.hideouthq.core.data.EntryState
import gg.hideouthq.core.data.LinkResult
import gg.hideouthq.core.data.Removal
import gg.hideouthq.core.data.TwinLinks

/**
 * Gives sets of files new names through anchors, over whatever names [links] addresses. Each source gets an anchor, a
 * second name of the same file; the sources go; the targets are linked from the anchors; the anchors go once the
 * journal has saved the targets, each only while it is one file with its target. A source goes only while it is one
 * file with its anchor and holds the expected bytes; an undo takes a target back while it is one file with its anchor,
 * whatever it holds, and drops an anchor only while it is one file with its source, whatever it holds. Each set's
 * directory goes first and comes back last, so the game never mounts a directory without its chunks, and nothing ever
 * replaces a name that is taken. It never saves: the journal saves each step before the next. [fenced] says why the
 * game cannot be changed now, when it cannot. An adoption uses only [anchor], [dropAnchors] and [release]: see
 * [PendingChange.Adopt].
 */
internal class SetMoves<E>(private val links: TwinLinks<E>, private val fenced: suspend () -> String?) {
    /** Links every source to its anchor and proves each anchor is one file with its source, holding the expected bytes. */
    suspend fun anchor(sets: List<List<Moved<E>>>): Linked {
        for (file in sets.flatten()) {
            fenced()?.let { return Linked.Wait(it) }
            when (val made = attempt { links.link(file.source, file.anchor) }) {
                is Attempt.Failed -> return Linked.Undo(LibraryProblem.PlacementFailed(made.reason))
                is Attempt.Done -> when (val result = made.value) {
                    LinkResult.Linked, LinkResult.Taken -> Unit
                    LinkResult.NotThere -> return Linked.Undo(LibraryProblem.FileMissing(file.sourcePath))
                    is LinkResult.Failed -> return Linked.Undo(LibraryProblem.PlacementFailed(result.reason))
                }
            }
            when (val seen = attempt { links.inspect(file.anchor, file.source) }) {
                is Attempt.Failed -> return Linked.Wait(seen.reason)
                is Attempt.Done -> when (val state = seen.value) {
                    is EntryState.Present -> when {
                        !state.twin -> return Linked.Undo(LibraryProblem.PlacementFailed(ChangedWhileMoved))
                        state.image != file.image -> return Linked.Undo(LibraryProblem.FileChanged(file.sourcePath))
                    }
                    is EntryState.Unreadable -> return Linked.Wait(state.reason)
                    EntryState.Absent, EntryState.NotPlain -> return Linked.Undo(LibraryProblem.PlacementFailed(ChangedWhileMoved))
                }
            }
        }
        return Linked.Done
    }

    /** Undoes an [anchor] that did not finish: anchors that are one file with their source go. */
    suspend fun dropAnchors(sets: List<List<Moved<E>>>): Undone {
        val left = mutableListOf<String>()
        for (file in sets.flatten()) {
            fenced()?.let { return Undone(left, it) }
            when (val removed = removeAnchor(file)) {
                is Attempt.Failed -> return Undone(left, removed.reason)
                is Attempt.Done -> if (!removed.value) left += file.anchorPath
            }
        }
        return Undone(left, null)
    }

    /** Removes every source while it is one file with its anchor, each set's directory first. */
    suspend fun release(sets: List<List<Moved<E>>>): Linked {
        for (set in sets) {
            val directory = set.single { it.isDirectory }.source
            for (file in set.sortedByDescending { it.isDirectory }) {
                fenced()?.let { return Linked.Wait(it) }
                if (!file.isDirectory) {
                    when (val gone = links.directoryGone(directory)) {
                        is Attempt.Failed -> return Linked.Wait(gone.reason)
                        is Attempt.Done -> if (!gone.value) return Linked.Undo(LibraryProblem.PlacementFailed(ChangedWhileMoved))
                    }
                }
                when (val removed = attempt { links.removeTwin(file.source, file.anchor, file.image) }) {
                    is Attempt.Failed -> return Linked.Wait(removed.reason)
                    is Attempt.Done -> when (val result = removed.value) {
                        Removal.Removed, Removal.Absent -> Unit
                        is Removal.Kept -> return Linked.Undo(LibraryProblem.PlacementFailed(ChangedWhileMoved))
                        is Removal.Failed -> return Linked.Wait(result.reason)
                    }
                }
            }
        }
        return Linked.Done
    }

    /** Links every target from its anchor, each set's chunks first and its directory last, then proves every target. */
    suspend fun link(sets: List<List<Moved<E>>>): Linked {
        for (set in sets) {
            for (file in set.sortedBy { it.isDirectory }) {
                fenced()?.let { return Linked.Wait(it) }
                when (val made = attempt { links.link(file.anchor, file.target) }) {
                    is Attempt.Failed -> return Linked.Undo(LibraryProblem.PlacementFailed(made.reason))
                    is Attempt.Done -> when (val result = made.value) {
                        LinkResult.Linked -> Unit
                        LinkResult.Taken -> when (val linked = links.linkedTarget(file.target, listOf(file.anchor))) {
                            is Attempt.Failed -> return Linked.Wait(linked.reason)
                            is Attempt.Done -> if (!linked.value) return Linked.Undo(LibraryProblem.NameTaken(file.targetPath))
                        }
                        LinkResult.NotThere -> return Linked.Undo(LibraryProblem.PlacementFailed(ChangedWhileMoved))
                        is LinkResult.Failed -> return Linked.Undo(LibraryProblem.PlacementFailed(result.reason))
                    }
                }
            }
        }
        for (file in sets.flatten()) {
            when (val seen = attempt { links.inspect(file.target, file.anchor) }) {
                is Attempt.Failed -> return Linked.Wait(seen.reason)
                is Attempt.Done -> when (val state = seen.value) {
                    is EntryState.Present -> if (!state.twin || state.image != file.image) return Linked.Undo(LibraryProblem.PlacementFailed(ChangedWhileMoved))
                    is EntryState.Unreadable -> return Linked.Wait(state.reason)
                    EntryState.Absent, EntryState.NotPlain -> return Linked.Undo(LibraryProblem.PlacementFailed(ChangedWhileMoved))
                }
            }
        }
        return Linked.Done
    }

    /**
     * Removes each anchor while it is one file with its target; any other stays and is reported, as it may be the only
     * copy of what the committed target holds.
     */
    suspend fun clean(sets: List<List<Moved<E>>>): Undone {
        val left = mutableListOf<String>()
        for (file in sets.flatten()) {
            fenced()?.let { return Undone(left, it) }
            when (val removed = attempt { links.removeLeftover(file.anchor, file.target, file.image, needed = true) }) {
                is Attempt.Failed -> return Undone(left, removed.reason)
                is Attempt.Done -> when (val result = removed.value) {
                    Removal.Removed, Removal.Absent -> Unit
                    is Removal.Kept -> left += file.anchorPath
                    is Removal.Failed -> return Undone(left, result.reason)
                }
            }
        }
        return Undone(left, null)
    }

    /**
     * Puts every moving set back under its own name: targets one file with their anchor go whatever they hold, which the
     * anchor keeps, each set's directory first, and a chunk only while nothing at all is at its directory's name.
     * Then sources come back from the anchors, chunks first, and a directory only once all its chunks are back.
     */
    suspend fun undo(sets: List<List<Moved<E>>>): Undone {
        val left = mutableListOf<String>()
        for (set in sets) {
            val directory = set.single { it.isDirectory }.target
            var directoryStays = false
            for (file in set.sortedByDescending { it.isDirectory }) {
                val state = when (val seen = attempt { links.inspect(file.target, file.anchor) }) {
                    is Attempt.Failed -> return Undone(left, seen.reason)
                    is Attempt.Done -> seen.value
                }
                when {
                    state is EntryState.Unreadable -> return Undone(left, state.reason)
                    state == EntryState.Absent -> Unit
                    state !is EntryState.Present || !state.twin -> if (file.isDirectory) directoryStays = true
                    directoryStays -> left += file.targetPath
                    else -> {
                        fenced()?.let { return Undone(left, it) }
                        if (!file.isDirectory) {
                            when (val gone = links.directoryGone(directory)) {
                                is Attempt.Failed -> return Undone(left, gone.reason)
                                is Attempt.Done -> if (!gone.value) {
                                    directoryStays = true
                                    left += file.targetPath
                                    continue
                                }
                            }
                        }
                        when (val removed = attempt { links.unlinkTwin(file.target, file.anchor) }) {
                            is Attempt.Failed -> return Undone(left, removed.reason)
                            is Attempt.Done -> when (val result = removed.value) {
                                Removal.Removed, Removal.Absent -> Unit
                                is Removal.Kept -> {
                                    left += file.targetPath
                                    if (file.isDirectory) directoryStays = true
                                }
                                is Removal.Failed -> return Undone(left, result.reason)
                            }
                        }
                    }
                }
            }
        }
        for (set in sets) {
            var whole = true
            for (file in set.sortedBy { it.isDirectory }) {
                if (file.isDirectory && !whole) continue
                val state = when (val seen = attempt { links.inspect(file.source, file.anchor) }) {
                    is Attempt.Failed -> return Undone(left, seen.reason)
                    is Attempt.Done -> seen.value
                }
                when {
                    state is EntryState.Unreadable -> return Undone(left, state.reason)
                    state is EntryState.Present && state.twin -> Unit
                    state != EntryState.Absent -> whole = false
                    else -> {
                        fenced()?.let { return Undone(left, it) }
                        when (val made = attempt { links.link(file.anchor, file.source) }) {
                            is Attempt.Failed -> return Undone(left, made.reason)
                            is Attempt.Done -> when (val result = made.value) {
                                LinkResult.Linked -> Unit
                                LinkResult.Taken, LinkResult.NotThere -> whole = false
                                is LinkResult.Failed -> return Undone(left, result.reason)
                            }
                        }
                    }
                }
            }
        }
        for (file in sets.flatten()) {
            fenced()?.let { return Undone(left, it) }
            when (val removed = removeAnchor(file)) {
                is Attempt.Failed -> return Undone(left, removed.reason)
                is Attempt.Done -> if (!removed.value) left += file.anchorPath
            }
        }
        return Undone(left.distinct(), null)
    }

    private suspend fun removeAnchor(file: Moved<E>): Attempt<Boolean> = when (val removed = attempt { links.removeLeftover(file.anchor, file.source, file.image, needed = true) }) {
        is Attempt.Failed -> removed
        is Attempt.Done -> when (val result = removed.value) {
            Removal.Removed, Removal.Absent -> Attempt.Done(true)
            is Removal.Kept -> Attempt.Done(false)
            is Removal.Failed -> Attempt.Failed(result.reason)
        }
    }
}

/** One moving file: its name now, the anchor that keeps its bytes reachable, and the name it takes. Paths are for reports. */
internal class Moved<E>(
    val source: E,
    val anchor: E,
    val target: E,
    val image: FileImage,
    val isDirectory: Boolean,
    val sourcePath: String,
    val targetPath: String,
    val anchorPath: String,
)

/** How far one step of a journal that links files got. */
internal sealed interface Linked {
    data object Done : Linked

    data class Undo(val problem: LibraryProblem) : Linked

    data class Wait(val reason: String) : Linked
}

/** What an undo or a clean left in place, and why it stopped early, if it did. */
internal class Undone(val leftInPlace: List<String>, val interrupted: String?)

/**
 * Whether nothing at all is at [directory] now, so a chunk of its set can go without leaving a directory the game
 * would mount without it. Asked afresh before each chunk: a name seen empty once can be taken again.
 */
internal suspend fun <E> TwinLinks<E>.directoryGone(directory: E): Attempt<Boolean> = when (val seen = attempt { inspect(directory, directory) }) {
    is Attempt.Failed -> seen
    is Attempt.Done -> when (val state = seen.value) {
        is EntryState.Unreadable -> Attempt.Failed(state.reason)
        else -> Attempt.Done(state == EntryState.Absent)
    }
}

/**
 * Deletes [entry], a name only a change makes, while it is one file with [stays], the name it was made beside or the
 * file it fed, whatever it holds: its bytes live on there. While no installed record needs its bytes from it ([needed]
 * is false), it also goes while it holds [expected], the bytes its journal recorded. Anything else stays: the only copy
 * of a record's bytes, or bytes no one recorded.
 */
internal suspend fun <E> TwinLinks<E>.removeLeftover(entry: E, stays: E, expected: FileImage, needed: Boolean): Removal {
    if (!needed) removeExact(entry, expected).let { if (it !is Removal.Kept) return it }
    return unlinkTwin(entry, stays)
}

/** Whether [entry] is one file with any of [witnesses]. */
internal suspend fun <E> TwinLinks<E>.linkedTarget(entry: E, witnesses: List<E>): Attempt<Boolean> {
    for (witness in witnesses) {
        when (val seen = attempt { inspect(entry, witness) }) {
            is Attempt.Failed -> return seen
            is Attempt.Done -> when (val state = seen.value) {
                EntryState.Absent, EntryState.NotPlain -> return Attempt.Done(false)
                is EntryState.Unreadable -> return Attempt.Failed(state.reason)
                is EntryState.Present -> if (state.twin) return Attempt.Done(true)
            }
        }
    }
    return Attempt.Done(false)
}

internal const val ChangedWhileMoved = "a file was changed, replaced or removed while it was being moved"
