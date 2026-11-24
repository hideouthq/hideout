package gg.hideouthq.core.data

import gg.hideouthq.core.customgames.InGameConsent
import gg.hideouthq.core.model.Accent
import gg.hideouthq.core.model.Appearance
import gg.hideouthq.core.model.Backdrop
import gg.hideouthq.core.model.ModCardStyle
import gg.hideouthq.core.model.SceneLighting
import gg.hideouthq.core.model.SceneSettings
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/**
 * The user's choices, kept in one small JSON file in [storage]. A file that cannot be read or understood counts as no
 * choices made, and is replaced by the next change.
 */
class Preferences(private val storage: CacheStorage, private val clock: Clock = Clock.System) {
    private val mutex = Mutex()
    private val published = MutableStateFlow<InstallDefaults?>(null)
    private val publishedScene = MutableStateFlow<SceneSettings?>(null)
    private val publishedStats = MutableStateFlow<StatsPreferences?>(null)
    private val publishedSidebarCollapsed = MutableStateFlow(false)
    private val publishedSetupDone = MutableStateFlow<Boolean?>(null)
    private val publishedHeroes = MutableStateFlow<List<String>>(emptyList())
    private val publishedAutoRepair = MutableStateFlow<Boolean?>(null)
    private val publishedDownloadAppUpdates = MutableStateFlow<Boolean?>(null)
    private val publishedLoad3dPreviews = MutableStateFlow<Boolean?>(null)
    private val publishedHideMature = MutableStateFlow<Boolean?>(null)
    private val publishedCustomGames = MutableStateFlow<Boolean?>(null)
    private val publishedInGameUi = MutableStateFlow<InGameConsent?>(null)
    private val publishedHostModeFolders = MutableStateFlow<Boolean?>(null)
    private val publishedAppearance = MutableStateFlow(Appearance())
    private val publishedNotifyServers = MutableStateFlow(false)
    private val publishedFavourites = MutableStateFlow<Set<String>>(emptySet())
    private val changedGameFolder = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val gameFolderChanges: SharedFlow<Unit> = changedGameFolder

    /** Whether the sidebar was last collapsed, as last read or saved. */
    val sidebarCollapsed: StateFlow<Boolean> = publishedSidebarCollapsed.asStateFlow()

    /** Whether first-run setup was finished, as last read or saved; null before [readSetup]. */
    val setupDone: StateFlow<Boolean?> = publishedSetupDone.asStateFlow()

    /** The heroes Discover leads with, catalogue ids such as `hero_inferno`, the most played first: the ones the Stats account plays most, kept here so a start leads with them before Stats has loaded. */
    val preferredHeroes: StateFlow<List<String>> = publishedHeroes.asStateFlow()

    /** Settings › Appearance › Theme, as last read or saved; the defaults before [readSetup]. */
    val appearance: StateFlow<Appearance> = publishedAppearance.asStateFlow()

    suspend fun setAccent(accent: Accent) = change { it.copy(accent = accent.name) }

    suspend fun setModCards(style: ModCardStyle) = change { it.copy(modCards = style.name) }

    /** Custom games › Servers › "Tell me when servers are live", as last read or saved; off before [readSetup]. */
    val notifyServers: StateFlow<Boolean> = publishedNotifyServers.asStateFlow()

    suspend fun setNotifyServers(on: Boolean) = change { it.copy(notifyServers = on) }

    /** The mods the user starred, by app-wide id such as "mod-721908", kept after one is uninstalled; empty before [readSetup]. */
    val favourites: StateFlow<Set<String>> = publishedFavourites.asStateFlow()

    suspend fun setFavourite(modId: String, favourite: Boolean) = change { it.copy(favourites = if (favourite) it.favourites + modId else it.favourites - modId) }

    /** Reads what the app opens with: [sidebarCollapsed], [setupDone], [preferredHeroes], [appearance], [notifyServers] and [favourites]. */
    suspend fun readSetup() {
        mutex.withLock {
            val stored = load()
            publishedSidebarCollapsed.value = stored.sidebarCollapsed
            publishedSetupDone.value = stored.setupDone
            publishedHeroes.value = stored.preferredHeroes
            publishedAppearance.value = stored.appearance()
            publishedNotifyServers.value = stored.notifyServers
            publishedFavourites.value = stored.favourites
        }
    }

    /** Setup has nothing left to ask, or was skipped: the app opens on the shell from now on. */
    suspend fun finishSetup() = change { it.copy(setupDone = true) }

    suspend fun setPreferredHeroes(heroIds: List<String>) = change { it.copy(preferredHeroes = heroIds) }

    suspend fun setSidebarCollapsed(collapsed: Boolean) = change { it.copy(sidebarCollapsed = collapsed) }

    /**
     * The install defaults as last read or saved, or null before the first read. A change appears here only after
     * [storage] has taken it, so a refused write leaves the saved value showing.
     */
    val defaults: StateFlow<InstallDefaults?> = published.asStateFlow()

    /** Stats' saved choices, as last read or saved; null before the first read. */
    val stats: StateFlow<StatsPreferences?> = publishedStats.asStateFlow()

    /** The Deadlock folder the user chose, or null to let Steam say where it is. */
    suspend fun gameFolder(): String? = mutex.withLock { load().gameFolder }

    suspend fun setGameFolder(path: String?) = change { it.copy(gameFolder = path) }

    /** What installing does without asking each time, read together so one install sees one answer. */
    suspend fun installDefaults(): InstallDefaults = mutex.withLock { load().defaults().also { published.value = it } }

    suspend fun setAskWhichVersion(ask: Boolean) = change { it.copy(askWhichVersion = ask) }

    /** Settings › Mods › "Keep downloaded files", for downloads that start from now on. */
    suspend fun setKeepDownloads(keep: Boolean) = change { it.copy(keepDownloads = keep) }

    /** Settings › Mods › "Download folder" for downloads that start from now on; null for Hideout's own. */
    suspend fun setDownloadFolder(folder: DownloadFolder?) = change { it.copy(downloadFolder = folder?.path) }

    /** Library's list/cards choice, as last read or saved; null before the first read. Read leniently: an unrecognised value counts as none chosen. */
    suspend fun libraryLayout(): String? = mutex.withLock { load().libraryLayout }

    suspend fun setLibraryLayout(value: String) = change { it.copy(libraryLayout = value) }

    /** Whether Library lists every installed mod or only the active profile's, read as leniently as [libraryLayout]. */
    suspend fun libraryScope(): String? = mutex.withLock { load().libraryScope }

    suspend fun setLibraryScope(value: String) = change { it.copy(libraryScope = value) }

    /** Whether Library has once said that edits save while Deadlock runs. */
    suspend fun runningNoteSeen(): Boolean = mutex.withLock { load().runningNoteSeen }

    suspend fun setRunningNoteSeen() = change { it.copy(runningNoteSeen = true) }

    suspend fun appUpdateOpened(): String? = mutex.withLock { load().appUpdateOpened }

    suspend fun setAppUpdateOpened(version: String) = change { it.copy(appUpdateOpened = version) }

    /** The 3D viewer's scene as last read or saved, shared by every mod page; null before the first read. */
    val scene: StateFlow<SceneSettings?> = publishedScene.asStateFlow()

    suspend fun sceneSettings(): SceneSettings = mutex.withLock { load().scene().also { publishedScene.value = it } }

    suspend fun setScene(settings: SceneSettings) = change {
        it.copy(
            sceneBackdrop = settings.backdrop.name,
            sceneLighting = settings.lighting.name,
            sceneTurntable = settings.turntable,
            sceneBloom = settings.bloom,
            sceneCustomBackdrop = settings.customBackdrop,
        )
    }

    suspend fun statsPreferences(): StatsPreferences = mutex.withLock { load().stats().also { publishedStats.value = it } }

    /** Settings › Experimental › "Repair skins automatically", as last read or saved; null before [readAutoRepair]. */
    val autoRepair: StateFlow<Boolean?> = publishedAutoRepair.asStateFlow()

    suspend fun readAutoRepair() = mutex.withLock { publishedAutoRepair.value = load().autoRepair }

    suspend fun setAutoRepair(on: Boolean) = change { it.copy(autoRepair = on) }

    /** Settings › About › "Download updates automatically", as last read or saved; null before [readDownloadAppUpdates]. */
    val downloadAppUpdates: StateFlow<Boolean?> = publishedDownloadAppUpdates.asStateFlow()

    suspend fun readDownloadAppUpdates() = mutex.withLock { publishedDownloadAppUpdates.value = load().downloadAppUpdates }

    suspend fun setDownloadAppUpdates(on: Boolean) = change { it.copy(downloadAppUpdates = on) }

    /** Settings › Mods › "Load 3D previews by default", as last read or saved; null before [readLoad3dPreviews]. */
    val load3dPreviews: StateFlow<Boolean?> = publishedLoad3dPreviews.asStateFlow()

    suspend fun readLoad3dPreviews() = mutex.withLock { publishedLoad3dPreviews.value = load().load3dPreviews }

    suspend fun setLoad3dPreviews(on: Boolean) = change { it.copy(load3dPreviews = on) }

    /** Settings › Mods › "Hide mature mods", as last read or saved; null before [readHideMature]. */
    val hideMature: StateFlow<Boolean?> = publishedHideMature.asStateFlow()

    suspend fun readHideMature() = mutex.withLock { publishedHideMature.value = load().hideMature }

    suspend fun setHideMature(on: Boolean) = change { it.copy(hideMature = on) }

    /** Settings › Experimental › "Custom games", as last read or saved; null before [readCustomGames]. */
    val customGames: StateFlow<Boolean?> = publishedCustomGames.asStateFlow()

    suspend fun readCustomGames() = mutex.withLock { publishedCustomGames.value = load().customGames }

    suspend fun setCustomGames(on: Boolean) = change { it.copy(customGames = on) }

    /** Settings › Experimental › "Hideout games inside Deadlock", as last read or saved; null before [readInGameUi]. */
    val inGameUi: StateFlow<InGameConsent?> = publishedInGameUi.asStateFlow()

    suspend fun readInGameUi() = mutex.withLock { publishedInGameUi.value = load().inGameConsent() }

    suspend fun setInGameUi(on: Boolean) = change { it.copy(inGameUi = on) }

    /** The custom games page's question: the answer holds for this run even when saving it throws, so the question isn't asked again. */
    suspend fun answerInGameUi(on: Boolean) {
        publishedInGameUi.value = if (on) InGameConsent.Yes else InGameConsent.No
        setInGameUi(on)
    }

    /** Settings › Developer › "Host mode folders", as last read or saved; null before [readHostModeFolders]. */
    val hostModeFolders: StateFlow<Boolean?> = publishedHostModeFolders.asStateFlow()

    suspend fun readHostModeFolders() = mutex.withLock { publishedHostModeFolders.value = load().hostModeFolders }

    suspend fun setHostModeFolders(on: Boolean) = change { it.copy(hostModeFolders = on) }

    /** Settings › Game › Stats. Off stops every Stats request and the Steam cache watcher. */
    suspend fun setStatsEnabled(enabled: Boolean) = change { it.copy(statsEnabled = enabled) }

    suspend fun setSteamTrackingEnabled(enabled: Boolean) = change { it.copy(steamTrackingEnabled = enabled) }

    /** Settings › Stats › "Share my match IDs with deadlock-api", for matches found from the moment it is turned on. */
    suspend fun setShareMatchIds(on: Boolean) = change {
        it.copy(shareMatchIdsSince = if (on) it.shareMatchIdsSince ?: clock.now().epochSeconds else null)
    }

    /** The 32-bit Steam account Stats shows, or null to ask again. */
    suspend fun setStatsAccount(accountId: Long?) = change { it.copy(statsAccountId = accountId) }

    /** The installs whose downloads were under way, or failed on the connection, when they were last seen. */
    suspend fun unfinishedInstalls(): List<UnfinishedInstall> = mutex.withLock { load().unfinishedInstalls }

    /** Keeps [install] for the next start, in place of any earlier one of the same mod. */
    suspend fun keepUnfinished(install: UnfinishedInstall) = change { stored ->
        stored.copy(unfinishedInstalls = stored.unfinishedInstalls.filter { it.modId != install.modId } + install)
    }

    suspend fun forgetUnfinished(modId: String) = change { stored -> stored.copy(unfinishedInstalls = stored.unfinishedInstalls.filter { it.modId != modId }) }

    /** The GameBanana files [modId] had when it was last uninstalled, empty when it never was. */
    suspend fun lastFiles(modId: String): List<Long> = mutex.withLock { load().lastFiles[modId].orEmpty() }

    /** Remembers [fileIds] as what [modId] had when it was uninstalled, so installing it again starts from them. */
    suspend fun rememberFiles(modId: String, fileIds: List<Long>) = change { it.copy(lastFiles = it.lastFiles + (modId to fileIds)) }

    suspend fun snapshot(): SettingsSnapshot = mutex.withLock { load().snapshot() }

    /** The mode and period filters every Stats tab shares, saved as their names. */
    suspend fun setStatsFilters(mode: String, period: String) = change { it.copy(statsMode = mode, statsPeriod = period) }

    private suspend fun change(edit: (StoredPreferences) -> StoredPreferences) = mutex.withLock {
        val stored = load()
        val next = edit(stored)
        if (next != stored) storage.write(FileName, json.encodeToString(next))
        if (next.gameFolder != stored.gameFolder) changedGameFolder.tryEmit(Unit)
        published.value = next.defaults()
        publishedScene.value = next.scene()
        publishedStats.value = next.stats()
        publishedSidebarCollapsed.value = next.sidebarCollapsed
        publishedSetupDone.value = next.setupDone
        publishedHeroes.value = next.preferredHeroes
        publishedAutoRepair.value = next.autoRepair
        publishedDownloadAppUpdates.value = next.downloadAppUpdates
        publishedLoad3dPreviews.value = next.load3dPreviews
        publishedHideMature.value = next.hideMature
        publishedCustomGames.value = next.customGames
        publishedInGameUi.value = next.inGameConsent()
        publishedHostModeFolders.value = next.hostModeFolders
        publishedAppearance.value = next.appearance()
        publishedNotifyServers.value = next.notifyServers
        publishedFavourites.value = next.favourites
    }

    private suspend fun load(): StoredPreferences {
        val text = quietly { storage.read(FileName) } ?: return StoredPreferences()
        return try {
            json.decodeFromString<StoredPreferences>(text).also { it.defaults() }
        } catch (e: IllegalArgumentException) {
            StoredPreferences()
        }
    }

    private companion object {
        const val FileName = "preferences.json"
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    }
}

/**
 * What installing does without asking each time (Settings › Mods): ask which version when a mod has several files, keep
 * the download afterwards, and where downloads go, Hideout's own folder when null. One install reads them all once,
 * before it asks anything, and keeps them to the end.
 */
data class InstallDefaults(
    val askWhichVersion: Boolean = true,
    val keepDownloads: Boolean = true,
    val downloadFolder: DownloadFolder? = null,
)

@Serializable
private data class StoredPreferences(
    val gameFolder: String? = null,
    val askWhichVersion: Boolean = true,
    val keepDownloads: Boolean = true,
    val downloadFolder: String? = null,
    val libraryLayout: String? = null,
    val libraryScope: String? = null,
    val sceneBackdrop: String? = null,
    val sceneLighting: String? = null,
    val sceneTurntable: Boolean = true,
    val sceneBloom: Boolean = false,
    val sceneCustomBackdrop: String? = null,
    val statsEnabled: Boolean = true,
    val statsAccountId: Long? = null,
    val statsMode: String? = null,
    val statsPeriod: String? = null,
    val steamTrackingEnabled: Boolean = false,
    val shareMatchIdsSince: Long? = null,
    val sidebarCollapsed: Boolean = false,
    val setupDone: Boolean = false,
    val preferredHeroes: List<String> = emptyList(),
    val autoRepair: Boolean = true,
    val downloadAppUpdates: Boolean = true,
    val load3dPreviews: Boolean = true,
    val hideMature: Boolean = false,
    val customGames: Boolean = true,
    /** Null until the player answers the custom games page's question. */
    val inGameUi: Boolean? = null,
    val hostModeFolders: Boolean = false,
    val accent: String? = null,
    val modCards: String? = null,
    val notifyServers: Boolean = false,
    val runningNoteSeen: Boolean = false,
    val appUpdateOpened: String? = null,
    val unfinishedInstalls: List<UnfinishedInstall> = emptyList(),
    val lastFiles: Map<String, List<Long>> = emptyMap(),
    val favourites: Set<String> = emptySet(),
) {
    fun defaults() = InstallDefaults(askWhichVersion, keepDownloads, downloadFolder?.let(::DownloadFolder))

    fun scene() = SceneSettings(
        backdrop = Backdrop.entries.firstOrNull { it.name == sceneBackdrop } ?: Backdrop.Studio,
        lighting = SceneLighting.entries.firstOrNull { it.name == sceneLighting } ?: SceneLighting.Studio,
        turntable = sceneTurntable,
        bloom = sceneBloom,
        customBackdrop = sceneCustomBackdrop,
    )

    /** Read leniently: a name this build does not know counts as none chosen. */
    fun appearance() = Appearance(
        accent = Accent.entries.firstOrNull { it.name == accent } ?: Accent.Graphite,
        modCards = ModCardStyle.entries.firstOrNull { it.name == modCards } ?: ModCardStyle.OnImage,
    )

    fun inGameConsent() = when (inGameUi) {
        null -> InGameConsent.Unanswered
        true -> InGameConsent.Yes
        false -> InGameConsent.No
    }

    fun stats() = StatsPreferences(statsEnabled, statsAccountId, statsMode, statsPeriod, steamTrackingEnabled, shareMatchIdsSince)

    fun snapshot() = SettingsSnapshot(
        gameFolder, defaults(), autoRepair, downloadAppUpdates, load3dPreviews, hideMature, customGames, inGameConsent(), hostModeFolders, statsEnabled, steamTrackingEnabled,
        shareMatchIdsSince != null, unfinishedInstalls.map { it.modId },
    )
}

/** The saved choices a bug report lists; nothing here names the user's accounts. [gameFolder] is null while Steam says where the game is. */
data class SettingsSnapshot(
    val gameFolder: String?,
    val install: InstallDefaults,
    val autoRepair: Boolean,
    val downloadAppUpdates: Boolean,
    val load3dPreviews: Boolean,
    val hideMature: Boolean,
    val customGames: Boolean,
    val inGameUi: InGameConsent,
    val hostModeFolders: Boolean,
    val stats: Boolean,
    val steamTracking: Boolean,
    val shareMatchIds: Boolean,
    val unfinishedInstalls: List<String>,
)

/**
 * Stats' saved choices. [mode] and [period] are names the Stats screen reads leniently. [shareMatchIdsSince] is when
 * sharing match IDs with deadlock-api was turned on, in epoch seconds, or null while it is off.
 */
data class StatsPreferences(val enabled: Boolean = true, val accountId: Long? = null, val mode: String? = null, val period: String? = null,
    val steamTrackingEnabled: Boolean = false, val shareMatchIdsSince: Long? = null) {
    val shareMatchIds: Boolean get() = shareMatchIdsSince != null
}

/**
 * An install of GameBanana mod [modId] that was downloading into [folder], with what it needs to go on from the bytes
 * there: the files chosen, the profiles it turns them on in, and the installation it changes, if any, as an update
 * ([update]), as a reinstall, or as new files.
 */
@Serializable
data class UnfinishedInstall(
    val modId: String,
    val folder: String,
    val fileIds: List<Long>,
    val profiles: List<String> = emptyList(),
    val installId: String? = null,
    val reinstall: Boolean = false,
    val update: List<UnfinishedReplacement> = emptyList(),
)

/** One file an unfinished update replaces, and the exact file it replaces it with. */
@Serializable
data class UnfinishedReplacement(val fromFileId: Long, val fromMd5: String?, val toFileId: Long, val toMd5: String?)
