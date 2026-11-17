package gg.hideouthq.core.data

import gg.hideouthq.core.library.VpkFile
import gg.hideouthq.core.library.VpkRead

/**
 * Other mod managers' own files outside the game, and bundles they exported, read whole as bytes. Strictly read-only:
 * nothing here writes, moves or deletes in another manager's folders. Which folders each manager uses on which
 * system is the adapter's knowledge; every byte is parsed by the pure readers in core.migration.
 */
interface ManagerData {
    suspend fun dmmState(): ByteArray?

    suspend fun grimoire(folder: String?): GrimoireFiles?

    /** The bundle at [path], its folder or its `mod-interchange.json`. */
    suspend fun bundle(path: String): BundleOpen

    /** The VPK at [path], relative to a bundle's [folder] and inside it, measured and its tree read; null when no plain file is there. */
    suspend fun bundleVpk(folder: String, path: String): BundleVpk?

    companion object {
        val None: ManagerData = object : ManagerData {
            override suspend fun dmmState(): ByteArray? = null

            override suspend fun grimoire(folder: String?): GrimoireFiles? = null

            override suspend fun bundle(path: String): BundleOpen = BundleOpen.Missing("bundles are not read here")

            override suspend fun bundleVpk(folder: String, path: String): BundleVpk? = null
        }
    }
}

class GrimoireFiles(val metadata: ByteArray?, val profiles: ByteArray?)

sealed interface BundleOpen {
    /** [folder] is the absolute folder the bundle's file paths are under. */
    class Found(val manifest: ByteArray, val folder: String) : BundleOpen

    data class Missing(val reason: String) : BundleOpen
}

/** One VPK of a bundle: [path] relative to the bundle's folder, as its manifest names it, and [location] where it is on this computer. */
data class BundleVpk(override val path: String, val location: String, override val sizeBytes: Long, override val sha256: String, override val directory: VpkRead) : VpkFile
