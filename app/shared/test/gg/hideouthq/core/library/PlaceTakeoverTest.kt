package gg.hideouthq.core.library

import gg.hideouthq.core.data.LocalFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlaceTakeoverTest {
    private val root = GameRoot("/games/Deadlock")
    private val migration = MigrationId("m-1")

    private fun image(tag: String) = Sha256.image(tag.encodeToByteArray())

    private fun held(id: String, mod: Long, number: Int?) = ManifestRecord(
        id, root, "Held $id", placedAt(number),
        listOf(InstalledSet(InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, mod, mod * 10, null)), null, null, SetOrigin.Imported(image("archive-$id")), null, "pak01_dir.vpk", listOf(directory(image(id))))),
    )

    private fun mod(title: String, matched: Matched, position: LoadPosition?, vararg paths: String) = Incoming(
        title, matched, paths.first().substringAfterLast('/'), position,
        paths.map { path -> IncomingSet(path.substringAfterLast('/'), listOf(IncomingFile(CitadelPath(path), VpkPart.Directory, image(path)))) },
    )

    private fun gb(id: Long) = SubmissionIdentity(SubmissionKind.Mod, id, id * 10, null)

    private fun known(id: Long, sets: Int = 1) = Matched.Pinned(List(sets) { gb(id) }, null)

    private val voice = gb(656_621).copy(fileId = 1_702_938)

    private fun takeover(vararg incoming: Incoming, profiles: List<IncomingProfile> = emptyList(), addonsMount: Int? = 0, copies: List<BundleCopy> = emptyList()) =
        Takeover(migration, root, Manager.DeadlockModManager, addonsMount, incoming.toList(), copies, profiles, unmount = emptySet(), autoexec = null, duplicates = emptyList(), manifests = emptyList())

    private fun counter(): () -> String {
        var next = 0
        return { "x${++next}" }
    }

    private fun place(takeover: Takeover, manifest: LibraryManifest, addons: List<String>, copied: List<String> = emptyList()) = placeTakeover(takeover, manifest, addons, copied, counter())

    private fun AdoptionPlan.record(title: String): Adoption = adopted.single { it.record.title.startsWith(title) }

    private val TakeoverPlacement.order: List<String> get() = plan.target.activeProfile.orders.getValue(root)

    private val here = LibraryManifest(
        records = listOf(
            held("h02", 691_863, null), held("h06", 628_313, 6), held("h08", 712_362, 8), held("h09", 726_711, 9),
            held("h12", 690_417, null), held("h14", 724_457, 14), held("h15", 701_199, 15), held("h16", 729_019, 16),
        ),
    )
    private val hereAddons = listOf(1, 3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 14, 15, 16).map { "pak${it.toString().padStart(2, '0')}_dir.vpk" } +
        listOf("630975_pak81_dir.vpk", "637275_Percentage Rename.vpk", "637275_pak06_dir.vpk", "721342_pak01_dir.vpk", ".dmm.json", "replays", "profile_1726_ranked")
    private val dmm = takeover(
        mod("Mohammad & Kareem", known(696_085), LoadPosition(0, 1), "addons/pak01_dir.vpk"),
        mod("Mugetsu", Matched.Pinned(listOf(gb(656_621), voice), null), LoadPosition(0, 3), "addons/pak03_dir.vpk", "addons/pak04_dir.vpk"),
        mod("Pomni", known(673_959), LoadPosition(0, 5), "addons/pak05_dir.vpk"),
        mod("Vivian Banshee", known(631_628, 2), LoadPosition(0, 7), "addons/pak07_dir.vpk", "addons/pak11_dir.vpk"),
        mod("Yamato remodel", known(691_863), LoadPosition(0, 10), "addons/pak10_dir.vpk"),
        mod("Ace", known(626_469), LoadPosition(0, 13), "addons/pak13_dir.vpk"),
        mod("Plague Doctor", known(630_975), null, "addons/630975_pak81_dir.vpk"),
        mod("Numeric Health", known(637_275, 2), null, "addons/637275_Percentage Rename.vpk", "addons/637275_pak06_dir.vpk"),
        mod("Naineng", Matched.Unpinned(SubmissionRef(SubmissionKind.Mod, 721_342)), null, "addons/721342_pak01_dir.vpk"),
    )

    @Test
    fun everyFreeMountedSetOnThisComputerIsClaimedWhereItIsAndEveryOtherModComesInOff() {
        val placed = place(dmm, here, hereAddons)
        val plan = placed.plan
        for ((title, numbers) in listOf("Mohammad" to listOf(1), "Mugetsu" to listOf(3, 4), "Pomni" to listOf(5), "Yamato" to listOf(10), "Ace" to listOf(13))) {
            assertTrue(plan.record(title).claimed, "$title is claimed where it is")
            assertEquals(numbers, plan.record(title).record.numbers.map { it.value })
        }
        assertEquals(listOf(gb(656_621), voice).map(InstalledSource::Remote), plan.record("Mugetsu").record.sets.map { it.source }, "each set is its own file's")
        val vivian = plan.record("Vivian")
        assertEquals(Placement.Off, vivian.record.placement, "a mod split across numbers that are not its own block comes in off")
        assertEquals(listOf("addons/pak07_dir.vpk", "addons/pak11_dir.vpk"), vivian.sources.map { it.value })
        for (title in listOf("Vivian", "Plague", "Numeric", "Naineng")) {
            val record = plan.record(title).record
            assertTrue(record.files.all { it.citadelPath(record).value.startsWith("hideout_off/${record.modId}/set-") }, "$title moves into its own off folder under names that hold no number")
        }
        assertEquals(SubmissionRef(SubmissionKind.Mod, 721_342), (plan.record("Naineng").record.sets.single().source as InstalledSource.Local).submission, "a mod known only by its submission comes in local with it")
        val ids = plan.adopted.associate { it.record.title.substringBefore(' ') to it.record.installId }
        assertEquals(
            listOf(ids["Mohammad"], ids["Mugetsu"], ids["Pomni"], "h06", ids["Vivian"], "h08", "h09", ids["Yamato"], ids["Ace"], "h14", "h15", "h16", "h02", "h12", ids["Plague"], ids["Numeric"], ids["Naineng"]),
            placed.order,
            "the saved order is the engine's: every mod that loads before the first of Hideout's that loads after it, the split one at pak07's place",
        )
        assertEquals(setOf(ids["Mohammad"], ids["Mugetsu"], ids["Pomni"], ids["Vivian"], ids["Yamato"], ids["Ace"]), plan.target.activeProfile.enabled - here.profiles.activeProfile.enabled, "the saved book turns on every mod that loads today")
        assertEquals(setOf(ids["Mohammad"], ids["Mugetsu"], ids["Pomni"], ids["Yamato"], ids["Ace"]), plan.book.activeProfile.enabled - here.profiles.activeProfile.enabled, "the applied book only those claimed where they load")
        assertTrue(plan.book.follows(here.records + plan.adopted.map { it.record }), "the applied book follows what the import commits")
    }

    @Test
    fun aModHideoutHoldsInThisGameComesInLocalAndSoDoesTheSecondOfOneMod() {
        val plan = place(dmm, here, hereAddons).plan
        val yamato = assertIs<InstalledSource.Local>(plan.record("Yamato").record.sets.single().source, "Hideout holds mod-691863, off")
        assertEquals("pak10_dir.vpk" to SubmissionRef(SubmissionKind.Mod, 691_863), yamato.fileName to yamato.submission, "it keeps its file's name and the submission it is")
        assertEquals(InstalledSource.Remote(gb(696_085)), plan.record("Mohammad").record.sets.single().source)

        val split = takeover(mod("Mixed", known(5), LoadPosition(0, 3), "addons/pak03_dir.vpk"), mod("Mixed", known(5), null, "addons/5_pak09_dir.vpk"))
        val mixed = place(split, LibraryManifest(), listOf("pak03_dir.vpk", "5_pak09_dir.vpk")).plan
        assertEquals(InstalledSource.Remote(gb(5)), mixed.adopted[0].record.sets.single().source, "the sets that load keep the mod's identity")
        assertIs<InstalledSource.Local>(mixed.adopted[1].record.sets.single().source, "the off part is a second, local record")
        assertEquals(listOf(true, false), mixed.adopted.map { it.record.enabled })
        assertEquals("Mixed (5_pak09_dir.vpk)", mixed.adopted[1].record.title, "a title the game has gets the file's name after it")
    }

    @Test
    fun aModSplitAroundHideoutsOwnComesInOffAndTheSavedOrderPutsItWhereItsWinnerLoads() {
        val manifest = LibraryManifest(records = listOf(held("h08", 1, 8), held("h09", 2, 9)))
        val placed = place(takeover(mod("Split", known(3, 2), LoadPosition(0, 7), "addons/pak07_dir.vpk", "addons/pak11_dir.vpk")), manifest, listOf("pak07_dir.vpk", "pak08_dir.vpk", "pak09_dir.vpk", "pak11_dir.vpk"))
        val split = placed.plan.adopted.single()
        assertTrue(!split.claimed && !split.record.enabled)
        assertEquals(listOf(split.record.installId, "h08", "h09"), placed.order)
        assertTrue(split.record.installId in placed.plan.target.activeProfile.enabled, "the saved book turns it on, for the choices worker to place")
    }

    @Test
    fun aClaimNeedsItsOwnBareConsecutiveNamesThatNoOtherEntryUses() {
        val addons = listOf("pak05_dir.vpk", "pak05_extra.vpk", "pak07_dir.vpk", "PAK09_DIR.VPK")
        val placed = place(takeover(
            mod("Shared", Matched.Unpinned(null), LoadPosition(0, 5), "addons/pak05_dir.vpk"),
            mod("Bare", Matched.Unpinned(null), LoadPosition(0, 7), "addons/pak07_dir.vpk"),
            mod("Shouting", Matched.Unpinned(null), LoadPosition(0, 9), "addons/PAK09_DIR.VPK"),
        ), LibraryManifest(), addons)
        assertEquals(listOf(false, true, false), placed.plan.adopted.map { it.claimed })
        assertEquals(listOf(Placement.Off, Placement.On(Slot(7)), Placement.Off), placed.plan.adopted.map { it.record.placement })
    }

    @Test
    fun aModThatLoadsFromAnotherMountComesInOffAndTheSavedOrderPutsItWhereTheEngineLoadsIt() {
        val manifest = LibraryManifest(records = listOf(held("h01", 1, 1), held("h02", 2, 2)))
        val placed = place(takeover(mod("Global", Matched.Unpinned(null), LoadPosition(0, 5), "grimoire/pak05_dir.vpk"), addonsMount = 1), manifest, listOf("pak01_dir.vpk", "pak02_dir.vpk"))
        val global = placed.plan.adopted.single()
        assertEquals(Placement.Off, global.record.placement)
        assertEquals(listOf(global.record.installId, "h01", "h02"), placed.order, "the grimoire mount loads before addons")
        assertEquals(placed.order, placed.plan.target.activeProfile.onOrder(root), "and the saved book turns it on there")
    }

    @Test
    fun anImportNeverRunsOutOfNumbersSinceEveryModItCannotClaimComesInOff() {
        val crowded = LibraryManifest(records = (1..99).map { held("h$it", it.toLong(), it) })
        val offs = (1..120).map { mod("Off $it", Matched.Unpinned(null), null, "addons/.disabled/m${it}_dir.vpk") }
        val wide = mod("Two sets", Matched.Unpinned(null), LoadPosition(0, 1), "grimoire/pak01_dir.vpk", "grimoire/pak02_dir.vpk")
        val placed = place(takeover(*(offs + wide).toTypedArray()), crowded, (1..99).map { "pak${it.toString().padStart(2, '0')}_dir.vpk" })
        assertEquals(121, placed.plan.adopted.size, "every mod comes in")
        assertTrue(placed.plan.adopted.none { it.record.enabled }, "none of them takes a number")
        assertEquals(crowded.records.map { it.installId } + placed.plan.adopted.map { it.record.installId }, placed.plan.book.activeProfile.orders.getValue(root))
    }

    @Test
    fun whenEveryModThatLoadsIsClaimedTheSavedBookIsTheAppliedOneSoTheChoicesSettleInTheCommit() {
        val manifest = LibraryManifest(records = listOf(held("h06", 1, 6)))
        val placed = place(takeover(
            mod("First", Matched.Unpinned(null), LoadPosition(0, 1), "addons/pak01_dir.vpk"),
            mod("Later", Matched.Unpinned(null), LoadPosition(0, 9), "addons/pak09_dir.vpk"),
            mod("Disabled", Matched.Unpinned(null), null, "addons/631628_pak07_dir.vpk"),
        ), manifest, listOf("pak01_dir.vpk", "pak06_dir.vpk", "pak09_dir.vpk", "631628_pak07_dir.vpk"))
        assertEquals(placed.plan.target, placed.plan.book)
        val ids = placed.plan.adopted.map { it.record.installId }
        assertEquals(listOf(ids[0], "h06", ids[1], ids[2]), placed.order)
        assertEquals(setOf("h06", ids[0], ids[1]), placed.plan.book.activeProfile.enabled)
    }

    @Test
    fun sourceProfilesBecomeNewProfilesInBothBooksWithUniqueNamesAndTheModsThatAreOnJoinTheActiveOne() {
        val a = held("a", 1, 1)
        val b = held("b", 2, null)
        val ranked = Profile(ProfileId("ranked"), "Ranked", ProfileColour.Blue, setOf("a"), mapOf(root to listOf("a", "b")))
        val applied = ProfileBook.main(listOf(a, b)).let { it.copy(profiles = it.profiles + ranked) }
        val saved = applied.copy(active = ProfileId("ranked")).withOrder(root, listOf("b", "a"))
        val manifest = LibraryManifest(records = listOf(a, b), profiles = applied, savedChoices = SavedChoices(saved))
        val profiles = listOf(
            IncomingProfile("Ranked", listOf(IncomingMember.Adopted(0), IncomingMember.Held("b"), IncomingMember.Copied(0))),
            IncomingProfile("ranked ", listOf(IncomingMember.Adopted(1), IncomingMember.Held("gone"))),
            IncomingProfile("  ", listOf(IncomingMember.Held("a"))),
        )
        val placed = place(takeover(mod("On", Matched.Unpinned(null), LoadPosition(0, 5), "addons/pak05_dir.vpk"), mod("Off", Matched.Unpinned(null), null, "addons/x_dir.vpk"), profiles = profiles, copies = listOf(BundleCopy("Copied", Matched.Unpinned(null), "c_dir.vpk", true, listOf(LocalFile("/bundle/c_dir.vpk", "c_dir.vpk", 1))))), manifest, listOf("pak01_dir.vpk", "pak05_dir.vpk"), copied = listOf("copied-1"))
        val on = placed.plan.record("On").record.installId
        val off = placed.plan.record("Off").record.installId
        assertEquals(listOf("Ranked 2", "ranked 3", "Imported"), placed.profiles)
        for (book in listOf(placed.plan.book, placed.plan.target)) {
            assertTrue(on in book.activeProfile.enabled && off !in book.activeProfile.enabled, "the mod that is on joins ${book.active.value}")
            val made = book.profiles.drop(2)
            assertEquals(listOf("Ranked 2", "ranked 3", "Imported"), made.map { it.name })
            assertEquals(listOf(setOf(on, "b", "copied-1"), setOf(off), setOf("a")), made.map { it.enabled })
            assertEquals(book.profiles.size, book.profiles.map { it.name.lowercase() }.distinct().size)
        }
        assertEquals(ProfileId("main"), placed.plan.book.active)
        assertEquals(ProfileId("ranked"), placed.plan.target.active, "unsettled saved choices are merged, not replaced")
        assertEquals(listOf("b", "a", on, off, "copied-1"), placed.order, "Hideout's own keep the order the user saved, and a copy not installed yet comes last")
    }

    @Test
    fun eachProfileGetsAnOrderTheAppliedOneByNumberTheOthersTheirOwnAndAnIncomingOneItsSourcesWithEveryAdoptedModWhereTheEngineLoadsIt() {
        val a = held("a", 1, 1)
        val b = held("b", 2, null)
        val applied = ProfileBook.main(listOf(a, b)).let { it.copy(profiles = it.profiles + Profile(ProfileId("ranked"), "Ranked", ProfileColour.Blue, setOf("b"), mapOf(root to listOf("b", "a")))) }
        val saved = applied.withOrder(root, listOf("b", "a")).membership(ProfileId("main"), setOf("b"), enabled = true)
        val manifest = LibraryManifest(records = listOf(a, b), profiles = applied, savedChoices = SavedChoices(saved))
        val incoming = IncomingProfile("Fun", listOf(IncomingMember.Adopted(0), IncomingMember.Held("b")))
        val placed = place(takeover(mod("On", Matched.Unpinned(null), LoadPosition(0, 5), "addons/pak05_dir.vpk"), mod("Off", Matched.Unpinned(null), null, "addons/x_dir.vpk"), profiles = listOf(incoming)), manifest, listOf("pak01_dir.vpk", "pak05_dir.vpk"))
        val on = placed.plan.record("On").record.installId
        val off = placed.plan.record("Off").record.installId
        fun ProfileBook.order(name: String) = profiles.single { it.name == name }.orders.getValue(root)

        assertEquals(listOf("a", on, "b", off), placed.plan.book.order("Main"), "the applied order has the claimed mod among those on by its number")
        assertEquals(listOf("b", "a", on, off), placed.plan.target.order("Main"), "Hideout's own as saved, the adopted ones where the engine loads them")
        assertEquals(listOf("b", on, "a", off), placed.plan.book.order("Ranked"), "another profile keeps its own mods' order")
        assertEquals(listOf("b", "a", on, off), placed.plan.target.order("Ranked"), "another profile keeps its own mods' order")
        for (book in listOf(placed.plan.book, placed.plan.target)) {
            assertEquals(listOf(on, "a", "b", off), book.order("Fun"), "an incoming profile has its members in its source's order, in their places")
            assertEquals(setOf(on, "b"), book.profiles.single { it.name == "Fun" }.enabled)
        }
        assertIs<PendingChange.Adopt>(LibraryManifest(records = listOf(a, b), pending = PendingChange.Adopt(placed.plan, AdoptStep.Linking)).pending, "both books order exactly what the import commits")
    }

    @Test
    fun aProfileAnEarlierRunOfTheImportMadeWithTheSameMembersIsNotMadeAgainAndOneWithNothingOnIsMadeAllTheSame() {
        val a = held("a", 1, 1)
        val b = held("b", 2, null)
        val ranked = Profile(ProfileId("ranked"), "Ranked", ProfileColour.Blue, setOf("a"), mapOf(root to listOf("a", "b")))
        val manifest = LibraryManifest(records = listOf(a, b), profiles = ProfileBook.main(listOf(a, b)).let { it.copy(profiles = it.profiles + ranked) })
        val again = listOf(
            IncomingProfile(" Ranked", listOf(IncomingMember.Held("a"))),
            IncomingProfile("Ranked", listOf(IncomingMember.Held("b"))),
            IncomingProfile("Ranked", emptyList()),
            IncomingProfile("Fun", listOf(IncomingMember.Held("gone"))),
        )
        val placed = place(takeover(profiles = again), manifest, listOf("pak01_dir.vpk"))
        assertEquals(listOf("Ranked", "Ranked 2", "Ranked 3", "Fun"), placed.profiles, "the same name with other members is a profile of its own, and so is one with nothing on")
        assertEquals(listOf("Main", "Ranked", "Ranked 2", "Ranked 3", "Fun"), placed.plan.book.profiles.map { it.name })
        assertEquals(emptySet(), placed.plan.book.profiles.last().enabled)
    }

    private val classic = mod("Classic", known(724_744), LoadPosition(0, 1), "game/citadel/addons/pak01_dir.vpk")

    private fun placedNames(manifest: LibraryManifest, name: String?) =
        place(takeover(classic).copy(liveProfileName = name), manifest, listOf("pak01_dir.vpk")).plan.target.profiles.map { it.name }

    @Test
    fun anEmptyMainTakesTheNameOfTheProfileItsModsCameFrom() {
        assertEquals(listOf("Ranked"), placedNames(LibraryManifest(), "Ranked"))
    }

    @Test
    fun aMainWithoutANameToTakeStaysMain() {
        assertEquals(listOf("Main"), placedNames(LibraryManifest(), null))
    }

    @Test
    fun aProfileTheUserNamedKeepsItsName() {
        val book = ProfileBook.main().let { book -> book.copy(profiles = book.profiles.map { it.copy(name = "Mine") }) }
        assertEquals(listOf("Mine"), placedNames(LibraryManifest(profiles = book), "Ranked"))
    }
}
