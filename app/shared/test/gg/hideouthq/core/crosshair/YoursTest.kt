package gg.hideouthq.core.crosshair

import gg.hideouthq.core.library.Manager
import kotlin.test.Test
import kotlin.test.assertEquals

class YoursTest {
    private val green = CrosshairPreset.Classic.crosshair.with(color = CrosshairColor(0, 255, 0))
    private val classic = SavedCrosshair(CrosshairId("preset-classic"), "Classic", CrosshairOrigin.Preset(CrosshairPreset.Classic), CrosshairPreset.Classic.crosshair)
    private val wide = SavedCrosshair(CrosshairId("preset-widegap"), "Wide gap", CrosshairOrigin.Preset(CrosshairPreset.WideGap), CrosshairPreset.WideGap.crosshair)
    private val copy = classic.copy(id = CrosshairId("a"), name = "Classic copy")

    @Test
    fun theCrosshairInUseIsTheOneWithTheSettingsHideoutsBlockRuns() {
        assertEquals(Yours(listOf(classic, wide), classic.id), inUse(classic.crosshair, listOf(classic, wide), lastUsed = null))
        assertEquals(Yours(listOf(classic, wide), wide.id), inUse(wide.crosshair, listOf(classic, wide), lastUsed = classic.id), "the last used only breaks ties")
    }

    @Test
    fun theLastUsedBreaksATieAndTheFirstWinsWithoutIt() {
        val kept = listOf(classic, wide, copy)
        assertEquals(copy.id, inUse(classic.crosshair, kept, lastUsed = copy.id).inUse)
        assertEquals(classic.id, inUse(classic.crosshair, kept, lastUsed = wide.id).inUse)
    }

    @Test
    fun settingsNoneHoldsShowAsMineInUseWithoutJoiningWhatIsKept() {
        val kept = listOf(classic, wide)
        val yours = inUse(green, kept, lastUsed = classic.id)
        val mine = SavedCrosshair(CrosshairId("mine"), "Mine", CrosshairOrigin.Mine(green), green)
        assertEquals(Yours(listOf(mine) + kept, mine.id), yours)
        assertEquals(CrosshairId("mine-2"), inUse(wide.crosshair.with(gap = 9.0), kept + mine, lastUsed = null).inUse, "a Mine kept before keeps its id")
    }

    @Test
    fun nothingIsInUseWhileTheGamesCrosshairIsNotKnown() {
        val kept = listOf(classic)
        assertEquals(Yours(kept, null), inUse(null, kept, lastUsed = classic.id))
    }

    @Test
    fun crosshairsJsonKeepsEveryOriginAndItsResetValues() {
        val base = green.with(gap = -7.0)
        val kept = Kept(
            listOf(
                classic.copy(crosshair = green),
                SavedCrosshair(CrosshairId("c"), "Mint", CrosshairOrigin.Community("crosshair_1", "kiri", base), green),
                SavedCrosshair(CrosshairId("d"), "Mint", CrosshairOrigin.CommunityCopy("crosshair_1", null, base), base),
                SavedCrosshair(CrosshairId("f"), "Pasted", CrosshairOrigin.Imported(base), base),
                SavedCrosshair(CrosshairId("g"), "Mine", CrosshairOrigin.FromManager(Manager.DeadlockModManager, base), green),
                SavedCrosshair(CrosshairId("h"), "New crosshair", CrosshairOrigin.Made, Crosshair.GameDefault),
                SavedCrosshair(CrosshairId("mine"), "Mine", CrosshairOrigin.Mine(base), base),
            ),
            lastUsed = CrosshairId("g"),
            waiting = base,
        )
        assertEquals(kept, KeptCodec.decode(KeptCodec.encode(kept)))
    }

    @Test
    fun noFileIsAFirstRunAndAFileNotThisFormatIsNeverTakenForOne() {
        val seed = Kept(listOf(classic, SavedCrosshair(CrosshairId("preset-dot"), "Dot", CrosshairOrigin.Preset(CrosshairPreset.Dot), CrosshairPreset.Dot.crosshair), wide), lastUsed = null)
        assertEquals(seed, KeptCodec.decode(null))
        assertEquals(listOf<Kept?>(null, null), listOf("not json", """{"crosshairs":{}}""").map(KeptCodec::decode))
    }

    @Test
    fun aSettingAnEntryDoesNotHoldIsTheGames() {
        val bigDot = Adjustable.DotSize.set(wide.crosshair, 9.0)
        val saved = KeptCodec.encode(Kept(listOf(wide.copy(crosshair = bigDot)), lastUsed = null))
        val older = saved.replace(Regex(",?\"(dot_size|outline_color_[rgb]|pip_gap_collapse)\":\"[^\"]*\""), "")
        assertEquals(Kept(listOf(wide), lastUsed = null), KeptCodec.decode(older))
    }

    @Test
    fun aBrokenEntryCostsOnlyItself() {
        val saved = KeptCodec.encode(Kept(listOf(classic, wide), lastUsed = wide.id))
        val unreadValue = saved.replaceFirst(Regex("(\"settings\":\\{[^}]*\"color_r\":\")255\""), "$1white\"")
        val wrongType = saved.replaceFirst(Regex("\"settings\":\\{[^}]*\\}"), "\"settings\":\"white\"")
        assertEquals(listOf(Kept(listOf(wide), wide.id), Kept(listOf(wide), wide.id)), listOf(unreadValue, wrongType).map(KeptCodec::decode))
    }
}
