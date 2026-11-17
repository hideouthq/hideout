package gg.hideouthq

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A native image answers a JNI lookup it has no metadata for with NoSuchMethodError, and copying then crashes the app.
 * These read the hand-written files alone, which no retrace writes. The names are what JDK 25's natives ask for.
 */
class NativeClipboardMetadataTest {
    private fun file(folder: String): JsonObject {
        val path = "/META-INF/native-image/gg.hideouthq/$folder/reachability-metadata.json"
        val text = checkNotNull(javaClass.getResource(path)) { "no $path on the classpath" }.readText()
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun JsonObject.reflection(type: String): List<JsonObject> =
        getValue("reflection").jsonArray.map { it.jsonObject }.filter { (it["type"] as? JsonPrimitive)?.content == type }

    private fun JsonObject.jniMethods(type: String): Set<String> =
        reflection(type).filter { it["jniAccessible"]?.jsonPrimitive?.booleanOrNull == true }.flatMap { entry ->
            entry["methods"]?.jsonArray.orEmpty().map(JsonElement::signature)
        }.toSet()

    private fun JsonObject.methods(type: String): Set<String> =
        reflection(type).flatMap { it["methods"]?.jsonArray.orEmpty().map(JsonElement::signature) }.toSet()

    private fun JsonObject.jniFields(type: String): Set<String> =
        reflection(type).filter { it["jniAccessible"]?.jsonPrimitive?.booleanOrNull == true }.flatMap { entry ->
            entry["fields"]?.jsonArray.orEmpty().map { it.jsonObject.getValue("name").jsonPrimitive.content }
        }.toSet()

    @Test
    fun windowsClipboardNativesFindWhatTheyCallBack() {
        val windows = file("windows")
        assertEquals(setOf("lostSelectionOwnershipImpl()", "handleContentsChanged()"), windows.jniMethods("sun.awt.windows.WClipboard"))
        assertEquals(setOf("getInstance()", "concatData(java.lang.Object,java.lang.Object)"), windows.jniMethods("sun.awt.datatransfer.DataTransferer"))
    }

    @Test
    fun windowsClipboardNativesCanThrowWhatTheyThrow() {
        val windows = file("windows")
        for (type in listOf("java.lang.IllegalStateException", "java.io.IOException", "java.lang.OutOfMemoryError")) {
            assertEquals(setOf("<init>(java.lang.String)"), windows.jniMethods(type), type)
        }
    }

    @Test
    fun aDroppedFileListCanFindItsStringClass() {
        assertTrue(file("windows").reflection("java.lang.String").any { it["jniAccessible"]?.jsonPrimitive?.booleanOrNull == true })
    }

    @Test
    fun bothImagesKeepWhatDataFlavorsLoadByName() {
        val both = file("resources")
        val named = listOf(
            "java.awt.Image", "java.io.InputStream", "java.io.Reader", "java.lang.String", "java.nio.ByteBuffer",
            "java.nio.CharBuffer", "java.util.List", "byte[]", "char[]",
        )
        for (type in named) assertTrue(both.reflection(type).isNotEmpty(), type)
        assertTrue(file("windows").reflection("java.net.URL").isNotEmpty(), "Windows' flavor map names java.net.URL")
    }

    @Test
    fun aPasteFromAnotherProgramCanSortItsFlavors() {
        assertEquals(setOf("<init>(java.lang.Object)", "get()"), file("resources").methods("java.rmi.MarshalledObject"))
        assertTrue(file("resources").reflection("java.rmi.Remote").isNotEmpty())
    }

    @Test
    fun x11NativesFindWhatTheyCallBackOnTheClipboardsPaths() {
        val linux = file("linux")
        assertEquals(setOf("getXRootWindow()"), linux.jniMethods("sun.awt.X11.XRootWindow"))
        assertEquals(
            setOf("grabPixels(int)", "warningOccurred(int)", "warningWithMessage(java.lang.String)", "writeMetadata()", "writeOutputData(byte[],int,int)"),
            linux.jniMethods("com.sun.imageio.plugins.jpeg.JPEGImageWriter"),
        )
        assertEquals(setOf("lengths", "values"), linux.jniFields("javax.imageio.plugins.jpeg.JPEGHuffmanTable"))
        assertEquals(setOf("qTable"), linux.jniFields("javax.imageio.plugins.jpeg.JPEGQTable"))
    }

    @Test
    fun theFlavorMapIsInTheFileBothImagesRead() {
        val flavorMap = "sun/datatransfer/resources/flavormap.properties"
        fun has(folder: String) = file(folder).getValue("resources").jsonArray.any { it.jsonObject["glob"]?.jsonPrimitive?.content == flavorMap }
        assertTrue(has("resources"))
        assertTrue(!has("windows") && !has("linux"))
    }
}

private val JsonElement.signature: String
    get() = jsonObject.let { method ->
        val parameters = method.getValue("parameterTypes").jsonArray.joinToString(",") { it.jsonPrimitive.content }
        "${method.getValue("name").jsonPrimitive.content}($parameters)"
    }
