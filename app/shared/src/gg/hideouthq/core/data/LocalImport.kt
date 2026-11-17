package gg.hideouthq.core.data

import gg.hideouthq.core.library.ArchiveIdentity

data class LocalFile(val path: String, val name: String, val sizeBytes: Long)

data class LocalArchiveSnapshot(val archive: ArchiveIdentity, val originalName: String)

/** Why a file handed to Hideout is not taken. */
enum class LocalRefusal {
    /** Not a `.zip`, `.7z`, `.rar` or `.vpk`. */
    UnsupportedType,

    /** One file of an archive split into several, such as `.7z.001` or `.z01`. */
    SplitArchive,

    /** It holds nothing. */
    Empty,

    /** It could not be read: it went, or another program holds it. */
    Unreadable,

    /** This Hideout takes no files, as in fake mode. */
    Unavailable,
}

sealed interface LocalImportResult {
    data class Ready(val snapshot: LocalArchiveSnapshot) : LocalImportResult
    data class Refused(val reason: LocalRefusal) : LocalImportResult
}

interface LocalImport {
    suspend fun snapshot(file: LocalFile): LocalImportResult
    suspend fun release(snapshot: LocalArchiveSnapshot)

    companion object {
        val Unavailable: LocalImport = object : LocalImport {
            override suspend fun snapshot(file: LocalFile): LocalImportResult = LocalImportResult.Refused(LocalRefusal.Unavailable)
            override suspend fun release(snapshot: LocalArchiveSnapshot) = Unit
        }
    }
}
