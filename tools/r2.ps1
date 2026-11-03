# Talk to the R2 Agent over Wi-Fi.
# Usage: powershell -ExecutionPolicy Bypass -File tools\r2.ps1 <command> [arg] [-Robot <ip>]
#   find                     listen for the robot's UDP announcement (port 7702)
#   status | log             agent state / agent event log
#   logcat [text] [-Lines N] Android log (all apps, incl. main-app crash traces), optionally only lines containing text
#   dmesg [text] [-Lines N]  kernel log
#   adb                      connect adb over Wi-Fi (root shell: tools\platform-tools\adb shell)
#   push-main [apk]          install a main-app build (default apps\out\R2Main.apk), on probation
#   push-agent [apk]         update the agent itself
#   rollback | restart       main app: reinstall last good build / restart it
#   serial '<json>'          send one command to the controller, e.g. '{"cmd":"mode","mode":13}' (flash front LCD)
#   safe-on | safe-off       stop / resume supervising the main app
#   wifi-station | wifi-ap   switch network mode
#   stock-on | stock-off     re-enable / disable the original Fanhome app (takes effect after reboot)
#   reboot
param(
    [Parameter(Position = 0, Mandatory = $true)][string]$Command,
    [Parameter(Position = 1)][string]$Arg,
    [string]$Robot,
    [int]$Lines = 500
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
$token = (Get-Content (Join-Path $root 'secrets\agent-token.txt') -Raw).Trim()
$cache = Join-Path $root 'secrets\last-robot-ip.txt'

function Find-Robot([int]$Seconds = 8) {
    $udp = New-Object System.Net.Sockets.UdpClient 7702
    $udp.Client.ReceiveTimeout = 1000
    $ep = New-Object System.Net.IPEndPoint ([System.Net.IPAddress]::Any), 0
    $deadline = (Get-Date).AddSeconds($Seconds)
    try {
        while ((Get-Date) -lt $deadline) {
            try {
                $msg = [Text.Encoding]::UTF8.GetString($udp.Receive([ref]$ep)) | ConvertFrom-Json
                if ($msg.r2agent) { Set-Content $cache $ep.Address.ToString() -NoNewline; return $ep.Address.ToString() }
            } catch [System.Net.Sockets.SocketException] { }
        }
    } finally { $udp.Close() }
    return $null
}

function Get-Robot {
    if ($Robot) { return $Robot }
    foreach ($ip in @((Get-Content $cache -ErrorAction SilentlyContinue), '192.168.43.1')) {
        if (-not $ip) { continue }
        try { Invoke-RestMethod "http://${ip}:7700/ping" -TimeoutSec 2 | Out-Null; return $ip } catch { }
    }
    $found = Find-Robot
    if (-not $found) { throw 'Robot not found. Join the same network as the robot (or its hotspot), or pass -Robot <ip>.' }
    return $found
}

function Call([string]$Method, [string]$Path, $Body, [string]$InFile, [int]$Timeout = 30) {
    $ip = Get-Robot
    $p = @{ Uri = "http://${ip}:7700$Path"; Method = $Method; Headers = @{ 'X-R2-Token' = $token }; TimeoutSec = $Timeout }
    if ($InFile) { $p.InFile = $InFile; $p.ContentType = 'application/vnd.android.package-archive' }
    elseif ($null -ne $Body) { $p.Body = [Text.Encoding]::UTF8.GetBytes($Body); $p.ContentType = 'application/json' }
    Invoke-RestMethod @p
}

switch ($Command) {
    'find'         { $ip = Find-Robot 10; if ($ip) { "robot at $ip" } else { 'no announcement heard' } }
    'status'       { Call GET /status | ConvertTo-Json -Depth 5 }
    'log'          { Call GET /log }
    { $_ -in 'logcat', 'dmesg' } {
        $q = "?lines=$Lines"; if ($Arg) { $q += '&grep=' + [uri]::EscapeDataString($Arg) }
        $out = Call GET "/$Command$q"
        if ($Command -eq 'dmesg' -and $out -match '^klogctl') {
            # This kernel restricts dmesg to root, so read it through root adb instead.
            $adb = Join-Path $PSScriptRoot 'platform-tools\adb.exe'
            $target = "$(Get-Robot):5555"
            & $adb connect $target | Out-Null
            $out = & $adb -s $target shell dmesg
            if ($Arg) { $out = $out | Where-Object { $_ -like "*$Arg*" } }
            $out = $out | Select-Object -Last $Lines
        }
        $out
    }
    'adb'          { & (Join-Path $PSScriptRoot 'platform-tools\adb.exe') connect "$(Get-Robot):5555" }
    'push-main'    { $f = if ($Arg) { $Arg } else { Join-Path $root 'apps\out\R2Main.apk' }; Call POST /main/install -InFile $f -Timeout 300 }
    'push-agent'   { $f = if ($Arg) { $Arg } else { Join-Path $root 'apps\out\R2Agent.apk' }; Call POST /agent/install -InFile $f -Timeout 300 }
    'rollback'     { Call POST /main/rollback -Timeout 120 }
    'restart'      { Call POST /main/restart }
    'serial'       { Call POST /serial $Arg }
    'safe-on'      { Call POST '/safe-mode?on=1' }
    'safe-off'     { Call POST '/safe-mode?on=0' }
    'wifi-station' { Call POST '/wifi?mode=station' }
    'wifi-ap'      { Call POST '/wifi?mode=ap' }
    'stock-on'     { Call POST '/stock?enable=1' }
    'stock-off'    { Call POST '/stock?enable=0' }
    'reboot'       { Call POST /reboot }
    default        { throw "unknown command $Command" }
}
