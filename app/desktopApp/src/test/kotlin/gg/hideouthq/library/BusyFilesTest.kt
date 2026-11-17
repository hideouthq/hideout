package gg.hideouthq.library

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.NoSuchFileException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class BusyFilesTest {
    private val slept = mutableListOf<Long>()
    private var attempts = 0

    private fun windows() = BusyFiles(windows = true, sleep = { slept += it })

    private fun failingWith(vararg failures: IOException): () -> String {
        val left = ArrayDeque(failures.toList())
        return {
            attempts++
            left.removeFirstOrNull()?.let { throw it }
            "moved"
        }
    }

    @Test
    fun onWindowsARenameOrDeleteIsTriedAgainWhileAnotherProgramHoldsTheFile() {
        val busy = arrayOf(
            AccessDeniedException("gameinfo.gi"),
            FileSystemException("gameinfo.gi", null, SharingViolation),
            FileSystemException("gameinfo.gi", "gameinfo.gi.hideout-displaced-t1", "Der Prozess kann nicht auf die Datei zugreifen, da ein anderer Prozess einen Teil der Datei gesperrt hat"),
        )
        assertEquals("moved", windows().retrying(failingWith(*busy)))
        assertEquals(4, attempts)
        assertEquals(listOf(10L, 20, 40), slept)
    }

    @Test
    fun aFileHeldForMoreThanAboutTwoSecondsFailsWithWhatTheLastTrySaw() {
        val last = FileSystemException("library.json", null, SharingViolation)
        val held = failingWith(*Array(9) { AccessDeniedException("library.json") }, last)
        assertSame(last, assertFailsWith<FileSystemException> { windows().retrying(held) })
        assertEquals(10, attempts)
        assertEquals(2_000L, slept.sum())
    }

    @Test
    fun onWindowsEveryFailureJavaGivesItsOwnClassIsThrownAtOnce() {
        val standing = listOf(
            NoSuchFileException("gameinfo.gi"),
            FileAlreadyExistsException("pak01_dir.vpk"),
            DirectoryNotEmptyException("hideout_off"),
            AtomicMoveNotSupportedException("library.json.tmp", "library.json", "not on one drive"),
            IOException("The network name cannot be found"),
        )
        for (failure in standing) {
            attempts = 0
            assertSame(failure, assertFailsWith<IOException> { windows().retrying(failingWith(failure)) })
            assertEquals(1, attempts, "${failure::class.simpleName}")
        }
        assertEquals(emptyList(), slept)
    }

    @Test
    fun elsewhereTheBlockRunsOnce() {
        val linux = BusyFiles(windows = false, sleep = { slept += it })
        val denied = AccessDeniedException("gameinfo.gi")
        assertSame(denied, assertFailsWith<AccessDeniedException> { linux.retrying(failingWith(denied)) })
        assertFailsWith<FileSystemException> { linux.retrying(failingWith(FileSystemException("gameinfo.gi", null, SharingViolation))) }
        assertEquals(2, attempts)
        assertEquals(emptyList(), slept)
        assertEquals("moved", linux.retrying(failingWith()))
    }

    private companion object {
        const val SharingViolation = "The process cannot access the file because it is being used by another process"
    }
}
