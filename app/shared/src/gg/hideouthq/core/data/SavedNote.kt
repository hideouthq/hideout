package gg.hideouthq.core.data

import gg.hideouthq.core.library.LibraryProblem

/** What both game pages' "● Saved · …" says: when a saved change reaches the game, or which save failed. */
sealed interface SavedNote {
    data object NextLaunch : SavedNote

    data object NextStart : SavedNote

    /** The crosshair waits for Deadlock to close, as the game saves its own over it when it exits. */
    data object Waiting : SavedNote

    /** Saved in the game's own folder only: Steam Cloud's copy, which the game reads, was not found. */
    data object GameFolderOnly : SavedNote

    /** A save that did not happen, which Retry tries again. */
    sealed interface Failed : SavedNote {
        /** The game's file's, refused for [problem]. */
        data class File(val problem: LibraryProblem) : Failed

        /** `crosshairs.json`'s, which could not be written. */
        data object Yours : Failed
    }
}
