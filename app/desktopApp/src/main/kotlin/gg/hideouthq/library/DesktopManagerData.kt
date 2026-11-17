package gg.hideouthq.library

import gg.hideouthq.OnWindows
import gg.hideouthq.core.data.BundleOpen
import gg.hideouthq.core.data.BundleVpk
import gg.hideouthq.core.data.GrimoireFiles
import gg.hideouthq.core.data.ManagerData
import gg.hideouthq.data.XdgDirs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path

class DesktopManagerData(
    private val home: Path,
    private val env: (String) -> String?,
    private val windows: Boolean,
) : ManagerData {
    private val xdg = XdgDirs(home.toFile(), env)

    override suspend fun dmmState(): ByteArray? = withContext(Dispatchers.IO) {
        dmmFolders().mapNotNull { stored(it.resolve(StateName), MaxStateBytes) }.maxByOrNull { it.modifiedAt }?.bytes
    }

    override suspend fun grimoire(folder: String?): GrimoireFiles? = withContext(Dispatchers.IO) {
        val folders = folder?.let(::pathOrNull)?.let(::listOf) ?: grimoireFolders()
        folders.mapNotNull { data ->
            val metadata = stored(data.resolve(MetadataName), MaxStateBytes)
            val profiles = stored(data.resolve(ProfilesName), MaxStateBytes)
            val written = listOfNotNull(metadata, profiles).maxOfOrNull { it.modifiedAt } ?: return@mapNotNull null
            written to GrimoireFiles(metadata?.bytes, profiles?.bytes)
        }.maxByOrNull { (written, _) -> written }?.second
    }

    override suspend fun bundle(path: String): BundleOpen = withContext(Dispatchers.IO) {
        val picked = pathOrNull(path) ?: return@withContext BundleOpen.Missing("$path is not a path on this computer")
        val attributes = attributesOrNull(picked) ?: return@withContext BundleOpen.Missing("$path is not there")
        val manifest = when {
            attributes.isDirectory && !attributes.isSymbolicLink -> picked.resolve(BundleManifest)
            attributes.isRegularFile -> picked
            else -> return@withContext BundleOpen.Missing("$path is a link or not a file or folder")
        }
        val read = stored(manifest, MaxBundleBytes) ?: return@withContext BundleOpen.Missing("there is no $BundleManifest in $path")
        BundleOpen.Found(read.bytes, manifest.parent.toString())
    }

    override suspend fun bundleVpk(folder: String, path: String): BundleVpk? = withContext(Dispatchers.IO) {
        val base = pathOrNull(folder) ?: return@withContext null
        val segments = path.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return@withContext null
        try {
            var at = base
            for (segment in segments.dropLast(1)) {
                at = at.resolve(segment)
                val attributes = attributesOrNull(at) ?: return@withContext null
                if (!attributes.isPlainFolder) return@withContext null
            }
            val file = at.resolve(segments.last())
            if (attributesOrNull(file)?.isRegularFile != true) return@withContext null
            val look = lookVpk(file)
            BundleVpk(path, file.toString(), look.image.sizeBytes, look.image.sha256, look.directory)
        } catch (e: IOException) {
            null
        }
    }

    private fun dmmFolders(): List<Path> = if (windows) {
        listOfNotNull(env("APPDATA"), env("LOCALAPPDATA")).mapNotNull(::pathOrNull).map { it.resolve(DmmId) }
    } else {
        val flatpak = home.resolve(".var/app/$DmmId")
        listOf(xdg.data().toPath().resolve(DmmId), xdg.config().toPath().resolve(DmmId), flatpak.resolve("data/$DmmId"), flatpak.resolve("config/$DmmId"))
    }

    private fun grimoireFolders(): List<Path> = if (windows) {
        listOfNotNull(env("APPDATA")?.let(::pathOrNull)?.resolve(GrimoireName))
    } else {
        listOf(xdg.config().toPath().resolve(GrimoireName), home.resolve(".var/app/$GrimoireFlatpakId/config/$GrimoireName"))
    }

    private fun stored(file: Path, maxBytes: Int): Stored? = try {
        val attributes = attributesOrNull(file)?.takeIf { it.isRegularFile } ?: return null
        readAtMost(file, maxBytes)?.let { Stored(it, attributes.lastModifiedTime().toMillis()) }
    } catch (e: IOException) {
        null
    }

    private class Stored(val bytes: ByteArray, val modifiedAt: Long)

    private fun pathOrNull(value: String): Path? = try {
        Path.of(value).takeIf { it.isAbsolute }
    } catch (e: InvalidPathException) {
        null
    }

    companion object {
        fun forThisComputer(): DesktopManagerData =
            DesktopManagerData(Path.of(System.getProperty("user.home")), System::getenv, OnWindows)

        private const val DmmId = "dev.stormix.deadlock-mod-manager"
        private const val StateName = "state.json"
        private const val GrimoireName = "grimoire"
        private const val GrimoireFlatpakId = "com.grimoiremods.Grimoire"
        private const val MetadataName = "mod-metadata.json"
        private const val ProfilesName = "profiles.json"
        private const val BundleManifest = "mod-interchange.json"
        private const val MaxStateBytes = 32 * 1024 * 1024
        private const val MaxBundleBytes = 16 * 1024 * 1024
    }
}
