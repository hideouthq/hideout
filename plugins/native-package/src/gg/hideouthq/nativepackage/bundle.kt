package gg.hideouthq.nativepackage

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.pathString
import kotlin.io.path.writeText

internal fun bundle(settings: NativePackageSettings, msi: Path, work: Path, packages: Path): Path {
    work.createDirectories()
    val source = work / "${settings.name}-Setup.wxs"
    source.writeText(bundleSource(settings, msi))
    val installer = packages / "${settings.name}-${settings.version}-Setup.exe"
    runTool(listOf("wix.exe", "build", "-arch", "x64", "-ext", "WixToolset.BootstrapperApplications.wixext/5.0.2", "-out", installer.pathString, source.pathString))
    return installer
}

internal fun bundleSource(settings: NativePackageSettings, msi: Path): String {
    fun xml(value: Any) = value.toString().replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
    val theme = settings.windowsInstallerTheme.toAbsolutePath()
    val payloads = theme.parent.listDirectoryEntries().filter { it.isRegularFile() && it != theme }.sorted()
        .joinToString("\n") { "      <Payload SourceFile=\"${xml(it)}\"/>" }
    return """<?xml version="1.0" encoding="UTF-8"?>
<Wix xmlns="http://wixtoolset.org/schemas/v4/wxs" xmlns:bal="http://wixtoolset.org/schemas/v4/wxs/bal">
  <Bundle Name="${xml(settings.name)}" Version="${xml(settings.version)}" Manufacturer="${xml(settings.vendor)}" UpgradeCode="${upgradeUuid("${settings.id}/setup")}" IconSourceFile="${xml(settings.windowsIcon.toAbsolutePath())}" AboutUrl="${xml(settings.homepage)}">
    <BootstrapperApplication>
      <bal:WixStandardBootstrapperApplication Theme="hyperlinkLicense" LicenseUrl="" ThemeFile="${xml(theme)}" SuppressOptionsUI="yes" LaunchTarget="[LocalAppDataFolder]${xml(settings.name)}\${xml(settings.name)}.exe" LaunchWorkingFolder="[LocalAppDataFolder]${xml(settings.name)}"/>
$payloads
    </BootstrapperApplication>
    <Chain>
      <MsiPackage SourceFile="${xml(msi.toAbsolutePath())}" Visible="yes" Permanent="yes" Compressed="yes" Cache="keep"/>
    </Chain>
  </Bundle>
</Wix>
"""
}
