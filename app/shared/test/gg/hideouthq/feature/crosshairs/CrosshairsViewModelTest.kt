package gg.hideouthq.feature.crosshairs

import gg.hideouthq.core.crosshair.with
import gg.hideouthq.core.autoexec.mine
import gg.hideouthq.core.crosshair.Adjustable
import gg.hideouthq.core.crosshair.ConvarNumber
import gg.hideouthq.core.crosshair.Crosshair
import gg.hideouthq.core.crosshair.CrosshairCodes
import gg.hideouthq.core.crosshair.CrosshairColor
import gg.hideouthq.core.crosshair.CrosshairId
import gg.hideouthq.core.crosshair.CrosshairOrigin
import gg.hideouthq.core.crosshair.CrosshairPreset
import gg.hideouthq.core.crosshair.CrosshairSetting
import gg.hideouthq.core.crosshair.HeroSettings
import gg.hideouthq.core.crosshair.ImportedCrosshair
import gg.hideouthq.core.crosshair.crosshair
import gg.hideouthq.core.crosshair.community.CommunityCrosshair
import gg.hideouthq.core.crosshair.community.CrosshairStyle
import gg.hideouthq.core.data.Loadable
import gg.hideouthq.AppContainer
import gg.hideouthq.core.data.CacheStorage
import gg.hideouthq.core.data.HeroSettingsCopy
import gg.hideouthq.core.data.fake.FakeCommunityCrosshairs
import gg.hideouthq.core.data.SavedNote
import gg.hideouthq.core.data.fake.InMemoryHeroSettings
import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.navigation.CrosshairsTab
import gg.hideouthq.feature.withViewModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class CrosshairsViewModelTest {
    private val mintCross = community("crosshair_mint", "Mint cross", "kiri", "2026-10-20T10:00:00Z", likes = 9, crosshair(CrosshairColor(0, 255, 140), 3.0, 1.5, -6.0, 1.0, lineBorder = 0.0, fixedGap = true, dotOpacity = 0.0, dotBorderOpacity = 0.0))
    private val pinpoint = community("crosshair_pin", "Pinpoint", "vexa", "2026-10-19T10:00:00Z", likes = 8, crosshair(CrosshairColor(0, 255, 255), 4.0, 2.0, 0.0, 0.0, fixedGap = true, dotOpacity = 1.0, dotBorderOpacity = 1.0))
    private val plus = community("crosshair_plus", "Plus", "oddball", "2026-10-18T10:00:00Z", likes = 7, crosshair(CrosshairColor.White, 2.0, 2.5, -9.0, 1.0, fixedGap = false, dotOpacity = 0.0, dotBorderOpacity = 0.0))
    private val limeDot = community("crosshair_lime", "Lime dot", "kiri", "2025-11-16T10:00:00Z", likes = 1, crosshair(CrosshairColor(170, 255, 0), 3.0, 2.0, 0.0, 0.0, lineBorder = 0.0, fixedGap = false, dotOpacity = 1.0, dotBorderOpacity = 0.8))
    private val library = listOf(mintCross, pinpoint, plus, limeDot)
    private val now = Instant.parse("2026-10-26T12:00:00Z")
    private val clock = object : Clock {
        override fun now() = this@CrosshairsViewModelTest.now
    }

    private inner class Page(val viewModel: CrosshairsViewModel, val settings: InMemoryHeroSettings) {
        val state: CrosshairsUiState get() = viewModel.uiState.value
        val browse: BrowseResults get() = assertIs<Loadable.Ready<BrowseResults>>(state.browse.results).value
        val selected get() = assertNotNull(state.yours.selected, "the editor shows a crosshair")

        fun saved(): Crosshair? = settings.file(InMemoryHeroSettings.Cloud)?.let { HeroSettings.parse(it)?.crosshair }

        fun card(name: String): CommunityCard = browse.cards.single { it.crosshair.name == name }
    }

    private fun page(
        tab: CrosshairsTab = CrosshairsTab.Browse,
        community: List<CommunityCrosshair> = library,
        test: suspend TestScope.(Page) -> Unit,
    ) = withViewModels { viewModels ->
        val settings = HeroSettings.New.with(mine).render().let { InMemoryHeroSettings(mapOf(HeroSettingsCopy.Game to it, InMemoryHeroSettings.Cloud to it)) }
        val container = AppContainer.fake(communityCrosshairs = FakeCommunityCrosshairs(community), heroSettings = settings)
        val viewModel = viewModels.keep(CrosshairsViewModel(container.crosshairs, container.communityCrosshairs, tab, clock, ZoneOffset.UTC))
        backgroundScope.launch { viewModel.uiState.collect {} }
        val page = Page(viewModel, settings)
        eventually("the sample game's saved crosshair is read") { container.crosshairs.state.value.inUse != null }
        runCurrent()
        test(page)
    }

    @Test
    fun withNoSteamCloudCopyForTheAccountUseInGameSaysToStartDeadlockOnce() = withViewModels { viewModels ->
        val settings = InMemoryHeroSettings(mapOf(HeroSettingsCopy.Game to HeroSettings.New.with(mine).render()))
        val container = AppContainer.fake(communityCrosshairs = FakeCommunityCrosshairs(library), heroSettings = settings)
        val viewModel = viewModels.keep(CrosshairsViewModel(container.crosshairs, container.communityCrosshairs, CrosshairsTab.Browse, clock, ZoneOffset.UTC))
        backgroundScope.launch { viewModel.uiState.collect {} }
        eventually("Use in game to say why it cannot be pressed") { viewModel.uiState.value.cannotUse == CannotUse.NotSavedYet }
        assertNull(viewModel.uiState.value.inUse)
    }

    @Test
    fun withoutSteamTheNoteThatDeadlockMightNotUseItShowsRightAfterUseInGameOnly() = withViewModels { viewModels ->
        val settings = InMemoryHeroSettings(mapOf(HeroSettingsCopy.Game to HeroSettings.New.with(mine).render()), account = null)
        val container = AppContainer.fake(communityCrosshairs = FakeCommunityCrosshairs(library), heroSettings = settings)
        val viewModel = viewModels.keep(CrosshairsViewModel(container.crosshairs, container.communityCrosshairs, CrosshairsTab.Browse, clock, ZoneOffset.UTC))
        backgroundScope.launch { viewModel.uiState.collect {} }
        eventually("the game's own copy is read") { container.crosshairs.state.value.inUse != null }
        runCurrent()
        assertEquals(SavedNote.NextStart, viewModel.uiState.value.note, "opening the page says nothing of Steam")
        viewModel.selectCommunity(pinpoint.id)
        viewModel.useCommunity()
        eventually("the note that Deadlock might not use it") { viewModel.uiState.value.note == SavedNote.GameFolderOnly }
    }

    @Test
    fun whenYourCrosshairsCannotBeReadUseInGameSaysSo() = withViewModels { viewModels ->
        val unreadable = object : CacheStorage by MemoryStorage() {
            override suspend fun read(name: String): String? = throw IOException("permission denied")
        }
        val container = AppContainer.fake(communityCrosshairs = FakeCommunityCrosshairs(library), crosshairsData = unreadable)
        val viewModel = viewModels.keep(CrosshairsViewModel(container.crosshairs, container.communityCrosshairs, CrosshairsTab.Browse, clock, ZoneOffset.UTC))
        backgroundScope.launch { viewModel.uiState.collect {} }
        eventually("Use in game to say why it cannot be pressed") { viewModel.uiState.value.cannotUse == CannotUse.YoursUnreadable }
    }

    @Test
    fun theRouteChoosesTheTabAndTheTabsSwitch() = page(CrosshairsTab.Yours) { page ->
        assertEquals(CrosshairsTab.Yours, page.state.tab)
        assertEquals("Mine", page.selected.name, "Yours opens on the crosshair in use")
        page.viewModel.selectTab(CrosshairsTab.Browse)
        runCurrent()
        assertEquals(CrosshairsTab.Browse, page.state.tab)
        assertEquals(4, page.state.yours.cards.size, "Mine and the three presets")
    }

    @Test
    fun theInUsePillOpensYoursOnTheCrosshairInUse() = page { page ->
        page.viewModel.selectYours(CrosshairId("preset-classic"))
        page.viewModel.editInUse()
        runCurrent()
        assertEquals(CrosshairsTab.Yours, page.state.tab)
        assertEquals(page.state.inUse, page.state.yours.selected)
    }

    @Test
    fun theFirstCrosshairIsPickedAndAFilteredOutPickGivesWayToTheFirstResult() = page { page ->
        assertEquals("Mint cross", page.browse.selected?.crosshair?.name, "Popular puts the most liked first")
        page.viewModel.selectCommunity(plus.id)
        runCurrent()
        assertEquals("Plus", page.browse.selected?.crosshair?.name)
        page.viewModel.setStyle(CrosshairStyle.DotOnly, true)
        runCurrent()
        assertEquals(listOf("Pinpoint", "Lime dot"), page.browse.cards.map { it.crosshair.name })
        assertEquals("Pinpoint", page.browse.selected?.crosshair?.name, "Plus is filtered out, so the first result is picked")
        page.viewModel.setQuery("kiri")
        runCurrent()
        assertEquals("Lime dot", page.browse.selected?.crosshair?.name, "the search covers creators")
        page.viewModel.clearFilters()
        runCurrent()
        assertEquals(4, page.browse.cards.size)
    }

    @Test
    fun useInGameWritesItToTheGamesSettingsAndPutsItInUse() = page { page ->
        page.viewModel.useCommunity()
        runCurrent()
        assertEquals("Mint cross", page.state.inUse?.name)
        assertTrue(page.card("Mint cross").inUse)
        assertTrue(page.card("Mint cross").saved, "a crosshair used joins Yours")
        assertEquals(5, page.state.yours.cards.size, "Mine stays in Yours when another is used")
        eventually("it is saved in the game's settings") { page.saved() == mintCross.crosshair }
    }

    @Test
    fun saveTogglesTheCrosshairInYoursButNotTheOneInUse() = page { page ->
        page.viewModel.toggleSaved()
        runCurrent()
        assertTrue(page.card("Mint cross").saved)
        assertTrue(page.state.yours.cards.any { (it.origin as? CrosshairOrigin.Community)?.id == mintCross.id })
        page.viewModel.toggleSaved()
        runCurrent()
        assertFalse(page.card("Mint cross").saved)
        assertEquals(4, page.state.yours.cards.size)

        page.viewModel.useCommunity()
        page.viewModel.toggleSaved()
        runCurrent()
        assertTrue(page.card("Mint cross").saved, "the crosshair in use stays in Yours")
    }

    @Test
    fun aSavedCrosshairEditedInYoursIsNoLongerSavedOnItsCardAndSaveKeepsItAgain() = page { page ->
        page.viewModel.toggleSaved()
        runCurrent()
        val saved = page.state.yours.cards.single { (it.origin as? CrosshairOrigin.Community)?.id == mintCross.id }
        page.viewModel.selectYours(saved.id)
        page.viewModel.setValue(Adjustable.Gap, 5.0)
        runCurrent()
        assertFalse(page.card("Mint cross").saved, "the edited entry is the user's own, not Mint cross as saved")
        page.viewModel.toggleSaved()
        runCurrent()
        assertTrue(page.card("Mint cross").saved)
        assertEquals(listOf(ConvarNumber.of(5.0), mintCross.crosshair.lines.gap), page.state.yours.cards.filter { (it.origin as? CrosshairOrigin.Community)?.id == mintCross.id }.map { it.crosshair.lines.gap })
    }

    @Test
    fun onlyTheCommunityCrosshairTheOneInUseCameFromCannotBeUnsaved() {
        val twin = community("crosshair_twin", "Twin", "nyx", "2026-10-17T10:00:00Z", likes = 0, mintCross.crosshair)
        page(community = library + twin) { page ->
            page.viewModel.useCommunity()
            runCurrent()
            assertFalse(page.card("Mint cross").canToggleSaved, "the crosshair in use came from Mint cross, so Mint cross stays saved")
            assertTrue(page.card("Twin").inUse, "Twin has the settings in use")
            assertTrue(page.card("Twin").canToggleSaved, "but it is another crosshair, which can be saved")
            page.viewModel.selectCommunity(twin.id)
            page.viewModel.toggleSaved()
            runCurrent()
            assertTrue(page.card("Twin").saved)
            assertEquals("Mint cross", page.state.inUse?.name, "saving its twin leaves the crosshair in use as it was")
        }
    }

    @Test
    fun editACopyOpensAnEntryOfItsOwnInYours() = page { page ->
        page.viewModel.editCopy()
        runCurrent()
        assertEquals(CrosshairsTab.Yours, page.state.tab)
        assertEquals("Mint cross", page.selected.name)
        assertIs<CrosshairOrigin.CommunityCopy>(page.selected.origin)
        page.viewModel.setValue(Adjustable.Gap, 5.0)
        runCurrent()
        assertEquals(ConvarNumber.of(5.0), page.selected.crosshair.lines.gap)
        assertEquals(mintCross.crosshair, page.card("Mint cross").crosshair.crosshair, "the community original never changes")
        assertFalse(page.card("Mint cross").saved, "a copy is not the community crosshair saved")
    }

    @Test
    fun copyCodeIsAShareCodeImportReadsBack() = page { page ->
        val code = assertNotNull(page.viewModel.communityCode())
        assertTrue(code.startsWith("HX1."))
        assertEquals(ImportedCrosshair("Mint cross", mintCross.crosshair), CrosshairCodes.parse(code))
        page.viewModel.selectTab(CrosshairsTab.Yours)
        assertEquals(ImportedCrosshair("Mine", mine), CrosshairCodes.parse(assertNotNull(page.viewModel.shareCode())), "Share gives the crosshair in the editor")
    }

    @Test
    fun editsToTheCrosshairInUseSaveOnTheirOwnAndResetReturnsToItsOrigin() = page(CrosshairsTab.Yours) { page ->
        page.viewModel.setValue(Adjustable.Gap, 2.0)
        page.viewModel.setColor(CrosshairColor(255, 59, 59))
        runCurrent()
        assertEquals(page.state.inUse?.id, page.selected.id, "the crosshair edited stays in use")
        val edited = mine.with(gap = 2.0, color = CrosshairColor(255, 59, 59))
        eventually("the edits are saved in the game's settings without Use in game") { page.saved() == edited }

        page.viewModel.reset()
        runCurrent()
        assertEquals(mine, page.selected.crosshair)
        eventually("Reset is saved too") { page.saved() == mine }
    }

    @Test
    fun editsToAnotherCrosshairWaitForUseInGame() = page(CrosshairsTab.Yours) { page ->
        page.viewModel.selectYours(CrosshairId("preset-classic"))
        page.viewModel.setValue(Adjustable.Length, 8.0)
        page.viewModel.setValue(Adjustable.LineBorder, 0.0)
        runCurrent()
        val classic = CrosshairPreset.Classic.crosshair.with(length = 8.0, lineBorder = 0.0)
        assertEquals(classic, page.selected.crosshair)
        assertEquals("Mine", page.state.inUse?.name)
        page.viewModel.useSelected()
        runCurrent()
        assertEquals("Classic", page.state.inUse?.name)
        eventually("Use in game writes the edited crosshair") { page.saved() == classic }
    }

    @Test
    fun yoursMakesDuplicatesRenamesAndDeletesButKeepsTheCrosshairInUse() = page(CrosshairsTab.Yours) { page ->
        page.viewModel.create()
        runCurrent()
        assertEquals(Crosshair.GameDefault, page.selected.crosshair, "New starts from the default")
        page.viewModel.setName("")
        runCurrent()
        assertEquals("", page.state.yours.nameDraft, "a blank name stays in the field")
        assertEquals("New crosshair", page.selected.name, "and is never kept")
        page.viewModel.setName("Tiny ")
        page.viewModel.commitName()
        runCurrent()
        assertEquals("Tiny", page.selected.name)
        assertNull(page.state.yours.nameDraft)
        page.viewModel.duplicate(page.selected.id)
        runCurrent()
        assertEquals("Tiny copy", page.selected.name)
        page.viewModel.delete(page.selected.id)
        runCurrent()
        assertEquals("Mine", page.selected.name, "with the one picked gone, the one in use is")
        assertEquals(5, page.state.yours.cards.size)
        page.viewModel.delete(page.selected.id)
        runCurrent()
        assertEquals(5, page.state.yours.cards.size, "the crosshair in use cannot be deleted")
    }

    @Test
    fun importReadsLinesAddsThemToYoursAndPicksThem() = page { page ->
        page.viewModel.openImport()
        page.viewModel.setImportText("not a crosshair")
        page.viewModel.confirmImport()
        runCurrent()
        assertEquals(ImportUi("not a crosshair", unreadable = true), page.state.importing)

        page.viewModel.setImportText(CrosshairSetting.entries.joinToString("\n") { "${it.convar} \"${it.format(plus.crosshair)}\"" })
        runCurrent()
        assertFalse(assertNotNull(page.state.importing).unreadable, "typing again clears the error")
        page.viewModel.confirmImport()
        runCurrent()
        assertNull(page.state.importing)
        assertEquals(CrosshairsTab.Yours, page.state.tab)
        assertEquals("Imported crosshair", page.selected.name)
        assertEquals(plus.crosshair, page.selected.crosshair)
        assertEquals(5, page.state.yours.cards.size)
    }

    private suspend fun eventually(what: String, done: () -> Boolean) {
        withContext(Dispatchers.Default) { withTimeoutOrNull(5_000) { while (!done()) delay(20) } } ?: fail("Timed out: $what")
    }

    private fun community(id: String, name: String, creator: String, createdAt: String, likes: Int, crosshair: Crosshair) =
        CommunityCrosshair(id, name, creator, Instant.parse(createdAt), likes, crosshair)
}
