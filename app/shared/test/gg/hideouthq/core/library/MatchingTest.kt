package gg.hideouthq.core.library

import gg.hideouthq.core.data.ArchiveIndex
import gg.hideouthq.core.data.FileListing
import gg.hideouthq.core.data.GameBananaClient
import gg.hideouthq.core.data.RemoteFile
import gg.hideouthq.core.data.Withdrawal
import gg.hideouthq.core.data.fake.FakeGameBanana
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MatchingTest {
    private val read = mutableListOf<Long>()
    private var indexes = Ace.indexes
    private val archives = ArchiveIndex { file ->
        read += file.id
        indexes[file.id]
    }

    private fun matching(banana: GameBananaClient) = Matching(banana, archives, Semaphore(2))

    private suspend fun identify(submission: SubmissionRef?, vararg sets: FilePrint, title: String = "", banana: GameBananaClient = FakeGameBanana(mapOf(Ace.ref to Ace.listing))): Matched? =
        matching(banana).identify(submission, title, sets.map { listOf(it.sizeBytes) }) { sets.map(::listOf) }

    private fun exact(vararg files: RemoteFile) = Matched.Pinned(files.map { it.identity(Ace.ref) }, Ace.listing.title)

    private val known = Matched.Unpinned(Ace.ref)

    private val nothing = Matched.Unpinned(null)

    @Test
    fun aRecordedFileIsThatFileWithoutAskingAndAnythingLessIsAClaimOfItsSubmission() {
        val file = SubmissionIdentity(SubmissionKind.Mod, 721_908, 1_829_854, "0332f45e5015186cb47219853f499a25")
        assertEquals(Matched.Pinned(listOf(file, file), title = null), ClaimedMod(ModIdentity.File(file), "Nicole").matched(sets = 2), "every set is the file the manager recorded")
        assertEquals(Matched.Unpinned(file.ref), ClaimedMod(ModIdentity.File(file), null, selfReported = true).matched(sets = 1), "a file a mod's own imprint names is only a claim of its submission")
        assertEquals(Matched.Unpinned(Ace.ref), ClaimedMod(ModIdentity.Submission(Ace.ref), "Ace").matched(sets = 1))
        assertEquals(nothing, ClaimedMod(ModIdentity.None, "Ace").matched(sets = 1))
    }

    @Test
    fun aSubmissionIsPinnedToTheOneFileWhoseArchiveHoldsItsVpks() = runTest {
        assertEquals(exact(Ace.noBag), identify(Ace.ref, Ace.noBagVpk), "two RARs of one pak02_dir.vpk told apart by their bytes")
        assertEquals(listOf(Ace.withBag.id, Ace.noBag.id), read, "current files first, until one holds exactly it")
        assertEquals(exact(Ace.older), identify(Ace.ref, Ace.olderVpk), "an archived file is a candidate too")
        assertEquals(nothing, matching(FakeGameBanana(mapOf(Ace.ref to Ace.listing))).identify(Ace.ref, "", listOf(listOf(Ace.noBagVpk.sizeBytes, Ace.olderVpk.sizeBytes))) { listOf(listOf(Ace.noBagVpk, Ace.olderVpk)) }, "every VPK of a set must be in one archive")
    }

    @Test
    fun eachSetIsPinnedOnItsOwnAndAModWithSomePinnedIsKnownByItsSubmission() = runTest {
        val banana = FakeGameBanana(mapOf(Ace.ref to Ace.listing), searches = mapOf("Ace" to listOf(Ace.ref)))
        assertEquals(exact(Ace.noBag, Ace.older), identify(Ace.ref, Ace.noBagVpk, Ace.olderVpk, banana = banana), "a main file and its voice lines, two files of one submission")
        assertEquals(exact(Ace.noBag, Ace.older), identify(null, Ace.noBagVpk, Ace.olderVpk, title = "Ace", banana = banana), "and so when found by its title")
        assertEquals(known, identify(Ace.ref, Ace.noBagVpk, FilePrint(5, 5), banana = banana), "one set no file holds leaves the other unpinned too")
        assertEquals(known, identify(null, Ace.noBagVpk, FilePrint(5, 5), title = "Ace", banana = banana), "and the set that pins names the submission")
    }

    @Test
    fun oneFileThatHoldsEverySetIsPinnedBeforeAFileForEach() = runTest {
        indexes = mapOf(Ace.withBag.id to listOf(Ace.noBagVpk, Ace.olderVpk), Ace.noBag.id to listOf(Ace.noBagVpk), Ace.older.id to listOf(Ace.olderVpk))
        assertEquals(exact(Ace.withBag, Ace.withBag), identify(Ace.ref, Ace.noBagVpk, Ace.olderVpk), "the full pack the mod came from, over its main and voice files")
        indexes = indexes + (Ace.withBag.id to listOf(Ace.withBagVpk, Ace.noBagVpk, Ace.olderVpk)) + (Ace.noBag.id to listOf(Ace.noBagVpk, Ace.olderVpk))
        assertEquals(exact(Ace.noBag, Ace.noBag), identify(Ace.ref, Ace.noBagVpk, Ace.olderVpk), "the one that holds exactly every set before a bigger pack")
    }

    @Test
    fun theExactVariantIsPinnedBeforeAPackThatHoldsItToo() = runTest {
        indexes = mapOf(Ace.withBag.id to listOf(Ace.withBagVpk, Ace.noBagVpk), Ace.noBag.id to listOf(Ace.noBagVpk), Ace.older.id to listOf(Ace.olderVpk, Ace.noBagVpk))
        assertEquals(exact(Ace.noBag), identify(Ace.ref, Ace.noBagVpk), "the archive of just that VPK, though the pack before it holds it")
        val pack = matching(FakeGameBanana(mapOf(Ace.ref to Ace.listing))).identify(Ace.ref, "", listOf(listOf(Ace.withBagVpk.sizeBytes, Ace.noBagVpk.sizeBytes))) { listOf(listOf(Ace.withBagVpk, Ace.noBagVpk)) }
        assertEquals(exact(Ace.withBag), pack, "and the pack when the mod is the pack")
        indexes = indexes - Ace.noBag.id
        assertEquals(exact(Ace.withBag), identify(Ace.ref, Ace.noBagVpk), "with no exact archive, the first that holds it")
    }

    @Test
    fun theSameBytesInSeveralFilesPinTheCurrentThenTheNewest() = runTest {
        indexes = mapOf(Ace.withBag.id to listOf(Ace.olderVpk), Ace.noBag.id to listOf(Ace.olderVpk), Ace.older.id to listOf(Ace.olderVpk))
        val newer = Ace.noBag.copy(addedAt = Ace.withBag.addedAt + 1)
        val banana = FakeGameBanana(mapOf(Ace.ref to Ace.listing.copy(files = listOf(Ace.withBag, newer))))
        assertEquals(exact(newer), identify(Ace.ref, Ace.olderVpk, banana = banana))
        assertEquals(exact(Ace.withBag), identify(Ace.ref, Ace.olderVpk), "a current file over the archived one")
    }

    @Test
    fun aSubmissionNoArchiveBearsOutProvesNothingAndOneGameBananaDoesNotAnswerForIsLeftForLater() = runTest {
        indexes = emptyMap()
        assertEquals(nothing, identify(Ace.ref, Ace.noBagVpk), "no evidence pins nothing, and the mod keeps what it was known by")
        assertEquals(nothing, identify(Ace.ref, Ace.noBagVpk, banana = FakeGameBanana(mapOf(Ace.ref to FileListing.Withdrawn(Withdrawal.Trashed)))), "a withdrawn submission is an answer")
        assertNull(identify(Ace.ref, Ace.noBagVpk, banana = FakeGameBanana()), "no answer is not one")
    }

    @Test
    fun aModThatClaimsNothingIsFoundByItsTitlePinnedOnlyByItsBytesAndElseLinkedByItsExactTitle() = runTest {
        val portgas = SubmissionRef(SubmissionKind.Mod, 582_035)
        val portgasFile = RemoteFile(1_400_001, "portgas.zip", 10, null, null, 1, 1, "https://gamebanana.com/dl/1400001")
        val titles = listOf("Ace Infernus", "Portgas D Ace Infernus", " portgas\u00A0d  ACE\tinfernus ")
        val banana = FakeGameBanana(
            mapOf(Ace.ref to Ace.listing, portgas to FileListing.Available("Portgas D Ace Infernus", listOf(portgasFile))),
            searches = titles.associateWith { listOf(portgas, Ace.ref) },
        )
        assertEquals(exact(Ace.noBag), identify(null, Ace.noBagVpk, title = "Ace Infernus", banana = banana))
        assertEquals(nothing, identify(null, FilePrint(5, 5), title = "Ace Infernus", banana = banana), "a near title never links")
        assertEquals(Matched.Unpinned(portgas), identify(null, FilePrint(5, 5), title = titles[1], banana = banana), "the exact title links its page and pins no file")
        assertEquals(Matched.Unpinned(portgas), identify(null, FilePrint(5, 5), title = titles[2], banana = banana), "whatever its case and spacing")
        assertEquals(exact(Ace.noBag), identify(null, Ace.noBagVpk, title = titles[1], banana = banana), "a submission whose archive holds the mod wins over the title")
        assertEquals(listOf(titles[0], titles[0], titles[1], titles[2], titles[1]), banana.searched)
        assertEquals(nothing, matching(banana).identify(null, titles[1], listOf(listOf(Ace.noBagVpk.sizeBytes))) { null }, "nor one whose files could not be read where an archive could hold them")
    }

    @Test
    fun aTitleLinksTheFirstOfTheResultsSearchedThatHasIt() = runTest {
        val found = (1L..6).map { SubmissionRef(SubmissionKind.Mod, 700_000 + it) }
        suspend fun linked(vararg titled: Int): Matched? {
            val listings = found.withIndex().associate { (index, ref) -> ref to FileListing.Available(if (index in titled) "Sinclair" else "Sinclair TOA", emptyList()) }
            return identify(null, FilePrint(5, 5), title = "Sinclair", banana = FakeGameBanana(listings, mapOf("Sinclair" to found)))
        }
        assertEquals(Matched.Unpinned(found[1]), linked(1, 3), "the first of several with the title")
        assertEquals(nothing, linked(5), "and only among the first five GameBanana finds")
    }

    @Test
    fun aSearchGameBananaDoesNotAnswerLeavesTheModForLater() = runTest {
        val silent = object : GameBananaClient {
            override suspend fun files(submission: SubmissionRef) = Ace.listing
            override suspend fun search(title: String): List<SubmissionRef>? = null
        }
        assertNull(identify(null, Ace.noBagVpk, title = "Ace", banana = silent))
    }

    @Test
    fun aMatchingAsksForEachListingAndSearchOnce() = runTest {
        val banana = FakeGameBanana(mapOf(Ace.ref to Ace.listing), searches = mapOf("Ace" to listOf(Ace.ref)))
        val matching = matching(banana)
        repeat(2) {
            assertEquals(exact(Ace.noBag), matching.identify(Ace.ref, "", listOf(listOf(Ace.noBagVpk.sizeBytes))) { listOf(listOf(Ace.noBagVpk)) })
            assertEquals(exact(Ace.withBag), matching.identify(null, "Ace", listOf(listOf(Ace.withBagVpk.sizeBytes))) { listOf(listOf(Ace.withBagVpk)) })
        }
        assertEquals(1 to listOf("Ace"), banana.calls to banana.searched, "as a check asks again for the mods it goes through")
    }
}
