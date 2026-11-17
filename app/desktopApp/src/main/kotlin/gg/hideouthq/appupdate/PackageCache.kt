package gg.hideouthq.appupdate

import gg.hideouthq.core.appupdate.PackageDownload
import gg.hideouthq.core.appupdate.PackageFormat
import gg.hideouthq.core.appupdate.ReleasePackage
import gg.hideouthq.core.appupdate.UpdateFailure
import gg.hideouthq.core.appupdate.UpdatePackages
import gg.hideouthq.library.Blob
import gg.hideouthq.library.BlobFailure
import gg.hideouthq.library.holdsBlob
import gg.hideouthq.library.retryingBusy
import gg.hideouthq.library.transferVerified
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal class PackageCache(private val folder: Path, private val client: HttpClient) : UpdatePackages {
    fun file(pkg: ReleasePackage): Path = folder.resolve("${pkg.sha256}.${pkg.format.extension}")

    override suspend fun probe(pkg: ReleasePackage): Boolean = withContext(Dispatchers.IO) {
        try {
            holdsBlob(file(pkg), pkg.sizeBytes, pkg.sha256)
        } catch (e: IOException) {
            false
        }
    }

    override suspend fun download(pkg: ReleasePackage, onProgress: (Long) -> Unit): PackageDownload =
        when (transferVerified(client, Blob(pkg.url, pkg.sizeBytes, pkg.sha256), partOf(pkg), file(pkg), onProgress)) {
            null -> PackageDownload.Verified
            BlobFailure.Server -> PackageDownload.Failed(UpdateFailure.Server)
            BlobFailure.Connection -> PackageDownload.Failed(UpdateFailure.Connection)
            BlobFailure.Disk -> PackageDownload.Failed(UpdateFailure.Disk)
            BlobFailure.Mismatch -> PackageDownload.Failed(UpdateFailure.Mismatch)
        }

    override suspend fun keepOnly(pkg: ReleasePackage?) = withContext(Dispatchers.IO) {
        val kept = pkg?.let { setOf(file(it).fileName.toString(), partOf(it).fileName.toString()) }.orEmpty()
        folder.toFile().listFiles().orEmpty().filter { it.name !in kept && Leftover.matches(it.name) }.forEach { leftover ->
            try {
                retryingBusy { Files.deleteIfExists(leftover.toPath()) }
            } catch (e: IOException) {
                // Windows holds a helper that is still exiting. The next start tries again.
            }
        }
    }

    private fun partOf(pkg: ReleasePackage) = folder.resolve("${pkg.sha256}.part")

    private companion object {
        val Leftover = Regex("""[0-9a-f]{64}\.(part|msi|AppImage|deb|rpm)|$FinishHelperName""")
    }
}

/** `apt-get` and `dnf` read a name as a file rather than a package's name by how it ends. */
private val PackageFormat.extension: String
    get() = when (this) {
        PackageFormat.Msi -> "msi"
        PackageFormat.AppImage -> "AppImage"
        PackageFormat.Deb -> "deb"
        PackageFormat.Rpm -> "rpm"
    }
