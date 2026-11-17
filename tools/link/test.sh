#!/bin/sh
set -eu
cd "$(dirname "$0")"
go vet ./...
bin=$(mktemp -d)
trap 'rm -rf "$bin"' EXIT
go test -c -o "$bin/link.test" .
"$bin/link.test" -test.list '^Test' > "$bin/listed"
grep '^Test' "$bin/listed" > "$bin/tests" || { cat "$bin/listed"; echo "FAIL hideout/tools/link: it lists no tests"; exit 1; }
# What go test adds and running the binary does not: a test that calls os.Exit(0) fails, and a hung one is stopped with
# every goroutine's stack. A test's output is shown only when it fails, whole, so parallel tests do not interleave.
xargs -P "$(nproc)" -I{} sh -c '
    out=$("$0" -test.run "^$1\$" -test.paniconexit0 -test.timeout 3m 2>&1) || { printf "%s\n--- FAIL: %s\n" "$out" "$1"; exit 1; }
' "$bin/link.test" {} < "$bin/tests" || { echo "FAIL hideout/tools/link"; exit 1; }
echo "ok hideout/tools/link ($(wc -l < "$bin/tests") tests)"
