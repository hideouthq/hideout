#!/bin/sh
# Usage: packaging/linux/try-link.sh <debian12|fedora44|arch> <evidence-dir>
set -u
distro=$1
evidence=$(mkdir -p "$2" && cd "$2" && pwd)
here=$(cd "$(dirname "$0")" && pwd)
packages=$here/../../build/tasks/_desktopApp_packages@native-package/package
code=HVC-HKN-23Z
case "$distro" in
  debian12) image=docker.io/library/debian:12
    install="apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq xvfb xdotool x11-utils x11-apps libgl1-mesa-dri procps xdg-utils desktop-file-utils libglib2.0-bin && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq /packages/hideout_*.deb"; start=- ;;
  fedora44) image=registry.fedoraproject.org/fedora:44
    install="dnf -y -q install xorg-x11-server-Xvfb xdotool xprop xwd mesa-dri-drivers procps-ng xdg-utils desktop-file-utils glib2 /packages/hideout-*.rpm"; start=- ;;
  arch) image=docker.io/library/archlinux:latest
    install="pacman -Syu --noconfirm --quiet xorg-server-xvfb xdotool xorg-xprop xorg-xwd mesa fontconfig ttf-dejavu libxtst libxi alsa-lib procps-ng xdg-utils glib2 && cp /packages/Hideout-*-x86_64.AppImage /opt/Hideout.AppImage"
    start="/opt/Hideout.AppImage" ;;
  *) echo "unknown distro $distro" >&2; exit 2 ;;
esac
podman run --rm -e APPIMAGE_EXTRACT_AND_RUN=1 -v "$packages:/packages:ro" -v "$here/check-link.sh:/check-link.sh:ro" -v "$evidence:/evidence" "$image" \
  sh -c "($install) > /evidence/install.log 2>&1 && echo '127.0.0.1 gamebanana.com api.deadlock-api.com' >> /etc/hosts && sh /check-link.sh /evidence '$start' $code" \
  2>&1 | tee "$evidence/run.log"
for shot in "$evidence"/*.xwd; do
  [ -f "$shot" ] && magick "xwd:$shot" "${shot%.xwd}.png" && rm "$shot"
done
tail -1 "$evidence/run.log" | grep -q '^PASS'
