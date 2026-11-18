package gg.hideouthq.core.customgames

import gg.hideouthq.core.data.CacheStorage
import kotlinx.serialization.json.Json

/** One row of the library. [key] is unique across rows, since a folder may share its id with a feed mode. */
sealed interface LibraryEntry {
    val key: String

    /** [inFeed] is false for one the feed doesn't list, or that no feed has been read for. */
    data class Listed(val mode: Mode, val install: Install, val inFeed: Boolean) : LibraryEntry {
        override val key get() = "mode:${mode.id.value}"
    }

    data class Folder(val path: String, val read: FolderRead) : LibraryEntry {
        override val key get() = "folder:$path"
    }
}

/**
 * Every mode this player can see, local first: the feed's in its order, then installed modes it doesn't list, which
 * stay hostable with no network, then the player's folders. Each mode is [ModeList.hostable]'s, so every screen hosts
 * the same version of it. Pure, over what [modes] holds.
 */
fun library(modes: ModeList): List<LibraryEntry> {
    val ids = (modes.modes.map { it.id } + modes.installed.keys).distinct()
    val listed = ids.mapNotNull { id -> modes.hostable(id)?.let { LibraryEntry.Listed(it, modes.installOf(it), inFeed = modes.feed?.mode(id) != null) } }
    return listed + modes.folders.orEmpty()
}

/** A file that can't be read counts as no folders. */
class ModeFolders(private val storage: CacheStorage) {
    suspend fun read(): List<String> = try {
        storage.read(FileName)?.let { Json.decodeFromString<List<String>>(it) }.orEmpty()
    } catch (e: IllegalArgumentException) {
        emptyList()
    }

    suspend fun write(paths: List<String>) = storage.write(FileName, Json.encodeToString(paths))

    private companion object {
        const val FileName = "custom-game-folders.json"
    }
}
