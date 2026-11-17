package gg.hideouthq.viewer

import gg.hideouthq.data.preview.PreviewCache
import gg.hideouthq.data.preview.PreviewHost
import gg.hideouthq.feature.moddetail.viewer.PreviewModel
import gg.hideouthq.preview.IndexEntry
import gg.hideouthq.preview.PreviewJson
import gg.hideouthq.preview.decodeBundleManifest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.io.path.Path

internal class StoreJob(val json: JsonObject) {
    val bundles = json.getValue("bundles").jsonArray.map { it.jsonPrimitive.content }
    val out = File(text("out"))

    private val entries = PreviewJson.parseToJsonElement(File(text("store"), "index.json").readText()).jsonObject.getValue("entries").jsonArray
        .map { PreviewJson.decodeFromJsonElement(IndexEntry.serializer(), it) }
        .associateBy { it.bundleId }
    private val cache = PreviewCache(text("host"), Path(text("cache")), "Hideout scene harness", PreviewHost.built?.key)

    suspend fun scene(id: String, info: GlInfo): Pair<PreviewModel, SceneContent> {
        val entry = checkNotNull(entries[id]) { "The store has no bundle $id" }
        val model = PreviewModel(entry, decodeBundleManifest(cache.manifestText(entry)))
        return model to cache.sceneFiles(model).content(info)
    }

    fun text(key: String) = json.getValue(key).jsonPrimitive.content

    companion object {
        fun from(property: String): StoreJob {
            val path = System.getProperty(property)
            assumeTrue("$property names no job", !path.isNullOrEmpty())
            return StoreJob(PreviewJson.parseToJsonElement(File(path!!).readText()).jsonObject)
        }
    }
}

internal val PreviewModel.stillAzimuth: Float
    get() = (fallbackPicture ?: manifest.pictures.firstOrNull())?.azimuth?.toFloat() ?: -70f
