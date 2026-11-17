package gg.hideouthq.core.library

const val DeadlockAppId = 1422450

/** The manifest Steam keeps for Deadlock in the `steamapps` folder of the library it is installed in. */
const val DeadlockManifestName = "appmanifest_$DeadlockAppId.acf"

/**
 * The libraries a Steam root's `steamapps/libraryfolders.vdf` lists, in Steam's order: each numbered entry is a block
 * holding a `path`. Other entries (`contentstatsid`, `TimeNextStatsReport`) are skipped.
 */
fun steamLibraryFolders(libraryFolders: KeyValues): List<String> {
    val root = libraryFolders.block("libraryfolders") ?: return emptyList()
    return root.children
        .filterIsInstance<KvBlock>()
        .filter { node -> node.condition == null && node.key.value.isNotEmpty() && node.key.value.all(Char::isDigit) }
        .mapNotNull { node -> node.string("path") }
}

/** The folder under `steamapps/common` that Deadlock's app manifest names, or null when it is anything but one plain folder, such as `../elsewhere`. */
fun deadlockInstallDir(appManifest: KeyValues): String? = appManifest.block("AppState")?.string("installdir")?.takeIf(::isPlainFolderName)

/** One folder name: not empty, `.` or `..`, and free of separators, drive letters, control characters and edge spaces. */
fun isPlainFolderName(name: String): Boolean =
    name.isNotEmpty() && name != "." && name != ".." && name.trim() == name && name.none { it == '/' || it == '\\' || it == ':' || it.isISOControl() }

/** A POSIX path from the root, a Windows path from a drive letter, or a UNC path. */
fun isAbsolutePath(path: String): Boolean {
    if (path.isEmpty() || path.any(Char::isISOControl)) return false
    val drive = path.length >= 3 && path[0].lowercaseChar() in 'a'..'z' && path[1] == ':' && (path[2] == '\\' || path[2] == '/')
    val unc = path.startsWith("\\\\") || path.startsWith("//")
    return drive || unc || path.startsWith('/')
}
