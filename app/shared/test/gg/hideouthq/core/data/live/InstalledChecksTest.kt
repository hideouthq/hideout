package gg.hideouthq.core.data.live

import gg.hideouthq.core.data.DataError
import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.data.gamebanana.FactsAnswer
import gg.hideouthq.core.data.gamebanana.FileFacts
import gg.hideouthq.core.data.live.LiveFixtures.virtualClock
import gg.hideouthq.core.library.FilesObservation
import gg.hideouthq.core.library.SubmissionIdentity
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.core.model.ModDetail
import gg.hideouthq.core.network.RemoteException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class InstalledChecksTest {
    private val mod1 = SubmissionIdentity(SubmissionKind.Mod, 1, 101, null)
    private val mod2 = SubmissionIdentity(SubmissionKind.Mod, 2, 201, null)
    private val sound5 = SubmissionIdentity(SubmissionKind.Sound, 5, 501, null)

    @Test
    fun aCatalogueCheckedWithinTheHourStillAsksAboutInstalledSubmissionsOnceTheLibraryIsRead() = runTest {
        val clock = virtualClock()
        val source = Source(clock)
        val installed = MutableSharedFlow<Set<SubmissionIdentity>>(replay = 1)
        val repository = repository(source, checkedAgo = 10.minutes.inWholeSeconds, installed = installed, clock = clock)
        runCurrent()
        assertEquals(emptyList(), source.events, "nothing is due and the library is not read yet")

        installed.emit(setOf(mod1, sound5))
        runCurrent()
        assertEquals(listOf("installed [mod-1, sound-5]"), source.events, "asked on its own, the fresh catalogue left alone")
        assertEquals(setOf(mod1.ref, sound5.ref), repository.installedFiles.value.keys)
        assertEquals(setOf(mod1), repository.installedFiles.value.getValue(mod1.ref).targets, "each says which installed files it was asked for")
    }

    @Test
    fun aPassAsksAboutInstalledSubmissionsBeforeTheCatalogueAndAFailingCatalogueDoesNotStopThem() = runTest {
        val clock = virtualClock()
        val source = Source(clock).apply { catalogueOnline = false }
        val repository = repository(source, checkedAgo = 2.hours.inWholeSeconds, installed = MutableStateFlow(setOf(mod1)), clock = clock)
        runCurrent()
        assertEquals(listOf("installed [mod-1]", "catalogue"), source.events)
        assertEquals(DataError.Offline, repository.freshness.value.error)
        assertEquals(source.answer(mod1.ref), repository.installedFiles.value.getValue(mod1.ref).answer)

        advanceTimeBy(1.minutes)
        repository.refresh()
        runCurrent()
        assertEquals(listOf("installed [mod-1]", "catalogue"), source.events.drop(2))
    }

    @Test
    fun aNewlyInstalledSubmissionIsAskedAboutAloneAndLeavesTheHourlyPassWhereItWas() = runTest {
        val clock = virtualClock()
        val source = Source(clock)
        val installed = MutableStateFlow(setOf(mod1))
        repository(source, checkedAgo = 30.minutes.inWholeSeconds, installed = installed, clock = clock)
        runCurrent()
        assertEquals(listOf("installed [mod-1]"), source.events)

        advanceTimeBy(10.minutes)
        installed.value = setOf(mod1, mod2)
        runCurrent()
        assertEquals(listOf("installed [mod-2]"), source.events.drop(1), "only the new one")

        advanceTimeBy(19.minutes)
        runCurrent()
        assertEquals(2, source.events.size, "the pass is still due an hour after the last catalogue check")
        advanceTimeBy(1.minutes)
        runCurrent()
        assertEquals(listOf("installed [mod-1, mod-2]", "catalogue"), source.events.drop(2))

        installed.value = setOf(mod2)
        runCurrent()
        installed.value = setOf(mod1, mod2)
        runCurrent()
        assertEquals(listOf("installed [mod-1]"), source.events.drop(4), "installed again after an uninstall, it is new again")
    }

    @Test
    fun anotherFileOfAnInstalledSubmissionHasItsSubmissionAskedAboutAgainWithEveryFileItHolds() = runTest {
        val clock = virtualClock()
        val source = Source(clock)
        val installed = MutableStateFlow(setOf(mod1, mod2))
        val repository = repository(source, checkedAgo = 0, installed = installed, clock = clock)
        runCurrent()
        assertEquals(listOf("installed [mod-1, mod-2]"), source.events)

        val changed = mod1.copy(fileId = 102)
        val alongside = SubmissionIdentity(SubmissionKind.Mod, 1, 103, null)
        installed.value = setOf(changed, alongside, mod2)
        runCurrent()
        assertEquals(listOf("installed [mod-1]"), source.events.drop(1), "the same submissions, but mod 1 holds other files now")
        assertEquals(setOf(changed, alongside), repository.installedFiles.value.getValue(mod1.ref).targets)
        assertEquals(setOf(mod2), repository.installedFiles.value.getValue(mod2.ref).targets)
    }

    @Test
    fun anAnswerAskedForBeforeAFileChangedIsNotTakenForTheNewFile() = runTest {
        val clock = virtualClock()
        val source = Source(clock)
        val installed = MutableStateFlow(setOf(mod1))
        val repository = repository(source, checkedAgo = 0, installed = installed, clock = clock)
        val inFlight = CompletableDeferred<Unit>()
        source.observeGate = inFlight
        runCurrent()
        assertEquals(listOf("installed [mod-1]"), source.events)

        val updated = mod1.copy(fileId = 102)
        installed.value = setOf(updated)
        source.observeGate = null
        inFlight.complete(Unit)
        runCurrent()
        assertEquals(listOf("installed [mod-1]", "installed [mod-1]"), source.events, "the change is asked about again as soon as the earlier answer is in")
        assertEquals(setOf(updated), repository.installedFiles.value.getValue(mod1.ref).targets)
    }

    @Test
    fun aRequestDuringAPassRunsOneMoreWholePass() = runTest {
        val clock = virtualClock()
        val gate = CompletableDeferred<Unit>()
        val source = Source(clock).apply { catalogueGate = gate }
        val repository = repository(source, checkedAgo = 0, installed = MutableStateFlow(setOf(mod1)), clock = clock)
        runCurrent()
        repository.refresh()
        runCurrent()
        assertEquals(listOf("installed [mod-1]", "installed [mod-1]", "catalogue"), source.events)
        repeat(2) { repository.refresh() }
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("installed [mod-1]", "catalogue"), source.events.drop(3))
    }

    @Test
    fun aFailedAnswerFailsItsOwnSubmissionsOnlyAndAThrowingSourceFailsEveryOne() = runTest {
        val clock = virtualClock()
        val source = Source(clock)
        val repository = repository(source, checkedAgo = 0, installed = MutableStateFlow(setOf(mod1, mod2, sound5)), clock = clock)
        runCurrent()

        source.failing = setOf(mod2, sound5)
        repository.refresh()
        runCurrent()
        val after = repository.installedFiles.value
        assertEquals(FilesObservation(source.answer(mod1.ref), setOf(mod1)), after.getValue(mod1.ref))
        assertEquals(FilesObservation(answer = null, setOf(mod2), failure = DataError.Offline), after.getValue(mod2.ref))
        assertEquals(FilesObservation(answer = null, setOf(sound5), failure = DataError.Offline), after.getValue(sound5.ref))

        source.broken = true
        repository.refresh()
        runCurrent()
        assertEquals(DataError.Unavailable, repository.installedFiles.value.getValue(mod1.ref).failure)
    }

    private suspend fun TestScope.repository(source: Source, checkedAgo: Long, installed: Flow<Set<SubmissionIdentity>>, clock: Clock): LiveCatalogRepository {
        val storage = MemoryStorage().also { SnapshotStore(it).save(LiveFixtures.snapshot(checkedAt = LiveFixtures.Now - checkedAgo)) }
        return LiveCatalogRepository(source, SnapshotStore(storage), backgroundScope, clock, installed = installed)
    }

    private class Source(private val clock: Clock) : CatalogSource {
        val events = mutableListOf<String>()
        var catalogueOnline = true
        var catalogueGate: CompletableDeferred<Unit>? = null
        var observeGate: CompletableDeferred<Unit>? = null
        var failing = emptySet<SubmissionIdentity>()
        var broken = false

        fun answer(submission: SubmissionRef): FactsAnswer = FactsAnswer.Read(
            FileFacts(downloads = submission.id.toInt(), largestFileBytes = null, newestFileAt = null, filesComplete = true),
        )

        override suspend fun heroes() = LiveFixtures.heroes

        override fun fetch(since: CatalogSnapshot?, knownPicks: List<SubmissionRef>, onProgress: (Float?) -> Unit): Flow<CatalogSnapshot> = flow {
            events += "catalogue"
            catalogueGate?.await()
            if (!catalogueOnline) throw RemoteException(DataError.Offline, "No route to host")
            val now = clock.now().epochSeconds
            emit(since?.copy(checkedAt = now) ?: LiveFixtures.snapshot(syncedAt = now))
        }

        override suspend fun observe(installed: Set<SubmissionIdentity>): Map<SubmissionRef, FilesObservation> {
            val submissions = installed.groupBy { it.ref }
            events += "installed ${submissions.keys.map { it.modId }.sorted()}"
            observeGate?.await()
            if (broken) error("Not a RemoteException")
            return submissions.mapValues { (submission, files) ->
                val targets = files.toSet()
                if (files.any { it in failing }) FilesObservation(answer = null, targets, failure = DataError.Offline)
                else FilesObservation(answer(submission), targets)
            }
        }

        override suspend fun detail(modId: String): ModDetail = error("No mod pages here")
    }
}
