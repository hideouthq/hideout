package gg.hideouthq.core.data.fake

import gg.hideouthq.core.customgames.Artifact
import gg.hideouthq.core.customgames.Choices
import gg.hideouthq.core.customgames.CustomGamesFeed
import gg.hideouthq.core.customgames.CustomGamesFeedSource
import gg.hideouthq.core.customgames.CustomGamesFetch
import gg.hideouthq.core.customgames.CustomGamesPlatform
import gg.hideouthq.core.customgames.DesktopOs
import gg.hideouthq.core.customgames.DisplayName
import gg.hideouthq.core.customgames.FetchProblem
import gg.hideouthq.core.customgames.FolderRead
import gg.hideouthq.core.customgames.GameRevision
import gg.hideouthq.core.customgames.GameSetup
import gg.hideouthq.core.customgames.GameTraffic
import gg.hideouthq.core.customgames.HowToPlay
import gg.hideouthq.core.customgames.InGamePort
import gg.hideouthq.core.customgames.InGameStamp
import gg.hideouthq.core.customgames.InstalledMode
import gg.hideouthq.core.customgames.InviteCode
import gg.hideouthq.core.customgames.InviteOffer
import gg.hideouthq.core.customgames.JournalEntry
import gg.hideouthq.core.customgames.LegendEntry
import gg.hideouthq.core.customgames.LinkEvent
import gg.hideouthq.core.customgames.LinkPath
import gg.hideouthq.core.customgames.LinkProcess
import gg.hideouthq.core.customgames.LinkStop
import gg.hideouthq.core.customgames.LocalPlayer
import gg.hideouthq.core.customgames.MenusScreenPort
import gg.hideouthq.core.customgames.MenusStamp
import gg.hideouthq.core.customgames.Mode
import gg.hideouthq.core.customgames.ModeContent
import gg.hideouthq.core.customgames.ModeFact
import gg.hideouthq.core.customgames.ModeFolders
import gg.hideouthq.core.customgames.ModeId
import gg.hideouthq.core.customgames.ModeKind
import gg.hideouthq.core.customgames.ModeListing
import gg.hideouthq.core.customgames.ModeOrigin
import gg.hideouthq.core.customgames.ModeRuntime
import gg.hideouthq.core.customgames.ModeSetting
import gg.hideouthq.core.customgames.Player
import gg.hideouthq.core.customgames.PreflightProblem
import gg.hideouthq.core.customgames.PreparedHost
import gg.hideouthq.core.customgames.ProcessIdentity
import gg.hideouthq.core.customgames.Refusal
import gg.hideouthq.core.customgames.ScoreSide
import gg.hideouthq.core.customgames.SectionKind
import gg.hideouthq.core.customgames.SectionState
import gg.hideouthq.core.customgames.ServerEvent
import gg.hideouthq.core.customgames.SessionEvent
import gg.hideouthq.core.customgames.SessionFiles
import gg.hideouthq.core.customgames.SessionJournal
import gg.hideouthq.core.customgames.SessionOwned
import gg.hideouthq.core.customgames.SettingKey
import gg.hideouthq.core.customgames.SettingOption
import gg.hideouthq.core.customgames.SettingValue
import gg.hideouthq.core.customgames.Sha256
import gg.hideouthq.core.customgames.SharedPack
import gg.hideouthq.core.customgames.StageResult
import gg.hideouthq.core.customgames.StepIcon
import gg.hideouthq.core.customgames.Transfer
import gg.hideouthq.core.customgames.download
import gg.hideouthq.core.library.GameActivity
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.GamesLines
import gg.hideouthq.core.library.LibraryProblem
import gg.hideouthq.core.library.SessionAdmission
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import java.time.LocalDate
import kotlin.time.Duration
import kotlin.time.Instant

class FakeLinkProcess(override val identity: ProcessIdentity? = ProcessIdentity(4242, 1_000)) : LinkProcess {
    private val lines = Channel<LinkEvent>(Channel.UNLIMITED)
    var stopAnswers = true
    var stops = 0
        private set
    var kills = 0
        private set
    val enters = mutableListOf<Duration>()
    var enterAnswer: List<LinkEvent> = listOf(LinkEvent.Entered, LinkEvent.Game(GameTraffic.Flowing))
    val joins = mutableListOf<Pair<GameSetup, SharedPack?>>()
    var joinAnswer: List<LinkEvent> = listOf(LinkEvent.Linked, LinkEvent.Path(LinkPath.Direct))

    override val events: Flow<LinkEvent> = lines.receiveAsFlow()

    fun say(event: LinkEvent) {
        lines.trySend(event)
    }

    fun endOutput(cause: Throwable? = null) {
        lines.close(cause)
    }

    override suspend fun enter(window: Duration) {
        enters += window
        enterAnswer.forEach(::say)
    }

    override suspend fun join(setup: GameSetup, shared: SharedPack?) {
        joins += setup to shared
        joinAnswer.forEach(::say)
    }

    override suspend fun stop() {
        stops++
        if (stopAnswers) say(LinkEvent.Stopped(LinkStop.StdinClosed))
    }

    override suspend fun kill() {
        kills++
        say(LinkEvent.Stopped(LinkStop.Signal))
    }
}

class FakeSessionFiles(activity: GameActivity = GameActivity.Closed, private val root: GameRoot = GameRoot("/games/Deadlock")) : SessionFiles {
    override val clientActivity = MutableStateFlow(activity)
    private var lines: GamesLines = GamesLines.Off

    override suspend fun game(): GameRoot = root

    override suspend fun gamesLines(): GamesLines = lines

    override suspend fun useCustomGames(on: Boolean): LibraryProblem? {
        lines = if (on) GamesLines.Mounted else GamesLines.Off
        return null
    }

    override suspend fun admit(): SessionAdmission = SessionAdmission.Held(root)

    override suspend fun launchClient(): LibraryProblem? = null

    override suspend fun closeClient() = Unit

    override suspend fun resume(game: GameRoot) = Unit

    override suspend fun release(): LibraryProblem? = null
}

class FakeCustomGamesPlatform(
    override val journal: SessionJournal = SessionJournal(MemoryStorage()),
    private val chatty: Boolean = true,
    override val folders: ModeFolders = ModeFolders(MemoryStorage()),
) : CustomGamesPlatform {
    var prepare: suspend (Mode, InviteOffer, CustomGamesFeed?, (Transfer) -> Unit) -> SessionEvent.PrepareAnswer =
        { _, offer, _, _ -> SessionEvent.RuntimePrepared(SamplePrepared.copy(offer = offer), SamplePort) }
    val hostRequests = mutableListOf<Pair<Mode, InviteOffer>>()
    var player: LocalPlayer? = SamplePlayer
    var prepareGuest: suspend ((Transfer) -> Unit) -> Unit = {}
    var startFails: Exception? = null
    var revision: GameRevision? = GameRevision(1)
    var revisionReads = 0
        private set
    val links = mutableListOf<FakeLinkProcess>()
    val hosted = mutableListOf<Triple<PreparedHost, GameRoot, Int>>()
    val hostSetups = mutableListOf<GameSetup>()
    val joined = mutableListOf<InviteCode>()
    var offered: InviteOffer? = SampleOffer
    val guestsPrepared = mutableListOf<Mode?>()
    val guestsFeeds = mutableListOf<CustomGamesFeed?>()

    val staged = mutableListOf<Pair<GameRoot, ModeContent?>>()
    var stageResult: StageResult = StageResult.Staged(emptyList())
    var stageGate: CompletableDeferred<Unit>? = null

    var menusStamp: MenusStamp? = SampleStamp
    var rebuildGives: MenusStamp? = SampleStamp
    val menusRebuilds = mutableListOf<Sha256?>()
    val gamesFoldersRemoved = mutableListOf<GameRoot>()

    var updatePending = false
    val removed = mutableListOf<Pair<GameRoot, SessionOwned>>()
    var leftoversStand = false
    var guestResult: SessionEvent.PrepareAnswer = SessionEvent.GuestPrepared(SampleContent, SampleRenderer)
    val leftoversEnded = mutableListOf<JournalEntry>()

    override suspend fun prepareHost(mode: Mode, choices: Choices, feed: CustomGamesFeed?, progress: (Transfer) -> Unit): SessionEvent.PrepareAnswer {
        val me = player ?: return SessionEvent.RuntimeFailed(Refusal.Preflight(PreflightProblem.NoSteamAccount))
        val offer = InviteOffer.of(mode, choices, me, SampleShared.takeIf { mode.origin is ModeOrigin.Folder })
        hostRequests += mode to offer
        return prepare(mode, offer, feed, progress).also { if (it is SessionEvent.RuntimePrepared && mode.origin is ModeOrigin.Feed) record(mode) }
    }

    val installedModes = mutableMapOf<ModeId, InstalledMode>()

    var installGate: CompletableDeferred<Unit>? = null
    var installProblem: FetchProblem? = null
    val installs = mutableListOf<Mode>()

    private fun record(mode: Mode) {
        installedModes[mode.id] = InstalledMode(mode, mode.download ?: 0, installedModes[mode.id]?.lastPlayed)
    }

    override suspend fun installed(): Map<ModeId, InstalledMode> = installedModes.toMap()

    override suspend fun install(mode: Mode, feed: CustomGamesFeed, progress: (Transfer) -> Unit): FetchProblem? {
        installs += mode
        progress(Transfer(0, mode.download ?: 0))
        installGate?.await()
        installProblem?.let { return it }
        record(mode)
        return null
    }

    override suspend fun remove(id: ModeId, feed: CustomGamesFeed?) {
        installedModes -= id
    }

    override suspend fun played(id: ModeId) {
        installedModes[id]?.let { installedModes[id] = it.copy(lastPlayed = SamplePlayedAt) }
    }

    override suspend fun localPlayer(): LocalPlayer? = player

    val folderReads = mutableMapOf<String, FolderRead>()

    override suspend fun readFolder(path: String): FolderRead =
        folderReads[path] ?: FolderRead.Read(FakeCustomGamesFeed.SampleFolder.copy(origin = ModeOrigin.Folder(path)))

    override fun startHost(prepared: PreparedHost, game: GameRoot, port: Int, setup: GameSetup): LinkProcess {
        startFails?.let { throw it }
        hosted += Triple(prepared, game, port)
        hostSetups += setup
        return link().also {
            if (chatty) {
                it.say(LinkEvent.InviteReady(SampleCode))
                it.say(LinkEvent.Server(ServerEvent.Ready("street")))
                val host = SamplePlayer.let { me -> Player(me.name?.value.orEmpty(), me.steamId) }
                (listOf(host) + SampleFriends).take(prepared.offer.capacity).forEachIndexed { slot, player -> it.say(LinkEvent.Server(ServerEvent.Joined(slot, player))) }
                it.say(LinkEvent.Reported(listOf(SampleScore)))
            }
        }
    }

    val placed = mutableListOf<SharedPack>()
    var placeProblem: FetchProblem? = null

    override suspend fun placeShared(pack: SharedPack): FetchProblem? {
        placed += pack
        return placeProblem
    }

    override suspend fun prepareGuest(mode: Mode?, shared: SharedPack?, feed: CustomGamesFeed?, progress: (Transfer) -> Unit): SessionEvent.PrepareAnswer {
        guestsPrepared += mode
        guestsFeeds += feed
        prepareGuest(progress)
        if (guestResult is SessionEvent.GuestPrepared) mode?.let(::record)
        return (guestResult as? SessionEvent.GuestPrepared)?.takeIf { shared != null }?.let { it.copy(content = it.content.copy(shared = shared)) } ?: guestResult
    }

    override suspend fun ownedBeforeServer(game: GameRoot): SessionOwned = SampleOwned

    override suspend fun menus(game: GameRoot, renderer: Sha256?, rebuild: Boolean): MenusStamp? {
        if (rebuild) {
            menusRebuilds += renderer
            if (renderer != null && rebuildGives != null) menusStamp = rebuildGives
        }
        return menusStamp
    }

    val devSessionsCleared = mutableListOf<GameRoot>()

    override suspend fun clearEndedDevSession(game: GameRoot) {
        devSessionsCleared += game
    }

    var aheadOfMenus: String? = null

    override suspend fun menusBlockedBy(game: GameRoot): String? = aheadOfMenus

    var aheadOfInGame: String? = null

    override suspend fun inGameBlockedBy(game: GameRoot): String? = aheadOfInGame

    override suspend fun stage(game: GameRoot, content: ModeContent?): StageResult {
        staged += game to content
        if (content == null) return StageResult.Staged(emptyList())
        stageGate?.await()
        return stageResult
    }

    override suspend fun removeGamesFolder(game: GameRoot): Boolean {
        gamesFoldersRemoved += game
        return true
    }

    override suspend fun removeLeftovers(game: GameRoot, owned: SessionOwned): Boolean {
        removed += game to owned
        return !leftoversStand
    }

    override suspend fun gameUpdatePending(game: GameRoot): Boolean = updatePending

    override fun startJoin(code: InviteCode): LinkProcess {
        startFails?.let { throw it }
        joined += code
        return link().also { if (chatty) it.say(offered?.let(LinkEvent::Offer) ?: LinkEvent.BadOffer) }
    }

    private fun link() = FakeLinkProcess().also {
        if (!chatty) it.joinAnswer = emptyList()
        links += it
    }

    override suspend fun gameRevision(game: GameRoot): GameRevision? {
        revisionReads++
        return revision
    }

    override suspend fun endLeftover(entry: JournalEntry) {
        leftoversEnded += entry
    }

    companion object {
        const val SamplePort = 27_015
        val SampleContent = ModeContent(listOf("content"))
        val SampleRenderer = Sha256.parse("e".repeat(64))
        val SampleStamp = MenusStamp("e".repeat(64), "https://hideouthq.gg/bridge/", MenusScreenPort, 1, InGameStamp(InGamePort, "a".repeat(64), emptyMap()))
        val SampleSetup = GameSetup(SampleStamp.screen, SampleStamp.page)
        val SampleOwned = SessionOwned(appIdFile = true)
        val SamplePlayedAt: Instant = Instant.fromEpochSeconds(1_800_000_000)
        val SamplePlayer = LocalPlayer("76561198000000001", DisplayName.parse("nori"))
        val SampleShared = SharedPack(checkNotNull(Sha256.parse("5".repeat(64))), 64_000_000)
        val SamplePrepared = PreparedHost("modlock", "modlock-host", listOf("platform"), SampleContent, InviteOffer.of(FakeCustomGamesFeed.SampleFolder, Choices.None, SamplePlayer, SampleShared), SamplePlayer, renderer = SampleRenderer)
        val SampleCode = checkNotNull(InviteCode.parse("P95-MAN-26D"))

        val SampleOffer = InviteOffer.of(FakeCustomGamesFeed.BebopWars, Choices.None, LocalPlayer("76561198000000002", DisplayName.parse("Ann")))
        val SampleFriends = listOf(Player("Ann", "76561198000000002"), Player("Kit", "76561198000000003"), Player("Rook", "76561198000000004"), Player("Wren", "76561198000000005"), Player("Ash", "76561198000000006"))

        val SampleScore = SectionState.Scoreboard(listOf(ScoreSide(25, listOf(0, 2)), ScoreSide(18, listOf(1, 3))), listOf("First to 25"))
    }
}

class FakeCustomGamesFeed(
    private val kept: CustomGamesFeed? = null,
    var answer: CustomGamesFetch = CustomGamesFetch.Fresh(SampleModes),
) : CustomGamesFeedSource {
    override suspend fun lastGood(): CustomGamesFeed? = kept

    var fetches = 0
        private set

    override suspend fun fetch(): CustomGamesFetch {
        fetches++
        return answer
    }

    companion object {
        private val SampleArtifact = Artifact("https://example.test/a.zip", 5, checkNotNull(Sha256.parse("a".repeat(64))))

        val SampleListing = ModeListing("Arena", "Last one standing wins.", "Fight in the arena.", 2..8, listOf("Hideout team"), LocalDate.of(2026, 11, 1))

        val SampleMode = Mode(ModeId("arena"), "1.0.0", SampleListing, ModeOrigin.Feed(SampleArtifact, null))

        val SampleFolder = Mode(ModeId("arena"), "1.0.0", SampleListing, ModeOrigin.Folder("/built/arena"))

        private fun pick(text: String) = checkNotNull(SettingValue.parse(text))

        val BebopWarsListing = ModeListing(
            name = "Bebop Wars",
            tagline = "Hook enemies across the canal and finish them on your bank. First to 25 kills wins.",
            description = "A winter hook arena for 1v1, 2v2 and 3v3. First to 25 kills wins. After eight minutes the higher score wins, and a tie goes to sudden death. " +
                "A Hook hit takes 55% of the target's maximum health and drags them onto your bank, so two hits normally kill. " +
                "A pulled enemy who survives fights on there with Hook, melee and parry, and their teammates can hook them home. A rescue pays 25 Souls. " +
                "Everyone starts with Hook and 300 Souls. The shop sells eight items in three sections, setup, utility and defense, and you equip one from each. " +
                "Your kills unlock Hook upgrades at 3, 5 and 10: longer range, a shorter cooldown, then Rebound. Death keeps your purchases and returns you to your shop after four seconds.",
            players = 2..6,
            creators = listOf("Hideout team"),
            released = LocalDate.of(2026, 11, 6),
            kind = ModeKind.Teams,
            minutes = 8..10,
            goal = "First to 25 kills",
            curated = true,
            howToPlay = listOf(
                HowToPlay("Hook them across", "Throw your Hook across the canal. A hit takes 55% of their health and drags them onto your bank.", StepIcon.Hook),
                HowToPlay("Finish them", "A second Hook normally kills, and melee works too. Each kill is a point. First to 25 wins, or the higher score after eight minutes.", StepIcon.Trophy),
                HowToPlay(
                    "Hook teammates home",
                    "When an enemy pulls a teammate over, hook them back before they fall. A rescue pays 25 Souls. Spend Souls at your shop on items that last the match.",
                    StepIcon.Souls,
                ),
            ),
            whatsNew = listOf(
                "Kills score now. A Hook hit takes 55% of health, so two hits normally kill.",
                "Pulled enemies fight on your bank with Hook, melee and parry.",
                "Hook a pulled teammate home. A rescue pays 25 Souls.",
                "The shop has eight items in three sections: setup, utility and defense.",
                "Hook upgrades at 3, 5 and 10 kills.",
                "A new arena and a scoreboard in the game's own style.",
            ),
            settings = listOf(
                ModeSetting(
                    checkNotNull(SettingKey.parse("teams")), "Teams", "Players per team. Shop prices and kill payouts are the same in every format.",
                    listOf(SettingOption(pick("1v1"), "1v1", 2), SettingOption(pick("2v2"), "2v2", 4), SettingOption(pick("3v3"), "3v3", 6)), pick("2v2"),
                ),
            ),
            sections = listOf(SectionKind.Scoreboard),
            summary = "Hook an enemy across the canal, then finish them with a second Hook or melee. Hook pulled teammates home. First to 25 kills wins.",
            legend = listOf(
                LegendEntry("Your team", 0xE6B977), LegendEntry("Enemy team", 0x8FB4E8), LegendEntry("Hook cable", 0xF3EEE4),
                LegendEntry("The canal, crossed only by a Hook", 0x2E6E9E), LegendEntry("Each side has its own Curiosity Shop", 0xF1DFB8),
            ),
            facts = listOf(
                ModeFact("To win", "First to 25 kills", "Higher score after 8 minutes, then sudden death"),
                ModeFact("Hook", "55% of health per hit", "Two hits normally kill"),
                ModeFact("Start with", "Hook and 300 Souls", "Eight items in three shop sections"),
                ModeFact("Arena", "A winter canal with a shop on each bank"),
            ),
        )

        val BebopWars = Mode(
            ModeId("bebop-wars"),
            "0.1.0",
            BebopWarsListing,
            ModeOrigin.Feed(Artifact("https://example.test/bebop-wars-rules.zip", 48_000, checkNotNull(Sha256.parse("b".repeat(64)))), Artifact("https://example.test/bebop-wars-client.zip", 12_000_000, checkNotNull(Sha256.parse("c".repeat(64))))),
        )

        val SampleModes = CustomGamesFeed(
            sequence = 1,
            runtime = ModeRuntime(mapOf(DesktopOs.Linux to SampleArtifact, DesktopOs.Windows to SampleArtifact), SampleArtifact, SampleArtifact, SampleArtifact),
            modes = listOf(SampleMode),
        )

        val Placeholder = SampleModes.copy(modes = listOf(BebopWars))
    }
}
