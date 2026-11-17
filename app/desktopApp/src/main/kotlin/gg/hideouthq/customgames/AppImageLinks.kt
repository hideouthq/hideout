package gg.hideouthq.customgames

import gg.hideouthq.appupdate.runningAppImage
import gg.hideouthq.data.AppId
import gg.hideouthq.data.XdgDirs
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val SchemeMime = "x-scheme-handler/hideout"
private const val HandlerFile = "$AppId-links.desktop"
private val PlainInExec = Regex("[A-Za-z0-9_./+,@=:-]+")

internal fun registerAppImageLinks(log: (String) -> Unit) {
    val appImage = runningAppImage() ?: return
    val applications = XdgDirs(File(System.getProperty("user.home")), System::getenv).data().toPath().resolve("applications")
    thread(isDaemon = true, name = "hideout-link-registration") {
        try {
            registerHandler(appImage, applications, ::xdgMime)
        } catch (e: IOException) {
            log("Could not register invite links: ${e::class.simpleName}")
        }
    }
}

internal fun registerHandler(appImage: Path, applications: Path, mime: (List<String>) -> String) {
    val entry = handlerEntry(appImage) ?: return
    // Asked before the entry exists, because xdg-mime would then answer with it, while GLib's opener needs a stated default.
    val unclaimed = mime(listOf("query", "default", SchemeMime)).isBlank()
    val file = Files.createDirectories(applications).resolve(HandlerFile)
    if (!Files.isRegularFile(file) || Files.readString(file) != entry) Files.writeString(file, entry)
    if (unclaimed) mime(listOf("default", HandlerFile, SchemeMime))
}

/**
 * Quotes only a path that needs it, since the plain `xdg-open` a desktop without its own opener falls back to takes the
 * first word as the program and reads the quotes as part of it. Null for a line break, which no entry can hold, or a
 * backslash, which desktops unescape differently.
 */
internal fun handlerEntry(appImage: Path): String? {
    val path = appImage.toString()
    if (path.any { it in "\n\r\\" }) return null
    val exec = if (PlainInExec.matches(path)) path else "\"" + path.replace(Regex("""["`$]""")) { "\\\\" + it.value }.replace("%", "%%") + "\""
    return """
        [Desktop Entry]
        Type=Application
        Name=Hideout
        Exec=$exec %u
        MimeType=$SchemeMime;
        NoDisplay=true
        Terminal=false
    """.trimIndent() + "\n"
}

private fun xdgMime(arguments: List<String>): String {
    val process = ProcessBuilder(listOf("xdg-mime") + arguments).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly()
    return output
}
