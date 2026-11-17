package gg.hideouthq.data

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import gg.hideouthq.core.data.ViewerFiles
import gg.hideouthq.library.DesktopHandoff
import gg.hideouthq.library.replaceAtomically
import gg.hideouthq.preview.sha256Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.awt.Desktop
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

class DesktopViewerFiles(
    private val backdrops: Path,
    private val pictures: Path,
    private val choose: suspend (title: String) -> File? = ::chooseImage,
    private val saveAs: SaveAs = ::saveFileDialog,
    private val show: (File) -> Unit = ::revealInFileManager,
) : ViewerFiles {
    override suspend fun importBackdrop(title: String): String? {
        val source = choose(title) ?: return null
        return withContext(Dispatchers.IO) { copyBackdrop(source.toPath()) }
    }

    override suspend fun readBackdrop(path: String): ByteArray? = withContext(Dispatchers.IO) {
        val file = Path.of(path)
        if (file.parent != backdrops || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) null else Files.readAllBytes(file)
    }

    override suspend fun savePicture(image: ImageBitmap, title: String, fileName: String): String? {
        // The Save dialog opens only in a folder that exists.
        withContext(Dispatchers.IO) { if (Files.isDirectory(pictures.parent)) Files.createDirectories(pictures) }
        val target = saveTarget(title, unused(pictures.resolve(fileName)).toFile(), saveAs)?.toPath() ?: return null
        withContext(Dispatchers.IO) {
            Files.createDirectories(target.parent)
            replaceAtomically(target, encodePng(image))
        }
        return target.toAbsolutePath().toString()
    }

    override suspend fun reveal(path: String) = runInterruptible(Dispatchers.IO) { show(File(path)) }

    private fun unused(file: Path): Path {
        if (!Files.exists(file)) return file
        val stem = file.fileName.toString().substringBeforeLast('.')
        return generateSequence(2) { it + 1 }.map { file.resolveSibling("$stem-$it.png") }.first { !Files.exists(it) }
    }

    internal fun copyBackdrop(source: Path): String {
        val extension = source.fileName.toString().substringAfterLast('.', "").lowercase()
        if (extension !in ImageExtensions) throw IOException("${source.fileName} is not a PNG, JPG or WebP picture")
        if (!Files.isRegularFile(source) || Files.size(source) > MaxBackdropBytes) throw IOException("${source.fileName} is missing or larger than ${MaxBackdropBytes / Megabyte} MB")
        val bytes = Files.readAllBytes(source)
        val size = try {
            Data.makeFromBytes(bytes).use { data -> Codec.makeFromData(data).use { it.width to it.height } }
        } catch (e: IllegalArgumentException) {
            throw IOException("${source.fileName} is not a picture Hideout can read", e)
        }
        if (size.first > MaxBackdropSide || size.second > MaxBackdropSide) throw IOException("${source.fileName} is larger than $MaxBackdropSide pixels a side")
        Files.createDirectories(backdrops)
        val target = backdrops.resolve("${sha256Hex(bytes).take(NameDigits)}.$extension")
        if (!Files.isRegularFile(target)) replaceAtomically(target, bytes)
        Files.list(backdrops).use { files -> files.filter { it != target && Owned.matches(it.fileName.toString()) }.toList() }.forEach(Files::deleteIfExists)
        return target.toString()
    }

    private companion object {
        val ImageExtensions = setOf("png", "jpg", "jpeg", "webp")
        val Owned = Regex("[0-9a-f]{16}\\.(png|jpg|jpeg|webp)")
        const val NameDigits = 16
        const val Megabyte = 1_000_000L
        const val MaxBackdropBytes = 40 * Megabyte
        const val MaxBackdropSide = 8192
    }
}

internal fun picturesFolder(): Path = AppDirs.pictures().toPath().resolve("Hideout")

private fun encodePng(image: ImageBitmap): ByteArray =
    Image.makeFromBitmap(image.asSkiaBitmap()).use { picture -> checkNotNull(picture.encodeToData(EncodedImageFormat.PNG)) { "PNG encoding failed" }.use { it.bytes } }

private suspend fun chooseImage(title: String): File? = openFileDialog(title, setOf("png", "jpg", "jpeg", "webp"))

private fun revealInFileManager(file: File) {
    val desktop = Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE_FILE_DIR)
    if (desktop) Desktop.getDesktop().browseFileDirectory(file) else DesktopHandoff().open(file.parentFile)
}
