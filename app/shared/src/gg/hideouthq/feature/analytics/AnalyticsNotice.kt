package gg.hideouthq.feature.analytics

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.analytics.UsageAnalytics
import gg.hideouthq.core.designsystem.component.ButtonSize
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.HideoutIconButton
import gg.hideouthq.core.designsystem.component.HideoutSwitch
import gg.hideouthq.core.designsystem.component.TopBarChip
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.resources.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun AnalyticsNotice(analytics: UsageAnalytics, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    var enabled by remember(analytics) { mutableStateOf(true) }
    var details by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    val dimens = HideoutDimens.TopBarChip
    fun save() {
        if (saving) return
        saving = true
        failed = false
        scope.launch {
            try { analytics.choose(enabled) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed = true }
            finally { saving = false }
        }
    }
    TopBarChip(
        label = stringResource(Res.string.analytics_chip),
        expanded = expanded,
        onToggle = { if (expanded) save() else onToggle() },
        panelWidth = dimens.UpdatePanelWidth,
        modifier = modifier,
        contentPadding = PaddingValues(start = dimens.UpdateStart, end = dimens.UpdateEnd),
        gap = dimens.UpdateGap,
        panel = { first ->
            Column(Modifier.testTag("analytics-notice"), verticalArrangement = Arrangement.spacedBy(dimens.PanelGapInside)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Image(painterResource(Res.drawable.analytics_helper), null, Modifier.size(64.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(Res.string.analytics_heading), style = HideoutTheme.typography.titleUpdateCard)
                        Text(stringResource(Res.string.analytics_notice_description), style = HideoutTheme.typography.caption, color = scheme.onSurfaceVariant)
                    }
                    HideoutIconButton(
                        HideoutIcons.Close, stringResource(Res.string.analytics_close), onClick = ::save,
                        modifier = Modifier.align(Alignment.Top), enabled = !saving, iconSize = 16.dp,
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.analytics_notice_toggle), Modifier.weight(1f), style = HideoutTheme.typography.labelNav)
                    HideoutSwitch(enabled, { enabled = it }, stringResource(Res.string.analytics_notice_toggle), Modifier.focusRequester(first).testTag("analytics-choice"), enabled = !saving)
                }
                if (details) Column(
                    Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(stringResource(Res.string.analytics_description), style = HideoutTheme.typography.caption, color = scheme.onSurfaceVariant)
                    Text(stringResource(Res.string.analytics_exclusions), style = HideoutTheme.typography.caption, color = scheme.onSurfaceVariant)
                }
                if (failed) Text(stringResource(Res.string.analytics_save_failed), style = HideoutTheme.typography.caption, color = scheme.error)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(Res.string.analytics_notice_hint), Modifier.weight(1f), style = HideoutTheme.typography.caption, color = scheme.outline)
                    HideoutButton(
                        stringResource(if (details) Res.string.analytics_less else Res.string.analytics_details), { details = !details },
                        variant = ButtonVariant.Text, size = ButtonSize.Small, contentPadding = PaddingValues(horizontal = 8.dp),
                    )
                }
            }
        },
    ) {
        Image(painterResource(Res.drawable.analytics_helper), null, Modifier.size(dimens.IconSlot))
        Text(stringResource(Res.string.analytics_chip), style = HideoutTheme.typography.labelNav, color = scheme.onSurface, maxLines = 1)
    }
}
