package gg.hideouthq.core.data

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException

/**
 * A file accepted only when the detached signature at its URL plus `.sig` matches its exact bytes. [verify] takes those
 * bytes and the signature as base64, and [what] names the file in what goes wrong.
 */
class SignedDownload(
    private val client: HttpClient,
    private val what: String,
    private val verify: (bytes: ByteArray, signatureBase64: String) -> Boolean,
) {
    suspend fun fetch(url: String): SignedAnswer {
        val bytes = when (val answer = download(url)) {
            is Answer.Missing -> return SignedAnswer.Unreachable(answer.detail)
            is Answer.Body -> answer.bytes
        }
        val signature = when (val answer = download("$url.sig")) {
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

    private suspend fun download(url: String): Answer = try {
        val response = client.get(url)
        if (response.status.isSuccess()) Answer.Body(response.bodyAsBytes()) else Answer.Missing("HTTP ${response.status.value} from $what")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Answer.Missing("No answer from $what: ${e::class.simpleName}")
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
