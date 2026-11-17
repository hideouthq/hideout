package gg.hideouthq.core.data.gamebanana

import gg.hideouthq.core.data.DataError
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.core.network.RemoteException
import gg.hideouthq.core.network.getText
import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

internal class Crawl(val submissions: List<Submission>, val skipped: Int, val listed: Int) {
    val newestModifiedAt: Long? get() = submissions.mapNotNull { it.modifiedAt }.maxOrNull()
}

internal class ListedPage(val number: Int, val pages: Int, val page: IndexPage, val facts: Map<SubmissionRef, FactsAnswer>)

internal fun requireReadable(skipped: Int, listed: Int) {
    if (skipped < 0 || listed < skipped || skipped.toLong() * 100 > listed) {
        throw RemoteException(DataError.Unavailable, "Unreadable listing records: $skipped of $listed")
    }
}

internal class GameBananaApi(
    private val client: HttpClient,
    private val maxConcurrent: Int = DefaultMaxConcurrent,
    private val baseUrl: String = DefaultBaseUrl,
) {
    private val permits = Semaphore(maxConcurrent)

    suspend fun categories(kind: SubmissionKind): List<SubmissionCategory> = categoriesUnder(kind, parentId = null)

    suspend fun submissions(kind: SubmissionKind, onPage: (ListedPage) -> Unit = {}): Crawl = coroutineScope {
        val first = indexPage(kind, 1, OldestFirst)
        val pages = ((first.totalRecords + PerPage - 1) / PerPage).coerceAtLeast(1)
        suspend fun listed(number: Int, page: IndexPage) =
            ListedPage(number, pages, page, fileFacts(page.submissions.map { it.key })).also(onPage)
        val untaken = AtomicInteger(pages)
        val oldest = async { listed(1, first) }
        val walkers = List(maxConcurrent) {
            async {
                buildList {
                    while (true) {
                        val number = untaken.getAndDecrement().takeIf { it > 1 } ?: break
                        add(listed(number, indexPage(kind, number, OldestFirst)))
                    }
                }
            }
        }
        val all = (walkers.awaitAll().flatten() + oldest.await()).sortedBy { it.number }.map { it.page }
        Crawl(all.flatMap { it.submissions }.distinctBy { it.key }, all.sumOf { it.skipped }, all.sumOf { it.submissions.size + it.skipped })
    }

    suspend fun mostDownloaded(kind: SubmissionKind, page: Int): IndexPage = indexPage(kind, page, MostDownloaded)

    suspend fun mostLiked(kind: SubmissionKind): IndexPage = indexPage(kind, 1, MostLiked)

    /**
     * Changes at or after [since], deduplicated by submission. GameBanana's latest-modified listing is not strictly
     * ordered, so only a fully readable page entirely older than [since] ends the walk before the listing's end.
     */
    suspend fun modifiedSince(kind: SubmissionKind, since: Long?): Crawl {
        val found = LinkedHashMap<SubmissionRef, Submission>()
        var skipped = 0
        var read = 0
        var listed = 0
        var page = 1
        while (true) {
            val index = indexPage(kind, page, LatestModified)
            skipped += index.skipped
            read += index.submissions.size + index.skipped
            listed = maxOf(read, index.totalRecords)
            for (submission in index.submissions) {
                val modified = submission.modifiedAt
                if (modified == null) {
                    skipped++
                    continue
                }
                if ((since == null || modified >= since) && modified >= (found[submission.key]?.modifiedAt ?: Long.MIN_VALUE)) {
                    found[submission.key] = submission
                }
            }
            requireReadable(skipped, listed)
            val older = since != null && index.skipped == 0 && index.submissions.isNotEmpty() &&
                index.submissions.all { it.modifiedAt != null && it.modifiedAt < since }
            if (older || page.toLong() * PerPage >= index.totalRecords) break
            page++
        }
        return Crawl(found.values.toList(), skipped, listed)
    }

    suspend fun fileFacts(keys: List<SubmissionRef>, onBatch: (done: Int, total: Int) -> Unit = { _, _ -> }): Map<SubmissionRef, FactsAnswer> =
        coroutineScope {
            val batches = keys.distinct().groupBy { it.kind }.values.flatMap { it.chunked(MultiBatch) }
            val done = AtomicInteger()
            val answers = batches.map { batch -> async { factsFor(batch).also { onBatch(done.incrementAndGet(), batches.size) } } }.awaitAll()
            buildMap { answers.forEach(::putAll) }
        }

    suspend fun featured(): FeaturedPicks = permits.withPermit {
        parseFeatured(client.getText("$baseUrl/Util/List/Featured") {
            parameter("_idGameRow", GameId)
            parameter("_nPage", 1)
        })
    }

    suspend fun profile(key: SubmissionRef): SubmissionProfile = permits.withPermit {
        parseProfile(client.getText("$baseUrl/${key.kind.apiName}/${key.id}/ProfilePage"), key)
    }

    suspend fun updates(key: SubmissionRef): List<SubmissionUpdate> = permits.withPermit {
        parseUpdates(client.getText("$baseUrl/${key.kind.apiName}/${key.id}/Updates") {
            parameter("_nPage", 1)
            parameter("_nPerpage", UpdatesShown)
        })
    }

    private suspend fun categoriesUnder(kind: SubmissionKind, parentId: Long?): List<SubmissionCategory> = coroutineScope {
        val level = permits.withPermit {
            parseCategories(
                client.getText("$baseUrl/${kind.apiName}/Categories") {
                    if (parentId == null) parameter("_idGameRow", GameId) else parameter("_idCategoryRow", parentId)
                    parameter("_sSort", "a_to_z")
                    parameter("_bShowEmpty", true)
                },
                parentId,
                kind,
            )
        }
        level.categories + level.withChildren.map { async { categoriesUnder(kind, it) } }.awaitAll().flatten()
    }

    private suspend fun indexPage(kind: SubmissionKind, page: Int, sort: String): IndexPage = permits.withPermit {
        parseIndexPage(client.getText("$baseUrl/${kind.apiName}/Index") {
            parameter("_aFilters[Generic_Game]", GameId)
            parameter("_sSort", sort)
            parameter("_nPerpage", PerPage)
            parameter("_nPage", page)
        })
    }

    private suspend fun factsFor(batch: List<SubmissionRef>): Map<SubmissionRef, FactsAnswer> = permits.withPermit {
        val kind = batch.first().kind
        parseFileFacts(
            client.getText("$baseUrl/${kind.apiName}/Multi") {
                parameter("_csvRowIds", batch.joinToString(",") { it.id.toString() })
                parameter("_csvProperties", FactsProperties)
            },
            kind,
            batch.map { it.id }.toSet(),
        )
    }

    companion object {
        const val GameId = 20948

        const val GamePage = "https://gamebanana.com/games/$GameId"
        const val DefaultMaxConcurrent = 8
        const val DefaultBaseUrl = "https://gamebanana.com/apiv11"
        const val PerPage = 50
        const val MultiBatch = 50
        const val UpdatesShown = 10

        private const val OldestFirst = "Generic_Oldest"
        private const val MostDownloaded = "Generic_MostDownloaded"
        private const val MostLiked = "Generic_MostLiked"
        private const val LatestModified = "Generic_LatestModified"

        // Sounds have no embedded media; Sound/Multi refuses the whole request when asked for it.
        private const val FactsProperties = "_idRow,_nDownloadCount,_aFiles"
    }
}
