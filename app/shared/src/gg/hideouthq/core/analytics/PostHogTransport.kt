package gg.hideouthq.core.analytics

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.header
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.withTimeoutOrNull

class PostHogTransport(private val client: HttpClient, private val version: String, private val os: String, private val projectKey: String) : UsageTransport {
    override suspend fun send(installation: String, event: UsageEvent) {
        withTimeoutOrNull(5_000) {
            client.post("https://eu.i.posthog.com/i/v0/e/") {
                header("Content-Type", "application/json")
                setBody(payload(installation, event).toString())
            }
        }
    }

    private fun payload(installation: String, event: UsageEvent) = buildJsonObject {
        put("api_key", projectKey)
        put("distinct_id", installation)
        put("event", when (event) {
            UsageEvent.AppActive -> "app_active"
            is UsageEvent.Started -> "${event.operation.wire}_started"
            is UsageEvent.Result -> "${event.operation.wire}_result"
        })
        put("properties", buildJsonObject {
            put("app_version", version)
            put("os", os)
            put("\$process_person_profile", false)
            put("\$geoip_disable", true)
            put("\$ip", JsonNull)
            when (event) {
                UsageEvent.AppActive -> Unit
                is UsageEvent.Started -> event.source?.let { put("source", it.wire) }
                is UsageEvent.Result -> {
                    put("outcome", event.outcome.wire)
                    put("duration_bucket", event.duration.wire)
                    put("stage", event.stage.wire)
                    event.failure?.let { put("failure", it.wire) }
                    event.source?.let { put("source", it.wire) }
                }
            }
        })
    }
}
