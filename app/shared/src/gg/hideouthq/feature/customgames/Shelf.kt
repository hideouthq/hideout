package gg.hideouthq.feature.customgames

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.hideouthq.core.customgames.CustomGames
import gg.hideouthq.core.designsystem.component.LinkButton
import gg.hideouthq.core.designsystem.component.SectionHeader
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.resources.*
import org.jetbrains.compose.resources.stringResource
import java.time.LocalDate

/** Shown only with the setting on, so a player who never turned it on makes no request. */
@Composable
fun CustomGamesShelf(games: CustomGames, onOpenMode: (String) -> Unit, onOpenAll: () -> Unit, modifier: Modifier = Modifier) {
    val modes by games.modes.collectAsStateWithLifecycle()
    val today = remember { LocalDate.now() }
    val (cards, total) = remember(modes, today) { shelf(modes, today) }
    LaunchedEffect(games) { games.refreshModes() }
    if (cards.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(HideoutDimens.MainPane.BlockGap)) {
        val lead = stringResource(Res.string.cg_shelf_title_lead)
        val em = stringResource(Res.string.cg_shelf_title_em)
        SectionHeader(
            title = lead + em,
            icon = HideoutIcons.Gamepad,
            accent = HideoutTheme.colors.sectionCustomGames,
            keyword = em,
            trailing = { LinkButton(if (total == 1) stringResource(Res.string.cg_shelf_open) else stringResource(Res.string.cg_shelf_all, total), onOpenAll) },
        )
        ModeGrid(cards, onOpenMode, Modifier.fillMaxWidth(), minCell = HideoutDimens.Card.MinColumnDiscover, maxColumns = 4)
    }
}
