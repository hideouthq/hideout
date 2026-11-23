package gg.hideouthq.core.customgames

import gg.hideouthq.core.data.CacheStorage
import gg.hideouthq.core.data.GameLauncher
import gg.hideouthq.core.library.GameActivity
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.GamesLines
import gg.hideouthq.core.library.LibraryProblem
import gg.hideouthq.core.library.ModLibrary
import gg.hideouthq.core.library.SessionAdmission
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration

/** The computer's side of a custom game. Implemented once for the desktop, faked in tests. */
interface CustomGamesPlatform {
    /**
     * Finds the Steam account that hosts, downloads, verifies and extracts what [mode] runs from, packs a folder mode's
     * content for guests, and picks a free UDP port for it. The [PreparedHost]'s offer comes from [InviteOffer.of]. A stop during a download cancels this call. [feed] is the newest, null when none has been read yet,
     * and says what of the runtime to keep. A feed mode is recorded as installed once its packages are here.
     */
    suspend fun prepareHost(mode: Mode, choices: Choices, feed: CustomGamesFeed?, progress: (Transfer) -> Unit): SessionEvent.PrepareAnswer

    /** Who plays on this computer, or null when Steam has no account here. */
    suspend fun localPlayer(): LocalPlayer?

    /**
     * Gets [mode]'s packages, which the owner picked as the ones the invite asks for, and the menus [feed] names. Null
     * plays with stock assets. A join has to work with the feed or the site down, so a renderer that could not be fetched
     * leaves the menus to whatever pack the game has. A mode's own client package is different, because its game can't
     * load without it. The mode is recorded as installed once its packages are here, so the guest can host it too.
     * [shared] is the host's pack, which the content names, and the helper fetches unless its folder is here already.
     */
    suspend fun prepareGuest(mode: Mode?, shared: SharedPack?, feed: CustomGamesFeed?, progress: (Transfer) -> Unit): SessionEvent.PrepareAnswer

    /** Unpacks [pack], which the join's helper fetched, into its folder, unless it is there already. */
    suspend fun placeShared(pack: SharedPack): FetchProblem?

    suspend fun installed(): Map<ModeId, InstalledMode>

    /** Fetches [mode]'s packages and records it, replacing the record of an older version. Only called while no session is held. */
    suspend fun install(mode: Mode, feed: CustomGamesFeed, progress: (Transfer) -> Unit): FetchProblem?

    /** Deletes the record of [id] and its packages that nothing else here needs. Only called while no session is held. */
    suspend fun remove(id: ModeId, feed: CustomGamesFeed?)

    /** Stamps [id]'s record as played now, if there is one. */
    suspend fun played(id: ModeId)

    /**
     * A developer's build folder at [path] as a [Mode] with [ModeOrigin.Folder]: `mod.json`'s slug and version, and
     * `hideout.json` read by [parseListing], with its art inside the folder, or [plainListing] with none. Never curated.
     */
    suspend fun readFolder(path: String): FolderRead

    /**
     * Starts the helper with stdin held open, serving [PreparedHost.share] when there is one and the page [setup] names
     * to this computer's game. Throws when it cannot be started, and the session then ends as a failed link.
     */
    fun startHost(prepared: PreparedHost, game: GameRoot, port: Int, setup: GameSetup): LinkProcess

    /** Starts a guest's helper and gives it [code] on stdin, which keeps it out of argv. Throws as [startHost] does. */
    fun startJoin(code: InviteCode): LinkProcess

    /** What [game] and this computer lack before a host's server runs, so that what appears from here on is this session's. */
    suspend fun ownedBeforeServer(game: GameRoot): SessionOwned

    /**
     * The stamp of the menus pack [game] has, rebuilt first from [renderer] when [rebuild] and the stamp differs from what
     * is wanted now. Only called with [rebuild] while Deadlock is closed. A rebuild that fails keeps the old pack.
     */
    suspend fun menus(game: GameRoot, renderer: Sha256?, rebuild: Boolean): MenusStamp?

    /**
     * Takes the block of a Modlock development session that has ended out of [game]'s `gameinfo.gi`, as Modlock's next
     * session would, since it mounts that session's menus ahead of Hideout's. A live session's block stays. Only called
     * while Deadlock is closed.
     */
    suspend fun clearEndedDevSession(game: GameRoot)

    /**
     * The first search path [game]'s `gameinfo.gi` mounts ahead of Hideout's menus with a pack that holds a file the
     * menus pack holds, or null when none does. Reads only the packs' directory trees.
     */
    suspend fun menusBlockedBy(game: GameRoot): String?

    /** Makes the mode's folder in [game] hold exactly [content], or nothing. An unwanted file a game or server holds stays and is named. */
    suspend fun stage(game: GameRoot, content: ModeContent?): StageResult

    /** Deletes Hideout's custom games folder from [game], once its block no longer names it. False when some of it stands. */
    suspend fun removeGamesFolder(game: GameRoot): Boolean

    /** Removes from [game] what [owned] proves this session's, once the session has released the files. False when any of it still stands. */
    suspend fun removeLeftovers(game: GameRoot, owned: SessionOwned): Boolean

    /** Whether Steam has an update for [game] it has not finished. A running server's files can keep it from finishing. */
    suspend fun gameUpdatePending(game: GameRoot): Boolean

    suspend fun gameRevision(game: GameRoot): GameRevision?

    val journal: SessionJournal

    val folders: ModeFolders

    /** Whether the system may stop everything to ask about a server that opens a port, as Windows' firewall does. */
    val firewallMayAsk: Boolean get() = false

    /** Sees the process [entry] recorded end, killing it if it will not. Does nothing when its identity no longer matches. */
    suspend fun endLeftover(entry: JournalEntry)
}

sealed interface StageResult {
    /** [held] names the unwanted files a running game or server kept, which the next stage removes. */
    data class Staged(val held: List<String>) : StageResult

    data class Failed(val problem: String) : StageResult
}

/**
 * What a session can prove it left on this computer. [appIdFile] and [compileCache] say the game had no
 * `steam_appid.txt` and Windows no `BytecodeAlliance` folder before this session's server ran, so what appears there is this session's.
 */
@Serializable
data class SessionOwned(val appIdFile: Boolean = false, val compileCache: Boolean = false)

/**
 * The host's helper serves [offer] to guests, and its choices become the server's `--arg`s. [share] is the pack file guests fetch, which
 * [offer] names. [renderer] is what this computer's menus are built from. [me] is the account whose game the helper
 * sends in and shows its screen.
 */
data class PreparedHost(
    val modlock: String,
    val server: String,
    val mods: List<String>,
    val content: ModeContent,
    val offer: InviteOffer,
    val me: LocalPlayer,
    val share: String? = null,
    val renderer: Sha256? = null,
)

/** A mode's files for the game, by folder. [shared] is a host's pack the guest's helper fetches, which admission unpacks into its folder first. */
data class ModeContent(val folders: List<String>, val shared: SharedPack? = null)

interface LinkProcess {
    /** Null when the system would not say, and a crash then leaves the process to its own parent-death handling. */
    val identity: ProcessIdentity?

    /**
     * The helper's lines as [parseLinkEvent] reads them, collected once. The last is always a [LinkEvent.Stopped]. When
     * the output ends without one, this emits `Stopped(Unknown)` itself.
     */
    val events: Flow<LinkEvent>

    /** Writes `enter <seconds>`. Does nothing once [stop] has closed stdin. */
    suspend fun enter(window: Duration)

    /** Does nothing once [stop] has closed stdin. */
    suspend fun join(setup: GameSetup, shared: SharedPack?)

    /** Closes the helper's stdin, which is its signal to stop. Returns without waiting for it to exit. */
    suspend fun stop()

    /** Force-ends the helper. What it started ends with it, by its parent-death signal on Linux and its job on Windows. */
    suspend fun kill()
}

@Serializable
data class ProcessIdentity(val pid: Long, val startedAtMillis: Long)

/**
 * What a crash leaves to clean up. [game] is set once the library holds that game's files, and [process] once the helper
 * runs. Holds no invite.
 */
@Serializable
data class JournalEntry(val game: GameRoot? = null, val process: ProcessIdentity? = null, val owned: SessionOwned? = null)

/** The journal as one small file in [storage]. A file that cannot be read counts as no journal, and an empty one as cleared. */
class SessionJournal(private val storage: CacheStorage) {
    suspend fun read(): JournalEntry? = try {
        storage.read(FileName)?.takeIf { it.isNotEmpty() }?.let { KeptJson.decodeFromString<JournalEntry>(it) }
    } catch (e: IllegalArgumentException) {
        null
    }

    suspend fun write(entry: JournalEntry) = storage.write(FileName, KeptJson.encodeToString(entry))

    suspend fun clear() = storage.write(FileName, "")

    private companion object {
        const val FileName = "custom-game-journal.json"
        val KeptJson = Json { ignoreUnknownKeys = true }
    }
}

/** The library's side of a session, so the owner's tests can fake it. */
interface SessionFiles {
    /** Whether the client runs, starting with what is known now. */
    val clientActivity: Flow<GameActivity>

    suspend fun game(): GameRoot?

    /** Whether `gameinfo.gi` names the custom games paths now, which for a running Deadlock is what it started with. */
    suspend fun gamesLines(): GamesLines

    /** Records whether custom games are in use here. The lines follow at once while Deadlock is closed, and the problem says why they did not. */
    suspend fun useCustomGames(on: Boolean): LibraryProblem?

    /** Holds the files. Deadlock may run. Writes nothing. */
    suspend fun admit(): SessionAdmission

    /** A plain Steam launch under the hold. Null once handed to Steam. */
    suspend fun launchClient(): LibraryProblem?

    /** Ends the running client, and the activity above then shows it closed. Throws when it would not end. */
    suspend fun closeClient()

    /** Holds [game], if nothing does, so [release] can end a session a crash left. */
    suspend fun resume(game: GameRoot)

    suspend fun release(): LibraryProblem?
}

class LibrarySessionFiles(private val library: ModLibrary, private val launcher: GameLauncher) : SessionFiles {
    override val clientActivity: Flow<GameActivity> = library.state.map { it.deadlock }.distinctUntilChanged()

    override suspend fun game(): GameRoot? = library.customGamesRoot()

    override suspend fun gamesLines(): GamesLines = library.gamesLines()

    override suspend fun useCustomGames(on: Boolean): LibraryProblem? = library.useCustomGames(on)

    override suspend fun admit(): SessionAdmission = library.admitSession()

    override suspend fun launchClient(): LibraryProblem? = library.launchSessionClient(launcher::launch)

    override suspend fun closeClient() = library.closeSessionClient()

    override suspend fun resume(game: GameRoot) = library.resumeSession(game)

    override suspend fun release(): LibraryProblem? = library.releaseSession()
}
