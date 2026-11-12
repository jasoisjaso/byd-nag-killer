# Nag Killer installer for Windows. Run it by double-clicking install-windows.bat.
# It downloads Google's adb and the latest Nag Killer, finds the car on your Wi-Fi, installs
# the app and sets it up. Everything it downloads goes in a "nag-killer-install" folder next to
# this file, which you can delete afterwards.
#
#   $env:NAGKILLER_APK = "C:\path\naglab.apk"   install this file instead of the latest release
#   $env:NAGKILLER_IP  = "192.168.1.50"         skip the search and use this address
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$Repo = "jasoisjaso/byd-nag-killer"
$Pkg = "au.jaso.naglab"
$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$Work = Join-Path $Here "nag-killer-install"
New-Item -ItemType Directory -Force -Path $Work | Out-Null

function Stop-Install($msg) {
    Write-Host ""
    Write-Host "STOPPED: $msg" -ForegroundColor Yellow
    Write-Host ""
    Read-Host "Press Enter to close" | Out-Null
    exit 1
}

Write-Host "Nag Killer installer"
Write-Host "===================="
Write-Host "Before you start, in the car (parked, on the same Wi-Fi as this computer):"
Write-Host "  Settings > System > Version. Tap the white words `"Factory reset`" 7 times"
Write-Host "  (NOT the blue Reset button), turn the screen upright, and tap"
Write-Host "  `"CONNECT USB TO ENABLE DEBUGGING MODE`". The README has this step with pictures."

# ---- 1. adb (Google's platform-tools)
$Adb = Join-Path $Work "platform-tools\adb.exe"
$onPath = Get-Command adb.exe -ErrorAction SilentlyContinue
if ($onPath) { $Adb = $onPath.Source }
elseif (-not (Test-Path $Adb)) {
    Write-Host ""
    Write-Host "Downloading Google's adb tool..."
    $zip = Join-Path $Work "platform-tools.zip"
    try { Invoke-WebRequest -UseBasicParsing -Uri "https://dl.google.com/android/repository/platform-tools-latest-windows.zip" -OutFile $zip }
    catch { Stop-Install "Couldn't download adb. Check this computer's internet connection." }
    Expand-Archive -Force -Path $zip -DestinationPath $Work
}
if (-not (Test-Path $Adb)) { Stop-Install "adb is missing." }

# ---- 2. the app
$Apk = $env:NAGKILLER_APK
if (-not $Apk -and (Test-Path (Join-Path $Here "naglab.apk"))) { $Apk = Join-Path $Here "naglab.apk" }
if (-not $Apk) {
    $Apk = Join-Path $Work "naglab.apk"
    Write-Host ""
    Write-Host "Downloading the latest Nag Killer..."
    try { Invoke-WebRequest -UseBasicParsing -Uri "https://github.com/$Repo/releases/latest/download/naglab.apk" -OutFile $Apk }
    catch { Stop-Install "Couldn't download Nag Killer from GitHub." }
}
if (-not (Test-Path $Apk) -or (Get-Item $Apk).Length -eq 0) { Stop-Install "The app file is missing or empty: $Apk" }
$Wd = Join-Path $Here "naglab_watchdog.sh"
if (-not (Test-Path $Wd)) {
    $Wd = Join-Path $Work "naglab_watchdog.sh"
    try { Invoke-WebRequest -UseBasicParsing -Uri "https://github.com/$Repo/releases/latest/download/naglab_watchdog.sh" -OutFile $Wd }
    catch { $Wd = $null }
}

# ---- 3. find the car: anything on this Wi-Fi answering on the debugging port
function Test-Port($ip) {
    $c = New-Object Net.Sockets.TcpClient
    try {
        $r = $c.BeginConnect($ip, 5555, $null, $null)
        if ($r.AsyncWaitHandle.WaitOne(1000) -and $c.Connected) { return $true }
        return $false
    } catch { return $false } finally { $c.Close() }
}
function Find-Cars {
    $found = @()
    $mine = [Net.Dns]::GetHostAddresses([Net.Dns]::GetHostName()) |
        Where-Object { $_.AddressFamily -eq "InterNetwork" -and -not $_.ToString().StartsWith("127.") -and -not $_.ToString().StartsWith("169.254.") }
    foreach ($a in $mine) {
        $parts = $a.ToString().Split(".")
        $base = "$($parts[0]).$($parts[1]).$($parts[2])"
        # start all 254 connections at once, then see which ones opened
        $tries = @()
        foreach ($i in 1..254) {
            $c = New-Object Net.Sockets.TcpClient
            try { $tries += ,@("$base.$i", $c, $c.BeginConnect("$base.$i", 5555, $null, $null)) } catch { $c.Close() }
        }
        Start-Sleep -Milliseconds 1500
        foreach ($t in $tries) {
            if ($t[2].IsCompleted -and $t[1].Connected) { $found += $t[0] }
            $t[1].Close()
        }
    }
    return $found | Sort-Object -Unique
}
$Ip = $env:NAGKILLER_IP
while (-not $Ip) {
    Write-Host ""
    Write-Host "Looking for the car on your Wi-Fi (a few seconds)..."
    $found = @(Find-Cars)
    if ($found.Count -eq 1) { $Ip = $found[0]; Write-Host "Found it at $Ip." }
    elseif ($found.Count -gt 1) {
        Write-Host "More than one device answered:"
        $found | ForEach-Object { Write-Host "  $_" }
        $Ip = Read-Host "Type the car's address (Settings > Wi-Fi > your network on the car)"
    } else {
        Write-Host "Didn't find the car. Check debugging is on and the car is on the same Wi-Fi as this computer."
        $Ip = Read-Host "Press Enter to look again, or type the car's address (e.g. 192.168.1.50)"
    }
}
$Ser = "${Ip}:5555"
if (-not (Test-Port $Ip)) { Stop-Install "Nothing is answering at $Ip. Is debugging on in the car, and is it on this Wi-Fi?" }

# adb writes ordinary messages to stderr; don't let that stop the script
$ErrorActionPreference = "Continue"
function Adb { & $Adb -s $Ser @args 2>&1 | ForEach-Object { "$_" } }

# ---- 4. connect, and wait for "Allow USB debugging?" on the car
& $Adb disconnect $Ser 2>&1 | Out-Null
& $Adb connect $Ser 2>&1 | Out-Null
$told = $false
$state = ""
foreach ($n in 1..60) {
    $state = (Adb get-state | Select-Object -First 1)
    if ($state -eq "device") { break }
    if (-not $told) {
        Write-Host ""
        Write-Host "Look at the car's screen: when it asks `"Allow USB debugging?`", tick `"Always allow`" and tap Allow."
        $told = $true
    }
    Start-Sleep -Seconds 2
    & $Adb connect $Ser 2>&1 | Out-Null
}
if ($state -ne "device") { Stop-Install "The car didn't allow the connection. Run this again and tap Allow on the car's screen." }

# ---- 5. make sure it's a BYD screen before installing anything
$pkgs = (Adb shell pm list packages) -join "`n"
if ($pkgs -notmatch "(?i)byd") {
    $model = (Adb shell getprop ro.product.model | Select-Object -First 1)
    Write-Host "The device at $Ip (`"$model`") doesn't look like a BYD car screen."
    if ((Read-Host "Install anyway? Type yes to go on") -ne "yes") { Stop-Install "Nothing was installed." }
}

# ---- 6. install and set up
Write-Host ""
Write-Host "Installing Nag Killer on the car..."
$out = (Adb install -r $Apk) -join "`n"
if ($out -notmatch "Success") {
    Write-Host $out
    if ($out -match "VERSION_DOWNGRADE") { Stop-Install "The car has a newer version than this one. To go back, remove it first:  `"$Adb`" -s $Ser uninstall $Pkg  (this clears its settings), then run this again." }
    elseif ($out -match "UPDATE_INCOMPATIBLE|SIGNATURES") { Stop-Install "The car has a copy from a different source. Remove it first:  `"$Adb`" -s $Ser uninstall $Pkg  then run this again." }
    else { Stop-Install "The install failed (the message is above)." }
}
# lets the system bring the app back after the car shuts it down
Adb shell cmd notification allow_listener "$Pkg/$Pkg.NagListener" | Out-Null
# lets the floating button show over other apps
Adb shell appops set $Pkg SYSTEM_ALERT_WINDOW allow | Out-Null
# background helper (brings the app back within seconds of a restart)
if ($Wd -and (Test-Path $Wd)) {
    Adb push $Wd /data/local/tmp/naglab_watchdog.sh | Out-Null
    $remote = 'D=/data/local/tmp; S=$D/naglab_watchdog.sh; chmod 755 $S; rm -f $D/naglab_watchdog.disabled; ' +
        'P=$(cat $D/naglab_watchdog.pid 2>/dev/null); ' +
        'if [ -n "$P" ] && kill -0 "$P" 2>/dev/null && grep -q naglab_watchdog /proc/$P/cmdline 2>/dev/null; then ' +
        'kill -- "-$P" 2>/dev/null || kill "$P" 2>/dev/null; fi; ' +
        'rm -f $D/naglab_watchdog.pid; setsid nohup $S >/dev/null 2>&1 </dev/null &'
    Adb shell $remote | Out-Null
}
Adb shell am start -n "$Pkg/.MainActivity" | Out-Null
$ver = ""
$line = (Adb shell dumpsys package $Pkg) | Where-Object { $_ -match "versionName=" } | Select-Object -First 1
if ($line) { $ver = ($line -split "=")[1].Trim() }

Write-Host ""
Write-Host "Done. Nag Killer $ver is installed and open on the car's screen." -ForegroundColor Green
Write-Host "Pick the nags you want kept off in the car. You don't need this computer again."
Write-Host "You can delete the `"nag-killer-install`" folder."
Write-Host ""
Read-Host "Press Enter to close" | Out-Null
