package gg.hideouthq.core.data

import gg.hideouthq.core.library.Manager

/** What is running now. While Deadlock runs, it holds its files, and Hideout changes none of them. */
interface GameProcesses {
    suspend fun scan(): ProcessScan

    /** Ends the running game. Throws when it would not end. Nothing is wrong when no game runs. */
    suspend fun endGame()
}

sealed interface ProcessScan {
    /** Deadlock was seen running ([game]) or every process could be read. [managers] are the other mod managers seen open. */
    data class Seen(val game: Boolean = false, val managers: Set<Manager> = emptySet()) : ProcessScan

    /** The process list could not be read, so the game may be running. */
    data class Unknown(val reason: String) : ProcessScan
}
