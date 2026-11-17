package gg.hideouthq.feature.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import gg.hideouthq.core.designsystem.component.ButtonSize
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.interaction.insetRing
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutRadius
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.library.FolderProblem
import gg.hideouthq.core.library.FoundVia
import gg.hideouthq.core.library.GameStatus
import gg.hideouthq.feature.settings.SettingsUiState
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.folder_problem_not_deadlock
import gg.hideouthq.resources.folder_problem_unavailable
import gg.hideouthq.resources.settings_game_change_failed
import gg.hideouthq.resources.settings_game_choose
import gg.hideouthq.resources.settings_game_choose_another
import gg.hideouthq.resources.settings_game_detect
import gg.hideouthq.resources.settings_game_found_chosen
import gg.hideouthq.resources.settings_game_found_steam
import gg.hideouthq.resources.settings_game_not_found
import gg.hideouthq.resources.settings_game_not_found_title
import gg.hideouthq.resources.settings_game_ready
import gg.hideouthq.resources.settings_game_searching
import gg.hideouthq.resources.settings_game_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DeadlockCard(state: SettingsUiState, onChooseFolder: () -> Unit, onDetectAgain: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val game = state.game
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(scheme.surfaceContainer, RoundedCornerShape(HideoutRadius.SettingsGroupEdge))
            .padding(HideoutDimens.Settings.CardPadding),
        verticalArrangement = Arrangement.spacedBy(HideoutDimens.Settings.CardGap),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Settings.CardGap), verticalAlignment = Alignment.CenterVertically) {
            val (icon, fill, tint) = gameTile(game)
            SettingTile(icon, fill, tint)
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Settings.TitleGap), verticalAlignment = Alignment.CenterVertically) {
                    val title = if (game is GameStatus.Missing) Res.string.settings_game_not_found_title else Res.string.settings_game_title
                    Text(stringResource(title), style = HideoutTheme.typography.titleSetting, color = scheme.onSurface)
                    if (game is GameStatus.Ready) StatusChip(stringResource(Res.string.settings_game_ready))
                }
                val description = when (game) {
                    GameStatus.Searching -> Res.string.settings_game_searching
                    is GameStatus.Missing -> Res.string.settings_game_not_found
                    is GameStatus.Ready -> if (game.via == FoundVia.Steam) Res.string.settings_game_found_steam else Res.string.settings_game_found_chosen
                }
                Text(
                    text = stringResource(description),
                    style = HideoutTheme.typography.notice,
                    color = scheme.outline,
                    modifier = Modifier.padding(top = HideoutDimens.Settings.DescriptionTopPadding),
                )
            }
        }
        val rejected = state.rejected
        val shownPath = rejected?.path ?: (game as? GameStatus.Ready)?.root?.path
        if (shownPath != null) PathBox(shownPath, error = rejected != null)
        if (rejected != null) NoteMessage(folderMessage(rejected.problem, state.separator))
        if (state.failed) NoteMessage(AnnotatedString(stringResource(Res.string.settings_game_change_failed)))
        Row(horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Settings.ButtonGap)) {
            val padding = PaddingValues(horizontal = HideoutDimens.Settings.ButtonHorizontalPadding)
            HideoutButton(
                text = stringResource(if (game is GameStatus.Ready) Res.string.settings_game_choose_another else Res.string.settings_game_choose),
                onClick = onChooseFolder,
                variant = if (game is GameStatus.Ready) ButtonVariant.Tonal else ButtonVariant.Primary,
                size = ButtonSize.Small,
                leadingIcon = HideoutIcons.Folder,
                leadingIconSize = HideoutDimens.Icon.Small,
                enabled = !state.busy && game != GameStatus.Searching,
                contentPadding = padding,
            )
            if ((game as? GameStatus.Ready)?.via != FoundVia.Steam) HideoutButton(
                text = stringResource(Res.string.settings_game_detect),
                onClick = onDetectAgain,
                variant = ButtonVariant.Text,
                size = ButtonSize.Small,
                leadingIcon = HideoutIcons.Refresh,
                leadingIconSize = HideoutDimens.Icon.Small,
                enabled = !state.busy && game != GameStatus.Searching,
                contentPadding = padding,
            )
        }
    }
}

@Composable
internal fun gameTile(game: GameStatus): Triple<ImageVector, Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (game) {
        is GameStatus.Ready -> Triple(HideoutIcons.Monitor, scheme.secondaryContainer, scheme.secondary)
        is GameStatus.Missing -> Triple(HideoutIcons.Warning, scheme.tertiaryContainer, scheme.tertiary)
        GameStatus.Searching -> Triple(HideoutIcons.Monitor, scheme.surfaceContainerHigh, scheme.onSurface)
    }
}

@Composable
internal fun PathBox(path: String, error: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val shape = HideoutShapes.Row
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(HideoutDimens.Settings.PathHeight)
            .background(scheme.surface, shape)
            .then(if (error) Modifier.insetRing(shape, scheme.error) { HideoutDimens.Settings.PathErrorOutline.toPx() } else Modifier)
            .padding(horizontal = HideoutDimens.Settings.PathHorizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Settings.PathGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HideoutIcon(HideoutIcons.Folder, contentDescription = null, size = HideoutDimens.Settings.PathIcon, tint = scheme.outline)
        Text(path, style = HideoutTheme.typography.monoPath, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
    }
}

@Composable
internal fun folderMessage(problem: FolderProblem, separator: Char): AnnotatedString = when (problem) {
    FolderProblem.NotDeadlock -> withMonoArguments(Res.string.folder_problem_not_deadlock, listOf("steamapps", "common", "Deadlock").joinToString(separator.toString()))
    FolderProblem.Unavailable -> AnnotatedString(stringResource(Res.string.folder_problem_unavailable))
}

@Composable
private fun withMonoArguments(resource: StringResource, vararg arguments: String): AnnotatedString {
    val template = stringResource(resource)
    val mono = SpanStyle(fontFamily = HideoutTheme.typography.mono.fontFamily, fontSize = MonoInlineSize)
    return buildAnnotatedString {
        var at = 0
        for (match in Placeholder.findAll(template)) {
            append(template.substring(at, match.range.first))
            withStyle(mono) { append(arguments[match.groupValues[1].toInt() - 1]) }
            at = match.range.last + 1
        }
        append(template.substring(at))
    }
}

private val Placeholder = Regex("%(\\d+)\\\$s")
private val MonoInlineSize = 12.sp
