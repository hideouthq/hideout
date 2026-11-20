package gg.hideouthq.library

import gg.hideouthq.core.data.FolderOpener
import gg.hideouthq.core.library.CitadelPath
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.SetFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.nio.file.NoSuchFileException

class DesktopFolderOpener(private val handoff: DesktopHandoff = DesktopHandoff()) : FolderOpener {
    override suspend fun open(root: GameRoot, folder: CitadelPath): Boolean = runInterruptible(Dispatchers.IO) {
        try {
            val there = try {
                gameFolder(root, SetFolder.GameSegments + folder.value.split('/'))
            } catch (e: NoSuchFileException) {
                if (folder != CitadelPath.Addons) throw e
                gameFolder(root, SetFolder.GameSegments)
            }
            handoff.open(there.toFile())
            true
        } catch (e: IOException) {
            false
        }
    }
}
