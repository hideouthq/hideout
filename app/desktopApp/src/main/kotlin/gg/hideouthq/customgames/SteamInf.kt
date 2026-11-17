package gg.hideouthq.customgames

import gg.hideouthq.core.customgames.GameRevision
import gg.hideouthq.core.library.DeadlockManifestName
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.library.readSmallText
import java.nio.file.Path

internal object SteamInf {
    private val Revision = Regex("""^SourceRevision=(\d+)\s*$""", RegexOption.MULTILINE)

    fun revision(game: GameRoot): GameRevision? {
        val text = readSmallText(Path.of(game.path, "game", "citadel", "steam.inf")) ?: return null
        return Revision.find(text)?.groupValues?.get(1)?.toLongOrNull()?.let(::GameRevision)
    }
}

internal object SteamManifest {
    private const val FullyInstalled = 4L
    private val Flags = Regex(""""StateFlags"\s+"(\d+)"""")

    fun updatePending(game: GameRoot): Boolean {
        val manifest = Path.of(game.path).parent?.parent?.resolve(DeadlockManifestName) ?: return false
        val flags = readSmallText(manifest)?.let(Flags::find)?.groupValues?.get(1)?.toLongOrNull() ?: return false
        return flags != FullyInstalled
    }
}

