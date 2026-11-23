package gg.hideouthq.social

import gg.hideouthq.core.customgames.social.RelayLatency
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.IOException
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

internal class HelperRelayLatency(private val helper: Path) : RelayLatency {
    override suspend fun measure(): Map<Int, Int>? = withTimeoutOrNull(Limit) {
        runInterruptible(Dispatchers.IO) {
            val process = try {
                ProcessBuilder(helper.toString(), "regions").redirectError(ProcessBuilder.Redirect.DISCARD).start()
            } catch (e: IOException) {
                return@runInterruptible null
            }
            try {
                process.outputStream.close()
                process.inputStream.bufferedReader().lineSequence().firstNotNullOfOrNull(::regionsIn)
            } finally {
                process.destroy()
            }
        }
    }

    private companion object {
        val Limit = 15.seconds
    }
}

internal fun regionsIn(line: String): Map<Int, Int>? {
    val event = try {
        Json.parseToJsonElement(line) as? JsonObject
    } catch (e: IllegalArgumentException) {
        null
    }
    if ((event?.get("type") as? JsonPrimitive)?.content != "regions") return null
    val regions = event["regions"] as? JsonArray ?: return null
    return regions.mapNotNull { region ->
        val fields = region as? JsonObject ?: return@mapNotNull null
        val id = (fields["id"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
        val ms = (fields["ms"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
        id to ms
    }.toMap()
}
