# First-time install over adb (USB). Later updates go over Wi-Fi with tools\r2.ps1.
#   install : disable stock app, put R2Agent in /system/priv-app, install R2Main, reboot
#   refresh-agent : replace the /system/priv-app agent (the recovery copy) with apps\out\R2Agent.apk and
#                   reboot; needed when the agent asks for a new system permission (e.g. SHUTDOWN)
#   root-helper : install or update R2's root helper (apps\agent\system) and start it now, no reboot. The agent
#                 needs it to switch adb over Wi-Fi off, set up the firewall and fix device nodes: R2's own su
#                 doesn't work. install and refresh-agent include it.
#   restore : undo everything -> factory software (stock app back, agent and main removed, adb on USB)
#   Works over USB or Wi-Fi adb (tools\r2.ps1 adb first).
# Usage: powershell -ExecutionPolicy Bypass -File tools\install-on-robot.ps1 install|restore
param([Parameter(Mandatory = $true)][ValidateSet('install', 'refresh-agent', 'root-helper', 'restore')][string]$Action)
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

# R2's root helper: r2-rootd.sh, started as root at every boot by install-recovery.sh (Android's flash_recovery
# service). Expects /system mounted read-write. Line endings are normalised so a Windows checkout works too.
function Install-RootHelper {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) 'r2-root-helper'
    New-Item -ItemType Directory -Force $tmp | Out-Null
    foreach ($n in 'r2-rootd.sh', 'install-recovery.sh') {
        $text = [IO.File]::ReadAllText((Join-Path $root "apps\agent\system\$n")) -replace "`r`n", "`n"
        [IO.File]::WriteAllText((Join-Path $tmp $n), $text, (New-Object Text.UTF8Encoding $false))
    }
    & $adb push (Join-Path $tmp 'r2-rootd.sh') /system/bin/r2-rootd.sh | Out-Null
    & $adb push (Join-Path $tmp 'install-recovery.sh') /system/etc/install-recovery.sh | Out-Null
    Sh 'chown root.shell /system/bin/r2-rootd.sh /system/etc/install-recovery.sh; chmod 750 /system/bin/r2-rootd.sh /system/etc/install-recovery.sh' | Out-Null
}

function Start-RootHelper {
    # A running copy keeps the old script; stop it, then start the new one the way the boot does.
    Sh 'for p in /proc/[0-9]*; do grep -q [r]2-rootd $p/cmdline 2>/dev/null && kill ${p#/proc/}; done; /system/etc/install-recovery.sh' | Out-Null
}
if ($Action -eq 'install') {
    foreach ($f in 'apps\out\R2Agent.apk', 'apps\out\R2Main.apk', 'backup\mmcblk0_full.img') {
        if (-not (Test-Path (Join-Path $root $f))) { throw "missing $f" }
    }
    Sh 'pm disable com.bullb.r2d2_nanopisystem' | Out-Null
    Sh 'mount -o remount,rw /system' | Out-Null
    & $adb push (Join-Path $root 'apps\out\R2Agent.apk') /system/priv-app/R2Agent.apk
    Sh 'chmod 644 /system/priv-app/R2Agent.apk' | Out-Null
    Install-RootHelper
    Sh 'sync; mount -o remount,ro /system' | Out-Null
    & $adb install -r (Join-Path $root 'apps\out\R2Main.apk')
    # adb over Wi-Fi is off by default from now on: the agent switches it on only when asked
    # (tools\r2.ps1 adb, for 30 minutes), because this build's adb is a root shell with no key check.
    Sh 'setprop persist.adb.tcp.port -1' | Out-Null
    Write-Host 'Rebooting. Give it ~60 s, then: tools\r2.ps1 status ; tools\r2.ps1 adb (switches Wi-Fi adb on for 30 min)'
    Write-Host "R2's hotspot gets its own password now. To see it, press R2's pairing button, join the hotspot with"
    Write-Host 'the factory password (it is back for 2 minutes), and open http://192.168.43.1:7700/ (Show hotspot password).'
    & $adb reboot
}
elseif ($Action -eq 'refresh-agent') {
    if (-not (Test-Path (Join-Path $root 'apps\out\R2Agent.apk'))) { throw 'missing apps\out\R2Agent.apk' }
    Sh 'mount -o remount,rw /system' | Out-Null
    & $adb push (Join-Path $root 'apps\out\R2Agent.apk') /system/priv-app/R2Agent.apk
    Sh 'chmod 644 /system/priv-app/R2Agent.apk' | Out-Null
    Install-RootHelper
    Sh 'sync; mount -o remount,ro /system' | Out-Null
    # On boot Android sees the system copy is newer than the over-the-air copy in /data, drops that copy
    # and keeps the agent's data (token, last good build).
    Write-Host 'Rebooting. Give it ~60 s, then: tools\r2.ps1 status'
    & $adb reboot
}
elseif ($Action -eq 'root-helper') {
    Sh 'mount -o remount,rw /system' | Out-Null
    Install-RootHelper
    Sh 'sync; mount -o remount,ro /system' | Out-Null
    Start-RootHelper
    Write-Host 'Root helper installed and started. It closes adb over Wi-Fi at every boot unless adb-always is set;'
    Write-Host 'this adb connection stays up until something switches adb off (tools\r2.ps1 adb-off, or a reboot).'
}
else {
    Sh 'pm uninstall dev.r2hack.main' | Out-Null
    Sh 'pm uninstall dev.r2hack.agent' | Out-Null   # removes an over-the-air agent update, if any
    Sh 'mount -o remount,rw /system' | Out-Null
    Sh 'rm /system/priv-app/R2Agent.apk' | Out-Null
    Sh 'rm -f /system/bin/r2-rootd.sh /system/etc/install-recovery.sh' | Out-Null
    Sh 'sync; mount -o remount,ro /system' | Out-Null
    Sh 'pm enable com.bullb.r2d2_nanopisystem' | Out-Null
    Sh 'setprop persist.adb.tcp.port 0' | Out-Null   # adb back on USB after the reboot
    Write-Host 'Factory software restored. Rebooting.'
    & $adb reboot
}
