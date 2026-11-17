package gg.hideouthq.core.analytics

import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.data.fake.FakeCustomGamesFeed
import kotlinx.coroutines.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class PostHogTransportTest {
    @Test fun aRequestTimeoutDoesNotKillDeliveryOfTheNextEvent() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val delivered = CompletableDeferred<JsonObject>()
        val client = HttpClient(MockEngine { request ->
            if (!entered.isCompleted) {
                entered.complete(Unit)
                delay(60_000)
            }
            delivered.complete(Json.parseToJsonElement((request.body as TextContent).text).jsonObject)
            respond("{}", HttpStatusCode.OK)
        })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val analytics = UsageAnalytics(MemoryStorage(), PostHogTransport(client, "1.2.3", "linux", "phc_test"), scope)
        try {
            analytics.load(); analytics.choose(true)
            withTimeout(5_000) { entered.await() }
            analytics.begin(UsageOperation.PreviewLoad).finish(UsageOutcome.Completed, UsageStage.Preview)
            val body = withTimeout(10_000) { delivered.await() }
            assertEquals("preview_load_result", body.getValue("event").jsonPrimitive.content)
        } finally { analytics.close(); scope.cancel(); client.close() }
    }

    @Test fun everyEventHasOnlyTheAllowedFieldsAndPrivacyControls() = runTest {
        val requests = mutableListOf<JsonObject>()
        val client = HttpClient(MockEngine { request ->
            assertEquals("https://eu.i.posthog.com/i/v0/e/", request.url.toString())
            requests += Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            respond("{}", HttpStatusCode.OK)
        })
        try {
            val transport = PostHogTransport(client, "1.2.3", "linux", "phc_test")
            transport.send("random-id", UsageEvent.AppActive)
            for (operation in UsageOperation.entries) {
                transport.send("random-id", UsageEvent.Started(operation))
                transport.send("random-id", UsageEvent.Result(operation, UsageOutcome.Failed, DurationBucket.UnderMinute, UsageStage.Download, failure = UsageFailure.Network))
            }
            val mode = PublicUsageMode.from(FakeCustomGamesFeed.SampleMode)!!
            transport.send("random-id", UsageEvent.PlayStarted(mode, UsagePlayRole.Host))
            transport.send("random-id", UsageEvent.PlayTime(mode, UsagePlayRole.Guest, PlayedMinutes.Five))
            for (body in requests) {
                assertEquals(setOf("api_key", "distinct_id", "event", "properties"), body.keys)
                assertEquals("phc_test", body["api_key"]?.jsonPrimitive?.content)
                assertEquals("random-id", body["distinct_id"]?.jsonPrimitive?.content)
                val properties = body.getValue("properties").jsonObject
                val common = setOf("app_version", "os", "\$process_person_profile", "\$geoip_disable", "\$ip")
                val event = body.getValue("event").jsonPrimitive.content
                val eventFields = when {
                    event == "custom_game_play_started" -> setOf("mode_id", "mode_name", "role")
                    event == "custom_game_play_time" -> setOf("mode_id", "mode_name", "role", "play_minutes")
                    event.endsWith("_result") -> setOf("outcome", "duration_bucket", "stage", "failure")
                    else -> emptySet()
                }
                assertEquals(common + eventFields, properties.keys)
                if (event.startsWith("custom_game_play_")) {
                    assertEquals(mode.id, properties.getValue("mode_id").jsonPrimitive.content)
                    assertEquals(mode.name, properties.getValue("mode_name").jsonPrimitive.content)
                    assertEquals(if (event.endsWith("started")) "host" else "guest", properties.getValue("role").jsonPrimitive.content)
                }
                if (event == "custom_game_play_time") assertEquals(JsonPrimitive(5), properties["play_minutes"])
                assertEquals(JsonPrimitive(false), properties["\$process_person_profile"])
                assertEquals(JsonPrimitive(true), properties["\$geoip_disable"])
                assertEquals(JsonNull, properties["\$ip"])
            }
        } finally { client.close() }
    }
}
