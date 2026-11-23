package gg.hideouthq.social

import gg.hideouthq.data.signedMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** resources/social/invite-signing.json is a copy of hideout-site's worker/test/fixtures/invite-signing.json, which the Worker's own tests verify. */
class SiteSigningTest {
    private val vectors = Json.parseToJsonElement(checkNotNull(javaClass.getResource("/social/invite-signing.json")).readText()).jsonArray.map { it.jsonObject }

    private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content

    private fun hmac(key: String) = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key.encodeToByteArray(), "HmacSHA256")) }

    /** Hands out [seed], so Ed25519's key generation makes the RFC 8032 test key. */
    private class Seeded(private val seed: ByteArray) : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) {
            seed.copyInto(bytes)
        }
    }

    @Test
    fun eachReleaseKeyIsTheOneTheWorkerDerivesFromItsMaster() {
        for (v in vectors) assertEquals(v.text("key"), HexFormat.of().formatHex(hmac(v.text("master")).doFinal("invite\n${v.text("build")}".encodeToByteArray())), v.text("path"))
    }

    @Test
    fun theSignerSignsTheWorkersVectorsWithTheBuildKeyAndTheDeviceKey() {
        for (v in vectors) {
            val time = v.getValue("time").jsonPrimitive.long
            val body = v.text("body").encodeToByteArray()
            val device = v["device"]?.jsonObject
            val key = DeviceKey.generate(Seeded(HexFormat.of().parseHex(device?.text("seed") ?: "00".repeat(32)))).first
            val url = "https://${v.text("host")}${v.text("path")}".toHttpUrl()
            assertEquals(v.text("message"), signedMessage(v.text("method"), SiteSigner.hostOf(url), url.encodedPath, time, v.text("build"), body).decodeToString())
            val headers = SiteSigner(hmac(v.text("key")), v.text("build"), key) { time }.headers(v.text("method"), url, body)
            assertEquals(v.text("time"), headers[SiteSigner.TimeHeader])
            assertEquals(v.text("build"), headers[SiteSigner.BuildHeader])
            assertEquals(v.text("signature"), headers[SiteSigner.SignatureHeader], v.text("path"))
            if (device != null) {
                assertEquals(device.text("key"), headers[SiteSigner.DeviceHeader])
                assertEquals(device.text("signature"), headers[SiteSigner.DeviceSignatureHeader], v.text("path"))
            }
            val other = SiteSigner(hmac(v.text("key")), "9.9.9", key) { time }.headers(v.text("method"), url, body)
            assertNotEquals(v.text("signature"), other[SiteSigner.SignatureHeader], "the build is signed")
        }
    }

    @Test
    fun aDeviceKeyIsMadeOnceKeptForThisUserAloneAndReadBack() {
        val folder = createTempDirectory("device-key")
        try {
            val file = folder / "nested" / "device.key"
            val made = DeviceKey.load(file)
            val again = DeviceKey.load(file)
            assertEquals(made.header, again.header)
            assertEquals(64, file.readBytes().size)
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
            val message = "GET\nhideouthq.gg\n/api/mailbox/76561198000000001\n1\n0.3.0\n".encodeToByteArray()
            assertEquals(made.sign(message), again.sign(message), "Ed25519 signs alike with the key read back")
        } finally {
            folder.toFile().deleteRecursively()
        }
    }
}
