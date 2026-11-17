package gg.hideouthq.core.library

import kotlin.jvm.JvmInline

@JvmInline
value class CitadelPath(val value: String) {
    init {
        require(isValid(value)) { "\"$value\" is not a relative path under game/citadel" }
    }

    val name: String get() = value.substringAfterLast('/')

    /** The folder this entry sits in, or null for a direct child of `game/citadel`. */
    val parent: CitadelPath? get() = value.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let(::CitadelPath)

    fun resolve(child: String): CitadelPath = CitadelPath("$value/$child")

    /** Whether this path is [folder] itself or inside it. */
    fun within(folder: CitadelPath): Boolean = value == folder.value || value.startsWith(folder.value + "/")

    /**
     * The set folder this path names a file in under a name Hideout gives there, `addons/pakNN_dir.vpk` or
     * `hideout_off/<modId>/set-<hash>_000.vpk` ([SetFolder.admits]); null for any other path.
     */
    val hideoutFolder: SetFolder?
        get() {
            val folder = parent?.value ?: return null
            val set = if (folder == Addons.value) {
                SetFolder.Addons
            } else {
                val modId = folder.removePrefix("${HideoutOff.value}/").takeIf { it != folder && '/' !in it } ?: return null
                runCatching { SetFolder.Off(modId) }.getOrNull() ?: return null
            }
            return set.takeIf { it.admits(name) }
        }

    /** Backup and staging folders, Hideout's and other managers', which hold copies of mods rather than mods. */
    val offLimits: Boolean get() = value.split('/').any { it.equals(Backups, ignoreCase = true) || it.equals(HideoutBackups.value, ignoreCase = true) || it.startsWith(StagingPrefix, ignoreCase = true) }

    companion object {
        val Addons: CitadelPath = CitadelPath("addons")
        val HideoutOff: CitadelPath = CitadelPath("hideout_off")

        /** Where Hideout keeps its backups of the game's Library, and the content of their files. */
        val HideoutBackups: CitadelPath = CitadelPath("hideout_backups")

        /** Where Restore original moves what the user edited in a mod's files, a folder for each, which nothing in Hideout deletes. */
        val HideoutEdits: CitadelPath = CitadelPath("hideout_backups/edits")

        private const val Backups = "addons-backups"
        private const val StagingPrefix = ".dmm-"

        fun parse(value: String): CitadelPath? = if (isValid(value)) CitadelPath(value) else null

        private fun isValid(value: String): Boolean =
            value.isNotEmpty() &&
                value.none { it == '\\' || it.isISOControl() } &&
                !(value.length >= 2 && value[1] == ':') &&
                value.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }
}
