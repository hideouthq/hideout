package gg.hideouthq.library

import gg.hideouthq.core.data.CitadelFile
import gg.hideouthq.core.data.GameFiles
import gg.hideouthq.core.library.ForeignRoot
import gg.hideouthq.core.library.SetFolder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class NioGameFiles : NioTwinLinks<CitadelFile>(), GameFiles {
    override fun path(entry: CitadelFile, create: Boolean): Path {
        val segments = SetFolder.GameSegments + entry.path.parent?.value?.split('/').orEmpty()
        if (entry.path.movedOut) return gameFolder(entry.root, segments, create).resolve(entry.path.name)
        if (create) {
            val folder = entry.path.hideoutFolder ?: throw IOException("${entry.path.value} is not a name Hideout gives a file")
            return managedFolder(entry.root, folder, create = true).resolve(entry.path.name)
        }
        if (entry.path.offLimits) throw IOException("${entry.path.value} is in a folder Hideout leaves alone")
        return gameFolder(entry.root, segments).resolve(entry.path.name)
    }

    override fun emptied(entry: CitadelFile, file: Path) {
        val root = entry.path.parent?.let(ForeignRoot::of) ?: return
        if (root !in Emptied) return
        retryingBusy { Files.delete(file.parent) }
        if (root == ForeignRoot.OverflowProfileFolder) retryingBusy { Files.delete(file.parent.parent) }
    }

    override fun nameOf(entry: CitadelFile) = entry.path.value

    private companion object {
        val Emptied = setOf(ForeignRoot.Overflow, ForeignRoot.ProfileFolder, ForeignRoot.OverflowProfileFolder, ForeignRoot.Priority, ForeignRoot.Parking)
    }
}
