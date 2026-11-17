package gg.hideouthq.core.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import gg.hideouthq.core.data.FilePicker
import gg.hideouthq.core.data.LocalFile
import gg.hideouthq.core.data.isSplitArchivePart
import gg.hideouthq.core.data.isSupportedImport
import gg.hideouthq.core.library.InstallInput
import gg.hideouthq.core.library.InstallRequest
import gg.hideouthq.core.library.ModLibrary
import kotlinx.coroutines.CancellationException

sealed interface FileImportPresentation {
    data object Idle : FileImportPresentation
    data object Picking : FileImportPresentation
    data class Hover(val files: List<LocalFile>?) : FileImportPresentation
    data class Feedback(val problem: FileImportProblem) : FileImportPresentation
}

enum class FileImportProblem { Unsupported, SplitArchive, Unreadable }

@Stable
class FileImportState(private val library: ModLibrary, private val picker: FilePicker, private val modal: WindowModal) {
    var presentation: FileImportPresentation by mutableStateOf(FileImportPresentation.Idle)
        private set

    /** The installed mod the file being picked is to reinstall, or null when it installs a new one. */
    var reinstalling: String? = null
        private set

    fun pick(reinstall: String? = null) {
        if (presentation == FileImportPresentation.Picking) return
        reinstalling = reinstall
        presentation = FileImportPresentation.Picking
    }

    suspend fun chooseFile(title: String) {
        if (presentation != FileImportPresentation.Picking) return
        val target = reinstalling.also { reinstalling = null }
        modal.hold(this)
        val file = try {
            picker.pick(title)
        } catch (cancelled: CancellationException) {
            presentation = FileImportPresentation.Idle
            throw cancelled
        } catch (_: Exception) {
            presentation = FileImportPresentation.Feedback(FileImportProblem.Unreadable)
            return
        } finally {
            modal.release(this)
        }
        presentation = FileImportPresentation.Idle
        file ?: return
        if (target != null) library.reinstall(target, file) else drop(listOf(file))
    }

    fun hover(files: List<LocalFile>?) {
        if (presentation == FileImportPresentation.Picking) return
        presentation = FileImportPresentation.Hover(files)
    }

    fun leave() {
        if (presentation is FileImportPresentation.Hover) presentation = FileImportPresentation.Idle
    }

    /** Installs each supported file of [files] on its own; files of other types are passed over unless none is supported. */
    fun drop(files: List<LocalFile>) {
        if (presentation == FileImportPresentation.Picking) return
        val supported = files.filter { it.isSupportedImport() }
        presentation = when {
            files.isEmpty() -> FileImportPresentation.Feedback(FileImportProblem.Unreadable)
            supported.isEmpty() && files.any { it.isSplitArchivePart() } -> FileImportPresentation.Feedback(FileImportProblem.SplitArchive)
            supported.isEmpty() -> FileImportPresentation.Feedback(FileImportProblem.Unsupported)
            else -> FileImportPresentation.Idle
        }
        val profiles = setOfNotNull(library.state.value.profiles?.active)
        supported.forEach { library.install(InstallRequest(InstallInput.Local(it), profiles)) }
    }

    fun unreadable() {
        if (presentation != FileImportPresentation.Picking) presentation = FileImportPresentation.Feedback(FileImportProblem.Unreadable)
    }

    fun dismiss() {
        if (presentation is FileImportPresentation.Feedback) presentation = FileImportPresentation.Idle
    }
}

val LocalFileImport = staticCompositionLocalOf<FileImportState?> { null }
