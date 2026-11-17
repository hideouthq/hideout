package gg.hideouthq.feature.customgames

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.hideouthq.core.customgames.CustomGames
import gg.hideouthq.core.customgames.EndReason
import gg.hideouthq.core.customgames.GameRecord
import gg.hideouthq.core.customgames.LiveSession
import gg.hideouthq.core.customgames.Role
import gg.hideouthq.core.customgames.Session
import gg.hideouthq.core.customgames.SiteOrigin
import gg.hideouthq.core.navigation.Route

@Composable
fun rememberPresent(games: CustomGames, site: SiteOrigin?): Present {
    val live by games.live.collectAsStateWithLifecycle()
    val restarts by games.restartsDeadlock.collectAsStateWithLifecycle()
    val transfer by games.transfer.collectAsStateWithLifecycle()
    val modes by games.modes.collectAsStateWithLifecycle()
    return remember(live, restarts, transfer, modes, site) { present(PresentInputs(live, restarts, transfer, modes, games.firewallMayAsk, site)) }
}

fun sessionRoute(before: LiveSession, after: LiveSession): Route? {
    val game = after.game ?: return null
    val cancelled = game.role is Role.Host && !game.started && after.session == Session.Ended(EndReason.PlayerStopped) && before.session != after.session
    val hostBegan = game.role is Role.Host && !sameGame(before.game, game)
    val guestIn = game.role is Role.Guest && game.started && before.game?.started != true
    return when {
        cancelled -> Route.CustomGames
        hostBegan || guestIn -> Route.CustomGame
        else -> null
    }
}

private fun sameGame(before: GameRecord?, after: GameRecord): Boolean =
    before != null && before.role == after.role && (before.endedAt == null || before.endedAt == after.endedAt)

/** A quit the player has to confirm while a game runs. */
@Stable
class QuitGuard {
    var asking by mutableStateOf<QuitWarning?>(null)
        private set
    private var leave: (() -> Unit)? = null

    /** True when [warning] needs the player's word, and [leave] then runs on [confirm]. False lets the caller quit now. */
    fun ask(warning: QuitWarning?, leave: () -> Unit): Boolean {
        if (warning == null) return false
        this.leave = leave
        asking = warning
        return true
    }

    fun confirm() {
        val quit = leave
        cancel()
        quit?.invoke()
    }

    fun cancel() {
        asking = null
        leave = null
    }
}
