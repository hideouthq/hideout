#!/usr/bin/env bash
set -euo pipefail
[[ $# == 2 ]] || { echo 'usage: build-runtime.sh BUILD_DIRECTORY ARTIFACT_DIRECTORY' >&2; exit 2; }
bridge_source=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
mkdir -p -- "$1" "$2"
bridge_build=$(realpath -- "$1")
bridge_artifacts=$(realpath -- "$2")
bridge_image=$(cat "$bridge_source/tools/sdk-image.txt")
[[ $bridge_image =~ @sha256:[0-9a-f]{64}$ ]] || exit 2
podman run --rm --network=none --userns=keep-id --user "$(id -u):$(id -g)" \
    --mount "type=bind,source=$bridge_source,destination=/source,ro" \
    --mount "type=bind,source=$bridge_build,destination=/build" \
    "$bridge_image" /bin/bash -euc '
    for bits in 32 64; do
        cmake -S /source -B /build/$bits -DCMAKE_C_COMPILER=/usr/bin/gcc-9 -DCMAKE_CXX_COMPILER=/usr/bin/g++-9 \
            -DCMAKE_C_FLAGS=-m$bits -DCMAKE_CXX_FLAGS=-m$bits -DCMAKE_BUILD_TYPE=Release
        cmake --build /build/$bits --target hideout_steam_bridge bridge_probe -- -j4
        /build/$bits/bridge_probe --check-load /build/$bits/libhideout_steam_bridge.so
    done'
python3 "$bridge_source/tools/verify-artifacts.py" "$bridge_build" "$bridge_artifacts" "$bridge_source" "$bridge_image"
