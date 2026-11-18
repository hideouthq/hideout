package gg.hideouthq

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * On GNOME the JDK picks GTK's look and feel, which looks up the desktop's font. When that font is not among those
 * already loaded, fontpath.c asks through JNI whether the display is local, and a native image missing either lookup
 * throws before the window shows. No trace on a computer whose fonts are already loaded reaches it.
 */
class NativeFontPathMetadataTest {
    private val linux: JsonObject = checkNotNull(
        javaClass.getResource("/META-INF/native-image/gg.hideouthq/linux/reachability-metadata.json"),
    ).readText().let { Json.parseToJsonElement(it).jsonObject }

    private fun jniMethods(type: String): Set<String> = linux.getValue("reflection").jsonArray.map { it.jsonObject }
        .filter { it["type"]?.jsonPrimitive?.content == type && it["jniAccessible"]?.jsonPrimitive?.booleanOrNull == true }
        .flatMap { it["methods"]?.jsonArray.orEmpty() }
        .map { method ->
            val parameters = method.jsonObject.getValue("parameterTypes").jsonArray.joinToString(",") { it.jsonPrimitive.content }
            "${method.jsonObject.getValue("name").jsonPrimitive.content}($parameters)"
        }.toSet()

    @Test
    fun theFontPathCanAskWhetherTheDisplayIsLocal() {
        assertEquals(setOf("getLocalGraphicsEnvironment()"), jniMethods("java.awt.GraphicsEnvironment"))
        assertEquals(setOf("isDisplayLocal()"), jniMethods("sun.java2d.SunGraphicsEnvironment"))
    }
}
