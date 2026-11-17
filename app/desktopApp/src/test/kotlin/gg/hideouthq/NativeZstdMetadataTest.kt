package gg.hideouthq

import com.github.luben.zstd.ZstdInputStreamNoFinalizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * zstd-jni's jni_inputstream_zstd.c looks up the stream's read positions through JNI the first time a ZstdInputStream
 * starts. Every 3D preview's model and every match's metadata from the Steam cache stream through one, so a native image
 * missing either field throws NoSuchFieldError and shows no preview at all.
 */
class NativeZstdMetadataTest {
    private val bothImages: JsonObject = checkNotNull(
        javaClass.getResource("/META-INF/native-image/gg.hideouthq/resources/reachability-metadata.json"),
    ).readText().let { Json.parseToJsonElement(it).jsonObject }

    private fun jniFields(type: String): Set<String> = bothImages.getValue("reflection").jsonArray.map { it.jsonObject }
        .filter { it["type"]?.jsonPrimitive?.content == type && it["jniAccessible"]?.jsonPrimitive?.booleanOrNull == true }
        .flatMap { it["fields"]?.jsonArray.orEmpty() }
        .map { it.jsonObject.getValue("name").jsonPrimitive.content }
        .toSet()

    @Test
    fun bothImagesCanStartAZstdStream() {
        val stream = ZstdInputStreamNoFinalizer::class.java
        val named = jniFields(stream.name)
        assertEquals(setOf("srcPos", "dstPos"), named)
        assertEquals(named.associateWith { Long::class.javaPrimitiveType }, named.associateWith { stream.getDeclaredField(it).type })
    }
}
