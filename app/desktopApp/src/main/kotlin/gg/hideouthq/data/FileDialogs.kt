package gg.hideouthq.data

import gg.hideouthq.OnWindows
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitDialogException
import io.github.vinceglb.filekit.dialogs.FileKitDialogParent
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openDirectoryPicker
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.dialogs.openFileSaver
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Component
import java.awt.Container
import java.awt.KeyboardFocusManager
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

internal suspend fun openFileDialog(title: String, extensions: Set<String>): File? =
    show(title) { FileKit.openFilePicker(FileKitType.File(extensions), startFolder(null), it) }

internal suspend fun openFolderDialog(title: String, start: File?): File? =
    show(title) { FileKit.openDirectoryPicker(startFolder(start), it) }

internal suspend fun saveFileDialog(title: String, suggested: File): File? =
    show(title) { FileKit.openFileSaver(suggested.nameWithoutExtension, defaultExtension = suggested.extension, directory = startFolder(suggested.parentFile), dialogSettings = it) }

/**
 * [wanted] or, as a dialog cannot show a folder that is not there yet, the nearest folder above it. Without one, the
 * user's home: GTK's Recent view, where its dialogs open otherwise, has no use for a folder and cannot choose one.
 */
private fun startFolder(wanted: File?): PlatformFile? =
    generateSequence(wanted ?: File(System.getProperty("user.home")), File::getParentFile).firstOrNull(File::isDirectory)?.let(::PlatformFile)

private suspend fun show(title: String, open: suspend (FileKitDialogSettings) -> PlatformFile?): File? {
    val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
    val settings = FileKitDialogSettings(title, owner?.let(FileKitDialogParent::awt))
    val opened = TimeSource.Monotonic.markNow()
    if (OnWindows) return open(settings)?.file
    // A portal's dialog is another program's window, which keeps above its parent but leaves it clickable. Compose takes
    // input in a canvas of its own, which disabling only the window leaves enabled.
    owner?.takesInput(false)
    return try {
        // Only cancelling ends a portal that took the request and never answers, which closes its dialog if it has one.
        // Windows' dialog disables its owner itself and blocks until it closes, so it needs neither.
        withTimeoutOrNull(PortalLimit) { open(settings) }?.file
    } catch (failure: FileKitDialogException) {
        // GTK's portal ends a dialog closed with Escape as it ends one that failed to open, only later.
        if (opened.elapsedNow() < ReactionTime) throw failure
        null
    } finally {
        owner?.takesInput(true)
    }
}

private fun Component.takesInput(on: Boolean) {
    isEnabled = on
    if (this is Container) components.forEach { it.takesInput(on) }
}

private val PortalLimit = 5.minutes
private val ReactionTime = 1.seconds
