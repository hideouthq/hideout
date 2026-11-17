package gg.hideouthq.core.crosshair

import gg.hideouthq.core.crosshair.community.CommunityCrosshair
import gg.hideouthq.core.data.CacheStorage
import gg.hideouthq.core.data.ReticleRead
import gg.hideouthq.core.data.SavedNote
import gg.hideouthq.core.library.GameActivity
import gg.hideouthq.core.library.GameStatus
import gg.hideouthq.core.library.LibraryProblem
import gg.hideouthq.core.library.ModLibrary
import gg.hideouthq.core.library.ReticleWrite
import gg.hideouthq.core.library.randomId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.milliseconds

/**
 * The owner of the crosshair Deadlock uses and of Yours, the crosshairs the user keeps in `crosshairs.json` in [data].
 * Both game pages and an import's crosshair go through it.
 *
 * The crosshair the game uses is the reticle it has saved ([ModLibrary.readReticle]), read again whenever its file
 * changes; which crosshair of Yours is in use is decided by equal settings ([inUse]). Use in game, and every change to
 * the crosshair in use, makes that crosshair the one waiting to be written: it is in use at once, `crosshairs.json`
 * keeps it so a restart does too, and [ModLibrary.writeReticle] writes it half a second after the last change once the
 * Library knows Deadlock is closed, as it waits to change mods, since the game saves its own reticle again as it exits.
 *
 * Every action is one change to what [state] is made from, so each makes one state: a slider step on the crosshair in
 * use never shows it out of use, or a "Mine" that comes and goes, on the way.
 */
class Crosshairs(
    private val library: ModLibrary,
    private val data: CacheStorage,
    private val scope: CoroutineScope,
    private val ids: () -> String = ::randomId,
    private val log: (String) -> Unit = {},
) {
    private val inputs = MutableStateFlow(Inputs())
    private val updating = Any()
    private val mutableState = MutableStateFlow(inputs.value.state)

    val state: StateFlow<CrosshairsState> = mutableState.asStateFlow()

    private val writing = Mutex()

    private var stored: Kept? = null
    private val storing = Mutex()

    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            val read = load()
            if (read == null) {
                update { it.copy(kept = Kept(emptyList(), lastUsed = null), yoursUnreadable = true) }
                return@launch
            }
            stored = read
            update { it.copy(kept = read) }
            inputs.map { it.kept }.filterNotNull().distinctUntilChanged().debounce(SaveAfter).collect { store(it) }
        }
        scope.launch {
            library.state.map { (it.game as? GameStatus.Ready)?.root }.distinctUntilChanged().collectLatest { root ->
                update { it.copy(game = if (root == null) GameCrosshair.NoGame else GameCrosshair.Reading, refused = null) }
                if (root == null) return@collectLatest
                library.reticleChanges(root).collect {
                    val read = writing.withLock { library.readReticle(root) }
                    update { it.copy(game = read.shown()) }
                }
            }
        }
        scope.launch { library.state.map { it.deadlock }.distinctUntilChanged().collect { deadlock -> update { it.copy(deadlock = deadlock) } } }
        scope.launch {
            combine(inputs.map { it.writable }, library.state.map { it.library }.distinctUntilChanged()) { crosshair, library -> crosshair?.let { it to library } }
                .distinctUntilChanged().debounce(SaveAfter).collect { if (it != null) write() }
        }
    }

    /**
     * Use in game: [pick] joins Yours when it is a community crosshair Yours does not hold unchanged, and becomes the
     * crosshair in use. A "Mine" no crosshair of Yours matched is kept as it was, so switching away never loses it.
     * Nothing changes before Yours and the game's settings are read.
     */
    fun use(pick: CrosshairPick) = transact { now ->
        val kept = now.changeable() ?: return@transact now to Unit
        val saved = (now.game as? GameCrosshair.Saved)?.crosshair ?: return@transact now to Unit
        val yours = now.state.yours
        var next = kept
        val entry = when (pick) {
            is CrosshairPick.Yours -> yours.entries.firstOrNull { it.id == pick.id }
            is CrosshairPick.Community -> yours.entries.firstOrNull { (it.origin as? CrosshairOrigin.Community)?.id == pick.crosshair.id && it.crosshair == pick.crosshair.crosshair }
                ?: community(pick.crosshair).also { next = next.added(it) }
        } ?: return@transact now to Unit
        yours.inUse?.takeIf { it != entry.id }?.let { next = next.keeping(yours, it) }
        now.copy(kept = next.copy(lastUsed = entry.id, waiting = next.toWrite(entry.crosshair, saved))) to Unit
    }

    /**
     * Changes Yours. A change to the crosshair in use reaches the game too. Returns the id of the entry made or changed,
     * for the page to select; null when refused: the crosshair in use cannot be deleted or unsaved, a name cannot be
     * blank, and nothing changes before Yours is read.
     */
    fun change(change: YoursChange): CrosshairId? = transact { now -> changed(now, change) }

    suspend fun changeWhenRead(change: YoursChange): CrosshairId? {
        inputs.first { it.kept != null }
        return change(change)
    }

    fun retry() {
        scope.launch { saveNow() }
    }

    fun saveBeforeExit(): Job = scope.launch { saveNow() }

    private suspend fun saveNow() {
        inputs.value.changeable()?.let { store(it) }
        if (inputs.value.writable == null && inputs.value.refused == null) return
        write()
        inputs.value.changeable()?.let { store(it) }
    }

    private fun changed(now: Inputs, change: YoursChange): Pair<Inputs, CrosshairId?> {
        val kept = now.changeable() ?: return now to null
        val yours = now.state.yours
        fun entry(id: CrosshairId) = yours.entries.firstOrNull { it.id == id }
        val current = yours.inUse

        fun keptAs(next: Kept, id: CrosshairId?): Pair<Inputs, CrosshairId?> {
            val held = if (current == null || inUse(now.running, next.crosshairs, next.lastUsed).inUse == current) next else next.keeping(yours, current).copy(lastUsed = current)
            return now.copy(kept = held) to id
        }
        return when (change) {
            is YoursChange.Settings -> entry(change.id)?.let { entry ->
                val next = kept.keeping(yours, entry.id).replacing(entry.copy(crosshair = change.crosshair))
                if (entry.id != current) return keptAs(next, entry.id)
                val saved = (now.game as? GameCrosshair.Saved)?.crosshair
                now.copy(kept = next.copy(lastUsed = entry.id, waiting = next.toWrite(change.crosshair, saved))) to entry.id
            } ?: (now to null)
            is YoursChange.Rename -> entry(change.id)?.takeIf { change.name.isNotBlank() }?.let { entry ->
                keptAs(kept.keeping(yours, entry.id).replacing(entry.copy(name = change.name.trim())), entry.id)
            } ?: (now to null)
            is YoursChange.Reset -> entry(change.id)?.let { changed(now, YoursChange.Settings(it.id, it.origin.base)) } ?: (now to null)
            is YoursChange.Delete -> entry(change.id)?.takeIf { it.id != current }?.let { entry ->
                keptAs(kept.copy(crosshairs = kept.crosshairs.filterNot { it.id == entry.id }), entry.id)
            } ?: (now to null)
            YoursChange.Create -> made("New crosshair", CrosshairOrigin.Made).let { keptAs(kept.added(it), it.id) }
            is YoursChange.Duplicate -> entry(change.id)?.let { entry ->
                val origin = (entry.origin as? CrosshairOrigin.Community)?.let { CrosshairOrigin.CommunityCopy(it.id, it.creator, it.base) } ?: entry.origin
                val copy = entry.copy(id = CrosshairId(ids()), name = "${entry.name} copy", origin = origin)
                keptAs(kept.keeping(yours, entry.id).added(copy), copy.id)
            } ?: (now to null)
            is YoursChange.Save -> yours.entries.firstOrNull { it.savedFrom == change.crosshair.id }?.let { now to it.id }
                ?: community(change.crosshair).let { keptAs(kept.added(it), it.id) }
            is YoursChange.Unsave -> {
                val saved = yours.entries.filter { it.savedFrom == change.communityId }
                if (saved.isEmpty() || saved.any { it.id == current }) return now to null
                keptAs(kept.copy(crosshairs = kept.crosshairs.filterNot { it in saved }), saved.first().id)
            }
            is YoursChange.Copy -> made(change.crosshair.name, CrosshairOrigin.CommunityCopy(change.crosshair.id, change.crosshair.creator, change.crosshair.crosshair)).let { keptAs(kept.added(it), it.id) }
            is YoursChange.Import -> made(change.name ?: "Imported crosshair", CrosshairOrigin.Imported(change.crosshair)).let { keptAs(kept.added(it), it.id) }
            is YoursChange.Adopt -> if (kept.crosshairs.any { it.crosshair == change.origin.base }) now to null else made("Imported crosshair", change.origin).let { keptAs(kept.added(it), it.id) }
        }
    }

    private suspend fun write(): Unit = writing.withLock {
        val sent = inputs.value.kept?.waiting ?: return@withLock update { it.copy(refused = null) }
        when (val written = library.writeReticle(sent)) {
            is ReticleWrite.Written -> update { now ->
                now.copy(game = written.read.shown(), kept = now.kept?.let { if (it.waiting == sent) it.copy(waiting = null) else it }, refused = null)
            }
            is ReticleWrite.Refused -> update { it.copy(refused = written.problem.takeUnless { problem -> problem.waits }) }
        }
    }

    private fun <T> transact(change: (Inputs) -> Pair<Inputs, T>): T = synchronized(updating) {
        val (next, result) = change(inputs.value)
        inputs.value = next
        mutableState.value = next.state
        result
    }

    private fun update(change: (Inputs) -> Inputs) = transact { change(it) to Unit }

    private fun Inputs.changeable(): Kept? = kept?.takeIf { !yoursUnreadable }

    private fun made(name: String, origin: CrosshairOrigin) = SavedCrosshair(CrosshairId(ids()), name, origin, origin.base)

    private fun community(crosshair: CommunityCrosshair) = made(crosshair.name, CrosshairOrigin.Community(crosshair.id, crosshair.creator, crosshair.crosshair))

    private suspend fun load(): Kept? {
        val text = try {
            data.read(KeptCodec.FileName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("${KeptCodec.FileName} could not be read (${e.message}), so Yours is left as it is until Hideout starts again")
            return null
        }
        val kept = KeptCodec.decode(text)
        if (kept == null) log("${KeptCodec.FileName} is not in a format Hideout reads, so Yours is left as it is until Hideout starts again")
        return kept
    }

    private suspend fun store(kept: Kept): Unit = storing.withLock {
        val saved = kept == stored || try {
            data.write(KeptCodec.FileName, KeptCodec.encode(kept))
            stored = kept
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("${KeptCodec.FileName} could not be saved (${e.message}), so Yours is kept as shown until it can be")
            false
        }
        update { it.copy(yoursUnsaved = !saved) }
    }
}

private val LibraryProblem.waits: Boolean
    get() = this == LibraryProblem.GameRunning || this is LibraryProblem.ProcessesUnknown || this is LibraryProblem.Interrupted || this is LibraryProblem.LibraryUnavailable

private fun Kept.keeping(yours: Yours, id: CrosshairId): Kept {
    if (crosshairs.any { it.id == id }) return this
    val entry = yours.entries.firstOrNull { it.id == id } ?: return this
    return copy(crosshairs = listOf(entry) + crosshairs)
}

private fun Kept.toWrite(crosshair: Crosshair, saved: Crosshair?): Crosshair? = crosshair.takeUnless { waiting == null && it == saved }

private fun Kept.replacing(entry: SavedCrosshair): Kept = copy(crosshairs = crosshairs.map { if (it.id == entry.id) entry else it })

private fun Kept.added(entry: SavedCrosshair): Kept = copy(crosshairs = crosshairs + entry)

private data class Inputs(
    val game: GameCrosshair = GameCrosshair.Reading,
    val kept: Kept? = null,
    val refused: LibraryProblem? = null,
    val yoursUnsaved: Boolean = false,
    val yoursUnreadable: Boolean = false,
    val deadlock: GameActivity = GameActivity.Unknown,
) {
    val writable: Crosshair? get() = kept?.waiting?.takeIf { deadlock == GameActivity.Closed && game is GameCrosshair.Saved }

    val running: Crosshair? get() = (game as? GameCrosshair.Saved)?.let { kept?.waiting ?: it.crosshair }

    val state: CrosshairsState
        get() = CrosshairsState(game, kept?.let { inUse(running, it.crosshairs, it.lastUsed) } ?: Yours.None, waiting = kept?.waiting != null, refused, yoursUnsaved, yoursUnreadable, deadlock)
}

private fun ReticleRead.shown(): GameCrosshair = when (this) {
    is ReticleRead.Read -> GameCrosshair.Saved(crosshair, cloud)
    ReticleRead.NotSavedYet -> GameCrosshair.NotSavedYet
    is ReticleRead.Unreadable -> GameCrosshair.Unreadable(reason)
}

sealed interface GameCrosshair {
    data object NoGame : GameCrosshair
    data object Reading : GameCrosshair

    /** Steam Cloud has no saved settings for the account signed in yet ([ReticleRead.NotSavedYet]). */
    data object NotSavedYet : GameCrosshair
    data class Unreadable(val reason: String) : GameCrosshair

    /** [crosshair] is what the game has saved; [cloud] is false when Steam Cloud's copy, which the game reads, was not found. */
    data class Saved(val crosshair: Crosshair, val cloud: Boolean) : GameCrosshair
}

data class CrosshairsState(
    val game: GameCrosshair,
    val yours: Yours,
    val waiting: Boolean,
    val refused: LibraryProblem?,
    val yoursUnsaved: Boolean,
    val yoursUnreadable: Boolean,
    val deadlock: GameActivity,
) {
    val inUse: SavedCrosshair? get() = yours.entries.firstOrNull { it.id == yours.inUse }

    val note: SavedNote
        get() = when {
            refused != null -> SavedNote.Failed.File(refused)
            yoursUnsaved -> SavedNote.Failed.Yours
            waiting && deadlock != GameActivity.Closed -> SavedNote.Waiting
            (game as? GameCrosshair.Saved)?.cloud == false -> SavedNote.GameFolderOnly
            else -> SavedNote.NextStart
        }
}

sealed interface CrosshairPick {
    data class Yours(val id: CrosshairId) : CrosshairPick
    data class Community(val crosshair: CommunityCrosshair) : CrosshairPick
}

sealed interface YoursChange {
    data class Settings(val id: CrosshairId, val crosshair: Crosshair) : YoursChange
    data class Rename(val id: CrosshairId, val name: String) : YoursChange

    data class Reset(val id: CrosshairId) : YoursChange
    data class Delete(val id: CrosshairId) : YoursChange

    data object Create : YoursChange
    data class Duplicate(val id: CrosshairId) : YoursChange

    data class Save(val crosshair: CommunityCrosshair) : YoursChange

    /** Saved, toggled off: every entry still as saved from community crosshair [communityId] goes; edited ones stay. */
    data class Unsave(val communityId: String) : YoursChange

    data class Copy(val crosshair: CommunityCrosshair) : YoursChange

    data class Import(val name: String?, val crosshair: Crosshair) : YoursChange

    /** An import's crosshair, [origin]'s values, joins Yours, not in use; refused when Yours holds its settings already. */
    data class Adopt(val origin: CrosshairOrigin) : YoursChange
}

private val SaveAfter = 500.milliseconds
