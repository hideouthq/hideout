package gg.hideouthq.customgames

import gg.hideouthq.core.customgames.Artifact
import gg.hideouthq.core.customgames.CustomGames
import gg.hideouthq.core.customgames.CustomGamesFeed
import gg.hideouthq.core.customgames.CustomGamesPlatform
import gg.hideouthq.core.customgames.DesktopOs
import gg.hideouthq.core.customgames.InviteCode
import gg.hideouthq.core.customgames.JournalEntry
import gg.hideouthq.core.customgames.LinkEvent
import gg.hideouthq.core.customgames.LinkProcess
import gg.hideouthq.core.customgames.LinkStop
import gg.hideouthq.core.customgames.Choices
import gg.hideouthq.core.customgames.ModeOrigin
import gg.hideouthq.core.customgames.OfferContent
import gg.hideouthq.core.customgames.ModeRuntime
import gg.hideouthq.core.customgames.SettingKey
import gg.hideouthq.core.customgames.SettingValue
import gg.hideouthq.core.customgames.InviteOffer
import gg.hideouthq.core.customgames.FolderRead
import gg.hideouthq.core.customgames.Transfer
import gg.hideouthq.core.customgames.ModeFolders
import gg.hideouthq.core.customgames.ModeId
import gg.hideouthq.core.customgames.Mode
import gg.hideouthq.core.customgames.LocalPlayer
import gg.hideouthq.core.customgames.DisplayName
import gg.hideouthq.core.customgames.PreparedHost
import gg.hideouthq.core.customgames.GameSetup
import gg.hideouthq.core.customgames.MenusScreenPort
import gg.hideouthq.core.customgames.MenusStamp
import gg.hideouthq.core.customgames.ModeContent
import gg.hideouthq.core.customgames.StageResult
import gg.hideouthq.core.customgames.PreflightProblem
import gg.hideouthq.core.customgames.Refusal
import gg.hideouthq.core.customgames.FetchProblem
import gg.hideouthq.core.customgames.SessionEvent.GuestPrepared
import gg.hideouthq.core.customgames.SessionEvent.RuntimeFailed
import gg.hideouthq.core.customgames.SessionEvent.RuntimePrepared
import gg.hideouthq.core.customgames.ProcessIdentity
import gg.hideouthq.core.customgames.ServerEvent
import gg.hideouthq.core.customgames.Session
import gg.hideouthq.core.customgames.SessionFiles
import gg.hideouthq.core.customgames.SessionJournal
import gg.hideouthq.core.customgames.SessionOwned
import gg.hideouthq.core.customgames.Sha256
import gg.hideouthq.core.customgames.SharedPack
import gg.hideouthq.core.customgames.ModeArt
import gg.hideouthq.core.data.fake.FakeCustomGamesFeed
import gg.hideouthq.core.data.fake.FakeCustomGamesPlatform
import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.library.GameActivity
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.GamesLines
import gg.hideouthq.core.library.LibraryProblem
import gg.hideouthq.core.library.SessionAdmission
import gg.hideouthq.data.desktopEngine
import gg.hideouthq.library.DesktopInstall
import gg.hideouthq.library.FolderLock
import gg.hideouthq.library.Registry
import gg.hideouthq.library.RegistryHive
import gg.hideouthq.library.SteamClient
import gg.hideouthq.library.SteamInstall
import gg.hideouthq.library.compat.SyntheticModels
import io.ktor.http.ContentType
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.exists
import kotlin.io.path.pathString
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val NoRegistry = Registry { _, _, _ -> null }

class DesktopCustomGamesTest {
    private val root: Path = Files.createTempDirectory("hideout-desktop-games")
    private val home: Path = root.resolve("home").also { Files.createDirectories(it.resolve(".local/share/Steam/steamapps")) }
    private val record: Path = root.resolve("record")
    private val logs = CopyOnWriteArrayList<String>()
    private val journal = SessionJournal(MemoryStorage())
    private val game = GameRoot(root.resolve("My Games/Deadlock").pathString)
    private val picks = checkNotNull(Choices.of(mapOf(checkNotNull(SettingKey.parse("teams")) to checkNotNull(SettingValue.parse("2v2")), checkNotNull(SettingKey.parse("score")) to checkNotNull(SettingValue.parse("5")))))
    private val prepared = PreparedHost("/rt/modlock", "/rt/host dir/modlock-host.exe", listOf("/rt/platform", "/rt/my mode"), ModeContent(listOf("/rt/my content")), InviteOffer(null, ModeId("my-mode"), OfferContent.Feed(null), picks, 4), LocalPlayer("1001", DisplayName.parse("nori")))

    private val setup = GameSetup(4_444, "https://pack.example/bridge/")
    private val gamesFolder: Path get() = Path.of(game.path, "game", "citadel", "hideout_games")
    private val secret = checkNotNull(InviteCode.parse("P95-MAN-26D"))

    private val client = DesktopInstall.downloadClient(desktopEngine(), "test")
    private val runtimeFolder = root.resolve("run time")
    private val spawned = mutableListOf<LinkProcess>()

    @AfterTest
    fun cleanUp() {
        runBlocking { spawned.forEach { it.kill() } }
        client.close()
        root.toFile().deleteRecursively()
    }

    private fun games(script: String = "cat > /dev/null", installs: List<SteamInstall> = emptyList(), windows: Boolean = false, player: LocalPlayer? = LocalPlayer("1001", DisplayName.parse("nori")), stageLimit: Duration = 60.seconds, stopLimit: Duration = 1.seconds): DesktopCustomGames {
        assumeTrue(System.getProperty("os.name") == "Linux")
        val helper = root.resolve("hideout-link")
        Files.writeString(
            helper,
            """
            #!/bin/sh
            for a in "${'$'}@"; do printf '%s\n' "${'$'}a" >> "$record"; done
            printf 'env %s|%s|%s|%s\n' "${'$'}MODLOCK_NO_UPDATE_CHECK" "${'$'}MODLOCK_HOST" "${'$'}DEADLOCK_DIR" "${'$'}HOME" >> "$record"
            $script
            """.trimIndent(),
        )
        helper.toFile().setExecutable(true)
        return DesktopCustomGames(helper, runtimeFolder, RuntimeStore(runtimeFolder, { client }, logs::add), journal, ModeFolders(MemoryStorage()), logs::add, windows, home.pathString, { installs }, NoRegistry, "https://menus.example/bridge/", { player }, stageLimit, stopLimit)
    }

    private fun events(process: LinkProcess) = runBlocking { withTimeout(20.seconds) { process.events.toList() } }

    private fun LinkProcess.tracked() = also { spawned += it }

    private fun joinFlagsIn(stdin: Path): List<String> =
        Json.parseToJsonElement(stdin.readLines().single { it.startsWith("join ") }.removePrefix("join ")).jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun hostRunsModlockUnderTheHelperWithItsWholeArgumentsTheHostsChoicesAndEnvironment() {
        val process = games("echo '{\"type\":\"stopped\",\"reason\":\"stdin-closed\"}'").startHost(prepared, game, 27_123, setup).tracked()
        events(process)
        assertEquals(
            listOf(
                "host", "--server-port", "27123", "--steam-id", "1001", "--offer", prepared.offer.encoded, "--page", "https://pack.example/bridge/", "--screen-listen", "127.0.0.1:4444",
                "--mod", "/rt/platform", "--mod", "/rt/my mode", "--", "/rt/modlock", "--json", "play", "--no-game", "--watch=false", "--ui-port", "0", "--port", "27123",
                "--arg", "teams=2v2", "--arg", "score=5", "/rt/platform", "/rt/my mode", "env 1|/rt/host dir/modlock-host.exe|${game.path}|${home.pathString}",
            ),
            record.readLines(),
        )
    }

    @Test
    fun aHostThatSharesAPackNamesItToTheHelper() {
        games("exit 0").startHost(prepared.copy(share = "/rt/pack.zip"), game, 1, setup).tracked().let(::events)
        assertEquals(listOf("--share", "/rt/pack.zip"), record.readLines().dropWhile { it != "--share" }.take(2))
    }

    @Test
    fun theHelpersLinesBecomeEventsEndingInItsStop() {
        val process = games(
            """
            echo '{"type":"invite","code":"P95-MAN-26D"}'
            echo 'not json'
            echo '{"type":"server","event":{"host":{"ready":{"map":"street"}}}}'
            echo '{"type":"stopped","reason":"server-exited","message":"gone"}'
            exit 1
            """.trimIndent(),
        ).startHost(prepared, game, 1, setup).tracked()
        assertEquals(
            listOf(
                LinkEvent.InviteReady(secret),
                LinkEvent.Server(ServerEvent.Ready("street")),
                LinkEvent.Stopped(LinkStop.ServerExited, "gone"),
            ),
            events(process),
        )
    }

    @Test
    fun whyTheHelperStoppedGoesToTheLogWithNoInviteInIt() {
        val line = """echo '{"type":"stopped","reason":"listen-failed","message":"P95-MAN-26D on tcFriendSecretAddress port busy"}'"""
        assertEquals(LinkStop.ListenFailed, (events(games(line).startHost(prepared, game, 1, setup).tracked()).last() as LinkEvent.Stopped).reason)
        assertEquals(listOf("Custom games helper stopped: ListenFailed: [invite] on [invite] port busy"), logs.toList())
    }

    @Test
    fun outputThatEndsWithNoStopSaysUnknown() {
        val process = games("exit 7").startHost(prepared, game, 1, setup).tracked()
        assertEquals(listOf<LinkEvent>(LinkEvent.Stopped(LinkStop.Unknown)), events(process))
    }

    @Test
    fun aStopIsPassedOnOnlyOnceTheHelperExited() {
        val marker = root.resolve("exited")
        val process = games(
            """
            echo '{"type":"stopped","reason":"stdin-closed"}'
            sleep 0.3
            touch "$marker"
            """.trimIndent(),
        ).startHost(prepared, game, 1, setup).tracked()
        events(process)
        assertTrue(marker.exists())
    }

    @Test
    fun aCodeGoesToStdinBareAndNeverIntoArgumentsOrTheLog() {
        val heard = root.resolve("heard")
        val games = games(
            """
            read line
            printf '%s' "${'$'}line" > "$heard"
            echo "joining ${'$'}line" >&2
            echo "joining P95-MAN-26D via tcFriendSecretAddress" >&2
            """.trimIndent(),
        )
        events(games.startJoin(secret).tracked())
        assertEquals("P95MAN26D", heard.readText())
        assertEquals(listOf("join"), record.readLines().filterNot { it.startsWith("env ") }, "argv")
        waitUntil { logs.size == 2 }
        assertEquals(listOf("Custom games helper: joining [invite]", "Custom games helper: joining [invite] via [invite]"), logs.toList())
    }

    @Test
    fun theJoinLineCarriesTheFlagsAsAJsonArraySoAPathWithASpaceSurvives() {
        val stdin = root.resolve("stdin")
        val pack = SharedPack(checkNotNull(Sha256.parse("a".repeat(64))), 12)
        val process = games("cat > \"$stdin\"").startJoin(secret).tracked()
        runBlocking {
            process.join(setup, pack)
            process.stop()
        }
        events(process)
        assertEquals("P95MAN26D", stdin.readLines().first())
        assertEquals(
            listOf(
                "--page", "https://pack.example/bridge/", "--screen-listen", "127.0.0.1:4444", "--steam-id", "1001",
                "--fetch", root.resolve("run time/${"a".repeat(64)}.blob").pathString, "--sha256", "a".repeat(64), "--bytes", "12",
            ),
            joinFlagsIn(stdin),
        )
    }

    @Test
    fun aJoinWithNoAccountOrPackGivesOnlyThePage() {
        val stdin = root.resolve("stdin")
        val process = games("cat > \"$stdin\"", player = null).startJoin(secret).tracked()
        runBlocking {
            process.join(setup, null)
            process.stop()
        }
        events(process)
        assertEquals(listOf("--page", "https://pack.example/bridge/", "--screen-listen", "127.0.0.1:4444"), joinFlagsIn(stdin))
    }

    @Test
    fun stopClosesStdinAndTheHelperEndsByItself() {
        val process = games("cat > /dev/null\nexit 0").startHost(prepared, game, 1, setup).tracked()
        runBlocking { process.stop() }
        assertEquals(listOf<LinkEvent>(LinkEvent.Stopped(LinkStop.Unknown)), events(process))
    }

    @Test
    fun anEnterGoesToStdinInWholeSecondsAndNothingFollowsTheStop() {
        val got = root.resolve("stdin")
        val process = games("cat > \"$got\"").startHost(prepared, game, 1, setup).tracked()
        runBlocking {
            process.enter(15.seconds)
            process.stop()
            process.enter(3.minutes)
        }
        events(process)
        assertEquals(listOf("enter 15"), got.readLines())
    }

    @Test
    fun killEndsTheHelperAndLeavesItsServerToTheInterruptItsDeathSends() {
        val childPid = root.resolve("child")
        val process = games("sleep 60 > /dev/null 2>&1 &\necho \$! > \"$childPid\"\ncat > /dev/null").startHost(prepared, game, 1, setup).tracked()
        waitUntil { childPid.exists() && childPid.readText().trim().isNotEmpty() }
        val child = ProcessHandle.of(childPid.readText().trim().toLong()).get()
        try {
            runBlocking { process.kill() }
            assertIs<LinkEvent.Stopped>(events(process).last())
            assertTrue(child.isAlive, "killing the server here would stop it cleaning up")
        } finally {
            child.destroyForcibly()
        }
    }

    @Test
    fun onWindowsKillEndsTheWholeTree() {
        val childPid = root.resolve("child")
        val process = games("sleep 60 &\necho \$! > \"$childPid\"\ncat > /dev/null", windows = true).startHost(prepared, game, 1, setup).tracked()
        waitUntil { childPid.exists() && childPid.readText().trim().isNotEmpty() }
        val child = ProcessHandle.of(childPid.readText().trim().toLong()).get()
        runBlocking { process.kill() }
        waitUntil { !child.isAlive }
        assertIs<LinkEvent.Stopped>(events(process).last())
    }

    @Test
    fun aLeftoverHelperStillStoppingIsLeftToFinish() {
        val finished = root.resolve("finished")
        val games = games()
        val process = ProcessBuilder("sh", "-c", "sleep 0.5; touch '$finished'").start()
        runBlocking { games.endLeftover(JournalEntry(game, identityOf(process.toHandle()))) }
        assertTrue(finished.exists(), "the helper's stop interrupts the server, so killing it early costs the cleanup")
    }

    @Test
    fun aLeftoverIsKilledOnlyWhenItsPidAndStartBothMatch() {
        val games = games(stopLimit = 200.milliseconds)
        val process = ProcessBuilder("sleep", "60").start()
        try {
            val identity = assertNotNull(identityOf(process.toHandle()))
            runBlocking { games.endLeftover(JournalEntry(game, identity.copy(startedAtMillis = identity.startedAtMillis - 1))) }
            assertFalse(process.waitFor(300, TimeUnit.MILLISECONDS), "a reused pid belongs to someone else")
            runBlocking { games.endLeftover(JournalEntry(game, null)) }
            assertTrue(process.isAlive)
            runBlocking { games.endLeftover(JournalEntry(game, identity)) }
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        } finally {
            process.destroyForcibly()
        }
        runBlocking { games.endLeftover(JournalEntry(game, ProcessIdentity(Long.MAX_VALUE, 1))) }
    }

    @Test
    fun whatALeftoverHelperStartedHasGoneOnceItIsEnded() {
        val finished = root.resolve("finished")
        val games = games()
        val process = ProcessBuilder("sh", "-c", "(sleep 0.5; touch '$finished') > /dev/null 2>&1 &\nsleep 0.1").start()
        Thread.sleep(50)
        runBlocking { games.endLeftover(JournalEntry(game, identityOf(process.toHandle()))) }
        assertTrue(finished.exists(), "a release asks whether a server runs, and this session's must not be the one it sees")
    }

    @Test
    fun aHostPreparesFromTheFeedAndPicksAPort() = serving("mod" to zipOf("mod.json" to "{}"), "cli" to "binary".encodeToByteArray(), "host" to zipOf("modlock-host.exe" to "h")) { artifact ->
        val mode = feedMode(artifact("mod"), null)
        val feed = CustomGamesFeed(1, ModeRuntime(mapOf(DesktopOs.Linux to artifact("cli")), artifact("host"), artifact("mod"), artifact("mod")), listOf(mode))
        val prepared = assertIs<RuntimePrepared>(runBlocking { games().prepareHost(mode, picks, feed) {} })
        assertEquals(InviteOffer.of(mode, picks, LocalPlayer("1001", DisplayName.parse("nori"))), prepared.host.offer)
        assertNull(prepared.host.share, "a feed mode's guests take its content from their own feed")
        assertEquals(setOf(mode.id), runBlocking { games().installed() }.keys, "a hosted feed mode is installed")
        assertTrue(prepared.port in 1..65535)
        assertTrue(Files.isExecutable(Path.of(prepared.host.modlock)))
        assertTrue(Path.of(prepared.host.server).exists())
        assertEquals(1, prepared.host.mods.size, "one package as both platform and mode is one mod")
        assertEquals(artifact("mod").sha256, prepared.host.renderer)
        assertTrue(Path.of(prepared.host.mods.single(), "mod.json").exists())
    }

    @Test
    fun aLocalFolderIsStagedAsItIsAndItsRulesAreCopiedWithoutContent() = serving("mod" to zipOf("mod.json" to "{}"), "cli" to "binary".encodeToByteArray()) { artifact ->
        val feed = CustomGamesFeed(1, ModeRuntime(mapOf(DesktopOs.Linux to artifact("cli")), artifact("mod"), artifact("mod"), artifact("mod")), emptyList())
        val built = Files.createDirectories(root.resolve("built"))
        Files.writeString(built.resolve("mod.json"), "{}")
        Files.createDirectories(built.resolve("data"))
        Files.writeString(built.resolve("data/areas"), "areas")
        Files.createDirectories(built.resolve("panorama/layout"))
        Files.writeString(built.resolve("panorama/layout/menu"), "menu")
        Files.createDirectories(built.resolve("maps"))
        Files.writeString(built.resolve("maps/arena.vpk"), "archive")
        Files.writeString(built.resolve("maps/arena.bsp"), "map")
        Files.writeString(built.resolve("server.log"), "log")
        Files.writeString(built.resolve("mod.js.map"), "source map")
        Files.writeString(built.resolve(".build.lock"), "")
        val prepared = assertIs<RuntimePrepared>(runBlocking { games().prepareHost(folderMode(built.pathString), Choices.None, feed) {} })
        assertEquals(listOf(built.pathString), prepared.host.content.folders, "the folder as it is is staged")
        val share = Path.of(checkNotNull(prepared.host.share))
        val pack = assertIs<OfferContent.Host>(prepared.host.offer.content).pack
        assertEquals(SharedPack(checkNotNull(Sha256.parse(sha256Of(share))), Files.size(share)), pack, "the invite names the pack the helper serves")
        assertEquals(listOf("maps/arena.vpk", "panorama/layout/menu"), ZipFile(share.toFile()).use { zip -> zip.entries().toList().map { it.name } }, "exactly what a guest's stage reads")
        val again = assertIs<RuntimePrepared>(runBlocking { games().prepareHost(folderMode(built.pathString), Choices.None, feed) {} })
        assertEquals(prepared.host.offer.content, again.host.offer.content, "the same build packs the same bytes")
        val rules = Path.of(prepared.host.mods.last())
        assertTrue(rules.resolve("mod.json").exists() && rules.resolve("data/areas").exists() && rules.resolve("maps/arena.bsp").exists())
        assertFalse(rules.resolve("panorama").exists() || rules.resolve("maps/arena.vpk").exists(), "Modlock would mount these itself")
    }

    @Test
    fun aGuestFetchesTheModeItIsGivenAndPlaysItFromDiskWithNoList() = serving("renderer" to zipOf("renderer.js" to "r"), "client" to zipOf("panorama/menu" to "m")) { artifact ->
        val mode = feedMode(artifact("client"), artifact("client"))
        val feed = CustomGamesFeed(1, ModeRuntime(mapOf(DesktopOs.Linux to artifact("client")), artifact("client"), artifact("client"), artifact("renderer")), listOf(mode))
        val games = games()
        val matching = assertIs<GuestPrepared>(runBlocking { games.prepareGuest(mode, null, feed) {} })
        assertEquals(artifact("renderer").sha256, matching.renderer)
        assertTrue(runtimeFolder.resolve(artifact("renderer").sha256.hex).resolve("renderer.js").exists())
        assertTrue(Path.of(matching.content.folders.single(), "panorama/menu").exists())
        assertEquals(mode, runBlocking { games.installed() }[mode.id]?.mode, "a guest can host what it joined")
        val offline = assertIs<GuestPrepared>(runBlocking { games.prepareGuest(mode, null, null) {} })
        assertEquals(matching.content, offline.content, "an installed mode needs no list")
        assertNull(offline.renderer)
    }

    @Test
    fun aGuestWhoCannotDownloadTheMapIsToldBeforeAnythingStarts() = serving("renderer" to zipOf("renderer.js" to "r")) { artifact ->
        val missing = artifact("renderer").copy(url = artifact("renderer").url + "-gone", sha256 = checkNotNull(Sha256.parse("b".repeat(64))))
        val mode = feedMode(artifact("renderer"), missing)
        val feed = CustomGamesFeed(1, ModeRuntime(mapOf(DesktopOs.Linux to artifact("renderer")), artifact("renderer"), artifact("renderer"), artifact("renderer")), listOf(mode))
        val refused = assertIs<RuntimeFailed>(runBlocking { games().prepareGuest(mode, null, feed) {} })
        assertEquals(Refusal.Preflight(PreflightProblem.Download(FetchProblem.Server)), refused.problem)
    }

    @Test
    fun aRendererThatWillNotDownloadCostsAGuestOnlyItsMenus() = serving("client" to zipOf("maps/arena.vpk" to "map")) { artifact ->
        val client = artifact("client")
        val mode = feedMode(client, client)
        val feed = CustomGamesFeed(1, ModeRuntime(mapOf(DesktopOs.Linux to client), client, client, goneRenderer(client)), listOf(mode))
        val shares = mutableListOf<Transfer>()
        val prepared = assertIs<GuestPrepared>(runBlocking { games().prepareGuest(mode, null, feed, shares::add) })
        assertNull(prepared.renderer)
        assertTrue(Path.of(prepared.content.folders.single(), "maps/arena.vpk").exists())
        assertTrue(shares.isNotEmpty(), "a guest's download says how far it has got")
    }

    @Test
    fun aRendererThatWillNotDownloadCostsAHostOnlyItsMenus() = serving("mod" to zipOf("mod.json" to "{}"), "cli" to "binary".encodeToByteArray(), "host" to zipOf("modlock-host.exe" to "h")) { artifact ->
        val mode = feedMode(artifact("mod"), artifact("mod"))
        val feed = CustomGamesFeed(1, ModeRuntime(mapOf(DesktopOs.Linux to artifact("cli")), artifact("host"), artifact("mod"), goneRenderer(artifact("mod"))), listOf(mode))
        val prepared = assertIs<RuntimePrepared>(runBlocking { games().prepareHost(mode, Choices.None, feed) {} })
        assertNull(prepared.host.renderer)
        assertTrue(Path.of(prepared.host.content.folders.single(), "mod.json").exists())
    }

    private fun feedMode(rules: Artifact, client: Artifact?) = FakeCustomGamesFeed.SampleMode.copy(origin = ModeOrigin.Feed(rules, client))

    private fun folderMode(path: String) = FakeCustomGamesFeed.SampleFolder.copy(origin = ModeOrigin.Folder(path))

    private fun goneRenderer(like: Artifact) = like.copy(url = like.url + "-gone", sha256 = checkNotNull(Sha256.parse("b".repeat(64))))

    @Test
    fun aGuestPlacesTheHostsPackItsHelperFetchedAndAnotherJoinOfTheSameBuildFetchesNothing() {
        val zip = zipOf("maps/arena.vpk" to "map", "panorama/menu" to "m")
        val pack = SharedPack(checkNotNull(Sha256.parse(sha256Of(zip))), zip.size.toLong())
        val stdin = root.resolve("stdin")
        val games = games("cat > \"$stdin\"")
        val first = assertIs<GuestPrepared>(runBlocking { games.prepareGuest(null, pack, null) {} }).content
        assertEquals(pack, first.shared, "the helper fetches it")
        val process = games.startJoin(secret).tracked()
        runBlocking {
            process.join(setup, first.shared)
            process.stop()
        }
        events(process)
        val fetch = joinFlagsIn(stdin).dropWhile { it != "--fetch" }.take(6)
        assertEquals(listOf("--fetch", runtimeFolder.resolve("${pack.sha256.hex}.blob").pathString, "--sha256", pack.sha256.hex, "--bytes", "${zip.size}"), fetch)
        Files.write(Path.of(fetch[1]), zip)
        assertNull(runBlocking { games.placeShared(pack) })
        assertTrue(Path.of(first.folders.single(), "maps/arena.vpk").exists())
        val second = assertIs<GuestPrepared>(runBlocking { games.prepareGuest(null, pack, null) {} }).content
        assertEquals(first.folders, second.folders)
        assertNull(second.shared, "its folder is here already")
        assertNull(runBlocking { games.placeShared(pack) }, "placing what is placed changes nothing")
    }

    @Test
    fun aGuestWithNoFeedHasNoRendererAndOneWithNoAccountStillDoes() = serving("renderer" to zipOf("renderer.js" to "r")) { artifact ->
        assertNull(assertIs<GuestPrepared>(runBlocking { games().prepareGuest(null, null, null) {} }).renderer)
        val feed = CustomGamesFeed(1, ModeRuntime(mapOf(DesktopOs.Linux to artifact("renderer")), artifact("renderer"), artifact("renderer"), artifact("renderer")), emptyList())
        assertEquals(artifact("renderer").sha256, assertIs<GuestPrepared>(runBlocking { games(player = null).prepareGuest(null, null, feed) {} }).renderer, "a pack names no account")
    }

    private fun buildFolder(listing: String? = Path.of("../../core/testResources/customgames/listings/valid/bebop-wars.json").readText()): Path {
        val built = Files.createDirectories(root.resolve("bebop"))
        Files.writeString(built.resolve("mod.json"), """{"slug":"bebop-wars","name":"Bebop Wars","version":"0.3.0","entry":"main.js"}""")
        listing?.let { Files.writeString(built.resolve("hideout.json"), it) }
        return built
    }

    @Test
    fun aBuildFolderReadsAsAModeWithItsOwnListingAndArt() {
        val built = buildFolder()
        Files.createDirectories(built.resolve("art"))
        Files.writeString(built.resolve("art/card-background@2x.jpg"), "jpg")
        val mode = assertIs<FolderRead.Read>(runBlocking { games().readFolder(built.pathString) }).mode
        assertEquals(ModeId("bebop-wars"), mode.id)
        assertEquals("0.3.0", mode.version, "the build's own version")
        assertEquals(ModeOrigin.Folder(built.pathString), mode.origin)
        assertEquals("Bebop Wars", mode.listing.name)
        assertFalse(mode.listing.curated, "only Hideout's feed can say a mode is its own")
        assertEquals(built.resolve("art/card-background@2x.jpg").toUri().toString(), mode.listing.art.card)
        assertNull(mode.listing.art.backdrop, "a picture the folder doesn't have shows placeholder art")
    }

    @Test
    fun aBuildFolderWithNoListingIsListedByItsModsName() {
        val games = games()
        val plain = assertIs<FolderRead.Read>(runBlocking { games.readFolder(buildFolder(listing = null).pathString) }).mode
        assertEquals(ModeId("bebop-wars") to "Bebop Wars", plain.id to plain.listing.name)
        assertEquals(ModeArt(), plain.listing.art)
        Files.writeString(root.resolve("bebop/mod.json"), """{"slug":"bebop-wars","version":"0.3.0"}""")
        assertEquals("bebop-wars", assertIs<FolderRead.Read>(runBlocking { games.readFolder(root.resolve("bebop").pathString) }).mode.listing.name, "with no name, its slug")
    }

    @Test
    fun artOutsideTheFolderIsNeverShown() {
        Files.writeString(root.resolve("secret.png"), "png")
        val listing = Path.of("../../core/testResources/customgames/listings/valid/bebop-wars.json").readText()
            .replace("art/card-background@2x.jpg", "../secret.png").replace("art/bebop-thumb@2x.png", root.resolve("secret.png").pathString)
        val art = assertIs<FolderRead.Read>(runBlocking { games().readFolder(buildFolder(listing).pathString) }).mode.listing.art
        assertNull(art.card)
        assertNull(art.tile)
    }

    @Test
    fun aFolderThatIsNoModeSaysWhy() {
        val games = games()
        assertEquals(FolderRead.NotThere, runBlocking { games.readFolder(root.resolve("gone").pathString) })
        assertEquals(FolderRead.BadListing, runBlocking { games.readFolder(buildFolder(listing = "{}").pathString) })
        Files.writeString(root.resolve("bebop/mod.json"), """{"slug":"Bebop Wars","version":"1"}""")
        assertEquals(FolderRead.NotAMode, runBlocking { games.readFolder(root.resolve("bebop").pathString) })
        Files.delete(root.resolve("bebop/mod.json"))
        assertEquals(FolderRead.NotAMode, runBlocking { games.readFolder(root.resolve("bebop").pathString) })
    }

    private val writesPack = """
        out=""; last=""
        for a in "${'$'}@"; do [ "${'$'}last" = "--out" ] && out="${'$'}a"; last="${'$'}a"; done
        echo pack > "${'$'}out"
        echo '{"type":"stopped","reason":"done"}'
    """.trimIndent()

    private fun rendererHere(digit: Char) = checkNotNull(Sha256.parse("$digit".repeat(64))).also { Files.createDirectories(runtimeFolder.resolve(it.hex)) }

    @Test
    fun menusAreBuiltOnceForTheRendererSiteAndGameAndAFailedRebuildKeepsThePack() {
        val renderer = rendererHere('c')
        val games = games(writesPack)
        assertNull(runBlocking { games.menus(game, renderer, rebuild = false) }, "no pack, and no build asked for")
        val stamp = MenusStamp(renderer.hex, "https://menus.example/bridge/", MenusScreenPort, null)
        assertEquals(stamp, runBlocking { games.menus(game, renderer, rebuild = true) })
        val asked = record.readLines().filterNot { it.startsWith("env ") }
        assertEquals(listOf("menus", "--game", game.path, "--renderer", runtimeFolder.resolve(renderer.hex).pathString, "--page", "https://menus.example/bridge/", "--screen-port", "29700", "--out"), asked.dropLast(1))
        assertEquals("pack\n", gamesFolder.resolve("menus/pak01_dir.vpk").readText())
        assertEquals(setOf("hideout.lock", "menus", "menus.json"), Files.list(gamesFolder).use { files -> files.map { it.fileName.toString() }.toList().toSet() }, "no part file is left")

        Files.delete(record)
        assertEquals(stamp, runBlocking { games.menus(game, renderer, rebuild = true) })
        assertFalse(record.exists(), "a current pack is not built again")

        val failing = games("echo '{\"type\":\"stopped\",\"reason\":\"failed\",\"message\":\"no news layout\"}'\nexit 1")
        assertEquals(stamp, runBlocking { failing.menus(game, rendererHere('d'), rebuild = true) }, "a failed build keeps the pack that was there")
        assertEquals("pack\n", gamesFolder.resolve("menus/pak01_dir.vpk").readText())
        assertTrue(logs.any { "no news layout" in it })
        assertEquals(stamp, runBlocking { failing.menus(game, checkNotNull(Sha256.parse("e".repeat(64))), rebuild = true) }, "a renderer never fetched builds nothing")
    }

    @Test
    fun anEndedDevSessionIsClearedByTheHelperAndWhatItFoundIsLogged() {
        fun said(line: String) = "echo '$line'\necho '{\"type\":\"stopped\",\"reason\":\"done\"}'"
        runBlocking { games(said("{\"type\":\"dev-session\",\"live\":false}")).clearEndedDevSession(game) }
        assertEquals(listOf("clear-dev", "--game", game.path), record.readLines().filterNot { it.startsWith("env ") })
        assertEquals(listOf("Custom games: took out the block of a Modlock dev session that had ended, whose menus loaded ahead of Hideout's"), logs)

        logs.clear()
        runBlocking { games(said("{\"type\":\"dev-session\",\"live\":true}")).clearEndedDevSession(game) }
        assertEquals(listOf("Custom games: a Modlock dev session is running in this game, so its block stays"), logs)

        logs.clear()
        runBlocking { games("echo '{\"type\":\"stopped\",\"reason\":\"done\"}'").clearEndedDevSession(game) }
        assertEquals(emptyList(), logs, "a game with no block has nothing to say")

        runBlocking { games("echo '{\"type\":\"stopped\",\"reason\":\"failed\",\"message\":\"gameinfo.gi is missing\"}'\nexit 1").clearEndedDevSession(game) }
        assertEquals(listOf("Custom games: could not check for a Modlock dev session's block: gameinfo.gi is missing"), logs)
    }

    @Test
    fun aFolderMountedAheadOfTheMenusWithAPackHoldingMenusFilesIsNamedAndNoOtherIs() {
        val citadel = Path.of(game.path, "game", "citadel")
        fun pack(folder: String, vararg files: String) {
            Files.createDirectories(citadel.resolve(folder))
            Files.write(citadel.resolve(folder).resolve("pak01_dir.vpk"), SyntheticModels.vpk(files.map { SyntheticModels.VpkFile(it, "x".encodeToByteArray()) }).first)
        }
        fun searchPaths(vararg lines: String) =
            Files.writeString(citadel.resolve("gameinfo.gi"), "GameInfo\n{\n\tFileSystem\n\t{\n\t\tSearchPaths\n\t\t{\n${lines.joinToString("") { "\t\t\t$it\n" }}\t\t}\n\t}\n}\n")
        val hideouts = arrayOf("// Hideout start", "Game citadel/hideout_games/menus", "Game citadel/hideout_games/mode", "Game citadel/addons", "Mod citadel", "Write citadel", "// Hideout end")
        pack("modlock_dev/31bb65da30bd1ed1", "panorama/scripts/modlock/boot.vjs_c", "panorama/layout/latest_news.vxml_c", "panorama/layout/modlock/ui.vxml_c")
        pack("news_only", "panorama/layout/latest_news.vxml_c")
        pack("tweaks", "materials/ui/hud.vtex_c", "panorama/layout/hud.vxml_c")
        pack("hideout_games/menus", "panorama/layout/latest_news.vxml_c", "panorama/scripts/modlock/boot.vjs_c")
        val games = games()
        fun blockedBy() = runBlocking { games.menusBlockedBy(game) }

        assertNull(blockedBy(), "no gameinfo.gi")
        searchPaths(*hideouts, "Game citadel")
        assertNull(blockedBy(), "the menus' own pack")
        searchPaths("// MODLOCK DEV BEGIN", "Game citadel/modlock_dev/31bb65da30bd1ed1", "Mod \"citadel\"", "Write \"citadel\"", "// MODLOCK DEV END", *hideouts, "Game citadel")
        assertEquals("citadel/modlock_dev/31bb65da30bd1ed1", blockedBy())
        searchPaths("Game citadel/tweaks", "Game citadel/gone", "Game citadel/news_only", *hideouts, "Game citadel")
        assertEquals("citadel/news_only", blockedBy(), "the news layout alone takes the menus' place")
        searchPaths("Game \"citadel\\news_only\"", *hideouts, "Game citadel")
        assertEquals("citadel\\news_only", blockedBy(), "as written, with Windows' separator")
        searchPaths("Game citadel/tweaks", *hideouts, "Game citadel/news_only", "Game citadel")
        assertNull(blockedBy(), "a pack without menus files, and one read after the menus")
        searchPaths("Game citadel/modlock_dev/31bb65da30bd1ed1", "Game citadel")
        assertNull(blockedBy(), "with no custom games lines there are no menus to load ahead of")
        assertEquals(emptyList(), logs)
    }

    @Test
    fun aStageFillsTheModeFolderAndNamesWhatStayedHeld() {
        val games = games("echo '{\"type\":\"staged\",\"held\":[\"maps/before.vpk\"]}'\necho '{\"type\":\"stopped\",\"reason\":\"done\"}'")
        assertEquals(StageResult.Staged(listOf("maps/before.vpk")), runBlocking { games.stage(game, ModeContent(listOf("/rt/my content", "/rt/pack"))) })
        assertEquals(listOf("stage", "--into", gamesFolder.resolve("mode").pathString, "--content", "/rt/my content", "--content", "/rt/pack"), record.readLines().filterNot { it.startsWith("env ") })
        val failing = games("echo '{\"type\":\"stopped\",\"reason\":\"failed\",\"message\":\"disk full\"}'\nexit 1")
        assertEquals(StageResult.Failed("disk full"), runBlocking { failing.stage(game, null) })
    }

    @Test
    fun theGamesFolderGoesWholeButNotWhileAnotherHolderHasItsLock() {
        Files.createDirectories(gamesFolder.resolve("menus"))
        Files.createDirectories(gamesFolder.resolve("mode/maps"))
        Files.writeString(gamesFolder.resolve("menus/pak01_dir.vpk"), "pack")
        Files.writeString(gamesFolder.resolve("mode/maps/arena.vpk"), "map")
        val games = games()
        checkNotNull(FolderLock.take(gamesFolder)).use {
            assertFalse(runBlocking { games.removeGamesFolder(game) })
            assertEquals("pack", gamesFolder.resolve("menus/pak01_dir.vpk").readText())
        }
        assertTrue(runBlocking { games.removeGamesFolder(game) })
        assertFalse(gamesFolder.exists())
        assertTrue(Path.of(game.path, "game", "citadel").exists())
        assertTrue(runBlocking { games.removeGamesFolder(game) }, "nothing left is nothing to do")
    }

    @Test
    fun aHungStageIsKilledAtItsLimitAndCountsAsFailed() {
        val pid = root.resolve("pid")
        val games = games("echo \$\$ > \"$pid\"\nexec sleep 300", stageLimit = 600.milliseconds)
        var result: StageResult? = null
        val call = thread(isDaemon = true) { result = runBlocking { games.stage(game, prepared.content) } }
        call.join(15_000)
        val helper = pid.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()?.let { ProcessHandle.of(it).orElse(null) }
        val alive = helper?.isAlive == true
        helper?.destroyForcibly()
        assertFalse(call.isAlive, "the call outlasted the stage's limit")
        assertFalse(alive, "the helper outlasted the stage's limit")
        assertEquals(StageResult.Failed("it ran past its limit"), result)
    }

    @Test
    fun stopDuringAHungStageEndsItWithoutWaitingForItsLimit() = runBlocking {
        val pid = root.resolve("pid")
        val games = games("echo \$\$ > \"$pid\"\nexec sleep 300", stageLimit = 5.minutes)
        val platform = object : CustomGamesPlatform by FakeCustomGamesPlatform(journal, chatty = false) {
            override suspend fun stage(game: GameRoot, content: ModeContent?) = if (content == null) StageResult.Staged(emptyList()) else games.stage(game, content)
        }
        val files = object : SessionFiles {
            override val clientActivity = MutableStateFlow(GameActivity.Closed)
            override suspend fun game() = this@DesktopCustomGamesTest.game
            override suspend fun gamesLines() = GamesLines.Mounted
            override suspend fun useCustomGames(on: Boolean): LibraryProblem? = null
            override suspend fun admit() = SessionAdmission.Held(this@DesktopCustomGamesTest.game)
            override suspend fun launchClient(): LibraryProblem? = null
            override suspend fun closeClient() = Unit
            override suspend fun release(): LibraryProblem? = null
            override suspend fun resume(game: GameRoot) = Unit
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val owner = CustomGames(platform, files, null, scope, hostFolders = flowOf(false))
            owner.start()
            owner.host(FakeCustomGamesFeed.SampleFolder, Choices.None, null)
            waitUntil { pid.exists() && pid.readText().isNotBlank() }
            owner.stop()
            val session = withTimeoutOrNull(15.seconds) { owner.live.first { it.session is Session.Ended }.session }
            val helper = ProcessHandle.of(pid.readText().trim().toLong())
            val alive = helper.map { it.isAlive }.orElse(false)
            helper.ifPresent { it.destroyForcibly() }
            assertIs<Session.Ended>(session, "the session stayed in ${owner.live.value.session}")
            assertFalse(alive, "the stage outlasted the stop")
        } finally {
            scope.cancel()
        }
        Unit
    }

    @Test
    fun aGameThatAlreadyHadAnAppIdFileKeepsIt() {
        val appId = Path.of(game.path, "game", "steam_appid.txt")
        Files.createDirectories(appId.parent)
        Files.writeString(appId, "1422450\n")
        assertFalse(runBlocking { games().ownedBeforeServer(game) }.appIdFile)
    }

    @Test
    fun aReleaseRemovesOnlyWhatTheSessionProvesItsOwn() {
        val appId = Path.of(game.path, "game", "steam_appid.txt")
        Files.createDirectories(appId.parent)
        Files.writeString(appId, "1422450\n\u0000")
        val games = games()
        assertTrue(runBlocking { games.removeLeftovers(game, SessionOwned(appIdFile = true)) })
        assertFalse(appId.exists())

        Files.writeString(appId, "1422450")
        runBlocking { games.removeLeftovers(game, SessionOwned(appIdFile = false)) }
        assertTrue(appId.exists(), "a file the game had before the session stays")

        Files.writeString(appId, "someone's own")
        runBlocking { games.removeLeftovers(game, SessionOwned(appIdFile = true)) }
        assertTrue(appId.exists(), "a file that says anything else is not the server's")
    }

    @Test
    fun aFolderThatIsGoneHasNothingLeftStanding() {
        val games = games()
        assertTrue(runBlocking { games.removeLeftovers(GameRoot(root.resolve("gone").pathString), SessionOwned(appIdFile = true)) })
    }

    @Test
    fun whatCannotBeRemovedIsSaid() {
        val appId = Path.of(game.path, "game", "steam_appid.txt")
        Files.createDirectories(appId.parent)
        Files.writeString(appId, "1422450")
        val games = games()
        assertTrue(appId.parent.toFile().setWritable(false))
        try {
            assertFalse(runBlocking { games.removeLeftovers(game, SessionOwned(appIdFile = true)) })
            assertTrue(appId.exists())
        } finally {
            appId.parent.toFile().setWritable(true)
        }
        assertTrue(runBlocking { games.removeLeftovers(game, SessionOwned(appIdFile = true)) })
        assertFalse(appId.exists())
    }

    @Test
    fun onlyWindowsWarnsAboutTheFirewall() {
        assertTrue(games(windows = true).firewallMayAsk)
        assertFalse(games(windows = false).firewallMayAsk)
    }

    @Test
    fun theCompileCacheGoesOnlyOnWindowsAndOnlyIfTheSessionFoundNone() {
        val cache = home.resolve("AppData/Local/BytecodeAlliance/wasmtime")
        val windows = games(windows = true)
        assertTrue(runBlocking { windows.ownedBeforeServer(game) }.compileCache)
        assertFalse(runBlocking { games().ownedBeforeServer(game) }.compileCache)
        Files.createDirectories(cache)
        assertFalse(runBlocking { windows.ownedBeforeServer(game) }.compileCache, "the player already had one, from another program")

        runBlocking { windows.removeLeftovers(game, SessionOwned(compileCache = false)) }
        assertTrue(cache.exists())
        runBlocking { windows.removeLeftovers(game, SessionOwned(compileCache = true)) }
        assertFalse(home.resolve("AppData/Local/BytecodeAlliance").exists())
    }

    @Test
    fun steamsRecordSaysWhetherAnUpdateIsWaiting() {
        val steamapps = root.resolve("library/steamapps").also { Files.createDirectories(it.resolve("common/Deadlock")) }
        val inLibrary = GameRoot(steamapps.resolve("common/Deadlock").pathString)
        assertFalse(SteamManifest.updatePending(inLibrary), "no record says nothing")
        val record = steamapps.resolve("appmanifest_1422450.acf")
        Files.writeString(record, "\"AppState\"\n{\n\t\"StateFlags\"\t\t\"4\"\n}\n")
        assertFalse(SteamManifest.updatePending(inLibrary))
        Files.writeString(record, "\"AppState\"\n{\n\t\"StateFlags\"\t\t\"6\"\n}\n")
        assertTrue(SteamManifest.updatePending(inLibrary))
    }

    @Test
    fun theAccountIsTheOneSteamSignedInLast() {
        val steam = root.resolve("steam-config").also { Files.createDirectories(it.resolve("config")) }
        Files.writeString(
            steam.resolve("config/loginusers.vdf"),
            """
            "users"
            {
                "1001" { "Timestamp" "5" }
                "2002" { "Timestamp" "9" "PersonaName" "nori" }
            }
            """.trimIndent(),
        )
        assertEquals(LocalPlayer("2002", DisplayName.parse("nori")), localPlayerOf(listOf(SteamInstall(steam.pathString, SteamClient.Desktop))))
        assertNull(localPlayerOf(emptyList()))
    }

    @Test
    fun hostingNeedsASteamAccountAFeedAndAFolderThatExists() {
        assertEquals(RuntimeFailed(Refusal.Preflight(PreflightProblem.NoSteamAccount)), runBlocking { games(player = null).prepareHost(folderMode(root.pathString), Choices.None, null) {} })
        val games = games()
        assertEquals(RuntimeFailed(Refusal.Preflight(PreflightProblem.NoModeList)), runBlocking { games.prepareHost(folderMode(root.pathString), Choices.None, null) {} })
        assertEquals(RuntimeFailed(Refusal.Preflight(PreflightProblem.FolderMissing)), runBlocking { games.prepareHost(folderMode(root.resolve("nope").pathString), Choices.None, null) {} })
    }

    @Test
    fun onlyTheSteamModlockFindsCanHost() {
        val native = root.resolve("native").also { Files.createDirectories(it.resolve("steamapps")) }
        val flatpak = root.resolve("flatpak").also { Files.createDirectories(it.resolve("steamapps")) }
        val empty = root.resolve("empty").also { Files.createDirectories(it) }

        assertNull(hostingProblem(listOf(empty, native), emptyList()))
        assertEquals(PreflightProblem.SteamUnsupported, hostingProblem(listOf(empty), listOf(SteamInstall(flatpak.pathString, SteamClient.Flatpak))))
        assertEquals(PreflightProblem.SteamNotFound, hostingProblem(listOf(empty), listOf(SteamInstall(root.resolve("absent").pathString, SteamClient.Flatpak))))
    }

    @Test
    fun aMissIsSaidBeforeAnythingIsDownloaded() {
        Files.delete(home.resolve(".local/share/Steam/steamapps"))
        val flatpak = root.resolve("flatpak").also { Files.createDirectories(it.resolve("steamapps")) }
        val games = games(installs = listOf(SteamInstall(flatpak.pathString, SteamClient.Flatpak)))
        assertEquals(RuntimeFailed(Refusal.Preflight(PreflightProblem.SteamUnsupported)), runBlocking { games.prepareHost(folderMode(root.pathString), Choices.None, null) {} })
        assertFalse(runtimeFolder.exists())
    }

    @Test
    fun modlocksSearchIsItsOwnNotHideouts() {
        val registered = Registry { hive, key, value -> """D:/Steam""".takeIf { hive == RegistryHive.CurrentUser && key == """Software\Valve\Steam""" && value == "SteamPath" } }
        assertEquals(listOf(Path.of("D:/Steam"), Path.of("""C:\Program Files (x86)\Steam""")), modlockSteamRoots(windows = true, "/home/me", registered))
        assertEquals(listOf(Path.of("/home/me/.local/share/Steam"), Path.of("/home/me/.steam/steam")), modlockSteamRoots(windows = false, "/home/me", NoRegistry))
    }

    @Test
    fun theHelperIsFoundBesideTheRealExecutableOrWhereAnOverrideSays() {
        val image = Files.createDirectories(root.resolve("image"))
        val program = Files.createFile(image.resolve("hideout"))
        val helper = Files.createFile(image.resolve("hideout-link"))
        val wrapper = Files.createSymbolicLink(root.resolve("wrapper"), program)
        val other = Files.createFile(root.resolve("my-helper"))
        val noFile = root.resolve("hideout.properties")

        assertEquals(helper.toRealPath(), LinkHelperLocation.resolve(null, null, noFile, wrapper, windows = false)?.toRealPath())
        assertNull(LinkHelperLocation.resolve(null, null, noFile, root.resolve("wrapper-gone"), windows = false))
        assertEquals(other, LinkHelperLocation.resolve(other.pathString, null, noFile, program, windows = false))
        assertEquals(other, LinkHelperLocation.resolve(null, other.pathString, noFile, program, windows = false))
        assertNull(LinkHelperLocation.resolve(root.resolve("nope").pathString, null, noFile, program, windows = false))
        assertNull(LinkHelperLocation.resolve(null, null, noFile, null, windows = false))

        Files.writeString(noFile, "${LinkHelperLocation.Property}=${other.pathString}\n")
        assertEquals(other, LinkHelperLocation.resolve(null, null, noFile, program, windows = false))
    }

    private fun serving(vararg files: Pair<String, ByteArray>, test: ((String) -> Artifact) -> Unit) {
        val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
            routing {
                files.forEach { (name, bytes) -> get("/$name") { call.respondBytes(bytes, ContentType.Application.OctetStream) } }
            }
        }.start(wait = false)
        try {
            val base = "http://127.0.0.1:${runBlocking { server.engine.resolvedConnectors().single().port }}"
            test { name ->
                val bytes = files.toMap().getValue(name)
                Artifact("$base/$name", bytes.size.toLong(), checkNotNull(Sha256.parse(MessageDigest.getInstance("SHA-256").digest(bytes).toHexString())))
            }
        } finally {
            server.stop(0, 0)
        }
    }

    private fun sha256Of(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()

    private fun sha256Of(file: Path) = sha256Of(Files.readAllBytes(file))

    private fun waitUntil(condition: () -> Boolean) {
        val until = System.nanoTime() + 20_000_000_000
        while (!condition()) {
            check(System.nanoTime() < until) { "never happened" }
            Thread.sleep(20)
        }
    }
}
