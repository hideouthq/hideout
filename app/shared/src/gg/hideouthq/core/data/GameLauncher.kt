package gg.hideouthq.core.data

import gg.hideouthq.core.library.GameRoot

/** Starts the Deadlock installed at a [GameRoot] through Steam; whether its mods load is already settled in its files. */
interface GameLauncher {
    suspend fun launch(game: GameRoot)
}
