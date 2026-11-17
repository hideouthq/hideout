#!/bin/sh
# Usage: packaging/linux/build.sh [kotlin arguments]   (default: do nativePackages -m desktopApp)
set -eu
repo=$(cd "$(dirname "$0")/../.." && pwd)
[ -d "$repo/.steam-sdk-artifacts" ] || "$repo/native/steam-bridge/tools/build-runtime.sh" "$repo/build/steam-sdk-build" "$repo/.steam-sdk-artifacts"
[ $# -gt 0 ] || set -- do nativePackages -m desktopApp
exec "$repo/packaging/linux/container.sh" xvfb-run -a -s "-screen 0 1600x1000x24" ./kotlin "$@"
