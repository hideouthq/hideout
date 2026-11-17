package gg.hideouthq.library

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopFileDropTest {
    @Test fun nativeFileListCarriesOnlySelectionMetadata() {
        val directory = Files.createTempDirectory(Files.createDirectories(Path.of("build", "file-import-tests")), "drop-")
        val file = directory.resolve("skin.zip").toFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val files = localFiles(transfer(listOf(file)))!!
        assertEquals(1, files.size)
        assertEquals(file.absolutePath, files.single().path)
        assertEquals("skin.zip", files.single().name)
        assertEquals(3L, files.single().sizeBytes)
        assertEquals(listOf("skin.zip"), directory.toFile().list()!!.toList())
    }

    @Test fun malformedNativePayloadIsNotPartiallyAccepted() {
        assertNull(localFiles(transfer(listOf("not a file"))))
        assertNull(localFiles(transfer("not a list")))
        assertNull(localFiles(object : Transferable {
            override fun getTransferDataFlavors() = emptyArray<DataFlavor>()
            override fun isDataFlavorSupported(flavor: DataFlavor) = false
            override fun getTransferData(flavor: DataFlavor): Any = throw UnsupportedFlavorException(flavor)
        }))
    }

    private fun transfer(value: Any) = object : Transferable {
        override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
        override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor
        override fun getTransferData(flavor: DataFlavor) = value
    }
}
