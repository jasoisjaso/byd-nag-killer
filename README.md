# Nag Killer for BYD Sealion 7

A tiny Android app that runs on the car's own screen and keeps **Driver Monitoring (DMS)
switched off**.

BYD turns DMS back on every time the car starts. You switch it off in Settings, drive, park,
come back: it is on again, chiming at you for glancing at the screen. Nag Killer watches the
setting and switches it off again within a few seconds of every start.

Tested on an Australian 2025 BYD Sealion 7 (DiLink 5, Android 11). It talks to BYD's own
settings service, the same one the Settings screen uses, so the car's UI shows the correct
state and nothing on the system is modified. No root, no cloud, 29 KB.

## Quick start

Needs a computer on the same Wi-Fi as the car, for one minute. (USB stick or the car's
browser won't work: the car blocks installs from its own screen.)

1. Download [naglab.apk](https://github.com/jasoisjaso/byd-nag-killer/releases/latest) and
   Google's [platform-tools](https://developer.android.com/tools/releases/platform-tools) zip.
   Unzip it, drop `naglab.apk` into the `platform-tools` folder.

2. In the car: Settings > System > Version, tap the **white** "Factory reset" text 7 times,
   tap the first grey "CONNECT USB TO ENABLE DEBUGGING MODE" button.
   Find the car's IP under Settings > Wi-Fi.

3. Open a terminal in the `platform-tools` folder and run (with your car's IP):

       adb connect 192.168.1.50:5555
       adb install naglab.apk

   (Windows: `.\adb`, Mac/Linux: `./adb`.) Tap Allow on the car when it asks.

4. In the car, open **Nag Killer**, tap the big button, then tap "Start background helper"
   and Allow. Done.

Didn't work? See [Troubleshooting](#troubleshooting).

## What it does

* `NagService` binds to `com.ts.appservice.settings` (BYD's SettingsService, exported, no
  permission needed) and subscribes to DMS state callbacks. Any of the three switches
  (master, fatigue, distraction) going to **1** -> it sets all three to **0**. It also polls
  (10 s for the first 3 minutes after a start, then 60 s) in case a reset arrives silently.
* BYD force-stops every third-party app at ACC off (`CarPowerBinder.forceStopThirdApp`),
  and a force-stopped app cannot restart itself. So a small shell script, `watchdog.sh`,
  runs as the ADB shell user (which BYD leaves alone) and restarts the service within 5 s.
  It also kicks the service at power-on and sends the app a heartbeat so the screen can say
  whether it is alive.
* The watchdog dies only on a head-unit reboot (rare). The app can bring it back by itself:
  it contains a minimal ADB client that connects to the car's own `adbd` on `127.0.0.1:5555`
  and starts the script. Turn Wireless ADB on, open the app, tap Allow once. No laptop.
* One button on screen: "Keep it OFF" or "Not managed". Nothing else.

Overdrive owners: Nag Killer does not touch anything Overdrive controls. The watchdog does
one extra thing for Overdrive: after a short stop at home it sends Overdrive the ACC_ON
broadcast the car itself never sends, so Overdrive wakes up again.

**Status:** core function proven on the car (full off/on cycle, DMS off 4 s after power-on,
survives BYD force-stopping third-party apps). The in-car "Start background helper" button
(step 4) is new in 0.5 and not yet exercised on a car; the computer fallback is the proven
path.

## Troubleshooting

* `adb connect` says it cannot connect: the car has switched Wireless ADB off again (it
  does that after sitting parked). Redo step 2.
* "Start background helper" fails on the car: do it from the computer. Download
  `naglab_watchdog.sh` from the release into the `platform-tools` folder and run

      adb push naglab_watchdog.sh /data/local/tmp/naglab_watchdog.sh
      adb shell "chmod 755 /data/local/tmp/naglab_watchdog.sh; setsid nohup /data/local/tmp/naglab_watchdog.sh >/dev/null 2>&1 </dev/null &"

* After a head-unit reboot the helper is gone: redo step 2, open Nag Killer, tap "Start
  background helper" (or the two lines above). The app also retries by itself every 10
  minutes, quietly, without ever popping the Allow dialog while you drive.
* Update: `adb install -r naglab.apk`. Remove: tap the button to "Not managed", or
  `adb uninstall au.jaso.naglab`.

## Stop it

Tap the button ("Not managed"), or uninstall. To stop the watchdog for good from a shell:
`touch /data/local/tmp/naglab_watchdog.disabled`.

## Build from source

Mac or Linux with a JDK 17+ and `adb` on your PATH.

    tools/get-tools.sh              # once: downloads build-tools 36 + android-30.jar from Google
    app/install.sh 192.168.1.50     # car's IP; builds, installs, starts the service + watchdog
    app/test/run.sh                 # Mac tests (needs ~/.android/adbkey from a previous adb run)

The first build creates `app/keys/naglab.keystore`. Keep it: an APK signed with a new key
cannot update the installed app (you would have to uninstall first). Your own build also
cannot update over the release APK, for the same reason.

## Layout

    app/src/au/jaso/naglab/
      NagService.java    foreground service: subscribe, enforce, poll
      DmsClient.java     raw binder client for BYD's DMS settings service
      AdbLocal.java      minimal ADB client (TCP, RSA auth), pure Java
      HelperSetup.java   uploads + starts watchdog.sh over local ADB
      HelperScript.java  the shell commands it sends (unit-tested on the Mac)
      HelperPing.java    heartbeat receiver from the watchdog
      MainActivity.java  the one-button screen
      BootReceiver.java  restart after boot / app update
    app/watchdog.sh      shell-uid watchdog (restarts the app, power-on kick, Overdrive wake)
    app/build.sh         javac + d8 + aapt2 + zipalign + apksigner
    app/install.sh       build + adb install + start watchdog
    app/test/run.sh      Mac tests: ADB key format vs ~/.android/adbkey.pub, signature,
                         handshake and shell against a mock adbd, script upload/start logic
    tools/get-tools.sh   fetches the SDK pieces (not committed)

## The binder interface

Bind `com.ts.appservice.settings/.SettingsService` with action
`com.ts.lib.settings.ISettingsAdapterService`, transact 2 `getSettingsAdapterService("dms")`,
then on `com.ts.lib.settings.dms.IDmsSettingsService`: 2 `setDmsSwtichStatus` (sic),
4 `setFatigueDetect`, 5 `setAttentionDetect`, 6 `getDmsSwitchChanged`, 7 `queryFatigueDetect`,
8 `queryAttentionDetect`, 11 `subscribe`, 12 `unsubscribe`. Callback
`com.ts.lib.settings.dms.IDmsSettingsCallback`: 1 dms, 2 fatigue, 3 attention. Values 0 off,
1 on. Turning only the master off leaves fatigue/attention reporting 1, so all three are set.
The reset-to-on happens on the DMS box / MCU side at power level 0->2, not in Android.

Logs: `adb logcat -s NagLab:W` (the ROM drops INFO), watchdog:
`/data/local/tmp/naglab_watchdog.log`.

## Not done / caveats

* 0.5's in-car helper restart has not been tried on the car yet.
* Only DMS. Speed-limit chime and lane-assist mode go through a different BYD service
  (`CarAdapterService`); the permissions are already declared, the code is not written.
* Other BYD models / DiLink versions: untested. The service names above came from the
  Sealion 7's `ts-framework.jar`; if your car has `com.ts.appservice.settings`, it may work.
* Use at your own risk. Driver monitoring exists for a reason; this is for people who have
  decided they do not want it and are tired of the car ignoring that decision.

MIT.
