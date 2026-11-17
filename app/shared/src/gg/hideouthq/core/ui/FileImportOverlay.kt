package gg.hideouthq.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gg.hideouthq.core.data.isSplitArchivePart
import gg.hideouthq.core.data.isSupportedImport
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.shape.Cookie
import gg.hideouthq.core.library.ModLibrary
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.file_import_close
import gg.hideouthq.resources.file_import_drop
import gg.hideouthq.resources.file_import_pick
import gg.hideouthq.resources.file_import_pick_reinstall
import gg.hideouthq.resources.file_import_supported
import gg.hideouthq.resources.file_import_types
import gg.hideouthq.resources.file_import_unreadable
import gg.hideouthq.resources.file_import_unsupported
import gg.hideouthq.resources.problem_archive_split
import gg.hideouthq.resources.size_mb
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

@Composable
fun FileImportHost(intake: FileImportState, library: ModLibrary) {
    val presentation = intake.presentation
    val pickTitle = stringResource(if (intake.reinstalling != null) Res.string.file_import_pick_reinstall else Res.string.file_import_pick)
    LaunchedEffect(presentation) {
        when (presentation) {
            FileImportPresentation.Picking -> intake.chooseFile(pickTitle)
            is FileImportPresentation.Feedback -> { delay(6_000); intake.dismiss() }
            else -> Unit
        }
    }
    when (presentation) {
        is FileImportPresentation.Hover -> {
            val state by library.state.collectAsState()
            FileImportOverlay(presentation, state.profiles?.activeProfile?.name)
        }
        is FileImportPresentation.Feedback -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.BottomCenter) {
            Snackbar(
                modifier = Modifier.widthIn(max = 560.dp).testTag("file-import-feedback"),
                dismissAction = { TextButton(onClick = intake::dismiss) { Text(stringResource(Res.string.file_import_close)) } },
            ) { Text(importProblemLabel(presentation.problem)) }
        }
        else -> Unit
    }
}

@Composable
fun FileImportOverlay(hover: FileImportPresentation.Hover, activeProfile: String?) {
    val files = hover.files
    val file = files?.singleOrNull()
    val problem = when {
        files.isNullOrEmpty() || files.any { it.isSupportedImport() } -> null
        files.any { it.isSplitArchivePart() } -> FileImportProblem.SplitArchive
        else -> FileImportProblem.Unsupported
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = .74f)).testTag("file-import-overlay")
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } }
            .padding(40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().height(320.dp)
                .background(Color.White.copy(alpha = .03f), RoundedCornerShape(32.dp))
                .drawBehind {
                    drawRoundRect(Color.White.copy(alpha = .42f), cornerRadius = CornerRadius(32.dp.toPx()),
                        style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx()))))
                }.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        ) {
            Box(Modifier.padding(bottom = 8.dp).size(84.dp).background(MaterialTheme.colorScheme.primary, Cookie.Twelve.shape), contentAlignment = Alignment.Center) {
                HideoutIcon(if (problem == null) HideoutIcons.Download else HideoutIcons.Warning, null, size = 32.dp, tint = MaterialTheme.colorScheme.onPrimary)
            }
            Text(problem?.let { importProblemLabel(it) } ?: stringResource(Res.string.file_import_drop), color = Color.White,
                fontSize = 22.sp, fontWeight = FontWeight(650), textAlign = TextAlign.Center)
            if (file != null) Text("${file.name} · ${importFileSize(file.sizeBytes)}", color = Color.White.copy(alpha = .7f),
                fontSize = 13.5.sp, fontWeight = FontWeight(450), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Text(if (activeProfile == null || problem != null) stringResource(Res.string.file_import_supported) else stringResource(Res.string.file_import_types, activeProfile),
                color = Color.White.copy(alpha = .5f), fontSize = 13.5.sp, fontWeight = FontWeight(450), textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun importFileSize(bytes: Long): String =
    if (bytes in 1_000_000L until 1_000_000_000L) stringResource(Res.string.size_mb, ModLabels.megabytes(bytes)) else ModLabels.size(bytes)

@Composable
private fun importProblemLabel(problem: FileImportProblem): String = stringResource(when (problem) {
    FileImportProblem.Unsupported -> Res.string.file_import_unsupported
    FileImportProblem.SplitArchive -> Res.string.problem_archive_split
    FileImportProblem.Unreadable -> Res.string.file_import_unreadable
})
