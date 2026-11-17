package gg.hideouthq.core.network

import gg.hideouthq.core.data.DataError
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.io.IOException

internal class RemoteException(val error: DataError, message: String, cause: Throwable? = null) : Exception(message, cause)

internal suspend fun HttpClient.getText(url: String, block: HttpRequestBuilder.() -> Unit = {}): String {
    val response = try {
        get(url, block)
    } catch (e: IOException) {
        throw RemoteException(DataError.Offline, "No answer from $url", e)
    }
    return when {
        response.status.isSuccess() -> response.bodyAsText()
        response.status == HttpStatusCode.NotFound -> throw RemoteException(DataError.NotFound, "Not found: $url")
        else -> throw RemoteException(DataError.Unavailable, "HTTP ${response.status.value} from $url")
    }
}
