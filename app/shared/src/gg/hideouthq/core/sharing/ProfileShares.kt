package gg.hideouthq.core.sharing

import gg.hideouthq.core.customgames.SiteOrigin
import gg.hideouthq.core.network.LenientJson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.seconds

/** What looking up a share code found. */
sealed interface SharedLookup {
    data class Found(val profile: SharedProfile) : SharedLookup

    /** The site keeps nothing readable under the code. */
    data object Missing : SharedLookup

    /** The site didn't answer, or there is no site to ask. */
    data object Unreachable : SharedLookup
}

/** The site that keeps shared profiles under short codes, so a link to one fits in a chat message. */
interface ProfileShares {
    /** Where links point; null where no site is set, and sharing then hands out long links alone. */
    val site: SiteOrigin?

    /** The code the site keeps [profile] under, the same for the same profile; null when it couldn't be reached or refused it. */
    suspend fun keep(profile: SharedProfile): ShareCode?

    suspend fun find(code: ShareCode): SharedLookup

    data object None : ProfileShares {
        override val site: SiteOrigin? = null
        override suspend fun keep(profile: SharedProfile): ShareCode? = null
        override suspend fun find(code: ShareCode): SharedLookup = SharedLookup.Unreachable
    }
}

/**
 * [site]'s `/api/profiles`, over the app's [client]. Each call gives up after a few seconds, because the person waits on
 * it and a long link works without the site.
 */
class SiteProfileShares(private val client: HttpClient, override val site: SiteOrigin) : ProfileShares {
    override suspend fun keep(profile: SharedProfile): ShareCode? = asked {
        val response = client.post("${site.value}/api/profiles") {
            contentType(ContentType.Application.OctetStream)
            setBody(profile.bytes())
        }
        if (response.status.isSuccess()) ShareCode.parse(LenientJson.decodeFromString(Kept.serializer(), response.bodyAsText()).code) else null
    }

    override suspend fun find(code: ShareCode): SharedLookup = asked {
        val response = client.get("${site.value}/api/profiles/${code.value}")
        when {
            response.status == HttpStatusCode.NotFound -> SharedLookup.Missing
            !response.status.isSuccess() -> null
            else -> SharedProfile.read(response.bodyAsBytes())?.let(SharedLookup::Found) ?: SharedLookup.Missing
        }
    } ?: SharedLookup.Unreachable

    private suspend fun <T> asked(call: suspend () -> T?): T? = withTimeoutOrNull(Wait) {
        try {
            call()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    @Serializable
    private data class Kept(val code: String)

    private companion object {
        val Wait = 8.seconds
    }
}

/**
 * Requests to open the profile import, from the profile menu or from a link the system hands Hideout, kept until the
 * import takes them. A link that started Hideout waits there for first-run setup to finish.
 */
class ProfileImports {
    private val requests = Channel<String?>(Channel.CONFLATED)

    /** Opens the import on [link], or on an empty field to paste one into when null. */
    fun open(link: String? = null) {
        requests.trySend(link)
    }

    val incoming: Flow<String?> = requests.receiveAsFlow()
}
