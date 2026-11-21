package gg.hideouthq.nativepackage

import org.w3c.dom.Element
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.io.path.isExecutable
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalPathApi::class)
class PackageNativeTest {
    private val root = createTempDirectory("native-package")
    private val settings = testSettings(root)

    @AfterTest
    fun cleanUp() = root.deleteRecursively()

    @Test
    fun linuxPackagesInstallTheImageInOptWithTheMenuEntryAndIconWhereDesktopsLook() {
        val image = (root / "image").createDirectories()
        (image / "Example").writeText("executable")
        (image / "Example").toFile().setExecutable(true)
        settings.linuxIcon.writeText("png")

        installTree(image, root / "tree", settings, "com.example.MainKt", "deb")

        assertTrue((root / "tree/opt/example/Example").isExecutable(), "the executable keeps its mode")
        assertEquals("deb", (root / "tree/opt/example/package-format").readText())
        assertEquals(desktopEntry(settings, "com.example.MainKt", exec = "/opt/example/Example", icon = "com.example.app"), (root / "tree/usr/share/applications/com.example.app.desktop").readText())
        assertEquals("png", (root / "tree/usr/share/icons/hicolor/512x512/apps/com.example.app.png").readText())
    }

    @Test
    fun theDebDependsOnTheNamedPackagesAndTheRpmOnTheSystemLibrariesRpmbuildFinds() {
        assertContains(debControl(settings, 42), "Package: example\nVersion: 1.2.3\nArchitecture: amd64\n")
        assertContains(debControl(settings, 42), "Installed-Size: 42\nDepends: libfontconfig1, libgl1\n")
        ((root / "tree/opt/example").createDirectories() / "libawt.so").writeText("library")
        val spec = rpmSpec(settings, root / "tree")
        assertContains(spec, "Name: example\nVersion: 1.2.3\n")
        assertContains(spec, "AutoProv: no\n%global __requires_exclude ^(libawt\\\\.so)\\\\(\n")
        assertContains(spec, "cp -a ${root / "tree"}/. %{buildroot}/\n")
        assertContains(spec, "%files\n/opt/example\n/usr/share/applications/com.example.app.desktop\n/usr/share/icons/hicolor/512x512/apps/com.example.app.png\n")
    }

    @Test
    fun theMsiInstallsEveryFileOfTheImagePerUserUnderAnUpgradeCodeFromTheId() {
        val image = (root / "image").createDirectories()
        (image / "Example.exe").writeText("executable")
        ((image / "bin").createDirectories() / "jawt.dll").writeText("jawt")

        val wix = wixSource(settings, image, writePackageFormat((root / "msi").createDirectories(), "msi"))

        assertContains(wix, "UpgradeCode=\"${java.util.UUID.nameUUIDFromBytes("com.example.app".toByteArray())}\"")
        assertContains(wix, "InstallScope=\"perUser\" InstallPrivileges=\"limited\"")
        assertContains(wix, "Source=\"${image / "Example.exe"}\"")
        assertContains(wix, "<Directory Id=\"dir_bin\" Name=\"bin\">")
        assertContains(wix, "Source=\"${image / "bin" / "jawt.dll"}\"")
        assertContains(wix, "Target=\"[INSTALLDIR]Example.exe\"")
        val mainFolder = wix.substringAfter("<DirectoryRef Id=\"INSTALLDIR\">").substringBefore("</DirectoryRef>")
        assertContains(mainFolder, "Source=\"${root / "msi" / "package-format"}\"")
        assertEquals("msi", (root / "msi" / "package-format").readText())
    }

    @Test
    fun aFailedMsiUpgradeRollsBackBecauseRemovingTheOldVersionSharesTheInstallsTransaction() {
        val wix = wixSource(settings, (root / "image").createDirectories(), writePackageFormat((root / "msi").createDirectories(), "msi"))

        assertContains(wix.substringAfter("<MajorUpgrade").substringBefore("/>"), "Schedule=\"afterInstallInitialize\"")
    }

    @Test
    fun theExeEmbedsTheMsiAndLeavesItsInstalledProductInChargeOfUpdatesAndUninstall() {
        val installer = root / "Example-1.2.3.msi"
        settings.windowsInstallerTheme.writeText("theme")
        (settings.windowsInstallerTheme.parent / "art & mascot.png").writeText("art")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(bundleSource(settings, installer).byteInputStream())
        val bundle = document.getElementsByTagName("Bundle").item(0) as Element
        val msi = document.getElementsByTagName("MsiPackage").item(0) as Element
        val ui = document.getElementsByTagName("bal:WixStandardBootstrapperApplication").item(0) as Element

        assertEquals(upgradeUuid("com.example.app/setup").toString(), bundle.getAttribute("UpgradeCode"))
        assertEquals(installer.toAbsolutePath().toString(), msi.getAttribute("SourceFile"))
        assertEquals("yes", msi.getAttribute("Visible"))
        assertEquals("yes", msi.getAttribute("Permanent"))
        assertEquals("yes", msi.getAttribute("Compressed"))
        assertEquals("keep", msi.getAttribute("Cache"))
        assertEquals("", ui.getAttribute("LicenseUrl"))
        assertEquals(settings.windowsInstallerTheme.toAbsolutePath().toString(), ui.getAttribute("ThemeFile"))
        val payloads = document.getElementsByTagName("Payload")
        assertEquals(1, payloads.length)
        assertEquals((settings.windowsInstallerTheme.parent / "art & mascot.png").toAbsolutePath().toString(), (payloads.item(0) as Element).getAttribute("SourceFile"))
        assertEquals("[LocalAppDataFolder]Example\\Example.exe", ui.getAttribute("LaunchTarget"))
    }

    @Test
    fun theExePreservesMetadataAndPathsContainingXmlCharacters() {
        val configured = object : NativePackageSettings by settings {
            override val vendor = "Example & \"Company\""
            override val homepage = "https://example.com/?a=1&b=2"
        }
        val installer = root / "build & packages" / "Example.msi"
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(bundleSource(configured, installer).byteInputStream())
        val bundle = document.getElementsByTagName("Bundle").item(0) as Element
        val msi = document.getElementsByTagName("MsiPackage").item(0) as Element

        assertEquals(configured.vendor, bundle.getAttribute("Manufacturer"))
        assertEquals(configured.homepage, bundle.getAttribute("AboutUrl"))
        assertEquals(installer.toAbsolutePath().toString(), msi.getAttribute("SourceFile"))
    }

    @Test
    fun theWindowsExecutableNamesItsVersionPublisherAndIcon() {
        val script = windowsResourceScript(settings, root / "app.manifest")

        assertContains(script, "1 ICON \"${settings.windowsIcon.toAbsolutePath().toString().replace("\\", "\\\\")}\"")
        assertContains(script, "FILEVERSION 1,2,3,0\n")
        assertContains(script, "VALUE \"CompanyName\", \"Example Vendor\"")
        assertContains(script, "VALUE \"OriginalFilename\", \"Example.exe\"")
    }

    @Test
    fun theWindowsExecutableDeclaresItScalesItself() {
        val manifest = root / "app.manifest"

        assertContains(windowsResourceScript(settings, manifest), "1 24 \"${manifest.toAbsolutePath().toString().replace("\\", "\\\\")}\"\n")
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(WindowsManifest.byteInputStream())
        assertEquals("true/PM", document.getElementsByTagNameNS("http://schemas.microsoft.com/SMI/2005/WindowsSettings", "dpiAware").item(0).textContent)
        assertEquals("PerMonitorV2", document.getElementsByTagNameNS("http://schemas.microsoft.com/SMI/2016/WindowsSettings", "dpiAwareness").item(0).textContent)
    }

    @Test
    fun theGeneratedVersionResourceSitsInTheIdsFolder() {
        writeAppPropertiesFile("gg.example.App", "2.3.4", root / "generated") { null }

        assertEquals("version=2.3.4\n", (root / "generated" / "gg" / "example" / "App" / "app.properties").readText())
    }

    @Test
    fun changingBuildConfigurationRemovesOrReplacesTheCaptureKey() {
        val generated = root / "generated"
        val file = generated / "gg/example/App/app.properties"
        fun write(key: String?) = writeAppPropertiesFile("gg.example.App", "2.3.4", generated) { key }

        write("phc_first")
        assertEquals("version=2.3.4\nposthog.projectKey=phc_first\n", file.readText())
        write(null)
        assertEquals("version=2.3.4\n", file.readText())
        write("phc_second")
        assertEquals("version=2.3.4\nposthog.projectKey=phc_second\n", file.readText())
        write("  ")
        assertEquals("version=2.3.4\n", file.readText())
    }

    @Test
    fun invalidCaptureKeysFailWithoutIncludingTheirValue() {
        for (key in listOf("phx_personal", "phc_project\nversion=other", "phc_")) {
            val failure = assertFailsWith<IllegalStateException> {
                writeAppPropertiesFile("gg.example.App", "2.3.4", root / "generated") { key }
            }
            assertEquals("$PostHogKeyVariable must be a PostHog project capture key", failure.message)
        }
    }

    @Test
    fun theImageIsNotBuiltWhenARuntimeJarHasNoNotice() {
        val app = file("build/tasks/_app_jarJvm/app-jvm.jar")
        val shared = file("build/tasks/_shared_jarJvm/shared-jvm.jar")
        val mapped = file("m2/org/example/mapped/1.0/mapped-1.0.jar").also { pom(it, "org.example", "mapped", "1.0") }
        val stray = file("m2/org/example/stray/2.0/stray-2.0.jar").also { pom(it, "org.example", "stray", "2.0") }
        val index = (root / "index.tsv").also { it.writeText("Mapped\tMIT\tyes\tmit.txt\torg.example:mapped:1.0\n") }

        requireNotices(app, listOf(app, shared, mapped), index)
        val failure = assertFailsWith<IllegalStateException> { requireNotices(app, listOf(app, shared, mapped, stray), index) }
        assertContains(failure.message.orEmpty(), stray.toString())
    }

    @Test
    fun theLinkHelperIsBuiltExecutableIntoTheImageForEveryFormatToCopy() {
        val source = (root / "link").createDirectories()
        (source / "go.mod").writeText("module example.com/link\n\ngo 1.21\n")
        (source / "main.go").writeText("package main\n\nfunc main() {}\n")
        val image = (root / "image").createDirectories()

        buildLinkHelper(source, image, windows = false)

        assertTrue((image / "hideout-link").isExecutable(), "the helper is executable in the image")
        installTree(image, root / "tree", settings.also { it.linuxIcon.writeText("png") }, "com.example.MainKt", "deb")
        assertTrue((root / "tree/opt/example/hideout-link").isExecutable(), "the helper keeps its mode in an installed package")
    }

    @Test
    fun aHostWithoutGoFailsNamingWhatToInstall() {
        val failure = assertFailsWith<IllegalStateException> {
            buildLinkHelper(root / "link", (root / "image").createDirectories(), windows = false, go = "go-that-is-not-installed")
        }
        assertContains(failure.message.orEmpty(), "Put Go on PATH")
    }

    @Test
    fun theMsiInstallsTheLinkHelperBesideTheExecutable() {
        val image = (root / "image").createDirectories()
        (image / "hideout-link.exe").writeText("helper")

        val wix = wixSource(settings, image, writePackageFormat((root / "msi").createDirectories(), "msi"))

        val mainFolder = wix.substringAfter("<DirectoryRef Id=\"INSTALLDIR\">").substringBefore("</DirectoryRef>")
        assertContains(mainFolder, "Source=\"${image / "hideout-link.exe"}\"")
    }

    @Test
    fun theMsiRegistersTheSchemeForThisUserAndRemovesItWithTheApp() {
        val image = (root / "image").createDirectories()
        (image / "Example.exe").writeText("executable")

        val wix = wixSource(settings, image, writePackageFormat((root / "msi").createDirectories(), "msi"))

        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(wix.byteInputStream())
        val key = document.getElementsByTagName("RegistryKey").item(0) as Element
        assertEquals("HKCU", key.getAttribute("Root"))
        assertEquals("Software\\Classes\\example", key.getAttribute("Key"))
        assertEquals("yes", key.getAttribute("ForceDeleteOnUninstall"))
        val values = key.getElementsByTagName("RegistryValue")
        val byKey = (0 until values.length).map { values.item(it) as Element }.associateBy { it.getAttribute("Name").ifEmpty { it.getAttribute("Key") } }
        assertEquals("", byKey.getValue("URL Protocol").getAttribute("Value"))
        assertEquals("\"[INSTALLDIR]Example.exe\" \"%1\"", byKey.getValue("shell\\open\\command").getAttribute("Value"))

        val refs = document.getElementsByTagName("ComponentRef")
        assertTrue((0 until refs.length).any { (refs.item(it) as Element).getAttribute("Id") == "urlScheme" }, "the feature installs it")
    }

    private fun file(path: String): Path = (root / path).also { it.parent.createDirectories(); it.writeText(path) }

    private fun pom(jar: Path, group: String, artifact: String, version: String) =
        jar.resolveSibling("$artifact-$version.pom").writeText("<project><groupId>$group</groupId><artifactId>$artifact</artifactId><version>$version</version></project>")
}

internal fun testSettings(root: Path) = object : NativePackageSettings {
    override val name = "Example"
    override val id = "com.example.app"
    override val urlScheme = "example"
    override val version = "1.2.3"
    override val description = "An example app"
    override val homepage = "https://example.com"
    override val vendor = "Example Vendor"
    override val linuxIcon = root / "example.png"
    override val windowsIcon = root / "example.ico"
    override val windowsInstallerTheme = (root / "installer").createDirectories() / "theme.xml"
    override val imageOptions = listOf("-Ob")
    override val windowsImageOptions = listOf("--windows-only")
    override val pgoProfile = root / "example.iprof.gz"
    override val debDependencies = listOf("libfontconfig1", "libgl1")
    override val noticeIndex = root / "index.tsv"
    override val linkHelper = root / "link"
    override val previewFile = root / "preview-build.properties"
}
