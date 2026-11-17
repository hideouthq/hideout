#!/bin/sh
set -eu

here=$(cd "$(dirname "$0")" && pwd)
checkout=$(git -C "$here" rev-parse --show-toplevel)
mkdir -p "${1:?usage: wine-probe.sh WORK}"
work=$(cd "$1" && pwd)
output="$work/wine-probe-output.txt"

jdk_zip=OpenJDK25U-jdk_x64_windows_hotspot_25.0.5_8.zip
jdk_url="https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.5%2B8/$jdk_zip"
jdk_sha256=636bdc13dc649ddc7d5921c5c7d23cca6e44fd19d662b45cc9c02135a6c96c42

mkdir -p "$work/cache"
if [ ! -f "$work/cache/$jdk_zip" ]; then
    curl -fL --retry 3 -o "$work/cache/$jdk_zip.partial" "$jdk_url"
    mv "$work/cache/$jdk_zip.partial" "$work/cache/$jdk_zip"
fi
echo "$jdk_sha256  $work/cache/$jdk_zip" | sha256sum --check --quiet
rm -rf "$work/jdk"
mkdir "$work/jdk"
unzip -q "$work/cache/$jdk_zip" -d "$work/jdk"
java_exe=$(echo "$work"/jdk/jdk-*/bin/java.exe)

(cd "$checkout" && ./kotlin build -m desktopTools)
printf "%s\n" -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -XX:ActiveProcessorCount=4 > "$work/probe.args"
python3 -I "$here/classpath.py" "$checkout/build" >> "$work/probe.args"
echo "gg.hideouthq.tools.WindowsProbeKt" >> "$work/probe.args"

export WINEPREFIX="$work/prefix" WINEDEBUG=-all WINEDLLOVERRIDES="winemenubuilder.exe=d;mscoree=d;mshtml=d"
export HOME="$work/home" XDG_CONFIG_HOME="$work/home/.config" XDG_DATA_HOME="$work/home/.local/share" XDG_CACHE_HOME="$work/home/.cache"
unset DISPLAY WAYLAND_DISPLAY
# Wine keeps each prefix's server socket in /tmp/.wine-<uid>/server-<device>-<inode>; only this prefix's is removed.
stop_wine() {
    [ -d "$WINEPREFIX" ] || return 0
    /usr/bin/wineserver -k 2>/dev/null || true
    /usr/bin/wineserver -w 2>/dev/null || true
    rm -rf "/tmp/.wine-$(id -u)/server-$(stat -c %D "$WINEPREFIX")-$(printf %x "$(stat -c %i "$WINEPREFIX")")"
}
stop_wine
rm -rf "$WINEPREFIX" "$HOME"
mkdir -p "$HOME"
/usr/bin/wine wineboot -i
/usr/bin/wineserver -w
/usr/bin/wine reg add 'HKCU\Software\Wine\Drivers' /v Graphics /d null /f
/usr/bin/wine reg add 'HKCU\Software\Valve\Steam' /v SteamPath /d 'C:/Games/Valve Steam' /f
/usr/bin/wine reg add 'HKLM\SOFTWARE\WOW6432Node\Valve\Steam' /v InstallPath /d 'C:\Games\Machine Steam' /f
/usr/bin/wine reg add 'HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\User Shell Folders' /v 'My Pictures' /t REG_EXPAND_SZ /d 'C:\Shots' /f
drive_c="$WINEPREFIX/drive_c"
mkdir -p "$drive_c/Shots" "$drive_c/Deadlock"
for steam in "Valve Steam" "Machine Steam"; do
    mkdir -p "$drive_c/Games/$steam/steamapps/common/Deadlock/game/citadel"
    printf '"AppState"\n{\n\t"appid"\t\t"1422450"\n\t"installdir"\t\t"Deadlock"\n}\n' > "$drive_c/Games/$steam/steamapps/appmanifest_1422450.acf"
    printf 'GameInfo {}\n' > "$drive_c/Games/$steam/steamapps/common/Deadlock/game/citadel/gameinfo.gi"
done
cp /usr/lib/wine/x86_64-windows/notepad.exe "$drive_c/Deadlock/deadlock.exe"
args_file=$(/usr/bin/wine winepath -w "$work/probe.args" 2>/dev/null | tr -d '\r')

probe() {
    status=0
    /usr/bin/wine "$java_exe" "@$args_file" "$1" > "$work/probe.part" 2>&1 || status=$?
    sed 's/^/[Wine, not real Windows] /' "$work/probe.part" | tee -a "$output"
    rm -f "$work/probe.part"
    echo "[Wine, not real Windows] probe $1 exited with $status (the number of failed checks)" | tee -a "$output"
    failures=$((failures + status))
}

{
    echo "Hideout Windows probe, $(date -u +%Y-%m-%dT%H:%M:%SZ), $(git -C "$checkout" rev-parse --short HEAD) on $(git -C "$checkout" branch --show-current)"
    echo "$(/usr/bin/wine --version), $(/usr/bin/wine "$java_exe" -XX:ActiveProcessorCount=4 -version 2>&1 | tr -d '\r' | head -1), $(/usr/bin/wine cmd /c ver 2>/dev/null | tr -d '\r' | grep -v '^$')"
} | tee "$output"

failures=0
/usr/bin/wine 'C:\Deadlock\deadlock.exe' > /dev/null 2>&1 &
sleep 3
probe running
# taskkill reaches only this prefix's processes, never a game Proton runs under its own wineserver.
/usr/bin/wine taskkill /f /im deadlock.exe > /dev/null 2>&1 || true
wait
probe stopped

stop_wine
echo "failed checks: $failures" | tee -a "$output"
exit "$failures"
