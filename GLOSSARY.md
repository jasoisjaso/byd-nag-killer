# Nag Killer

An app on the car's own screen that keeps BYD's Driver Monitoring switched off, plus the paid route for owners who don't want to install it by hand.

## Product

**Nag Killer**:
The Android app that runs on the car and keeps the nags the owner picked off (DMS first). Free and open source.
_Avoid_: NagLab (old spike name, still the package id), the app, the APK

**DMS**:
BYD's driver monitoring camera (master, fatigue and distraction switches) that nags the driver and resets to on at every start.
_Avoid_: driver attention, DAW (and "nag" when you mean DMS only)

**Nag**:
One car feature Nag Killer can switch off and keep off: DMS, lane assist, the speed-limit chime (tone plus speed-limit warning), child detection. Never braking (AEB) or steering beyond the lane-assist mode the driver can pick on the car's own quick tile.
_Avoid_: alert, ADAS feature (too broad)

**Killed**:
A nag the owner switched on in the app's "Keep off" column: switched off now, kept off at every start, and put back to its remembered mode when switched back.
_Avoid_: disabled, blocked

**Quick button**:
The floating button over CarPlay and other apps. Tap = kill the nags ticked for it (never turns one back on). Hidden while the app screen is open.
_Avoid_: overlay, bubble, FAB

**Watchdog**:
The small script that runs as the ADB shell user and revives Nag Killer after BYD force-stops it.
_Avoid_: helper script, daemon

## Selling

**Installer**:
The paid one-click program that puts Nag Killer and its Watchdog on a car from a computer. What a Buyer pays for.
_Avoid_: kit, setup tool, Kit command (that's the separate byd-sealion7 project)

**Signed Build**:
The ready-made Nag Killer app signed with the project key and delivered inside the Installer, so updates install over the top.
_Avoid_: release APK, binary

**Manual Install**:
The free route: build or download the app and run adb yourself, as described in the README.
_Avoid_: DIY install, free version

**Buyer**:
A person who paid for the Installer.
_Avoid_: customer, user, owner (an owner may use the Manual Install and never be a Buyer)

**Supported Car**:
A car and firmware combination Nag Killer has been proven on. At launch: the Australian BYD Sealion 7 (DiLink 5, Android 11) only.
_Avoid_: compatible car, works on

**Car Check**:
The test that a car is a Supported Car before anything is installed or switched, run by the Installer and again by Nag Killer on every start. A car that fails is left untouched.
_Avoid_: compatibility check, detection

**Unsupported Car**:
Any car that fails the Car Check, including a Supported Car model whose firmware has not been proven.
_Avoid_: incompatible car
