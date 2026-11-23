package gg.hideouthq

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import gg.hideouthq.appupdate.FinishAppUpdateFlag
import gg.hideouthq.appupdate.FinishingUpdate
import gg.hideouthq.appupdate.appUpdateFolder
import gg.hideouthq.appupdate.desktopAppUpdates
import gg.hideouthq.appupdate.finishAppUpdate
import gg.hideouthq.appupdate.isNativeImage
import gg.hideouthq.appupdate.isRunning
import gg.hideouthq.core.customgames.InviteCode
import gg.hideouthq.core.customgames.LiveSession
import gg.hideouthq.core.data.fake.FakeAppUpdate
import gg.hideouthq.core.data.fake.FakeConfig
import gg.hideouthq.core.data.fake.ForeignSeed
import gg.hideouthq.core.data.preview.PreviewRepository
import gg.hideouthq.core.designsystem.icon.rememberWindowIcons
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.network.BusyHosts
import gg.hideouthq.core.network.hideoutHttpClient
import gg.hideouthq.core.ui.LocalReducedMotion
import gg.hideouthq.core.ui.LocalWindowChrome
import gg.hideouthq.customgames.CustomGamesSite
import gg.hideouthq.customgames.DesktopCustomGames
import gg.hideouthq.customgames.customGamesFeed
import gg.hideouthq.customgames.registerAppImageLinks
import gg.hideouthq.data.AppDirs
import gg.hideouthq.data.CoilRemoteImages
import gg.hideouthq.data.DesktopBuild
import gg.hideouthq.data.DesktopReportExport
import gg.hideouthq.data.DesktopViewerFiles
import gg.hideouthq.data.FileCacheStorage
import gg.hideouthq.data.HideoutLog
import gg.hideouthq.data.JavaSoundSamplePlayer
import gg.hideouthq.data.desktopEngine
import gg.hideouthq.data.picturesFolder
import gg.hideouthq.data.readMachine
import gg.hideouthq.data.preview.PreviewCache
import gg.hideouthq.data.preview.PreviewHost
import gg.hideouthq.data.preview.PreviewPictureFetcher
import gg.hideouthq.deadlockstats.DesktopStatsPlatform
import gg.hideouthq.feature.customgames.quitWarning
import gg.hideouthq.feature.moddetail.viewer.LocalModelViewport
import gg.hideouthq.ingame.InGameEndpoint
import gg.hideouthq.library.DataFolderClaim
import gg.hideouthq.library.DesktopConfigFiles
import gg.hideouthq.library.DesktopFileDrop
import gg.hideouthq.library.DesktopFolderOpener
import gg.hideouthq.library.DesktopInstall
import gg.hideouthq.library.DesktopManagerData
import gg.hideouthq.library.FileLibraryStore
import gg.hideouthq.library.FileMigrationRecords
import gg.hideouthq.library.NioGameInventory
import gg.hideouthq.library.NioResourceIndexSource
import gg.hideouthq.library.SteamGameLocator
import gg.hideouthq.library.SteamLauncher
import gg.hideouthq.library.SystemFilePicker
import gg.hideouthq.library.SystemFolderPicker
import gg.hideouthq.library.claimDataFolder
import gg.hideouthq.library.serveFocusRequests
import gg.hideouthq.viewer.GlThread
import gg.hideouthq.viewer.NativeModelViewport
import gg.hideouthq.window.DesktopWindowChrome
import gg.hideouthq.window.MouseSideButtons
import gg.hideouthq.window.WindowResizeGrips
import gg.hideouthq.window.allowOtherInstanceForeground
import gg.hideouthq.window.firstWindowSize
import gg.hideouthq.window.raiseUnless
import com.sun.jna.NativeLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.lwjgl.system.Configuration
import java.awt.Dimension
import java.awt.GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSPARENT
import java.awt.Toolkit
import java.awt.geom.RoundRectangle2D
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

fun main(args: Array<String>) {
    if (args.firstOrNull() == FinishAppUpdateFlag) exitProcess(finishAppUpdate(args.asList()))
    pointLibrariesAtTheExecutable()
    val link = args.firstOrNull(InviteCode::isAppLink)
    val dataFolder = if (System.getProperty("hideout.data") == "fake") null else {
        leaveIfAnUpdateIsFinishing(link)
        ownDataFolderOrExit(link)
    }
    configureNativeMemory()
    nameTheWindowClass()
    // The JDK reads this once, when the first connection picks a proxy, so it is set before anything connects.
    System.setProperty("java.net.useSystemProxies", "true")
    System.setProperty("compose.application.configure.swing.globals", "true")
    val reducedMotion = SystemMotionPreference.prefersReducedMotion()
    val build = DesktopBuild.read()
    val about = build.about
    val userAgent = "Hideout/${about.version} (+https://hideouthq.gg)"
    val previewCache = PreviewHost.fromProcess()?.let { base ->
        PreviewCache(base, AppDirs.cache().resolve("previews").toPath(), userAgent, PreviewHost.built?.key)
    }
    val container = if (System.getProperty("hideout.data") == "fake") {
        val backdrops = Files.createTempDirectory("hideout-fake-backdrops")
        AppContainer.fake(fakeConfigFromSystemProperties(), about = about, filePicker = SystemFilePicker, viewerFiles = DesktopViewerFiles(backdrops, picturesFolder()), customGamesSite = CustomGamesSite.fromProcess())
    } else {
        liveContainer(previewCache, build, userAgent, dataFolder)
    }
    link?.let(container::openAppLink)
    val viewport = NativeModelViewport(previewCache, lazy { GlThread() })
    application {
        val appState = rememberHideoutAppState(container)
        val scope = rememberCoroutineScope()
        val leaving = remember { AtomicBoolean() }
        val leave: () -> Unit = {
            leaving.set(true)
            scope.launch {
                withTimeoutOrNull(ExitSaveWait) { listOf(container.autoexec.saveBeforeExit(), container.crosshairs.saveBeforeExit()).joinAll() }
                if (container.appUpdates.leave()) exitApplication() else leaving.set(false)
            }
        }
        val exit: () -> Unit = {
            if (!appState.quit.ask(quitWarning(container.customGames?.live?.value ?: LiveSession()), leave)) leave()
        }
        LaunchedEffect(Unit) { container.appUpdates.exitRequests.collect { leave() } }
        val shellShown = container.preferences.setupDone.collectAsState().value == true
        val state = rememberWindowState(size = firstWindowSize(), position = WindowPosition(Alignment.Center))
        Window(
            onCloseRequest = exit,
            title = "Hideout",
            state = state,
            undecorated = true,
            // Window installs these once, when it creates the AWT window, so they must not close over composition values.
            onPreviewKeyEvent = appState.keys::onPreviewKeyEvent,
            onKeyEvent = appState.keys::onKeyEvent,
        ) {
            val window = window
            val icons = rememberWindowIcons()
            remember(window) {
                window.minimumSize = Dimension(HideoutDimens.Window.MinWidth.value.toInt(), HideoutDimens.Window.MinHeight.value.toInt())
                window.iconImages = icons
            }
            LaunchedEffect(window, state.size, state.placement) {
                if (window.graphicsConfiguration.device.isWindowTranslucencySupported(PERPIXEL_TRANSPARENT)) {
                    window.shape = if (state.placement == WindowPlacement.Floating) {
                        RoundRectangle2D.Double(0.0, 0.0, state.size.width.value.toDouble(), state.size.height.value.toDouble(), 40.0, 40.0)
                    } else null
                }
            }
            if (shellShown) {
                DisposableEffect(window, appState.fileImport) {
                    val drop = DesktopFileDrop(window, appState.fileImport)
                    onDispose { drop.close() }
                }
            }
            DisposableEffect(window, appState.keys) {
                val sideButtons = MouseSideButtons(window, appState.keys::onPointerButton)
                onDispose { sideButtons.close() }
            }
            DisposableEffect(window, dataFolder) {
                val requests = dataFolder?.serveFocusRequests(::note, onLink = container::openAppLink) { window.raiseUnless(leaving::get) }
                onDispose { requests?.close() }
            }
            val chrome = remember(window, state) { DesktopWindowChrome(window, state, onClose = exit) }
            CompositionLocalProvider(LocalReducedMotion provides reducedMotion, LocalWindowChrome provides chrome, LocalModelViewport provides viewport) {
                HideoutTheme(accent = container.preferences.appearance.collectAsState().value.accent) {
                    Box(Modifier.fillMaxSize()) {
                        AppContent(appState)
                        WindowResizeGrips(window, enabled = state.placement == WindowPlacement.Floating)
                    }
                }
            }
        }
    }
}

private fun leaveIfAnUpdateIsFinishing(link: String?) {
    if (!FinishingUpdate(appUpdateFolder()).handOff(link, ::isRunning, System.currentTimeMillis())) return
    note("Not starting: an update is finishing, and Hideout opens once it's done")
    exitProcess(0)
}

private fun ownDataFolderOrExit(link: String?): DataFolderClaim.Owner? {
    when (val claim = claimDataFolder(AppDirs.data().toPath(), System::getenv, link = link, onRunningInstance = ::allowOtherInstanceForeground)) {
        is DataFolderClaim.Owner -> return claim
        DataFolderClaim.HandedOff -> exitProcess(0)
        is DataFolderClaim.NoAnswer -> {
            note("Not starting: ${claim.reason}")
            exitProcess(RunningInstanceDidNotAnswer)
        }
        is DataFolderClaim.Unlockable -> {
            note(claim.reason)
            return null
        }
    }
}

private fun note(line: String) {
    HideoutLog(AppDirs.data().toPath()).write(line)
    System.err.println(line)
}

private const val RunningInstanceDidNotAnswer = 3

private fun liveContainer(previewCache: PreviewCache?, build: DesktopBuild, userAgent: String, dataFolder: DataFolderClaim.Owner?): AppContainer {
    val about = build.about
    val log = HideoutLog(AppDirs.data().toPath())
    Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
        log.write("Crashed on ${thread.name}: ${failure.stackTraceToString()}")
        failure.printStackTrace()
    }
    val busy = BusyHosts()
    val client = hideoutHttpClient(desktopEngine(), userAgent, log::write, busy)
    val stats = DesktopStatsPlatform(userAgent)
    val cache = AppDirs.cache()
    val storage = FileCacheStorage(cache)
    val data = FileCacheStorage(AppDirs.data())
    val previews = PreviewRepository(previewCache, storage, CoroutineScope(SupervisorJob() + Dispatchers.Default))
    val appUpdates = desktopAppUpdates(client) { DesktopInstall.downloadClient(desktopEngine(), userAgent, log::write) }
    val machine = readMachine(about.version, updatesItself = appUpdates.method != null)
    log.write("Hideout ${about.version} started: ${machine.line}")
    val customGamesPlatform = DesktopCustomGames.forThisComputer(data, { DesktopInstall.downloadClient(desktopEngine(), userAgent, log::write) }, log::write)
    return AppContainer.live(
        postHogProjectKey = build.postHogProjectKey,
        analyticsOs = when { System.getProperty("os.name").startsWith("Windows") -> "windows"; System.getProperty("os.name").startsWith("Linux") -> "linux"; else -> "other" },
        client = client,
        busyHosts = busy,
        storage = storage,
        images = CoilRemoteImages(client, cache.resolve("images"), previewCache?.let { PreviewPictureFetcher(previews, it) }),
        soundPlayer = JavaSoundSamplePlayer(client),
        previews = previews,
        locator = SteamGameLocator.forThisComputer(),
        folderPicker = SystemFolderPicker,
        filePicker = SystemFilePicker,
        data = data,
        libraryStore = FileLibraryStore(AppDirs.data().toPath(), dataFolder?.lock),
        inventory = NioGameInventory,
        ports = DesktopInstall.ports(AppDirs.data().toPath(), cache.toPath(), client, DesktopInstall.downloadClient(desktopEngine(), userAgent, log::write), busy, log::write),
        launcher = SteamLauncher(),
        folderOpener = DesktopFolderOpener(),
        about = about,
        viewerFiles = DesktopViewerFiles(AppDirs.data().toPath().resolve("backdrops"), picturesFolder()),
        statsPlatform = stats,
        resourceIndexSource = NioResourceIndexSource(),
        compatibility = DesktopInstall.compatibility(AppDirs.data().toPath()),
        managerData = DesktopManagerData.forThisComputer(),
        migrationRecords = FileMigrationRecords(AppDirs.data().toPath().resolve("migrations")),
        configFiles = DesktopConfigFiles(),
        reportExport = DesktopReportExport(log, machine, AppDirs.data().toPath()),
        previewFiles = previewCache,
        collectionsSource = previewCache,
        appUpdateMethod = appUpdates.method,
        releaseFeed = appUpdates.feed,
        updatePackages = appUpdates.packages,
        customGamesPlatform = customGamesPlatform,
        customGamesFeed = customGamesFeed(client, data),
        customGamesSite = CustomGamesSite.fromProcess(),
    ).also { container ->
        registerAppImageLinks(log::write)
        val inGame = container.inGame?.let { inGame ->
            customGamesPlatform?.let { platform ->
                InGameEndpoint(inGame, { container.modLibrary.customGamesRoot()?.let { platform.menusPack(it) } }, log::write).also { it.start() }
            }
        }
        Runtime.getRuntime().addShutdownHook(
            Thread {
                container.analytics?.close()
                container.statsService?.close()
                stats.close()
                inGame?.close()
                container.customGames?.close()
            },
        )
    }
}

private fun fakeConfigFromSystemProperties() = FakeConfig(
    latencyMillis = System.getProperty("hideout.fakeLatency")?.toLongOrNull() ?: 0L,
    setupDone = System.getProperty("hideout.setupDone")?.toBooleanStrictOrNull() ?: true,
    foreign = ForeignSeed.entries.firstOrNull { it.name.equals(System.getProperty("hideout.foreign"), ignoreCase = true) } ?: ForeignSeed.None,
    appUpdate = FakeAppUpdate.entries.firstOrNull { it.name.equals(System.getProperty("hideout.update.fake"), ignoreCase = true) } ?: FakeAppUpdate.None,
)

private val ExitSaveWait = 2.seconds

/**
 * AWT names the window class after the bottom stack frame. In a native image that is a method handle rather than the
 * main class, so desktops could not match the window to the menu entry's `StartupWMClass` and its icon.
 */
private fun nameTheWindowClass() {
    if (OnWindows || !isNativeImage()) return
    val toolkit = Toolkit.getDefaultToolkit()
    if (toolkit.javaClass.name != "sun.awt.X11.XToolkit") return
    toolkit.javaClass.getDeclaredField("awtAppClassName").apply { isAccessible = true }.set(null, "gg-hideouthq-MainKt")
}

/**
 * A native image has no `java.home`. Skiko loads its library from `skiko.library.path` and JAWT from `<java.home>/lib`
 * (`bin` on Windows), and Java Sound and Windows' AWT fonts read files under it too. The packages put them all beside
 * the executable.
 */
private fun pointLibrariesAtTheExecutable() {
    if (!isNativeImage()) return
    val folder = Path.of(ProcessHandle.current().info().command().get()).parent.toString()
    System.setProperty("java.home", folder)
    System.setProperty("skiko.library.path", folder)
}

/**
 * glibc serves allocations of up to 32 MiB from its arenas once it has freed one of that size, and arenas keep what the
 * app freed. A fixed 1 MiB threshold maps frames, texture uploads and scene buffers on their own and unmaps them when freed.
 *
 * LWJGL would allocate the viewer's mesh decode and texture upload buffers from the jemalloc it bundles, which purges
 * freed pages only while it keeps allocating, so every bundle's buffer sizes stayed resident after its preview closed.
 * The C library's allocator takes them instead.
 */
internal fun configureNativeMemory() {
    if (System.getProperty("os.name") == "Linux") NativeLibrary.getInstance("c").getFunction("mallopt").invokeInt(arrayOf(MallocMmapThreshold, 1 shl 20))
    Configuration.MEMORY_ALLOCATOR.set("system")
}

private const val MallocMmapThreshold = -3
