# Nag Killer: which BYD cars could it work on? (2026-10-18)

Static analysis only: the app source, the research notes in byd-sealion7/docs/research, and the
system APKs pulled from the AU Sealion 7 (byd-system/; jadx decompiles in
a local scratch folder). Nothing has been tested on any other car.

## 1. Car- and firmware-specific values in the code

| Where | Value | Depends on |
|---|---|---|
| DmsClient | bind `com.ts.appservice.settings` / `com.ts.appservice.settings.SettingsService`, action `com.ts.lib.settings.ISettingsAdapterService` | `/system/app/SettingsService` (ts platform, sharedUserId system) |
| DmsClient | adapter transact **2** = `getSettingsAdapterService("dms")` | AIDL method order in ts-framework.jar |
| DmsClient | `com.ts.lib.settings.dms.IDmsSettingsService` codes 2/4/5/6/7/8/11/12; callback `IDmsSettingsCallback` codes 1/2/3 | AIDL order. Unversioned: if BYD adds or reorders a method, the same code calls a different method with no error |
| DmsClient | values 0 = off, 1 = on, master gates fatigue and attention | IDmsSettingServiceBinder -> `com.arcsoft.dmssocketserver.DmsSocketServer` (system shared library `arcsoft.dms.socket`) |
| NagService | re-check after ACC on (10 s poll for 3 min, then 60 s) | the DMS box / MCU resets the switches at power level 0->2 |
| watchdog.sh | logcat tag `AbsBYDAutoBodyworkListener`, line `onPowerLevelChanged level: 2 --- lastPowerLevel: 0` | text BydCarSystemUI writes to the log |
| watchdog.sh / HelperSetup / install.sh | `/data/local/tmp/naglab_watchdog.{sh,pid,log,disabled}`, `naglab_lastpoweron` | shell uid able to write there and run `setsid`, `pidof`, `am`, `logcat` |
| watchdog premise | shell-uid processes survive ACC off; `CarPowerBinder.forceStopThirdApp()` only kills non-system apps | com.ts.appservice.power (CarPowerOnStandbyState) |
| HelperSetup / AdbLocal | `127.0.0.1:5555`, legacy TCP adbd; key label `naglab@sealion7` (just a label) | Wireless ADB toggle in the hidden menu |
| watchdog.sh | Overdrive: `com.overdrive.app/.receiver.BootReceiver`, `com.byd.action.ACC_ON`, `/data/local/tmp/overdrive_parked_shutdown` | optional; does nothing if Overdrive is absent |
| Manifest | minSdk 29, targetSdk 28 (avoids Android 11 package-visibility filtering); BYDAUTO_* permissions (normal on this car, unused so far) | Android 10+ |
| Logging | everything at Log.w | ROM has `persist.log.tag=W` |

Sealion 7 reference: build RQ3A.211001.001 test-keys, Android 11, Snapdragon 8155 (DiLink 5),
carType 208, BydCarSettings 1.18.2-202511121542, SettingsService minSdk 30.

## 2. Evidence for other models in the same firmware

* **BydMyCar `carlib.zr.a()`** maps carType to a 3D car model folder. This image ships exactly six:
  `123 -> MR`, `143 -> PA_RTL`, `180 -> MC`, `182 -> UKE`, `196 -> ST`, `208 -> UKE_RTL`.
  The Sealion 7 is 208 = UKE_RTL. Reading `_RTL` as right-hand drive (AU) and 182 = UKE as the
  left-hand-drive Sealion 7 is an **inference**: no APK names a model. The other codes are
  separate BYD platforms that share this head-unit image. The APKs do not say which models they are.
* **BydCarSettings DMS gate** (`z0.c.e()` / `a0.e.a()`): the DMS screen appears only when
  carType is one of **196, 182, 208, 60, 123, 143** and the props
  `persist.vendor.bosch.cfg.supplies.dms.{camera.isp,name,camera.lens}` are not `-1`. These are
  the car types where this firmware expects a DMS camera. 180 (MC) is in MyCar but is **not** on
  the DMS list. 60 is named in the bodywork constants as `AUTO_TYPE_MRE` / `AUTO_TYPE_STEM`, and
  196 as `AUTO_TYPE_STEBU`. 208, 182, 143 and 123 are unnamed (the constant table stops at 178).
* carType special cases: 196 uses a different cockpit-perception getter and setter than 182/208.
  143 with `pa_dms_num` gets attention forced to 0 in the reset-to-defaults code. Neither affects
  the SettingsService path Nag Killer uses.
* Package names hint at the platform: `com.stingray.karaoke.car.byd8155f` (8155 platform) and the
  `com.ts.*` service family (ts-framework, CarAdapterService, CarPowerService). Overdrive issue
  #307 and CRCinAU/abrp-telemetry-sl7 report the same `com.ts.*` services on Sealion 7 cars.
* Not shared: DiLink 3 cars (Atto 3, Dolphin, older Seal; msm8953 / DiLink 13.x) are a
  different platform. The research found nothing that suggests `com.ts.appservice.settings`
  exists on them. wheregoes/byd-apps notes DiLink 3 and DiLink 5 are incompatible.

**Plausible candidates, in order:** (1) any Sealion 7 on this DiLink 5 / 8155 image: LHD EU/CN
(carType 182) and RHD AU/UK/NZ/JP (208), on other software versions; (2) the other carTypes in
this image with DMS (196 ST, 123 MR, 143 PA, 60), if they use the same ArcSoft DMS box;
(3) other DiLink 5 / 8155 BYD and Denza cars, if `pm path com.ts.appservice.settings` returns a path
and SettingsService links `arcsoft.dms.socket`. Everything else: assume no.

Quick check on a candidate car (read-only, over ADB):
`pm path com.ts.appservice.settings`, `getprop | grep dms`, `dumpsys package com.ts.appservice.settings | grep -i library`,
and logcat for `isHasDms carType :` when opening the DMS settings screen.

## 3. What a buyer needs

* A BYD head unit that allows sideloading through **wireless ADB** on `IP:5555`. On the Sealion 7
  (SW 1.2.x to 3.3.0): Settings > System > Version, tap the white "Factory reset" text 7 times,
  then tap "CONNECT USB TO ENABLE DEBUGGING MODE" (do NOT confirm a reset). Other models use the
  dialer code `*#91532547#*` plus an IMEI-derived password, or have had ADB removed (Seal after
  ~2310, Atto 3). Some firmware shows "adb install be disabled"; toggling debugging off and on fixed it.
* A computer on the same Wi-Fi with platform-tools, once, to `adb install` (the car blocks
  installs started from its own screen). A single arm-compatible APK, minSdk 29 (Android 10+).
* Tap "Allow USB debugging" for the computer, and again for the app's own key when using the in-car helper.
* The **watchdog** (shell uid) must be running, or the app is force-stopped at the first ACC off
  and stays dead. It survives ACC off/on but **not a head-unit reboot**. After a reboot: turn
  Wireless ADB on again (the car turns it off after sitting parked), open Nag Killer and tap "Start
  background helper", or re-push from the computer. The app retries on its own every 10 min, but
  only if ADB is on and its key is already trusted. The in-car helper start (0.5) has not been tried on a car yet.
* The car must not apply an OTA that removes ADB or the exported, unchecked SettingsService.

## 4. On an unsupported car: silent vs loud failures

**Loud (install fails, or the app shows an obvious fault):**
* Android below 10: `INSTALL_FAILED_OLDER_SDK`.
* No wireless ADB, or installs blocked: `adb connect` fails, "adb install be disabled", or "not passed safety checks".
* `com.ts.appservice.settings` missing: bindService returns false. The screen stays on
  "Connecting to the car..." forever (only logcat says `bindService -> false`). There is no
  explicit "unsupported car" message.
* SettingsService present but without a "dms" adapter: `getSettingsAdapterService(dms) returned null`.
  Same endless "Connecting..." screen.
* Bind refused (a future firmware adds a permission or caller check): SecurityException is
  logged, and the screen stays on "Connecting...".
* Watchdog cannot start (no ADB): the screen shows "Background helper: NOT running".

**Silent (looks fine, does nothing or the wrong thing):**
* SettingsService binds but the car has no ArcSoft DMS box, or a different supplier:
  setters return normally, no callbacks arrive, and the status shows "?". `fixCount` counts
  a successful transact as a fix even if nothing changed.
* AIDL methods reordered in other firmware: codes 2/4/5 could call different
  methods (e.g. setDmsMode sets "sensitive" or "factory" instead) with no error. Callback codes 1/2/3
  could map to the wrong switch. This is the most dangerous case.
* A different reset trigger (e.g. a mid-drive reset, or a reset not tied to power level 0->2): only the 60 s poll catches it.
* SystemUI log text or tag differs: the watchdog's power-on kick never fires. NagService still
  recovers through the 5 s pidof loop and its 10 s/60 s polls, so DMS turns off later than the usual ~4 s.
* `forceStopThirdApp` also kills shell processes, or `/data/local/tmp` is not writable or
  executable: the app dies at each ACC off. The screen only shows it after the 6-minute heartbeat timeout.
* `persist.log.tag` is different: no functional effect (logs are already at WARN).
* BYD "Disable background Apps" turned ON for Nag Killer disables BootReceiver. The watchdog still revives the service.
