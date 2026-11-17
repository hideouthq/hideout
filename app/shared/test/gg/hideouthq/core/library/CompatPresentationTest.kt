package gg.hideouthq.core.library

import gg.hideouthq.core.data.fake.FakeCompatibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CompatPresentationTest {
    private val model = "models/heroes_staging/tengu/tengu_v2/ivy.vmdl_c"
    private val source = InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, 612_000, 1_400_000, null))
    private val dir = SetFile(VpkPart.Directory, FileImage(40, "a".repeat(64)))
    private val set = InstalledSet(source, null, null, SetOrigin.Downloaded(ArchiveIdentity("/downloads/ivy.zip", 10, "b".repeat(64))), null, "pak04_dir.vpk", listOf(dir))
    private val basis = CheckBasis("build:fake", "fake-codec-1", listOf(FakeCompatibility.Frame, FakeCompatibility.Sound), emptySet())

    private val ivyReason = "[zipline] look converted already while others keep the old pivot"
    private val sound = CompatFinding(FakeCompatibility.Sound, model, FindingKind.Repairable, "6 values", edits = 6)
    private val camera = CompatFinding(FakeCompatibility.Frame, model, FindingKind.NeedsAuthor, ivyReason)

    private fun install(findings: List<CompatFinding>, repairedBy: List<RuleIdentity> = emptyList(), incomplete: String? = null, pending: RepairWork? = null, problem: String? = null, restoring: Boolean = false): InstallCompatibility {
        val check = InstallCheck("ivy", basis, listOf(SetCheck(source, set.componentKey, listOf(dir.image), findings, incomplete)))
        val applied = repairedBy.takeIf { it.isNotEmpty() }?.let { rules ->
            listOf(AppliedRepair(set, SetDerivation("orig1", listOf(dir.image), rules, "fake-codec-1", "build:fake", resources = 1, edits = 6), current = true))
        }.orEmpty()
        return InstallCompatibility("ivy", check, applied = applied, choice = RepairChoice.Repaired.takeIf { repairedBy.isNotEmpty() }, pending = pending, problem = problem, problemRestoring = restoring)
    }

    @Test
    fun aPartRepairSaysWhatWasRepairedAndWhatWasLeftUnchangedWithTheWholeReason() {
        val before = install(listOf(sound, camera))
        assertEquals(CompatStatus.Found(CompatLabel.RepairAvailable), before.status(enabled = true))
        assertEquals(listOf(FindingOutcome.Repairable to FakeCompatibility.Sound, FindingOutcome.LeftUnchanged to FakeCompatibility.Frame), before.outcomes.map { it.outcome to it.rule })
        assertEquals(CompatStatus.Found(CompatLabel.NeedsAuthorUpdate), install(listOf(camera)).status(enabled = true))
        val ivy = install(listOf(sound, camera), repairedBy = listOf(FakeCompatibility.Sound))
        assertEquals(CompatStatus.Found(CompatLabel.PartlyRepaired), ivy.status(enabled = true))
        assertEquals(
            listOf(
                RuleFindings(FindingOutcome.Repaired, FakeCompatibility.Sound, listOf(sound)),
                RuleFindings(FindingOutcome.LeftUnchanged, FakeCompatibility.Frame, listOf(camera)),
            ),
            ivy.outcomes,
        )
        assertEquals(ivyReason, ivy.outcomes.single { it.outcome == FindingOutcome.LeftUnchanged }.findings.single().detail, "the reason is the rule's own, untouched")
    }

    @Test
    fun oneRuleRepairedInOneModelAndLeftInAnotherIsShownUnderBoth() {
        val other = "models/heroes_staging/tengu/tengu_v2/ivy_gun.vmdl_c"
        val fixed = CompatFinding(FakeCompatibility.Frame, model, FindingKind.Repairable, "14 values", edits = 14)
        val left = CompatFinding(FakeCompatibility.Frame, other, FindingKind.NeedsAuthor, "the root bone is scaled")
        val outcomes = install(listOf(fixed, left), repairedBy = listOf(FakeCompatibility.Frame)).outcomes
        assertEquals(listOf(RuleFindings(FindingOutcome.Repaired, FakeCompatibility.Frame, listOf(fixed)), RuleFindings(FindingOutcome.LeftUnchanged, FakeCompatibility.Frame, listOf(left))), outcomes)
    }

    @Test
    fun whatCouldNotBeCheckedIsUncheckedNeverTheAuthorsAndHasNoChip() {
        val declined = CompatFinding(FakeCompatibility.Frame, model, FindingKind.Unknown, "which frame the root attachments are in cannot be told")
        val unknown = install(listOf(declined))
        assertNull(unknown.status(enabled = true), "nothing is said, and nothing is called fine")
        assertEquals(listOf(RuleFindings(FindingOutcome.Unchecked, FakeCompatibility.Frame, listOf(declined))), unknown.outcomes)
        assertEquals(emptyList(), unknown.needsAuthor)

        val unread = install(emptyList(), incomplete = "its files are not the ones recorded")
        assertNull(unread.status(enabled = true))
        assertEquals(listOf("its files are not the ones recorded"), unread.incomplete)
        assertEquals(emptyList(), unread.outcomes)
    }

    @Test
    fun filesChangingComeFirstThenAChangeThatDidNotFinishThenTheCheck() {
        for (work in listOf(RepairWork.Queued(restoring = false), RepairWork.Repairing, RepairWork.Verifying(restoring = false), RepairWork.Restoring, RepairWork.WaitingForGame(restoring = true))) {
            assertEquals(CompatStatus.Changing(work), install(listOf(sound), pending = work, problem = "an earlier try failed").status(enabled = true), "$work")
        }
        assertEquals(CompatStatus.Unfinished(restoring = false), install(listOf(sound), problem = "the repaired model did not read back").status(enabled = true))
        assertEquals(CompatStatus.Unfinished(restoring = true), install(listOf(sound), repairedBy = listOf(FakeCompatibility.Sound), problem = "their file stays", restoring = true).status(enabled = true))
        assertEquals(CompatStatus.Found(CompatLabel.RepairApplied), install(listOf(sound), repairedBy = listOf(FakeCompatibility.Sound)).status(enabled = true))
        assertNull(install(listOf(sound), repairedBy = listOf(FakeCompatibility.Sound)).status(enabled = false), "a repair of a mod that is off is not called applied")
        assertNull(InstallCompatibility("ivy", pending = null).status(enabled = true), "an unchecked mod says nothing")
    }

    @Test
    fun choosingTheOriginalFilesIsTheAnswerToWhatWasFoundSoNoChipAsksAgain() {
        val original = install(listOf(sound, camera)).copy(choice = RepairChoice.Original)
        assertNull(original.status(enabled = true))
        assertEquals(listOf(sound), original.repairable, "what was found is still known for the details")
        assertEquals(CompatStatus.Changing(RepairWork.Restoring), original.copy(pending = RepairWork.Restoring).status(enabled = true), "putting the originals back still shows")
        assertEquals(CompatStatus.Unfinished(restoring = true), original.copy(problem = "their file stays", problemRestoring = true).status(enabled = true), "and so does a restore that didn’t finish")
    }
}
