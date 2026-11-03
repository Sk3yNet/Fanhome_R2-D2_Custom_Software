# First-time install over adb (USB). Later updates go over Wi-Fi with tools\r2.ps1.
#   install : disable stock app, put R2Agent in /system/priv-app, install R2Main, reboot
#   restore : undo everything -> factory software (stock app back, agent and main removed, adb on USB)
#   Works over USB or Wi-Fi adb (tools\r2.ps1 adb first).
# Usage: powershell -ExecutionPolicy Bypass -File tools\install-on-robot.ps1 install|restore
param([Parameter(Mandatory = $true)][ValidateSet('install', 'restore')][string]$Action)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
$adb = Join-Path $PSScriptRoot 'platform-tools\adb.exe'

function Sh([string]$cmd) {
    $out = & $adb shell $cmd
    Write-Host "  > $cmd"; if ($out) { $out | ForEach-Object { Write-Host "    $_" } }
    return ($out -join "`n")
}

if (-not ((& $adb devices) -match "`tdevice")) { throw 'No adb device. Connect the robot over USB first.' }
if ((Sh 'id') -notmatch 'uid=0') { throw 'adb is not running as root on the robot.' }

if ($Action -eq 'install') {
    foreach ($f in 'apps\out\R2Agent.apk', 'apps\out\R2Main.apk', 'backup\mmcblk0_full.img') {
        if (-not (Test-Path (Join-Path $root $f))) { throw "missing $f" }
    }
    Sh 'pm disable com.bullb.r2d2_nanopisystem' | Out-Null
    Sh 'mount -o remount,rw /system' | Out-Null
    & $adb push (Join-Path $root 'apps\out\R2Agent.apk') /system/priv-app/R2Agent.apk
    Sh 'chmod 644 /system/priv-app/R2Agent.apk' | Out-Null
    Sh 'sync; mount -o remount,ro /system' | Out-Null
    & $adb install -r (Join-Path $root 'apps\out\R2Main.apk')
    # Root adb over Wi-Fi from the next boot. On KitKat adbd then listens on TCP *instead of* USB;
    # `adb usb` switches back until the next reboot, `setprop persist.adb.tcp.port 0` (serial console) for good.
    Sh 'setprop persist.adb.tcp.port 5555' | Out-Null
    if ((Sh 'getprop ro.adb.secure').Trim() -ne '1') {
        Write-Host 'Note: adb has no key check on this build; anyone on the same Wi-Fi can get a root shell on port 5555.'
    }
    Write-Host 'Rebooting. Give it ~60 s, then: tools\r2.ps1 status ; tools\r2.ps1 adb'
    & $adb reboot
}
else {
    Sh 'pm uninstall dev.r2hack.main' | Out-Null
    Sh 'pm uninstall dev.r2hack.agent' | Out-Null   # removes an over-the-air agent update, if any
    Sh 'mount -o remount,rw /system' | Out-Null
    Sh 'rm /system/priv-app/R2Agent.apk' | Out-Null
    Sh 'sync; mount -o remount,ro /system' | Out-Null
    Sh 'pm enable com.bullb.r2d2_nanopisystem' | Out-Null
    Sh 'setprop persist.adb.tcp.port 0' | Out-Null   # adb back on USB after the reboot
    Write-Host 'Factory software restored. Rebooting.'
    & $adb reboot
}
