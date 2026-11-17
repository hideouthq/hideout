package gg.hideouthq.appupdate

import gg.hideouthq.core.appupdate.PackageFormat
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class InstallDetectionTest {
    private val mount = "/tmp/.mount_HideouAbC"

    private fun detect(
        marker: String?,
        appImage: String? = null,
        appDir: String? = mount,
        executableFolder: String = mount,
        realPath: (Path) -> Path? = { it },
        writable: Boolean = true,
        tools: Set<String> = setOf("pkexec", "apt-get", "dnf"),
    ) = detectInstall(
        marker,
        {
            when (it) {
                "APPIMAGE" -> appImage
                "APPDIR" -> appDir
                else -> null
            }
        },
        Path.of(executableFolder),
        realPath,
        { writable },
        { it in tools },
    )

    @Test
    fun noMarkerMeansAJvmRunOrABareImage() {
        assertEquals(null, detect(null))
        assertEquals(null, detect(null, appImage = "/home/me/Hideout.AppImage"))
    }

    @Test
    fun anUnknownMarkerMeansNoMethod() {
        assertEquals(null, detect("snap"))
        assertEquals(null, detect(""))
    }

    @Test
    fun theMarkerMayEndInANewline() = assertEquals(InstalledAs.Msi, detect("msi\n"))

    @Test
    fun anAppImageInAWritableFolderReplacesTheFileAppImageNames() =
        assertEquals(InstalledAs.AppImage(Path.of("/home/me/Apps/Hideout-1.1.0-x86_64.AppImage")), detect("appimage", appImage = "/home/me/Apps/Hideout-1.1.0-x86_64.AppImage"))

    @Test
    fun nixsExtractedCopyHasTheMarkerButNoAppImageVariable() = assertEquals(null, detect("appimage", appImage = null))

    @Test
    fun nixsCopyStartedFromAnotherAppImagesTerminalLeavesThatAppImageAlone() =
        assertEquals(null, detect("appimage", appImage = "/home/me/Apps/Other.AppImage", appDir = "/tmp/.mount_OtherXyZ", executableFolder = "/nix/store/abc-hideout/lib"))

    @Test
    fun anAppImageVariableWithNoAppDirIsNotBelieved() = assertEquals(null, detect("appimage", appImage = "/home/me/Hideout.AppImage", appDir = null))

    @Test
    fun anAppDirThatDoesNotExistIsNotBelieved() =
        assertEquals(null, detect("appimage", appImage = "/home/me/Hideout.AppImage", realPath = { null }))

    @Test
    fun extractAndRunCountsBecauseTheRuntimePointsAppDirAtTheExtractedFolder() {
        val extracted = "/tmp/appimage_extracted_0123456789abcdef"
        assertEquals(
            InstalledAs.AppImage(Path.of("/home/me/Hideout.AppImage")),
            detect("appimage", appImage = "/home/me/Hideout.AppImage", appDir = extracted, executableFolder = extracted),
        )
    }

    @Test
    fun appDirAndTheExecutablesFolderAreComparedByRealPath() {
        val links = mapOf(Path.of("/tmp/link") to Path.of(mount))
        assertEquals(
            InstalledAs.AppImage(Path.of("/home/me/Hideout.AppImage")),
            detect("appimage", appImage = "/home/me/Hideout.AppImage", appDir = "/tmp/link", realPath = { links[it] ?: it }),
        )
    }

    @Test
    fun anAppImageInAFolderItCannotWriteCanOnlyPointAtGitHub() = assertEquals(null, detect("appimage", appImage = "/opt/apps/Hideout.AppImage", writable = false))

    @Test
    fun aRelativeAppImagePathIsNotBelieved() = assertEquals(null, detect("appimage", appImage = "Hideout.AppImage"))

    @Test
    fun anAppImageVariableWithNoFolderIsNotBelieved() = assertEquals(null, detect("appimage", appImage = "/"))

    @Test
    fun theFolderJudgedIsTheOneHoldingTheAppImage() {
        val asked = mutableListOf<Path>()
        val folder = Path.of(mount)
        detectInstall("appimage", { if (it == "APPDIR") mount else "/home/me/Apps/Hideout.AppImage" }, folder, { it }, { asked.add(it) }, { true })
        assertEquals(listOf(Path.of("/home/me/Apps")), asked)
    }

    @Test
    fun debAndRpmInstallThroughPkexecWithTheirOwnTool() {
        assertEquals(InstalledAs.SystemPackage(PackageFormat.Deb, "apt-get"), detect("deb"))
        assertEquals(InstalledAs.SystemPackage(PackageFormat.Rpm, "dnf"), detect("rpm"))
    }

    @Test
    fun aSystemPackageWithoutPkexecCanOnlyPointAtGitHub() {
        assertEquals(null, detect("deb", tools = setOf("apt-get")))
        assertEquals(null, detect("rpm", tools = setOf("dnf")))
    }

    @Test
    fun anRpmOnASystemWithoutDnfCanOnlyPointAtGitHub() = assertEquals(null, detect("rpm", tools = setOf("pkexec", "zypper")))

    @Test
    fun anMsiNeedsNothingElse() = assertEquals(InstalledAs.Msi, detect("msi", tools = emptySet(), writable = false))
}
