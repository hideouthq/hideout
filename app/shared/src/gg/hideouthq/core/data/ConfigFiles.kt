package gg.hideouthq.core.data

import gg.hideouthq.core.library.CitadelPath
import gg.hideouthq.core.library.GameRoot

/**
 * A config file under `game/citadel` handed to the desktop: to the app its type opens with, or its folder to the file
 * manager. Each is true once the desktop took it, false when it could not.
 */
interface ConfigFiles {
    suspend fun edit(root: GameRoot, path: CitadelPath): Boolean

    suspend fun show(root: GameRoot, path: CitadelPath): Boolean

    companion object {
        val Unavailable = object : ConfigFiles {
            override suspend fun edit(root: GameRoot, path: CitadelPath) = false
            override suspend fun show(root: GameRoot, path: CitadelPath) = false
        }
    }
}
