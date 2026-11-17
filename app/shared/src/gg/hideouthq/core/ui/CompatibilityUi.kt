package gg.hideouthq.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.designsystem.component.ButtonSize
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.ChipSize
import gg.hideouthq.core.designsystem.component.ChipTone
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.StatusChip
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.library.CompatLabel
import gg.hideouthq.core.library.CompatStatus
import gg.hideouthq.core.library.FindingOutcome
import gg.hideouthq.core.library.InstallCompatibility
import gg.hideouthq.core.library.RepairChoice
import gg.hideouthq.core.library.RepairWork
import gg.hideouthq.core.library.RuleFindings
import gg.hideouthq.core.library.RuleIdentity
import gg.hideouthq.core.library.incomplete
import gg.hideouthq.core.library.outcomes
import gg.hideouthq.core.library.status
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.compat_apply
import gg.hideouthq.resources.compat_check_again
import gg.hideouthq.resources.compat_check_failed
import gg.hideouthq.resources.compat_checking
import gg.hideouthq.resources.compat_chip_description
import gg.hideouthq.resources.compat_chose_original
import gg.hideouthq.resources.compat_close
import gg.hideouthq.resources.compat_details_title
import gg.hideouthq.resources.compat_failed
import gg.hideouthq.resources.compat_feature_frame
import gg.hideouthq.resources.compat_feature_other
import gg.hideouthq.resources.compat_feature_sound
import gg.hideouthq.resources.compat_finding_edits
import gg.hideouthq.resources.compat_finding_resource
import gg.hideouthq.resources.compat_fixed_heading
import gg.hideouthq.resources.compat_incomplete
import gg.hideouthq.resources.compat_label_needs_author
import gg.hideouthq.resources.compat_label_partly_repaired
import gg.hideouthq.resources.compat_label_repair_applied
import gg.hideouthq.resources.compat_label_repair_available
import gg.hideouthq.resources.compat_left_unchanged_heading
import gg.hideouthq.resources.compat_no_known_problem
import gg.hideouthq.resources.compat_not_checked
import gg.hideouthq.resources.compat_off_note
import gg.hideouthq.resources.compat_original_waits
import gg.hideouthq.resources.compat_outdated
import gg.hideouthq.resources.compat_outdated_heading
import gg.hideouthq.resources.compat_qualification
import gg.hideouthq.resources.compat_queued
import gg.hideouthq.resources.compat_reason
import gg.hideouthq.resources.compat_repairable_heading
import gg.hideouthq.resources.compat_repairing
import gg.hideouthq.resources.compat_requested_waits
import gg.hideouthq.resources.compat_restoring
import gg.hideouthq.resources.compat_status_queued
import gg.hideouthq.resources.compat_status_repair_unfinished
import gg.hideouthq.resources.compat_status_restore_unfinished
import gg.hideouthq.resources.compat_status_restoring
import gg.hideouthq.resources.compat_status_verifying
import gg.hideouthq.resources.compat_status_waiting
import gg.hideouthq.resources.compat_unavailable
import gg.hideouthq.resources.compat_unknown_heading
import gg.hideouthq.resources.compat_use_original
import gg.hideouthq.resources.compat_verifying
import gg.hideouthq.resources.compat_verifying_original
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
private fun compatStatusText(status: CompatStatus): String = stringResource(
    when (status) {
        is CompatStatus.Changing -> when (val work = status.work) {
            is RepairWork.Queued -> Res.string.compat_status_queued
            RepairWork.Repairing -> Res.string.compat_repairing
            RepairWork.Restoring -> Res.string.compat_status_restoring
            is RepairWork.Verifying -> Res.string.compat_status_verifying
            is RepairWork.WaitingForGame -> Res.string.compat_status_waiting
        }
        is CompatStatus.Unfinished -> if (status.restoring) Res.string.compat_status_restore_unfinished else Res.string.compat_status_repair_unfinished
        is CompatStatus.Found -> when (status.label) {
            CompatLabel.RepairAvailable -> Res.string.compat_label_repair_available
            CompatLabel.RepairApplied -> Res.string.compat_label_repair_applied
            CompatLabel.PartlyRepaired -> Res.string.compat_label_partly_repaired
            CompatLabel.NeedsAuthorUpdate -> Res.string.compat_label_needs_author
        }
    },
)

@Composable
fun CompatChip(install: InstallCompatibility, enabled: Boolean, title: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val status = install.status(enabled) ?: return
    val text = compatStatusText(status)
    val description = stringResource(Res.string.compat_chip_description, title, text)
    val (tone, icon) = when (status) {
        is CompatStatus.Changing -> when (status.work) {
            is RepairWork.Queued -> ChipTone.Neutral to HideoutIcons.Clock
            is RepairWork.WaitingForGame -> ChipTone.Neutral to HideoutIcons.InGame
            RepairWork.Repairing, RepairWork.Restoring, is RepairWork.Verifying -> ChipTone.Neutral to null
        }
        is CompatStatus.Unfinished -> ChipTone.Warning to HideoutIcons.Warning
        is CompatStatus.Found -> when (status.label) {
            CompatLabel.RepairApplied -> ChipTone.Neutral to HideoutIcons.Shield
            CompatLabel.PartlyRepaired -> (if (enabled) ChipTone.Warning else ChipTone.Neutral) to HideoutIcons.Shield
            CompatLabel.NeedsAuthorUpdate -> ChipTone.Neutral to HideoutIcons.Info
            CompatLabel.RepairAvailable -> ChipTone.Warning to HideoutIcons.Warning
        }
    }
    StatusChip(text, tone, modifier.semantics { contentDescription = description }, leadingIcon = icon, size = ChipSize.Small, onClick = onClick, busy = install.pending?.busy == true)
}

private val RepairWork.busy: Boolean
    get() = this == RepairWork.Repairing || this == RepairWork.Restoring || this is RepairWork.Verifying

@Composable
private fun featureName(rule: RuleIdentity): String = when (rule.id) {
    "root-motion-attachment-frame" -> stringResource(Res.string.compat_feature_frame)
    "tagged-sound-binding-name" -> stringResource(Res.string.compat_feature_sound)
    else -> stringResource(Res.string.compat_feature_other, rule.id)
}

@Composable
fun CompatDetails(
    install: InstallCompatibility?,
    enabled: Boolean,
    unavailable: String?,
    canChange: Boolean,
    onApply: () -> Unit,
    onOriginal: () -> Unit,
    onCheckAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(HideoutDimens.AlertDialog.Gap)) {
        KeepsHeight(holding = install?.pending != null) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(HideoutDimens.AlertDialog.Gap)) {
                when {
                    unavailable != null -> Note(stringResource(Res.string.compat_unavailable, unavailable))
                    install == null -> Note(stringResource(Res.string.compat_not_checked))
                    install.failed != null -> Note(stringResource(Res.string.compat_check_failed, install.failed))
                    install.pending is RepairWork.Verifying || install.checking -> Unit
                    install.check == null -> Note(stringResource(Res.string.compat_not_checked))
                }
                if (install != null && install.check != null) {
                    val outcomes = install.outcomes
                    val incomplete = install.incomplete
                    if (outcomes.isNotEmpty() || incomplete.isNotEmpty()) Outcomes(outcomes, incomplete)
                    if (install.outdated.isNotEmpty() && install.repairable.isEmpty()) {
                        Text(stringResource(Res.string.compat_outdated_heading), style = type.titleRow, color = scheme.onSurface)
                        Note(stringResource(Res.string.compat_outdated))
                    }
                    if (outcomes.isEmpty() && incomplete.isEmpty() && install.outdated.isEmpty()) Note(stringResource(Res.string.compat_no_known_problem))
                    if (install.repaired.isNotEmpty() && !enabled) Note(stringResource(Res.string.compat_off_note))
                }
                if (install != null) {
                    if (install.choice == RepairChoice.Original && install.repairable.isNotEmpty()) Note(stringResource(Res.string.compat_chose_original))
                    install.problem?.let { Note(stringResource(Res.string.compat_failed, it), tertiary = true) }
                    if (install.repairable.isNotEmpty() || install.repaired.isNotEmpty()) Note(stringResource(Res.string.compat_qualification))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Library.NoteGap), verticalAlignment = Alignment.CenterVertically) {
            HideoutButton(stringResource(Res.string.compat_check_again), onCheckAgain, variant = ButtonVariant.Text, size = ButtonSize.Small, leadingIcon = HideoutIcons.Refresh, leadingIconSize = HideoutDimens.Icon.Small, enabled = unavailable == null)
            val work = install?.pending
            val asksOriginal = install?.choice == RepairChoice.Original
            val offersOriginal = install != null && !install.held && !asksOriginal && (install.repairable.isNotEmpty() || install.applied.isNotEmpty())
            if (offersOriginal || work?.restoring == true) {
                HideoutButton(stringResource(Res.string.compat_use_original), onOriginal, variant = ButtonVariant.Text, size = ButtonSize.Small, enabled = canChange && !asksOriginal)
            }
            Spacer(Modifier.weight(1f))
            if (install != null && !install.held && (install.repairable.isNotEmpty() || work?.restoring == false)) {
                HideoutButton(
                    stringResource(Res.string.compat_apply),
                    onApply,
                    variant = ButtonVariant.Tonal,
                    size = ButtonSize.Small,
                    leadingIcon = HideoutIcons.Shield,
                    leadingIconSize = HideoutDimens.Icon.Small,
                    enabled = canChange && install.repairable.isNotEmpty() && work?.restoring != false && (install.choice != RepairChoice.Repaired || install.problem != null),
                )
            }
        }
    }
}

@Composable
private fun CompatActivity(install: InstallCompatibility?, modifier: Modifier = Modifier) {
    Box(modifier) {
        val work = install?.pending
        when {
            work != null -> Progress(work)
            install?.checking == true -> Note(stringResource(Res.string.compat_checking))
        }
    }
}

/**
 * While [holding], lays [content] out no shorter than it was just before [holding] became true, or has been since, so
 * what leaves it as holding starts or meanwhile moves nothing around it; otherwise it takes its own height.
 */
@Composable
internal fun KeepsHeight(holding: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    var floor by remember { mutableStateOf(0.dp) }
    Box(
        modifier.heightIn(min = if (holding) floor else 0.dp).onSizeChanged { size ->
            val height = with(density) { size.height.toDp() }
            if (!holding || height > floor) floor = height
        },
    ) {
        content()
    }
}

@Composable
private fun Progress(work: RepairWork) {
    val text = stringResource(
        when (work) {
            is RepairWork.Queued -> Res.string.compat_queued
            RepairWork.Repairing -> Res.string.compat_repairing
            RepairWork.Restoring -> Res.string.compat_restoring
            is RepairWork.Verifying -> if (work.restoring) Res.string.compat_verifying_original else Res.string.compat_verifying
            is RepairWork.WaitingForGame -> if (work.restoring) Res.string.compat_original_waits else Res.string.compat_requested_waits
        },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Library.NoteGap), verticalAlignment = Alignment.CenterVertically) {
        if (work.busy) CircularProgressIndicator(Modifier.size(HideoutDimens.Chip.Spinner), color = MaterialTheme.colorScheme.tertiary, strokeWidth = HideoutDimens.Chip.SpinnerStroke)
        Note(text, tertiary = true)
    }
}

@Composable
private fun Note(text: String, tertiary: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Text(text, style = HideoutTheme.typography.bodyDialog, color = if (tertiary) scheme.tertiary else scheme.onSurfaceVariant)
}

@Composable
private fun Outcomes(outcomes: List<RuleFindings>, incomplete: List<String>) {
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    Column(
        Modifier.fillMaxWidth().heightIn(max = HideoutDimens.Library.UpdateListMaxHeight).background(scheme.surface, HideoutShapes.Row)
            .verticalScroll(rememberScrollState()).padding(vertical = HideoutDimens.Library.FilesBoxVerticalPadding),
    ) {
        val sections = outcomes.groupBy { it.outcome }
        FindingOutcome.entries.filter { it in sections || it == FindingOutcome.Unchecked && incomplete.isNotEmpty() }.forEach { outcome ->
            Column(
                Modifier.fillMaxWidth().padding(horizontal = HideoutDimens.Library.FileRowHorizontalPadding, vertical = HideoutDimens.Library.FilesBoxVerticalPadding),
                verticalArrangement = Arrangement.spacedBy(HideoutDimens.Library.FilesBoxVerticalPadding),
            ) {
                Text(stringResource(outcome.heading()), style = type.titleRow, color = scheme.onSurface)
                sections[outcome].orEmpty().forEach { found ->
                    Text(featureName(found.rule), style = type.bodyDialog, color = scheme.onSurface)
                    found.findings.forEach { finding ->
                        val where = finding.resource.substringAfterLast('/')
                        val counted = outcome == FindingOutcome.Repaired || outcome == FindingOutcome.Repairable
                        val line = if (counted && finding.edits > 0) stringResource(Res.string.compat_finding_resource, where, pluralStringResource(Res.plurals.compat_finding_edits, finding.edits, finding.edits)) else where
                        Text(line, style = type.mono, color = scheme.outline)
                        if (!counted) Text(stringResource(Res.string.compat_reason, finding.detail), style = type.bodyDialog, color = scheme.onSurfaceVariant)
                    }
                }
                if (outcome == FindingOutcome.Unchecked) incomplete.forEach { reason ->
                    Text(stringResource(Res.string.compat_incomplete, reason), style = type.bodyDialog, color = scheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun FindingOutcome.heading() = when (this) {
    FindingOutcome.Repaired -> Res.string.compat_fixed_heading
    FindingOutcome.Repairable -> Res.string.compat_repairable_heading
    FindingOutcome.LeftUnchanged -> Res.string.compat_left_unchanged_heading
    FindingOutcome.Unchecked -> Res.string.compat_unknown_heading
}

@Composable
fun CompatDialog(
    title: String?,
    install: InstallCompatibility?,
    enabled: Boolean,
    unavailable: String?,
    canChange: Boolean,
    modal: WindowModal,
    onApply: () -> Unit,
    onOriginal: () -> Unit,
    onCheckAgain: () -> Unit,
    onDismiss: () -> Unit,
) {
    var last by remember { mutableStateOf(title) }
    if (title != null) last = title
    val shown = last ?: return
    val close = remember { FocusRequester() }
    val heading = stringResource(Res.string.compat_details_title, shown)
    AlertModal(visible = title != null, modal = modal, title = heading, onDismiss = onDismiss, initialFocus = close) {
        Column(verticalArrangement = Arrangement.spacedBy(HideoutDimens.AlertDialog.Gap)) {
            Text(heading, style = HideoutTheme.typography.titleDialog, color = MaterialTheme.colorScheme.onSurface)
            CompatDetails(install, enabled, unavailable, canChange, onApply, onOriginal, onCheckAgain)
            Row(
                Modifier.fillMaxWidth().padding(top = HideoutDimens.AlertDialog.FooterTopPadding),
                horizontalArrangement = Arrangement.spacedBy(HideoutDimens.AlertDialog.FooterGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompatActivity(install, Modifier.weight(1f))
                HideoutButton(
                    stringResource(Res.string.compat_close),
                    onDismiss,
                    modifier = Modifier.focusRequester(close),
                    variant = ButtonVariant.Text,
                    size = ButtonSize.Regular,
                    contentPadding = PaddingValues(horizontal = HideoutDimens.AlertDialog.CancelPadding),
                )
            }
        }
    }
}
