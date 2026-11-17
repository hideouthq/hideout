#!/bin/sh
# Usage: check-window.sh <evidence-dir> <command...>
set -u
evidence=$1
shift
run=$(mktemp -d)
mkdir -p "$run/home" "$run/tmp"
Xvfb :77 -screen 0 1600x1000x24 -nolisten tcp > "$evidence/xvfb.log" 2>&1 &
xvfb=$!
export DISPLAY=:77 HOME="$run/home" TMPDIR="$run/tmp"
sleep 2
start=$(date +%s%N)
"$@" -Duser.home="$run/home" -Djava.io.tmpdir="$run/tmp" > "$evidence/app.log" 2>&1 &
app=$!
trap 'kill "$app" "$xvfb" 2>/dev/null; rm -rf "$run"' EXIT
window=
while [ -z "$window" ] && [ $(( ($(date +%s%N) - start) / 1000000000 )) -lt 120 ] && kill -0 "$app" 2>/dev/null; do
  window=$(xdotool search --name '^Hideout$' 2>/dev/null | head -1)
  [ -n "$window" ] || sleep 0.05
done
if [ -z "$window" ]; then
  echo "FAIL: no Hideout window"
  exit 1
fi
echo "window after $(( ($(date +%s%N) - start) / 1000000 )) ms"
sleep 10
xprop -id "$window" WM_CLASS | tee "$evidence/xprop.txt"
xwd -root -silent -out "$evidence/screen.xwd"
if ! kill -0 "$app" 2>/dev/null; then
  echo "FAIL: the app exited"
elif ! grep -q '"gg-hideouthq-MainKt"' "$evidence/xprop.txt"; then
  echo "FAIL: WM_CLASS does not match the menu entry's StartupWMClass"
# Xvfb has no hardware GL, so Skiko falls back to software rendering after this one line, as it does on the JVM.
elif grep -v "RenderException: Cannot create Linux GL context" "$evidence/app.log" | grep -E "Exception|Error:|UnsatisfiedLinkError|Missing(Reflection|JNI|Resource)Registration|was not registered"; then
  echo "FAIL: errors in app.log"
else
  echo "PASS: Hideout window up and its log clean"
  exit 0
fi
exit 1
