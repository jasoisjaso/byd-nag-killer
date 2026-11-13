#!/usr/bin/env bash
# Install / update Nag Killer on the car and (re)start its watchdog.
# Car needs Wireless ADB on. Usage: app/install.sh [car-ip]   (IP is remembered in app/.car-ip)
#   APK=path/to/naglab.apk app/install.sh   install that file instead of building this tree
set -euo pipefail
APK="${APK:-}"
case "$APK" in ""|/*) ;; *) APK="$PWD/$APK" ;; esac   # relative to where you ran it from
cd "$(dirname "$0")"

ADB="${ADB:-adb}"
command -v "$ADB" >/dev/null || { echo "adb not found (install platform-tools or set ADB=/path/to/adb)" >&2; exit 2; }
IP="${1:-$(cat .car-ip 2>/dev/null || true)}"
[ -n "$IP" ] || { echo "usage: $0 <car-ip>" >&2; exit 2; }
SER="$IP:5555"
A=("$ADB" -s "$SER")

nc -z -G 3 "$IP" 5555 2>/dev/null || nc -z -w 3 "$IP" 5555 2>/dev/null \
  || { echo "car not reachable on $IP:5555 (Wireless ADB on?)" >&2; exit 1; }
# Connect, but never hang: the adb server on the Mac can go stale while the car answers fine
# (24 and 27 Oct: stuck at wait-for-device until adb kill-server). Try, restart adb once, give up.
connect() {   # $1 = seconds to wait
  local i state
  "$ADB" disconnect "$SER" >/dev/null 2>&1 || true
  "$ADB" connect "$SER" >/dev/null 2>&1 || true
  for i in $(seq 1 "$1"); do
    state=$("${A[@]}" get-state 2>/dev/null | tr -d '\r' || true)
    if [ "$state" = device ]; then return 0; fi
    # (grep without -q: under pipefail an early exit would look like "no match")
    if [ "$i" = 5 ] && "$ADB" devices 2>/dev/null | grep "^${SER}[[:space:]]*unauthorized" >/dev/null; then
      echo "the car is asking \"Allow USB debugging?\": tick Always allow and tap Allow" >&2
    fi
    sleep 1
  done
  return 1
}
if ! connect 15; then
  echo "adb isn't getting through to $SER, restarting it and trying once more..." >&2
  "$ADB" kill-server >/dev/null 2>&1 || true
  connect 30 || { echo "couldn't connect to $SER (car reachable, adb not: is the car showing an Allow box?)" >&2; exit 1; }
fi
echo "$IP" > .car-ip

if [ -n "$APK" ]; then
  [ -f "$APK" ] || { echo "no such file: $APK" >&2; exit 2; }
  echo "installing $APK (not building)"
else
  ./build.sh >/dev/null
  APK=build/naglab.apk
fi
"${A[@]}" install -r "$APK" | tail -1
# Notification access = the system revives the app after BYD's force-stop and after a
# head-unit reboot, with no ADB (NagListener). Proven on the car 2026-10-24.
"${A[@]}" shell cmd notification allow_listener au.jaso.naglab/au.jaso.naglab.NagListener
# Floating quick button = "display over other apps".
"${A[@]}" shell appops set au.jaso.naglab SYSTEM_ALERT_WINDOW allow

# Watchdog first (survives BYD's force-stop of third-party apps at ACC off), so the app screen
# opened below already sees a heartbeat and does not try to start a second one itself.
# Same stop/start logic as HelperScript.ensure(): a stale pid from before a reboot is only
# trusted when /proc/<pid>/cmdline names the watchdog.
"${A[@]}" push watchdog.sh /data/local/tmp/naglab_watchdog.sh >/dev/null
# shellcheck disable=SC2016  # runs on the car; expansion is meant to happen there
"${A[@]}" shell 'D=/data/local/tmp; S=$D/naglab_watchdog.sh; chmod 755 $S; rm -f $D/naglab_watchdog.disabled;
  P=$(cat $D/naglab_watchdog.pid 2>/dev/null);
  if [ -n "$P" ] && kill -0 "$P" 2>/dev/null && grep -q naglab_watchdog /proc/$P/cmdline 2>/dev/null; then
    kill -- "-$P" 2>/dev/null || kill "$P" 2>/dev/null; fi;
  rm -f $D/naglab_watchdog.pid;
  setsid nohup $S >/dev/null 2>&1 </dev/null &'
sleep 2
WPID=$("${A[@]}" shell cat /data/local/tmp/naglab_watchdog.pid 2>/dev/null | tr -d '\r' || true)
if [ -n "$WPID" ] && "${A[@]}" shell "kill -0 $WPID" 2>/dev/null; then
  echo "watchdog running (pid $WPID)"
else
  echo "WARNING: watchdog did not start" >&2
  exit 1
fi

# Turn it on (opens the Nag Killer screen) and start the service.
"${A[@]}" shell am start -n au.jaso.naglab/.MainActivity --ez enable true >/dev/null
