#!/bin/sh
# Usage: packaging/linux/try-distro.sh <ubuntu20|debian12|fedora44|arch> <evidence-dir>
set -u
distro=$1
evidence=$(mkdir -p "$2" && cd "$2" && pwd)
here=$(cd "$(dirname "$0")" && pwd)
packages=$here/../../build/tasks/_desktopApp_packages@native-package/package
x11="xvfb xdotool x11-utils x11-apps libgl1-mesa-dri"
case "$distro" in
  ubuntu20) image=docker.io/library/ubuntu:20.04 ;;
  debian12) image=docker.io/library/debian:12 ;;
  fedora44) image=registry.fedoraproject.org/fedora:44 ;;
  arch) image=docker.io/library/archlinux:latest ;;
  *) echo "unknown distro $distro" >&2; exit 2 ;;
esac
case "$distro" in
  ubuntu*|debian*) install="apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq $x11 /packages/hideout_*.deb"; run=/opt/hideout/Hideout ;;
  fedora*) install="dnf -y -q install xorg-x11-server-Xvfb xdotool xprop xwd mesa-dri-drivers /packages/hideout-*.rpm"; run=/opt/hideout/Hideout ;;
  arch) install="pacman -Syu --noconfirm --quiet xorg-server-xvfb xdotool xorg-xprop xorg-xwd mesa fontconfig ttf-dejavu libxtst libxi alsa-lib"; run="env APPIMAGE_EXTRACT_AND_RUN=1 /packages/Hideout-*-x86_64.AppImage" ;;
esac
podman run --rm -v "$packages:/packages:ro" -v "$here/check-window.sh:/check-window.sh:ro" -v "$evidence:/evidence" "$image" \
  sh -c "($install) > /evidence/install.log 2>&1 && grep PRETTY_NAME /etc/os-release > /evidence/os-release && ldd --version | head -1 > /evidence/glibc && sh /check-window.sh /evidence $run" \
  2>&1 | tee "$evidence/run.log"
[ -f "$evidence/screen.xwd" ] && magick "xwd:$evidence/screen.xwd" "$evidence/screen.png" && rm "$evidence/screen.xwd"
tail -1 "$evidence/run.log" | grep -q '^PASS'
