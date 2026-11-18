package gg.hideouthq.core.data

import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray

/**
 * A file accepted only when the detached signature at its URL plus `.sig` matches its exact bytes. [verify] takes those
 * bytes and the signature as base64, and [what] names the file in what goes wrong. A file over [MaxFileBytes] or a
 * signature over [MaxSignatureBytes] is unreachable, and is read no further than that.
 */
class SignedDownload(
    private val client: HttpClient,
    private val what: String,
    private val verify: (bytes: ByteArray, signatureBase64: String) -> Boolean,
) {
    suspend fun fetch(url: String): SignedAnswer {
        val bytes = when (val answer = download(url, MaxFileBytes)) {
            is Answer.Missing -> return SignedAnswer.Unreachable(answer.detail)
            is Answer.Body -> answer.bytes
        }
        val signature = when (val answer = download("$url.sig", MaxSignatureBytes)) {
            is Answer.Missing -> return SignedAnswer.Unreachable(answer.detail)
            is Answer.Body -> answer.bytes.decodeToString().trim()
        }
        return if (genuine(bytes, signature)) SignedAnswer.Genuine(bytes, signature) else SignedAnswer.Forged
    }

    /** Whether [signature] proves [bytes], for a copy kept on disk. A check that throws proves nothing. */
    fun genuine(bytes: ByteArray, signature: String): Boolean = try {
        verify(bytes, signature)
    } catch (e: Exception) {
        false
    }

    private suspend fun download(url: String, limit: Long): Answer = try {
        client.prepareGet(url).execute { response ->
            if (!response.status.isSuccess()) return@execute Answer.Missing("HTTP ${response.status.value} from $what")
            if ((response.contentLength() ?: 0) > limit) throw TooLarge()
            val bytes = response.bodyAsChannel().readBuffer(limit + 1).readByteArray()
            if (bytes.size > limit) throw TooLarge()
            Answer.Body(bytes)
        }
    } catch (e: TooLarge) {
        Answer.Missing("Over $limit bytes from $what")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Answer.Missing("No answer from $what: ${e::class.simpleName}")
    }

    /** Thrown rather than returned, so the response is cancelled instead of left to finish. */
    private class TooLarge : Exception()

    companion object {
        const val MaxFileBytes = 1L shl 20
        const val MaxSignatureBytes = 1L shl 10
    }

    private sealed interface Answer {
        class Body(val bytes: ByteArray) : Answer

        class Missing(val detail: String) : Answer
    }
}

sealed interface SignedAnswer {
    class Genuine(val bytes: ByteArray, val signature: String) : SignedAnswer

    class Unreachable(val detail: String) : SignedAnswer

    data object Forged : SignedAnswer
}
