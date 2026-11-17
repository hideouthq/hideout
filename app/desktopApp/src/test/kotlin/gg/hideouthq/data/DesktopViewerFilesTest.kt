package gg.hideouthq.data

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopViewerFilesTest {
    @Test
    fun aBackdropIsCopiedInByItsContentReplacesTheOneBeforeAndOnlyCopiesAreRead() = runBlocking {
        val root = Files.createTempDirectory("viewer-files")
        val backdrops = root.resolve("data/backdrops")
        val first = root.resolve("first.png").also { Files.write(it, png(4, 3)) }
        val second = root.resolve("second.JPG").also { Files.write(it, png(5, 2, EncodedImageFormat.JPEG)) }
        var chosen: File? = first.toFile()
        val files = DesktopViewerFiles(backdrops, root.resolve("Pictures/Hideout"), choose = { chosen })

        val copied = checkNotNull(files.importBackdrop("Choose a backdrop picture"))
        assertEquals(backdrops, Path.of(copied).parent)
        assertContentEquals(Files.readAllBytes(first), files.readBackdrop(copied))
        assertEquals(copied, files.importBackdrop("Choose a backdrop picture"), "the same picture again is the same copy")

        chosen = second.toFile()
        val replaced = checkNotNull(files.importBackdrop("Choose a backdrop picture"))
        assertTrue(replaced.endsWith(".jpg"), replaced)
        assertEquals(listOf(Path.of(replaced)), Files.list(backdrops).use { it.toList() }, "the earlier copy is removed")
        assertNull(files.readBackdrop(copied))
        assertNull(files.readBackdrop(first.toString()), "only Hideout's own copies are read")

        chosen = root.resolve("notes.txt").toFile().also { it.writeText("not a picture") }
        assertFailsWith<IOException> { files.importBackdrop("Choose a backdrop picture") }
        chosen = root.resolve("named.png").toFile().also { it.writeText("not a picture either") }
        assertFailsWith<IOException> { files.importBackdrop("Choose a backdrop picture") }
        chosen = root.resolve("huge.png").toFile().also { it.writeBytes(png(9000, 1)) }
        assertFailsWith<IOException> { files.importBackdrop("Choose a backdrop picture") }
        chosen = null
        assertNull(files.importBackdrop("Choose a backdrop picture"), "cancelled")
        assertEquals(listOf(Path.of(replaced)), Files.list(backdrops).use { it.toList() })
    }

    private fun png(width: Int, height: Int, format: EncodedImageFormat = EncodedImageFormat.PNG): ByteArray =
        Surface.makeRasterN32Premul(width, height).use { surface -> surface.makeImageSnapshot().use { checkNotNull(it.encodeToData(format)).bytes } }

    @Test
    fun aPictureIsSavedWholeAsPngUnderASuggestionThatReplacesNothing() = runBlocking {
        val root = Files.createTempDirectory("viewer-files")
        val pictures = root.resolve("Pictures/Hideout")
        val suggested = mutableListOf<File>()
        val files = DesktopViewerFiles(root.resolve("backdrops"), pictures, saveAs = { _, file -> suggested += file; file })
        val image = ImageBitmap(8, 6)

        val first = checkNotNull(files.savePicture(image, "Save image", "lilith-default.png"))
        val second = checkNotNull(files.savePicture(image, "Save image", "lilith-default.png"))
        assertEquals(listOf("lilith-default.png", "lilith-default-2.png"), suggested.map { it.name })
        assertEquals(listOf(first, second).map { Path.of(it).name }, listOf("lilith-default.png", "lilith-default-2.png"))
        Image.makeFromEncoded(Files.readAllBytes(Path.of(second))).use { assertEquals(8 to 6, it.width to it.height) }
        assertTrue(Files.list(pictures).use { list -> list.allMatch { it.name.endsWith(".png") } }, "no temporary file is left behind")

        val named = DesktopViewerFiles(root.resolve("backdrops"), pictures, saveAs = { _, _ -> pictures.resolve("poster").toFile() })
        assertEquals(pictures.resolve("poster.png").toString(), named.savePicture(image, "Save image", "lilith.png"))
        assertNull(DesktopViewerFiles(root.resolve("backdrops"), pictures, saveAs = { _, _ -> null }).savePicture(image, "Save image", "lilith.png"))
    }

    @Test
    fun theSaveDialogOpensInHideoutsFolderButNeverMakesTheUsersPicturesFolder() = runBlocking {
        val root = Files.createTempDirectory(Files.createDirectories(Path.of("build", "viewer-tests")), "pictures").toRealPath()
        val pictures = root.resolve("Bilder/Hideout")
        val image = ImageBitmap(8, 6)
        fun files(saveAs: SaveAs) = DesktopViewerFiles(root.resolve("backdrops"), pictures, saveAs = saveAs)

        assertNull(files { _, suggested -> null.also { assertEquals(pictures.resolve("lilith.png").toFile(), suggested) } }.savePicture(image, "Save image", "lilith.png"))
        assertFalse(Files.exists(root.resolve("Bilder")), "cancelled, with the Pictures folder not made either")
        val elsewhere = Files.createDirectories(root.resolve("Desktop")).resolve("lilith.png")
        assertEquals(elsewhere.toString(), files { _, _ -> elsewhere.toFile() }.savePicture(image, "Save image", "lilith.png"))
        assertFalse(Files.exists(root.resolve("Bilder")), "saved elsewhere")
        assertEquals(pictures.resolve("lilith.png").toString(), files { _, suggested -> suggested }.savePicture(image, "Save image", "lilith.png"))
        assertNull(files { _, _ -> null }.savePicture(image, "Save image", "lilith.png"))
        assertEquals(listOf("lilith.png"), Files.list(pictures).use { list -> list.map { it.name }.toList() }, "a folder that was there stays")

        val existing = Files.createDirectories(root.resolve("Pictures")).resolve("Hideout")
        assertNull(DesktopViewerFiles(root.resolve("backdrops"), existing, saveAs = { _, _ -> null }).savePicture(image, "Save image", "lilith.png"))
        assertTrue(Files.isDirectory(existing), "Hideout's folder is made inside an existing Pictures folder, so the dialog opens in it")
    }

    @Test
    fun aTypedNameThatTheExtensionMakesAnExistingFileIsOfferedAgainInFull() = runBlocking {
        val root = Files.createTempDirectory("viewer-files")
        val pictures = root.resolve("Pictures/Hideout").also { Files.createDirectories(it) }
        val poster = pictures.resolve("poster.png").also { Files.write(it, Original) }
        val image = ImageBitmap(8, 6)
        val targets = ArrayDeque<File?>()
        val offered = mutableListOf<File>()
        val files = DesktopViewerFiles(root.resolve("backdrops"), pictures, saveAs = { _, suggested -> offered += suggested; targets.removeFirst() })

        targets += listOf(pictures.resolve("poster").toFile(), null)
        assertNull(files.savePicture(image, "Save image", "lilith.png"), "typed without the extension, then cancelled")
        assertEquals(poster.toFile(), offered.last(), "the dialog asks about the .png file that would be written")
        assertContentEquals(Original, Files.readAllBytes(poster))

        targets += listOf(pictures.resolve("poster").toFile(), poster.toFile())
        assertEquals(poster.toString(), files.savePicture(image, "Save image", "lilith.png"), "the dialog was told the full name and asked")
        assertFalse(Original.contentEquals(Files.readAllBytes(poster)))
    }

    @Test
    fun aSlowRevealLeavesTheSwingThreadFree() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val files = DesktopViewerFiles(Path.of("unused-backdrops"), Path.of("unused-pictures"), show = {
            entered.complete(Unit)
            check(release.await(5, TimeUnit.SECONDS))
        })
        val opening = async(Dispatchers.Swing) { files.reveal("/Pictures/日本語 image.png") }
        try {
            entered.await()
            withTimeout(1_000) {
                withContext(Dispatchers.Swing) {
                    assertTrue(SwingUtilities.isEventDispatchThread())
                }
            }
            assertTrue(opening.isActive, "The UI responds before the slow handoff finishes")
        } finally {
            release.countDown()
        }
        opening.await()
    }

    private companion object {
        val Original = byteArrayOf(1, 2, 3)
    }
}
