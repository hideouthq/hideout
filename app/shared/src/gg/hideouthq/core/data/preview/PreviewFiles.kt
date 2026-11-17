package gg.hideouthq.core.data.preview

/** The 3D preview files this computer keeps. A preview downloads its files again the next time it is opened. */
interface PreviewFiles {
    suspend fun bytes(): Long

    suspend fun clear()
}
