package gg.hideouthq.data

import java.io.File

internal typealias SaveAs = suspend (title: String, suggested: File) -> File?

/**
 * GTK's dialogs return a name as typed, so the extension is added here. The dialog never checked that fuller name, so if
 * a file already has it the dialog opens again offering it, and asks before replacing it.
 */
internal suspend fun saveTarget(title: String, suggested: File, saveAs: SaveAs): File? {
    val extension = suggested.extension
    var offered = suggested
    while (true) {
        val chosen = saveAs(title, offered) ?: return null
        if (chosen.extension.equals(extension, ignoreCase = true)) return chosen
        offered = File(chosen.parentFile, "${chosen.name}.$extension")
        if (!offered.exists()) return offered
    }
}
