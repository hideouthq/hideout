package gg.hideouthq.nativepackage

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.PathWalkOption
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.pathString
import kotlin.io.path.relativeTo
import kotlin.io.path.walk
import kotlin.io.path.writeText

internal fun msi(settings: NativePackageSettings, image: Path, work: Path, packages: Path) {
    work.createDirectories()
    val source = work / "${settings.name}.wxs"
    source.writeText(wixSource(settings, image, writePackageFormat(work, "msi")))
    val objects = work / "${settings.name}.wixobj"
    runTool(listOf("candle.exe", "-nologo", "-arch", "x64", "-out", objects.pathString, source.pathString))
    // ICE validation needs the Windows Installer service, which per-user builds and build machines may not have.
    runTool(listOf("light.exe", "-nologo", "-sval", "-out", (packages / "${settings.name}-${settings.version}.msi").pathString, objects.pathString))
}

/** Windows Installer replaces an older install with the same upgrade code, so it is derived from the id, which never changes. */
internal fun upgradeUuid(id: String): UUID = UUID.nameUUIDFromBytes(id.toByteArray())

/**
 * The WiX source: one component per folder of [image], each keyed by a value under the app's HKCU key, as per-user
 * components must be, and the shortcuts and the URL scheme in components of their own.
 */
@OptIn(ExperimentalPathApi::class)
internal fun wixSource(settings: NativePackageSettings, image: Path, packageFormat: Path): String {
    val folders = (listOf(image) + image.walk(PathWalkOption.INCLUDE_DIRECTORIES).filter { it.isDirectory() && it != image }).sortedBy { it.pathString }
    val key = "Software\\${settings.vendor}\\${settings.name}"
    fun id(folder: Path) = if (folder == image) "INSTALLDIR" else "dir_" + folder.relativeTo(image).pathString.replace(Regex("[^A-Za-z0-9]"), "_")
    fun component(folder: Path, index: Int): String {
        val files = Files.list(folder).use { it.toList() }.filter { !it.isDirectory() }.sortedBy { it.name }
        return """
            <DirectoryRef Id="${id(folder)}">
              <Component Id="files$index" Guid="${UUID.nameUUIDFromBytes("${settings.id}/${folder.relativeTo(image)}".toByteArray())}">
                <RegistryValue Root="HKCU" Key="$key" Name="files$index" Type="integer" Value="1" KeyPath="yes"/>
${(if (folder == image) files + listOf(packageFormat) else files).joinToString("\n") { "                <File Id=\"f${index}_${it.name.replace(Regex("[^A-Za-z0-9]"), "_")}\" Source=\"${it.pathString}\"/>" }}
                ${if (folder == image) "" else "<RemoveFolder Id=\"remove$index\" On=\"uninstall\"/>"}
              </Component>
            </DirectoryRef>"""
    }
    fun tree(folder: Path): String = Files.list(folder).use { it.toList() }.filter { it.isDirectory() }.sortedBy { it.name }
        .joinToString("") { "<Directory Id=\"${id(it)}\" Name=\"${it.name}\">${tree(it)}</Directory>" }
    return """<?xml version="1.0" encoding="UTF-8"?>
<Wix xmlns="http://schemas.microsoft.com/wix/2006/wi">
  <Product Id="*" Name="${settings.name}" Language="1033" Version="${settings.version}" Manufacturer="${settings.vendor}" UpgradeCode="${upgradeUuid(settings.id)}">
    <Package InstallerVersion="500" Compressed="yes" InstallScope="perUser" InstallPrivileges="limited" Platform="x64" Description="${settings.description}"/>
    <MajorUpgrade Schedule="afterInstallInitialize" AllowSameVersionUpgrades="yes" DowngradeErrorMessage="A newer ${settings.name} is already installed."/>
    <MediaTemplate EmbedCab="yes" CompressionLevel="high"/>
    <Icon Id="app.ico" SourceFile="${settings.windowsIcon.toAbsolutePath()}"/>
    <Property Id="ARPPRODUCTICON" Value="app.ico"/>
    <Property Id="ARPURLINFOABOUT" Value="${settings.homepage}"/>
    <Directory Id="TARGETDIR" Name="SourceDir">
      <Directory Id="LocalAppDataFolder">
        <Directory Id="INSTALLDIR" Name="${settings.name}">${tree(image)}</Directory>
      </Directory>
      <Directory Id="ProgramMenuFolder">
        <Directory Id="MenuFolder" Name="${settings.name}"/>
      </Directory>
      <Directory Id="DesktopFolder"/>
    </Directory>
${folders.mapIndexed { index, folder -> component(folder, index) }.joinToString("\n")}
    <DirectoryRef Id="MenuFolder">
      <Component Id="shortcuts" Guid="${UUID.nameUUIDFromBytes("${settings.id}/shortcuts".toByteArray())}">
        <Shortcut Id="menu" Name="${settings.name}" Description="${settings.description}" Target="[INSTALLDIR]${settings.name}.exe" WorkingDirectory="INSTALLDIR" Icon="app.ico"/>
        <Shortcut Id="desktop" Directory="DesktopFolder" Name="${settings.name}" Description="${settings.description}" Target="[INSTALLDIR]${settings.name}.exe" WorkingDirectory="INSTALLDIR" Icon="app.ico"/>
        <RemoveFolder Id="removeMenu" On="uninstall"/>
        <RegistryValue Root="HKCU" Key="$key" Name="shortcuts" Type="integer" Value="1" KeyPath="yes"/>
      </Component>
    </DirectoryRef>
    <DirectoryRef Id="INSTALLDIR">
      <Component Id="urlScheme" Guid="${UUID.nameUUIDFromBytes("${settings.id}/urlScheme".toByteArray())}">
        <RegistryKey Root="HKCU" Key="Software\Classes\${settings.urlScheme}" ForceDeleteOnUninstall="yes">
          <RegistryValue Type="string" Value="URL:${settings.name}" KeyPath="yes"/>
          <RegistryValue Type="string" Name="URL Protocol" Value=""/>
          <RegistryValue Type="string" Key="DefaultIcon" Value="[INSTALLDIR]${settings.name}.exe,0"/>
          <RegistryValue Type="string" Key="shell\open\command" Value="&quot;[INSTALLDIR]${settings.name}.exe&quot; &quot;%1&quot;"/>
        </RegistryKey>
      </Component>
    </DirectoryRef>
    <Feature Id="app" Level="1">
${(folders.indices.map { "files$it" } + "shortcuts" + "urlScheme").joinToString("\n") { "      <ComponentRef Id=\"$it\"/>" }}
    </Feature>
  </Product>
</Wix>
"""
}
