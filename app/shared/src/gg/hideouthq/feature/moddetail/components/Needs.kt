package gg.hideouthq.feature.moddetail.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import gg.hideouthq.core.data.InstallStatus
import gg.hideouthq.core.designsystem.component.ButtonSize
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.component.InstallButtonState
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.hideoutClickable
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.ui.ModLabels
import gg.hideouthq.feature.moddetail.Need
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.detail_needs
import gg.hideouthq.resources.install
import gg.hideouthq.resources.need_install_named
import gg.hideouthq.resources.need_not_installed
import gg.hideouthq.resources.need_off
import gg.hideouthq.resources.need_on
import org.jetbrains.compose.resources.stringResource
import gg.hideouthq.core.designsystem.theme.HideoutDimens.Changelog as WhatsNew

@Composable
internal fun Needs(needs: List<Need>, canInstall: Boolean, onOpen: (Need) -> Unit, onInstall: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(WhatsNew.HeaderGap)) {
        SectionLabel(stringResource(Res.string.detail_needs))
        needs.forEach { need -> NeedRow(need, canInstall, onOpen, onInstall) }
    }
}

@Composable
private fun NeedRow(need: Need, canInstall: Boolean, onOpen: (Need) -> Unit, onInstall: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val shape = RoundedCornerShape(WhatsNew.RowRadius)
    val opens = need !is Need.Named
    val status = (need as? Need.Listed)?.status
    val installed = status as? InstallStatus.Installed
    val labels = status?.let { ModLabels.install(it) }
    val caption = when (need) {
        is Need.Listed -> when {
            installed != null -> stringResource(if (installed.enabled) Res.string.need_on else Res.string.need_off)
            ModLabels.installButton(need.status) is InstallButtonState.Downloading -> labels?.downloading
            else -> stringResource(Res.string.need_not_installed)
        }
        is Need.Elsewhere -> need.url.substringAfter("://").substringBefore('/').removePrefix("www.")
        is Need.Named -> null
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (opens && states.hovered) scheme.surfaceContainerHigh else scheme.surfaceContainer, shape)
            .then(if (opens) Modifier.focusRing(states.focusVisible, shape).hideoutClickable(source, role = Role.Button) { onOpen(need) } else Modifier)
            .padding(start = WhatsNew.RowStart, top = WhatsNew.RowPadding, end = WhatsNew.RowPadding, bottom = WhatsNew.RowPadding),
        horizontalArrangement = Arrangement.spacedBy(WhatsNew.RowGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(HideoutDimens.Fact.IconTile).background(scheme.surfaceContainerHigh, HideoutShapes.Tile), contentAlignment = Alignment.Center) {
            HideoutIcon(
                icon = when {
                    installed != null -> HideoutIcons.Check
                    need is Need.Listed -> HideoutIcons.Download
                    need is Need.Elsewhere -> HideoutIcons.Globe
                    else -> HideoutIcons.Box
                },
                contentDescription = null,
                tint = if (installed?.enabled == true) scheme.secondary else scheme.onSurface,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(need.name, style = HideoutTheme.typography.factValue, color = scheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            caption?.let { Text(it, style = HideoutTheme.typography.caption, color = scheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        when {
            need is Need.Listed && (need.status == InstallStatus.NotInstalled || need.status is InstallStatus.Failed) -> {
                val named = stringResource(Res.string.need_install_named, need.name)
                HideoutButton(
                    stringResource(Res.string.install), { onInstall(need.modId) },
                    modifier = Modifier.semantics { contentDescription = named }, variant = ButtonVariant.Tonal, size = ButtonSize.Small, enabled = canInstall,
                )
            }
            need is Need.Elsewhere -> HideoutIcon(HideoutIcons.OpenExternal, contentDescription = null, size = WhatsNew.Chevron, tint = scheme.outline)
            opens -> HideoutIcon(HideoutIcons.ChevronRight, contentDescription = null, size = WhatsNew.Chevron, tint = scheme.outline)
        }
    }
}
