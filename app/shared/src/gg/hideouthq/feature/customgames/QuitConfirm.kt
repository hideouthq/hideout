package gg.hideouthq.feature.customgames

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.hideouthq.core.customgames.CustomGames
import gg.hideouthq.core.ui.WindowModal
import gg.hideouthq.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun QuitConfirm(guard: QuitGuard, games: CustomGames, modal: WindowModal) {
    val live by games.live.collectAsStateWithLifecycle()
    val keep = stringResource(Res.string.cg_keep_open)
    val words = when (guard.asking) {
        null -> null
        QuitWarning.EndsHostedGame -> ConfirmWords(
            stringResource(Res.string.cg_quit_host_title),
            stringResource(Res.string.cg_quit_host_body, live.game?.listing?.name ?: stringResource(Res.string.cg_unknown_mode)),
            keep, stringResource(Res.string.cg_quit_host_confirm),
        )
        QuitWarning.LeavesGame -> ConfirmWords(
            stringResource(Res.string.cg_quit_guest_title), stringResource(Res.string.cg_quit_guest_body), keep, stringResource(Res.string.cg_quit_guest_confirm),
        )
        QuitWarning.ModsComeBackLater -> ConfirmWords(
            stringResource(Res.string.cg_quit_cleanup_title), stringResource(Res.string.cg_quit_cleanup_body), keep, stringResource(Res.string.cg_quit_cleanup_confirm),
        )
    }
    ConfirmDialog(words, modal, guard::cancel, guard::confirm)
}
