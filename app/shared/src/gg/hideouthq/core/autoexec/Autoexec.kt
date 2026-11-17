package gg.hideouthq.core.autoexec

import gg.hideouthq.core.data.FileRead
import gg.hideouthq.core.data.GameInventory
import gg.hideouthq.core.data.MaxConfigBytes
import gg.hideouthq.core.data.SavedNote
import gg.hideouthq.core.library.AutoexecWrite
import gg.hideouthq.core.library.ConfigTarget
import gg.hideouthq.core.library.GameActivity
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.GameStatus
import gg.hideouthq.core.library.LibraryProblem
import gg.hideouthq.core.library.ModLibrary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.milliseconds

/**
 * The owner of what Deadlock runs when it starts: `cfg/autoexec.cfg`, which the Autoexec page shows and edits.
 *
 * The file is the truth. [state] shows the file as last read or written with the edits not saved yet made on top, so
 * a change on disk, from another editor, another tool or a restore, simply replaces what was read, and the pending
 * edits land on it. A save runs half a second after the last edit: [ModLibrary.writeAutoexec] reads the file again under
 * the library's lock, makes the pending edits on it and writes it through the journal, while Deadlock runs too.
 */
class Autoexec(
    private val library: ModLibrary,
    private val inventory: GameInventory,
    private val scope: CoroutineScope,
) {
    private val inputs = MutableStateFlow(Inputs())
    private val updating = Any()
    private val mutableState = MutableStateFlow(inputs.value.state)

    /** Made again by [update] before it returns, so an edit is in it before [edit] returns: a field that typed it never reads back the text before. */
    val state: StateFlow<AutoexecState> = mutableState.asStateFlow()

    private val flushing = Mutex()

    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            library.state.map { (it.game as? GameStatus.Ready)?.root }.distinctUntilChanged().collectLatest { root ->
                update { it.copy(onDisk = if (root == null) FileState.NoGame else FileState.Reading, pending = emptyList(), refused = null) }
                if (root == null) return@collectLatest
                library.configChanges(root, ConfigTarget.Autoexec.path).collect {
                    val file = flushing.withLock { read(root) }
                    update { it.copy(onDisk = file) }
                }
            }
        }
        scope.launch { inputs.map { it.pending }.distinctUntilChanged().debounce(500.milliseconds).collect { if (it.isNotEmpty()) flush() } }
        scope.launch { library.state.map { it.deadlock }.distinctUntilChanged().collect { deadlock -> update { it.copy(deadlock = deadlock) } } }
    }

    /** Shows [edit] at once and saves it with the edits around it. Ignored until the game's file has been read. */
    fun edit(edit: AutoexecEdit) = update { now ->
        if (now.onDisk !is FileState.Read) return@update now
        now.copy(pending = now.pending + edit)
    }

    fun retry() {
        scope.launch { flush() }
    }

    /**
     * Saves the pending edits now, and a file that is not there as the header alone, so an editor opened on it finds what
     * the page shows. Null once saved; otherwise why not, which [state] shows as a failed save too.
     */
    suspend fun save(): LibraryProblem? = flush(create = true)

    fun saveBeforeExit(): Job = scope.launch { flush() }

    private suspend fun flush(create: Boolean = false): LibraryProblem? = flushing.withLock {
        val sent = inputs.value.pending
        if (sent.isEmpty() && !create) return@withLock null
        val written = library.writeAutoexec { onDiskNow ->
            replay(onDiskNow, sent).let { if (create && it == AutoexecDocument.parse(null)) AutoexecDocument.parse(it.writable()) else it }
        }
        when (written) {
            is AutoexecWrite.Written -> {
                update { it.copy(onDisk = FileState.Read(written.document), pending = it.pending.let { edits -> edits.drop(edits.zip(sent).takeWhile { (now, then) -> now == then }.size) }, refused = null) }
                null
            }
            is AutoexecWrite.Refused -> written.problem.also { problem -> update { it.copy(refused = problem) } }
        }
    }

    private fun update(change: (Inputs) -> Inputs): Unit = synchronized(updating) {
        inputs.value = change(inputs.value)
        mutableState.value = inputs.value.state
    }

    private suspend fun read(root: GameRoot): FileState = try {
        when (val file = inventory.read(root, ConfigTarget.Autoexec.path, MaxConfigBytes)) {
            is FileRead.Present -> FileState.Read(AutoexecDocument.parse(file.bytes))
            FileRead.Absent -> FileState.Read(AutoexecDocument.parse(null))
            is FileRead.Unreadable -> FileState.Unreadable(file.reason)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        FileState.Unreadable(e.message ?: "autoexec.cfg could not be read")
    }
}

private data class Inputs(
    val onDisk: FileState = FileState.Reading,
    val pending: List<AutoexecEdit> = emptyList(),
    val refused: LibraryProblem? = null,
    val deadlock: GameActivity = GameActivity.Unknown,
) {
    val state: AutoexecState
        get() = AutoexecState((onDisk as? FileState.Read)?.let { FileState.Read(replay(it.document, pending)) } ?: onDisk, refused, deadlock)
}

data class AutoexecState(
    val file: FileState,
    val refused: LibraryProblem?,
    val deadlock: GameActivity,
) {
    val note: SavedNote
        get() = when {
            refused != null -> SavedNote.Failed.File(refused)
            deadlock == GameActivity.Running -> SavedNote.NextStart
            else -> SavedNote.NextLaunch
        }
}

sealed interface FileState {
    data object NoGame : FileState
    data object Reading : FileState
    data class Unreadable(val reason: String) : FileState
    data class Read(val document: AutoexecDocument) : FileState
}
