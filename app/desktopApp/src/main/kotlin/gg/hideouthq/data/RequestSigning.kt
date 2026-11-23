package gg.hideouthq.data

import java.security.MessageDigest
import java.util.HexFormat

/**
 * What a Hideout Worker checks a request's signatures over: `METHOD\nHOST\nPATH\nTIME\nBUILD`, then for a [body] a
 * newline and its lowercase hex SHA-256 (an empty body hashes nothing). The preview host signs no body; the site's
 * invite and directory routes sign every one, so a request without a body passes an empty array.
 */
internal fun signedMessage(method: String, host: String, path: String, time: Long, build: String, body: ByteArray? = null): ByteArray {
    val text = "$method\n$host\n$path\n$time\n$build"
    return (if (body == null) text else "$text\n${HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body))}").encodeToByteArray()
}
