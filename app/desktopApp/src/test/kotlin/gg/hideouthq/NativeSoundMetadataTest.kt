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
 * Java Sound loads every mixer provider before a sound preview plays, and PortMixerProvider.c builds one PortMixerInfo
 * through JNI for each of the system's mixers. A computer with a sound card's mixer reaches it and one without never
 * does, so a trace can miss it and a native image missing the lookup throws as the preview starts.
 */
class NativeSoundMetadataTest {
    private val bothImages: JsonObject = checkNotNull(
        javaClass.getResource("/META-INF/native-image/gg.hideouthq/resources/reachability-metadata.json"),
    ).readText().let { Json.parseToJsonElement(it).jsonObject }

    private fun jniMethods(type: String): Set<String> = bothImages.getValue("reflection").jsonArray.map { it.jsonObject }
        .filter { it["type"]?.jsonPrimitive?.content == type && it["jniAccessible"]?.jsonPrimitive?.booleanOrNull == true }
        .flatMap { it["methods"]?.jsonArray.orEmpty() }
        .map { method ->
            val parameters = method.jsonObject.getValue("parameterTypes").jsonArray.joinToString(",") { it.jsonPrimitive.content }
            "${method.jsonObject.getValue("name").jsonPrimitive.content}($parameters)"
        }.toSet()

    @Test
    fun bothImagesCanListTheSystemsMixers() {
        assertEquals(
            setOf("<init>(int,java.lang.String,java.lang.String,java.lang.String,java.lang.String)"),
            jniMethods("com.sun.media.sound.PortMixerProvider\$PortMixerInfo"),
        )
    }
}
