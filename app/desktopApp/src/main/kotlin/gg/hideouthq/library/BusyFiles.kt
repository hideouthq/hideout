package gg.hideouthq.library

import gg.hideouthq.OnWindows
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException

/**
 * On Windows, Defender, the search indexer and Steam Cloud open files briefly, and a read, rename or delete meanwhile
 * fails with an [AccessDeniedException], or with a plain [FileSystemException], the class Java gives every Windows error
 * it has no class of its own for, a sharing or lock violation among them.
 */
internal class BusyFiles(private val windows: Boolean, private val sleep: (millis: Long) -> Unit = Thread::sleep) {
    fun <T> retrying(block: () -> T): T {
        for (pause in Pauses) {
            try {
                return block()
            } catch (e: FileSystemException) {
                if (!windows || e !is AccessDeniedException && e::class != FileSystemException::class) throw e
            }
            sleep(pause)
        }
        return block()
    }

    private companion object {
        val Pauses = listOf(10L, 20, 40, 80, 150, 300, 400, 500, 500)
    }
}

private val thisComputer = BusyFiles(windows = OnWindows)

internal fun <T> retryingBusy(block: () -> T): T = thisComputer.retrying(block)
