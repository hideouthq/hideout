#!/bin/sh
# Usage: packaging/linux/container.sh command [arguments]
set -eu
repo=$(cd "$(dirname "$0")/../.." && pwd)
common=$(git -C "$repo" rev-parse --path-format=absolute --git-common-dir)
image=localhost/hideout-native-build:$(sha256sum "$repo/packaging/linux/Containerfile" | cut -c1-12)
podman image exists "$image" || podman build -t "$image" "$repo/packaging/linux"
mkdir -p "$HOME/.cache/JetBrains"
# Caps the build so it cannot take the desktop session down. A CI runner's rootless podman has no memory controller
# delegated to it, so the limit there would fail the run.
limit="--memory=12g --memory-swap=12g"
[ -z "${CI:-}" ] || limit=
exec podman run --rm $limit --userns=keep-id --user "$(id -u):$(id -g)" -e HOME="$HOME" -w "$repo" \
    -e GOMODCACHE="$HOME/.cache/JetBrains/go/mod" -e GOCACHE="$HOME/.cache/JetBrains/go/build" \
    -e HIDEOUT_BUILD_PREVIEW_URL -e HIDEOUT_BUILD_MASTER -e HIDEOUT_BUILD_POSTHOG_KEY \
    -v "$repo:$repo" -v "$common:$common:ro" -v "$HOME/.cache/JetBrains:$HOME/.cache/JetBrains" \
    "$image" "$@"
