package gg.hideouthq.data

import java.security.MessageDigest
import java.util.HexFormat

/** The preview host's signatures cover no body. The site's invite and directory routes always cover one, so pass an empty array there, never null. */
internal fun signedMessage(method: String, host: String, path: String, time: Long, build: String, body: ByteArray? = null): ByteArray {
    val text = "$method\n$host\n$path\n$time\n$build"
    return (if (body == null) text else "$text\n${HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body))}").encodeToByteArray()
}
