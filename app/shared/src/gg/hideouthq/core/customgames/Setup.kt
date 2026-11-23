package gg.hideouthq.core.customgames

import gg.hideouthq.core.library.GameActivity
import gg.hideouthq.core.library.GamesLines
import gg.hideouthq.core.library.LibraryProblem
import kotlinx.serialization.Serializable

/** The loopback port a Deadlock's menus read their screen from. Fixed, and recorded in each pack's stamp. */
const val MenusScreenPort = 29700

/** What the helper serves to the Deadlock that will play: the screen port and bridge page its pack was built for. */
data class GameSetup(val screen: Int, val page: String)

/** The loopback port the app serves the in-game UI on. Fixed, and recorded in each pack's stamp. */
const val InGamePort = 29701

/**
 * The pack in `menus/`, as `menus.json` records it. Two stamps are equal exactly when the pack needs no rebuild.
 * [helper] is the SHA-256 of the `hideout-link` that built it, which embeds the in-game UI, so a new UI rebuilds the pack.
 * A `menus.json` from before [endpoint] and [helper] reads as no stamp, and the next close of Deadlock rebuilds.
 */
@Serializable
data class MenusStamp(val renderer: String, val page: String, val screen: Int, val revision: Long?, val endpoint: Int, val helper: String)

/** What `menus.json` holds: the stamp, and the token the helper baked into this pack. */
data class MenusPack(val stamp: MenusStamp, val token: PackToken)

/**
 * 32 lowercase hex characters, drawn by the helper for each pack. An image request from the game carries no Origin, so
 * the in-game endpoint takes one only with the running pack's token, which a web page on the desktop can't know.
 */
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
 * Whether [resource], a path as a VPK's tree lists it, is one of the files Hideout's menus pack holds: the news layout it
 * starts from, or Modlock's layouts and scripts. A pack Deadlock reads first that holds one takes the menus' place.
 */
fun isMenusFile(resource: String): Boolean =
    resource == "panorama/layout/latest_news.vxml_c" || resource.startsWith("panorama/scripts/modlock/") || resource.startsWith("panorama/layout/modlock/")

/**
 * What a mode needs from this computer's Deadlock. A running Deadlock read `gameinfo.gi` and its pack as it started, and
 * Hideout writes neither while it runs, so [lines] and [menus] read now are what it has. With [client] closed the caller
 * has just written the lines, so only the pack can be missing. [menusBlockedBy] is a search path loaded ahead of the
 * menus that holds their files. It refuses the session, where a restart Hideout asked for could load it again.
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
