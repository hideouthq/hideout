package gg.hideouthq.feature.moddetail.viewer

import androidx.compose.ui.unit.dp
import gg.hideouthq.feature.moddetail.ViewerState
import gg.hideouthq.preview.AnimationCategory
import gg.hideouthq.preview.PreviewAbility
import gg.hideouthq.preview.PreviewClip
import gg.hideouthq.preview.PictureRole
import gg.hideouthq.preview.PreviewIcon
import gg.hideouthq.preview.PreviewPicture
import gg.hideouthq.preview.ResourceOwner
import gg.hideouthq.preview.ResourceSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ViewerCatalogueTest {
    private fun clip(id: String, category: AnimationCategory?, owner: ResourceOwner = ResourceOwner.Game, changed: Boolean = false) =
        PreviewClip(id, 1_000, category, ResourceSource(owner, "models/heroes/silver/clips/$id.vnmclip_c", "a".repeat(64)), changed)

    private fun icon(name: String, owner: ResourceOwner = ResourceOwner.Game, changed: Boolean = false) =
        PreviewIcon("icons/$name.png", ResourceSource(owner, "panorama/images/hud/abilities/$name.vtex_c", "b".repeat(64)), changed)

    private val clips = listOf(
        clip("ultimate", AnimationCategory.Abilities),
        clip("intro", AnimationCategory.Poses, ResourceOwner.Mod, changed = true),
        clip("idle", AnimationCategory.Idle),
        clip("fire", AnimationCategory.Combat),
        clip("victory", AnimationCategory.Poses),
        clip("run", AnimationCategory.Movement),
        clip("heavy_melee", AnimationCategory.Combat, ResourceOwner.Mod),
        clip("hero_select", AnimationCategory.Poses, ResourceOwner.Mod, changed = true),
        clip("ability_1", AnimationCategory.Abilities),
    )

    private val abilities = listOf(
        PreviewAbility(4, "ultimate", "Ultimate", clipId = "ultimate", icon = icon("ultimate", ResourceOwner.Mod, changed = true), vanillaIcon = icon("ultimate_vanilla")),
        PreviewAbility(1, "ability_1", "Ability 1", clipId = "ability_1", icon = icon("ability_1", ResourceOwner.Mod)),
        PreviewAbility(2, "ability_2", "Ability 2", clipId = null, icon = icon("ability_2", ResourceOwner.Mod)),
        PreviewAbility(3, "ability_3", "Ability 3", clipId = "not_exported", icon = icon("ability_3")),
    )

    private val catalogue = ViewerCatalogue(clips, abilities)

    @Test
    fun clipsGroupInTheFixedCategoryOrderWithChangedClipsFirstAndOtherwiseStable() {
        assertEquals(
            listOf(ViewerCategory.Idle, ViewerCategory.Movement, ViewerCategory.Combat, ViewerCategory.Abilities, ViewerCategory.Poses),
            catalogue.groups.map { it.category },
        )
        assertEquals(listOf("fire", "heavy_melee"), catalogue.group(ViewerCategory.Combat)?.clips?.map { it.id })
        assertEquals(listOf("intro", "hero_select", "victory"), catalogue.group(ViewerCategory.Poses)?.clips?.map { it.id })
        assertEquals(
            listOf("idle", "run", "fire", "heavy_melee", "ability_1", "ultimate", "intro", "hero_select", "victory"),
            catalogue.clips.map { it.id },
        )
    }

    @Test
    fun onlyAVerifiedByteChangeMarksAPillAndAModOwnedSourceAloneDoesNot() {
        assertEquals(listOf(false, false, false, false, true), catalogue.groups.map { it.changed })
    }

    @Test
    fun clipsWithoutMetadataShareOnePillUnderTheirOwnNames() {
        val old = ViewerCatalogue(listOf(PreviewClip("shop_menu_base", 2_867), PreviewClip("models/heroes_wip/inferno/clips/ui_shop_idle", 2_867)), emptyList())
        assertEquals(listOf(ViewerCategory.Animations), old.groups.map { it.category })
        assertEquals(listOf("Shop Menu Base", "Shop Idle"), old.clips.map { it.label }, "an unrecognised name is Title-Cased; a recognised phrase reads out")
        assertFalse(old.groups.single().changed)

        val mixed = ViewerCatalogue(listOf(PreviewClip("t_pose", 1_000), clip("idle", AnimationCategory.Idle)), emptyList())
        assertEquals(listOf(ViewerCategory.Idle, ViewerCategory.Animations), mixed.groups.map { it.category })
    }

    @Test
    fun readableLabelsCoverTheRequiredPhrasesAndTitleCaseEverythingElse() {
        fun label(id: String) = PreviewClip(id, 1_000).label
        assertEquals("Crouching", label("weapon_crouch_idle"))
        assertEquals("Running", label("out_of_combat_run_n"), "directional suffixes collapse into one reading")
        assertEquals("Main Menu", label("main_menu_idle"), "a more specific phrase wins over the bare idle it also contains")
        assertEquals("Melee Dash", label("melee_dash"), "dash alone is not mistaken for Air Dash without air")
        assertEquals("Rope Climb Up", label("rope_climb_up"), "an unfamiliar name is read, never rejected")
        assertEquals(
            listOf("Relaxed Idle", "Idle", "Light Melee", "Heavy Melee", "Parry", "Mantle", "Dash", "Slide", "Knocked Down", "Zipline", "Hero Select"),
            listOf("out_of_combat_stand_idle", "weapon_stand_idle", "melee_quick_1", "melee_hit", "parry", "mantle_64", "dash_ground",
                "slide_start", "knockdown_small", "zipline_attached_loop", "ui_hero_select").map(::label),
        )
    }

    @Test
    fun theMenuCollapsesSameNamedVariantsButKeepsAModChangedOrAbilityLinkedOneOfThem() {
        val runs = listOf(
            clip("weapon_run_n", AnimationCategory.Movement),
            clip("weapon_run_s", AnimationCategory.Movement),
            clip("weapon_run_e", AnimationCategory.Movement, changed = true),
        )
        val movement = ViewerCatalogue(runs, emptyList()).group(ViewerCategory.Movement)!!
        assertEquals(listOf("weapon_run_e", "weapon_run_n", "weapon_run_s"), movement.clips.map { it.id }, "every real clip still steps through, unchanged")
        assertEquals(listOf("weapon_run_e", "weapon_run_n"), movement.menu.map { it.id }, "the changed clip and the first duplicate survive; the second collapses")

        val jumps = listOf(clip("jump_light", AnimationCategory.Combat), clip("jump_heavy", AnimationCategory.Combat))
        val jumpAbility = PreviewAbility(1, "jump_heavy", "Jump Heavy", clipId = "jump_heavy", icon = icon("jump_heavy"))
        val combat = ViewerCatalogue(jumps, listOf(jumpAbility)).group(ViewerCategory.Combat)!!
        assertEquals(listOf("Jump", "Jump"), combat.clips.map { it.label }, "two different clips can read the same name")
        assertEquals(listOf("jump_light", "jump_heavy"), combat.menu.map { it.id }, "an ability-linked clip keeps its own row despite the name collision")
    }

    @Test
    fun anAbilitysOwnNameStandsInForItsClipsGuessedLabel() {
        val echo = PreviewAbility(1, "echo", "Echo Overdrive", clipId = "echo_dash", icon = icon("echo"))
        val catalogue = ViewerCatalogue(listOf(clip("echo_dash", AnimationCategory.Abilities)), listOf(echo))
        val dash = catalogue.clip("echo_dash")!!
        assertEquals("Echo Dash", dash.label, "the clip's own guess from its id")
        assertEquals("Echo Overdrive", catalogue.label(dash), "the ability's curated name wins when it plays one")
    }

    @Test
    fun aRememberedClipTheBundleLacksFallsBackToTheFirst() {
        assertEquals("idle", catalogue.resolve(null)?.id)
        assertEquals("idle", catalogue.resolve("gone")?.id)
        assertEquals("victory", catalogue.resolve("victory")?.id)
        assertNull(catalogue.clip("gone"))
        assertNull(ViewerCatalogue.Empty.resolve("idle"))
    }

    private fun still(clip: String?) = PreviewPicture(PictureRole.Detail, "pictures/detail.webp", clip, if (clip == null) 0 else 500, 800, 1200, -100.0, "studio_three_point")

    @Test
    fun theStageOpensOnTheClipItsStillWasCapturedOnNotTheFirstChangedOne() {
        val idles = listOf(clip("weapon_stand_idle", AnimationCategory.Idle), clip("here_emote_idle_01", AnimationCategory.Idle, ResourceOwner.Mod, changed = true))
        val catalogue = ViewerCatalogue(idles, emptyList(), still("weapon_stand_idle"))
        assertEquals("here_emote_idle_01", catalogue.clips.first().id)
        assertEquals("weapon_stand_idle", catalogue.resolve(null)?.id)
        assertEquals(ViewerCategory.Idle, ViewerState().activeCategory(catalogue))
    }

    @Test
    fun aStillCapturedInTheRestPoseOpensTheStageOnTheRestPoseThoughTheBundleHasClips() {
        val rest = ViewerCatalogue(clips, abilities, still(null))
        assertNull(rest.resolve(null), "the rest pose, not the first clip")
        assertNull(ViewerState().activeCategory(rest))
        assertEquals("idle", ViewerState().stepClip(1, rest).clipId, "right from the rest pose plays the first clip")
        assertEquals("victory", ViewerState().stepClip(-1, rest).clipId, "left plays the last")
        assertEquals("fire", ViewerState().pickCategory(ViewerCategory.Combat, rest).clipId)
        assertEquals("ability_1", ViewerState().playAbility(1, rest).clipId)
        assertNull(ViewerCatalogue.Empty.step(null, 1))
    }

    @Test
    fun anAbilityPlaysOnlyAClipTheBundleExports() {
        assertEquals(listOf(1, 2, 3, 4), catalogue.abilities.map { it.slot })
        assertEquals("ability_1", catalogue.abilityClip(1)?.id)
        assertNull(catalogue.abilityClip(2), "no clip")
        assertNull(catalogue.abilityClip(3), "a clip the bundle does not export")
        assertEquals("ultimate", catalogue.abilityClip(4)?.id)
        assertNull(catalogue.abilityClip(5))
    }

    @Test
    fun anInactivePillPlaysItsFirstClipAndOpensItsMenuAndTheActivePillTogglesIt() {
        val start = ViewerState()
        val combat = start.pickCategory(ViewerCategory.Combat, catalogue)
        assertEquals("fire", combat.clipId)
        assertEquals(ViewerCategory.Combat, combat.openMenu(catalogue))
        val closed = combat.pickCategory(ViewerCategory.Combat, catalogue)
        assertEquals("fire", closed.clipId)
        assertNull(closed.openMenu(catalogue))
        assertEquals(ViewerCategory.Combat, closed.pickCategory(ViewerCategory.Combat, catalogue).openMenu(catalogue))
        assertEquals(ViewerCategory.Idle, start.activeCategory(catalogue))
        assertEquals(ViewerCategory.Idle, start.pickCategory(ViewerCategory.Idle, catalogue).openMenu(catalogue))

        val paused = combat.copy(playing = false).pickCategory(ViewerCategory.Poses, catalogue)
        assertEquals("intro", paused.clipId)
        assertTrue(paused.playing)
        assertEquals(ViewerCategory.Poses, paused.openMenu(catalogue), "the open menu moves to the other pill")
        assertEquals(start, start.pickCategory(ViewerCategory.Animations, catalogue), "a pill the bundle lacks does nothing")
    }

    @Test
    fun aRowPlaysItsClipAndKeepsTheMenuOpen() {
        val open = ViewerState().pickCategory(ViewerCategory.Combat, catalogue)
        val heavy = open.copy(playing = false).playClip("heavy_melee", catalogue)
        assertEquals("heavy_melee", heavy.clipId)
        assertTrue(heavy.playing)
        assertEquals(ViewerCategory.Combat, heavy.openMenu(catalogue))
        assertEquals(heavy, heavy.playClip("gone", catalogue))
        assertNull(ViewerState().playClip("victory", catalogue).openMenu(catalogue), "a closed menu stays closed")
    }

    @Test
    fun arrowsStepThroughEveryClipWrappingAndAnOpenMenuFollowsItsPill() {
        val first = ViewerState()
        assertEquals("victory", first.stepClip(-1, catalogue).clipId)
        assertEquals("run", first.stepClip(1, catalogue).clipId)
        assertEquals("idle", first.copy(clipId = "victory").stepClip(1, catalogue).clipId)

        val open = first.pickCategory(ViewerCategory.Combat, catalogue).playClip("heavy_melee", catalogue)
        val crossed = open.stepClip(1, catalogue)
        assertEquals("ability_1", crossed.clipId)
        assertEquals(ViewerCategory.Abilities, crossed.openMenu(catalogue))
        assertEquals(ViewerCategory.Combat, crossed.stepClip(-1, catalogue).openMenu(catalogue))
        assertNull(first.stepClip(1, catalogue).openMenu(catalogue), "a closed menu stays closed")
        assertEquals(ViewerState(), ViewerState().stepClip(1, ViewerCatalogue.Empty))
    }

    @Test
    fun abilityKeysPlayARealClipAndCloseTheMenuAndAnAbsentClipDoesNothing() {
        val open = ViewerState().pickCategory(ViewerCategory.Poses, catalogue).copy(playing = false)
        val ultimate = open.playAbility(4, catalogue)
        assertEquals("ultimate", ultimate.clipId)
        assertTrue(ultimate.playing)
        assertNull(ultimate.openMenu(catalogue))
        assertEquals(ViewerCategory.Abilities, ultimate.activeCategory(catalogue))
        for (slot in listOf(2, 3, 5)) assertEquals(open, open.playAbility(slot, catalogue), "slot $slot")
    }

    @Test
    fun aMenuLeftOpenForAClipANewBundleLacksIsClosed() {
        val open = ViewerState().pickCategory(ViewerCategory.Poses, catalogue)
        val refreshed = ViewerCatalogue(listOf(clip("idle", AnimationCategory.Idle), clip("run", AnimationCategory.Movement)), emptyList())
        assertEquals("idle", refreshed.resolve(open.clipId)?.id)
        assertNull(open.openMenu(refreshed))
        assertEquals("run", open.stepClip(1, refreshed).clipId)
    }

    @Test
    fun aMenuStartsJustBeforeItsPillUnlessItWouldCrossTheStripOrTheEdge() {
        val strip = 940.dp
        assertEquals(MenuAnchor(194.dp, 56f / 256f), menuAnchor(200.dp, 300.dp, 16.dp, strip))
        assertEquals(MenuAnchor(550.dp, 200f / 256f), menuAnchor(700.dp, 800.dp, 16.dp, strip), "it ends just after a pill near the strip")
        assertEquals(16.dp, menuAnchor(10.dp, 60.dp, 16.dp, strip).start)
        assertEquals(16.dp, menuAnchor(30.dp, 80.dp, 16.dp, 200.dp).start, "a stage narrower than the menu keeps its start edge")
    }

    @Test
    fun theStripShowsTheModsIconsAndSaysWhetherTheModChangedTheIconOrTheAnimation() {
        val labels = AbilityLabels("strip", changed = "both", changedIcon = "icon", changedAnimation = "animation", unchanged = "unchanged",
            noClip = "no clip", noClipChanged = "icon, no clip")
        val modded = abilityTiles("b".repeat(32), catalogue, catalogue.clip("ultimate"), labels)
        assertEquals(listOf("unchanged", "no clip", "no clip", "icon"), modded.map { it.detail })
        assertEquals(listOf(true, false, false, true), modded.map { it.played })
        assertEquals(listOf(false, false, false, true), modded.map { it.selected })
        assertEquals(listOf(false, false, false, true), modded.map { it.changed })
        val cast = clip("cast", AnimationCategory.Abilities, ResourceOwner.Mod, changed = true)
        val both = ViewerCatalogue(
            listOf(cast),
            listOf(
                PreviewAbility(1, "a", "A", clipId = "cast", icon = icon("a", ResourceOwner.Mod, changed = true)),
                PreviewAbility(2, "b", "B", clipId = "cast", icon = icon("b")),
                PreviewAbility(3, "c", "C", clipId = null, icon = icon("c", ResourceOwner.Mod, changed = true)),
            ),
        )
        assertEquals(listOf("both", "animation", "icon, no clip"), abilityTiles("b".repeat(32), both, null, labels).map { it.detail })
    }

    @Test
    fun theAbilitiesPillListsAbilitiesInSlotOrderAndNamesEveryAbilityAClipPlays() {
        assertEquals(listOf("ability_1", "ultimate"), catalogue.group(ViewerCategory.Abilities)!!.clips.map { it.id })
        val throwClip = clip("throw", AnimationCategory.Abilities)
        val shared = ViewerCatalogue(
            listOf(throwClip),
            listOf(
                PreviewAbility(1, "bolt", "Vexing Bolt", clipId = "throw", icon = icon("bolt")),
                PreviewAbility(3, "hex", "Rabbit Hex", clipId = "throw", icon = icon("hex")),
            ),
        )
        assertEquals("Vexing Bolt · Rabbit Hex", shared.label(throwClip))
    }
}
