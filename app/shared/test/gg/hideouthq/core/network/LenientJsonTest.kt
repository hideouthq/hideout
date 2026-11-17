package gg.hideouthq.core.network

import gg.hideouthq.core.data.DataError
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LenientJsonTest {
    @Serializable
    private class Sample(val count: Int = 0, val obsolete: Boolean = false)

    @Test
    fun warningsPrintedBeforeTheJsonAreDropped() {
        val body = "<br />\n<b>Warning</b>:  Undefined index in /var/www/api.php on line 12<br />\n{\"count\": 3}"
        assertEquals(3, decodeLenient(Sample.serializer(), body).count)
    }

    @Test
    fun quotedValuesAndNullsAreRead() {
        val sample = decodeLenient(Sample.serializer(), """{"count": "4", "obsolete": "true", "extra": 1}""")
        assertEquals(4, sample.count)
        assertEquals(true, sample.obsolete)
        assertEquals(0, decodeLenient(Sample.serializer(), """{"count": null}""").count)
    }

    @Test
    fun aBodyThatIsNotJsonIsUnavailable() {
        val failure = assertFailsWith<RemoteException> { decodeLenient(Sample.serializer(), "<html>Bad gateway</html>") }
        assertEquals(DataError.Unavailable, failure.error)
    }

    @Test
    fun listsMayArriveAsObjectsKeyedById() {
        val asObject = JsonObject(mapOf("1835803" to JsonPrimitive("a"), "1835804" to JsonPrimitive("b")))
        assertEquals(listOf("a", "b"), asObject.asList().map { it.asStringOrNull() })
        assertEquals(listOf("a"), JsonArray(listOf(JsonPrimitive("a"))).asList().map { it.asStringOrNull() })
        assertEquals(emptyList(), JsonPrimitive(false).asList())
        assertEquals(true, JsonPrimitive("true").asBooleanOrNull())
        assertEquals(12L, JsonPrimitive("12").asLongOrNull())
    }
}
