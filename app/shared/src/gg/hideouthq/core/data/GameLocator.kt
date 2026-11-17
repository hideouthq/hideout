package gg.hideouthq.core.data

import gg.hideouthq.core.library.FolderCheck
import gg.hideouthq.core.library.GameRoot

/** Finds Deadlock on this computer and tells a Deadlock folder from the wrong one. Read-only. */
interface GameLocator {
    /** How this platform separates folders in a path, for showing relative paths such as `game\citadel\gameinfo.gi`. */
    val separator: Char

    /** Every Deadlock folder Steam knows about, best first, without duplicates. Unreadable Steam files are skipped. */
    suspend fun detect(): List<GameRoot>

    /**
     * The Deadlock folder [path] is, or is inside as its `game` or `game/citadel`, or holds as a Steam library, its
     * `steamapps` or its `common`; else why there is none. A missing or unreadable folder is [FolderCheck.Invalid].
     */
    suspend fun check(path: String): FolderCheck
}

/** Asks the user to choose a folder in a dialog called [title], starting at [start] when there is one. Null when they cancel. */
fun interface FolderPicker {
    suspend fun pick(title: String, start: String?): String?
}
