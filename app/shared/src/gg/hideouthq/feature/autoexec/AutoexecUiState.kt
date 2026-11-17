package gg.hideouthq.feature.autoexec

import gg.hideouthq.core.autoexec.AutoexecDocument
import gg.hideouthq.core.autoexec.AutoexecState
import gg.hideouthq.core.autoexec.CommandCategory
import gg.hideouthq.core.autoexec.CommandLibrary
import gg.hideouthq.core.autoexec.CommandRow
import gg.hideouthq.core.autoexec.FileState
import gg.hideouthq.core.autoexec.LibraryCommand
import gg.hideouthq.core.autoexec.LockedLines
import gg.hideouthq.core.data.SavedNote
import gg.hideouthq.core.library.ConfigTarget

enum class AutoexecView { List, Text }

/** What the page hands the desktop: autoexec.cfg to an editor, or its folder to the file manager. */
enum class AutoexecOpen { Editor, Folder }

internal data class AutoexecChoices(
    val view: AutoexecView = AutoexecView.List,
    val adding: String = "",
    val query: String = "",
    val category: CommandCategory? = null,
    val notOpened: AutoexecOpen? = null,
)

data class AutoexecUiState(
    /** The header's `game/citadel/cfg/autoexec.cfg`, written with the separator of the system the game is on. */
    val path: String,
    val file: AutoexecFile,
    val view: AutoexecView,
    val adding: String,
    val library: LibraryUi,
    val note: SavedNote,
    /** Said in place of [note]: what the desktop could not open. */
    val notOpened: AutoexecOpen?,
)

sealed interface AutoexecFile {
    data object NoGame : AutoexecFile
    data object Reading : AutoexecFile
    data class Unreadable(val reason: String) : AutoexecFile

    data class Ready(
        val commandCount: Int,
        val commands: List<CommandItem>,
        /** The Text view's lines that cannot be edited: the header and every section. */
        val locked: List<LockedLines>,
    ) : AutoexecFile {
        val on: Int get() = commands.count { it.row.on }
        val off: Int get() = commands.size - on
    }
}

/** A List row: [description] from the library, null for a command it does not have. */
data class CommandItem(val row: CommandRow, val description: String?)

data class LibraryUi(
    val query: String,
    val category: CommandCategory?,
    /** Each category with its whole count, whatever the search or the chip shows. */
    val categories: List<Pair<CommandCategory, Int>>,
    val groups: List<LibraryGroup>,
    /** The card header's "9 commands": the whole library. */
    val total: Int,
    /** False until the game's file is read, as nothing can be added before. */
    val canAdd: Boolean,
)

data class LibraryGroup(val category: CommandCategory, val items: List<LibraryItem>)

/** [added]: a line of this command is in the file, on or off, so its button is the check that takes every one out. */
data class LibraryItem(val command: LibraryCommand, val added: Boolean)

internal fun autoexecUi(autoexec: AutoexecState, choices: AutoexecChoices, separator: Char): AutoexecUiState {
    val file = when (val file = autoexec.file) {
        FileState.NoGame -> AutoexecFile.NoGame
        FileState.Reading -> AutoexecFile.Reading
        is FileState.Unreadable -> AutoexecFile.Unreadable(file.reason)
        is FileState.Read -> ready(file.document)
    }
    val path = ConfigTarget.Autoexec.segments.joinToString(separator.toString())
    return AutoexecUiState(path, file, choices.view, choices.adding, library((autoexec.file as? FileState.Read)?.document, choices), autoexec.note, choices.notOpened)
}

private fun ready(document: AutoexecDocument): AutoexecFile.Ready =
    AutoexecFile.Ready(document.commandCount, document.commands.map { CommandItem(it, CommandLibrary.find(it.ref.name)?.description) }, document.editorText.locked)

private fun library(document: AutoexecDocument?, choices: AutoexecChoices): LibraryUi {
    val inFile = document?.commands.orEmpty().map { it.ref.name.lowercase() }.toSet()
    val query = choices.query.trim()
    val matching = CommandLibrary.Commands.filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) || it.description.contains(query, ignoreCase = true) }
    val groups = CommandCategory.entries.filter { choices.category == null || it == choices.category }.mapNotNull { category ->
        matching.filter { it.category == category }.takeIf { it.isNotEmpty() }?.let { commands -> LibraryGroup(category, commands.map { LibraryItem(it, it.name.lowercase() in inFile) }) }
    }
    val categories = CommandCategory.entries.map { category -> category to CommandLibrary.Commands.count { it.category == category } }.filter { it.second > 0 }
    return LibraryUi(choices.query, choices.category, categories, groups, CommandLibrary.Commands.size, canAdd = document != null)
}
