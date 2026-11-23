package gg.hideouthq.nativepackage

import org.jetbrains.amper.plugins.Configurable
import java.nio.file.Path

/** Who the app is, what its packages show, and how native-image compiles it. */
@Configurable
interface NativePackageSettings {
    /** The name people see: the launcher, the menu entry and the package file names. Linux packages use it in lower case. */
    val name: String

    /**
     * The reverse-DNS app id. It names the AppImage's desktop file and icon and the folder of the generated
     * `app.properties`, and the Windows upgrade code is derived from it, so it never changes once released.
     */
    val id: String

    /** The URL scheme every package format sends to the app, as an argument to the executable. */
    val urlScheme: String

    /**
     * The untracked file a local build reads HIDEOUT_BUILD_PREVIEW_URL, HIDEOUT_BUILD_MASTER and HIDEOUT_BUILD_POSTHOG_KEY
     * from, as lines of those names, when the environment does not set them. It need not exist.
     */
    val previewFile: Path

    /** The app version. MSI accepts one to three numbers. */
    val version: String

    /** One line about the app, for package managers and menus. */
    val description: String

    /** The app's home page. */
    val homepage: String

    /** Who packages and Windows name as the publisher. */
    val vendor: String

    /** The square PNG the Linux packages and the AppImage show. */
    val linuxIcon: Path

    /** The ICO the MSI, its shortcuts and the Windows launcher show, holding each size Windows picks from. */
    val windowsIcon: Path

    val windowsInstallerTheme: Path

    /** Options every native-image build gets, such as the garbage collector and build-time initialization. */
    val imageOptions: List<String>

    /** Options added when building on Windows. */
    val windowsImageOptions: List<String>

    /**
     * The gzipped profile-guided optimization profile. The image is optimized with it when the file exists.
     */
    val pgoProfile: Path

    /** Packages the DEB depends on: those that hold the system libraries the image and its JDK libraries link. The RPM's come from rpmbuild, which reads them. */
    val debDependencies: List<String>

    /** The notice index that must name every runtime JAR the package ships, as `readNoticeIndex` reads it. */
    val noticeIndex: Path

    /** The Go module of the link helper, built for the build host's system into the image beside the executable. */
    val linkHelper: Path
}

internal val NativePackageSettings.linuxPackage: String get() = name.lowercase()
