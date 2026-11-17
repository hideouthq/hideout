#!/usr/bin/env bash
set -euo pipefail
[[ $# -gt 0 ]] || exit 2
bridge_executable=$1
shift
bridge_install=$(dirname -- "$(readlink -f -- "${BASH_SOURCE[0]}")")
plain() {
    unset HIDEOUT_STEAM_BRIDGE_ENDPOINT HIDEOUT_STEAM_BRIDGE_LIBRARY HIDEOUT_STEAM_BRIDGE_PROBE
    exec "$bridge_executable" "$@"
}
bridge_manifest=$(sha256sum -- "$bridge_install/SHA256SUMS" 2>/dev/null | cut -d ' ' -f 1) || bridge_manifest=
if [[ -f "$bridge_install/disabled" || ${bridge_install##*/} != "$bridge_manifest" ]] || ! (cd -- "$bridge_install" && sha256sum --check --status SHA256SUMS); then plain "$@"; fi
if [[ ! -f "$bridge_install/steam-root" || ! -f "$bridge_install/architecture" ]]; then plain "$@"; fi
IFS= read -r bridge_root < "$bridge_install/steam-root" || plain "$@"
IFS= read -r bridge_architecture < "$bridge_install/architecture" || plain "$@"
case $bridge_architecture in ubuntu12_32|steamrt64) ;; *) plain "$@";; esac
bridge_target=$(readlink -f -- "$bridge_executable") || plain "$@"
if [[ "$bridge_target" != "$bridge_root/$bridge_architecture/steam" ||
      ! -f ${HIDEOUT_STEAM_BRIDGE_LIBRARY-} || ! -x ${HIDEOUT_STEAM_BRIDGE_PROBE-} ||
      -z ${HIDEOUT_STEAM_BRIDGE_ENDPOINT-} ]]; then plain "$@"; fi
bridge_report=$(
    "$HIDEOUT_STEAM_BRIDGE_PROBE" "$bridge_target" "$bridge_root/$bridge_architecture/steamclient.so" "$HIDEOUT_STEAM_BRIDGE_LIBRARY" --startup "$HIDEOUT_STEAM_BRIDGE_ENDPOINT" "$$"
) || { printf 'Hideout Steam bridge: %s\n' "$bridge_report" >&2; plain "$@"; }
case $bridge_report in
    '{"version":1,"compatible":true,'*) ;;
    *) plain "$@" ;;
esac
# LD_PRELOAD splits on whitespace and colons. Create its alias in the final
# process namespace, including inside Flatpak/Snap and the client container.
bridge_runtime="${XDG_RUNTIME_DIR:-/tmp}/hideout-steam-$(id -u)"
if [[ $bridge_runtime == *[[:space:]:]* ]]; then bridge_runtime="/tmp/hideout-steam-$(id -u)"; fi
umask 077
if ! mkdir -m 700 -- "$bridge_runtime" 2>/dev/null; then
    if [[ ! -d $bridge_runtime || -L $bridge_runtime || ! -O $bridge_runtime || $(stat -c %a -- "$bridge_runtime") != 700 ]]; then plain "$@"; fi
fi
ln -sfn -- "$HIDEOUT_STEAM_BRIDGE_LIBRARY" "$bridge_runtime/loaded-bridge.so" || plain "$@"
export HIDEOUT_STEAM_BRIDGE_LIBRARY="$bridge_runtime/loaded-bridge.so"
export LD_PRELOAD="${HIDEOUT_STEAM_BRIDGE_LIBRARY}${LD_PRELOAD:+:$LD_PRELOAD}"
exec "$bridge_executable" "$@"
