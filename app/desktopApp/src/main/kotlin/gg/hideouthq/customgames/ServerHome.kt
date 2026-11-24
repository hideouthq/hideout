package gg.hideouthq.customgames

import gg.hideouthq.core.customgames.Sha256
import gg.hideouthq.library.deleteTree
import gg.hideouthq.library.readSmallText
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
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
     * The server to run from [source], the unpacked host package [digest]: this home's copy of it, or the package's own
     * while the copy can't be made, as while a server left from an earlier session still runs from here.
     */
    fun take(source: Path, digest: Sha256): Path {
        val stamp = folder.resolve(StampFile)
        if (readSmallText(stamp) == digest.hex && program.isRegularFile()) return program
        return try {
            Files.deleteIfExists(stamp)
            deleteTree(folder)
            Files.walk(source).use { paths ->
                paths.forEach { Files.copy(it, folder.resolve(source.relativize(it).toString())) }
            }
            Files.writeString(stamp, digest.hex)
            program
        } catch (e: IOException) {
            log("Custom games: could not copy the game server: ${e::class.simpleName}")
            source.resolve(ServerProgram)
        }
    }
}

private const val StampFile = ".package"
