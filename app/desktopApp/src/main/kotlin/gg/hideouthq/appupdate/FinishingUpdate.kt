package gg.hideouthq.appupdate

import gg.hideouthq.core.customgames.ProcessIdentity
import gg.hideouthq.customgames.identityOf
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The MSI helper that is finishing an update, as [MsiInstaller] names it in [folder] before Hideout exits. A Hideout
 * opened while that helper runs asks it to reopen Hideout once the MSI is done, and leaves at once: waiting would keep
 * its own executable open, and Windows Installer replaces a file in use only at the next reboot.
 */
internal class FinishingUpdate(folder: Path) {
    private val helperFile = folder.resolve("finish-update.helper")
    private val reopenFile = folder.resolve("finish-update.reopen")

    fun announce(helper: ProcessIdentity) {
        delete(reopenFile)
        write(helperFile, "${helper.pid} ${helper.startedAtMillis}")
    }

    /**
     * True when the announced helper still runs, has been asked to open Hideout with [link] when it's done, and this
     * copy should exit. A helper gone, or started more than [HandOffLimitMillis] ago, is forgotten and this copy opens.
     */
    fun handOff(link: String?, running: (ProcessIdentity) -> Boolean, nowMillis: Long): Boolean {
        val helper = helper() ?: return false
        if (nowMillis - helper.startedAtMillis > HandOffLimitMillis || !running(helper)) {
            delete(helperFile)
            delete(reopenFile)
            return false
        }
        if (!write(reopenFile, link.orEmpty())) return false
        // The helper forgets its announcement before it reads the request, so one still here means the request will be read.
        if (helper() == helper) return true
        delete(reopenFile)
        return false
    }

    /** The helper's last step: forgets the announcement, then returns what a Hideout opened meanwhile asked for, "" for no link. */
    fun close(): String? {
        delete(helperFile)
        val reopen = try {
            Files.readString(reopenFile)
        } catch (e: IOException) {
            null
        }
        delete(reopenFile)
        return reopen
    }

    private fun helper(): ProcessIdentity? = try {
        val (pid, started) = Files.readString(helperFile).trim().split(" ").takeIf { it.size == 2 } ?: return null
        ProcessIdentity(pid.toLongOrNull() ?: return null, started.toLongOrNull() ?: return null)
    } catch (e: IOException) {
        null
    }

    private fun write(file: Path, text: String): Boolean = try {
        Files.writeString(file, text)
        true
    } catch (e: IOException) {
        false
    }

    private fun delete(file: Path) {
        try {
            Files.deleteIfExists(file)
        } catch (e: IOException) {
        }
    }

    companion object {
        /** Longer than the helper's own waits: two minutes for Hideout to exit, and 100 seconds while another install runs. */
        const val HandOffLimitMillis = 5 * 60_000L
    }
}

internal fun isRunning(process: ProcessIdentity): Boolean = ProcessHandle.of(process.pid).map(::identityOf).orElse(null) == process
