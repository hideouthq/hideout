package gg.hideouthq.core.data.live

import gg.hideouthq.core.data.DataError
import gg.hideouthq.core.data.deadlock.DeadlockApi
import gg.hideouthq.core.data.gamebanana.Crawl
import gg.hideouthq.core.data.gamebanana.FactsAnswer
import gg.hideouthq.core.data.gamebanana.FeaturedPicks
import gg.hideouthq.core.data.gamebanana.GameBananaApi
import gg.hideouthq.core.data.gamebanana.ListedPage
import gg.hideouthq.core.data.gamebanana.Submission
import gg.hideouthq.core.data.gamebanana.SubmissionUpdate
import gg.hideouthq.core.data.gamebanana.facts
import gg.hideouthq.core.data.gamebanana.requireReadable
import gg.hideouthq.core.library.FilesObservation
import gg.hideouthq.core.library.SubmissionIdentity
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.core.model.Hero
import gg.hideouthq.core.model.ModDetail
import gg.hideouthq.core.network.RemoteException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

internal interface CatalogSource {
    suspend fun heroes(): List<Hero>

    fun fetch(since: CatalogSnapshot?, knownPicks: List<SubmissionRef> = since?.featured.orEmpty(), onProgress: (Float?) -> Unit): Flow<CatalogSnapshot>

    suspend fun observe(installed: Set<SubmissionIdentity>): Map<SubmissionRef, FilesObservation>

    suspend fun detail(modId: String): ModDetail
}

internal class GameBananaCatalogSource(
    private val gameBanana: GameBananaApi,
    private val deadlock: DeadlockApi,
    private val clock: Clock,
) : CatalogSource {
    override suspend fun heroes(): List<Hero> = deadlock.heroes()

    override fun fetch(since: CatalogSnapshot?, knownPicks: List<SubmissionRef>, onProgress: (Float?) -> Unit): Flow<CatalogSnapshot> =
        if (since == null) reconcile(knownPicks, onProgress) else flow { emit(update(since, knownPicks, onProgress)) }

    override suspend fun observe(installed: Set<SubmissionIdentity>): Map<SubmissionRef, FilesObservation> = coroutineScope {
        val targets = installed.groupBy { it.ref }.mapValues { (_, files) -> files.toSet() }
        val batches = targets.keys.groupBy { it.kind }.values.flatMap { it.chunked(GameBananaApi.MultiBatch) }
        batches.map { batch ->
            async {
                val answers = try {
                    gameBanana.fileFacts(batch)
                } catch (e: RemoteException) {
                    return@async batch.map { it to FilesObservation(answer = null, targets.getValue(it), failure = e.error) }
                }
                batch.map { it to FilesObservation(answers.getValue(it), targets.getValue(it)) }
            }
        }.awaitAll().flatten().toMap()
    }

    override suspend fun detail(modId: String): ModDetail {
        val key = SubmissionRef.parse(modId) ?: throw RemoteException(DataError.NotFound, "Not a GameBanana mod: $modId")
        val profile = gameBanana.profile(key)
        return ModDetailMapper.toDetail(profile, if (profile.hasUpdates) updatesOrNone(key) else emptyList())
    }

    private fun reconcile(knownPicks: List<SubmissionRef>, onProgress: (Float?) -> Unit): Flow<CatalogSnapshot> = channelFlow {
        val found = MutableStateFlow(Found())
        val featured = async {
            picksOr(knownPicks).also { picks ->
                val facts = gameBanana.fileFacts(picks.submissions.map { it.key })
                found.update { it.withHeard(picks.submissions, facts) }
            }
        }
        val categories = SubmissionKind.entries.map { async { gameBanana.categories(it) } }.awaitAll().flatten()
        fun snapshot(listed: Collection<Submission>, facts: Map<SubmissionRef, FactsAnswer>, picks: List<SubmissionRef>, through: Map<SubmissionKind, Long>, unheard: Map<SubmissionKind, UnheardLimit>) =
            clock.now().epochSeconds.let { now ->
                CatalogSnapshot(CatalogSnapshot.SchemaVersion, now, categories, listed.map { it.copy(facts = facts[it.key]?.facts) }, picks, now, through, unheard)
            }
        val publisher = launch {
            val picks = featured.await().refs
            found.collect { heard ->
                if (heard.listed.isEmpty()) return@collect
                onProgress(heard.progress)
                send(snapshot(heard.listed.values, heard.facts, picks, emptyMap(), heard.unheard))
                delay(PublishInterval)
            }
        }
        val crawls = coroutineScope {
            SubmissionKind.entries.associateWith { kind ->
                launch { hearCeilings(kind, found) }
                gameBanana.submissions(kind) { page -> found.update { it.withPage(kind, page) } }
            }
        }
        publisher.cancelAndJoin()
        requireReadable(crawls.values)
        val picks = featured.await()
        send(snapshot((crawls.values.flatMap { it.submissions } + picks.submissions).distinctBy { it.key }, found.value.facts, picks.refs, crawls.newest(emptyMap()), emptyMap()))
    }

    private suspend fun hearCeilings(kind: SubmissionKind, found: MutableStateFlow<Found>) {
        try {
            coroutineScope {
                val liked = async { gameBanana.mostLiked(kind).submissions }
                val downloaded = (1..CeilingPages).map { page -> async { gameBanana.mostDownloaded(kind, page).submissions } }.awaitAll().flatten()
                val facts = gameBanana.fileFacts((downloaded + liked.await()).map { it.key })
                val downloads = downloaded.mapNotNull { facts[it.key]?.facts?.downloads }.minOrNull()
                val likes = liked.await().minOfOrNull { it.likes }
                found.update { heard ->
                    val ceilings = if (downloads != null && likes != null) heard.ceilings + (kind to Ceilings(downloads, likes)) else heard.ceilings
                    heard.withHeard(downloaded + liked.await(), facts).copy(ceilings = ceilings)
                }
            }
        } catch (e: RemoteException) {
        }
    }

    /**
     * [previous] with every submission changed since [ModifiedReplayWindow] before the newest change it had listed, the
     * overlap guarding against a change GameBanana listed late, and with GameBanana's picks asked afresh.
     */
    private suspend fun update(previous: CatalogSnapshot, knownPicks: List<SubmissionRef>, onProgress: (Float?) -> Unit): CatalogSnapshot = coroutineScope {
        val featured = async { picksOr(knownPicks).refs }
        val crawls = SubmissionKind.entries.associateWith { kind ->
            async { gameBanana.modifiedSince(kind, previous.modifiedThrough[kind]?.minus(ModifiedReplayWindow.inWholeSeconds)) }
        }.mapValues { it.value.await() }
        requireReadable(crawls.values)
        val known = previous.submissions.associateBy { it.key }
        val listed = crawls.values.flatMap { it.submissions }
        val merged = LinkedHashMap(known)
        listed.forEach { merged[it.key] = it.copy(facts = known[it.key]?.facts) }
        val changed = listed.filter { known[it.key]?.modifiedAt != it.modifiedAt }
        val unknown = merged.values.filter { it.facts?.filesComplete != true }
        val filed = previous.categories.mapTo(HashSet()) { it.kind to it.id }
        val categories = async {
            if (changed.all { it.isFiledIn(filed) }) previous.categories
            else SubmissionKind.entries.map { async { gameBanana.categories(it) } }.awaitAll().flatten()
        }
        val facts = gameBanana.fileFacts((changed + unknown).map { it.key }) { done, total -> onProgress(done.toFloat() / total) }
        CatalogSnapshot(
            schemaVersion = CatalogSnapshot.SchemaVersion,
            syncedAt = previous.syncedAt,
            categories = categories.await(),
            submissions = merged.values.map { submission -> facts[submission.key]?.let { submission.copy(facts = it.facts) } ?: submission },
            featured = featured.await(),
            checkedAt = clock.now().epochSeconds,
            modifiedThrough = crawls.newest(previous.modifiedThrough),
        )
    }

    private fun requireReadable(crawls: Collection<Crawl>) = requireReadable(crawls.sumOf { it.skipped }, crawls.sumOf { it.listed })

    private fun Map<SubmissionKind, Crawl>.newest(previous: Map<SubmissionKind, Long>): Map<SubmissionKind, Long> =
        SubmissionKind.entries.mapNotNull { kind ->
            listOfNotNull(previous[kind], get(kind)?.newestModifiedAt).maxOrNull()?.let { kind to it }
        }.toMap()

    private fun Submission.isFiledIn(categories: Set<Pair<SubmissionKind, Long>>): Boolean =
        listOfNotNull(categoryId, rootCategoryId).all { (key.kind to it) in categories }

    private suspend fun updatesOrNone(key: SubmissionRef): List<SubmissionUpdate> = try {
        gameBanana.updates(key)
    } catch (e: RemoteException) {
        emptyList()
    }

    private suspend fun picksOr(known: List<SubmissionRef>): FeaturedPicks = try {
        gameBanana.featured()
    } catch (e: RemoteException) {
        FeaturedPicks(known, emptyList())
    }

    private data class Ceilings(val downloads: Int, val likes: Int)

    private data class Found(
        val listed: Map<SubmissionRef, Submission> = emptyMap(),
        val facts: Map<SubmissionRef, FactsAnswer> = emptyMap(),
        val totals: Map<SubmissionKind, Int> = emptyMap(),
        val ceilings: Map<SubmissionKind, Ceilings> = emptyMap(),
        val walked: Map<SubmissionKind, Map<Int, Long?>> = emptyMap(),
        val pages: Map<SubmissionKind, Int> = emptyMap(),
    ) {
        val progress: Float get() = totals.entries.sumOf { (kind, total) ->
            (listed.keys.count { it.kind == kind } + facts.keys.count { it.kind == kind }) / (2.0 * total.coerceAtLeast(1))
        }.toFloat() / SubmissionKind.entries.size

        val unheard: Map<SubmissionKind, UnheardLimit> get() = SubmissionKind.entries.filterNot { kind ->
            pages[kind]?.let { walked[kind]?.size == it } == true
        }.associateWith { kind ->
            val heard = walked[kind].orEmpty()
            val run = pages[kind]?.let { last -> generateSequence(last) { it - 1 }.takeWhile { it in heard } }.orEmpty()
            UnheardLimit(run.mapNotNull(heard::get).minOrNull(), ceilings[kind]?.downloads ?: Int.MAX_VALUE, ceilings[kind]?.likes ?: Int.MAX_VALUE)
        }

        fun withHeard(submissions: List<Submission>, answers: Map<SubmissionRef, FactsAnswer>) =
            copy(listed = listed + submissions.associateBy { it.key }, facts = facts + answers)

        fun withPage(kind: SubmissionKind, listing: ListedPage) = withHeard(listing.page.submissions, listing.facts).copy(
            totals = totals + (kind to listing.page.totalRecords),
            pages = pages + (kind to listing.pages),
            walked = walked + (kind to (walked[kind].orEmpty() + (listing.number to listing.page.submissions.minOfOrNull { it.addedAt }))),
        )
    }

    private companion object {
        val ModifiedReplayWindow = 1.hours
        val PublishInterval = 400.milliseconds
        const val CeilingPages = 2
    }
}
