package gg.hideouthq.customgames

import gg.hideouthq.core.customgames.Sha256
import gg.hideouthq.library.deleteTree
import gg.hideouthq.library.readSmallText
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.isRegularFile

internal const val ServerProgram = "modlock-host.exe"

/** Beside the runtime folder in the app's data folder. */
internal const val ServerFolder = "customgames-server"

/**
 * The game server at one path, whichever runtime it comes from. Windows Firewall keys its rules and its question by the
 * program's path, so the answer the player gave once holds for every runtime the feed moves to.
 */
internal class ServerHome(private val folder: Path, private val log: (String) -> Unit) {
    val program: Path = folder.resolve(ServerProgram)

    /**
     * [program] holding the server from [source], the unpacked host package [digest], or null while it can't. The new
     * copy is made beside it and swapped in whole, and a server running from here keeps Windows from moving its folder,
     * so a failed copy leaves the server here as it was.
     */
    fun take(source: Path, digest: Sha256): Path? {
        if (readSmallText(folder.resolve(StampFile)) == digest.hex && program.isRegularFile()) return program
        val next = folder.resolveSibling("${folder.fileName}.next")
        val old = folder.resolveSibling("${folder.fileName}.old")
        return try {
            deleteTree(next)
            deleteTree(old)
            Files.walk(source).use { paths ->
                paths.forEach { Files.copy(it, next.resolve(source.relativize(it).toString())) }
            }
            Files.writeString(next.resolve(StampFile), digest.hex)
            if (Files.exists(folder)) Files.move(folder, old, StandardCopyOption.ATOMIC_MOVE)
            try {
                Files.move(next, folder, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: IOException) {
                if (Files.exists(old)) Files.move(old, folder, StandardCopyOption.ATOMIC_MOVE)
                throw e
            }
            program
        } catch (e: IOException) {
            log("Custom games: could not copy the game server: ${e::class.simpleName}")
            null
        } finally {
            quietly { deleteTree(next) }
            quietly { deleteTree(old) }
        }
    }

    private inline fun quietly(work: () -> Unit) = try {
        work()
    } catch (e: IOException) {
        Unit
    }
}

private const val StampFile = ".package"
