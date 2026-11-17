package gg.hideouthq.core.library

import gg.hideouthq.core.data.DataError
import gg.hideouthq.core.data.gamebanana.FactsAnswer
import gg.hideouthq.core.data.gamebanana.FileFacts
import gg.hideouthq.core.data.gamebanana.SubmissionFileOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstalledUpdatesTest {
    private val root = GameRoot("/games/deadlock")
    private val other = GameRoot("/games/second")
    private val submission = SubmissionRef(SubmissionKind.Mod, 7)

    @Test
    fun anInstallationFromTheUsersOwnFileIsLeftOut() {
        val local = InstalledSource.Local("mine", "mine.zip")
        assertEquals(emptyMap(), InstalledUpdates.of(listOf(record(set(local, 5))), emptyMap()))
    }

    @Test
    fun withoutAnObservationItIsNotCheckedAndWithoutAUsableAnswerItIsUnknown() {
        val record = record(set(remote(100)))
        assertEquals(SourceState.NotChecked, stateOf(record, null))
        val unknown = listOf(
            seen(listed(file(100)).answer, failure = DataError.Offline),
            seen(answer = null, failure = DataError.Unavailable),
            seen(FactsAnswer.Missing),
            seen(FactsAnswer.Unreadable),
            listed(file(100), complete = false),
        )
        unknown.forEach { observation ->
            assertEquals(SourceState.Unknown, stateOf(record, observation), "$observation")
            assertNull(classify(record, observation).plan)
        }
    }

    @Test
    fun theSameFileIsCurrentUnverifiedOrChangedByItsMd5AndOtherFilesBesideItAreNoUpdate() {
        assertEquals(SourceState.Current, stateOf(record(set(remote(100))), listed(file(100), file(101), file(102))))
        assertEquals(SourceState.Unverified, stateOf(record(set(remote(100, md5 = null))), listed(file(100))))
        assertEquals(SourceState.Unverified, stateOf(record(set(remote(100))), listed(file(100, md5 = null))))
        val changed = file(100, md5 = Md5B)
        assertEquals(SourceState.Changed(changed), stateOf(record(set(remote(100))), listed(changed)))
        assertEquals(SourceState.Changed(changed), stateOf(record(set(remote(100), version = null)), listed(changed)), "with or without a version position")
        assertNull(classify(record(set(remote(100))), listed(file(100), file(101))).plan, "nothing changed")
        assertNull(classify(record(set(remote(100, md5 = null))), listed(file(100))).plan, "unverified is never re-downloaded on its own")
    }

    @Test
    fun aChangedFileIsPinnedInAPlanAgainstEverythingInstalled() {
        val record = record(set(remote(100), 5, name = "old.zip"))
        val updates = classify(record, listed(file(100, md5 = Md5B, name = "new.zip")))
        val replacement = Replacement(remote(100).identity, SubmissionIdentity(SubmissionKind.Mod, 7, 100, Md5B))
        assertEquals(UpdatePlan(root, "install-1", record.provenance, listOf(replacement)), updates.plan)
    }

    @Test
    fun aSoleNewerFileReplacesOneThatIsGoneOnlyWhenEverythingSaysItIsTheOnlyOne() {
        val successor = file(200, name = "v2.zip")
        val updates = classify(record(set(remote(100), 5, name = "v1.zip")), listed(successor))
        assertEquals(SourceState.Successor(successor), updates.sources.single().state)
        assertEquals(
            listOf(Replacement(remote(100).identity, SubmissionIdentity(SubmissionKind.Mod, 7, 200, Md5A))),
            updates.plan?.replacements,
        )

        val noMd5 = file(200, md5 = null)
        val unpinned = classify(record(set(remote(100))), listed(noMd5))
        assertEquals(SourceState.Successor(noMd5), unpinned.sources.single().state, "still a candidate to confirm")
        assertNull(unpinned.plan, "but not for Update all without an MD5 to check it against")

        val review = mapOf(
            "a set installed before its position was kept" to stateOf(record(set(remote(100), version = null)), listed(file(200))),
            "a file chosen from several" to stateOf(record(set(remote(100), version = VersionPosition(1, 2))), listed(file(200))),
            "several files now" to stateOf(record(set(remote(100))), listed(file(200), file(201))),
            "no file now" to stateOf(record(set(remote(100))), listed()),
            "an older id" to stateOf(record(set(remote(100))), listed(file(99))),
        )
        review.forEach { (case, state) -> assertEquals(SourceState.Review, state, case) }
    }

    @Test
    fun eachFileIsJudgedOnItsOwnAndOneThatIsNotKnownKeepsTheWholeForReview() {
        val both = record(set(remote(100), 5), set(remote(110), 6))
        val changedAndCurrent = classify(both, listed(file(100, md5 = Md5B), file(110)))
        assertEquals(listOf(SourceState.Changed(file(100, md5 = Md5B)), SourceState.Current), changedAndCurrent.sources.map { it.state })
        assertEquals(both.provenance, changedAndCurrent.plan?.installed, "pinned to every set it holds, not only the one replaced")

        val changedAndGone = classify(record(set(remote(100), 5), set(remote(110), 6)), listed(file(100, md5 = Md5B), file(200)))
        assertEquals(listOf(SourceState.Changed(file(100, md5 = Md5B)), SourceState.Review), changedAndGone.sources.map { it.state }, "two files installed and two listed: no sole successor")
        assertEquals(listOf(remote(100).identity), changedAndGone.replacements.map { it.from }, "the known change is still there to review")
        assertNull(changedAndGone.plan)

        val changedAndUnverified = classify(record(set(remote(100), 5), set(remote(110, md5 = null), 6)), listed(file(100, md5 = Md5B), file(110)))
        assertEquals(listOf(SourceState.Changed(file(100, md5 = Md5B)), SourceState.Unverified), changedAndUnverified.sources.map { it.state })
        assertNull(changedAndUnverified.plan)
    }

    @Test
    fun filesKeptOverAReviewStayKeptWhileTheSubmissionOffersExactlyWhatItDidThen() {
        val gone = listed(file(200), file(201))
        assertEquals(setOf(200L, 201L), classify(record(set(remote(100))), gone).offered)
        assertEquals(false, classify(record(set(remote(100))), gone).kept, "nothing was kept yet")
        val kept = record(set(remote(100))).copy(reviewed = setOf(200, 201))
        assertEquals(true, classify(kept, gone).kept)
        assertEquals(SourceState.Review, stateOf(kept, gone), "what GameBanana says is unchanged; only the user's answer to it is")
        assertEquals(false, classify(kept, listed(file(200), file(201), file(202))).kept, "a file added to the submission asks again")
        assertEquals(false, classify(kept, listed(file(200))).kept, "so does one taken away")
        assertNull(classify(kept, listed(file(200), file(201), complete = false)).offered, "an incomplete list is not what was kept against")
    }

    @Test
    fun componentsOfOneArchiveAreOneSourceAndASoleSuccessorStillApplies() {
        val source = remote(100)
        val record = record(set(source, 5, component = "a/pak01_dir.vpk", name = "pack.zip"), set(source, 6, component = "b/pak01_dir.vpk", name = "pack.zip"))
        val updates = classify(record, listed(file(200)))
        assertEquals(listOf(SourceUpdate(source.identity, SourceState.Successor(file(200)))), updates.sources)
    }

    @Test
    fun theSameSubmissionInTwoGamesIsJudgedFromOneObservationAndAnotherKindIsNotItsObservation() {
        val records = listOf(record(set(remote(100)), id = "here"), record(set(remote(100)), id = "there", root = other))
        val sound = SubmissionRef(SubmissionKind.Sound, 7)
        val asked = setOf(remote(100).identity)
        val byInstall = InstalledUpdates.of(records, mapOf(submission to listed(file(100, md5 = Md5B)).copy(targets = asked), sound to listed(file(100)).copy(targets = asked)))
        assertEquals(setOf("here", "there"), byInstall.keys)
        assertEquals(other, byInstall.getValue("there").gameRoot)
        assertTrue(byInstall.values.all { it.sources.single().state == SourceState.Changed(file(100, md5 = Md5B)) })
        assertEquals(other, byInstall.getValue("there").plan?.gameRoot)

        val onlySound = InstalledUpdates.of(records, mapOf(sound to listed(file(100)).copy(targets = asked)))
        assertTrue(onlySound.values.all { it.sources.single().state == SourceState.NotChecked }, "sound 7 says nothing about mod 7")
    }

    @Test
    fun aFileInstalledSinceTheLatestCheckAskedIsNotCheckedYetWhateverTheAnswerSays() {
        val updated = record(set(remote(100, md5 = Md5B)))
        val askedAboutTheOldFile = listed(file(100, md5 = Md5B)).copy(targets = setOf(remote(100).identity))
        assertEquals(SourceState.NotChecked, InstalledUpdates.of(listOf(updated), mapOf(submission to askedAboutTheOldFile)).getValue("install-1").sources.single().state)
        assertNull(InstalledUpdates.of(listOf(updated), mapOf(submission to askedAboutTheOldFile)).getValue("install-1").plan)
    }

    private fun stateOf(record: ManifestRecord, observation: FilesObservation?): SourceState = classify(record, observation).sources.single().state

    private fun classify(record: ManifestRecord, observation: FilesObservation?): InstallationUpdates {
        val asked = observation?.copy(targets = record.sets.mapNotNull { (it.source as? InstalledSource.Remote)?.identity }.toSet())
        return InstalledUpdates.of(listOf(record), listOfNotNull(asked?.let { submission to it }).toMap()).getValue(record.installId)
    }

    private fun seen(answer: FactsAnswer?, failure: DataError? = null) = FilesObservation(answer, targets = emptySet(), failure = failure)

    private fun listed(vararg files: SubmissionFileOption, complete: Boolean = true) =
        FilesObservation(FactsAnswer.Read(FileFacts(0, null, null, files = files.toList(), filesComplete = complete)), targets = emptySet())

    private fun file(id: Long, md5: String? = Md5A, name: String? = "file-$id.zip") = SubmissionFileOption(id, md5, name, null)

    private fun remote(fileId: Long, md5: String? = Md5A) = InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, 7, fileId, md5))

    private fun set(source: InstalledSource, number: Int = 5, version: VersionPosition? = VersionPosition(1, 1), name: String? = "skin.zip", component: String = "pak01_dir.vpk") =
        InstalledSet(
            source = source,
            originalName = name.takeIf { source is InstalledSource.Remote },
            variant = null,
            origin = if (source is InstalledSource.Remote) SetOrigin.Downloaded(ArchiveIdentity("/downloads/archive-$number.zip", 10, "a".repeat(64))) else SetOrigin.Imported(FileImage(10, "a".repeat(64))),
            version = version,
            componentKey = component,
            files = listOf(SetFile(VpkPart.Directory, FileImage(10, "b".repeat(64)))),
        )

    private fun record(vararg sets: InstalledSet, id: String = "install-1", root: GameRoot = this.root) = ManifestRecord(id, root, "Mod", placedAt(5), sets.toList())

    private companion object {
        const val Md5A = "ab965746d197e296c253fb552dc1fd47"
        const val Md5B = "8797b6cdb331371ef015e78a9f79bad9"
    }
}
