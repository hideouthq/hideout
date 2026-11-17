package gg.hideouthq.deadlockstats

internal object SteamDesktopEntry {
    fun wrap(original: String, launcher: String): String {
        require(launcher.startsWith('/') && launcher.none { it == '\n' || it == '\r' || it == '\u0000' })
        val escaped = buildString {
            append('"')
            launcher.forEach { c -> when (c) {
                '%' -> append("%%")
                '\\' -> append("\\\\\\\\")
                '"', '$', '`' -> { append("\\\\"); append(c) }
                else -> append(c)
            } }
            append('"')
        }
        var section = ""
        var mainExec = false
        return original.lineSequence().joinToString("\n") { line ->
            if (line.startsWith('[') && line.endsWith(']')) section = line
            when {
                (section == "[Desktop Entry]" || section.startsWith("[Desktop Action ")) && line.startsWith("Exec=") -> {
                    require(line.removePrefix("Exec=").isNotBlank())
                    if (section == "[Desktop Entry]") mainExec = true
                    "Exec=$escaped -- ${line.removePrefix("Exec=")}"
                }
                section == "[Desktop Entry]" && line.startsWith("DBusActivatable=") -> "DBusActivatable=false"
                else -> line
            }
        }.also { require(mainExec) { "Steam desktop entry has no main Exec" } }
    }
}
