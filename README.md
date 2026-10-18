# Nag Killer for BYD Sealion 7

A tiny Android app that runs on the car's own screen and keeps **Driver Monitoring (DMS)
switched off**.

BYD turns DMS back on every time the car starts. You switch it off in Settings, drive, park,
come back: it is on again, chiming at you for glancing at the screen. Nag Killer watches the
setting and switches it off again within a few seconds of every start.

Tested on an Australian 2025 BYD Sealion 7 (DiLink 5, Android 11). It talks to BYD's own
settings service, the same one the Settings screen uses, so the car's UI shows the correct
state and nothing on the system is modified. No root, no cloud, no Gradle.

**Status:** core function proven on the car (full off/on cycle, DMS off 4 s after power-on,
survives BYD force-stopping third-party apps). Version 0.5 adds an in-car recovery path that
is unit-tested but not yet exercised on the car.

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

## Install the ready-made app (no building)

You cannot install it from a USB stick or the car's browser: the Sealion 7 blocks every
install started on its own screen. You need a computer on the same Wi-Fi as the car for
about one minute, and the free `adb` tool from Google. That is the whole requirement.

1. Download `naglab.apk` from the [latest release](https://github.com/jasoisjaso/byd-nag-killer/releases/latest).
2. Get adb: download "SDK Platform-Tools" for your OS from
   https://developer.android.com/tools/releases/platform-tools and unzip it. Put
   `naglab.apk` in that unzipped folder.
3. Car: Settings > System > Version, tap the **white** "Factory reset" text 7 times (NOT
   the blue Reset button), tap the first grey "CONNECT USB TO ENABLE DEBUGGING MODE"
   button. Note the car's Wi-Fi IP (Settings > Wi-Fi > your network).
4. Open a terminal in the platform-tools folder and run (replace the IP):

       Windows (PowerShell or cmd):   .\adb connect 192.168.1.50:5555
                                      .\adb install naglab.apk
       Mac / Linux:                   ./adb connect 192.168.1.50:5555
                                      ./adb install naglab.apk

   The car asks "Allow USB debugging?" the first time: tick Always allow, tap Allow.
5. Car: open **Nag Killer**, tap the big button so it says "Keep it OFF: active", then tap
   "Start background helper" and Allow once more when asked. The screen should say
   "Background helper: running". Done. ADB can go off; the computer is not needed again.

If step 5 says the helper did not start, run these two from the same terminal (Mac/Linux
shown; on Windows use `.\adb`), with `naglab_watchdog.sh` from the release next to the APK:

    ./adb push naglab_watchdog.sh /data/local/tmp/naglab_watchdog.sh
    ./adb shell "chmod 755 /data/local/tmp/naglab_watchdog.sh; setsid nohup /data/local/tmp/naglab_watchdog.sh >/dev/null 2>&1 </dev/null &"

Updating: install the new APK the same way (`adb install -r naglab.apk`). Removing:
`adb uninstall au.jaso.naglab`, or just tap the button to "Not managed" and leave it.

## Build and install from source

You need: a Mac or Linux box with a JDK 17+ and `adb` on your PATH, the car on your home
Wi-Fi, and Wireless ADB enabled on the car (step 3 above). The car switches ADB off again
after it has been parked for a while; that is fine, Nag Killer does not need it while driving.

    tools/get-tools.sh              # once: downloads build-tools 36 + android-30.jar from Google
    app/install.sh 192.168.1.50     # car's IP; builds, installs, starts the service + watchdog

The first build creates `app/keys/naglab.keystore`. Keep it: an APK signed with a new key
cannot update the installed app (you would have to uninstall first). Your own build also
cannot update over the release APK, for the same reason.

## After a head-unit reboot

The watchdog is gone. Either run `app/install.sh` again (or the two adb commands above), or
from the car:

1. Turn Wireless ADB on (steps above).
2. Open Nag Killer. It tries by itself after 4 s, or tap "Start background helper".
3. First time only the car asks "Allow USB debugging?": tick Always allow, tap Allow.
4. The screen shows "Background helper: running". Wireless ADB can go off again.

The service also retries on its own every 10 minutes while the helper is missing, but only
with a key the car already trusts: it never pops that dialog while you are driving.

## Stop it

Tap the button ("Not managed"), or uninstall. To stop the watchdog for good from a shell:
`touch /data/local/tmp/naglab_watchdog.disabled`.

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
