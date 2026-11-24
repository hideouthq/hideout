package gg.hideouthq.core.customgames

import gg.hideouthq.core.library.GameActivity
import gg.hideouthq.core.library.GamesLines
import gg.hideouthq.core.library.LibraryProblem
import kotlinx.serialization.Serializable

/** The loopback port a Deadlock's menus read their screen from. Fixed, and recorded in each pack's stamp. */
const val MenusScreenPort = 29700

/** What the helper serves to the Deadlock that will play: the screen port and bridge page its pack was built for. */
data class GameSetup(val screen: Int, val page: String)

const val InGamePort = 29701

/**
 * The pack in `menus/`, as `menus.json` records it. Two stamps are equal exactly when the pack needs no rebuild.
 * [inGame] is null for a pack holding only the renderer, the only kind releases before the in-game UI wrote.
 */
@Serializable
data class MenusStamp(val renderer: String, val page: String, val screen: Int, val revision: Long?, val inGame: InGameStamp? = null)

/**
 * [helper] is the digest of the `hideout-link` that built the pack, since that embeds the in-game UI. [layouts] are the
 * game's layouts the pack holds its own copies of, each with the player's pack it was copied from, null for the game's
 * own, whose changes [MenusStamp.revision] follows.
 */
@Serializable
data class InGameStamp(val endpoint: Int, val helper: String, val layouts: Map<String, LayoutSource?>)

/** A pack under `game/`, [pack] as `citadel/addons/pak02_dir.vpk`, and the CRC-32 its tree records for the layout. */
@Serializable
data class LayoutSource(val pack: String, val crc: Long)

/** [token] is null exactly when the pack has no in-game part, so nothing in the game can ask for one. */
data class MenusPack(val stamp: MenusStamp, val token: PackToken?)

@JvmInline
value class PackToken private constructor(val hex: String) {
    override fun toString() = "PackToken(redacted)"

    companion object {
        private val Shape = Regex("[0-9a-f]{32}")

        fun parse(text: String): PackToken? = if (Shape.matches(text)) PackToken(text) else null
    }
}

/** Why the Deadlock that runs now can't take a mode, which only a restart fixes. */
enum class RestartReason {
    /** No custom games lines, or no pack, when it started. */
    NotSetUp,

    /** Hideout wrote the lines and something else took them out, such as a Deadlock update. */
    GameUpdated,

    /** A session this run sent the game in and its menus never took it. */
    MenusDidNotStart,
}

sealed interface SetupAnswer {
    data class Ready(val setup: GameSetup) : SetupAnswer

    data class NeedsRestart(val why: RestartReason) : SetupAnswer

    data class Failed(val problem: Refusal) : SetupAnswer
}

/**
 * What a mode needs from this computer's Deadlock. A running Deadlock read `gameinfo.gi` and its pack as it started, and
 * Hideout writes neither while it runs, so [lines] and [menus] read now are what it has. With [client] closed the caller
 * has just written the lines, so only the pack can be missing. [menusBlockedBy] is a search path loaded ahead of the
 * menus that holds their renderer's files. It refuses the session, where a restart Hideout asked for could load it
 * again. One holding only the in-game UI's files doesn't, since that UI is all it costs.
 */
fun setupOf(client: GameActivity, lines: GamesLines, menus: MenusStamp?, renderer: Sha256?, menusFailedThisRun: Boolean, menusBlockedBy: String?): SetupAnswer = when (client) {
    GameActivity.Closed -> when {
        menus == null -> SetupAnswer.Failed(Refusal.MenusUnavailable)
        menusBlockedBy != null -> SetupAnswer.Failed(Refusal.MenusBlocked(menusBlockedBy))
        else -> SetupAnswer.Ready(GameSetup(menus.screen, menus.page))
    }
    GameActivity.Running -> when {
        menus == null && renderer == null -> SetupAnswer.Failed(Refusal.MenusUnavailable)
        lines == GamesLines.Removed -> SetupAnswer.NeedsRestart(RestartReason.GameUpdated)
        (lines != GamesLines.Mounted && lines != GamesLines.Leaving) || menus == null -> SetupAnswer.NeedsRestart(RestartReason.NotSetUp)
        menusBlockedBy != null -> SetupAnswer.Failed(Refusal.MenusBlocked(menusBlockedBy))
        menusFailedThisRun -> SetupAnswer.NeedsRestart(RestartReason.MenusDidNotStart)
        else -> SetupAnswer.Ready(GameSetup(menus.screen, menus.page))
    }
    GameActivity.Unknown, GameActivity.Unread -> SetupAnswer.Failed(Refusal.Library(LibraryProblem.ProcessesUnknown("Deadlock's process could not be read")))
}
