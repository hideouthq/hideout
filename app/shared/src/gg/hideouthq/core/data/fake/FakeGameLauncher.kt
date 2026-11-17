package gg.hideouthq.core.data.fake

import gg.hideouthq.core.data.GameLauncher
import gg.hideouthq.core.library.GameRoot

class FakeGameLauncher : GameLauncher {
    val launches: MutableList<GameRoot> = mutableListOf()

    override suspend fun launch(game: GameRoot) {
        launches += game
    }
}
