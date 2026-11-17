package gg.hideouthq.core.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.platform.asAwtTransferable
import androidx.compose.ui.platform.awtClipboard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import java.awt.datatransfer.ClipboardOwner
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class PatientClipboardTest {
    @Test
    fun aClipboardFreeFromTheStartTakesTheTextAtOnce() = runTest {
        val board = HeldBoard(heldFor = 0)
        assertTrue(board.copyText("code"))
        assertEquals("code", board.text)
        assertEquals(0, currentTime)
    }

    @Test
    fun aClipboardAnotherProgramLetsGoOfTakesTheText() = runTest {
        val board = HeldBoard(heldFor = 2)
        assertTrue(board.copyText("code"))
        assertEquals("code", board.text)
    }

    @Test
    fun aClipboardNeverLetGoOfSaysItFailedWithinASecond() = runTest {
        val board = HeldBoard(heldFor = Int.MAX_VALUE)
        assertFalse(board.copyText("code"))
        assertNull(board.text)
        assertTrue(currentTime <= 1_000, "waited $currentTime ms")
    }

    @Test
    fun aHeldWriteFromComposeDoesNothingAndDoesNotThrow() = runTest {
        val board = HeldBoard(heldFor = Int.MAX_VALUE)
        board.patient().setClipEntry(ClipEntry(StringSelection("code")))
        assertNull(board.text)
    }

    @Test
    fun aReadDuringAHoldFindsNoEntry() = runTest {
        val board = HeldBoard(heldFor = Int.MAX_VALUE, text = "already there")
        assertNull(board.patient().getClipEntry())
        assertTrue(currentTime <= 1_000, "waited $currentTime ms")
    }

    @Test
    fun aReadWaitsForAClipboardAnotherProgramLetsGoOf() = runTest {
        val board = HeldBoard(heldFor = 3, text = "already there")
        assertEquals("already there", board.patient().getClipEntry().text())
    }

    @Test
    fun whatComposeAsksTheNativeClipboardDirectlyAnswersEmptyDuringAHold() {
        val native = HeldBoard(heldFor = Int.MAX_VALUE, text = "already there").patient().awtClipboard!!
        assertEquals(0, native.availableDataFlavors.size)
        assertFalse(native.isDataFlavorAvailable(DataFlavor.stringFlavor))
        assertNull(native.getContents(null))
    }

    @Test
    fun theNativeClipboardAnswersOnceItIsFree() {
        val native = HeldBoard(heldFor = 0, text = "already there").patient().awtClipboard!!
        assertTrue(native.isDataFlavorAvailable(DataFlavor.stringFlavor))
        assertNotNull(native.getContents(null))
    }

    @Test
    fun aCancelledReadOrWriteIsNotReportedAsAHeldClipboard() = runTest {
        val board = PatientClipboard(object : Clipboard by HeldBoard(heldFor = 0) {
            override suspend fun getClipEntry(): ClipEntry? = throw CancellationException("left the page")
            override suspend fun setClipEntry(clipEntry: ClipEntry?) = throw CancellationException("left the page")
        })
        assertFailsWith<CancellationException> { board.getClipEntry() }
        assertFailsWith<CancellationException> { board.copyText("code") }
    }

    @Test
    fun missingNativeMetadataStaysLoud() = runTest {
        val board = PatientClipboard(object : Clipboard by HeldBoard(heldFor = 0) {
            override suspend fun setClipEntry(clipEntry: ClipEntry?) = throw NoSuchMethodError("lostSelectionOwnershipImpl")
        })
        assertFailsWith<NoSuchMethodError> { board.copyText("code") }
    }

    @Test
    fun wrappingTwiceKeepsOneRetryLoop() {
        val once = HeldBoard(heldFor = 0).patient()
        assertSame(once, once.patient())
    }

    @Test
    fun theAppHasNoPopupThatDropsThePatientClipboard() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "project.yaml").exists() }
        val raw = listOf("app/shared/src", "app/desktopApp/src/main").flatMap { File(root, it).walkTopDown().filter { file -> file.extension == "kt" } }
            .filter { file -> file.name != "PatientClipboard.kt" && file.readLines().any { it == "import androidx.compose.ui.window.Popup" } }
        assertEquals(emptyList(), raw.map { it.relativeTo(root).path })
    }

    private fun ClipEntry?.text(): String? = this?.asAwtTransferable?.getTransferData(DataFlavor.stringFlavor) as String?
}

/** Refuses its first [heldFor] calls the way AWT does while another program has the Windows clipboard. */
@OptIn(ExperimentalComposeUiApi::class)
private class HeldBoard(private var heldFor: Int, var text: String? = null) : Clipboard {
    private fun refuseWhileHeld() = check(heldFor-- <= 0) { "cannot open system clipboard" }

    override suspend fun getClipEntry(): ClipEntry? {
        refuseWhileHeld()
        return text?.let { ClipEntry(StringSelection(it)) }
    }

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        refuseWhileHeld()
        text = clipEntry?.asAwtTransferable?.getTransferData(DataFlavor.stringFlavor) as String?
    }

    override val nativeClipboard: NativeClipboard = object : java.awt.datatransfer.Clipboard("held") {
        override fun getContents(requestor: Any?): Transferable? {
            refuseWhileHeld()
            return text?.let(::StringSelection)
        }

        override fun setContents(contents: Transferable, owner: ClipboardOwner?) = refuseWhileHeld()
    }
}
