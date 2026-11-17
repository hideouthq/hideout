package gg.hideouthq.core.library

import gg.hideouthq.core.data.FileListing
import gg.hideouthq.core.data.GameBananaClient
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.RemoteFile
import gg.hideouthq.core.data.fake.FakeDownloader
import gg.hideouthq.core.data.fake.FakeGameLocator
import gg.hideouthq.core.data.fake.FakeProcesses
import gg.hideouthq.core.data.fake.InMemoryGameFiles
import gg.hideouthq.core.data.fake.InMemoryLibraryStore
import gg.hideouthq.core.data.fake.MemoryStorage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class QuestionPublicationTest {
    private val root = GameRoot("/games/Deadlock")
    private val vanillaInfo = "GameInfo\n{\n\tFileSystem\n\t{\n\t\tSearchPaths\n\t\t{\n\t\t\tGame citadel\n\t\t}\n\t}\n}\n".encodeToByteArray()
    private val mods = (1..Installs).map { SubmissionRef(SubmissionKind.Mod, 700_000L + it) }

    private class HeldListings(private val listings: Map<SubmissionRef, FileListing>) : GameBananaClient {
        val arrived = AtomicInteger()
        val release = CompletableDeferred<Unit>()

        override suspend fun files(submission: SubmissionRef): FileListing {
            arrived.incrementAndGet()
            release.await()
            return listings.getValue(submission)
        }
    }

    private class FirstSeen {
        private val order = mutableListOf<Long>()

        @Synchronized
        fun record(state: LibraryState) {
            state.operations.values.mapNotNull { it.stage.choice?.value }.sorted().forEach { if (it !in order) order += it }
        }

        @Synchronized
        fun inOrder(): Boolean = order == order.sorted()

        @Synchronized
        override fun toString() = order.toString()
    }

    private class Round(val library: ModLibrary, val listings: HeldListings, val scope: CoroutineScope)

    private fun remote(id: Long) = RemoteFile(id, "file_$id.zip", 1_000, null, null, downloads = id, addedAt = 1_777_795_519, downloadUrl = "https://gamebanana.com/dl/$id")

    private suspend fun round(): Round {
        val listings = HeldListings(mods.associateWith { FileListing.Available("Mod ${it.id}", listOf(remote(it.id * 10 + 1), remote(it.id * 10 + 2))) })
        val files = InMemoryGameFiles().apply { putBytes(root, InMemoryGameFiles.GameInfo, vanillaInfo) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val library = ModLibrary(
            FakeGameLocator(listOf(root.path), mapOf(root.path to null)),
            Preferences(MemoryStorage()),
            InMemoryLibraryStore(),
            files,
            InstallPorts(listings, FakeDownloader(files), files, files, FakeProcesses()),
            scope,
            watch = WatchTiming(period = 1.hours),
        )
        library.start()
        withTimeout(5.seconds) { library.state.first { it.library is LibraryStatus.Ready && it.game is GameStatus.Ready } }
        return Round(library, listings, scope)
    }

    private suspend fun Round.askAll() {
        mods.forEach { library.install(it.modId) }
        while (listings.arrived.get() < ListingsAtOnce) Thread.onSpinWait()
        listings.release.complete(Unit)
    }

    private fun LibraryState.questions() = operations.values.mapNotNull { it.stage.choice }

    @Test
    fun questionsAskedOnManyThreadsAppearInTheOrderTheyAreNumbered() = runBlocking {
        val disordered = mutableListOf<String>()
        repeat(Rounds) { index ->
            val round = round()
            val collected = FirstSeen()
            val polled = FirstSeen()
            val polling = AtomicBoolean(true)
            round.scope.launch(Dispatchers.Unconfined) { round.library.state.collect(collected::record) }
            val poller = thread { while (polling.get()) polled.record(round.library.state.value) }
            round.askAll()
            val open = withTimeout(5.seconds) { round.library.state.first { it.questions().size == Installs } }.questions()
            polling.set(false)
            poller.join()
            if (!collected.inOrder() || !polled.inOrder()) disordered += "round $index: collected $collected, polled $polled"
            assertEquals((1L..Installs).toList(), open.map { it.value }.sorted(), "every install asks once, numbered from 1")
            open.forEach { assertTrue(round.library.answer(it, ChoiceAnswer.Cancel)) }
            round.scope.cancel()
        }
        assertEquals(emptyList(), disordered, "a question became visible after a newer one")
    }

    @Test
    fun everyQuestionTakesItsAnswerTheMomentItCanBeSeen() = runBlocking {
        repeat(AnswerRounds) {
            val round = round()
            val answers = ConcurrentHashMap<ChoiceId, Boolean>()
            round.scope.launch(Dispatchers.Unconfined) {
                round.library.state.collect { state ->
                    state.questions().filterNot(answers::containsKey).forEach { answers[it] = round.library.answer(it, ChoiceAnswer.Cancel) }
                }
            }
            round.askAll()
            withTimeout(5.seconds) {
                round.library.state.first { state -> mods.none { it.modId in state.operations } }
                while (answers.size < Installs) delay(1)
            }
            assertTrue(answers.values.all { it }, "every question took the answer given as soon as it was seen, got $answers")
            round.scope.cancel()
        }
    }

    private companion object {
        const val Installs = 8
        const val ListingsAtOnce = 2
        const val Rounds = 500
        const val AnswerRounds = 50
    }
}
