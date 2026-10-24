#!/usr/bin/env bash
# Install / update Nag Killer on the car and (re)start its watchdog.
# Car needs Wireless ADB on. Usage: app/install.sh [car-ip]   (IP is remembered in app/.car-ip)
set -euo pipefail
cd "$(dirname "$0")"

ADB="${ADB:-adb}"
command -v "$ADB" >/dev/null || { echo "adb not found (install platform-tools or set ADB=/path/to/adb)" >&2; exit 2; }
IP="${1:-$(cat .car-ip 2>/dev/null || true)}"
[ -n "$IP" ] || { echo "usage: $0 <car-ip>" >&2; exit 2; }
SER="$IP:5555"
A=("$ADB" -s "$SER")

nc -z -G 3 "$IP" 5555 2>/dev/null || nc -z -w 3 "$IP" 5555 2>/dev/null \
  || { echo "car not reachable on $IP:5555 (Wireless ADB on?)" >&2; exit 1; }
"$ADB" disconnect "$SER" >/dev/null 2>&1 || true
"$ADB" connect "$SER" >/dev/null
"${A[@]}" wait-for-device
echo "$IP" > .car-ip

./build.sh >/dev/null
"${A[@]}" install -r build/naglab.apk | tail -1
# Notification access = the system revives the app after BYD's force-stop and after a
# head-unit reboot, with no ADB (NagListener). Proven on the car 2026-10-24.
"${A[@]}" shell cmd notification allow_listener au.jaso.naglab/au.jaso.naglab.NagListener

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
