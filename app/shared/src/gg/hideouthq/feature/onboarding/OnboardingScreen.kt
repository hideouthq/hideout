package gg.hideouthq.feature.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.designsystem.component.AppWindow
import gg.hideouthq.core.designsystem.component.ArtPanel
import gg.hideouthq.core.designsystem.component.ArtScene
import gg.hideouthq.core.designsystem.component.ButtonSize
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.ScrollColumn
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.feature.imports.ImportActions
import gg.hideouthq.feature.imports.ImportContent
import gg.hideouthq.feature.imports.ImportPreviewPanel
import gg.hideouthq.feature.imports.fromLine
import gg.hideouthq.feature.imports.importHeading
import gg.hideouthq.feature.imports.importLabel
import gg.hideouthq.feature.settings.components.PathBox
import gg.hideouthq.feature.settings.components.folderMessage
import gg.hideouthq.feature.shell.FrameWindowControls
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.app_name
import gg.hideouthq.resources.hideout_logo
import gg.hideouthq.resources.onboarding_game_missing_lead
import gg.hideouthq.resources.onboarding_game_missing_title
import gg.hideouthq.resources.onboarding_skip_setup
import gg.hideouthq.resources.onboarding_start_fresh
import gg.hideouthq.resources.settings_game_choose
import gg.hideouthq.resources.settings_game_picker_title
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun OnboardingScreen(state: OnboardingUiState, actions: OnboardingActions, imports: ImportActions, modifier: Modifier = Modifier) {
    val step = state.step
    AppWindow(modifier) {
        if (step == null) return@AppWindow
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(PanelGap)) {
            Column(
                Modifier.width(SetupWidth).fillMaxHeight().background(MaterialTheme.colorScheme.surface, PanelShape)
                    .padding(start = SidePadding, end = SidePadding, top = TopPadding, bottom = BottomPadding),
            ) {
                Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(Res.drawable.hideout_logo), contentDescription = null, modifier = Modifier.size(28.dp))
                    Text(stringResource(Res.string.app_name), style = HideoutTheme.typography.titleBrand, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                }
                ScrollColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(top = 44.dp, bottom = 8.dp)) {
                    when (step) {
                        SetupStep.Game -> {
                            StepHeading(null, stringResource(Res.string.onboarding_game_missing_title), stringResource(Res.string.onboarding_game_missing_lead))
                            state.game.rejected?.let { rejected ->
                                Column(Modifier.padding(top = StackTop), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    PathBox(rejected.path, error = true)
                                    Text(folderMessage(rejected.problem, state.game.separator), style = HideoutTheme.typography.notice, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        SetupStep.Mods -> {
                            val (title, lead) = importHeading(state.mods)
                            StepHeading(state.mods.from?.let { fromLine(it) }, title, lead)
                            ImportContent(state.mods, imports, Modifier.padding(top = StackTop))
                        }
                    }
                }
                Footer(state, actions, imports)
            }
            ArtPanel(ArtScene.Skyline, Modifier.weight(1f).fillMaxHeight()) {
                if (step == SetupStep.Mods) PreviewStage { ImportPreviewPanel(state.mods) }
                FrameWindowControls(Modifier.align(Alignment.TopEnd).padding(12.dp))
            }
        }
    }
}

@Composable
private fun PreviewStage(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val scale = minOf(1f, maxWidth / PreviewWidth, maxHeight / PreviewHeight)
        Box(
            Modifier.requiredSize(maxOf(maxWidth, PreviewWidth), maxOf(maxHeight, PreviewHeight)).graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        ) { content() }
    }
}

@Composable
private fun StepHeading(eyebrow: String?, title: String, lead: String) {
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    if (eyebrow != null) Text(eyebrow, style = type.labelBadge, color = scheme.outline, maxLines = 1, modifier = Modifier.padding(bottom = 8.dp))
    Text(title, style = type.displayTitle, color = scheme.onSurface, modifier = Modifier.semantics { heading() })
    Text(lead, style = type.bodyLead, color = scheme.onSurfaceVariant, modifier = Modifier.widthIn(max = 500.dp).padding(top = 12.dp))
}

@Composable
private fun Footer(state: OnboardingUiState, actions: OnboardingActions, imports: ImportActions) {
    val pickerTitle = stringResource(Res.string.settings_game_picker_title)
    Row(Modifier.fillMaxWidth().padding(top = 18.dp).heightIn(min = FooterHeight), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        val skip = stringResource(if (state.step == SetupStep.Game) Res.string.onboarding_skip_setup else Res.string.onboarding_start_fresh)
        HideoutButton(skip, actions::skip, Modifier.height(40.dp), variant = ButtonVariant.Text, size = ButtonSize.Small, contentPadding = PaddingValues(horizontal = 14.dp))
        Spacer(Modifier.weight(1f))
        when {
            state.step == SetupStep.Game -> HideoutButton(
                stringResource(Res.string.settings_game_choose), { actions.chooseFolder(pickerTitle) }, size = ButtonSize.Large, enabled = !state.game.busy, contentPadding = ButtonPadding,
            )
            !state.mods.listing -> HideoutButton(importLabel(state.mods), imports::startImport, size = ButtonSize.Large, enabled = state.mods.canImport, contentPadding = ButtonPadding)
        }
    }
}

private val StackTop = 26.dp
private val SetupWidth = 620.dp
private val PanelGap = 8.dp
private val SidePadding = 44.dp
private val TopPadding = 26.dp
private val BottomPadding = 28.dp
private val PanelShape = RoundedCornerShape(20.dp)
private val FooterHeight = 48.dp
private val ButtonPadding = PaddingValues(horizontal = 24.dp)
private val PreviewWidth = 796.dp
private val PreviewHeight = 884.dp
