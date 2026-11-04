# First-time install over adb (USB). Later updates go over Wi-Fi with tools\r2.ps1.
#   install : disable stock app, put R2Agent in /system/priv-app, install R2Main, reboot
#   refresh-agent : replace the /system/priv-app agent (the recovery copy) with apps\out\R2Agent.apk and
#                   reboot; needed when the agent asks for a new system permission (e.g. SHUTDOWN)
#   restore : undo everything -> factory software (stock app back, agent and main removed, adb on USB)
#   Works over USB or Wi-Fi adb (tools\r2.ps1 adb first).
# Usage: powershell -ExecutionPolicy Bypass -File tools\install-on-robot.ps1 install|restore
param([Parameter(Mandatory = $true)][ValidateSet('install', 'refresh-agent', 'restore')][string]$Action)
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
    # adb over Wi-Fi is off by default from now on: the agent switches it on only when asked
    # (tools\r2.ps1 adb, for 30 minutes), because this build's adb is a root shell with no key check.
    Sh 'setprop persist.adb.tcp.port -1' | Out-Null
    Write-Host 'Rebooting. Give it ~60 s, then: tools\r2.ps1 status ; tools\r2.ps1 adb (switches Wi-Fi adb on for 30 min)'
    & $adb reboot
}
elseif ($Action -eq 'refresh-agent') {
    if (-not (Test-Path (Join-Path $root 'apps\out\R2Agent.apk'))) { throw 'missing apps\out\R2Agent.apk' }
    Sh 'mount -o remount,rw /system' | Out-Null
    & $adb push (Join-Path $root 'apps\out\R2Agent.apk') /system/priv-app/R2Agent.apk
    Sh 'chmod 644 /system/priv-app/R2Agent.apk' | Out-Null
    Sh 'sync; mount -o remount,ro /system' | Out-Null
    # On boot Android sees the system copy is newer than the over-the-air copy in /data, drops that copy
    # and keeps the agent's data (token, last good build).
    Write-Host 'Rebooting. Give it ~60 s, then: tools2.ps1 status'
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
