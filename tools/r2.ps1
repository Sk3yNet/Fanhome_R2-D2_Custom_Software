# Talk to the R2 Agent over Wi-Fi.
# Usage: powershell -ExecutionPolicy Bypass -File tools\r2.ps1 <command> [arg] [-Robot <ip>]
#   find                     listen for the robot's UDP announcement (port 7702)
#   status | log             agent state / agent event log
#   log-previous             agent event log from the previous boot (e.g. after a crash or power loss)
#   action <name>            run a robot action, e.g. dance, wake, lightsaber, leia (list: actions)
#   stop                     emergency stop (the agent stops the robot itself, then cancels the action)
#   volume <0-100>           droid sounds and music volume (kept across reboots)
#   logcat [text] [-Lines N] Android log (all apps, incl. main-app crash traces), optionally only lines containing text
#   dmesg [text] [-Lines N]  kernel log
#   adb [minutes]            switch on adb over Wi-Fi for a while (default 30 min) and connect
#                            (root shell: tools\platform-tools\adb shell). It switches itself off again.
#   adb-off                  switch adb over Wi-Fi off now (adb is back on USB)
#   adb-always               keep adb over Wi-Fi on, also after reboots (trusted networks only)
#   allow-subnets [list]     also accept API clients from these subnets, e.g. 10.0.20.0/24,10.0.30.5
#                            (empty: only the robot's own subnet; no argument: show the list)
#   fetch-token              copy this robot's own API token to secrets\robot-token.txt (in the pairing
#                            button's setup window, or over root adb)
#   push-main [apk]          install a main-app build (default apps\out\R2Main.apk), on probation
#   push-agent [apk]         update the agent itself
#   rollback | restart       main app: reinstall last good build / restart it
#   serial '<json>'          send one command to the controller, e.g. '{"cmd":"mode","mode":13}' (flash front LCD)
#   safe-on | safe-off       stop / resume supervising the main app
#   wifi-station | wifi-ap   switch network mode
#   wifi-scan                networks R2 can see
#   wifi-join <ssid>         join a network (asks for its password; R2 falls back to its hotspot if it fails)
#   import-identity          copy the factory app's robot name, ID and paired phones to R2 Main (over root adb),
#                            so the official phone app still recognizes the robot
#   stock-on | stock-off     re-enable / disable the original Fanhome app (takes effect after reboot)
#   sim-on | sim-off         use the pretend controller instead of the real one (applies after reboot)
#   sim-button <n>           pretend a robot button was pressed (simulator only)
#   reboot | shutdown
param(
    [Parameter(Position = 0, Mandatory = $true)][string]$Command,
    [Parameter(Position = 1)][string]$Arg,
    [string]$Robot,
    [int]$Lines = 500
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
# The robot's own token if fetched, otherwise the development token built into your agent.
$tokenFile = @('secrets\robot-token.txt', 'secrets\agent-token.txt') | ForEach-Object { Join-Path $root $_ } | Where-Object { Test-Path $_ } | Select-Object -First 1
$token = if ($tokenFile) { (Get-Content $tokenFile -Raw).Trim() } else { '' }
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

# adb over Wi-Fi is off by default: ask the agent to switch it on, then connect. Returns the adb target.
function Connect-Adb([int]$Minutes = 30) {
    $ip = Get-Robot
    $r = Call POST "/adb?on=1&minutes=$Minutes"
    Write-Host $r.result
    $adbExe = Join-Path $PSScriptRoot 'platform-tools\adb.exe'
    $target = "${ip}:5555"
    for ($i = 0; $i -lt 5; $i++) {   # adbd restarts on the robot; give it a moment
        $out = & $adbExe connect $target
        if ($out -match 'connected to') { break }
        Start-Sleep -Seconds 1
    }
    $out | Write-Host
    return $target
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
    'log-previous' { Call GET '/log?previous=1' }
    'actions'      { 'stop wake turn-left turn-right turn-around forward circle patrol dance shake-head who-are-you noise not-recognized lightsaber arm short-lcd long-lcd flash-front flash-back leia luke mute unmute pair phone-app-on phone-app-off voice-on voice-off face-on face-off' -split ' ' }
    'action'       { if (-not $Arg) { throw 'usage: r2.ps1 action <name>  (see: r2.ps1 actions)' }; Call POST "/action?name=$([uri]::EscapeDataString($Arg))" }
    'stop'         { Call POST /stop }
    'volume'       { if ($Arg -notmatch '^\d+$') { throw 'usage: r2.ps1 volume <0-100>' }; Call POST "/volume?percent=$Arg" }
    { $_ -in 'logcat', 'dmesg' } {
        $q = "?lines=$Lines"; if ($Arg) { $q += '&grep=' + [uri]::EscapeDataString($Arg) }
        $out = Call GET "/$Command$q"
        if ($Command -eq 'dmesg' -and $out -match '^klogctl') {
            # This kernel restricts dmesg to root, so read it through root adb instead.
            $adb = Join-Path $PSScriptRoot 'platform-tools\adb.exe'
            $target = Connect-Adb
            $out = & $adb -s $target shell dmesg
            if ($Arg) { $out = $out | Where-Object { $_ -like "*$Arg*" } }
            $out = $out | Select-Object -Last $Lines
        }
        $out
    }
    'fetch-token'  {
        # During the pairing button's setup window the agent hands the token out without one;
        # otherwise read it over root adb (which needs a working token or the built-in one).
        $t = ''
        try { $t = (Invoke-RestMethod "http://$(Get-Robot):7700/setup/token" -TimeoutSec 5).token } catch { }
        if (-not $t) {
            $adb = Join-Path $PSScriptRoot 'platform-tools\adb.exe'; $target = Connect-Adb
            $t = (& $adb -s $target shell cat /data/data/dev.r2hack.agent/files/api-token).Trim()
        }
        if ($t -notmatch '^[0-9a-f]{48}$') { throw "no token found (is the agent updated and running?)" }
        New-Item -ItemType Directory -Force (Join-Path $root 'secrets') | Out-Null
        Set-Content (Join-Path $root 'secrets\robot-token.txt') $t -NoNewline -Encoding ascii
        'saved to secrets\robot-token.txt'
    }
    'adb'          { $m = if ($Arg) { [int]$Arg } else { 30 }; Connect-Adb $m | Out-Null }
    'adb-off'      { Call POST '/adb?on=0' }
    'adb-always'   { Call POST '/adb?always=1' }
    'allow-subnets' {
        if ($PSBoundParameters.ContainsKey('Arg')) { Call POST "/clients/allow?subnets=$([uri]::EscapeDataString($Arg))" }
        else { (Call GET /clients/allow).extra_subnets }
    }
    'push-main'    { $f = if ($Arg) { $Arg } else { Join-Path $root 'apps\out\R2Main.apk' }; Call POST /main/install -InFile $f -Timeout 300 }
    'push-agent'   { $f = if ($Arg) { $Arg } else { Join-Path $root 'apps\out\R2Agent.apk' }; Call POST /agent/install -InFile $f -Timeout 300 }
    'rollback'     { Call POST /main/rollback -Timeout 120 }
    'restart'      { Call POST /main/restart }
    'serial'       { Call POST /serial $Arg }
    'safe-on'      { Call POST '/safe-mode?on=1' }
    'safe-off'     { Call POST '/safe-mode?on=0' }
    'wifi-station' { Call POST '/wifi?mode=station' }
    'wifi-scan'    { (Call GET /wifi/scan).networks | Sort-Object rssi -Descending | Format-Table ssid, rssi -AutoSize }
    'wifi-join'    {
        if (-not $Arg) { throw 'usage: r2.ps1 wifi-join <ssid>' }
        $secure = Read-Host "Password for $Arg (Enter for an open network)" -AsSecureString
        $plain = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
        Call POST /wifi/join (@{ ssid = $Arg; password = $plain } | ConvertTo-Json -Compress)
        $plain = $null
    }
    'import-identity' {
        $adb = Join-Path $PSScriptRoot 'platform-tools\adb.exe'; $target = Connect-Adb
        $xmlText = (& $adb -s $target shell cat /data/data/com.bullb.r2d2_nanopisystem/shared_prefs/robot.xml) -join "`n"
        if ($xmlText -notmatch '<map') { throw 'factory app settings not found on the robot' }
        $map = ([xml]$xmlText).map
        $get = { param($n) ($map.string | Where-Object { $_.name -eq $n }).'#text' }
        $clients = & $get 'clientList'
        $body = @{ name = (& $get 'robot_name'); uuid = (& $get 'robot_udid') }
        $json = $body | ConvertTo-Json -Compress
        if ($clients) { $json = $json.TrimEnd('}') + ',"clients":' + $clients + '}' }
        Call POST /identity $json
    }
    'wifi-ap'      { Call POST '/wifi?mode=ap' }
    'stock-on'     { Call POST '/stock?enable=1' }
    'stock-off'    { Call POST '/stock?enable=0' }
    'reboot'       { Call POST /reboot }
    'shutdown'     { Call POST /shutdown }
    'sim-on'       { Call POST '/sim?on=1' }
    'sim-off'      { Call POST '/sim?on=0' }
    'sim-button'   { Call POST "/sim/button?value=$Arg" }
    default        { throw "unknown command $Command" }
}
