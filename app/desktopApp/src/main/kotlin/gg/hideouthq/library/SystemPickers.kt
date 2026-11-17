package gg.hideouthq.library

import gg.hideouthq.core.data.FilePicker
import gg.hideouthq.core.data.FolderPicker
import gg.hideouthq.core.data.LocalFile
import gg.hideouthq.data.openFileDialog
import gg.hideouthq.data.openFolderDialog
import java.io.File

object SystemFilePicker : FilePicker {
    override suspend fun pick(title: String): LocalFile? = openFileDialog(title, setOf("zip", "7z", "rar", "vpk"))?.asLocalFile()
}

object SystemFolderPicker : FolderPicker {
    override suspend fun pick(title: String, start: String?): String? = openFolderDialog(title, start?.let(::File))?.absolutePath
}

internal fun File.asLocalFile() = LocalFile(absolutePath, name, length())
