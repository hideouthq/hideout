package gg.hideouthq.core.data

import gg.hideouthq.core.library.LibraryManifest

/**
 * The durable manifest: Hideout's only record of what it owns. The real store keeps it in one file, published by an
 * atomic rename. A file that cannot be trusted comes back as [ManifestLoad.Unreadable], never as an empty manifest.
 */
interface LibraryStore {
    suspend fun load(): ManifestLoad

    /**
     * Replaces the manifest, or refuses and leaves the stored manifest exactly as it was. A save that throws may have
     * done either; only reading the store again tells which.
     */
    suspend fun save(manifest: LibraryManifest): ManifestSave
}

sealed interface ManifestLoad {
    /** There is no manifest: a fresh library, or one whose file was lost, so no retained original is swept. */
    data object Absent : ManifestLoad

    data class Loaded(val manifest: LibraryManifest) : ManifestLoad

    /** The manifest cannot be trusted, read, or confirmed absent. Read-only: never an empty, writable library. */
    data class Unreadable(val reason: String) : ManifestLoad
}

sealed interface ManifestSave {
    data object Saved : ManifestSave

    /** Nothing changed: the manifest was too large, the stored one could not be trusted, or publishing it failed. */
    data class Refused(val reason: String) : ManifestSave
}
