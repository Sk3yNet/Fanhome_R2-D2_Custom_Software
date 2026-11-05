# Talk to the R2 Agent over Wi-Fi.
# Usage: powershell -ExecutionPolicy Bypass -File tools\r2.ps1 <command> [arg] [-Robot <ip>]
#   find                     listen for the robot's UDP announcement (port 7702)
#   status | log             agent state / agent event log
#   log-previous             agent event log from the previous boot (e.g. after a crash or power loss)
#   action <name>            run a robot action, e.g. dance, wake, lightsaber, leia (list: actions)
#   stop                     emergency stop (the agent stops the robot itself, then cancels the action)
#   routines                 list the stored routines (docs/routines.md) and what's running
#   routine <name>           show one routine's JSON
#   routine-put <file.json>  upload a routine (checked on the robot; replaces one with the same name)
#   routine-run <name>       run a routine (also: action <name>)
#   routine-stop             end the running routine or action
#   routine-delete <name>    delete a routine (a replaced built-in action comes back)
#   places                   list the taught rooms (docs/places.md) and where R2 is
#   place <name> | place-put <file.json> | place-delete <name>
#                            show / upload / delete one room's route
#   goto <place>             drive R2 to a taught room, or home
#   tour                     visit every taught room in turn, then come home
#   here <home|place|unknown>  tell R2 where he is now (e.g. after carrying him home)
#   teach <place>            start teaching the way from home to a room: drive with the web page's joystick, then teach-save
#   teach-back <place>       teach the way back from a room to home
#   teach-turn               teach a half turn on the spot (lets R2 mirror routes that have no taught way back)
#   teach-save | teach-cancel  end teaching
#   cues                     list the watch-with-me cue files (docs/watch-with-me.md)
#   cue <name>               show one cue file's JSON
#   cue-put <file.json>      upload a cue file (checked on the robot; replaces one with the same name)
#   cue-delete <name>        delete a cue file
#   watch                    watch-with-me: what R2 is watching, where, and the next reaction
#   watch-start <cue> [-At h:mm:ss]
#                            start reacting along with a film by hand (press play at the same moment),
#                            from the start or from -At; Home Assistant does this by itself when set up
#   watch-pause | watch-resume | watch-stop
#   watch-on | watch-off     the "Watch with me" switch: off keeps R2 quiet during films
#   volume <0-100>           droid sounds and music volume (kept across reboots)
#   liveliness [level]       how much R2 does on his own: off, quiet, chatty or full (docs/liveliness.md);
#                            no level shows the current one
#   logcat [text] [-Lines N] Android log (all apps, incl. main-app crash traces), optionally only lines containing text
#   dmesg [text] [-Lines N]  kernel log
#   adb [minutes]            switch on adb over Wi-Fi for a while (default 30 min) and connect
#                            (root shell: tools\platform-tools\adb shell). It switches itself off again.
#   adb-off                  switch adb over Wi-Fi off now (adb is back on USB)
#   adb-always               keep adb over Wi-Fi on, also after reboots (trusted networks only)
#   allow-subnets [list]     also accept API clients from these subnets, e.g. 10.0.20.0/24,10.0.30.5
#                            (empty: only the robot's own subnet; no argument: show the list)
#   mqtt [host[:port]] [-User name]
#                            Home Assistant over MQTT: set the broker (asks for the password with -User),
#                            'off' switches it off, no argument shows the settings and connection state
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
#   hotspot                  R2's hotspot name and this robot's own password (in the pairing button's setup
#                            window without a token, otherwise over the API or root adb)
#   hotspot-password [new]   set R2's hotspot password (asks for it; 'new' makes a random one)
#   import-identity          copy the factory app's robot name, ID and paired phones to R2 Main (over root adb),
#                            so the official phone app still recognizes the robot
#   stock-on | stock-off     re-enable / disable the original Fanhome app (takes effect after reboot)
#   sim-on | sim-off         use the pretend controller instead of the real one (applies after reboot)
#   sim-button <n>           pretend a robot button was pressed (simulator only)
#   assist <off|robot|ha>    Home Assistant voice pipeline (docs/home-assistant-voice.md): off, wake word on the
#                            robot ("hey R2-D2", then the request goes to Home Assistant), or wake word in Home Assistant
#   assist-only [ip]         accept the voice connection only from this address (Home Assistant); no ip: anyone on R2's subnet
#   reboot | shutdown
param(
    [Parameter(Position = 0, Mandatory = $true)][string]$Command,
    [Parameter(Position = 1)][string]$Arg,
    [string]$Robot,
    [string]$User,
    [int]$Lines = 500,
    [string]$At
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
    'actions'      { 'stop wake turn-left turn-right turn-around forward circle patrol dance shake-head who-are-you noise not-recognized lightsaber arm short-lcd long-lcd flash-front flash-back leia luke mute unmute pair phone-app-on phone-app-off voice-on voice-off face-on face-off liveliness-off liveliness-quiet liveliness-chatty liveliness-full react-clap react-double-clap react-loud reactions-on reactions-off' -split ' ' }
    'action'       { if (-not $Arg) { throw 'usage: r2.ps1 action <name>  (see: r2.ps1 actions)' }; Call POST "/action?name=$([uri]::EscapeDataString("$Arg"))" }
    'stop'         { Call POST /stop }
    'routines'     {
        $d = Call GET /routines
        "running: $($d.running)" + $(if ($d.last_routine) { "   last: $($d.last_routine)" } else { '' })
        $d.routines | Format-Table name, steps, timeout, while_charging, replaces_action, description, error -AutoSize
    }
    'routine'      { if (-not $Arg) { throw 'usage: r2.ps1 routine <name>' }; (Call GET "/routine?name=$([uri]::EscapeDataString($Arg))").routine | ConvertTo-Json -Depth 20 }
    'routine-put'  {
        if (-not $Arg -or -not (Test-Path $Arg)) { throw 'usage: r2.ps1 routine-put <file.json>  (examples: docs\routines\)' }
        Call POST /routine (Get-Content $Arg -Raw -Encoding UTF8)
    }
    'routine-run'  { if (-not $Arg) { throw 'usage: r2.ps1 routine-run <name>' }; Call POST "/routine/run?name=$([uri]::EscapeDataString($Arg))" }
    'routine-stop' { Call POST /routine/stop }
    'places'       {
        $d = Call GET /places
        "R2 is at: $($d.location)" + $(if ($d.teaching) { "   teaching: $($d.teaching)" } else { '' }) + $(if ($d.last_trip) { "   last: $($d.last_trip)" } else { '' })
        "turn-around taught: $($d.turn_around_seconds) s   charger is home: $($d.charger_is_home)   patrol tours: $($d.patrol_tour)"
        $d.places | Format-Table name, spoken, there_seconds, there_segments, way_back, area -AutoSize
    }
    'place'        { if (-not $Arg) { throw 'usage: r2.ps1 place <name>' }; (Call GET "/place?name=$([uri]::EscapeDataString($Arg))").place | ConvertTo-Json -Depth 20 }
    'place-put'    {
        if (-not $Arg -or -not (Test-Path $Arg)) { throw 'usage: r2.ps1 place-put <file.json>' }
        Call POST /place (Get-Content $Arg -Raw -Encoding UTF8)
    }
    'place-delete' { if (-not $Arg) { throw 'usage: r2.ps1 place-delete <name>' }; Call POST "/place/delete?name=$([uri]::EscapeDataString($Arg))" }
    'goto'         { if (-not $Arg) { throw 'usage: r2.ps1 goto <place|home>' }; Call POST "/goto?place=$([uri]::EscapeDataString($Arg))" }
    'tour'         { Call POST /tour }
    'here'         { if (-not $Arg) { throw 'usage: r2.ps1 here <home|place|unknown>' }; Call POST "/place/here?name=$([uri]::EscapeDataString($Arg))" }
    'teach'        { if (-not $Arg) { throw 'usage: r2.ps1 teach <place>' }; Call POST "/place/teach?what=there&name=$([uri]::EscapeDataString($Arg))" }
    'teach-back'   { if (-not $Arg) { throw 'usage: r2.ps1 teach-back <place>' }; Call POST "/place/teach?what=back&name=$([uri]::EscapeDataString($Arg))" }
    'teach-turn'   { Call POST "/place/teach?what=turn" }
    'teach-save'   { Call POST /place/teach/save }
    'teach-cancel' { Call POST /place/teach/cancel }
    'routine-delete' { if (-not $Arg) { throw 'usage: r2.ps1 routine-delete <name>' }; Call POST "/routine/delete?name=$([uri]::EscapeDataString($Arg))" }
    'cues'         {
        $d = Call GET /cues
        $d.cues | Format-Table name, title, cues, offset, @{ n = 'match'; e = { $_.match -join ', ' } }, error -AutoSize
    }
    'cue'          { if (-not $Arg) { throw 'usage: r2.ps1 cue <name>' }; (Call GET "/cue?name=$([uri]::EscapeDataString($Arg))").cue | ConvertTo-Json -Depth 20 }
    'cue-put'      {
        if (-not $Arg -or -not (Test-Path $Arg)) { throw 'usage: r2.ps1 cue-put <file.json>  (examples: docs\watch-with-me\)' }
        Call POST /cue (Get-Content $Arg -Raw -Encoding UTF8)
    }
    'cue-delete'   { if (-not $Arg) { throw 'usage: r2.ps1 cue-delete <name>' }; Call POST "/cue/delete?name=$([uri]::EscapeDataString($Arg))" }
    'watch'        { Call GET /watch | ConvertTo-Json -Depth 5 }
    'watch-start'  {
        if (-not $Arg) { throw 'usage: r2.ps1 watch-start <cue file> [-At h:mm:ss]  (list: r2.ps1 cues)' }
        $body = @{ state = 'playing'; cue = $Arg; position = $(if ($At) { $At } else { 0 }) }
        Call POST /watch ($body | ConvertTo-Json -Compress)
    }
    'watch-pause'  { Call POST /watch '{"state":"paused"}' }
    'watch-resume' {
        $body = @{ state = 'playing' }; if ($At) { $body.position = $At }
        Call POST /watch ($body | ConvertTo-Json -Compress)
    }
    'watch-stop'   { Call POST /watch '{"state":"stopped"}' }
    'watch-on'     { Call POST /watch '{"enabled":true}' }
    'watch-off'    { Call POST /watch '{"enabled":false}' }
    'volume'       { if ($Arg -notmatch '^\d+$') { throw 'usage: r2.ps1 volume <0-100>' }; Call POST "/volume?percent=$Arg" }
    'liveliness'   {
        if (-not $Arg) { (Call GET /status).liveliness }
        elseif ($Arg -notin 'off', 'quiet', 'chatty', 'full') { throw 'usage: r2.ps1 liveliness <off|quiet|chatty|full>' }
        else { Call POST "/action?name=liveliness-$($Arg.ToLower())" }
    }
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
        if ($PSBoundParameters.ContainsKey('Arg')) { Call POST "/clients/allow?subnets=$([uri]::EscapeDataString("$Arg"))" }
        else { (Call GET /clients/allow).extra_subnets }
    }
    'mqtt'         {
        if (-not $Arg) { Call GET /mqtt | ConvertTo-Json }
        elseif ($Arg -eq 'off') { Call POST /mqtt '{"enabled":false}' }
        else {
            $hostName, $port = $Arg -split ':', 2
            $body = @{ host = $hostName; port = $(if ($port) { [int]$port } else { 1883 }) }
            if ($PSBoundParameters.ContainsKey('User')) {
                $body.username = $User
                if ($User) {
                    $secure = Read-Host "MQTT password for $User" -AsSecureString
                    $body.password = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
                } else { $body.password = '' }
            }
            Call POST /mqtt ($body | ConvertTo-Json -Compress)
            $body = $null
        }
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
    'hotspot'      {
        # In the setup window this works without a token; otherwise with the token, or over root adb.
        $h = $null
        try { $h = Invoke-RestMethod "http://$(Get-Robot):7700/hotspot" -Headers @{ 'X-R2-Token' = $token } -TimeoutSec 5 } catch { }
        if ($h) {
            "name:     $($h.ssid)"
            "password: $($h.password)"
            if ($h.using -eq 'factory') { "(running with the factory settings right now: $($h.factory_seconds_left) s left)" }
        } else {
            $adb = Join-Path $PSScriptRoot 'platform-tools\adb.exe'; $target = Connect-Adb
            "password: " + (& $adb -s $target shell cat /data/data/dev.r2hack.agent/files/hotspot-password).Trim()
        }
    }
    'hotspot-password' {
        $plain = ''
        if ($Arg -ne 'new') {
            $secure = Read-Host 'New hotspot password (8-63 characters; Enter for a random one)' -AsSecureString
            $plain = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
        }
        $h = Call POST /hotspot (@{ password = $plain } | ConvertTo-Json -Compress)
        $plain = $null
        "name:     $($h.ssid)"
        "password: $($h.password)"
        $h.result
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
    'assist'       {
        if ($Arg -notin 'off', 'robot', 'ha') { throw 'usage: r2.ps1 assist <off|robot|ha>' }
        Call POST "/assist?mode=$Arg"
    }
    'assist-only'  { Call POST "/assist?host=$([uri]::EscapeDataString("$Arg"))" }
    'reboot'       { Call POST /reboot }
    'shutdown'     { Call POST /shutdown }
    'sim-on'       { Call POST '/sim?on=1' }
    'sim-off'      { Call POST '/sim?on=0' }
    'sim-button'   { Call POST "/sim/button?value=$Arg" }
    default        { throw "unknown command $Command" }
}
