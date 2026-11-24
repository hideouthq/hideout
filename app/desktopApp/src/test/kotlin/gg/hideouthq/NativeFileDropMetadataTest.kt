package gg.hideouthq

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * awt_DnDDT.cpp finds the drop target's peer by name each time a drag enters the window. A native image without these
 * answers with a pending NoClassDefFoundError the native code clears, so the player sees only a no-drop cursor.
 */
class NativeFileDropMetadataTest {
    private val windows: JsonObject = checkNotNull(
        javaClass.getResource("/META-INF/native-image/gg.hideouthq/windows/reachability-metadata.json"),
    ).readText().let { Json.parseToJsonElement(it).jsonObject }

    private fun jniMethods(type: String): Set<String> = windows.getValue("reflection").jsonArray.map { it.jsonObject }
        .filter { (it["type"] as? JsonPrimitive)?.content == type && it["jniAccessible"]?.jsonPrimitive?.booleanOrNull == true }
        .flatMap { it["methods"]?.jsonArray.orEmpty() }
        .map { method ->
            val parameters = method.jsonObject.getValue("parameterTypes").jsonArray.joinToString(",") { it.jsonPrimitive.content }
            "${method.jsonObject.getValue("name").jsonPrimitive.content}($parameters)"
        }.toSet()

    @Test
    fun aDragEnteringTheWindowCanCreateItsPeer() {
        assertEquals(
            setOf("getWDropTargetContextPeer()", "getFileStream(java.lang.String,long)", "getIStream(long)"),
            jniMethods("sun.awt.windows.WDropTargetContextPeer"),
        )
    }

    @Test
    fun theNativesFindEveryStepOfADragOnThePeersSuperclass() {
        val position = "java.awt.Component,int,int,int,int,long[],long"
        assertEquals(
            setOf("handleEnterMessage($position)", "handleMotionMessage($position)", "handleDropMessage($position)", "handleExitMessage(java.awt.Component,long)"),
            jniMethods("sun.awt.dnd.SunDropTargetContextPeer"),
        )
    }
}
