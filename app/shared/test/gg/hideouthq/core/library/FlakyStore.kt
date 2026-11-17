package gg.hideouthq.core.library

import gg.hideouthq.core.data.LibraryStore
import gg.hideouthq.core.data.ManifestLoad
import gg.hideouthq.core.data.ManifestSave
import gg.hideouthq.core.data.fake.InMemoryLibraryStore

internal class FlakyStore(private val inner: InMemoryLibraryStore) : LibraryStore {
    var unreadable: String? = null
    var unreadableLoads = 0
    var vanished = false

    val asked: MutableList<LibraryManifest> = mutableListOf()

    override suspend fun load(): ManifestLoad {
        unreadable?.let { return ManifestLoad.Unreadable(it) }
        if (unreadableLoads > 0) {
            unreadableLoads--
            return ManifestLoad.Unreadable("library.json could not be read")
        }
        return if (vanished) ManifestLoad.Absent else inner.load()
    }

    override suspend fun save(manifest: LibraryManifest): ManifestSave {
        asked += manifest
        unreadable?.let { return ManifestSave.Refused(it) }
        return inner.save(manifest)
    }
}
