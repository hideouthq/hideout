$ErrorActionPreference = 'Stop'
if ($env:GITHUB_ACTIONS -ne 'true') { throw 'Run this installer check on a disposable GitHub Windows runner.' }

$packages = Join-Path $PWD 'build/tasks/_desktopApp_packages@native-package/package'
$work = Join-Path $env:RUNNER_TEMP 'hideout-installer-check'
$installed = Join-Path $env:LOCALAPPDATA 'Hideout'
$protocol = 'HKCU:\Software\Classes\hideout'
$menu = Join-Path ([Environment]::GetFolderPath('Programs')) 'Hideout\Hideout.lnk'
$desktop = Join-Path ([Environment]::GetFolderPath('Desktop')) 'Hideout.lnk'
$registry = @(
    'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*',
    'HKCU:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*',
    'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*',
    'HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*'
)
function Installed-Hideout {
    @(Get-ItemProperty $registry -ErrorAction SilentlyContinue | Where-Object DisplayName -eq 'Hideout')
}
function Run-Installer($file, $arguments, $expected = @(0, 3010)) {
    $process = Start-Process $file -ArgumentList $arguments -PassThru
    if (-not $process.WaitForExit(180000)) { $process.Kill($true); throw "Installer timed out: $file" }
    if ($process.ExitCode -notin $expected) { throw "Installer failed with $($process.ExitCode): $file $arguments" }
}
function Check-Installed($version) {
    $entries = @(Installed-Hideout)
    if ($entries.Count -ne 1 -or $entries[0].DisplayVersion -ne $version -or $entries[0].WindowsInstaller -ne 1) {
        throw "Expected one MSI Apps entry for Hideout $version, found: $($entries | ConvertTo-Json -Compress)"
    }
    foreach ($path in @("$installed\Hideout.exe", "$installed\package-format", $menu, $desktop, $protocol)) {
        if (-not (Test-Path $path)) { throw "Installation is missing $path" }
    }
    if ((Get-Content "$installed\package-format" -Raw) -ne 'msi') { throw 'EXE installation changed the update package format.' }
    return $entries[0].PSChildName
}
function Check-Uninstalled {
    if (@(Installed-Hideout).Count -ne 0) { throw 'Uninstall left an Apps entry.' }
    foreach ($path in @("$installed\Hideout.exe", $menu, $desktop, $protocol)) {
        if (Test-Path $path) { throw "Uninstall left $path" }
    }
}

if (@(Installed-Hideout).Count -or (Test-Path $installed)) { throw 'The installer test requires a clean runner.' }
New-Item -ItemType Directory -Force $work | Out-Null
$setup = @(Get-ChildItem "$packages/*-Setup.exe")
$msi = @(Get-ChildItem "$packages/*.msi")
if ($setup.Count -ne 1 -or $msi.Count -ne 1) { throw 'Expected exactly one EXE and one MSI.' }
$download = Join-Path $work 'Downloaded Setup.exe'
Copy-Item $setup[0].FullName $download
[xml]$source = Get-Content (Join-Path $packages '..\msi\Hideout.wxs') -Raw
$version = $source.Wix.Product.Version
$next = [version]$version
$nextVersion = "$($next.Major).$($next.Minor).$($next.Build + 1)"
$source.Wix.Product.Version = $nextVersion
$upgradeSource = Join-Path $work 'upgrade.wxs'
$source.Save($upgradeSource)
& candle.exe -nologo -arch x64 -out "$work\upgrade.wixobj" $upgradeSource
if ($LASTEXITCODE) { throw 'Could not compile the MSI upgrade fixture.' }
& light.exe -nologo -sval -out "$work\upgrade.msi" "$work\upgrade.wixobj"
if ($LASTEXITCODE) { throw 'Could not link the MSI upgrade fixture.' }

try {
    Run-Installer $download "/quiet /norestart /log `"$work\install.log`""
    $product = Check-Installed $version
    Remove-Item $download
    Remove-Item "$installed\package-format"
    Run-Installer msiexec.exe "/fomus $product /qn /norestart /l*v `"$work\repair.log`""
    $null = Check-Installed $version
    Write-Output 'EXE installation has one MSI Apps entry and repairs without the downloaded EXE.'

    Run-Installer msiexec.exe "/i `"$work\upgrade.msi`" /qn /norestart /l*v `"$work\upgrade.log`""
    $updatedProduct = Check-Installed $nextVersion
    if ($updatedProduct -eq $product) { throw 'The fixture did not perform an MSI major upgrade.' }
    Remove-Item "$installed\package-format"
    Run-Installer msiexec.exe "/fomus $updatedProduct /qn /norestart /l*v `"$work\upgrade-repair.log`""
    $null = Check-Installed $nextVersion
    Write-Output 'Direct MSI upgrade replaced the product, kept one current Apps entry and repaired successfully.'

    Run-Installer $setup[0].FullName "/quiet /norestart /log `"$work\downgrade.log`""
    $null = Check-Installed $nextVersion
    Run-Installer msiexec.exe "/x $updatedProduct /qn /norestart /l*v `"$work\uninstall.log`""
    Check-Uninstalled

    Run-Installer msiexec.exe "/i `"$($msi[0].FullName)`" /qn /norestart /l*v `"$work\msi-install.log`""
    Run-Installer $setup[0].FullName "/quiet /norestart /log `"$work\reinstall.log`""
    $product = Check-Installed $version
    Run-Installer msiexec.exe "/x $product /qn /norestart /l*v `"$work\reinstall-uninstall.log`""
    Check-Uninstalled
    Write-Output 'Older EXE preserved the newer installation. MSI installation, EXE rerun and uninstall passed.'
} finally {
    foreach ($entry in @(Installed-Hideout | Where-Object WindowsInstaller -eq 1)) {
        Run-Installer msiexec.exe "/x $($entry.PSChildName) /qn /norestart /l*v `"$work\cleanup.log`""
    }
}
