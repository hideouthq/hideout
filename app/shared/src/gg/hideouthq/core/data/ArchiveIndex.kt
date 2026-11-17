package gg.hideouthq.core.data

import gg.hideouthq.core.library.FilePrint

/** What GameBanana's archives hold, read from each archive's own index and never from its contents. */
fun interface ArchiveIndex {
    /** The prints of the VPKs [file] holds, in its index's order; null when the index says nothing either way. */
    suspend fun vpks(file: RemoteFile): List<FilePrint>?

    companion object {
        val Unavailable: ArchiveIndex = ArchiveIndex { null }
    }
}
