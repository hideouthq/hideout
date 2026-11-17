package gg.hideouthq.feature.analytics

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.analytics.UsageAnalytics
import gg.hideouthq.core.designsystem.component.AppWindow
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.SwitchRow
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.feature.shell.FrameWindowControls
import gg.hideouthq.resources.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
fun AnalyticsDisclosure(analytics: UsageAnalytics) {
    var enabled by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AppWindow {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 56.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(stringResource(Res.string.analytics_heading), style = HideoutTheme.typography.headlinePage)
                Text(stringResource(Res.string.analytics_description), style = HideoutTheme.typography.body)
                Text(stringResource(Res.string.analytics_exclusions), style = HideoutTheme.typography.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SwitchRow(stringResource(Res.string.analytics_title), enabled, { if (!saving) enabled = it }, Modifier.testTag("analytics-choice"))
                if (failed) Text(stringResource(Res.string.analytics_save_failed), color = MaterialTheme.colorScheme.error)
                HideoutButton(text = stringResource(Res.string.analytics_continue), onClick = {
                    saving = true
                    scope.launch {
                        try { analytics.choose(enabled) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { failed = true }
                        finally { saving = false }
                    }
                }, enabled = !saving, modifier = Modifier.testTag("analytics-continue"))
            }
        }
        FrameWindowControls(Modifier.align(Alignment.TopEnd).padding(12.dp))
    }
}
