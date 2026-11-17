package gg.hideouthq.core.library

import kotlinx.serialization.Serializable

/** A Deadlock folder, absolute and with links resolved, so two spellings of one folder are equal. */
@Serializable
@JvmInline
value class GameRoot(val path: String)

/** What makes a folder Deadlock's: this file, relative to it. */
val GameInfoSegments: List<String> = listOf("game", "citadel", "gameinfo.gi")

sealed interface FolderCheck {
    data class Valid(val root: GameRoot) : FolderCheck

    data class Invalid(val path: String, val problem: FolderProblem) : FolderCheck
}

enum class FolderProblem {
    /** No Deadlock in it, around it or in the Steam library it is. */
    NotDeadlock,

    /** Not there, not a folder, or not readable. */
    Unavailable,
}
