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
 * awt_Component.cpp builds every mouse wheel event through JNI, and reads a turn the page could not use (at its top or
 * bottom) back through JNI when Compose hands it to the window. A native image missing either lookup throws on the AWT
 * thread: without the constructor no page scrolls, without getWheelRotation a turn at an edge closes Hideout.
 */
class NativeMouseWheelMetadataTest {
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
    fun aWheelTurnCanBecomeAnEventAndGoBackToTheWindowAtAPagesEdge() {
        assertEquals(
            setOf(
                "<init>(java.awt.Component,int,long,int,int,int,int,int,int,boolean,int,int,int,double)",
                "getWheelRotation()",
            ),
            jniMethods("java.awt.event.MouseWheelEvent"),
        )
    }
}
