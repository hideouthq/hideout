#!/usr/bin/env bash
set -euo pipefail
bridge_install=$(dirname -- "$(readlink -f -- "${BASH_SOURCE[0]}")")
if [[ ${1-} == --prepare ]]; then
    [[ $# == 2 ]] || exit 2
    bridge_source=${STEAM_RUNTIME_STEAMRT:-"$2/steamrt64/pv-runtime/steam-runtime-steamrt"}
    bridge_source=$(readlink -f -- "$bridge_source")
    case $bridge_source in
        "$bridge_install"/runtime-*) IFS= read -r bridge_source < "$bridge_source/source";;
    esac
    bridge_libexec=pressure-vessel/libexec/steam-runtime-tools-0
    [[ -x "$bridge_source/$bridge_libexec/srt-client-container" && -x "$bridge_source/run" ]] || exit 1
    bridge_key=$(printf '%s' "$bridge_source" | sha256sum | cut -d ' ' -f 1)
    bridge_view="$bridge_install/runtime-$bridge_key"
    umask 077
    mkdir -p -- "$bridge_view/$bridge_libexec"
    for bridge_dir in '' pressure-vessel pressure-vessel/libexec "$bridge_libexec"; do
        for bridge_item in "$bridge_source/${bridge_dir:+$bridge_dir/}"*; do
            [[ -e "$bridge_item" ]] || continue
            bridge_name=${bridge_item##*/}
            case "$bridge_dir/$bridge_name" in /pressure-vessel|pressure-vessel/libexec|pressure-vessel/libexec/steam-runtime-tools-0|"$bridge_libexec/srt-client-container") continue;; esac
            ln -sfn -- "$bridge_item" "$bridge_view/${bridge_dir:+$bridge_dir/}$bridge_name"
        done
    done
    printf '%s\n' "$bridge_source" > "$bridge_view/.source-$$"
    mv -f -- "$bridge_view/.source-$$" "$bridge_view/source"
    ln -sfn -- "$bridge_install/client-container.sh" "$bridge_view/$bridge_libexec/srt-client-container"
    printf '%s\n' "$bridge_view"
    exit
fi

bridge_view=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd)
IFS= read -r bridge_source < "$bridge_view/source"
bridge_command="$bridge_source/pressure-vessel/libexec/steam-runtime-tools-0/srt-client-container"
bridge_options=(--pv-entry-point "$bridge_source/run")
while [[ $# -gt 0 && $1 != -- ]]; do bridge_options+=("$1"); shift; done
[[ ${1-} == -- ]] || exec "$bridge_command" "${bridge_options[@]}"
shift
IFS= read -r bridge_root < "$bridge_install/steam-root"
if [[ ${1-} == "$bridge_root/steamrt64/steam" && ! -f "$bridge_install/disabled" ]]; then
    exec "$bridge_command" "${bridge_options[@]}" -- "$bridge_install/final-launch.sh" "$@"
fi
exec "$bridge_command" "${bridge_options[@]}" -- "$@"
