package gg.hideouthq.core.data

import gg.hideouthq.core.library.CitadelPath
import gg.hideouthq.core.library.EntryKind
import gg.hideouthq.core.library.FileImage
import gg.hideouthq.core.library.FilePrint
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.Imprint
import gg.hideouthq.core.library.VpkRead

/**
 * What a game root's folders actually hold, with no notion of who owns what — that judgment is the manifest's.
 * Read-only: nothing here writes, moves or deletes. Below the root, which the locator has already resolved, links are
 * never followed: a linked folder or file is not where Hideout put anything. A folder or file that cannot be read is
 * reported as such, never as empty or missing.
 */
interface GameInventory {
    /**
     * Every entry directly inside `<root>/game/citadel/[folder]`, a path of forward-slash segments, empty for
     * `game/citadel` itself, each as it is seen without following a link. Empty when the folder does not exist yet.
     */
    suspend fun list(root: GameRoot, folder: String): FolderListing

    /**
     * The plain file at `<root>/[relativePath]`, a path of forward-slash segments. What it holds may be known from an
     * earlier read of the same file, as in [TwinLinks], so a write that leaves its size and times as they were is not seen.
     */
    suspend fun file(root: GameRoot, relativePath: String): FileObservation

    /**
     * The bytes of the plain file at [path] under `<root>/game/citadel`, when it holds at most [maxBytes]: a config or a
     * manager's manifest. A link there, or on the way to it, is unreadable, never taken for no file.
     */
    suspend fun read(root: GameRoot, path: CitadelPath, maxBytes: Int): FileRead

    /**
     * The `.vpk` at [path] under `<root>/game/citadel`: its size, SHA-256 and CRC-32, what its own directory says and
     * the [Imprint] at its root, all from one read of its bytes.
     */
    suspend fun vpk(root: GameRoot, path: CitadelPath): VpkLook
}

sealed interface VpkLook {
    /** [crc32] is the whole file's, unsigned. */
    data class Present(val image: FileImage, val crc32: Long, val directory: VpkRead, val imprint: Imprint?) : VpkLook {
        /** What an archive that held the file lists: its own size and CRC-32, or an imprinted one's from before, when its imprint says. */
        val print: FilePrint? get() = if (imprint == null) FilePrint(image.sizeBytes, crc32) else imprint.original
    }

    /** No plain file is there: nothing, a link, a folder, or a path through a linked folder. */
    data object Absent : VpkLook

    data class Unreadable(val reason: String) : VpkLook
}

sealed interface FileRead {
    class Present(val bytes: ByteArray) : FileRead

    /** Nothing is there. */
    data object Absent : FileRead

    /** Something is there that could not be read whole, or is not a plain file: a link, a folder, or a path through a link. */
    data class Unreadable(val reason: String) : FileRead {
        companion object {
            /** [path], under the game folder, is a link. */
            fun link(path: String) = Unreadable("$path is a link. Hideout never reads or changes the game's files through one.")
        }
    }
}

sealed interface FolderListing {
    data class Listed(val entries: List<FolderEntry>) : FolderListing {
        val names: List<String> get() = entries.map { it.name }
    }

    /** The folder, or one on the way to it, is a link, not a folder, or cannot be read: its contents are unknown. */
    data class Unreadable(val reason: String) : FolderListing
}

/**
 * One entry of a folder. A symbolic link or a Windows junction is a [EntryKind.Link], whatever it points at. A plain
 * file has its [stamp], which tells a file read once from one changed since; nothing else has one.
 */
data class FolderEntry(val name: String, val kind: EntryKind, val stamp: FileStamp? = null)

/** A plain file's size and last-modified time, in epoch milliseconds, as the folder listing saw them. */
data class FileStamp(val sizeBytes: Long, val modifiedAt: Long)

sealed interface FileObservation {
    /** A plain file; its size and SHA-256 come from the same bytes. */
    data class Present(val sizeBytes: Long, val sha256: String) : FileObservation

    /** No plain file Hideout could have written is there: nothing, a link, a folder, or a path through a linked folder. */
    data object Absent : FileObservation

    /** Something is there but could not be read, so whether it is Hideout's file is unknown. */
    data class Unreadable(val reason: String) : FileObservation
}
