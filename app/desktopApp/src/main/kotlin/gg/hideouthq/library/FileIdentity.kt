package gg.hideouthq.library

import gg.hideouthq.OnWindows
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** Which file [file] names: the key Java gives on Linux, and on Windows, where Java gives none, its volume and file id. Null where neither is known. */
internal fun fileIdentity(file: Path, attributes: BasicFileAttributes): Any? =
    attributes.fileKey() ?: if (OnWindows) WindowsFileIds?.of(file) else null

/** A file's id as FILE_ID_INFO gives it: the volume's serial number, and NTFS's 64-bit file index or ReFS's 128-bit id. */
private data class WindowsFileId(val volume: Long, val low: Long, val high: Long)

private val WindowsFileIds: FileIdQuery? by lazy {
    try {
        FileIdQuery(NativeLibrary.getInstance("kernel32"))
    } catch (_: Throwable) {
        null
    }
}

/**
 * Opens the name with no access to its bytes and sharing everything, so no other program is kept from the file, and
 * asks a link about itself rather than its target. A name that will not open so, such as one past MAX_PATH, has no id.
 */
private class FileIdQuery(kernel: NativeLibrary) {
    private val createFile = kernel.getFunction("CreateFileW")
    private val fileInformation = kernel.getFunction("GetFileInformationByHandleEx")
    private val closeHandle = kernel.getFunction("CloseHandle")

    fun of(file: Path): WindowsFileId? = try {
        val name = file.toAbsolutePath().toString()
        val handle = Memory((name.length + 1) * 2L).use { wide ->
            wide.setWideString(0, name)
            createFile.invokePointer(arrayOf(wide, ReadAttributes, ShareAll, null, OpenExisting, BackupSemantics or OpenReparsePoint, null))
        }
        if (handle == null || Pointer.nativeValue(handle) == InvalidHandle) {
            null
        } else {
            try {
                Memory(IdInfoBytes.toLong()).use { info ->
                    if (fileInformation.invokeInt(arrayOf(handle, FileIdInfo, info, IdInfoBytes)) == 0) null
                    else WindowsFileId(info.getLong(0), info.getLong(8), info.getLong(16)).takeIf { it.low != 0L || it.high != 0L }
                }
            } finally {
                closeHandle.invokeInt(arrayOf(handle))
            }
        }
    } catch (_: Throwable) {
        null
    }

    private companion object {
        const val ReadAttributes = 0x80
        const val ShareAll = 0x7
        const val OpenExisting = 3
        const val BackupSemantics = 0x02000000
        const val OpenReparsePoint = 0x00200000
        const val InvalidHandle = -1L
        const val FileIdInfo = 18
        const val IdInfoBytes = 24
    }
}
