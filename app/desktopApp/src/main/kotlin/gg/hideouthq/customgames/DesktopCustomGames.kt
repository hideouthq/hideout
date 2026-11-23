package gg.hideouthq.customgames

import gg.hideouthq.OnWindows
import gg.hideouthq.core.customgames.Choices
import gg.hideouthq.core.customgames.CustomGamesFeed
import gg.hideouthq.core.customgames.CustomGamesPlatform
import gg.hideouthq.core.customgames.Deadline
import gg.hideouthq.core.customgames.DesktopOs
import gg.hideouthq.core.customgames.DisplayName
import gg.hideouthq.core.customgames.FetchProblem
import gg.hideouthq.core.customgames.FolderRead
import gg.hideouthq.core.customgames.GameRevision
import gg.hideouthq.core.customgames.GameSetup
import gg.hideouthq.core.customgames.InGamePort
import gg.hideouthq.core.customgames.InGameStamp
import gg.hideouthq.core.customgames.InstalledMode
import gg.hideouthq.core.customgames.InviteCode
import gg.hideouthq.core.customgames.InviteOffer
import gg.hideouthq.core.customgames.JournalEntry
import gg.hideouthq.core.customgames.LinkProcess
import gg.hideouthq.core.customgames.LocalPlayer
import gg.hideouthq.core.customgames.MenusPack
import gg.hideouthq.core.customgames.MenusScreenPort
import gg.hideouthq.core.customgames.MenusStamp
import gg.hideouthq.core.customgames.Mode
import gg.hideouthq.core.customgames.ModeContent
import gg.hideouthq.core.customgames.ModeFolders
import gg.hideouthq.core.customgames.ModeId
import gg.hideouthq.core.customgames.ModeOrigin
import gg.hideouthq.core.customgames.PreflightProblem
import gg.hideouthq.core.customgames.PreparedHost
import gg.hideouthq.core.customgames.Refusal
import gg.hideouthq.core.customgames.SessionEvent.GuestPrepared
import gg.hideouthq.core.customgames.SessionEvent.PrepareAnswer
import gg.hideouthq.core.customgames.SessionEvent.RuntimeFailed
import gg.hideouthq.core.customgames.SessionEvent.RuntimePrepared
import gg.hideouthq.core.customgames.SessionJournal
import gg.hideouthq.core.customgames.SessionOwned
import gg.hideouthq.core.customgames.Sha256
import gg.hideouthq.core.customgames.SharedPack
import gg.hideouthq.core.customgames.StageLimit
import gg.hideouthq.core.customgames.StageResult
import gg.hideouthq.core.customgames.Transfer
import gg.hideouthq.core.customgames.parseListing
import gg.hideouthq.core.customgames.plainListing
import gg.hideouthq.core.data.CacheStorage
import gg.hideouthq.core.library.DeadlockAppId
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.data.AppDirs
import gg.hideouthq.data.UserPropertiesFile
import gg.hideouthq.data.userOverride
import gg.hideouthq.library.Registry
import gg.hideouthq.library.RegistryHive
import gg.hideouthq.library.SteamInstall
import gg.hideouthq.library.SteamLoginIdentities
import gg.hideouthq.library.WindowsRegistry
import gg.hideouthq.library.deleteTree
import gg.hideouthq.library.digestRest
import gg.hideouthq.library.readSmallText
import gg.hideouthq.library.retryingBusy
import gg.hideouthq.library.sha256
import gg.hideouthq.library.steamInstalls
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.security.DigestOutputStream
import java.time.LocalDateTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.pathString
import kotlin.time.Clock
import kotlin.time.Duration

/** [home] goes to Modlock too, so the Steam [hostingProblem] finds in it is the one Modlock finds. */
class DesktopCustomGames internal constructor(
    private val helper: Path,
    private val runtimeFolder: Path,
    private val store: RuntimeStore,
    override val journal: SessionJournal,
    override val folders: ModeFolders,
    private val log: (String) -> Unit,
    private val windows: Boolean = OnWindows,
    private val home: String = System.getProperty("user.home"),
    private val installs: () -> List<SteamInstall> = ::steamInstalls,
    private val registry: Registry = WindowsRegistry,
    private val pageUrl: String = CustomGamesSite.bridgePage(null),
    private val player: () -> LocalPlayer? = { localPlayerOf(installs()) },
    stageLimit: Duration = StageLimit,
    private val stopLimit: Duration = Deadline.Stopping.after,
) : CustomGamesPlatform {
    private val gamesFolder = GamesFolder(helper, runtimeFolder, log, stageLimit)

    override val firewallMayAsk: Boolean get() = windows

    override suspend fun localPlayer(): LocalPlayer? = withContext(Dispatchers.IO) { player() }

    override suspend fun prepareHost(mode: Mode, choices: Choices, feed: CustomGamesFeed?, progress: (Transfer) -> Unit): PrepareAnswer = withContext(Dispatchers.IO) {
        hostingProblem(modlockSteamRoots(windows, home, registry), installs())?.let { return@withContext refused(it) }
        val me = player() ?: return@withContext refused(PreflightProblem.NoSteamAccount)
        val origin = mode.origin
        if (origin is ModeOrigin.Folder && !Files.isDirectory(Path.of(origin.builtDir))) return@withContext refused(PreflightProblem.FolderMissing)
        val runtime = feed?.runtime ?: return@withContext refused(PreflightProblem.NoModeList)
        val cli = runtime.cli[if (windows) DesktopOs.Windows else DesktopOs.Linux] ?: return@withContext refused(PreflightProblem.NothingToHostWithHere)
        val modlockName = if (windows) "modlock.exe" else "modlock"
        val feedOrigin = origin as? ModeOrigin.Feed
        val wanted = listOfNotNull(
            RuntimePiece.Program(cli, modlockName),
            RuntimePiece.Package(runtime.host, runnable = true),
            RuntimePiece.Package(runtime.platform, runnable = false),
            feedOrigin?.let { RuntimePiece.Package(it.rules, runnable = false) },
            feedOrigin?.client?.let { RuntimePiece.Package(it, runnable = false) },
        )
        val menus = listOf(RuntimePiece.Package(runtime.renderer, runnable = false))
        store.provide(wanted, feed.runtimeDigests(), menus, mode.takeIf { feedOrigin != null }, progress)?.let { return@withContext refused(PreflightProblem.Download(it)) }
        val rules = when (origin) {
            is ModeOrigin.Feed -> store.folderOf(origin.rules.sha256).pathString
            is ModeOrigin.Folder -> stagedRules(Path.of(origin.builtDir)).pathString
        }
        val content = when (origin) {
            is ModeOrigin.Feed -> listOfNotNull(origin.client?.let { store.folderOf(it.sha256).pathString })
            is ModeOrigin.Folder -> listOf(origin.builtDir)
        }
        val shared = (origin as? ModeOrigin.Folder)?.let { packContent(Path.of(it.builtDir)) }
        // The same package can be a feed's platform mod and a mode, and Modlock takes a mod once.
        val mods = listOf(store.folderOf(runtime.platform.sha256).pathString, rules).distinct()
        val offer = InviteOffer.of(mode, choices, me, shared)
        val modlock = store.folderOf(cli.sha256).resolve(modlockName).pathString
        val server = store.folderOf(runtime.host.sha256).resolve(ServerProgram).pathString
        val host = PreparedHost(modlock, server, mods, ModeContent(content), offer, me, shared?.let { localContent.pathString }, ifFetched(runtime.renderer.sha256))
        RuntimePrepared(host, freeUdpPort())
    }

    override suspend fun prepareGuest(mode: Mode?, shared: SharedPack?, feed: CustomGamesFeed?, progress: (Transfer) -> Unit): PrepareAnswer = withContext(Dispatchers.IO) {
        val packages = mode?.origin as? ModeOrigin.Feed
        val wanted = listOfNotNull(packages?.client).map { RuntimePiece.Package(it, runnable = false) }
        val optional = listOfNotNull(feed?.runtime?.renderer, packages?.rules).map { RuntimePiece.Package(it, runnable = false) }
        val keep = feed?.runtimeDigests().orEmpty() + listOfNotNull(shared?.sha256)
        store.provide(wanted, keep, optional, mode, progress)?.let { return@withContext refused(PreflightProblem.Download(it)) }
        val content = listOfNotNull(packages?.client?.sha256, shared?.sha256).map { store.folderOf(it).pathString }
        GuestPrepared(ModeContent(content, shared?.takeUnless { Files.isDirectory(store.folderOf(it.sha256)) }), feed?.runtime?.renderer?.sha256?.let(::ifFetched))
    }

    override suspend fun placeShared(pack: SharedPack): FetchProblem? = store.placeShared(pack)

    override suspend fun installed(): Map<ModeId, InstalledMode> = store.installed()

    override suspend fun install(mode: Mode, feed: CustomGamesFeed, progress: (Transfer) -> Unit): FetchProblem? {
        val origin = mode.origin as? ModeOrigin.Feed ?: return null
        val packages = listOfNotNull(origin.rules, origin.client).map { RuntimePiece.Package(it, runnable = false) }
        return store.provide(packages, feed.runtimeDigests(), listOf(RuntimePiece.Package(feed.runtime.renderer, runnable = false)), mode, progress)
    }

    override suspend fun remove(id: ModeId, feed: CustomGamesFeed?) = store.remove(id, feed?.runtimeDigests().orEmpty())

    override suspend fun played(id: ModeId) = store.played(id, Clock.System.now())

    override suspend fun readFolder(path: String): FolderRead = withContext(Dispatchers.IO) {
        val root = Path.of(path)
        if (!Files.isDirectory(root)) return@withContext FolderRead.NotThere
        val manifest = jsonIn(root.resolve("mod.json"))
        val id = manifest?.text("slug")?.let(ModeId::parse) ?: return@withContext FolderRead.NotAMode
        val version = manifest.text("version")?.takeIf { it.isNotBlank() } ?: return@withContext FolderRead.NotAMode
        val listingFile = root.resolve(ListingFile)
        val listing = if (Files.exists(listingFile)) {
            jsonIn(listingFile)?.let { json -> parseListing(json) { folderArt(root, it) } } ?: return@withContext FolderRead.BadListing
        } else {
            plainListing(manifest.text("name")?.takeIf { it.isNotBlank() } ?: id.value)
        }
        FolderRead.Read(Mode(id, version, listing.copy(curated = false), ModeOrigin.Folder(path)))
    }

    private fun ifFetched(digest: Sha256): Sha256? = digest.takeIf { Files.isDirectory(store.folderOf(it)) }

    /** A copy of a built mod holding no content, since Modlock mounts any it finds, and the content goes in by [stage]. */
    private fun stagedRules(built: Path): Path {
        deleteTree(localRules)
        for (name in builtFiles(built).filterNot(::isContent)) {
            val target = localRules.resolve(name)
            Files.createDirectories(target.parent)
            Files.copy(built.resolve(name), target)
        }
        return localRules
    }

    private fun packContent(built: Path): SharedPack {
        val digest = sha256()
        ZipOutputStream(DigestOutputStream(Files.newOutputStream(localContent).buffered(), digest)).use { zip ->
            for (name in builtFiles(built).filter(::isContent).sortedBy { it.invariantSeparatorsPathString }) {
                zip.putNextEntry(ZipEntry(name.invariantSeparatorsPathString).apply { timeLocal = ZipTime })
                Files.copy(built.resolve(name), zip)
                zip.closeEntry()
            }
        }
        return SharedPack(Sha256.parse(digest.digest().toHexString())!!, Files.size(localContent))
    }

    private fun builtFiles(built: Path): List<Path> = Files.walk(built).use { files -> files.filter { it.isRegularFile() }.map(built::relativize).toList() }

    /** What Modlock's `check.AddonDirs` and the map archives under `maps` put in a game: the content `hideout-link stage` reads. */
    private fun isContent(name: Path): Boolean {
        val top = name.getName(0).toString()
        return name.nameCount > 1 && top in ContentFolders || name.nameCount == 2 && top == "maps" && name.getName(1).toString().endsWith(".vpk")
    }

    private val localRules: Path get() = runtimeFolder.resolveSibling("customgames-local-rules")

    private val localContent: Path get() = runtimeFolder.resolveSibling("customgames-local-content.zip")

    override fun startHost(prepared: PreparedHost, game: GameRoot, port: Int, setup: GameSetup): LinkProcess {
        val choices = prepared.offer.choices.args.flatMap { listOf("--arg", it) }
        val server = listOf(prepared.modlock, "--json", "play", "--no-game", "--watch=false", "--ui-port", "0", "--port", port.toString()) + choices + prepared.mods
        val environment = buildMap {
            put("MODLOCK_NO_UPDATE_CHECK", "1")
            put("MODLOCK_HOST", prepared.server)
            put("DEADLOCK_DIR", game.path)
            if (!windows) put("HOME", home)
        }
        val mods = prepared.mods.flatMap { listOf("--mod", it) }
        val page = listOf("--page", setup.page, "--screen-listen", "127.0.0.1:${setup.screen}")
        val share = prepared.share?.let { listOf("--share", it) }.orEmpty()
        val offer = listOf("--offer", prepared.offer.encoded)
        return start(listOf(helper.pathString, "host", "--server-port", port.toString(), "--steam-id", prepared.me.steamId) + offer + page + share + mods + "--" + server, environment)
    }

    override fun startJoin(code: InviteCode): LinkProcess {
        val process = start(listOf(helper.pathString, "join"), emptyMap(), ::joinFlags)
        try {
            process.give(code.value)
        } catch (e: IOException) {
            process.endNow()
            throw e
        }
        return process
    }

    private fun joinFlags(setup: GameSetup, shared: SharedPack?): List<String> {
        val account = player()?.let { listOf("--steam-id", it.steamId) }.orEmpty()
        val fetch = shared?.let { listOf("--fetch", store.blobOf(it.sha256).pathString, "--sha256", it.sha256.hex, "--bytes", it.bytes.toString()) }.orEmpty()
        return listOf("--page", setup.page, "--screen-listen", "127.0.0.1:${setup.screen}") + account + fetch
    }

    override suspend fun ownedBeforeServer(game: GameRoot): SessionOwned = withContext(Dispatchers.IO) {
        SessionOwned(appIdFile = Files.notExists(appIdFileOf(game)), compileCache = windows && Files.notExists(compileCache))
    }

    override suspend fun menus(game: GameRoot, renderer: Sha256?, rebuild: Boolean): MenusStamp? = withContext(Dispatchers.IO) {
        val have = gamesFolder.pack(game)?.stamp
        val built = renderer?.takeIf { rebuild }?.let(::ifFetched) ?: return@withContext have
        val want = MenusStamp(built.hex, pageUrl, MenusScreenPort, SteamInf.revision(game)?.source, InGameStamp(InGamePort, helperDigest))
        if (have == want) have else gamesFolder.buildMenus(game, store.folderOf(built), want)?.stamp ?: have
    }

    suspend fun menusPack(game: GameRoot): MenusPack? = withContext(Dispatchers.IO) { gamesFolder.pack(game) }

    private val helperDigest: String by lazy { sha256().also { digest -> FileChannel.open(helper).use { it.digestRest(digest) } }.digest().toHexString() }

    override suspend fun clearEndedDevSession(game: GameRoot) = withContext(Dispatchers.IO) { gamesFolder.clearEndedDevSession(game) }

    override suspend fun menusBlockedBy(game: GameRoot): String? = withContext(Dispatchers.IO) { gamesFolder.menusBlockedBy(game) }

    override suspend fun stage(game: GameRoot, content: ModeContent?): StageResult = withContext(Dispatchers.IO) {
        gamesFolder.stage(game, content?.folders.orEmpty())
    }

    override suspend fun removeGamesFolder(game: GameRoot): Boolean = withContext(Dispatchers.IO) { gamesFolder.remove(game) }

    override suspend fun removeLeftovers(game: GameRoot, owned: SessionOwned): Boolean = withContext(Dispatchers.IO) {
        val appId = appIdFileOf(game)
        // The server ends the file with a newline and a NUL.
        val appIdStands = owned.appIdFile && readSmallText(appId)?.trim { it.isWhitespace() || it == '\u0000' } == DeadlockAppId.toString()
        val appIdGone = !appIdStands || removing("steam_appid.txt") { retryingBusy { Files.delete(appId) } }
        val cacheGone = !owned.compileCache || removing("the server's compile cache") { deleteTree(compileCache) }
        appIdGone && cacheGone
    }

    private inline fun removing(what: String, remove: () -> Unit): Boolean = try {
        remove()
        true
    } catch (e: IOException) {
        log("Custom games: could not remove $what: ${e::class.simpleName}")
        false
    }

    private fun appIdFileOf(game: GameRoot) = Path.of(game.path, "game", "steam_appid.txt")

    /** Wasmtime asks Windows for this folder itself, whatever the environment says, so it hangs off the real home. */
    private val compileCache: Path get() = Path.of(home, "AppData", "Local", "BytecodeAlliance")

    override suspend fun gameUpdatePending(game: GameRoot): Boolean = withContext(Dispatchers.IO) { SteamManifest.updatePending(game) }

    private fun start(command: List<String>, environment: Map<String, String>, joinFlags: (GameSetup, SharedPack?) -> List<String> = { _, _ -> emptyList() }): HelperProcess {
        Files.createDirectories(runtimeFolder)
        val builder = ProcessBuilder(command).directory(runtimeFolder.toFile())
        builder.environment().putAll(environment)
        return HelperProcess(builder.start(), log, windows, joinFlags)
    }

    override suspend fun gameRevision(game: GameRoot): GameRevision? = withContext(Dispatchers.IO) { SteamInf.revision(game) }

    override suspend fun endLeftover(entry: JournalEntry) = withContext(Dispatchers.IO) {
        val process = entry.process ?: return@withContext
        val handle = ProcessHandle.of(process.pid).orElse(null) ?: return@withContext
        if (identityOf(handle) != process) return@withContext
        val tree = handle.descendants().toList()
        if (!windows) handle.onExit().completeOnTimeout(handle, stopLimit.inWholeMilliseconds, TimeUnit.MILLISECONDS).join()
        endHelper(handle, windows)
        CompletableFuture.allOf(*tree.map { it.onExit() }.toTypedArray()).completeOnTimeout(null, stopLimit.inWholeMilliseconds, TimeUnit.MILLISECONDS).join()
    }

    companion object {
        fun forThisComputer(storage: CacheStorage, client: () -> HttpClient, log: (String) -> Unit): DesktopCustomGames? {
            val helper = LinkHelperLocation.fromProcess() ?: return null
            val runtime = AppDirs.data().toPath().resolve("customgames")
            return DesktopCustomGames(helper, runtime, RuntimeStore(runtime, client, log), SessionJournal(storage), ModeFolders(storage), log, pageUrl = CustomGamesSite.bridgePage(CustomGamesSite.fromProcess()))
        }
    }
}

private fun refused(problem: PreflightProblem) = RuntimeFailed(Refusal.Preflight(problem))

private fun CustomGamesFeed.runtimeDigests(): Set<Sha256> = buildSet {
    runtime.cli.values.forEach { add(it.sha256) }
    add(runtime.host.sha256)
    add(runtime.platform.sha256)
    add(runtime.renderer.sha256)
}

/** The account signed in to Steam last, the same choice Modlock makes, since its slot is the one the game takes. */
internal fun localPlayerOf(installs: List<SteamInstall>): LocalPlayer? {
    val identities = SteamLoginIdentities.at(installs)
    val last = identities.firstOrNull { it.mostRecent } ?: identities.maxByOrNull { it.lastLoginAt ?: 0 } ?: return null
    return LocalPlayer(last.steamId64.toString(), DisplayName.parse(last.personaName))
}

// On loopback, because a socket open to the network makes Windows ask the player to let Hideout through its firewall.
private fun freeUdpPort(): Int = DatagramChannel.open().use { channel ->
    channel.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
    (channel.localAddress as InetSocketAddress).port
}

private const val ListingFile = "hideout.json"

private fun jsonIn(file: Path): JsonObject? = readSmallText(file)?.let {
    try {
        Json.parseToJsonElement(it) as? JsonObject
    } catch (e: IllegalArgumentException) {
        null
    }
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun folderArt(root: Path, listed: String): String? {
    if (listed.split('/', '\\').any { it == ".." }) return null
    val file = try {
        Path.of(listed).takeUnless { it.isAbsolute }?.let { root.resolve(it).normalize() }
    } catch (e: InvalidPathException) {
        null
    }
    return file?.takeIf { it.startsWith(root.normalize()) && Files.isRegularFile(it) }?.toUri()?.toString()
}

private const val ServerProgram = "modlock-host.exe"

/** The folders of a built mod Modlock's `check.AddonDirs` mounts. */
private val ContentFolders = setOf("panorama", "models", "materials", "particles")

private val ZipTime = LocalDateTime.of(1980, 1, 1, 0, 0)

/** Modlock needs Steam even when told where the game is, so a Steam only Hideout can find is still a Steam it cannot host with. */
internal fun hostingProblem(modlockRoots: List<Path>, installs: List<SteamInstall>): PreflightProblem? {
    fun library(root: Path) = Files.isDirectory(root.resolve("steamapps"))
    return when {
        modlockRoots.any(::library) -> null
        installs.any { library(Path.of(it.root)) } -> PreflightProblem.SteamUnsupported
        else -> PreflightProblem.SteamNotFound
    }
}

/** Where Modlock 532c102-hideout.1 looks for Steam, in `session/steam_other.go` and `session/steam_windows.go`. */
internal fun modlockSteamRoots(windows: Boolean, home: String, registry: Registry): List<Path> =
    if (windows) {
        listOfNotNull(registry.string(RegistryHive.CurrentUser, """Software\Valve\Steam""", "SteamPath"), """C:\Program Files (x86)\Steam""").map { Path.of(it) }
    } else {
        listOf(Path.of(home, ".local", "share", "Steam"), Path.of(home, ".steam", "steam"))
    }

/** The helper beside the real executable, which is inside the AppDir, `/opt`, the MSI's folder or Nix's `lib/hideout`, whatever wrapper started it. */
internal object LinkHelperLocation {
    const val Property = "hideout.customgames.helper"
    const val Environment = "HIDEOUT_CUSTOMGAMES_HELPER"

    fun fromProcess(): Path? = resolve(
        System.getProperty(Property),
        System.getenv(Environment),
        AppDirs.config().toPath().resolve(UserPropertiesFile),
        ProcessHandle.current().info().command().map { Path.of(it) }.orElse(null),
        OnWindows,
    )

    fun resolve(property: String?, environment: String?, userFile: Path, executable: Path?, windows: Boolean): Path? {
        val override = userOverride(property, environment, userFile, Property)?.let { Path.of(it) }
        val found = override ?: besideReal(executable)?.resolve(if (windows) "hideout-link.exe" else "hideout-link")
        return found?.takeIf(Files::isRegularFile)
    }

    private fun besideReal(executable: Path?): Path? = try {
        executable?.toRealPath()?.parent
    } catch (e: IOException) {
        null
    }
}
