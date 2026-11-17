package gg.hideouthq.nativepackage

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.io.path.isRegularFile
import kotlin.io.path.pathString
import kotlin.io.path.walk
import kotlin.io.path.writeText

@OptIn(ExperimentalPathApi::class)
internal fun deb(settings: NativePackageSettings, image: Path, mainClass: String, work: Path, packages: Path) {
    val root = work / "root"
    installTree(image, root, settings, mainClass, "deb")
    (root / "DEBIAN").createDirectories()
    (root / "DEBIAN/control").writeText(debControl(settings, installedKiB(root)))
    runTool(listOf("dpkg-deb", "--root-owner-group", "-Zxz", "--build", root.pathString, (packages / "${settings.linuxPackage}_${settings.version}_amd64.deb").pathString))
    work.deleteRecursively()
}

@OptIn(ExperimentalPathApi::class)
internal fun rpm(settings: NativePackageSettings, image: Path, mainClass: String, work: Path, packages: Path) {
    val root = work / "root"
    installTree(image, root, settings, mainClass, "rpm")
    val spec = work / "${settings.linuxPackage}.spec"
    spec.writeText(rpmSpec(settings, root))
    runTool(listOf("rpmbuild", "-bb", "--define", "_topdir ${(work / "rpmbuild").pathString}", "--define", "_rpmdir ${packages.pathString}",
        "--define", "_build_name_fmt %%{NAME}-%%{VERSION}-%%{RELEASE}.%%{ARCH}.rpm", spec.pathString))
    work.deleteRecursively()
}

internal fun installTree(image: Path, root: Path, settings: NativePackageSettings, mainClass: String, format: String) {
    val app = "/opt/${settings.linuxPackage}"
    copyTree(image, (root / app.drop(1)).also { it.parent.createDirectories() })
    writePackageFormat(root / app.drop(1), format)
    val applications = (root / "usr/share/applications").createDirectories()
    (applications / "${settings.id}.desktop").writeText(desktopEntry(settings, mainClass, exec = "$app/${settings.name}", icon = settings.id))
    settings.linuxIcon.copyTo((root / "usr/share/icons/hicolor/512x512/apps").createDirectories() / "${settings.id}.png")
}

internal fun debControl(settings: NativePackageSettings, installedKiB: Long): String = """
    Package: ${settings.linuxPackage}
    Version: ${settings.version}
    Architecture: amd64
    Maintainer: ${settings.vendor}
    Installed-Size: $installedKiB
    Depends: ${settings.debDependencies.joinToString(", ")}
    Section: games
    Priority: optional
    Homepage: ${settings.homepage}
    Description: ${settings.description}
""".trimIndent() + "\n"

/**
 * The spec installs [root] as it is. rpmbuild adds a requirement for each library the image links, except the ones the
 * package carries itself. It provides nothing, so those never satisfy another package, and it neither strips nor rewrites them.
 */
internal fun rpmSpec(settings: NativePackageSettings, root: Path): String = """
        Name: ${settings.linuxPackage}
        Version: ${settings.version}
        Release: 1
        Summary: ${settings.description}
        License: Unknown
        URL: ${settings.homepage}
        Vendor: ${settings.vendor}
        AutoProv: no
        %global __requires_exclude ^(${bundledLibraries(root).joinToString("|") { it.replace(".", "\\\\.") }})\\(
        %define debug_package %{nil}
        %define __os_install_post %{nil}
        %define _build_id_links none

        %description
        ${settings.description}

        %install
        cp -a ${root.pathString}/. %{buildroot}/

        %files
        /opt/${settings.linuxPackage}
        /usr/share/applications/${settings.id}.desktop
        /usr/share/icons/hicolor/512x512/apps/${settings.id}.png
    """.trimIndent() + "\n"

@OptIn(ExperimentalPathApi::class)
private fun bundledLibraries(root: Path): List<String> = root.walk().map { it.fileName.toString() }.filter { it.endsWith(".so") }.sorted().toList()

@OptIn(ExperimentalPathApi::class)
private fun installedKiB(root: Path): Long = root.walk().filter { it.isRegularFile() }.sumOf { Files.size(it) } / 1024 + 1
