package gg.hideouthq.library.compat

import gg.hideouthq.library.compat.SyntheticModels.Identity
import gg.hideouthq.library.compat.SyntheticModels.OldPivot
import gg.hideouthq.library.compat.SyntheticModels.attachment
import gg.hideouthq.library.compat.SyntheticModels.data
import gg.hideouthq.library.compat.SyntheticModels.keyValues
import gg.hideouthq.library.compat.SyntheticModels.mesh
import gg.hideouthq.library.compat.SyntheticModels.model
import gg.hideouthq.library.compat.SyntheticModels.table
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ModelRulesTest {
    private val path = "models/heroes/test/test.vmdl_c"

    private val vanilla = model(listOf(mesh(listOf(attachment("standing_pivot", Identity, doubleArrayOf(0.0, 0.0, 94.0)), attachment("aim", Identity, doubleArrayOf(-0.008, -6.9, 55.9))))))

    private val oldPivot = attachment("standing_pivot", OldPivot, doubleArrayOf(0.0, 94.0, 0.0))
    private val oldModel = model(listOf(mesh(listOf(oldPivot))))

    private val sounds = "scripts/tagged_sounds/test_anim_sounds.vdata"

    private fun game(model: ByteArray = vanilla, extra: Map<String, ByteArray> = mapOf("${sounds}_c" to table("CTaggedSound_t"))): (String) -> ByteArray? = (mapOf(path to model) + extra)::get

    private fun context(game: (String) -> ByteArray? = game(), set: (String) -> ByteArray? = { null }, above: (String) -> Boolean = { false }) = RuleContext(game, set, above)

    private fun frame(bytes: ByteArray, context: RuleContext = context()) = ModelRepairs.assess(path, bytes, context).single { it.rule == RootMotionAttachmentFrame }.outcome

    private fun sound(bytes: ByteArray, context: RuleContext = context()) = ModelRepairs.assess(path, bytes, context).single { it.rule == TaggedSoundBinding }.outcome

    private fun repaired(bytes: ByteArray, context: RuleContext = context()) = assertIs<ResourceRepair.Repaired>(ModelRepairs.repair(path, bytes, context)).bytes

    private fun assertLeftForTheAuthor(bytes: ByteArray, context: RuleContext = context()) {
        assertIs<RuleOutcome.Unrepairable>(frame(bytes, context))
        assertEquals(ResourceRepair.Refused("nothing to repair"), ModelRepairs.repair(path, bytes, context))
    }

    private fun attachments(bytes: ByteArray): Map<Pair<Int, String>, Pair<List<Long>, List<Long>>> {
        val file = ResourceContainer.read(bytes)
        return file.blocks("MDAT").flatMap { block ->
            val mesh = Kv3Binary.decode(file.payload(block)).root as Kv3.Obj
            val list = mesh["m_attachments"]?.let(::list).orEmpty()
            list.map { item ->
                val value = (item as Kv3.Obj)["value"] as Kv3.Obj
                val rotation = list(list(value["m_vInfluenceRotations"]!!)[0]).map { (it as Kv3.Number).bits }
                val offset = list(list(value["m_vInfluenceOffsets"]!!)[0]).map { (it as Kv3.Number).bits }
                (block.index to (value["m_name"] as Kv3.Str).value) to (rotation to offset)
            }
        }.toMap()
    }

    private fun bits(vararg v: Double) = v.map { java.lang.Float.floatToRawIntBits(it.toFloat()).toLong() and 0xFFFFFFFFL }

    @Test
    fun theOldPivotBecomesTheIdentityAndItsOffsetIsPermutedExactly() {
        val mod = model(listOf(mesh(listOf(oldPivot, attachment("aim", OldPivot, doubleArrayOf(-6.893630027770996, 55.884300231933594, -0.008449999615550041))))))
        val outcome = assertIs<RuleOutcome.Repairable>(frame(mod))
        assertEquals(13, outcome.edits.size)
        assertEquals(FieldEdit(1, "m_attachments/0/value/m_vInfluenceRotations/0/0", SyntheticModels.float(-0.5), SyntheticModels.float(0.0)), outcome.edits.first())
        val repaired = repaired(mod)
        val after = attachments(repaired)
        assertEquals(bits(0.0, 0.0, 0.0, 1.0) to bits(0.0, 0.0, 94.0), after.values.first())
        assertEquals(bits(-0.008449999615550041, -6.893630027770996, 55.884300231933594), after.values.last().second)
    }

    @Test
    fun anAuthoredRotationIsCarriedIntoTheNewFrameByTheRootRotation() {
        val lean = doubleArrayOf(0.0, -0.7071067690849304, 0.0, 0.7071067690849304)
        val mod = model(listOf(mesh(listOf(oldPivot, attachment("lean", lean, doubleArrayOf(1.0, -2.0, -0.0))))))
        val repaired = repaired(mod)
        val (rotation, offset) = attachments(repaired).entries.single { it.key.second == "lean" }.value
        val r = lean.map { it.toFloat().toDouble() }
        val q = Quat(0.5, 0.5, 0.5, 0.5) * Quat(r[0], r[1], r[2], r[3])
        assertEquals(bits(q.x, q.y, q.z, q.w), rotation)
        assertEquals(bits(-0.0, 1.0, -2.0), offset, "negative zero keeps its sign")
    }

    @Test
    fun everyMeshBlockIsConvertedOnItsOwnAndBlocksWithoutAttachmentsStayAsTheyAre() {
        val lod0 = mesh(listOf(oldPivot))
        val lod1 = mesh(listOf(attachment("standing_pivot", OldPivot, doubleArrayOf(0.0, 93.0, 0.0)), attachment("crouching_pivot", OldPivot, doubleArrayOf(0.0, 65.0, 0.0))))
        val bare = mesh(null)
        val mod = model(listOf(lod0, lod1, bare))
        val repaired = repaired(mod)
        val after = attachments(repaired)
        assertEquals(3, after.size)
        assertTrue(after.values.all { it.first == bits(0.0, 0.0, 0.0, 1.0) })
        assertEquals(setOf(bits(0.0, 0.0, 94.0), bits(0.0, 0.0, 93.0), bits(0.0, 0.0, 65.0)), after.values.map { it.second }.toSet())
        val before = ResourceContainer.read(mod)
        val written = ResourceContainer.read(repaired)
        for ((a, b) in before.blocks.zip(written.blocks)) {
            val changed = a.type == "MDAT" && Kv3Binary.decode(before.payload(a)).root != mesh(null)
            if (!changed) assertContentEquals(before.payload(a), written.payload(b), "${a.type}#${a.index} stays byte for byte")
        }
    }

    @Test
    fun attachmentsOnOtherBonesAndTheSkeletonAreNotTouched() {
        val mod = model(listOf(mesh(listOf(oldPivot, attachment("hand", doubleArrayOf(0.1, 0.2, 0.3, 0.9), doubleArrayOf(4.0, 5.0, 6.0), influence = "hand_R")))))
        val repaired = repaired(mod)
        assertEquals(attachments(mod).entries.single { it.key.second == "hand" }.value, attachments(repaired).entries.single { it.key.second == "hand" }.value)
        val data = { bytes: ByteArray -> ResourceContainer.read(bytes).let { it.payload(it.blocks("DATA").single()) } }
        assertContentEquals(data(mod), data(repaired), "the skeleton, its root translation included, is the original block")
    }

    @Test
    fun aModelAlreadyInTheCurrentFrameIsNotAffected() {
        assertEquals(RuleOutcome.NotAffected, frame(vanilla))
        assertEquals(RuleOutcome.NotAffected, frame(model(listOf(mesh(null)))))
    }

    @Test
    fun mixedFramesAreLeftForTheAuthor() {
        val mixed = model(listOf(mesh(listOf(oldPivot, attachment("zipline", Identity, doubleArrayOf(0.0, 0.0, 140.0))))))
        assertLeftForTheAuthor(mixed)
        val acrossMeshes = model(listOf(mesh(listOf(oldPivot)), mesh(listOf(attachment("standing_pivot", Identity, doubleArrayOf(0.0, 0.0, 94.0))))))
        assertIs<RuleOutcome.Unrepairable>(frame(acrossMeshes), "one mesh converted and another not is a partial migration")
    }

    @Test
    fun aCurrentRotationInAnotherMeshVetoesConversionWhateverItsOffset() {
        val lean = doubleArrayOf(0.0, -0.707106769, 0.0, 0.707106769)
        val gameMeshes = listOf(
            mesh(listOf(attachment("standing_pivot", Identity, doubleArrayOf(0.0, 0.0, 94.0)), attachment("lean", Identity, doubleArrayOf(0.0, 0.0, 0.0)))),
            mesh(listOf(attachment("lean", lean, doubleArrayOf(1.0, 2.0, 3.0)))),
        )
        for (offset in listOf(doubleArrayOf(1.0, 2.0, 3.0), doubleArrayOf(4.0, 5.0, 6.0))) for (meshes in listOf(gameMeshes, gameMeshes.reversed())) {
            val mixed = model(listOf(
                mesh(listOf(oldPivot)),
                mesh(listOf(attachment("lean", lean, offset))),
            ))
            assertLeftForTheAuthor(mixed, context(game(model(meshes))))
        }
    }

    private fun legacyIdentityZipline(rotation: DoubleArray = Identity) = model(listOf(mesh(listOf(
        oldPivot,
        attachment("zipline", rotation, doubleArrayOf(3.0, 97.0, 0.0)),
    ))))

    private fun gameWithZiplines(ziplines: List<Kv3.Obj>) = game(model(ziplines.map { zipline ->
        mesh(listOf(attachment("standing_pivot", Identity, doubleArrayOf(0.0, 0.0, 94.0)), zipline))
    }))

    @Test
    fun anOldIdentityIsConvertedOnlyWithCompleteVanillaEvidence() {
        val context = context(gameWithZiplines(List(2) { attachment("zipline", SyntheticModels.Root, doubleArrayOf(0.0, 3.0, 97.0)) }))
        for (rotation in listOf(Identity, Identity.map { -it }.toDoubleArray())) {
            val mod = legacyIdentityZipline(rotation)
            assertIs<RuleOutcome.Repairable>(frame(mod, context))
            val repaired = repaired(mod, context)
            val (actualRotation, offset) = attachments(repaired).entries.single { it.key.second == "zipline" }.value
            val sign = rotation[3]
            assertEquals(bits(0.5 * sign, 0.5 * sign, 0.5 * sign, 0.5 * sign), actualRotation)
            assertEquals(bits(0.0, 3.0, 97.0), offset)
            assertEquals(RuleOutcome.NotAffected, frame(repaired, context))
        }
    }

    @Test
    fun anIdentityNeedsASameNameGameCandidateMatchingBothItsConvertedRotationAndOffset() {
        for (game in listOf(
            game(),
            gameWithZiplines(listOf(attachment("zipline", SyntheticModels.Root, doubleArrayOf(0.0, 3.0, 98.0)))),
            gameWithZiplines(listOf(attachment("zipline", doubleArrayOf(0.0, 0.0, 0.707106769, 0.707106769), doubleArrayOf(0.0, 3.0, 97.0)))),
        )) {
            assertLeftForTheAuthor(legacyIdentityZipline(), context(game))
        }
    }

    @Test
    fun oneMatchingMeshCannotOverrideConflictingIdentityEvidence() {
        val matching = attachment("zipline", SyntheticModels.Root, doubleArrayOf(0.0, 3.0, 97.0))
        for (conflict in listOf(
            attachment("zipline", SyntheticModels.Root, doubleArrayOf(0.0, 3.0, 98.0)),
            attachment("zipline", doubleArrayOf(0.0, 0.0, 0.707106769, 0.707106769), doubleArrayOf(0.0, 3.0, 97.0)),
            attachment("zipline", Identity, doubleArrayOf(700.0, 800.0, 900.0)),
        )) for (candidates in listOf(listOf(matching, conflict), listOf(conflict, matching))) {
            assertLeftForTheAuthor(legacyIdentityZipline(), context(gameWithZiplines(candidates)))
        }
    }

    @Test
    fun malformedOrAmbiguousAttachmentsAreDeclined() {
        val repeated = model(listOf(mesh(listOf(oldPivot, attachment("standing_pivot", OldPivot, doubleArrayOf(0.0, 95.0, 0.0))))))
        assertIs<RuleOutcome.Declined>(frame(repeated), "a name repeated in one mesh")
        val renamed = model(listOf(mesh(listOf(attachment("standing_pivot", OldPivot, doubleArrayOf(0.0, 94.0, 0.0), key = "other")))))
        assertIs<RuleOutcome.Declined>(frame(renamed), "a key that is not its name")
        val short = model(listOf(mesh(listOf(attachment("standing_pivot", doubleArrayOf(-0.5, -0.5, -0.5), doubleArrayOf(0.0, 94.0, 0.0))))))
        assertIs<RuleOutcome.Declined>(frame(short), "a rotation of three")
        val nan = model(listOf(mesh(listOf(attachment("standing_pivot", OldPivot, doubleArrayOf(0.0, Double.NaN, 0.0))))))
        assertIs<RuleOutcome.Declined>(frame(nan), "a value that is not finite")
    }

    @Test
    fun unsafeInfluencesAreConfirmedButNotRepaired() {
        val blended = model(listOf(mesh(listOf(oldPivot, attachment("aim", OldPivot, doubleArrayOf(1.0, 2.0, 3.0), count = 2, weight = 0.5, second = "pelvis")))))
        assertIs<RuleOutcome.Unrepairable>(frame(blended))
        val flagged = model(listOf(mesh(listOf(attachment("standing_pivot", OldPivot, doubleArrayOf(0.0, 94.0, 0.0), rootTransform = true)))))
        assertIs<RuleOutcome.Unrepairable>(frame(flagged))
        val scaled = model(listOf(mesh(listOf(oldPivot))), data(scale = 2.0))
        assertIs<RuleOutcome.Unrepairable>(frame(scaled))
    }

    @Test
    fun withoutTheGamesOwnEvidenceNothingIsClaimed() {
        assertIs<RuleOutcome.Declined>(frame(oldModel, context({ null })), "no game model at the path")
        assertIs<RuleOutcome.Declined>(frame(oldModel, context(game(oldModel))), "the game itself still uses the old pivots")
        val turned = model(listOf(mesh(listOf(attachment("standing_pivot", Identity, doubleArrayOf(0.0, 0.0, 94.0))))), data(root = doubleArrayOf(0.0, 0.0, 0.0, 1.0)))
        assertIs<RuleOutcome.Declined>(frame(oldModel, context(game(turned))), "the game's root is rotated differently")
        val tilted = model(listOf(mesh(listOf(oldPivot))), data(root = doubleArrayOf(0.1, 0.5, 0.5, 0.7)))
        assertIs<RuleOutcome.Declined>(frame(tilted), "a root that is not a quarter-turn rotation")
        val external = model(listOf(mesh(listOf(oldPivot))), data(refMeshes = listOf(Kv3.Str("models/x.vmesh"))))
        assertIs<RuleOutcome.Declined>(frame(external), "meshes outside the model")
    }

    private val oldBinding = keyValues("\tCitadelModelParticleSettings_t = \n\t{\n\t\tm_flScale = 0.83\n\t}\n\tCitadelTaggedSoundSettings_t = \n\t{\n\t\tm_taggedSounds = \n\t\t[\n\t\t\tresource:\"$sounds\",\n\t\t]\n\t}\n\t// a comment that mentions TaggedSoundSettings_t\n")
    private val vanillaWithNewBinding = model(listOf(mesh(listOf(attachment("standing_pivot", Identity, doubleArrayOf(0.0, 0.0, 94.0))))), data(keyValues = keyValues("\tTaggedSoundSettings_t = \n\t{\n\t\tm_taggedSounds = [ resource:\"$sounds\" ]\n\t}\n")))

    private fun keyText(bytes: ByteArray): String {
        val file = ResourceContainer.read(bytes)
        val data = Kv3Binary.decode(file.payload(file.blocks("DATA").single())).root as Kv3.Obj
        return ((data["m_modelInfo"] as Kv3.Obj)["m_keyValueText"] as Kv3.Str).value
    }

    @Test
    fun theOldSoundBindingIsRenamedAndNothingElseOfTheTextChanges() {
        val mod = model(listOf(mesh(null)), data(keyValues = oldBinding))
        val context = context(game(vanillaWithNewBinding))
        assertIs<RuleOutcome.Repairable>(sound(mod, context))
        val repaired = repaired(mod, context)
        assertEquals(oldBinding.replace("\tCitadelTaggedSoundSettings_t =", "\tTaggedSoundSettings_t ="), keyText(repaired))
        assertEquals(RuleOutcome.NotAffected, sound(repaired, context))
    }

    @Test
    fun aFailedFrameConversionLeavesEveryMeshUntouchedWhileSoundRepairSucceeds() {
        val quarterTurn = doubleArrayOf(0.0, 0.0, 0.7071067811865476, 0.7071067811865476)
        val pivot = doubleArrayOf(0.0, 0.0, -0.7071067811865476, 0.7071067811865476)
        val first = mesh(listOf(attachment("standing_pivot", pivot, doubleArrayOf(1.0, 2.0, 3.0))))
        val second = mesh(listOf(attachment("standing_pivot", pivot, doubleArrayOf(0.0, 0.0, 0.0)).replacing("value") { value ->
            (value as Kv3.Obj).replacing("m_vInfluenceOffsets") { offsets ->
                val slots = offsets as Kv3.Typed
                val zeros = Kv3.Typed(Kv3Type.ArrayAuxiliary, Kv3Type.DoubleZero, 0, List(3) { Kv3.Number(Kv3Type.DoubleZero, 0) })
                slots.copy(items = listOf(zeros) + slots.items.drop(1))
            }
        }))
        val mod = model(listOf(first, second), data(root = quarterTurn, keyValues = oldBinding))
        val current = model(listOf(mesh(listOf(attachment("standing_pivot", Identity, doubleArrayOf(-2.0, 1.0, 3.0))))), data(root = quarterTurn, keyValues = keyText(vanillaWithNewBinding)))
        val context = context(game(current))
        assertIs<RuleOutcome.Declined>(frame(mod, context), "a typed zero cannot encode the negative zero from this rotation")
        val repaired = assertIs<ResourceRepair.Repaired>(ModelRepairs.repair(path, mod, context))
        assertIs<RuleOutcome.Declined>(repaired.verdicts.single { it.rule == RootMotionAttachmentFrame }.outcome)
        assertIs<RuleOutcome.Repairable>(repaired.verdicts.single { it.rule == TaggedSoundBinding }.outcome)
        assertEquals(oldBinding.replace("\tCitadelTaggedSoundSettings_t =", "\tTaggedSoundSettings_t ="), keyText(repaired.bytes))
        val before = ResourceContainer.read(mod)
        val after = ResourceContainer.read(repaired.bytes)
        for (block in before.blocks("MDAT")) {
            assertContentEquals(before.payload(block), after.payload(after.blocks[block.index]), "MDAT#${block.index} must stay byte for byte")
        }
    }

    @Test
    fun aRuleFailingAfterReplacingBlocksLeavesThemToTheNextRuleUntouched() {
        val text = keyValues("CitadelTaggedSoundSettings_t = { m_taggedSounds = [ resource:\"$sounds\" ] }")
        val current = text.replace("CitadelTaggedSoundSettings_t", "TaggedSoundSettings_t")
        val mod = model(listOf(
            mesh(listOf(oldPivot)),
            mesh(listOf(attachment("standing_pivot", OldPivot, doubleArrayOf(0.0, 93.0, 0.0)))),
        ), data(keyValues = text))
        val game = mapOf(
            path to model(listOf(mesh(listOf(attachment("standing_pivot", Identity, doubleArrayOf(0.0, 0.0, 94.0))))), data(keyValues = current)),
            "${sounds}_c" to table("CTaggedSound_t"),
        )::get
        val failing = object : ModelRule by RootMotionAttachmentFrame {
            override fun assess(model: ModelDocument, context: RuleContext): RuleOutcome {
                val plan = assertIs<RuleOutcome.Repairable>(RootMotionAttachmentFrame.assess(model, context))
                return RuleOutcome.Repairable(plan.edits) { target ->
                    plan.apply(target)
                    throw Kv3Failure("late apply failure")
                }
            }
        }
        val repair = assertIs<ResourceRepair.Repaired>(ModelRepairs.repair(path, mod, RuleContext(game), listOf(failing, TaggedSoundBinding)))
        assertIs<RuleOutcome.Declined>(repair.verdicts[0].outcome)
        assertIs<RuleOutcome.Repairable>(repair.verdicts[1].outcome)
        val before = ResourceContainer.read(mod)
        val after = ResourceContainer.read(repair.bytes)
        for (block in before.blocks("MDAT")) assertContentEquals(before.payload(block), after.payload(after.blocks[block.index]))
        assertEquals(current, keyText(repair.bytes))
    }

    @Test
    fun anIntentionalNewBindingEvenEmptyIsLeftAlone() {
        val silent = keyValues("\tCitadelTaggedSoundSettings_t = { m_taggedSounds = [ resource:\"$sounds\" ] }\n\tTaggedSoundSettings_t = { m_taggedSounds = [ ] }\n")
        assertEquals(RuleOutcome.NotAffected, sound(model(listOf(mesh(null)), data(keyValues = silent)), context(game(vanillaWithNewBinding))))
        val emptyOld = keyValues("\tCitadelTaggedSoundSettings_t = { m_taggedSounds = [ resource:\"\" ] }\n")
        assertEquals(RuleOutcome.NotAffected, sound(model(listOf(mesh(null)), data(keyValues = emptyOld)), context(game(vanillaWithNewBinding))), "no table, nothing to keep playing")
    }

    @Test
    fun theModsOwnSoundTableIsUsedAndKept() {
        val custom = "scripts/tagged_sounds/custom_sounds.vdata"
        val text = keyValues("\tCitadelTaggedSoundSettings_t = { m_taggedSounds = [ resource:\"$custom\", resource:\"$sounds\" ] }\n")
        val mod = model(listOf(mesh(null)), data(keyValues = text))
        val set = mapOf("${custom}_c" to table("CTaggedSound_t"))::get
        val repaired = repaired(mod, context(game(vanillaWithNewBinding), set))
        assertTrue(keyText(repaired).contains("TaggedSoundSettings_t = { m_taggedSounds = [ resource:\"$custom\", resource:\"$sounds\" ] }"))
    }

    @Test
    fun soundBindingsThatCannotBeProvenAreNotRenamed() {
        val mod = model(listOf(mesh(null)), data(keyValues = oldBinding))
        assertIs<RuleOutcome.Unrepairable>(sound(mod, context(mapOf(path to vanillaWithNewBinding)::get)), "the table is nowhere")
        assertIs<RuleOutcome.Declined>(sound(mod, context(game(vanillaWithNewBinding, mapOf("${sounds}_c" to table("CSomethingElse_t"))))), "not a tagged sound table")
        assertIs<RuleOutcome.Declined>(sound(mod, context(game(vanillaWithNewBinding), above = { it == "${sounds}_c" })), "another mod replaces the table")
        assertIs<RuleOutcome.Declined>(sound(mod, context(game(vanilla))), "the game does not show the new name")
        val broken = model(listOf(mesh(null)), data(keyValues = oldBinding.replace("m_flScale = 0.83", "m_flScale = ")))
        assertIs<RuleOutcome.Declined>(sound(broken, context(game(vanillaWithNewBinding))), "text that does not parse")
        val twice = model(listOf(mesh(null)), data(keyValues = keyValues("\tCitadelTaggedSoundSettings_t = { m_taggedSounds = [ ] }\n\tCitadelTaggedSoundSettings_t = { m_taggedSounds = [ ] }\n")))
        assertIs<RuleOutcome.Declined>(sound(twice, context(game(vanillaWithNewBinding))))
    }

    @Test
    fun bothRulesComposeInOrderAndTheResultIsProven() {
        val mod = model(listOf(mesh(listOf(oldPivot))), data(keyValues = oldBinding))
        val game = game(vanillaWithNewBinding)
        val repaired = assertIs<ResourceRepair.Repaired>(ModelRepairs.repair(path, mod, context(game)))
        assertEquals(listOf(RootMotionAttachmentFrame, TaggedSoundBinding), repaired.verdicts.filter { it.outcome is RuleOutcome.Repairable }.map { it.rule })
        assertTrue(ModelRepairs.assess(path, repaired.bytes, context(game)).all { it.outcome == RuleOutcome.NotAffected })
    }

    @Test
    fun aContainerThatCannotBeRewrittenExactlyIsDeclinedByEveryRule() {
        val tail = oldModel + ByteArray(16)
        assertTrue(ModelRepairs.assess(path, tail, context()).all { it.outcome is RuleOutcome.Declined })
        assertTrue(ModelRepairs.assess("models/x.vtex_c", oldModel, context()).all { it.outcome == RuleOutcome.NotAffected }, "only models are checked")
    }
}
