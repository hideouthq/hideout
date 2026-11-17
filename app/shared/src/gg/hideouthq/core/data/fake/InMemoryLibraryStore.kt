package gg.hideouthq.core.data.fake

import gg.hideouthq.core.data.LibraryStore
import gg.hideouthq.core.data.ManifestLoad
import gg.hideouthq.core.data.ManifestSave
import gg.hideouthq.core.library.LibraryManifest

class InMemoryLibraryStore(
    initial: LibraryManifest? = null,
    private val forceUnreadable: String? = null,
) : LibraryStore {
    var manifest: LibraryManifest? = initial
        private set

    var refuse: (LibraryManifest) -> String? = { null }

    var afterStore: (LibraryManifest) -> Unit = {}

    val saved: MutableList<LibraryManifest> = mutableListOf()

    override suspend fun load(): ManifestLoad {
        if (forceUnreadable != null) return ManifestLoad.Unreadable(forceUnreadable)
        val current = manifest ?: return ManifestLoad.Absent
        return ManifestLoad.Loaded(current)
    }

    override suspend fun save(manifest: LibraryManifest): ManifestSave {
        refuse(manifest)?.let { return ManifestSave.Refused(it) }
        this.manifest = manifest
        saved += manifest
        afterStore(manifest)
        return ManifestSave.Saved
    }
}
