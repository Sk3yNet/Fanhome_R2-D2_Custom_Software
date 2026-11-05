# Talk to the R2 Agent over Wi-Fi.
# Usage: powershell -ExecutionPolicy Bypass -File tools\r2.ps1 <command> [arg] [-Robot <ip>]
#   find                     listen for the robot's UDP announcement (port 7702)
#   status | log             agent state / agent event log
#   log-previous             agent event log from the previous boot (e.g. after a crash or power loss)
#   action <name>            run a robot action, e.g. dance, wake, lightsaber, leia (list: actions)
#   chirp [mood|'text']      a newly generated droid chirp: a mood (ack happy excited question sad worried
#                            chatter; default random), or droid sounds shaped like a sentence (docs/chirps.md)
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
#   event <name>             send a named event; routines with that "event" trigger run (docs/routines.md)
#   volume <0-100>           droid sounds and music volume (kept across reboots)
#   liveliness [level]       how much R2 does on his own: off, quiet, chatty or full (docs/liveliness.md);
#                            no level shows the current one
#   plugins                  list the plugins (docs/plugins.md): state, what each asks for and is granted
#   plugin-on | plugin-off <name>
#                            enable / disable a plugin, e.g. plugin-off home-assistant
#   plugin-grant <name>=<permissions>
#                            what a plugin may do, e.g. plugin-grant battery-alert=status (empty: nothing)
#   plugin-config <name>=<file.json>
#                            merge the JSON object in the file into a plugin's config
#   logcat [text] [-Lines N] Android log (all apps, incl. main-app crash traces), optionally only lines containing text
#   dmesg [text] [-Lines N]  kernel log
#   adb [minutes]            switch on adb over Wi-Fi for a while (default 30 min) and connect
#                            (root shell: tools\platform-tools\adb shell). It switches itself off again.
#   adb-off                  switch adb over Wi-Fi off now (adb is back on USB)
#   adb-always               keep adb over Wi-Fi on, also after reboots (trusted networks only)
#   allow-subnets [list]     also accept API clients from these subnets, e.g. 10.0.20.0/24,10.0.30.5
#                            (empty: only the robot's own subnet; no argument: show the list)
#   hardening                boot hardening report: firewall, device permissions, Bluetooth, and what listens
#                            on the network (docs/hardening.md)
#   harden-on                switch on the firewall (as a 5-minute trial), tighten device permissions and keep
#                            Bluetooth off, then check the API still answers and confirm the firewall
#   harden-trial             firewall trial only, without confirming (it removes itself after 5 minutes)
#   harden-confirm           keep the firewall in a trial, also after reboots
#   harden-off               undo all of it (firewall, device permissions, Bluetooth)
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
#   assist-reply <voice|beeps>  answer in the pipeline's voice, or in droid beeps with the text as subtitles
#   pair                     give this PC its own admin key (press R2's pairing button first); saved to
#                            secrets\robot-client.txt and used from then on (docs/security.md)
#   whoami                   which key this PC uses and what it may do
#   clients                  list API clients (paired devices, tokens), their level and when they were last seen
#   client-add <name> [-Role read|control|admin] [-Plain]
#                            make a key for another device; -Plain makes a token that can be sent as is
#                            (Home Assistant), read or control only
#   client-role <id> -Role <r>   change a client's level
#   client-revoke <id>       revoke a client's key
#   rotate-owner             replace the robot's owner key (secrets\robot-token.txt is updated)
#   update                   self-update settings and the latest release seen
#   update-check | update-install
#                            look for a newer GitHub release / download, verify and install it (R2 reboots)
#   update-auto <on|off>     check every 6 hours and install while R2 is idle (off by default)
#   update-source <owner/repo>   follow another repository's releases (also add its certificate: trust-add)
#   trust | trust-add <sha256> | trust-remove <sha256>
#                            signing certificates whose APKs R2 installs (its own is always trusted)
#   reboot | shutdown
#   switch-source <owner/repo | folder> [-Tag v1.2.3] [-Yes]
#                            move the robot to builds signed with another key (another fork's releases, or your
#                            own builds in a folder such as apps\out), keeping settings (docs/releases.md).
#                            No argument: show who signed the robot's apps and which source it follows.
#   save-settings [folder]   back up the main app's and the agent's settings to the PC (over root adb)
#   restore-settings <folder> [-Agent]
#                            put the main app's settings back from a backup; -Agent also the agent's (then reboots)
param(
    [Parameter(Position = 0, Mandatory = $true)][string]$Command,
    [Parameter(Position = 1)][string]$Arg,
    [string]$Robot,
    [string]$User,
    [string]$Role,
    [switch]$Plain,
    [int]$Lines = 500,
    [string]$At,
    [string]$Tag,
    [switch]$Yes,
    [switch]$Agent
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
# Requests are signed (docs/security.md) with, in this order: this PC's own key (pair), the robot's owner
# key (fetch-token), or the development key built into your own agent builds.
$cred = $null
$clientFile = Join-Path $root 'secrets\robot-client.txt'
$ownerFile = Join-Path $root 'secrets\robot-token.txt'
$devFile = Join-Path $root 'secrets\agent-token.txt'
if (Test-Path $clientFile) {
    $id, $key = (Get-Content $clientFile -Raw).Trim() -split ':', 2
    $cred = @{ Id = $id; Key = $key }
} elseif (Test-Path $ownerFile) { $cred = @{ Id = 'owner'; Key = (Get-Content $ownerFile -Raw).Trim() } }
elseif (Test-Path $devFile) { $cred = @{ Id = 'dev'; Key = (Get-Content $devFile -Raw).Trim() } }
$script:clockOffset = $null
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

function Hex([byte[]]$Bytes) { -join ($Bytes | ForEach-Object { $_.ToString('x2') }) }

function Sha256Hex([byte[]]$Bytes) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { Hex $sha.ComputeHash($Bytes) } finally { $sha.Dispose() }
}

# Signed requests must carry the robot's time (within 5 minutes), so sign with its clock, not this PC's.
function Get-RobotTime([string]$Ip) {
    $now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    if ($null -eq $script:clockOffset) {
        $script:clockOffset = 0
        try { $script:clockOffset = [long](Invoke-RestMethod "http://${Ip}:7700/ping" -TimeoutSec 5).time - $now } catch { }
    }
    return $now + $script:clockOffset
}

# The X-R2-* headers for one request. $Path may carry a query string, encoded as it is sent.
function Get-SignedHeaders([string]$Method, [string]$Path, [string]$BodyHash, [long]$Time) {
    $q = $Path.IndexOf('?')
    $p = if ($q -ge 0) { $Path.Substring(0, $q) } else { $Path }
    $pairs = New-Object System.Collections.Generic.List[string]
    if ($q -ge 0) {
        foreach ($kv in $Path.Substring($q + 1) -split '&') {
            $e = $kv.IndexOf('='); if ($e -le 0) { continue }
            $k = [uri]::UnescapeDataString($kv.Substring(0, $e).Replace('+', ' '))
            if ($k -match '^r2(client|time|nonce|sig)$') { continue }
            $pairs.Add($k + '=' + [uri]::UnescapeDataString($kv.Substring($e + 1).Replace('+', ' ')))
        }
    }
    $sorted = $pairs.ToArray(); [Array]::Sort($sorted, [StringComparer]::Ordinal)
    $nb = New-Object byte[] 12; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($nb)
    $nonce = Hex $nb
    $canon = "R2-HMAC-SHA256`n$Method`n$p`n$($sorted -join '&')`n$Time`n$nonce`n$BodyHash"
    $hmac = New-Object Security.Cryptography.HMACSHA256 -ArgumentList (, [Text.Encoding]::UTF8.GetBytes($cred.Key))
    try { $sig = Hex $hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($canon)) } finally { $hmac.Dispose() }
    return @{ 'X-R2-Client' = $cred.Id; 'X-R2-Time' = "$Time"; 'X-R2-Nonce' = $nonce; 'X-R2-Body' = $BodyHash; 'X-R2-Signature' = $sig }
}

function Call([string]$Method, [string]$Path, $Body, [string]$InFile, [int]$Timeout = 30) {
    $ip = Get-Robot
    $p = @{ Uri = "http://${ip}:7700$Path"; Method = $Method; TimeoutSec = $Timeout }
    $bodyHash = Sha256Hex ([byte[]]@())
    if ($InFile) {
        $p.InFile = $InFile; $p.ContentType = 'application/vnd.android.package-archive'
        $bodyHash = (Get-FileHash -Algorithm SHA256 $InFile).Hash.ToLowerInvariant()
    } elseif ($null -ne $Body) {
        $bytes = [Text.Encoding]::UTF8.GetBytes($Body)
        $p.Body = $bytes; $p.ContentType = 'application/json'
        $bodyHash = Sha256Hex $bytes
    }
    $p.Headers = if ($cred) { Get-SignedHeaders $Method $Path $bodyHash (Get-RobotTime $ip) } else { @{} }
    Invoke-RestMethod @p
}

# ---- Switching signers (switch-source, save-settings, restore-settings). Plan: docs/releases.md ----
$adbExe = Join-Path $PSScriptRoot 'platform-tools\adb.exe'
$apps = @{
    main  = @{ pkg = 'dev.r2hack.main'; apk = 'R2Main.apk'; dirs = @('shared_prefs', 'files/routines', 'files/cues'); files = @('files/voice/keywords.txt', 'files/places.json') }
    agent = @{ pkg = 'dev.r2hack.agent'; apk = 'R2Agent.apk'; dirs = @('shared_prefs'); files = @() }
}
$sourceFile = Join-Path $root 'secrets\robot-source.json'

function Invoke-AdbShell([string]$Target, [string]$Cmd) {
    @(& $adbExe -s $Target shell $Cmd) | ForEach-Object { "$_".TrimEnd("`r") }
}

# Installed app on the robot: APK path, versionCode and Linux uid (from dumpsys, as KitKat has no grep).
function Get-RobotApp([string]$Target, [string]$Name) {
    $pkg = $apps[$Name].pkg
    $path = @(Invoke-AdbShell $Target "pm path $pkg") -match '^package:' | Select-Object -First 1
    if (-not $path) { return $null }
    $dump = (Invoke-AdbShell $Target "dumpsys package $pkg") -join "`n"
    $info = @{ path = $path.Substring(8).Trim(); version = 0; uid = $null; cert = $null }
    if ($dump -match 'versionCode=(\d+)') { $info.version = [long]$Matches[1] }
    if ($dump -match 'userId=(\d+)') { $info.uid = $Matches[1] }
    return $info
}

# Pulls the installed APK to $Dir and reads its certificate there.
function Get-RobotSigner([string]$Target, [string]$Name, [string]$Dir) {
    $info = Get-RobotApp $Target $Name
    if (-not $info) { return $null }
    $local = Join-Path $Dir $apps[$Name].apk
    & $adbExe -s $Target pull $info.path $local 2>&1 | Out-Null
    if (-not (Test-Path $local)) { throw "could not copy $($info.path) from the robot" }
    $info.cert = Get-ApkCert $local
    return $info
}

# Copies an app's settings (shared_prefs and the files listed in $apps) to $Dir\<app>\. Returns the file count.
function Save-AppSettings([string]$Target, [string]$Name, [string]$Dir) {
    $data = "/data/data/$($apps[$Name].pkg)"
    $rels = @($apps[$Name].files)
    foreach ($d in $apps[$Name].dirs) {
        # Entries are plain names; an error line ("...: No such file or directory") has a colon or slash.
        # *.bak is a half-written preferences file, which Android would restore over the real one.
        $rels += Invoke-AdbShell $Target "ls $data/$d" | Where-Object { $_ -and $_ -notmatch '[:/]' -and $_ -notmatch '\.bak$' } | ForEach-Object { "$d/$_" }
    }
    $n = 0
    foreach ($rel in $rels) {
        if (@(Invoke-AdbShell $Target "ls '$data/$rel'") -notcontains "$data/$rel") { continue }   # optional file not there
        $local = Join-Path (Join-Path $Dir $Name) $rel
        New-Item -ItemType Directory -Force (Split-Path $local) | Out-Null
        & $adbExe -s $Target pull "$data/$rel" $local 2>&1 | Out-Null
        if (-not (Test-Path $local)) { throw "could not copy $data/$rel" }
        $n++
    }
    return $n
}

# Puts saved settings back and gives them to the app's current uid (a reinstall gets a new one).
# The app must not be running, or it may write its old settings over them.
function Restore-AppSettings([string]$Target, [string]$Name, [string]$Dir) {
    $base = Join-Path $Dir $Name
    if (-not (Test-Path $base)) { return 0 }
    $info = Get-RobotApp $Target $Name
    if (-not $info -or -not $info.uid) { throw "$($apps[$Name].pkg) is not installed" }
    $data = "/data/data/$($apps[$Name].pkg)"
    $baseFull = (Resolve-Path $base).Path
    $cmds = @(); $dirs = @{}
    foreach ($f in Get-ChildItem $base -Recurse -File) {
        $rel = $f.FullName.Substring($baseFull.Length + 1) -replace '\\', '/'
        & $adbExe -s $Target push $f.FullName "$data/$rel" 2>&1 | Out-Null
        $cmds += "chown $($info.uid):$($info.uid) '$data/$rel'; chmod 660 '$data/$rel'"
        $parts = $rel.Split('/')
        for ($i = 1; $i -lt $parts.Count; $i++) { $dirs[($parts[0..($i - 1)] -join '/')] = $true }
    }
    foreach ($d in $dirs.Keys) { $cmds += "chown $($info.uid):$($info.uid) '$data/$d'; chmod 771 '$data/$d'" }
    for ($i = 0; $i -lt $cmds.Count; $i += 10) {   # a few at a time: adb shell command lines are short on KitKat
        Invoke-AdbShell $Target ($cmds[$i..([Math]::Min($i + 9, $cmds.Count - 1))] -join '; ') | Out-Null
    }
    Invoke-AdbShell $Target "restorecon -R $data" | Out-Null
    return @($cmds).Count - $dirs.Count
}

# False when the agent no longer accepts our token (it lost its data); throws on other errors.
function Test-Token {
    try { Call GET /status | Out-Null; return $true }
    catch {
        $code = 0; try { $code = [int]$_.Exception.Response.StatusCode } catch { }
        if ($code -in 401, 403) { return $false }
        throw
    }
}

function Wait-Robot([int]$Seconds) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    Start-Sleep -Seconds 20
    while ((Get-Date) -lt $deadline) {
        try { $ip = Get-Robot; Invoke-RestMethod "http://${ip}:7700/ping" -TimeoutSec 3 | Out-Null; return $true }
        catch { Start-Sleep -Seconds 5 }
    }
    return $false
}

# Where the new APKs come from: a folder with R2Agent.apk and R2Main.apk, or the latest (or -Tag) GitHub
# release of owner/repo, downloaded to apps\out\releases\ and checked against its SHA256SUMS.
function Get-Source([string]$Spec, [string]$ReleaseTag) {
    if (Test-Path $Spec -PathType Container) {
        $dir = (Resolve-Path $Spec).Path
        return @{ dir = $dir; label = $dir; repo = $null; tag = $null; noteCert = $null }
    }
    if ($Spec -notmatch '^[A-Za-z0-9-]+/[A-Za-z0-9._-]+$') { throw "not a folder or owner/repo: $Spec" }
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    $api = "https://api.github.com/repos/$Spec/releases/" + $(if ($ReleaseTag) { "tags/$ReleaseTag" } else { 'latest' })
    $headers = @{ 'User-Agent' = 'r2-switch-source'; Accept = 'application/vnd.github+json' }
    try { $rel = Invoke-RestMethod $api -Headers $headers -TimeoutSec 30 }
    catch { throw "no release found at $api ($($_.Exception.Message))" }
    $dir = Join-Path $root ("apps\out\releases\" + ($Spec -replace '/', '-') + "-$($rel.tag_name)")
    New-Item -ItemType Directory -Force $dir | Out-Null
    foreach ($name in 'R2Agent.apk', 'R2Main.apk', 'SHA256SUMS') {
        $asset = $rel.assets | Where-Object { $_.name -eq $name } | Select-Object -First 1
        if (-not $asset) { throw "release $($rel.tag_name) of $Spec has no $name" }
        Write-Host "downloading $name from $Spec $($rel.tag_name)"
        Invoke-WebRequest $asset.browser_download_url -OutFile (Join-Path $dir $name) -Headers @{ 'User-Agent' = 'r2-switch-source' } -UseBasicParsing -TimeoutSec 300
    }
    foreach ($line in Get-Content (Join-Path $dir 'SHA256SUMS')) {
        if ($line -notmatch '^([0-9a-fA-F]{64})\s+\*?(\S+)$') { continue }
        $actual = (Get-FileHash (Join-Path $dir $Matches[2]) -Algorithm SHA256).Hash
        if ($actual -ne $Matches[1]) { throw "$($Matches[2]) does not match SHA256SUMS: the download is damaged or was changed" }
    }
    $noteCert = if ($rel.body -match 'certificate SHA-256:\s*`([0-9a-f]{64})`') { $Matches[1] } else { $null }
    return @{ dir = $dir; label = "$Spec $($rel.tag_name)"; repo = $Spec; tag = $rel.tag_name; noteCert = $noteCert }
}

function Save-Secret([string]$File, [string]$Text) {
    New-Item -ItemType Directory -Force (Join-Path $root 'secrets') | Out-Null
    Set-Content $File $Text -NoNewline -Encoding ascii
}

switch ($Command) {
    'find'         { $ip = Find-Robot 10; if ($ip) { "robot at $ip" } else { 'no announcement heard' } }
    'status'       { Call GET /status | ConvertTo-Json -Depth 5 }
    'log'          { Call GET /log }
    'log-previous' { Call GET '/log?previous=1' }
    'actions'      { 'stop wake turn-left turn-right turn-around forward circle patrol follow dance shake-head who-are-you noise not-recognized chirp lightsaber arm short-lcd long-lcd flash-front flash-back leia luke mute unmute pair phone-app-on phone-app-off voice-on voice-off face-on face-off liveliness-off liveliness-quiet liveliness-chatty liveliness-full chirps-on chirps-off beeps-on beeps-off react-clap react-double-clap react-loud reactions-on reactions-off' -split ' ' }
    'action'       { if (-not $Arg) { throw 'usage: r2.ps1 action <name>  (see: r2.ps1 actions)' }; Call POST "/action?name=$([uri]::EscapeDataString("$Arg"))" }
    'chirp'        {
        $moods = 'random', 'ack', 'happy', 'excited', 'question', 'sad', 'worried', 'chatter'
        if (-not $Arg) { Call POST '/chirp?mood=random' }
        elseif ($Arg -in $moods) { Call POST "/chirp?mood=$Arg" }
        else { Call POST /chirp $Arg }   # text: the body, so long sentences fit
    }
    'stop'         { Call POST /stop }
    'routines'     {
        $d = Call GET /routines
        "running: $($d.running)" + $(if ($d.last_routine) { "   last: $($d.last_routine)" } else { '' })
        if ($d.last_trigger) { "last trigger: $($d.last_trigger)" }
        $d.routines | Format-Table name, steps, timeout, while_charging, replaces_action, @{ n = 'triggers'; e = { $_.triggers -join '; ' } }, description, error -AutoSize
    }
    'routine'      { if (-not $Arg) { throw 'usage: r2.ps1 routine <name>' }; (Call GET "/routine?name=$([uri]::EscapeDataString($Arg))").routine | ConvertTo-Json -Depth 20 }
    'routine-put'  {
        if (-not $Arg -or -not (Test-Path $Arg)) { throw 'usage: r2.ps1 routine-put <file.json>  (examples: docs\routines\)' }
        Call POST /routine (Get-Content $Arg -Raw -Encoding UTF8)
    }
    'routine-run'  { if (-not $Arg) { throw 'usage: r2.ps1 routine-run <name>' }; Call POST "/routine/run?name=$([uri]::EscapeDataString($Arg))" }
    'routine-stop' { Call POST /routine/stop }
    'event'        { if (-not $Arg) { throw 'usage: r2.ps1 event <name>' }; Call POST "/event?name=$([uri]::EscapeDataString($Arg))" }
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
    'plugins'      {
        $d = Call GET /plugins
        $d.plugins | Format-Table name, enabled, state, @{ n = 'granted'; e = { $_.granted -join ',' } }, @{ n = 'asks_for'; e = { $_.asks_for -join ',' } } -AutoSize -Wrap
        'permissions:'; $d.permissions.PSObject.Properties | ForEach-Object { "  $($_.Name): $($_.Value)" }
    }
    { $_ -in 'plugin-on', 'plugin-off' } {
        if (-not $Arg) { throw "usage: r2.ps1 $Command <name>  (see: r2.ps1 plugins)" }
        $on = if ($Command -eq 'plugin-on') { 1 } else { 0 }
        Call POST "/plugin?name=$([uri]::EscapeDataString($Arg))&enabled=$on"
    }
    'plugin-grant' {
        if ($Arg -notmatch '^([^=]+)=(.*)$') { throw 'usage: r2.ps1 plugin-grant <name>=<permissions>, e.g. battery-alert=status,sound' }
        Call POST "/plugin?name=$([uri]::EscapeDataString($Matches[1]))&grant=$([uri]::EscapeDataString($Matches[2]))"
    }
    'plugin-config' {
        if ($Arg -notmatch '^([^=]+)=(.+)$' -or -not (Test-Path $Matches[2])) { throw 'usage: r2.ps1 plugin-config <name>=<file.json>' }
        $name = $Matches[1]
        Call POST "/plugin/config?name=$([uri]::EscapeDataString($name))" (Get-Content $Matches[2] -Raw -Encoding UTF8)
    }
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
        Save-Secret $ownerFile $t
        'saved to secrets\robot-token.txt (the owner key: keep it private; it always works, so it is your way back in)'
    }
    'pair'         {
        # Works in the pairing button's 2-minute window, or with an admin key already in place.
        $c = Call POST "/clients/pair?name=$([uri]::EscapeDataString("PC $env:COMPUTERNAME"))"
        Save-Secret $clientFile "$($c.id):$($c.key)"
        "paired as $($c.id) ($($c.role)); key saved to secrets\robot-client.txt"
    }
    'whoami'       { Call GET /whoami | ConvertTo-Json }
    'clients'      {
        (Call GET /clients).clients | ForEach-Object {
            $seen = if ($_.last_seen) { [DateTimeOffset]::FromUnixTimeSeconds($_.last_seen).LocalDateTime } else { 'never' }
            [pscustomobject]@{ id = $_.id; name = $_.name; role = $_.role; plain = $_.plain_token; last_seen = $seen; last = $_.last_request }
        } | Format-Table -AutoSize
    }
    'client-add'   {
        if (-not $Arg) { throw 'usage: r2.ps1 client-add <name> [-Role read|control|admin] [-Plain]' }
        $r = if ($Role) { $Role } else { 'control' }
        $q = "name=$([uri]::EscapeDataString($Arg))&role=$r" + $(if ($Plain) { '&plain=1' } else { '' })
        $c = Call POST "/clients/add?$q"
        "id:   $($c.id)   ($($c.role)$(if ($c.plain_token) { ', plain token' }))"
        if ($c.plain_token) { "token: $($c.key)" } else { "key:  $($c.id):$($c.key)" }
        'Shown only now. Revoke with: r2.ps1 client-revoke ' + $c.id
    }
    'client-role'  { if (-not $Arg -or -not $Role) { throw 'usage: r2.ps1 client-role <id> -Role read|control|admin' }; Call POST "/clients/role?id=$([uri]::EscapeDataString($Arg))&role=$Role" }
    'client-revoke' { if (-not $Arg) { throw 'usage: r2.ps1 client-revoke <id>' }; Call POST "/clients/revoke?id=$([uri]::EscapeDataString($Arg))" }
    'rotate-owner' {
        if (-not $cred -or $cred.Id -ne 'owner') { throw 'sign with the owner key for this: remove secrets\robot-client.txt for a moment, or run fetch-token first' }
        $c = Call POST /clients/owner/rotate
        Save-Secret $ownerFile $c.key
        'new owner key saved to secrets\robot-token.txt; the old one no longer works'
    }
    'update'       { Call GET /update | ConvertTo-Json -Depth 5 }
    'update-check' { Call POST /update/check -Timeout 60 | ConvertTo-Json }
    'update-install' { Call POST /update/install -Timeout 600 }
    'update-auto'  { if ($Arg -notin 'on', 'off') { throw 'usage: r2.ps1 update-auto <on|off>' }; Call POST "/update/auto?on=$(if ($Arg -eq 'on') { 1 } else { 0 })" }
    'update-source' { if ($Arg -notmatch '^[\w.-]+/[\w.-]+$') { throw 'usage: r2.ps1 update-source <owner/repo>' }; Call POST "/update/source?repo=$([uri]::EscapeDataString($Arg))" }
    'trust'        { Call GET /trust | ConvertTo-Json }
    'trust-add'    { if (-not $Arg) { throw 'usage: r2.ps1 trust-add <certificate sha256>' }; Call POST "/trust?add=$([uri]::EscapeDataString($Arg))" | ConvertTo-Json }
    'trust-remove' { if (-not $Arg) { throw 'usage: r2.ps1 trust-remove <certificate sha256>' }; Call POST "/trust?remove=$([uri]::EscapeDataString($Arg))" | ConvertTo-Json }
    'adb'          { $m = if ($Arg) { [int]$Arg } else { 30 }; Connect-Adb $m | Out-Null }
    'adb-off'      { Call POST '/adb?on=0' }
    'adb-always'   { Call POST '/adb?always=1' }
    'allow-subnets' {
        if ($PSBoundParameters.ContainsKey('Arg')) { Call POST "/clients/allow?subnets=$([uri]::EscapeDataString("$Arg"))" }
        else { (Call GET /clients/allow).extra_subnets }
    }
    'hardening'    { Call GET /hardening | ConvertTo-Json -Depth 6 }
    'harden-trial' { Call POST '/hardening?firewall=1' }
    'harden-confirm' { Call POST /hardening/confirm }
    'harden-off'   { Call POST '/hardening?all=0' }
    'harden-on'    {
        (Call POST '/hardening?firewall=1&devices=1&bluetooth=1').result
        Start-Sleep -Seconds 2
        # A new connection through the firewall: if it gets through, the firewall can stay.
        try { Call GET /status -Timeout 10 | Out-Null } catch {
            throw "The API stopped answering with the firewall on, so it was NOT confirmed; it removes itself within 5 minutes. ($_)"
        }
        (Call POST /hardening/confirm).result
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
        try { $h = Call GET /hotspot -Timeout 5 } catch { }
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
    'assist-reply' {
        if ($Arg -notin 'voice', 'beeps') { throw 'usage: r2.ps1 assist-reply <voice|beeps>' }
        Call POST "/action?name=$(if ($Arg -eq 'beeps') { 'beeps-on' } else { 'beeps-off' })"
    }
    'reboot'       { Call POST /reboot }
    'shutdown'     { Call POST /shutdown }
    'sim-on'       { Call POST '/sim?on=1' }
    'sim-off'      { Call POST '/sim?on=0' }
    'sim-button'   { Call POST "/sim/button?value=$Arg" }
    'save-settings' {
        . (Join-Path $PSScriptRoot 'apk-info.ps1')
        $dir = if ($Arg) { $Arg } else { Join-Path $root ('secrets\settings-' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
        $target = Connect-Adb
        foreach ($name in 'agent', 'main') { "{0}: {1} files" -f $name, (Save-AppSettings $target $name $dir) }
        "saved to $dir (it holds the API token and Wi-Fi and MQTT passwords: keep it private)"
    }
    'restore-settings' {
        if (-not $Arg -or -not (Test-Path $Arg -PathType Container)) { throw 'usage: r2.ps1 restore-settings <folder> [-Agent]' }
        $target = Connect-Adb
        Call POST '/safe-mode?on=1' | Out-Null   # stops the main app so it can't overwrite what comes back
        "main: {0} files restored" -f (Restore-AppSettings $target 'main' $Arg)
        if ($Agent) {
            "agent: {0} files restored, rebooting (the agent takes its token from the backup)" -f (Restore-AppSettings $target 'agent' $Arg)
            & $adbExe -s $target reboot
        } else { Call POST '/safe-mode?on=0' | Out-Null; 'main app restarted' }
    }
    'switch-source' {
        . (Join-Path $PSScriptRoot 'apk-info.ps1')
        $target = Connect-Adb 60
        $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
        $backup = Join-Path $root "secrets\switch-backup-$stamp"
        New-Item -ItemType Directory -Force $backup | Out-Null
        $old = @{}
        foreach ($name in 'agent', 'main') { $old[$name] = Get-RobotSigner $target $name $backup }
        $recorded = if (Test-Path $sourceFile) { Get-Content $sourceFile -Raw | ConvertFrom-Json } else { $null }
        if (-not $Arg) {
            Remove-Item $backup -Recurse -Force
            foreach ($name in 'agent', 'main') {
                if ($old[$name]) { '{0,-6} signed by {1}  (versionCode {2})' -f $name, $old[$name].cert, $old[$name].version }
                else { '{0,-6} not installed' -f $name }
            }
            if ($recorded) { "source: $($recorded.source), switched $($recorded.switched)" } else { 'source: not recorded (never switched from this PC)' }
            return
        }

        $src = Get-Source $Arg $Tag
        $new = @{}
        foreach ($name in 'agent', 'main') {
            $f = Join-Path $src.dir $apps[$name].apk
            if (-not (Test-Path $f)) { throw "missing $f" }
            $new[$name] = @{ file = $f; cert = (Get-ApkCert $f); version = (Get-ApkVersionCode $f) }
        }
        if ($new.agent.cert -ne $new.main.cert) { throw "the two APKs in $($src.label) are signed with different keys" }
        if ($src.noteCert -and $src.noteCert -ne $new.main.cert) {
            throw "the release notes name certificate $($src.noteCert), but the APKs are signed with $($new.main.cert)"
        }
        $change = @{}
        foreach ($name in 'agent', 'main') { $change[$name] = -not $old[$name] -or $old[$name].cert -ne $new[$name].cert }
        if (-not $old.agent) { throw 'the agent is not installed: use tools\install-on-robot.ps1 install first' }
        # The agent lives in /system. When there is also an over-the-air update in /data (push-agent), Android
        # uses the /system copy at boot only if it is at least as new; otherwise the switch would silently not happen.
        if ($change.agent -and $old.agent.path -notlike '/system/*' -and $new.agent.version -lt $old.agent.version) {
            throw "the new agent (versionCode $($new.agent.version)) is older than the robot's over-the-air update ($($old.agent.version)). Pick a newer release."
        }

        "From: $(if ($recorded) { $recorded.source } else { 'current builds' })"
        "  agent  $($old.agent.cert)"
        "  main   $(if ($old.main) { $old.main.cert } else { 'not installed' })"
        "To:   $($src.label)"
        "  both   $($new.main.cert)"
        if (-not $change.agent -and -not $change.main) {
            Remove-Item $backup -Recurse -Force
            'Same signer as on the robot: no switch needed. Update in place with push-main and push-agent.'
            return
        }
        "Changes: $((@('agent', 'main') | Where-Object { $change[$_] }) -join ' and ') (reinstalled with the new key; settings are kept)"
        if (-not $Yes -and (Read-Host 'Compare the certificate with the one the source publishes. Type yes to switch') -ne 'yes') {
            Remove-Item $backup -Recurse -Force; 'cancelled, nothing changed'; return
        }

        # 1. Back up. The agent first, so its saved settings don't include the safe mode set next.
        "agent settings: $(Save-AppSettings $target 'agent' $backup) files"
        Call POST '/safe-mode?on=1' | Out-Null   # main app stopped and not relaunched while we work
        if ($old.main) { "main settings: $(Save-AppSettings $target 'main' $backup) files" }
        @{ from = $recorded; to = $src.label; backup = $backup } | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $backup 'switch.json')
        "backup with the old APKs: $backup"

        # 2. Main app: uninstall (Android refuses an update from another key), install, put settings back.
        if ($change.main) {
            if ($old.main) { Invoke-AdbShell $target "pm uninstall $($apps.main.pkg)" | Out-Null }
            $out = (& $adbExe -s $target install -r $new.main.file 2>&1) -join "`n"
            if ($out -notmatch 'Success') {
                Write-Warning "main app install failed: $out"
                if ($old.main) {
                    & $adbExe -s $target install -r (Join-Path $backup $apps.main.apk) | Out-Null
                    Restore-AppSettings $target 'main' $backup | Out-Null
                    'the previous main app and its settings are back'
                }
                Call POST '/safe-mode?on=0' | Out-Null
                throw 'switch stopped; the agent was not changed'
            }
            "main app installed, $(Restore-AppSettings $target 'main' $backup) settings files restored"
            # The agent's rollback copy is signed with the old key: drop it. The new build becomes the
            # rollback copy once it has run through probation.
            Invoke-AdbShell $target "rm $('/data/data/' + $apps.agent.pkg)/files/main-lastgood.apk" | Out-Null
        }

        # 3. Agent: replace the /system copy and reboot. Android 4.4 accepts a new certificate for a system app
        #    at boot ("signature changed; retaining data"), drops an older over-the-air copy, and keeps the data.
        if ($change.agent) {
            Invoke-AdbShell $target 'mount -o remount,rw /system' | Out-Null
            & $adbExe -s $target push $new.agent.file /system/priv-app/R2Agent.apk 2>&1 | Out-Null
            Invoke-AdbShell $target 'chmod 644 /system/priv-app/R2Agent.apk; sync; mount -o remount,ro /system' | Out-Null
            'agent replaced in /system, rebooting (about a minute)'
            & $adbExe -s $target reboot
            if (-not (Wait-Robot 240)) { throw "the robot did not come back within 4 minutes. Backup: $backup" }
            if (-not (Test-Token)) {
                Write-Warning 'The agent no longer accepts the saved token, so it lost its settings in the switch.'
                "To get them back: press the pairing button on R2, run 'tools\r2.ps1 fetch-token', then"
                "'tools\r2.ps1 restore-settings $backup -Agent'. Please report this: docs/releases.md expects the data to stay."
                return
            }
            $target = Connect-Adb 30
            $after = Join-Path $backup 'after'; New-Item -ItemType Directory -Force $after | Out-Null
            $now = Get-RobotSigner $target 'agent' $after
            if ($now.cert -ne $new.agent.cert) { throw "the robot still runs the old agent ($($now.path)). Backup: $backup" }
            'agent switched, settings kept'
        }
        Call POST '/safe-mode?on=0' | Out-Null
        # The agent's own certificate is now the new one, so it trusts this source's updates; point its
        # self-update at the same repository (docs/security.md). An agent without self-update answers 404.
        if ($src.repo) {
            try { (Call POST "/update/source?repo=$([uri]::EscapeDataString($src.repo))").result }
            catch { Write-Warning "could not set the agent's update source ($($_.Exception.Message)); later: r2.ps1 update-source $($src.repo)" }
        }
        @{ source = $src.label; repo = $src.repo; tag = $src.tag; cert = $new.main.cert; switched = (Get-Date -Format 's') } |
            ConvertTo-Json | Set-Content $sourceFile
        "Done: the robot now runs $($src.label). From now on update it with push-main and push-agent from this source."
    }
    default        { throw "unknown command $Command" }
}
