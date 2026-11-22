package gg.hideouthq.library

import gg.hideouthq.OnWindows
import gg.hideouthq.core.data.GameProcesses
import gg.hideouthq.core.data.ProcessScan
import gg.hideouthq.core.library.KnownProcess
import gg.hideouthq.core.library.knownProcess
import com.sun.jna.Native
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Kernel32Util
import com.sun.jna.platform.win32.Tlhelp32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinError
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Under Proton, argv[0] in `/proc/<pid>/cmdline` is the Windows path, `S:\common\Deadlock\game\bin\win64\deadlock.exe`.
 * On Windows a Toolhelp snapshot names every process without opening it, so a game Steam started as administrator is
 * seen too.
 */
class ProcessTable(
    private val source: ProcessSource = ProcessSource.forThisComputer(),
    private val end: (Long) -> Boolean = { pid -> ProcessHandle.of(pid).map(ProcessHandle::destroy).orElse(true) },
) : GameProcesses {
    override suspend fun endGame() = withContext(Dispatchers.IO) {
        val game = when (source) {
            is ProcessSource.Proc -> gameInProc(source.folder)
            is ProcessSource.Snapshot -> source.listed().filter { knownProcess(null, it.exeName) == KnownProcess.Game && source.alive(it.pid) }.map { it.pid }
            ProcessSource.Unsupported -> throw IOException("the process list of ${System.getProperty("os.name")} is not read")
        }
        for (pid in game) if (!end(pid)) throw IOException("process $pid would not end")
    }

    private fun gameInProc(proc: Path): List<Long> = Files.newDirectoryStream(proc).use { entries ->
        val buffer = ByteArray(MaxBytes)
        entries.mapNotNull { entry -> entry.fileName.toString().toLongOrNull()?.takeIf { observe(entry, buffer) == KnownProcess.Game } }
    }

    override suspend fun scan(): ProcessScan = withContext(Dispatchers.IO) {
        when (source) {
            is ProcessSource.Proc -> scanProc(source.folder)
            is ProcessSource.Snapshot -> scanSnapshot(source)
            ProcessSource.Unsupported -> ProcessScan.Unknown("the process list of ${System.getProperty("os.name")} is not read")
        }
    }

    private fun scanProc(proc: Path): ProcessScan {
        val pids = try {
            Files.newDirectoryStream(proc).use { entries -> entries.mapNotNull { it.fileName.toString().toLongOrNull() } }
        } catch (e: IOException) {
            return ProcessScan.Unknown(e.message ?: "$proc could not be read")
        }
        val buffer = ByteArray(MaxBytes)
        val known = pids.mapNotNullTo(mutableSetOf()) { pid -> observe(proc.resolve(pid.toString()), buffer) }
        return scanOf(known)
    }

    /** `stat` costs about as much as `cmdline` and `comm` together, so only a process those two name is asked whether it has exited. */
    private fun observe(folder: Path, buffer: ByteArray): KnownProcess? = try {
        val argv0 = read(folder.resolve("cmdline"), buffer).let { size -> buffer.decodeToString(0, (0 until size).firstOrNull { buffer[it] == 0.toByte() } ?: size) }
        val name = buffer.decodeToString(0, read(folder.resolve("comm"), buffer)).trimEnd('\n')
        knownProcess(argv0, name)?.takeUnless { zombie(folder, buffer) }
    } catch (_: IOException) {
        null
    }

    /** Whether `stat`'s state, the field after the name in parentheses, says the process has exited and waits to be reaped. */
    private fun zombie(folder: Path, buffer: ByteArray): Boolean {
        val stat = try {
            buffer.decodeToString(0, read(folder.resolve("stat"), buffer))
        } catch (_: IOException) {
            return false
        }
        return stat.substring(stat.lastIndexOf(')') + 1).trimStart().firstOrNull() in ExitedStates
    }

    private fun scanSnapshot(snapshot: ProcessSource.Snapshot): ProcessScan = try {
        scanOf(snapshot.listed().mapNotNull { listed -> knownProcess(null, listed.exeName)?.takeIf { it != KnownProcess.Game || snapshot.alive(listed.pid) } })
    } catch (e: IOException) {
        ProcessScan.Unknown(e.message ?: "the process list could not be read")
    }

    private fun scanOf(known: Collection<KnownProcess>): ProcessScan =
        ProcessScan.Seen(KnownProcess.Game in known, known.filterIsInstance<KnownProcess.OtherManager>().map { it.manager }.toSet())

    /** A scan reads hundreds of these, and Files.newInputStream opens each more slowly than FileInputStream. */
    private fun read(file: Path, buffer: ByteArray): Int = FileInputStream(file.toFile()).use { input ->
        var size = 0
        while (size < buffer.size) {
            val read = input.read(buffer, size, buffer.size - size)
            if (read < 0) break
            size += read
        }
        size
    }

    private companion object {
        const val MaxBytes = 64 * 1024
        val ExitedStates = setOf('Z', 'X', 'x')
    }
}

sealed interface ProcessSource {
    data class Proc(val folder: Path) : ProcessSource

    /** Windows' snapshot lists a process that has exited while something still holds it. */
    class Snapshot(val alive: (Long) -> Boolean = ::stillActive, val listed: () -> List<ListedProcess> = ::toolhelpProcesses) : ProcessSource

    data object Unsupported : ProcessSource

    companion object {
        fun forThisComputer(): ProcessSource {
            val os = System.getProperty("os.name")
            val proc = Path.of("/proc")
            return when {
                os.equals("Linux", ignoreCase = true) && Files.isDirectory(proc.resolve("self")) -> Proc(proc)
                OnWindows -> Snapshot()
                else -> Unsupported
            }
        }
    }
}

data class ListedProcess(val pid: Long, val exeName: String)

/** A process that cannot be opened, as a game run as administrator, is taken as running. */
private fun stillActive(pid: Long): Boolean {
    val kernel = Kernel32.INSTANCE
    val handle = kernel.OpenProcess(WinNT.PROCESS_QUERY_LIMITED_INFORMATION, false, pid.toInt()) ?: return true
    try {
        val code = IntByReference()
        return !kernel.GetExitCodeProcess(handle, code) || code.value == StillActive
    } finally {
        kernel.CloseHandle(handle)
    }
}

private const val StillActive = 259

private fun toolhelpProcesses(): List<ListedProcess> {
    val kernel = Kernel32.INSTANCE
    val snapshot = kernel.CreateToolhelp32Snapshot(Tlhelp32.TH32CS_SNAPPROCESS, WinDef.DWORD(0))
    if (snapshot == WinBase.INVALID_HANDLE_VALUE) throw IOException("the process list could not be read: ${Kernel32Util.formatMessage(Native.getLastError())}")
    try {
        val entry = Tlhelp32.PROCESSENTRY32()
        val listed = mutableListOf<ListedProcess>()
        var more = kernel.Process32First(snapshot, entry)
        while (more) {
            listed += ListedProcess(entry.th32ProcessID.toLong(), Native.toString(entry.szExeFile))
            more = kernel.Process32Next(snapshot, entry)
        }
        val ended = Native.getLastError()
        if (ended != WinError.ERROR_NO_MORE_FILES) throw IOException("the process list could not be read to its end: ${Kernel32Util.formatMessage(ended)}")
        return listed
    } finally {
        kernel.CloseHandle(snapshot)
    }
}
