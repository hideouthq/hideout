package gg.hideouthq.library

import gg.hideouthq.core.data.ConfigFiles
import gg.hideouthq.core.library.CitadelPath
import gg.hideouthq.core.library.GameRoot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.IOException

class DesktopConfigFiles(private val handoff: DesktopHandoff = DesktopHandoff()) : ConfigFiles {
    override suspend fun edit(root: GameRoot, path: CitadelPath) = hand { handoff.open(file(root, path)) }

    override suspend fun show(root: GameRoot, path: CitadelPath) = hand { handoff.open(file(root, path).parentFile) }

    private fun file(root: GameRoot, path: CitadelPath) = File(root.path, "game/citadel/${path.value}")

    private suspend fun hand(open: () -> Unit): Boolean = runInterruptible(Dispatchers.IO) {
        try {
            open()
            true
        } catch (e: IOException) {
            false
        }
    }
}
