package gg.hideouthq.library

import gg.hideouthq.core.data.FolderOpener
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.SetFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.nio.file.NoSuchFileException

class DesktopFolderOpener(private val handoff: DesktopHandoff = DesktopHandoff()) : FolderOpener {
    override suspend fun open(root: GameRoot, folder: SetFolder): Boolean = runInterruptible(Dispatchers.IO) {
        try {
            val there = try {
                managedFolder(root, folder, create = false)
            } catch (e: NoSuchFileException) {
                if (folder != SetFolder.Addons) throw e
                gameFolder(root, SetFolder.GameSegments)
            }
            handoff.open(there.toFile())
            true
        } catch (e: IOException) {
            false
        }
    }
}
