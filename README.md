# Nag Killer for BYD Sealion 7

A small Android app for the car's own screen. It switches off the nags you pick and keeps
them off every time the car starts:

* **Driver monitoring (DMS)**: fatigue and distraction alerts from the steering-column camera.
* **Lane assist**: steering nudges and the lane-departure buzz.
* **Speed limit chime**: the tone when the limit changes, and the over-limit warning.
* **Child detection**: the rear-seat "child left in the car" alarm. Opt-in only.

BYD switches these back on at every start. You turn DMS off in Settings, drive, park, come
back: it's on again and chiming at you for glancing at the screen. Nag Killer watches each
setting and switches it off again within a few seconds of every start. A floating **quick
button** over CarPlay kills your chosen set with one tap.

Tested on an Australian 2025 BYD Sealion 7 (DiLink 5, Android 11). It uses BYD's own settings
services, the same ones the car's Settings screen uses, so the car's UI always shows the real
state and nothing on the system is modified. No root, no cloud, about 120 KB. It never
touches braking (AEB) or steering beyond the lane-assist mode you can already pick on the
car's quick tile.

## Quick start

You need a computer on the same Wi-Fi as the car for about two minutes. A USB stick or the
car's browser won't work: the car blocks every install started from its own screen.

1. Download [naglab.apk](https://github.com/jasoisjaso/byd-nag-killer/releases/latest) and
   Google's [platform-tools](https://developer.android.com/tools/releases/platform-tools) zip.
   Unzip platform-tools and drop `naglab.apk` into the `platform-tools` folder.

2. Turn on debugging in the car (the "hidden page"). Do this parked.

   1. Settings > System > Version. The last row says "Factory reset" in **white words on the
      left** and has a **blue "Reset" button on the right**. **Never touch either blue Reset
      button: it really wipes the car.**
   2. Tap the white words "Factory reset" **7 times**, steadily, about 2 taps a second.
      Nothing happens on screen until the 7th tap. If nothing opens, wait a second and try
      again at an even rhythm. If a "Reset?" box ever pops up, tap Cancel.
   3. **Rotate the screen to portrait** (the car's screen-rotate button). In landscape the
      hidden page's buttons run off the edge of the screen, so **you can't see or tap the
      debugging button**. This is the step people miss.
   4. In portrait, tap the **first** long grey button, "CONNECT USB TO ENABLE DEBUGGING
      MODE/REVOKE USB DEBUGGING...". Leave everything else on that page alone (especially
      "Factory Service Interface" and "Rollbench Mode"). Rotate back to landscape.
   5. Find the car's IP under Settings > Wi-Fi > tap your network (looks like 192.168.x.x).

   The car switches debugging off again after it has sat parked for a while. You only need it
   on for installing; Nag Killer itself keeps working without it.

3. Open a terminal in the `platform-tools` folder and run these, using your car's IP:

       adb connect 192.168.1.50:5555
       adb install -r naglab.apk
       adb shell cmd notification allow_listener au.jaso.naglab/au.jaso.naglab.NagListener
       adb shell appops set au.jaso.naglab SYSTEM_ALERT_WINDOW allow

   On Windows type `.\adb`, on Mac/Linux `./adb`. When the car asks "Allow USB debugging?",
   tick "Always allow" and tap Allow. The last two lines let the car restart Nag Killer by
   itself after a restart, and let the quick button float over other apps. (If you skip them,
   step 4 sets them up too, as long as Wireless ADB is on.)

4. In the car, open **Nag Killer**. If the bottom line says "Background helper not running",
   tap **Start**, then Allow, ticking "Always allow". That's it. The computer isn't needed again.

Didn't work? See [Troubleshooting](#troubleshooting).

## Using it

**Left side: status and the button.**

* The headline is measured against what you asked for. "All set" means every nag you're
  keeping off is off, and it lists which you've left on. "Switching off" (orange) means one
  you're keeping off came back on and is being switched off again.
* The big button kills the nags ticked in the chips under it (Monitoring, Lane assist, Speed
  chime, Child alarm). Tap a chip to add it to the button or take it out. Monitoring, lane assist
  and the chime are ticked by default; child detection you have to add yourself. The button
  names what it will kill ("Kill Monitoring + Lane assist") and shows a ✓ when all of them are
  already off.
* The button **only ever kills**. Tapping it again, or by accident while driving, can't turn
  anything back on.

**Right side: one row per nag.** **Car now** shows what the car reports; lane assist and child
detection also show their mode in the row text. **Keep off** is the switch:

* **Switch on**: the nag goes off now and stays off at every start. Nag Killer first remembers
  how it was set: the lane assist mode (Warning / Prevent / Both), whether the chime and the
  over-limit warning were each on, and the child detection mode (Standard / Delay).
* **Switch off**: the nag comes back the way it was. This is the only way Nag Killer turns
  something back on.

Colours: green means silenced. Grey "On" means it's on because you chose to leave it on.
Orange means a nag you asked to keep off is on.

**Quick button.** Turn on the "Quick button" row and the same button floats over everything,
CarPlay included. It hides while the Nag Killer screen is open, since the big button is right
there.

* Tap: kills the ticked nags. A small card shows what happened to each one ("switched off",
  "already off", "still on" or "no answer").
* Dot: green when every ticked nag is killed and off, orange when one isn't.
* Hold, then drag: moves the button. It remembers where you put it.

## How it keeps working

* BYD force-stops every third-party app when the car turns off
  (`CarPowerBinder.forceStopThirdApp`), and the head unit sometimes reboots while parked
  overnight.
* **Notification access** (`NagListener`) is the main revive path. The system itself rebinds
  a granted notification listener at boot and after the app is killed, which starts Nag
  Killer again with no ADB. It doesn't read any notifications. Proven on the car: after a
  force-stop the app was back in under 0.1 s, and after a head-unit reboot it was back about
  20 s after boot.
* The **background helper** (`watchdog.sh`, runs as the ADB shell user, which BYD leaves
  alone) is a second line of defence. It restarts the app within 5 s and kicks it at every
  power-on. It also sends Overdrive the ACC_ON broadcast the car never sends, so an Overdrive
  that went to sleep while parked at home wakes up again.
* If the helper is gone after a reboot, the app restarts it over the car's own ADB
  (`127.0.0.1:5555`) using a key you approved once. It never pops the Allow dialog while you
  drive.

Overdrive owners: Nag Killer doesn't touch anything Overdrive controls.

## Status

| What | On the car |
|---|---|
| DMS off within seconds of every start, survives the force-stop at car off | proven |
| Revive after a force-stop and after a head-unit reboot (notification access) | proven |
| Lane assist off, and back on in the remembered mode | proven |
| Child detection off, and back on (Standard) | proven |
| Speed-limit chime off, and back on | proven |
| Over-limit warning off / on writes | sent, not yet confirmed on the car (it was already off) |
| A real overnight park with only the notification-access revive | not yet tested |

Other BYD models and DiLink versions are untested. The service names came from the Sealion 7's
`ts-framework.jar`. If your car has `com.ts.appservice.settings` and
`com.ts.appservice.caradapter`, it may work.

## Troubleshooting

* **I tapped "Factory reset" 7 times but there's no debugging button.** Rotate the screen to
  portrait. The hidden page opens in landscape too, but the long grey buttons run off the edge
  there and you can't see or tap them. If nothing opened at all, tap the white words again at
  a slow, even 2 taps a second (never the blue Reset button).
* **`adb connect` says it can't connect.** The car has switched Wireless ADB off again (it
  does that after sitting parked). Redo step 2. If the car answers but adb still fails with
  "No route to host", run `adb kill-server` and try again.
* **"Background helper not running" and Start fails.** Do it from the computer instead.
  Download `naglab_watchdog.sh` from the release into the `platform-tools` folder and run:

      adb push naglab_watchdog.sh /data/local/tmp/naglab_watchdog.sh
      adb shell "chmod 755 /data/local/tmp/naglab_watchdog.sh; setsid nohup /data/local/tmp/naglab_watchdog.sh >/dev/null 2>&1 </dev/null &"

  DMS is still kept off without the helper. The helper makes the comeback faster and wakes
  Overdrive.
* **The quick button doesn't show.** The "Quick button" row says it needs "display over
  other apps". Run the `appops` line from step 3, or turn Wireless ADB on and tap Start.
* **Nag Killer doesn't come back after the car has been off.** Run the `allow_listener` line
  from step 3, then open the app once.
* **A nag shows "still on".** The car didn't accept the write. Open the app and use the switch
  again; logs are in `adb logcat -s NagLab:W`.

## Update, stop, remove

* **Update:** `adb install -r naglab.apk`. Your settings stay.
* **Bring one nag back:** switch its Keep off switch off in the app, and it comes back as it was.
* **Stop the helper for good:** `adb shell touch /data/local/tmp/naglab_watchdog.disabled`
* **Remove:** switch everything off, then `adb uninstall au.jaso.naglab`.

## Build from source

You need a Mac or Linux machine with JDK 17+ and `adb` on your PATH (or `ADB=/path/to/adb`).

    tools/get-tools.sh              # once: downloads build-tools 36 + android-30.jar from Google
    app/install.sh 192.168.1.50     # car's IP (remembered in app/.car-ip): builds, installs,
                                    # grants notification access + overlay, starts the watchdog
    app/test/run.sh                 # Mac tests (needs ~/.android/adbkey from a previous adb run)

The first build creates `app/keys/naglab.keystore`. Keep it: an APK signed with a new key can't
update the installed app, so you would have to uninstall first. For the same reason, your own
build can't update over the release APK.

App icon and button art are rendered from `app/art/*.svg` by `app/art/render.sh` (needs
cairosvg). The PNGs are committed, so a normal build doesn't need it.

## Layout

    app/src/au/jaso/naglab/
      NagService.java    foreground service: keeps the picked nags off, kill/restore, quick button host
      DmsClient.java     raw binder client for BYD's DMS settings service
      AdasClient.java    raw binder client for lane assist, speed-limit chime, child detection
      QuickButton.java   the floating button (overlay window), result card, status dot
      MainActivity.java  the landscape app screen
      Toggle.java        the big hand-drawn switch
      NagListener.java   notification-access revive hook (reads nothing)
      AdbLocal.java      minimal ADB client (TCP, RSA auth), pure Java
      HelperSetup.java   grants + uploads + starts watchdog.sh over local ADB
      HelperScript.java  the shell commands it sends (unit-tested on the Mac)
      HelperPing.java    heartbeat receiver from the watchdog
      BootReceiver.java  restart after boot / app update
    app/watchdog.sh      shell-uid watchdog (restarts the app, power-on kick, Overdrive wake)
    app/build.sh         aapt2 + javac + d8 + zipalign + apksigner (no Gradle)
    app/install.sh       build + adb install + grants + start watchdog
    app/test/run.sh      Mac tests: ADB key format, signature, handshake against a mock adbd,
                         script upload/start logic, grant commands
    app/art/             SVG sources for the icon and the quick button
    tools/get-tools.sh   fetches the SDK pieces (not committed)

## The binder interfaces

**DMS.** Bind `com.ts.appservice.settings/.SettingsService`, action
`com.ts.lib.settings.ISettingsAdapterService`, transact 2 `getSettingsAdapterService("dms")`.
Then on `com.ts.lib.settings.dms.IDmsSettingsService` the calls are: 2 `setDmsSwtichStatus`
(sic), 4 `setFatigueDetect`, 5 `setAttentionDetect`, 6 `getDmsSwitchChanged`,
7 `queryFatigueDetect`, 8 `queryAttentionDetect`, 11 `subscribe`, 12 `unsubscribe`. Callback
`IDmsSettingsCallback`: 1 dms, 2 fatigue, 3 attention. 0 = off, 1 = on. Turning only the master
off leaves fatigue and attention at 1, so all three are set. The reset to on happens on the
DMS box / MCU side, at power level 0 -> 2.

**Lane assist, chime, child detection.** Bind `com.ts.appservice.caradapter/.CarAdapterService`,
action `com.ts.lib.caradapter.ICarAdapterService`, transact 2 `getCarAdapterService("assist")`,
then `com.ts.lib.caradapter.assist.ICarAssistAdapterService`. **Read and write codes differ.**

| Nag | Read | Write |
|---|---|---|
| Lane assist | 11: 0 Off, 1 Warning, 2 Prevent, 3 Both | 12: 1 Off, 2 Warning, 5 Prevent, 4 Both (writing 0 is ignored) |
| Speed-limit change tone | 64: 0 on, 1 off | 65: 2 on, 1 off |
| Over-limit warning | 71 / 72 (two buses): 1 on | 22: 2 on, 1 off |
| Child detection | 187: 1 Standard, 3 Delay, 2 Off | 185: same codes |

The write replies don't tell you whether the car took the change, so Nag Killer always reads
the value back. Codes 837/845 on the "setting" sub-binder look like child detection, but
they're a different feature (cockpit mobile monitoring).

Logs: `adb logcat -s NagLab:W` (the ROM drops INFO). Watchdog log:
`/data/local/tmp/naglab_watchdog.log`.

## Caveats

Use at your own risk. Driver monitoring and child detection exist for a reason. This is for
people who have decided they don't want them and are tired of the car ignoring that decision.
Child detection is never in the quick button unless you add it.

MIT.
