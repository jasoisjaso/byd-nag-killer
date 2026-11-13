#!/bin/bash
# Nag Killer installer for Mac (and Linux). Double-click it, or run it in Terminal.
# It downloads Google's adb and the latest Nag Killer, finds the car on your Wi-Fi, installs
# the app and sets it up. Nothing is installed on your computer: everything it downloads goes
# in a "nag-killer-install" folder next to this file, which you can delete afterwards.
#
#   NAGKILLER_APK=/path/to/naglab.apk   install this file instead of the latest release
#   NAGKILLER_IP=192.168.1.50           skip the search and use this address
set -u
REPO="jasoisjaso/byd-nag-killer"
PKG="au.jaso.naglab"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$HERE/nag-killer-install"
mkdir -p "$WORK"

note() { printf '\n%s\n' "$*"; }   # not "say": that is the Mac's talking command
die() { printf '\nSTOPPED: %s\n\n' "$*"; read -r -p "Press Enter to close." _; exit 1; }
ask() { local a; read -r -p "$1 " a; printf '%s' "$a"; }

echo "Nag Killer installer"
echo "===================="
echo "Before you start, in the car (parked, on the same Wi-Fi as this computer):"
echo "  Settings > System > Version. Tap the white words \"Factory reset\" 7 times"
echo "  (NOT the blue Reset button), turn the screen upright, and tap"
echo "  \"CONNECT USB TO ENABLE DEBUGGING MODE\". The README has this step in full."

# ---- 1. adb (Google's platform-tools)
case "$(uname -s)" in
  Darwin) OS=darwin ;;
  Linux)  OS=linux ;;
  *) die "This installer is for Mac and Linux. On Windows use install-windows.bat." ;;
esac
ADB="$(command -v adb || true)"
if [ -z "$ADB" ]; then
  ADB="$WORK/platform-tools/adb"
  if [ ! -x "$ADB" ]; then
    note "Downloading Google's adb tool..."
    curl -fL --progress-bar -o "$WORK/platform-tools.zip" \
      "https://dl.google.com/android/repository/platform-tools-latest-$OS.zip" \
      || die "Couldn't download adb. Check this computer's internet connection."
    (cd "$WORK" && unzip -q -o platform-tools.zip) || die "Couldn't unzip adb."
    xattr -dr com.apple.quarantine "$WORK/platform-tools" 2>/dev/null || true
  fi
fi
[ -x "$ADB" ] || die "adb is missing."

# ---- 2. the app
APK="${NAGKILLER_APK:-}"
if [ -z "$APK" ] && [ -f "$HERE/naglab.apk" ]; then APK="$HERE/naglab.apk"; fi
if [ -z "$APK" ]; then
  APK="$WORK/naglab.apk"
  note "Downloading the latest Nag Killer..."
  curl -fL --progress-bar -o "$APK" "https://github.com/$REPO/releases/latest/download/naglab.apk" \
    || die "Couldn't download Nag Killer from GitHub."
fi
[ -s "$APK" ] || die "The app file is missing or empty: $APK"
WD="$WORK/naglab_watchdog.sh"
if [ -f "$HERE/naglab_watchdog.sh" ]; then WD="$HERE/naglab_watchdog.sh"
else curl -fsL -o "$WD" "https://github.com/$REPO/releases/latest/download/naglab_watchdog.sh" || WD=""; fi

# ---- 3. find the car: anything on this Wi-Fi answering on the debugging port
# -G (give up connecting after a second) is the Mac's nc; elsewhere it is -w. Trying one and
# then the other made every address with nothing on it wait out a 75 second connect on a Mac
# (13 Nov 2026: the search took 76 s per network instead of a couple).
if [ "$OS" = darwin ]; then probe() { nc -z -G 1 "$1" 5555 >/dev/null 2>&1; }
else probe() { nc -z -w 1 "$1" 5555 >/dev/null 2>&1; }; fi
find_cars() {
  local ip base i
  for ip in $(ifconfig 2>/dev/null | awk '/inet / && $2 !~ /^127\./ {print $2}'); do
    base="${ip%.*}"
    for i in $(seq 1 254); do
      ( probe "$base.$i" && echo "$base.$i" ) &
    done
    wait
  done | sort -u
}
IP="${NAGKILLER_IP:-}"
while [ -z "$IP" ]; do
  note "Looking for the car on your Wi-Fi (a few seconds)..."
  FOUND="$(find_cars)"
  N=$(printf '%s\n' "$FOUND" | grep -c . || true)
  if [ "$N" = 1 ]; then
    IP="$FOUND"; echo "Found it at $IP."
  elif [ "$N" -gt 1 ]; then
    echo "More than one device answered:"
    # shellcheck disable=SC2086  # one address per line
    printf '  %s\n' $FOUND
    IP="$(ask "Type the car's address (Settings > Wi-Fi > your network on the car):")"
  else
    echo "Didn't find the car. Check debugging is on and the car is on the same Wi-Fi as this computer."
    IP="$(ask "Press Enter to look again, or type the car's address (e.g. 192.168.1.50):")"
  fi
done
SER="$IP:5555"
probe "$IP" || die "Nothing is answering at $IP. Is debugging on in the car, and is it on this Wi-Fi?"

# ---- 4. connect, and wait for "Allow USB debugging?" on the car
"$ADB" disconnect "$SER" >/dev/null 2>&1
"$ADB" connect "$SER" >/dev/null 2>&1
TOLD=0
KICKED=0
for TRY in $(seq 1 60); do
  STATE="$("$ADB" -s "$SER" get-state 2>&1 | tr -d '\r')"
  [ "$STATE" = device ] && break
  if [ "$TOLD" = 0 ]; then
    note "Look at the car's screen: when it asks \"Allow USB debugging?\", tick \"Always allow\" and tap Allow."
    TOLD=1
  fi
  # Not even asking after a while: adb on this computer can get stuck while the car answers
  # fine. Restarting it once is what fixes that.
  case "$STATE" in
    *unauthorized*) ;;
    *) if [ "$TRY" -ge 8 ] && [ "$KICKED" = 0 ]; then "$ADB" kill-server >/dev/null 2>&1; KICKED=1; fi ;;
  esac
  sleep 2
  "$ADB" connect "$SER" >/dev/null 2>&1
done
[ "${STATE:-}" = device ] || die "The car didn't allow the connection. Run this again and tap Allow on the car's screen."
A() { "$ADB" -s "$SER" "$@"; }

# ---- 5. make sure it's a BYD screen before installing anything
if ! A shell pm list packages 2>/dev/null | grep -q -i "byd"; then
  MODEL="$(A shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
  echo "The device at $IP (\"$MODEL\") doesn't look like a BYD car screen."
  [ "$(ask "Install anyway? Type yes to go on:")" = yes ] || die "Nothing was installed."
fi

# ---- 6. install and set up
note "Installing Nag Killer on the car..."
OUT="$(A install -r "$APK" 2>&1)"
if ! printf '%s' "$OUT" | grep -q Success; then
  printf '%s\n' "$OUT" | tail -3
  case "$OUT" in
    *VERSION_DOWNGRADE*) die "The car has a newer version than this one. To go back, remove it first:  \"$ADB\" -s $SER uninstall $PKG  (this clears its settings), then run this again." ;;
    *UPDATE_INCOMPATIBLE*|*SIGNATURES*) die "The car has a copy from a different source. Remove it first:  \"$ADB\" -s $SER uninstall $PKG  then run this again." ;;
    *) die "The install failed (the message is above)." ;;
  esac
fi
# lets the system bring the app back after the car shuts it down
A shell cmd notification allow_listener "$PKG/$PKG.NagListener" >/dev/null 2>&1 \
  || echo "Note: couldn't set the restart permission. The app will ask in the car."
# lets the floating button show over other apps
A shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1 \
  || echo "Note: couldn't set the floating button permission."
# background helper (brings the app back within seconds of a restart)
if [ -n "$WD" ] && [ -s "$WD" ]; then
  A push "$WD" /data/local/tmp/naglab_watchdog.sh >/dev/null 2>&1
  # shellcheck disable=SC2016  # runs on the car
  A shell 'D=/data/local/tmp; S=$D/naglab_watchdog.sh; chmod 755 $S; rm -f $D/naglab_watchdog.disabled;
    P=$(cat $D/naglab_watchdog.pid 2>/dev/null);
    if [ -n "$P" ] && kill -0 "$P" 2>/dev/null && grep -q naglab_watchdog /proc/$P/cmdline 2>/dev/null; then
      kill -- "-$P" 2>/dev/null || kill "$P" 2>/dev/null; fi;
    rm -f $D/naglab_watchdog.pid;
    setsid nohup $S >/dev/null 2>&1 </dev/null &' >/dev/null 2>&1
fi
A shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1
VER="$(A shell dumpsys package "$PKG" 2>/dev/null | awk -F= '/versionName/ {print $2; exit}' | tr -d '\r')"

note "Done. Nag Killer ${VER:-} is installed and open on the car's screen."
echo "Pick the nags you want kept off in the car. You don't need this computer again."
echo "You can delete the \"nag-killer-install\" folder."
echo
read -r -p "Press Enter to close." _
