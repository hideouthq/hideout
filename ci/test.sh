#!/bin/sh
# Usage: ci/test.sh [--all]
set -eu
repo=$(cd "$(dirname "$0")/.." && pwd)
cd "$repo"
passed=$repo/build/ci-cache/passed
mkdir -p "$passed"
find "$passed" -type f -mtime +30 -delete
all=no
[ "${1:-}" != --all ] || all=yes
logs=$(mktemp -d)
trap 'rm -rf "$logs"' EXIT

inputs() {
    git ls-files -z --cached --others --exclude-standard -- "$@" ci/test.sh ':!.cache' ':!.skiko' ':!.steam-sdk-artifacts' \
        > "$logs/files" || exit 1
    xargs -0 -r sh -c 'for f; do [ ! -f "$f" ] || printf "%s\0" "$f"; done' sh < "$logs/files" > "$logs/found" || exit 1
    sort -zu "$logs/found" > "$logs/existing" || exit 1
    [ -s "$logs/existing" ] || { echo "no input files for $*" >&2; exit 1; }
    xargs -0 sha256sum -- < "$logs/existing" > "$logs/hashes" || exit 1
    xargs -0 stat -c '%a %n' -- < "$logs/existing" >> "$logs/hashes" || exit 1
    sha256sum "$logs/hashes" | cut -c1-32
}

report() {
    if [ "$2" = passed ]; then
        echo "::group::$1"
        cat "$logs/$1.log"
        echo "::endgroup::"
    else
        cat "$logs/$1.log"
    fi
    cat "$logs/$1.result"
}

part() {
    name=$1
    shift
    marker=$passed/$name-$key
    if [ $all = no ] && [ -e "$marker" ]; then
        echo "$name: skipped, a run with the same inputs passed" | tee "$logs/$name.result"
        return 0
    fi
    echo "$name: started"
    began=$(date +%s)
    if "$@" > "$logs/$name.log" 2>&1; then
        touch "$marker"
        echo "$name: passed in $(( $(date +%s) - began )) s" > "$logs/$name.result"
        (flock 9; report "$name" passed) 9> "$logs/print.lock"
    else
        echo "$name: FAILED after $(( $(date +%s) - began )) s" > "$logs/$name.result"
        (flock 9; report "$name" failed) 9> "$logs/print.lock"
        return 1
    fi
}

python_tests() {
    podman run --rm --network=none --userns=keep-id --user "$(id -u):$(id -g)" -v "$repo:$repo" -w "$repo" \
        docker.io/library/python@sha256:ac25e5afbfdb4a94ff99cc24b0345719210133144481480bcba6085efa36a341 \
        python3 -B -m unittest discover -s "$1" -p 'test_*.py'
}

packaging/linux/container.sh true

pids=
key=$(inputs . ':!docs' ':!*.md' ':!.github' ':!release-notes' ':!tools')
part kotlin packaging/linux/build.sh test & pids="$pids kotlin:$!"
key=$(inputs tools/link packaging/linux/Containerfile packaging/linux/container.sh)
part link packaging/linux/container.sh tools/link/test.sh & pids="$pids link:$!"
key=$(inputs tools/release release-notes app/desktopApp/module.yaml)
part release python_tests tools/release & pids="$pids release:$!"

failed=no
for entry in $pids; do
    name=${entry%%:*}
    if ! wait "${entry#*:}" || [ ! -s "$logs/$name.result" ]; then
        failed=yes
        [ -s "$logs/$name.result" ] || echo "$name: FAILED, it ended without a result"
    fi
done
echo "Summary:"
cat "$logs"/*.result
[ $failed = no ]
