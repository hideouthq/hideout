package gg.hideouthq

import gg.hideouthq.core.analytics.*

import gg.hideouthq.core.appupdate.AppUpdateMethod
import gg.hideouthq.core.appupdate.AppUpdates
import gg.hideouthq.core.appupdate.AppVersion
import gg.hideouthq.core.appupdate.ReleaseFeedSource
import gg.hideouthq.core.appupdate.UpdatePackages
import gg.hideouthq.core.autoexec.Autoexec
import gg.hideouthq.core.crosshair.Crosshairs
import gg.hideouthq.core.crosshair.HeroSettings
import gg.hideouthq.core.crosshair.community.CommunityCrosshairs
import gg.hideouthq.core.crosshair.community.DmmCrosshairs
import gg.hideouthq.core.customgames.CustomGames
import gg.hideouthq.core.customgames.CustomGamesFeed
import gg.hideouthq.core.customgames.CustomGamesFeedSource
import gg.hideouthq.core.customgames.CustomGamesFetch
import gg.hideouthq.core.customgames.CustomGamesPlatform
import gg.hideouthq.core.customgames.LibrarySessionFiles
import gg.hideouthq.core.customgames.SiteOrigin
import gg.hideouthq.core.data.CacheStorage
import gg.hideouthq.core.data.CatalogRepository
import gg.hideouthq.core.data.ConfigFiles
import gg.hideouthq.core.data.Downloader
import gg.hideouthq.core.data.FilePicker
import gg.hideouthq.core.data.FolderOpener
import gg.hideouthq.core.data.FolderPicker
import gg.hideouthq.core.data.GameBananaClient
import gg.hideouthq.core.data.GameInventory
import gg.hideouthq.core.data.GameLauncher
import gg.hideouthq.core.data.GameLocator
import gg.hideouthq.core.data.GameProcesses
import gg.hideouthq.core.data.HeroSettingsFiles
import gg.hideouthq.core.data.LibraryStore
import gg.hideouthq.core.data.ReportExport
import gg.hideouthq.core.data.ManagerData
import gg.hideouthq.core.data.MigrationRecords
import gg.hideouthq.core.data.NewsRepository
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.SoundSamplePlayer
import gg.hideouthq.core.data.ViewerFiles
import gg.hideouthq.core.data.collections.CollectionsRepository
import gg.hideouthq.core.data.collections.CollectionsSource
import gg.hideouthq.core.data.collections.LiveCollectionsRepository
import gg.hideouthq.core.data.deadlock.DeadlockApi
import gg.hideouthq.core.data.fake.FakeAppUpdatePorts
import gg.hideouthq.core.data.fake.FakeCatalogRepository
import gg.hideouthq.core.data.fake.FakeCommunityCrosshairs
import gg.hideouthq.core.data.fake.FakeConfig
import gg.hideouthq.core.data.fake.FakeConfigFiles
import gg.hideouthq.core.data.fake.FakeCustomGamesFeed
import gg.hideouthq.core.data.fake.FakeCustomGamesPlatform
import gg.hideouthq.core.data.fake.FakeDownloader
import gg.hideouthq.core.data.fake.FakeGameBanana
import gg.hideouthq.core.data.fake.FakeGameLauncher
import gg.hideouthq.core.data.fake.FakeGameLocator
import gg.hideouthq.core.data.fake.FakeManagerData
import gg.hideouthq.core.data.fake.FakeProcesses
import gg.hideouthq.core.data.fake.FakeProfileShares
import gg.hideouthq.core.data.fake.FakeSoundSamplePlayer
import gg.hideouthq.core.data.fake.FakeStore
import gg.hideouthq.core.data.fake.InMemoryGameFiles
import gg.hideouthq.core.data.fake.InMemoryHeroSettings
import gg.hideouthq.core.data.fake.InMemoryLibraryStore
import gg.hideouthq.core.data.fake.InMemoryMigrationRecords
import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.data.fake.NoCacheStorage
import gg.hideouthq.core.data.fake.SampleAbout
import gg.hideouthq.core.data.fake.SampleCollections
import gg.hideouthq.core.data.fake.SampleFolders
import gg.hideouthq.core.data.fake.SampleGame
import gg.hideouthq.core.data.fake.SampleNews
import gg.hideouthq.core.data.fake.UncataloguedSampleManifest
import gg.hideouthq.core.data.gamebanana.GameBananaApi
import gg.hideouthq.core.data.live.GameBananaCatalogSource
import gg.hideouthq.core.data.live.LiveCatalogRepository
import gg.hideouthq.core.data.live.SnapshotStore
import gg.hideouthq.core.data.news.SteamNewsRepository
import gg.hideouthq.core.data.preview.PreviewFiles
import gg.hideouthq.core.data.preview.PreviewRepository
import gg.hideouthq.core.designsystem.image.NoRemoteImages
import gg.hideouthq.core.designsystem.image.RemoteImages
import gg.hideouthq.core.library.CompatibilityPort
import gg.hideouthq.core.library.FilePriorities
import gg.hideouthq.core.library.InstallPorts
import gg.hideouthq.core.library.LibraryState
import gg.hideouthq.core.library.ModLibrary
import gg.hideouthq.core.library.ResourceIndex
import gg.hideouthq.core.library.ResourceIndexCache
import gg.hideouthq.core.library.ResourceIndexSource
import gg.hideouthq.core.migration.Migration
import gg.hideouthq.core.model.AppAbout
import gg.hideouthq.core.sharing.InstallLink
import gg.hideouthq.core.sharing.InstallLinks
import gg.hideouthq.core.sharing.ProfileImports
import gg.hideouthq.core.sharing.ProfileLink
import gg.hideouthq.core.sharing.ProfileShares
import gg.hideouthq.core.sharing.SiteProfileShares
import gg.hideouthq.feature.discover.followPlayedHeroes
import gg.hideouthq.feature.discover.prefetchFeaturedRenders
import gg.hideouthq.core.customgames.social.Social
import gg.hideouthq.core.customgames.social.SocialNetwork
import gg.hideouthq.feature.ingame.InGame
import gg.hideouthq.feature.stats.NoStatsSource
import gg.hideouthq.feature.stats.StatsSource
import gg.hideouthq.feature.stats.data.LiveStatsSource
import gg.hideouthq.feature.stats.data.StatsPlatform
import gg.hideouthq.feature.stats.data.StatsService
import gg.hideouthq.core.network.BusyHosts
import gg.hideouthq.core.network.gameBananaUntil
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Instant

class AppContainer(
    val catalog: CatalogRepository,
    /** Starts the game. */
    val launcher: GameLauncher,
    val soundPlayer: SoundSamplePlayer,
    /** Draws pictures from URLs (covers, hero portraits); without one, only placeholders show. */
    val images: RemoteImages = NoRemoteImages,
    /** Generated previews, which are optional: without a host there are none and nothing else changes. */
    val previews: PreviewRepository,
    val modLibrary: ModLibrary,
    /** The user's choices: the same instance [modLibrary] reads its install defaults from. */
    val preferences: Preferences,
    val folderPicker: FolderPicker,
    /** Shows a set's folder in the system file manager. */
    val folderOpener: FolderOpener,
    val about: AppAbout,
    val filePicker: FilePicker = FilePicker.Unavailable,
    /** The 3D viewer's own files: the user's backdrop and saved pictures. */
    val viewerFiles: ViewerFiles = ViewerFiles.Unavailable,
    /** Deadlock's news on Steam, for Discover. */
    val news: NewsRepository = NewsRepository.None,
    /** The skin collections, for Discover and their pages; none without a preview host. */
    val collections: CollectionsRepository = CollectionsRepository.None,
    /** Match stats; [NoStatsSource] where this build has no Stats backend. */
    val stats: StatsSource = NoStatsSource,
    /** Owns Stats' sessions and watcher, to close at shutdown; null without a backend. */
    val statsService: StatsService? = null,
    val filePriorities: FilePriorities? = null,
    /** Bringing mods in from another manager, shared by onboarding and Library. */
    val migration: Migration,
    /** What Deadlock runs when it starts, autoexec.cfg, for Autoexec. */
    val autoexec: Autoexec,
    /** The crosshair the game uses and the crosshairs of Yours, for Crosshairs and an import. */
    val crosshairs: Crosshairs,
    /** The crosshairs people share, for Crosshairs › Browse. */
    val communityCrosshairs: CommunityCrosshairs,
    /** Opens autoexec.cfg in the system's editor, or its folder. */
    val configFiles: ConfigFiles = ConfigFiles.Unavailable,
    val reportExport: ReportExport = ReportExport.Unavailable,
    /** The kept 3D preview files, for Settings; null where this build has no previews. */
    val previewFiles: PreviewFiles? = null,
    val appUpdates: AppUpdates = neverChecking(preferences),
    /** Hosting and joining custom games, to close at shutdown; null where this build has no helper to run them with. */
    val customGames: CustomGames? = null,
    /** Where a host's invite links point; null where no site is set, and a host then hands out the code alone. */
    val customGamesSite: SiteOrigin? = null,
    val inGame: InGame? = null,
    /** The same site's short codes for shared profiles. */
    val profileShares: ProfileShares = ProfileShares.None,
    /** Profile links on their way to the import, from the menu or the system. */
    val profileImports: ProfileImports = ProfileImports(),
    val installLinks: InstallLinks = InstallLinks(),
    val analytics: UsageAnalytics? = null,
) {
    /**
     * Hands a `hideout:` link from a launch or a second instance to its part of Hideout: profile links to the import,
     * GameBanana's 1-click links to the shell, the rest to custom games.
     */
    fun openAppLink(link: String) {
        when {
            ProfileLink.isAppLink(link) -> profileImports.open(link)
            InstallLink.isAppLink(link) -> installLinks.open(InstallLink.parse(link))
            else -> customGames?.openLink(link)
        }
    }

    companion object {
        /**
         * The real catalogue from GameBanana and the Deadlock API over [client], saved in [storage]
         * between runs, with pictures drawn by [images] and previews played by [soundPlayer].
         * Generated [previews] are matched to each mod's exact current file and never affect the
         * catalogue; the skin collections come from the same host through [collectionsSource]. The game is found by [locator], and the user's choice of folder is kept in [data].
         * What is installed comes from the manifest in [libraryStore], checked against the game through
         * [inventory]; installing, turning sets on or off and uninstalling go through [ports], and
         * [launcher] starts the game once [ModLibrary.launch] has readied it. The catalogue's checks also ask
         * GameBanana about the files of every submission the library holds. [folderOpener] shows
         * a set's folder, and [about] is this build's version. Hideout updates itself through [appUpdateMethod], from
         * [releaseFeed], keeping packages in [updatePackages]; with no feed, or a version that isn't a release's, it never checks. Custom games exist only with a [customGamesPlatform], and offer the modes in [customGamesFeed]. Background work (checking the catalogue, refreshing the preview index, finding
         * the game, reading the manifest, watching gameinfo.gi, finishing a change once it is asked for)
         * lives as long as the app. [busyHosts] is [client]'s: installs and catalogue checks that wait on GameBanana say
         * until when.
         */
        fun live(
            client: HttpClient,
            busyHosts: BusyHosts,
            storage: CacheStorage,
            images: RemoteImages,
            soundPlayer: SoundSamplePlayer,
            previews: PreviewRepository,
            locator: GameLocator,
            folderPicker: FolderPicker,
            data: CacheStorage,
            libraryStore: LibraryStore,
            inventory: GameInventory,
            ports: InstallPorts,
            launcher: GameLauncher,
            folderOpener: FolderOpener,
            about: AppAbout,
            filePicker: FilePicker = FilePicker.Unavailable,
            viewerFiles: ViewerFiles = ViewerFiles.Unavailable,
            statsPlatform: StatsPlatform? = null,
            resourceIndexSource: ResourceIndexSource = ResourceIndexSource { _, _, _ -> ResourceIndex.Unavailable },
            compatibility: CompatibilityPort = CompatibilityPort.Unavailable,
            managerData: ManagerData = ManagerData.None,
            migrationRecords: MigrationRecords,
            configFiles: ConfigFiles = ConfigFiles.Unavailable,
            reportExport: ReportExport = ReportExport.Unavailable,
            previewFiles: PreviewFiles? = null,
            collectionsSource: CollectionsSource? = null,
            appUpdateMethod: AppUpdateMethod? = null,
            releaseFeed: ReleaseFeedSource? = null,
            updatePackages: UpdatePackages = UpdatePackages.None,
            customGamesPlatform: CustomGamesPlatform? = null,
            customGamesFeed: CustomGamesFeedSource? = null,
            customGamesSite: SiteOrigin? = null,
            socialNetwork: SocialNetwork? = null,
            analyticsOs: String = "unknown",
            postHogProjectKey: String? = null,
        ): AppContainer {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val preferences = Preferences(data)
            val analytics = postHogProjectKey?.let { UsageAnalytics(data, PostHogTransport(client, about.version, analyticsOs, it), scope) }
            val indexes = ResourceIndexCache(storage, resourceIndexSource)
            val gameBananaBusy = busyHosts.hosts.map { it.gameBananaUntil() }.stateIn(scope, SharingStarted.Eagerly, null)
            val library = ModLibrary(locator, preferences, libraryStore, inventory, ports, scope, compatibility = compatibility, resourceIndexes = indexes, gameBananaBusy = gameBananaBusy, analytics = analytics).apply { start() }
            val autoexec = Autoexec(library, inventory, scope).apply { start() }
            val crosshairs = Crosshairs(library, data, scope, log = ports.log).apply { start() }
            val migration = Migration(library, inventory, managerData, migrationRecords, crosshairs, scope, log = ports.log, analytics = analytics).apply { start() }
            val source = GameBananaCatalogSource(GameBananaApi(client), DeadlockApi(client), Clock.System)
            val catalog = LiveCatalogRepository(
                source, SnapshotStore(storage), scope, Clock.System, previews = previews.index, installed = library.installedSources,
                previewsAvailable = previews.available, busy = gameBananaBusy,
            )
            val news = SteamNewsRepository(client, storage, scope, Clock.System).apply { start() }
            val statsService = statsPlatform?.let { StatsService(it, preferences, scope, Clock.System) }
            val running = AppVersion.parse(about.version)
            val appUpdates = AppUpdates(
                appUpdateMethod, running ?: NoVersion, releaseFeed.takeIf { running != null }, updatePackages, data, preferences,
                library.state.modsBusy(), scope, ports.log,
            ).apply { start() }
            library.refuseSessionsWhen { appUpdates.restartBegun }
            val customGames = customGamesPlatform?.let {
                CustomGames(it, LibrarySessionFiles(library, launcher), customGamesFeed, scope, ports.log, preferences.hostFolders(), analytics, preferences.inGameAnswer(), preferences::answerInGameUi).apply { start() }
            }
            scope.launch { prefetchFeaturedRenders(catalog, preferences.preferredHeroes, images) }
            statsService?.let { stats -> scope.launch { followPlayedHeroes(stats, catalog, preferences) } }
            return AppContainer(
                analytics = analytics,
                catalog = catalog,
                launcher = launcher,
                soundPlayer = soundPlayer,
                images = images,
                previews = previews,
                modLibrary = library,
                filePriorities = FilePriorities(library, indexes),
                preferences = preferences,
                folderPicker = folderPicker,
                folderOpener = folderOpener,
                about = about,
                filePicker = filePicker,
                viewerFiles = viewerFiles,
                news = news,
                collections = collectionsSource?.let { LiveCollectionsRepository(it, storage, scope) } ?: CollectionsRepository.None,
                stats = statsService?.let { LiveStatsSource(it, catalog, Clock.System) } ?: NoStatsSource,
                statsService = statsService,
                migration = migration,
                autoexec = autoexec,
                crosshairs = crosshairs,
                communityCrosshairs = DmmCrosshairs(client, storage, scope),
                configFiles = configFiles,
                reportExport = reportExport,
                previewFiles = previewFiles,
                appUpdates = appUpdates,
                customGames = customGames,
                customGamesSite = customGamesSite,
                inGame = customGames?.let { InGame(it, Social(it, socialNetwork, scope).apply { start() }, customGamesSite, scope) },
                profileShares = customGamesSite?.let { SiteProfileShares(client, it) } ?: ProfileShares.None,
            )
        }

        /**
         * Sample data throughout, for tests and screenshots, with the real [ModLibrary] over the sample
         * manifest and game ([libraryStore], [files]), GameBanana answering from [gameBanana], downloads
         * landing in those files through [downloader] and the game running when [processes] says so. [FakeConfig.previews]
         * attaches fixture bundles from [previews] to sample mods for offscreen viewer checks.
         * [FakeConfig.game] places the made-up game; the real [ModLibrary] finds it at once, and
         * [folderPicker] stands in for the user choosing a folder and [folderOpener] for the file manager.
         * [FakeConfig.appUpdate] scripts the Hideout update, against [about]'s version or a sample one. Custom games offer
         * [customGamesFeed]. Another manager's data is [managerData], [FakeConfig.foreign]'s unless given.
         */
        fun fake(
            config: FakeConfig = FakeConfig(),
            previews: PreviewRepository = PreviewRepository(null, NoCacheStorage, CoroutineScope(SupervisorJob())),
            images: RemoteImages = NoRemoteImages,
            folderPicker: FolderPicker = FolderPicker { _, _ -> null },
            launcher: GameLauncher = FakeGameLauncher(),
            libraryStore: LibraryStore = InMemoryLibraryStore(UncataloguedSampleManifest.manifest(config)),
            files: InMemoryGameFiles = UncataloguedSampleManifest.files(config),
            gameBanana: GameBananaClient = FakeGameBanana(),
            processes: GameProcesses = FakeProcesses(),
            downloader: (InMemoryGameFiles) -> Downloader = { FakeDownloader(it, SampleFolders.Downloads) },
            folderOpener: FolderOpener = FolderOpener { _, _ -> true },
            about: AppAbout = SampleAbout,
            filePicker: FilePicker = FilePicker.Unavailable,
            viewerFiles: ViewerFiles = ViewerFiles.Unavailable,
            stats: StatsSource = NoStatsSource,
            resourceIndexSource: ResourceIndexSource? = null,
            compatibility: CompatibilityPort = CompatibilityPort.Unavailable,
            migrationRecords: MigrationRecords = InMemoryMigrationRecords(),
            configFiles: ConfigFiles = FakeConfigFiles(files),
            reportExport: ReportExport = ReportExport.Unavailable,
            communityCrosshairs: CommunityCrosshairs = FakeCommunityCrosshairs(),
            crosshairsData: CacheStorage = MemoryStorage(),
            heroSettings: HeroSettingsFiles = InMemoryHeroSettings(mapOf(InMemoryHeroSettings.Cloud to HeroSettings.New.render())),
            previewFiles: PreviewFiles? = null,
            preferences: Preferences = Preferences(MemoryStorage()),
            customGamesSite: SiteOrigin? = null,
            profileShares: ProfileShares = FakeProfileShares(customGamesSite),
            customGamesFeed: CustomGamesFeed = FakeCustomGamesFeed.Placeholder,
            gameBananaBusy: StateFlow<Instant?> = MutableStateFlow(null),
            managerData: ManagerData = FakeManagerData(config.foreign),
            analytics: UsageAnalytics? = null,
        ): AppContainer {
            val store = FakeStore(config)
            val catalog = FakeCatalogRepository(store)
            val gameScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            if (config.game == SampleGame.WrongFolder) gameScope.launch { preferences.setGameFolder(SampleFolders.SteamLibrary) }
            if (config.setupDone) gameScope.launch { preferences.finishSetup() }
            val steam = if (config.game == SampleGame.Found) listOf(SampleFolders.Steam) else emptyList()
            val indexes = resourceIndexSource?.let { ResourceIndexCache(MemoryStorage(), it) }
            val library = ModLibrary(
                FakeGameLocator(steam), preferences, libraryStore, files,
                InstallPorts(gameBanana, downloader(files), files, files, processes, gameFiles = files.gameFiles, backups = files.backups, heroSettings = heroSettings), gameScope, compatibility = compatibility,
                resourceIndexes = indexes, gameBananaBusy = gameBananaBusy, analytics = analytics,
            ).apply { start() }
            val autoexec = Autoexec(library, files, gameScope).apply { start() }
            val crosshairs = Crosshairs(library, crosshairsData, gameScope).apply { start() }
            val migration = Migration(library, files, managerData, migrationRecords, crosshairs, gameScope, analytics = analytics).apply { start() }
            val running = AppVersion.parse(about.version) ?: SampleVersion
            val updatePorts = FakeAppUpdatePorts(config.appUpdate, running)
            if (!updatePorts.downloadsAutomatically) gameScope.launch { preferences.setDownloadAppUpdates(false) }
            val appUpdates = AppUpdates(
                updatePorts.method, running, updatePorts.feed, updatePorts.packages, updatePorts.records, preferences,
                library.state.modsBusy(), gameScope,
            ).apply { start() }
            library.refuseSessionsWhen { appUpdates.restartBegun }
            val customGames = CustomGames(FakeCustomGamesPlatform(), LibrarySessionFiles(library, launcher), FakeCustomGamesFeed(answer = CustomGamesFetch.Fresh(customGamesFeed)), gameScope, hostFolders = preferences.hostFolders(), analytics = analytics, inGame = preferences.inGameAnswer(), saveInGame = preferences::answerInGameUi).apply { start() }
            return AppContainer(
                analytics = analytics,
                catalog = catalog,
                launcher = launcher,
                soundPlayer = FakeSoundSamplePlayer(),
                images = images,
                previews = previews,
                modLibrary = library,
                filePriorities = indexes?.let { FilePriorities(library, it) },
                preferences = preferences,
                folderPicker = folderPicker,
                folderOpener = folderOpener,
                about = about,
                filePicker = filePicker,
                viewerFiles = viewerFiles,
                news = SampleNews(),
                collections = SampleCollections,
                stats = stats,
                migration = migration,
                autoexec = autoexec,
                crosshairs = crosshairs,
                communityCrosshairs = communityCrosshairs,
                configFiles = configFiles,
                reportExport = reportExport,
                previewFiles = previewFiles,
                appUpdates = appUpdates,
                customGames = customGames,
                customGamesSite = customGamesSite,
                profileShares = profileShares,
            )
        }

        private val SampleVersion = AppVersion(1, 3, 2)

        private fun Flow<LibraryState>.modsBusy(): Flow<Boolean> = map { it.modsBusy }.distinctUntilChanged()
    }
}

private val NoVersion = AppVersion(0, 0, 0)

private fun neverChecking(preferences: Preferences) = AppUpdates(
    method = null,
    running = NoVersion,
    feed = null,
    packages = UpdatePackages.None,
    records = NoCacheStorage,
    preferences = preferences,
    modsBusy = emptyFlow(),
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
)

private fun Preferences.hostFolders() = hostModeFolders.onStart { readHostModeFolders() }.filterNotNull()

private fun Preferences.inGameAnswer() = inGameUi.onStart { readInGameUi() }.filterNotNull()
