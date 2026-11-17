package gg.hideouthq.feature.autoexec

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import gg.hideouthq.core.autoexec.Autoexec
import gg.hideouthq.core.autoexec.AutoexecEdit
import gg.hideouthq.core.autoexec.CommandCategory
import gg.hideouthq.core.autoexec.CommandRef
import gg.hideouthq.core.autoexec.EditorText
import gg.hideouthq.core.autoexec.FileState
import gg.hideouthq.core.autoexec.TextHunk
import gg.hideouthq.core.data.ConfigFiles
import gg.hideouthq.core.library.ConfigTarget
import gg.hideouthq.core.library.GameStatus
import gg.hideouthq.core.library.ModLibrary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AutoexecViewModel(
    private val autoexec: Autoexec,
    private val library: ModLibrary,
    private val files: ConfigFiles,
) : ViewModel(), AutoexecPageActions {
    private val choices = MutableStateFlow(AutoexecChoices())

    val uiState: StateFlow<AutoexecUiState> = combine(autoexec.state, choices) { state, choices -> autoexecUi(state, choices, library.separator) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(StopTimeoutMillis), autoexecUi(autoexec.state.value, AutoexecChoices(), library.separator))

    /** The Text view's buffer, which always comes back to the document as shown. */
    val editor = TextFieldState()

    private var shown = EditorText(emptyList(), emptyList())

    init {
        viewModelScope.launch { autoexec.state.collect { reconcile() } }
        viewModelScope.launch { snapshotFlow { editor.text.toString() }.collect { reconcile() } }
    }

    override fun setView(view: AutoexecView) = choices.update { it.copy(view = view) }

    override fun setValue(ref: CommandRef, value: String) = edit(AutoexecEdit.SetValue(now(ref), value))

    override fun setOn(ref: CommandRef, on: Boolean) = edit(AutoexecEdit.SetOn(now(ref), on))

    override fun remove(ref: CommandRef) = edit(AutoexecEdit.Remove(now(ref)))

    override fun setAdding(text: String) = choices.update { it.copy(adding = text) }

    /** The Add field, split on its first space; text that does not start with a command name stays in the field. */
    override fun add() {
        val add = AutoexecEdit.Add.parse(choices.value.adding) ?: return
        edit(add)
        choices.update { it.copy(adding = "") }
    }

    override fun search(text: String) = choices.update { it.copy(query = text) }

    override fun filter(category: CommandCategory?) = choices.update { it.copy(category = category) }

    override fun toggle(item: LibraryItem) =
        edit(if (item.added) AutoexecEdit.RemoveAll(item.command.name) else AutoexecEdit.Add(item.command.name, item.command.suggested))

    override fun openInEditor() {
        val root = root() ?: return
        viewModelScope.launch { if (autoexec.save() == null) opened(AutoexecOpen.Editor, files.edit(root, ConfigTarget.Autoexec.path)) }
    }

    override fun showInFolder() {
        val root = root() ?: return
        viewModelScope.launch { opened(AutoexecOpen.Folder, files.show(root, ConfigTarget.Autoexec.path)) }
    }

    override fun retry() = autoexec.retry()

    override fun dismissNotOpened() = choices.update { it.copy(notOpened = null) }

    private fun root() = (library.state.value.game as? GameStatus.Ready)?.root

    private fun opened(target: AutoexecOpen, done: Boolean) = choices.update { it.copy(notOpened = target.takeUnless { done }) }

    private fun edit(edit: AutoexecEdit) {
        choices.update { it.copy(notOpened = null) }
        autoexec.edit(edit)
    }

    private fun now(ref: CommandRef): CommandRef = (autoexec.state.value.file as? FileState.Read)?.document?.commands
        ?.firstOrNull { it.ref.name.equals(ref.name, ignoreCase = true) && it.ref.occurrence == ref.occurrence }?.ref ?: ref

    @OptIn(ExperimentalFoundationApi::class)
    private fun reconcile() {
        val typed = editor.text.toString()
        if (typed != shown.text) TextHunk.between(shown, typed)?.let(::edit)
        val now = (autoexec.state.value.file as? FileState.Read)?.document?.editorText ?: return
        shown = now
        if (now.text == typed) return
        val cursor = editor.selection.start
        val caret = if (typed.commonPrefixWith(now.text).length < cursor) cursor + now.text.length - typed.length else cursor
        editor.edit {
            replace(0, length, now.text)
            placeCursorBeforeCharAt(caret.coerceIn(0, now.text.length))
        }
        editor.undoState.clearHistory()
    }
}

private const val StopTimeoutMillis = 5_000L
