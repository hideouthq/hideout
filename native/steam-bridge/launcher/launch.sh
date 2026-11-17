#!/usr/bin/env bash
set -euo pipefail
[[ ${1-} == -- ]] || exit 2
shift
[[ $# -gt 0 ]] || exit 2
bridge_install=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
plain() {
    unset HIDEOUT_STEAM_BRIDGE_ENDPOINT HIDEOUT_STEAM_BRIDGE_LIBRARY HIDEOUT_STEAM_BRIDGE_PROBE
    exec "$@"
}
if [[ -n ${DEBUGGER-} || ! -f "$bridge_install/libhideout_steam_bridge.so" || ! -f "$bridge_install/endpoint-path" || -f "$bridge_install/disabled" ]]; then
    exec "$@"
fi
bridge_manifest=$(sha256sum -- "$bridge_install/SHA256SUMS" 2>/dev/null | cut -d ' ' -f 1) || bridge_manifest=
if [[ ${bridge_install##*/} != "$bridge_manifest" ]] || ! (cd -- "$bridge_install" && sha256sum --check --status SHA256SUMS); then exec "$@"; fi
for bridge_config in steam-root architecture package; do
    [[ -f "$bridge_install/$bridge_config" ]] || plain "$@"
done
IFS= read -r bridge_steam_root < "$bridge_install/steam-root" || plain "$@"
IFS= read -r bridge_architecture < "$bridge_install/architecture" || plain "$@"
bridge_selected=ubuntu12_32
[[ ! -e "$bridge_steam_root/.steam-enable-steamrt64-client" ]] || bridge_selected=steamrt64
case ${STEAM_FORCE_CLIENT-} in
    "") ;;
    ubuntu12_32|scout|ldlp) bridge_selected=ubuntu12_32;;
    steamrt64|steamrt3c|steamrt) bridge_selected=steamrt64;;
    *) exec "$@";;
esac
[[ $bridge_selected == "$bridge_architecture" ]] || exec "$@"
IFS= read -r HIDEOUT_STEAM_BRIDGE_ENDPOINT < "$bridge_install/endpoint-path" || plain "$@"
export HIDEOUT_STEAM_BRIDGE_ENDPOINT
if [[ $bridge_architecture == steamrt64 ]]; then
    bridge_view=$("$bridge_install/client-container.sh" --prepare "$bridge_steam_root") || plain "$@"
    export STEAM_RUNTIME_STEAMRT="$bridge_view"
    export HIDEOUT_STEAM_BRIDGE_LIBRARY="$bridge_install/libhideout_steam_bridge.so"
    export HIDEOUT_STEAM_BRIDGE_PROBE="$bridge_install/bridge_probe"
    exec "$@"
fi
IFS= read -r bridge_package < "$bridge_install/package"
if [[ $bridge_package == Flatpak || $bridge_package == Snap ]]; then
    # steam.sh enters the selected root before invoking DEBUGGER. The relative
    # name remains whitespace-free even when the package's home contains spaces.
    export DEBUGGER="./.hideout-tracking/${bridge_install##*/}/final-launch.sh"
    export HIDEOUT_STEAM_BRIDGE_LIBRARY="$bridge_install/libhideout_steam_bridge.so"
    export HIDEOUT_STEAM_BRIDGE_PROBE="$bridge_install/bridge_probe"
    exec "$@"
fi
# Steam's DEBUGGER command is word-split, so use a private, whitespace-free alias.
bridge_uid=$(id -u)
bridge_runtime="${XDG_RUNTIME_DIR:-/tmp}/hideout-steam-$bridge_uid"
if [[ "$bridge_runtime" == *[[:space:]:]* ]]; then bridge_runtime="/tmp/hideout-steam-$bridge_uid"; fi
umask 077
if ! mkdir -m 700 -- "$bridge_runtime" 2>/dev/null; then
    if [[ ! -d "$bridge_runtime" || -L "$bridge_runtime" || ! -O "$bridge_runtime" || $(stat -c %a -- "$bridge_runtime") != 700 ]]; then exec "$@"; fi
fi
ln -sfn -- "$bridge_install/final-launch.sh" "$bridge_runtime/final-launch"
ln -sfn -- "$bridge_install/libhideout_steam_bridge.so" "$bridge_runtime/bridge.so"
ln -sfn -- "$bridge_install/bridge_probe" "$bridge_runtime/probe"
export HIDEOUT_STEAM_BRIDGE_LIBRARY="$bridge_runtime/bridge.so"
export HIDEOUT_STEAM_BRIDGE_PROBE="$bridge_runtime/probe"
export DEBUGGER="$bridge_runtime/final-launch"
exec "$@"
