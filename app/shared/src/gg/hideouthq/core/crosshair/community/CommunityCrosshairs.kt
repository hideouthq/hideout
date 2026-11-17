package gg.hideouthq.core.crosshair.community

import gg.hideouthq.core.crosshair.Crosshair
import gg.hideouthq.core.crosshair.CrosshairSetting
import gg.hideouthq.core.crosshair.ReadCrosshair
import gg.hideouthq.core.crosshair.crosshairOf
import gg.hideouthq.core.data.CacheStorage
import gg.hideouthq.core.data.DataError
import gg.hideouthq.core.data.Loadable
import gg.hideouthq.core.network.LenientJson
import gg.hideouthq.core.network.RemoteException
import gg.hideouthq.core.network.decodeLenient
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource

data class CommunityCrosshair(
    val id: String,
    val name: String,
    val creator: String?,
    val createdAt: Instant,
    val likes: Int,
    val crosshair: Crosshair,
)

/** Every community crosshair, whole: the source serves no paging, sorting or search, so [BrowseQuery] does all of it. */
interface CommunityCrosshairs {
    /** The list, from the copy kept last time at once, then as fetched. A failed fetch keeps what is shown. */
    val crosshairs: StateFlow<Loadable<List<CommunityCrosshair>>>

    fun refresh()
}

class DmmCrosshairs(
    private val client: HttpClient,
    private val storage: CacheStorage,
    private val scope: CoroutineScope,
    private val time: TimeSource = TimeSource.Monotonic,
) : CommunityCrosshairs {
    private val shown = MutableStateFlow<Loadable<List<CommunityCrosshair>>>(Loadable.Loading)
    override val crosshairs: StateFlow<Loadable<List<CommunityCrosshair>>> = shown.asStateFlow()

    private var read = false
    private var etag: String? = null
    private var fetching: Job? = null
    private var checked: TimeMark? = null

    override fun refresh() {
        if (fetching?.isActive == true || checked?.let { it.elapsedNow() < Recheck } == true) return
        fetching = scope.launch {
            if (!read) {
                read = true
                load()?.let { saved ->
                    val kept = parsed(saved.body) ?: return@let
                    shown.value = Loadable.Ready(kept)
                    etag = saved.etag
                }
            }
            fetch()
        }
    }

    private suspend fun fetch() {
        try {
            val response = client.get(Url) { etag?.let { header(HttpHeaders.IfNoneMatch, it) } }
            when {
                response.status == HttpStatusCode.NotModified && shown.value is Loadable.Ready -> checked = time.markNow()
                response.status.isSuccess() -> {
                    val body = response.bodyAsText()
                    shown.value = Loadable.Ready(parsed(body) ?: return failed(DataError.Unavailable))
                    etag = response.headers[HttpHeaders.ETag]
                    storage.write(FileName, LenientJson.encodeToString(SavedCrosshairs.serializer(), SavedCrosshairs(etag, body)))
                    checked = time.markNow()
                }
                else -> failed(DataError.Unavailable)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(DataError.Offline)
        }
    }

    private fun failed(error: DataError) {
        if (shown.value !is Loadable.Ready) shown.value = Loadable.Failed(error)
    }

    private fun parsed(body: String): List<CommunityCrosshair>? = try {
        parseDmmCrosshairs(body)
    } catch (e: RemoteException) {
        null
    }

    private suspend fun load(): SavedCrosshairs? = try {
        storage.read(FileName)?.let { LenientJson.decodeFromString(SavedCrosshairs.serializer(), it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    @Serializable
    private class SavedCrosshairs(val etag: String? = null, val body: String)

    private companion object {
        const val Url = "https://api.deadlockmods.app/api/v2/crosshairs"
        const val FileName = "community-crosshairs.json"
        val Recheck = 6.hours
    }
}

internal fun parseDmmCrosshairs(text: String): List<CommunityCrosshair> =
    decodeLenient(JsonArray.serializer(), text).mapNotNull(::communityCrosshair).distinctBy { it.id }

private fun communityCrosshair(element: JsonElement): CommunityCrosshair? {
    val row = try {
        LenientJson.decodeFromJsonElement(RowDto.serializer(), element)
    } catch (e: IllegalArgumentException) {
        return null
    }
    return CommunityCrosshair(
        id = row.id?.takeIf { it.isNotBlank() } ?: return null,
        name = row.name?.trim()?.takeIf { it.isNotEmpty() } ?: return null,
        creator = row.userName?.trim()?.takeIf { it.isNotEmpty() },
        createdAt = row.createdAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null,
        likes = row.likes ?: 0,
        crosshair = row.config?.crosshair() ?: return null,
    )
}

internal fun dmmConfig(json: JsonObject): Crosshair? = try {
    LenientJson.decodeFromJsonElement(ConfigDto.serializer(), json).crosshair()
} catch (e: IllegalArgumentException) {
    null
}

internal fun dmmConfigLines(json: JsonObject): List<String> = try {
    LenientJson.decodeFromJsonElement(ConfigDto.serializer(), json).read()?.let { read -> read.set.map { "${it.convar} \"${it.format(read.crosshair)}\"" } }.orEmpty()
} catch (e: IllegalArgumentException) {
    emptyList()
}

@Serializable
private data class RowDto(
    val id: String? = null,
    val userName: String? = null,
    val name: String? = null,
    val config: ConfigDto? = null,
    val likes: Int? = null,
    val createdAt: String? = null,
)

@Serializable
private data class ConfigDto(
    val gap: Double? = null,
    val width: Double? = null,
    val height: Double? = null,
    val pipOpacity: Double? = null,
    val dotOpacity: Double? = null,
    val dotOutlineOpacity: Double? = null,
    val color: ColorDto? = null,
    val pipBorder: Boolean? = null,
    val pipGapStatic: Boolean? = null,
) {
    fun crosshair(): Crosshair? = read()?.crosshair

    fun read(): ReadCrosshair? {
        val settings = mapOf(
            CrosshairSetting.ColorR to color?.r, CrosshairSetting.ColorG to color?.g, CrosshairSetting.ColorB to color?.b,
            CrosshairSetting.OutlineBorder to pipBorder?.let { if (it) 1 else 0 }, CrosshairSetting.GapStatic to pipGapStatic, CrosshairSetting.Opacity to pipOpacity,
            CrosshairSetting.Width to width, CrosshairSetting.Height to height, CrosshairSetting.Gap to gap,
            CrosshairSetting.DotOpacity to dotOpacity, CrosshairSetting.DotOutlineOpacity to dotOutlineOpacity,
        ).mapNotNull { (convar, value) -> value?.let { convar.suffix to "$it" } }.toMap()
        return crosshairOf(settings).takeIf { read -> read.set.isNotEmpty() && read.set.size == settings.size }
    }
}

@Serializable
private data class ColorDto(val r: Double? = null, val g: Double? = null, val b: Double? = null)
