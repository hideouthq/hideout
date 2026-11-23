package gg.hideouthq.data

import gg.hideouthq.BuildMask
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The values the build masked into resources under the key it compiled into [BuildMask], by name. A dev build has none.
 * The native-package plugin's buildSecrets.kt writes them.
 */
internal object BuildSecrets {
    fun resource(name: String) = "/${AppId.replace('.', '/')}/secrets/$name"

    fun text(name: String): String? = read(name)?.let { bytes -> bytes.decodeToString().also { bytes.fill(0) } }

    /** An HMAC-SHA256 [Mac] under the value named [name], so the value never becomes a String. */
    fun mac(name: String): Mac? = read(name)?.let { bytes ->
        try {
            hmac(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun read(name: String): ByteArray? {
        val stored = BuildSecrets::class.java.getResourceAsStream(resource(name))?.use { it.readBytes() } ?: return null
        val key = BuildMask.bytes()
        return try {
            unmask(key, name, stored)
        } finally {
            key.fill(0)
        }
    }

    fun unmask(key: ByteArray, name: String, stored: ByteArray): ByteArray {
        val mac = hmac(key)
        val blocks = List((stored.size + 31) / 32) { block -> mac.doFinal("$name\n".encodeToByteArray() + ByteBuffer.allocate(4).putInt(block).array()) }
        return ByteArray(stored.size) { i -> (stored[i].toInt() xor blocks[i / 32][i % 32].toInt()).toByte() }
    }

    private fun hmac(key: ByteArray): Mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
}
