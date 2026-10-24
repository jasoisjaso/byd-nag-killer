#!/system/bin/sh
# Nag Killer watchdog. Runs as the adb shell user (uid 2000), started with setsid by
# app/install.sh (Mac) or by the app itself over local ADB (HelperSetup), so it leads its own
# process group (stopped with kill -- -PGID).
#
# 1. BYD's power service (com.ts.appservice.power, CarPowerBinder.forceStopThirdApp)
#    force-stops EVERY third-party app at each ACC off, and a force-stopped app cannot
#    restart itself. Shell processes are not touched, so this loop brings Nag Killer back.
# 2. Overdrive coexistence: at home Overdrive (ON-only) sleeps at ACC off and only wakes when
#    its BootReceiver gets com.byd.action.ACC_ON, which this car never sends (checked
#    2026-10-18). On real car power-on (SystemUI logs "onPowerLevelChanged level: 2 ---
#    lastPowerLevel: 0") with Overdrive's parked marker present, we send it that broadcast.
#    Verified by hand: Overdrive admits ACC ON, clears the marker, restarts its daemons.
#
# Dies only when the head unit reboots; then turn Wireless ADB on and open Nag Killer (it
# starts this again by itself), or re-run app/install.sh from the Mac.
# Stop it for good: touch /data/local/tmp/naglab_watchdog.disabled
PKG=au.jaso.naglab
DIR=/data/local/tmp
LOG=$DIR/naglab_watchdog.log
PIDFILE=$DIR/naglab_watchdog.pid
OFF=$DIR/naglab_watchdog.disabled
OD_MARKER=$DIR/overdrive_parked_shutdown

# The pid file survives a head-unit reboot: only trust a pid that is really us.
is_watchdog() {
  [ -n "$1" ] && [ "$1" != "$$" ] && kill -0 "$1" 2>/dev/null \
    && grep -q naglab_watchdog "/proc/$1/cmdline" 2>/dev/null
}

if is_watchdog "$(cat "$PIDFILE" 2>/dev/null)"; then
  exit 0   # already running
fi
echo $$ > "$PIDFILE"
echo "[$(date)] watchdog started pid $$" >> "$LOG"

# Heartbeat so the app can show "background helper running" (and knows when it must
# start it again after a screen reboot). 0x20 = FLAG_INCLUDE_STOPPED_PACKAGES.
ping_app() { am broadcast -n "$PKG/.HelperPing" -f 0x20 >/dev/null 2>&1; }

# Start (or poke) NagService; harmless if it is already running (re-opens its fast-poll
# window, which is what we want right after a power-on).
start_app() { am start-foreground-service -n "$PKG/.NagService" >/dev/null 2>&1; }

wake_overdrive() {
  [ -f "$OD_MARKER" ] || return 0
  pm path com.overdrive.app >/dev/null 2>&1 || return 0
  sleep 3
  # 0x10000020 = FLAG_RECEIVER_FOREGROUND | FLAG_INCLUDE_STOPPED_PACKAGES
  if am broadcast -a com.byd.action.ACC_ON -n com.overdrive.app/.receiver.BootReceiver \
      -f 0x10000020 >/dev/null 2>&1; then
    echo "[$(date)] Overdrive was parked-asleep -> sent it ACC_ON" >> "$LOG"
  fi
}

acc_watch() {
  while true; do
    since=$(date +'%m-%d %H:%M:%S.000')
    logcat -v brief -T "$since" 'AbsBYDAutoBodyworkListener:W' '*:S' 2>/dev/null | while read -r line; do
      case "$line" in
        *"onPowerLevelChanged level: 2 --- lastPowerLevel: 0"*)
          now=$(date +%s)
          last=$(cat "$DIR/naglab_lastpoweron" 2>/dev/null || echo 0)
          if [ $((now - last)) -ge 60 ]; then
            echo "$now" > "$DIR/naglab_lastpoweron"
            echo "[$(date)] car power-on" >> "$LOG"
            start_app     # DMS resets to ON right about now: make the app re-check fast
            ping_app
            wake_overdrive
          fi
          ;;
      esac
    done
    sleep 5   # logcat ended (buffer cleared?): restart it
  done
}

acc_watch &

ping_app
# Started mid-drive (after a head-unit reboot killed the old watchdog, the owner opened Nag
# Killer): the power-on moment has passed, so wake a parked-asleep Overdrive now. Only runs
# when its parked marker exists; the app and install.sh only start us with the car on.
wake_overdrive &

FAILED=0
TICK=0
while true; do
  TICK=$((TICK + 1))
  if [ $((TICK % 24)) -eq 0 ]; then ping_app; fi   # every ~2 min (app treats >6 min as dead)
  if [ -f "$OFF" ]; then
    echo "[$(date)] disabled marker found, exiting" >> "$LOG"
    rm -f "$PIDFILE"
    kill 0   # whole process group = us + acc_watch + its logcat (we are the setsid leader)
    exit 0
  fi
  if ! pidof "$PKG" >/dev/null 2>&1; then
    if start_app; then
      echo "[$(date)] app was not running (BYD force-stop?) -> NagService started" >> "$LOG"
      sleep 2
      ping_app
    elif [ "$FAILED" != 1 ]; then
      echo "[$(date)] could not start NagService (am failed)" >> "$LOG"
      FAILED=1
    fi
  else
    FAILED=0
  fi
  # keep the log small
  if [ "$(wc -c < "$LOG" 2>/dev/null)" -gt 65536 ] 2>/dev/null; then
    tail -n 200 "$LOG" > "$LOG.tmp" && mv "$LOG.tmp" "$LOG"
  fi
  sleep 5
done
