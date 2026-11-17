package gg.hideouthq.core.data.news

import gg.hideouthq.core.data.CacheStorage
import gg.hideouthq.core.data.NewsRepository
import gg.hideouthq.core.data.live.HtmlText
import gg.hideouthq.core.data.quietly
import gg.hideouthq.core.library.DeadlockAppId
import gg.hideouthq.core.model.NewsKind
import gg.hideouthq.core.model.NewsPost
import gg.hideouthq.core.network.decodeLenient
import gg.hideouthq.core.network.getText
import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Deadlock's own announcements on Steam (`ISteamNews/GetNewsForApp`, community announcements only, not the press posts
 * Steam mixes in). The last posts read are kept in [storage] and shown at once; they are fetched again when an hour
 * old, then every hour. A failed fetch keeps what was shown.
 */
class SteamNewsRepository(
    private val client: HttpClient,
    private val storage: CacheStorage,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
) : NewsRepository {
    private val shown = MutableStateFlow(emptyList<NewsPost>())
    override val posts: StateFlow<List<NewsPost>> = shown.asStateFlow()

    fun start() {
        scope.launch {
            val saved = load()
            saved?.let { shown.value = it.posts }
            val age = saved?.let { (clock.now().epochSeconds - it.fetchedAt).seconds } ?: RefreshEvery
            if (age < RefreshEvery) delay(RefreshEvery - age)
            while (true) {
                refresh()
                delay(RefreshEvery)
            }
        }
    }

    private suspend fun refresh() {
        quietly {
            val text = client.getText(NewsUrl) {
                parameter("appid", DeadlockAppId)
                parameter("count", PostCount)
                parameter("maxlength", 0)
                parameter("feeds", AnnouncementsFeed)
                parameter("format", "json")
            }
            val posts = parseSteamNews(text)
            shown.value = posts
            storage.write(FileName, CacheJson.encodeToString(SavedNews.serializer(), SavedNews(clock.now().epochSeconds, posts)))
        }
    }

    private suspend fun load(): SavedNews? = quietly {
        storage.read(FileName)?.let { CacheJson.decodeFromString(SavedNews.serializer(), it) }
    }

    @Serializable
    private class SavedNews(val fetchedAt: Long, val posts: List<NewsPost>)

    private companion object {
        val RefreshEvery = 1.hours
        const val NewsUrl = "https://api.steampowered.com/ISteamNews/GetNewsForApp/v2/"
        const val AnnouncementsFeed = "steam_community_announcements"
        const val PostCount = 10
        const val FileName = "steam-news.json"
        val CacheJson = Json { ignoreUnknownKeys = true; explicitNulls = false }
    }
}

const val SteamNewsPage = "https://store.steampowered.com/news/app/$DeadlockAppId"

internal fun parseSteamNews(text: String): List<NewsPost> =
    decodeLenient(NewsDto.serializer(), text).appnews.items.mapNotNull { item ->
        val title = item.title?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val id = item.gid?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val body = paragraphs(item.contents.orEmpty())
        NewsPost(
            id = id,
            title = HtmlText.decodeEntities(title),
            kind = if (item.tags.any { it.equals(PatchNotesTag, ignoreCase = true) }) NewsKind.PatchNotes else NewsKind.Announcement,
            postedAt = item.date ?: 0,
            excerpt = excerpt(body),
            body = body,
            imageUrl = firstImage(item.contents.orEmpty()),
            url = "$SteamNewsPage/view/$id",
        )
    }.sortedByDescending { it.postedAt }

internal fun firstImage(contents: String): String? =
    ImageTag.find(contents)?.let { match -> (match.groups[1] ?: match.groups[2])?.value?.trim() }
        ?.replace(ClanImage, ClanImageHost)
        ?.takeIf { it.startsWith("https://") }

internal fun paragraphs(contents: String): List<String> {
    val text = contents
        .replace(ImageTag, "")
        .replace(Regex("\\[\\*]"), "\n• ")
        .replace(Regex("\\[(?:/p|br|/h[1-6]|/list|/olist|hr)[^\\]]*]", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("\\[/?[a-zA-Z0-9*]+(?:[= ][^\\]]*)?]"), "")
        .replace("\\[", "[")
    return HtmlText.toPlainText(text.replace("\n", "<br>")).lines().map { it.trim() }.filter { it.isNotEmpty() && it != "•" }
}

private fun excerpt(body: List<String>): String {
    val text = body.joinToString(" ")
    return if (text.length <= ExcerptLength) text else text.take(ExcerptLength).substringBeforeLast(' ').trimEnd(',', '.', ';', ':') + "…"
}

private val ImageTag = Regex("\\[img(?:\\s+src=\"([^\"]+)\"[^\\]]*)?](?:([^\\[]*)\\[/img])?", RegexOption.IGNORE_CASE)
private val ClanImage = Regex("\\{STEAM_CLAN(?:_LOC)?_IMAGE}")
private const val ClanImageHost = "https://clan.akamai.steamstatic.com/images"
private const val PatchNotesTag = "patchnotes"
private const val ExcerptLength = 220

@Serializable
private class NewsDto(val appnews: AppNewsDto)

@Serializable
private class AppNewsDto(@SerialName("newsitems") val items: List<ItemDto> = emptyList())

@Serializable
private class ItemDto(
    val gid: String? = null,
    val title: String? = null,
    val contents: String? = null,
    val date: Long? = null,
    val tags: List<String> = emptyList(),
)
