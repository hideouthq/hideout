#!/bin/sh
# Usage: check-link.sh <evidence-dir> <start-command or -> <code>
set -u
evidence=$1
start=$2
code=$3
# A process started through an AppImage's AppRun link goes by that name.
names='Hideout|AppRun'
run=$(mktemp -d)
mkdir -p "$run/home/.local/share/hideout" "$run/home/.config" "$run/tmp"
Xvfb :78 -screen 0 1600x1000x24 -nolisten tcp > "$evidence/xvfb.log" 2>&1 &
xvfb=$!
# A native image takes its home from the account, not $HOME, so the XDG folders are what keep the app in this one.
export DISPLAY=:78 HOME="$run/home" TMPDIR="$run/tmp" XDG_DATA_HOME="$run/home/.local/share" XDG_CONFIG_HOME="$run/home/.config" XDG_CACHE_HOME="$run/home/.cache"
echo '{"setupDone":true}' > "$XDG_DATA_HOME/hideout/preferences.json"
trap 'pkill -x "$names"; kill "$xvfb" 2>/dev/null; rm -rf "$run"' EXIT
sleep 2

window() {
  waited=0
  while [ "$waited" -lt 120 ]; do
    [ -n "$(xdotool search --name '^Hideout$' 2>/dev/null | head -1)" ] && return 0
    sleep 1
    waited=$((waited + 1))
  done
  return 1
}

fail() {
  echo "FAIL: $1"
  exit 1
}

click() {
  name=$1
  shift
  # A plain xdg-open waits on the program it starts, so with Hideout closed it stays for as long as Hideout runs.
  "$@" >> "$evidence/opener.log" 2>&1 &
  opener=$!
  window || fail "$name: no Hideout window"
  sleep 8
  xwd -root -silent -out "$evidence/$name.xwd"
  if [ "$name" != cold ]; then
    kill -0 "$opener" 2>/dev/null && fail "$name: the second launch did not end"
    wait "$opener" || fail "$name: $* failed"
  fi
  echo "$name: $(pgrep -c -x "$names") Hideout running"
  [ "$(pgrep -c -x "$names")" = 1 ] || fail "$name: not exactly one Hideout"
}

if [ "$start" != - ]; then
  $start > "$evidence/first-run.log" 2>&1 &
  window || fail "the first run showed no window"
  sleep 3
  pkill -x "$names"
  sleep 2
fi

handler=$(xdg-mime query default x-scheme-handler/hideout)
echo "handler: $handler"
ls -la "$XDG_DATA_HOME/applications" 2>&1 | tee "$evidence/applications.txt"
cat "$XDG_CONFIG_HOME/mimeapps.list" > "$evidence/mimeapps.txt" 2>&1
grep hideout /usr/share/applications/mimeinfo.cache > "$evidence/mimeinfo.txt" 2>&1
[ -n "$handler" ] || fail "the scheme has no handler"

click cold xdg-open "hideout://join/$code"
click bad xdg-open "hideout://join/not-a-code"
click running gio open "hideout://join/$code"
grep -E -l "$code|$(printf %s "$code" | tr -d -)" "$XDG_DATA_HOME/hideout/hideout.log" > /dev/null 2>&1 && fail "the invite reached the log"
echo "PASS: every click reached one Hideout"
