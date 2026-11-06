#!/usr/bin/env python3
"""Talk to the R2 Agent over Wi-Fi, from Windows, macOS or Linux.

The Python version of tools/r2.ps1 and tools/install-on-robot.ps1, with the same commands.
Needs Python 3.8+ and nothing else (the serial-* commands also need pyserial).

Usage: python3 tools/r2.py <command> [arg] [--robot <ip>] [--user <name>] [--lines N]
  find                     listen for the robot's UDP announcement (port 7702)
  status | log             agent state / agent event log
  log-previous             agent event log from the previous boot (e.g. after a crash or power loss)
  action <name>            run a robot action, e.g. dance, wake, lightsaber, leia (list: actions)
  chirp [mood|'text']      a newly generated droid chirp: a mood (ack happy excited question sad worried
                           chatter whistle scream; default random), or droid sounds shaped like a sentence
                           (docs/chirps.md)
  sounds                   the soundboard (docs/soundboard.md): factory sounds, mood banks, chirps, reactions
  sound <number|mood|stop> play a factory sound by number, a random one from a mood bank (happy, random...),
                           or stop the sound playing
  stop                     emergency stop (the agent stops the robot itself, then cancels the action;
                           with the drive lock on, driving is locked again)
  drive                    drive lock state (docs/drive-lock.md)
  drive-lock [on|off]      require enabling before R2 drives (kept across reboots); no argument shows it
  drive-enable [minutes]   enable driving until R2 hasn't driven for this long (default 10, max 120)
  drive-disable            lock driving again now (stops R2 if it is driving)
  routines                 list the stored routines (docs/routines.md) and what's running
  routine <name>           show one routine's JSON
  routine-put <file.json>  upload a routine (checked on the robot; replaces one with the same name)
  routine-run <name>       run a routine (also: action <name>)
  routine-stop             end the running routine or action
  routine-delete <name>    delete a routine (a replaced built-in action comes back)
  places                   list the taught rooms (docs/places.md) and where R2 is
  place <name> | place-put <file.json> | place-delete <name>
                           show / upload / delete one room's route
  goto <place>             drive R2 to a taught room, or home
  tour                     visit every taught room in turn, then come home
  here <home|place|unknown>  tell R2 where he is now (e.g. after carrying him home)
  teach <place>            start teaching the way from home to a room: drive with the web page's joystick, then teach-save
  teach-back <place>       teach the way back from a room to home
  teach-turn               teach a half turn on the spot (lets R2 mirror routes that have no taught way back)
  teach-save | teach-cancel  end teaching
  cues                     list the watch-with-me cue files (docs/watch-with-me.md)
  cue <name>               show one cue file's JSON
  cue-put <file.json>      upload a cue file (checked on the robot; replaces one with the same name)
  cue-delete <name>        delete a cue file
  watch                    watch-with-me: what R2 is watching, where, and the next reaction
  watch-start <cue> [--at h:mm:ss]
                           start reacting along with a film by hand (press play at the same moment),
                           from the start or from --at; Home Assistant does this by itself when set up
  watch-pause | watch-resume [--at h:mm:ss] | watch-stop
  watch-on | watch-off     the "Watch with me" switch: off keeps R2 quiet during films
  event <name>             send a named event; routines with that "event" trigger run (docs/routines.md)
  volume <0-100>           droid sounds and music volume (kept across reboots)
  liveliness [level]       how much R2 does on his own: off, quiet, chatty or full (docs/liveliness.md);
                           no level shows the current one
  plugins                  list the plugins (docs/plugins.md): state, what each asks for and is granted
  plugin-on | plugin-off <name>
                           enable / disable a plugin, e.g. plugin-off home-assistant
  plugin-grant <name>=<permissions>
                           what a plugin may do, e.g. plugin-grant battery-alert=status (empty: nothing)
  plugin-config <name>=<file.json>
                           merge the JSON object in the file into a plugin's config
  logcat [text] [--lines N] Android log (all apps, incl. main-app crash traces), optionally only lines containing text
  dmesg [text] [--lines N]  kernel log
  adb [minutes]            switch on adb over Wi-Fi for a while (default 30 min) and connect
                           (root shell: adb shell). It switches itself off again.
  adb-off                  switch adb over Wi-Fi off now (adb is back on USB)
  adb-always               keep adb over Wi-Fi on, also after reboots (trusted networks only)
  allow-subnets [list]     also accept API clients from these subnets, e.g. 10.0.20.0/24,10.0.30.5
                           (empty string '': only the robot's own subnet; no argument: show the list)
  hardening                boot hardening report: firewall, device permissions, Bluetooth, and what listens
                           on the network (docs/hardening.md)
  harden-on                switch on the firewall (as a 5-minute trial), tighten device permissions and keep
                           Bluetooth off, then check the API still answers and confirm the firewall
  harden-trial             firewall trial only, without confirming (it removes itself after 5 minutes)
  harden-confirm           keep the firewall in a trial, also after reboots
  harden-off               undo all of it (firewall, device permissions, Bluetooth)
  mqtt [host[:port]] [--user name]
                           Home Assistant over MQTT: set the broker (asks for the password with --user),
                           'off' switches it off, no argument shows the settings and connection state
  fetch-token              copy this robot's own API token to secrets/robot-token.txt (in the pairing
                           button's setup window, or over root adb)
  push-main [apk]          install a main-app build (default apps/out/R2Main.apk), on probation
  push-agent [apk]         update the agent itself
  rollback | restart       main app: reinstall last good build / restart it
  serial '<json>'          send one command to the controller, e.g. '{"cmd":"mode","mode":13}' (flash front LCD)
  safe-on | safe-off       stop / resume supervising the main app
  wifi-station | wifi-ap   switch network mode
  wifi-scan                networks R2 can see
  wifi-join <ssid>         join a network (asks for its password; R2 falls back to its hotspot if it fails)
  hotspot                  R2's hotspot name and this robot's own password (in the pairing button's setup
                           window without a token, otherwise over the API or root adb)
  hotspot-password [new]   set R2's hotspot password (asks for it; 'new' makes a random one)
  import-identity          copy the factory app's robot name, ID and paired phones to R2 Main (over root adb),
                           so the official phone app still recognizes the robot
  stock-on | stock-off     re-enable / disable the original Fanhome app (takes effect after reboot)
  sim-on | sim-off         use the pretend controller instead of the real one (applies after reboot)
  sim-button <n>           pretend a robot button was pressed (simulator only)
  assist <off|robot|ha>    Home Assistant voice pipeline (docs/home-assistant-voice.md)
  assist-only [ip]         accept the voice connection only from this address; no ip: anyone on R2's subnet
  pair                     give this PC its own admin key (press R2's pairing button first); saved to
                           secrets/robot-client.txt (docs/security.md)
  whoami                   which key this PC uses and what it may do
  clients                  list API clients (paired devices, tokens), their level and when they were last seen
  client-add <name> [--role read|control|admin] [--plain]
                           make a key for another device; --plain makes a token that can be sent as is
                           (Home Assistant), read or control only
  client-role <id> --role <r>   change a client's level
  client-revoke <id>       revoke a client's key
  rotate-owner             replace the robot's owner key (secrets/robot-token.txt is updated)
  update                   self-update settings and the latest release seen
  update-check | update-install
                           look for a newer GitHub release / download, verify and install it (R2 reboots)
  update-auto <on|off>     check every 6 hours and install while R2 is idle (off by default)
  update-source <owner/repo>   follow another repository's releases (also add its certificate: trust-add)
  update-fetch [owner/repo | folder] [--tag v1.2.3]
                           download a release on this PC (default: the one R2 follows), check its SHA256SUMS
                           and put it in the update mirror (apps/out/mirror); a folder such as apps/out puts
                           your own builds there (docs/releases.md: updating from a PC)
  update-push [folder | owner/repo] [--tag v1.2.3]
                           install R2 Main, then the agent, from a folder (default apps/out) or a release,
                           skipping what R2 already has (R2 reboots for a new agent)
  update-build             git pull, build both apps (apps/build.sh or build.ps1), then update-push apps/out
  update-serve [port]      serve the update mirror over HTTP (default port 8780) for R2 to download from
  update-mirror <url|off>  read releases from a copy on the local network, e.g. the Home Assistant add-on
                           (http://<host>:8765, docs/update-mirror.md); off: straight from GitHub
  trust | trust-add <sha256> | trust-remove <sha256>
                           signing certificates whose APKs R2 installs (its own is always trusted)
  assist-reply <voice|beeps>  answer in the pipeline's voice, or in droid beeps with the text as subtitles
  reboot | shutdown
  switch-source <owner/repo | folder> [--tag v1.2.3] [--yes]
                           move the robot to builds signed with another key (another fork's releases, or your
                           own builds in a folder such as apps/out), keeping settings (docs/releases.md).
                           No argument: show who signed the robot's apps and which source it follows.
  save-settings [folder]   back up the main app's and the agent's settings to the PC (over root adb)
  restore-settings <folder> [--agent]
                           put the main app's settings back from a backup; --agent also the agent's (then reboots)
  apk-info <file.apk>      an APK's signing certificate (SHA-256) and versionCode, as switch-source reads them

First-time install over adb (USB, or Wi-Fi adb after 'adb'), as tools/install-on-robot.ps1:
  install                  disable the stock app, put R2Agent in /system/priv-app, install R2Main, reboot
  refresh-agent            replace the /system/priv-app agent with apps/out/R2Agent.apk and reboot
  restore                  undo everything: factory software back, agent and main removed, adb on USB

Serial console (needs: pip install pyserial), as tools/capture-serial.ps1 and tools/serial-cmd.ps1:
  serial-capture --port P [--baud 115200] [--seconds 180] [--out boot.log]
  serial-cmd --port P --file tools/serial-commands/x.txt [--baud 115200] [--wait-ms 3000] [--out serial-cmd.log]

adb is taken from tools/platform-tools/ if it's there, otherwise from your PATH.
"""

import argparse
import getpass
import hashlib
import hmac
import json
import os
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

TOOLS = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(TOOLS)
SECRETS = os.path.join(ROOT, 'secrets')
CACHE = os.path.join(SECRETS, 'last-robot-ip.txt')
HOTSPOT_IP = '192.168.43.1'
API_PORT = 7700
ANNOUNCE_PORT = 7702

ACTIONS = ('stop wake turn-left turn-right turn-around forward circle patrol follow dance shake-head who-are-you noise '
           'not-recognized chirp whistle freak-out lightsaber arm short-lcd long-lcd flash-front flash-back leia luke mute unmute pair '
           'phone-app-on phone-app-off voice-on voice-off face-on face-off liveliness-off liveliness-quiet liveliness-chatty '
           'liveliness-full chirps-on chirps-off beeps-on beeps-off react-clap react-double-clap react-loud reactions-on '
           'reactions-off').split()
CHIRP_MOODS = ('random', 'ack', 'happy', 'excited', 'question', 'sad', 'worried', 'chatter', 'whistle', 'scream')


class R2Error(Exception):
    def __init__(self, message, code=None):
        super().__init__(message)
        self.code = code  # the HTTP status, for errors the agent sent


def q(value):
    return urllib.parse.quote(str(value), safe='')


def read_credentials():
    """(client id, key) to sign with: this PC's own key (pair), the robot's owner key (fetch-token), or the
    development key built into your own agent builds. None if there is none."""
    for name, client in (('robot-client.txt', None), ('robot-token.txt', 'owner'), ('agent-token.txt', 'dev')):
        path = os.path.join(SECRETS, name)
        if os.path.isfile(path):
            with open(path, encoding='utf-8-sig') as f:
                text = f.read().strip()
            if client is None:
                client, _, text = text.partition(':')
            return client, text
    return None


EMPTY_SHA256 = hashlib.sha256(b'').hexdigest()


def canonical(method, target, time_s, nonce, body_sha256):
    """The string a request signs (docs/security.md): the query as URL-decoded name=value pairs, sorted,
    without the r2client/r2time/r2nonce/r2sig parameters."""
    path, _, query = target.partition('?')
    pairs = []
    for kv in query.split('&') if query else []:
        k, eq, v = kv.partition('=')
        if not eq or not k:
            continue
        k = urllib.parse.unquote_plus(k)
        if k in ('r2client', 'r2time', 'r2nonce', 'r2sig'):
            continue
        pairs.append(k + '=' + urllib.parse.unquote_plus(v))
    pairs.sort()
    return '\n'.join(['R2-HMAC-SHA256', method, path, '&'.join(pairs), str(time_s), nonce, body_sha256])


def signed_headers(client, key, method, target, body_sha256, time_s, nonce=None):
    nonce = nonce or os.urandom(12).hex()
    sig = hmac.new(key.encode('utf-8'), canonical(method, target, time_s, nonce, body_sha256).encode('utf-8'),
                   hashlib.sha256).hexdigest()
    return {'X-R2-Client': client, 'X-R2-Time': str(time_s), 'X-R2-Nonce': nonce, 'X-R2-Body': body_sha256,
            'X-R2-Signature': sig}


def write_secret(name, text):
    os.makedirs(SECRETS, exist_ok=True)
    with open(os.path.join(SECRETS, name), 'w', encoding='ascii', newline='') as f:
        f.write(text)


def find_robot(seconds=8):
    """Listen for the agent's UDP announcement; returns the robot's address or None."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        s.bind(('', ANNOUNCE_PORT))
        s.settimeout(1.0)
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            try:
                data, (ip, _) = s.recvfrom(4096)
            except socket.timeout:
                continue
            try:
                msg = json.loads(data.decode('utf-8'))
            except ValueError:
                continue
            if isinstance(msg, dict) and msg.get('r2agent'):
                write_secret('last-robot-ip.txt', ip)
                return ip
    finally:
        s.close()
    return None


def ping(ip, timeout=2):
    """The /ping reply (a dict, or {} if it isn't JSON), or None if the robot doesn't answer."""
    try:
        with urllib.request.urlopen('http://%s:%d/ping' % (ip, API_PORT), timeout=timeout) as resp:
            reply = decode(resp.read(), resp.headers.get('Content-Type', ''))
            return reply if isinstance(reply, dict) else {}
    except Exception:
        return None


class Robot:
    def __init__(self, ip=None, credentials=None):
        self._ip = ip
        cred = read_credentials() if credentials is None else credentials
        self.client_id, self.key = cred if cred else (None, None)
        self._clock_offset = None  # robot time - this PC's time, in seconds; None: not asked yet
        self._signs = None  # False for agents from before signed requests: they take X-R2-Token

    def auth_headers(self, method, target, body_sha256):
        if not self.key:
            return {}
        now = int(time.time())
        if self._clock_offset is None:
            # Signed requests carry the robot's time (within 5 minutes), so sign with its clock, not this PC's.
            reply = ping(self.ip, timeout=5) or {}
            self._signs = 'time' in reply
            try:
                self._clock_offset = int(reply['time']) - now
            except (KeyError, TypeError, ValueError):
                self._clock_offset = 0
        if not self._signs:
            return {'X-R2-Token': self.key}
        return signed_headers(self.client_id, self.key, method, target, body_sha256, now + self._clock_offset)

    @property
    def ip(self):
        if not self._ip:
            candidates = []
            if os.path.isfile(CACHE):
                with open(CACHE, encoding='utf-8-sig') as f:
                    candidates.append(f.read().strip())
            candidates.append(HOTSPOT_IP)
            for ip in candidates:
                if ip and ping(ip):
                    self._ip = ip
                    break
            else:
                self._ip = find_robot()
            if not self._ip:
                raise R2Error('Robot not found. Join the same network as the robot (or its hotspot), '
                              'or pass --robot <ip>.')
        return self._ip

    def call(self, method, path, body=None, in_file=None, timeout=30, token=True):
        headers = {}
        data = None
        if in_file is not None:
            with open(in_file, 'rb') as f:
                data = f.read()
            headers['Content-Type'] = 'application/vnd.android.package-archive'
        elif body is not None:
            data = body.encode('utf-8') if isinstance(body, str) else body
            headers['Content-Type'] = 'application/json'
        elif method == 'POST':
            data = b''
        if token:
            headers.update(self.auth_headers(method, path, hashlib.sha256(data or b'').hexdigest()))
        req = urllib.request.Request('http://%s:%d%s' % (self.ip, API_PORT, path), data=data,
                                     headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return decode(resp.read(), resp.headers.get('Content-Type', ''))
        except urllib.error.HTTPError as e:
            reply = decode(e.read(), e.headers.get('Content-Type', ''))
            msg = reply.get('error') if isinstance(reply, dict) and reply.get('error') else reply
            raise R2Error('HTTP %d: %s' % (e.code, msg), e.code)
        except urllib.error.URLError as e:
            raise R2Error('cannot reach the robot at %s: %s' % (self.ip, e.reason))


def decode(raw, content_type):
    text = raw.decode('utf-8', errors='replace')
    if 'json' in content_type:
        try:
            return json.loads(text)
        except ValueError:
            pass
    return text


def show(value):
    """Print an API reply: text as is, a bare {"result": ...} as its text, other JSON indented."""
    if value is None:
        return
    if isinstance(value, dict) and list(value) == ['result']:
        value = value['result']
    if isinstance(value, (dict, list)):
        print(json.dumps(value, indent=2, ensure_ascii=False))
    else:
        text = str(value)
        print(text, end='' if text.endswith('\n') else '\n')


def table(rows, columns):
    rows = [['' if r.get(c) is None else str(r.get(c)) for c in columns] for r in rows]
    widths = [max([len(c)] + [len(r[i]) for r in rows]) for i, c in enumerate(columns)]
    lines = ['  '.join(c.ljust(w) for c, w in zip(columns, widths)).rstrip(),
             '  '.join('-' * w for w in widths)]
    lines += ['  '.join(v.ljust(w) for v, w in zip(r, widths)).rstrip() for r in rows]
    return '\n'.join(lines)


# ---- adb -------------------------------------------------------------------------------------------

def adb_exe():
    for name in ('adb.exe', 'adb'):
        path = os.path.join(TOOLS, 'platform-tools', name)
        if os.path.isfile(path):
            return path
    found = shutil.which('adb')
    if not found:
        raise R2Error('adb not found: unzip Android platform-tools to tools/platform-tools, or put adb on your PATH')
    return found


def adb(*args, check=False):
    p = subprocess.run([adb_exe()] + list(args), stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                       universal_newlines=True, encoding='utf-8', errors='replace')
    if check and p.returncode != 0:
        raise R2Error('adb %s failed:\n%s' % (' '.join(args), p.stdout))
    return p.stdout


def connect_adb(robot, minutes=30):
    """adb over Wi-Fi is off by default: ask the agent to switch it on, then connect. Returns the target."""
    show(robot.call('POST', '/adb?on=1&minutes=%d' % minutes))
    target = '%s:5555' % robot.ip
    out = ''
    for _ in range(5):  # adbd restarts on the robot; give it a moment
        out = adb('connect', target)
        if 'connected to' in out:
            break
        time.sleep(1)
    print(out.strip())
    return target


# ---- commands --------------------------------------------------------------------------------------

def need(arg, usage):
    if not arg:
        raise R2Error('usage: r2.py ' + usage)
    return arg


def cmd_find(robot, a):
    ip = find_robot(10)
    print('robot at %s' % ip if ip else 'no announcement heard')


def cmd_drive_lock(robot, a):
    if not a.arg:
        show(robot.call('GET', '/drive'))
    elif a.arg in ('on', 'off'):
        show(robot.call('POST', '/drive?lock=' + ('1' if a.arg == 'on' else '0')))
    else:
        raise R2Error('usage: r2.py drive-lock [on|off]')


def cmd_drive_enable(robot, a):
    m = a.arg or '10'
    if not m.isdigit():
        raise R2Error('usage: r2.py drive-enable [minutes]')
    show(robot.call('POST', '/drive?enable=1&minutes=' + m))


def cmd_chirp(robot, a):
    if not a.arg:
        show(robot.call('POST', '/chirp?mood=random'))
    elif a.arg in CHIRP_MOODS:
        show(robot.call('POST', '/chirp?mood=' + a.arg))
    else:
        show(robot.call('POST', '/chirp', a.arg))  # text: the body, so long sentences fit


def cmd_sounds(robot, a):
    d = robot.call('GET', '/sounds')
    print('R2 is muted' if d.get('muted') else 'volume %s%%' % d.get('volume'))
    print('factory sounds:')
    print(table([dict(s, moods=', '.join(s.get('moods') or [])) for s in d.get('sounds') or []],
                ['number', 'label', 'moods']))
    print('mood banks (r2.py sound <mood>): ' + ' '.join(m['name'] for m in d.get('moods') or []))
    print('chirps (r2.py chirp <mood>): ' + ' '.join(c['mood'] for c in d.get('chirps') or []))
    print('reactions (r2.py action <name>): ' + ', '.join(r['action'] + (' (your routine)' if r.get('custom') else '')
                                                         for r in d.get('reactions') or []))


def cmd_sound(robot, a):
    arg = need(a.arg, 'sound <number|mood|stop>  (see: r2.py sounds)')
    if arg == 'stop':
        show(robot.call('POST', '/sound/stop'))
    elif arg.isdigit():
        show(robot.call('POST', '/sound?number=' + arg))
    else:
        show(robot.call('POST', '/sound?mood=' + q(arg)))


def cmd_routines(robot, a):
    d = robot.call('GET', '/routines')
    line = 'running: %s' % (d.get('running') or '')
    if d.get('last_routine'):
        line += '   last: %s' % d['last_routine']
    print(line)
    if d.get('last_trigger'):
        print('last trigger: %s' % d['last_trigger'])
    rows = [dict(r, triggers='; '.join(str(t) for t in r['triggers']) if isinstance(r.get('triggers'), list)
                 else r.get('triggers')) for r in d.get('routines') or []]
    print(table(rows, ['name', 'steps', 'timeout', 'while_charging', 'replaces_action', 'triggers', 'description',
                       'error']))


def cmd_routine(robot, a):
    name = need(a.arg, 'routine <name>')
    show(robot.call('GET', '/routine?name=' + q(name)).get('routine'))


def cmd_routine_put(robot, a):
    if not a.arg or not os.path.isfile(a.arg):
        raise R2Error('usage: r2.py routine-put <file.json>  (examples: docs/routines/)')
    with open(a.arg, encoding='utf-8-sig') as f:
        show(robot.call('POST', '/routine', f.read()))


def cmd_places(robot, a):
    d = robot.call('GET', '/places')
    line = 'R2 is at: %s' % d.get('location')
    if d.get('teaching'):
        line += '   teaching: %s' % d['teaching']
    if d.get('last_trip'):
        line += '   last: %s' % d['last_trip']
    print(line)
    print('turn-around taught: %s s   charger is home: %s   patrol tours: %s'
          % (d.get('turn_around_seconds'), d.get('charger_is_home'), d.get('patrol_tour')))
    print(table(d.get('places') or [], ['name', 'spoken', 'there_seconds', 'there_segments', 'way_back', 'area']))


def put_file(robot, a, path, usage):
    if not a.arg or not os.path.isfile(a.arg):
        raise R2Error('usage: r2.py ' + usage)
    with open(a.arg, encoding='utf-8-sig') as f:
        show(robot.call('POST', path, f.read()))


def cmd_cues(robot, a):
    rows = []
    for c in robot.call('GET', '/cues').get('cues') or []:
        c = dict(c)
        if isinstance(c.get('match'), list):
            c['match'] = ', '.join(str(m) for m in c['match'])
        rows.append(c)
    print(table(rows, ['name', 'title', 'cues', 'offset', 'match', 'error']))


def cmd_cue(robot, a):
    show(robot.call('GET', '/cue?name=' + q(need(a.arg, 'cue <name>'))).get('cue'))


def cmd_cue_put(robot, a):
    if not a.arg or not os.path.isfile(a.arg):
        raise R2Error('usage: r2.py cue-put <file.json>  (examples: docs/watch-with-me/)')
    with open(a.arg, encoding='utf-8-sig') as f:
        show(robot.call('POST', '/cue', f.read()))


def post_watch(robot, body):
    show(robot.call('POST', '/watch', json.dumps(body, separators=(',', ':'))))


def cmd_watch_start(robot, a):
    cue = need(a.arg, 'watch-start <cue file> [--at h:mm:ss]  (list: r2.py cues)')
    post_watch(robot, {'state': 'playing', 'cue': cue, 'position': a.at if a.at else 0})


def cmd_watch_resume(robot, a):
    body = {'state': 'playing'}
    if a.at:
        body['position'] = a.at
    post_watch(robot, body)


def cmd_volume(robot, a):
    if not (a.arg or '').isdigit():
        raise R2Error('usage: r2.py volume <0-100>')
    show(robot.call('POST', '/volume?percent=' + a.arg))


def cmd_liveliness(robot, a):
    if not a.arg:
        show(robot.call('GET', '/status').get('liveliness'))
    elif a.arg.lower() not in ('off', 'quiet', 'chatty', 'full'):
        raise R2Error('usage: r2.py liveliness <off|quiet|chatty|full>')
    else:
        show(robot.call('POST', '/action?name=liveliness-' + a.arg.lower()))


def cmd_plugins(robot, a):
    d = robot.call('GET', '/plugins')
    rows = [dict(pl, granted=','.join(pl.get('granted') or []), asks_for=','.join(pl.get('asks_for') or []))
            for pl in d.get('plugins') or []]
    print(table(rows, ['name', 'enabled', 'state', 'granted', 'asks_for']))
    print('permissions:')
    for k, v in (d.get('permissions') or {}).items():
        print('  %s: %s' % (k, v))


def cmd_plugin_switch(robot, a):
    name = need(a.arg, '%s <name>  (see: r2.py plugins)' % a.command)
    show(robot.call('POST', '/plugin?name=%s&enabled=%d' % (q(name), 1 if a.command == 'plugin-on' else 0)))


def cmd_plugin_grant(robot, a):
    name, eq, perms = (a.arg or '').partition('=')
    if not name or not eq:
        raise R2Error('usage: r2.py plugin-grant <name>=<permissions>, e.g. battery-alert=status,sound')
    show(robot.call('POST', '/plugin?name=%s&grant=%s' % (q(name), q(perms))))


def cmd_plugin_config(robot, a):
    name, _, path = (a.arg or '').partition('=')
    if not name or not path or not os.path.isfile(path):
        raise R2Error('usage: r2.py plugin-config <name>=<file.json>')
    with open(path, encoding='utf-8-sig') as f:
        show(robot.call('POST', '/plugin/config?name=' + q(name), f.read()))


def cmd_logs(robot, a):
    query = '?lines=%d' % a.lines
    if a.arg:
        query += '&grep=' + q(a.arg)
    out = robot.call('GET', '/%s%s' % (a.command, query))
    if a.command == 'dmesg' and isinstance(out, str) and out.startswith('klogctl'):
        # This kernel restricts dmesg to root, so read it through root adb instead.
        target = connect_adb(robot)
        lines = adb('-s', target, 'shell', 'dmesg').splitlines()
        if a.arg:
            lines = [l for l in lines if a.arg in l]
        out = '\n'.join(lines[-a.lines:])
    show(out)


def cmd_fetch_token(robot, a):
    # During the pairing button's setup window the agent hands the token out without one;
    # otherwise read it over root adb (which needs a working token or the built-in one).
    t = ''
    try:
        reply = robot.call('GET', '/setup/token', timeout=5, token=False)
        if isinstance(reply, dict):
            t = reply.get('token') or ''
    except R2Error:
        pass
    if not t:
        target = connect_adb(robot)
        t = adb('-s', target, 'shell', 'cat /data/data/dev.r2hack.agent/files/api-token').strip()
    if len(t) != 48 or any(c not in '0123456789abcdef' for c in t):
        raise R2Error('no token found (is the agent updated and running?)')
    write_secret('robot-token.txt', t)
    print('saved to %s (the owner key: keep it private; it always works, so it is your way back in)'
          % os.path.join('secrets', 'robot-token.txt'))


def cmd_adb(robot, a):
    if a.arg and not a.arg.isdigit():
        raise R2Error('usage: r2.py adb [minutes]')
    connect_adb(robot, int(a.arg) if a.arg else 30)


def cmd_allow_subnets(robot, a):
    if a.arg is not None:
        show(robot.call('POST', '/clients/allow?subnets=' + q(a.arg)))
    else:
        show(robot.call('GET', '/clients/allow').get('extra_subnets'))


def cmd_assist_reply(robot, a):
    if a.arg not in ('voice', 'beeps'):
        raise R2Error('usage: r2.py assist-reply <voice|beeps>')
    show(robot.call('POST', '/action?name=' + ('beeps-on' if a.arg == 'beeps' else 'beeps-off')))


def cmd_harden_on(robot, a):
    show(robot.call('POST', '/hardening?firewall=1&devices=1&bluetooth=1'))
    time.sleep(2)
    # A new connection through the firewall: if it gets through, the firewall can stay.
    try:
        robot.call('GET', '/status', timeout=10)
    except R2Error as e:
        raise R2Error('The API stopped answering with the firewall on, so it was NOT confirmed; '
                      'it removes itself within 5 minutes. (%s)' % e)
    show(robot.call('POST', '/hardening/confirm'))


def cmd_mqtt(robot, a):
    if not a.arg:
        show(robot.call('GET', '/mqtt'))
    elif a.arg == 'off':
        show(robot.call('POST', '/mqtt', '{"enabled":false}'))
    else:
        host, _, port = a.arg.partition(':')
        body = {'host': host, 'port': int(port) if port else 1883}
        if a.user is not None:
            body['username'] = a.user
            body['password'] = getpass.getpass('MQTT password for %s: ' % a.user) if a.user else ''
        show(robot.call('POST', '/mqtt', json.dumps(body, separators=(',', ':'))))


def cmd_push(robot, a):
    default = 'R2Main.apk' if a.command == 'push-main' else 'R2Agent.apk'
    apk = a.arg or os.path.join(ROOT, 'apps', 'out', default)
    if not os.path.isfile(apk):
        raise R2Error('missing %s (build it with apps/build.sh, or pass the apk)' % apk)
    path = '/main/install' if a.command == 'push-main' else '/agent/install'
    show(robot.call('POST', path, in_file=apk, timeout=300))


def cmd_serial(robot, a):
    show(robot.call('POST', '/serial', need(a.arg, "serial '<json>'")))


def cmd_wifi_scan(robot, a):
    nets = robot.call('GET', '/wifi/scan').get('networks') or []
    nets.sort(key=lambda n: n.get('rssi') if isinstance(n.get('rssi'), (int, float)) else -999, reverse=True)
    print(table(nets, ['ssid', 'rssi']))


def cmd_wifi_join(robot, a):
    ssid = need(a.arg, 'wifi-join <ssid>')
    password = getpass.getpass('Password for %s (Enter for an open network): ' % ssid)
    show(robot.call('POST', '/wifi/join', json.dumps({'ssid': ssid, 'password': password},
                                                     separators=(',', ':'))))


def show_hotspot(h):
    print('name:     %s' % h.get('ssid'))
    print('password: %s' % h.get('password'))


def cmd_hotspot(robot, a):
    # In the setup window this works without a token; otherwise with the token, or over root adb.
    h = None
    try:
        h = robot.call('GET', '/hotspot', timeout=5)
    except R2Error:
        pass
    if isinstance(h, dict):
        show_hotspot(h)
        if h.get('using') == 'factory':
            print('(running with the factory settings right now: %s s left)' % h.get('factory_seconds_left'))
    else:
        target = connect_adb(robot)
        print('password: ' + adb('-s', target, 'shell', 'cat /data/data/dev.r2hack.agent/files/hotspot-password').strip())


def cmd_hotspot_password(robot, a):
    plain = ''
    if a.arg != 'new':
        plain = getpass.getpass('New hotspot password (8-63 characters; Enter for a random one): ')
    h = robot.call('POST', '/hotspot', json.dumps({'password': plain}, separators=(',', ':')))
    if isinstance(h, dict):
        show_hotspot(h)
        if h.get('result'):
            print(h['result'])
    else:
        show(h)


def cmd_pair(robot, a):
    # Works in the pairing button's 2-minute window, or with an admin key already in place.
    c = robot.call('POST', '/clients/pair?name=' + q('PC ' + socket.gethostname()))
    write_secret('robot-client.txt', '%s:%s' % (c['id'], c['key']))
    print('paired as %s (%s); key saved to %s' % (c['id'], c.get('role'), os.path.join('secrets', 'robot-client.txt')))


def cmd_clients(robot, a):
    rows = []
    for c in robot.call('GET', '/clients').get('clients') or []:
        seen = c.get('last_seen')
        rows.append({'id': c.get('id'), 'name': c.get('name'), 'role': c.get('role'), 'plain': c.get('plain_token'),
                     'last_seen': time.strftime('%Y-%m-%d %H:%M:%S', time.localtime(seen)) if seen else 'never',
                     'last': c.get('last_request')})
    print(table(rows, ['id', 'name', 'role', 'plain', 'last_seen', 'last']))


def cmd_client_add(robot, a):
    name = need(a.arg, 'client-add <name> [--role read|control|admin] [--plain]')
    query = 'name=%s&role=%s' % (q(name), q(a.role or 'control')) + ('&plain=1' if a.plain else '')
    c = robot.call('POST', '/clients/add?' + query)
    print('id:   %s   (%s%s)' % (c['id'], c.get('role'), ', plain token' if c.get('plain_token') else ''))
    print('token: %s' % c['key'] if c.get('plain_token') else 'key:  %s:%s' % (c['id'], c['key']))
    print('Shown only now. Revoke with: r2.py client-revoke ' + c['id'])


def cmd_client_role(robot, a):
    if not a.arg or not a.role:
        raise R2Error('usage: r2.py client-role <id> --role read|control|admin')
    show(robot.call('POST', '/clients/role?id=%s&role=%s' % (q(a.arg), q(a.role))))


def cmd_rotate_owner(robot, a):
    if robot.client_id != 'owner':
        raise R2Error('sign with the owner key for this: move secrets/robot-client.txt away for a moment, '
                      'or run fetch-token first')
    c = robot.call('POST', '/clients/owner/rotate')
    write_secret('robot-token.txt', c['key'])
    print('new owner key saved to %s; the old one no longer works' % os.path.join('secrets', 'robot-token.txt'))


def cmd_update_auto(robot, a):
    if a.arg not in ('on', 'off'):
        raise R2Error('usage: r2.py update-auto <on|off>')
    show(robot.call('POST', '/update/auto?on=' + ('1' if a.arg == 'on' else '0')))


def cmd_update_source(robot, a):
    import re
    if not re.match(r'^[\w.-]+/[\w.-]+$', a.arg or ''):
        raise R2Error('usage: r2.py update-source <owner/repo>')
    show(robot.call('POST', '/update/source?repo=' + q(a.arg)))


def cmd_update_mirror(robot, a):
    import re
    url = 'off' if a.arg == 'off' else a.arg or ''
    if url != 'off' and not re.match(r'^https?://[^/?#@\s]+(/[^?#@\s]*)?$', url):
        raise R2Error('usage: r2.py update-mirror <http://host:port | off>')
    show(robot.call('POST', '/update/mirror?url=' + q('' if url == 'off' else url)))


def identity_body(xml_text):
    """The /identity request body from the factory app's shared_prefs/robot.xml."""
    if '<map' not in xml_text:
        raise R2Error('factory app settings not found on the robot')
    root = ET.fromstring(xml_text[xml_text.index('<'):])
    strings = {s.get('name'): (s.text or '') for s in root.iter('string')}
    body = {'name': strings.get('robot_name'), 'uuid': strings.get('robot_udid')}
    clients = strings.get('clientList')
    if clients:
        body['clients'] = json.loads(clients)
    return json.dumps(body, separators=(',', ':'))


def cmd_import_identity(robot, a):
    target = connect_adb(robot)
    xml_text = adb('-s', target, 'shell', 'cat /data/data/com.bullb.r2d2_nanopisystem/shared_prefs/robot.xml')
    show(robot.call('POST', '/identity', identity_body(xml_text)))


def cmd_assist(robot, a):
    if a.arg not in ('off', 'robot', 'ha'):
        raise R2Error('usage: r2.py assist <off|robot|ha>')
    show(robot.call('POST', '/assist?mode=' + a.arg))


def simple(method, path):
    return lambda robot, a: show(robot.call(method, path))


def with_name(path, usage):
    return lambda robot, a: show(robot.call('POST', path + q(need(a.arg, usage))))


# ---- first-time install (install-on-robot.ps1) -----------------------------------------------------

def sh(cmd):
    out = adb('shell', cmd)
    print('  > ' + cmd)
    for line in out.splitlines():
        print('    ' + line)
    return out


def apk_path(name):
    return os.path.join(ROOT, 'apps', 'out', name)


def install_preflight():
    devices = adb('devices')
    if not any(l.rstrip().endswith('\tdevice') for l in devices.splitlines()):
        raise R2Error('No adb device. Connect the robot over USB first.')
    if 'uid=0' not in sh('id'):
        raise R2Error('adb is not running as root on the robot.')


def push_system_agent():
    sh('mount -o remount,rw /system')
    print(adb('push', apk_path('R2Agent.apk'), '/system/priv-app/R2Agent.apk', check=True).strip())
    sh('chmod 644 /system/priv-app/R2Agent.apk')
    sh('sync; mount -o remount,ro /system')


def cmd_install(robot, a):
    for f in (apk_path('R2Agent.apk'), apk_path('R2Main.apk'), os.path.join(ROOT, 'backup', 'mmcblk0_full.img')):
        if not os.path.isfile(f):
            raise R2Error('missing ' + os.path.relpath(f, ROOT))
    install_preflight()
    sh('pm disable com.bullb.r2d2_nanopisystem')
    push_system_agent()
    print(adb('install', '-r', apk_path('R2Main.apk'), check=True).strip())
    # adb over Wi-Fi is off by default from now on: the agent switches it on only when asked
    # (r2.py adb, for 30 minutes), because this build's adb is a root shell with no key check.
    sh('setprop persist.adb.tcp.port -1')
    print('Rebooting. Give it ~60 s, then: r2.py status ; r2.py adb (switches Wi-Fi adb on for 30 min)')
    adb('reboot')


def cmd_refresh_agent(robot, a):
    if not os.path.isfile(apk_path('R2Agent.apk')):
        raise R2Error('missing apps/out/R2Agent.apk')
    install_preflight()
    push_system_agent()
    # On boot Android sees the system copy is newer than the over-the-air copy in /data, drops that copy
    # and keeps the agent's data (token, last good build).
    print('Rebooting. Give it ~60 s, then: r2.py status')
    adb('reboot')


def cmd_restore(robot, a):
    install_preflight()
    sh('pm uninstall dev.r2hack.main')
    sh('pm uninstall dev.r2hack.agent')  # removes an over-the-air agent update, if any
    sh('mount -o remount,rw /system')
    sh('rm /system/priv-app/R2Agent.apk')
    sh('sync; mount -o remount,ro /system')
    sh('pm enable com.bullb.r2d2_nanopisystem')
    sh('setprop persist.adb.tcp.port 0')  # adb back on USB after the reboot
    print('Factory software restored. Rebooting.')
    adb('reboot')


# ---- APK certificate and versionCode (apk-info.ps1) ------------------------------------------------

def _der(b, i):
    """One DER element at b[i]: (tag, content start, content end)."""
    tag = b[i]
    n = b[i + 1]
    i += 2
    if n & 0x80:
        k = n & 0x7f
        n = int.from_bytes(b[i:i + k], 'big')
        i += k
    return tag, i, i + n


def _der_children(b, start, end):
    out = []
    while start < end:
        tag, s, e = _der(b, start)
        out.append((tag, start, s, e))
        start = e
    return out


def _read_apk_entry(apk, match):
    import re
    import zipfile
    with zipfile.ZipFile(apk) as z:
        for name in z.namelist():
            if re.match(match, name):
                return z.read(name)
    return None


def apk_cert(apk):
    """SHA-256 of the signing certificate, lowercase hex: the same value as apksigner's "Signer #1 certificate
    SHA-256 digest" and the one in the release notes. Read from the v1 (JAR) signature, which our builds always
    have because they support Android 4.4."""
    p7 = _read_apk_entry(apk, r'^META-INF/[^/]+\.(RSA|DSA|EC)$')
    if not p7:
        raise R2Error('%s has no v1 (JAR) signature' % apk)
    # ContentInfo SEQUENCE { contentType OID, [0] { SignedData SEQUENCE { version, digestAlgorithms,
    # encapContentInfo, [0] certificates, ... } } }
    _, s, e = _der(p7, 0)
    ci = _der_children(p7, s, e)
    _, s, e = _der(p7, ci[1][2])
    for tag, start, cs, ce in _der_children(p7, s, e):
        if tag == 0xa0:  # certificates [0] IMPLICIT SET OF Certificate: we sign with one key, so the first
            _, _, cert_end = _der(p7, cs)
            return hashlib.sha256(p7[cs:cert_end]).hexdigest()
    raise R2Error('%s: no certificate in its signature' % apk)


def apk_version_code(apk):
    """android:versionCode from the binary AndroidManifest.xml."""
    import struct
    b = _read_apk_entry(apk, r'^AndroidManifest\.xml$')
    if not b:
        raise R2Error('%s has no AndroidManifest.xml' % apk)
    u16 = lambda o: struct.unpack_from('<H', b, o)[0]
    u32 = lambda o: struct.unpack_from('<I', b, o)[0]
    if u16(0) != 0x0003:
        raise R2Error('%s: AndroidManifest.xml is not binary XML' % apk)
    res_ids = []
    off = u16(2)  # first chunk after the file header
    while off + 8 <= len(b):
        ctype, header_size, size = u16(off), u16(off + 2), u32(off + 4)
        if size < 8:
            break
        if ctype == 0x0180:  # resource map: attribute name index -> resource id
            res_ids = [u32(off + header_size + 4 * i) for i in range((size - header_size) // 4)]
        elif ctype == 0x0102:  # first start tag: <manifest>
            ext = off + header_size
            attr_start, attr_size, attr_count = u16(ext + 8), u16(ext + 10), u16(ext + 12)
            for i in range(attr_count):
                at = ext + attr_start + i * attr_size
                name = u32(at + 4)
                if name < len(res_ids) and res_ids[name] == 0x0101021b:  # android:versionCode
                    return struct.unpack_from('<i', b, at + 16)[0]
            raise R2Error('%s: no versionCode in the manifest' % apk)
        off += size
    raise R2Error('%s: manifest tag not found' % apk)


def cmd_apk_info(robot, a):
    apk = need(a.arg, 'apk-info <file.apk>')
    if not os.path.isfile(apk):
        raise R2Error('no such file: ' + apk)
    print('cert %s  versionCode %d' % (apk_cert(apk), apk_version_code(apk)))


# ---- switching signers (switch-source, save-settings, restore-settings). Plan: docs/releases.md ----

APPS = {
    'main': {'pkg': 'dev.r2hack.main', 'apk': 'R2Main.apk', 'dirs': ['shared_prefs', 'files/routines', 'files/cues'],
             'files': ['files/voice/keywords.txt', 'files/places.json']},
    'agent': {'pkg': 'dev.r2hack.agent', 'apk': 'R2Agent.apk', 'dirs': ['shared_prefs'], 'files': []},
}
SOURCE_FILE = os.path.join(SECRETS, 'robot-source.json')


def adb_shell(target, cmd):
    return [l.rstrip('\r') for l in adb('-s', target, 'shell', cmd).splitlines()]


def robot_app(target, name):
    """Installed app on the robot: APK path, versionCode and Linux uid (from dumpsys, as KitKat has no grep)."""
    import re
    pkg = APPS[name]['pkg']
    paths = [l for l in adb_shell(target, 'pm path ' + pkg) if l.startswith('package:')]
    if not paths:
        return None
    dump = '\n'.join(adb_shell(target, 'dumpsys package ' + pkg))
    info = {'path': paths[0][8:].strip(), 'version': 0, 'uid': None, 'cert': None}
    m = re.search(r'versionCode=(\d+)', dump)
    if m:
        info['version'] = int(m.group(1))
    m = re.search(r'userId=(\d+)', dump)
    if m:
        info['uid'] = m.group(1)
    return info


def robot_signer(target, name, folder):
    """Pulls the installed APK to folder and reads its certificate there."""
    info = robot_app(target, name)
    if not info:
        return None
    local = os.path.join(folder, APPS[name]['apk'])
    adb('-s', target, 'pull', info['path'], local)
    if not os.path.isfile(local):
        raise R2Error('could not copy %s from the robot' % info['path'])
    info['cert'] = apk_cert(local)
    return info


def save_app_settings(target, name, folder):
    """Copies an app's settings (shared_prefs and the files listed in APPS) to folder/<app>/. Returns the count."""
    import re
    data = '/data/data/' + APPS[name]['pkg']
    rels = list(APPS[name]['files'])
    for d in APPS[name]['dirs']:
        # Entries are plain names; an error line ("...: No such file or directory") has a colon or slash.
        # *.bak is a half-written preferences file, which Android would restore over the real one.
        rels += ['%s/%s' % (d, e) for e in adb_shell(target, 'ls %s/%s' % (data, d))
                 if e and not re.search(r'[:/]', e) and not e.endswith('.bak')]
    n = 0
    for rel in rels:
        if '%s/%s' % (data, rel) not in adb_shell(target, "ls '%s/%s'" % (data, rel)):
            continue  # optional file not there
        local = os.path.join(folder, name, *rel.split('/'))
        os.makedirs(os.path.dirname(local), exist_ok=True)
        adb('-s', target, 'pull', '%s/%s' % (data, rel), local)
        if not os.path.isfile(local):
            raise R2Error('could not copy %s/%s' % (data, rel))
        n += 1
    return n


def restore_app_settings(target, name, folder):
    """Puts saved settings back and gives them to the app's current uid (a reinstall gets a new one).
    The app must not be running, or it may write its old settings over them."""
    base = os.path.join(folder, name)
    if not os.path.isdir(base):
        return 0
    info = robot_app(target, name)
    if not info or not info['uid']:
        raise R2Error('%s is not installed' % APPS[name]['pkg'])
    data = '/data/data/' + APPS[name]['pkg']
    uid = info['uid']
    cmds, dirs, files = [], set(), 0
    for dirpath, _, names in os.walk(base):
        for fn in names:
            rel = os.path.relpath(os.path.join(dirpath, fn), base).replace(os.sep, '/')
            adb('-s', target, 'push', os.path.join(dirpath, fn), '%s/%s' % (data, rel))
            cmds.append("chown %s:%s '%s/%s'; chmod 660 '%s/%s'" % (uid, uid, data, rel, data, rel))
            files += 1
            parts = rel.split('/')
            for i in range(1, len(parts)):
                dirs.add('/'.join(parts[:i]))
    for d in sorted(dirs):
        cmds.append("chown %s:%s '%s/%s'; chmod 771 '%s/%s'" % (uid, uid, data, d, data, d))
    for i in range(0, len(cmds), 10):  # a few at a time: adb shell command lines are short on KitKat
        adb_shell(target, '; '.join(cmds[i:i + 10]))
    adb_shell(target, 'restorecon -R ' + data)
    return files


def token_works(robot):
    """False when the agent no longer accepts our key (it lost its data); raises on other errors."""
    try:
        robot.call('GET', '/status')
        return True
    except R2Error as e:
        if e.code in (401, 403):
            return False
        raise


def wait_robot(robot, seconds):
    deadline = time.monotonic() + seconds
    time.sleep(20)
    while time.monotonic() < deadline:
        if ping(robot.ip, timeout=3) is not None:
            robot._clock_offset = None  # it rebooted: ask for its clock again
            return True
        time.sleep(5)
    return False


def http_get(url, timeout=30, accept=None):
    headers = {'User-Agent': 'r2-switch-source'}
    if accept:
        headers['Accept'] = accept
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=timeout) as resp:
        return resp.read()


def get_source(spec, tag):
    """Where the new APKs come from: a folder with R2Agent.apk and R2Main.apk, or the latest (or --tag) GitHub
    release of owner/repo, downloaded to apps/out/releases/ and checked against its SHA256SUMS."""
    import re
    if os.path.isdir(spec):
        d = os.path.abspath(spec)
        return {'dir': d, 'label': d, 'repo': None, 'tag': None, 'note_cert': None, 'published': '', 'url': ''}
    if not re.match(r'^[A-Za-z0-9-]+/[A-Za-z0-9._-]+$', spec):
        raise R2Error('not a folder or owner/repo: ' + spec)
    api = 'https://api.github.com/repos/%s/releases/%s' % (spec, 'tags/' + tag if tag else 'latest')
    try:
        rel = json.loads(http_get(api, accept='application/vnd.github+json').decode('utf-8'))
    except Exception as e:
        raise R2Error('no release found at %s (%s)' % (api, e))
    d = os.path.join(ROOT, 'apps', 'out', 'releases', '%s-%s' % (spec.replace('/', '-'), rel['tag_name']))
    os.makedirs(d, exist_ok=True)
    for name in ('R2Agent.apk', 'R2Main.apk', 'SHA256SUMS'):
        asset = next((x for x in rel.get('assets') or [] if x.get('name') == name), None)
        if not asset:
            raise R2Error('release %s of %s has no %s' % (rel['tag_name'], spec, name))
        print('downloading %s from %s %s' % (name, spec, rel['tag_name']))
        with open(os.path.join(d, name), 'wb') as f:
            f.write(http_get(asset['browser_download_url'], timeout=300))
    with open(os.path.join(d, 'SHA256SUMS'), encoding='utf-8') as f:
        for line in f:
            m = re.match(r'^([0-9a-fA-F]{64})\s+\*?(\S+)$', line.strip())
            if not m:
                continue
            with open(os.path.join(d, m.group(2)), 'rb') as g:
                if hashlib.sha256(g.read()).hexdigest() != m.group(1).lower():
                    raise R2Error('%s does not match SHA256SUMS: the download is damaged or was changed' % m.group(2))
    m = re.search(r'certificate SHA-256:\s*`([0-9a-f]{64})`', rel.get('body') or '')
    return {'dir': d, 'label': '%s %s' % (spec, rel['tag_name']), 'repo': spec, 'tag': rel['tag_name'],
            'note_cert': m.group(1) if m else None, 'published': rel.get('published_at') or '',
            'url': rel.get('html_url') or ''}


def stamp():
    return time.strftime('%Y%m%d-%H%M%S')


def cmd_save_settings(robot, a):
    folder = a.arg or os.path.join(SECRETS, 'settings-' + stamp())
    target = connect_adb(robot)
    for name in ('agent', 'main'):
        print('%s: %d files' % (name, save_app_settings(target, name, folder)))
    print('saved to %s (it holds the API token and Wi-Fi and MQTT passwords: keep it private)' % folder)


def cmd_restore_settings(robot, a):
    if not a.arg or not os.path.isdir(a.arg):
        raise R2Error('usage: r2.py restore-settings <folder> [--agent]')
    target = connect_adb(robot)
    robot.call('POST', '/safe-mode?on=1')  # stops the main app so it can't overwrite what comes back
    print('main: %d files restored' % restore_app_settings(target, 'main', a.arg))
    if a.agent:
        print('agent: %d files restored, rebooting (the agent takes its token from the backup)'
              % restore_app_settings(target, 'agent', a.arg))
        adb('-s', target, 'reboot')
    else:
        robot.call('POST', '/safe-mode?on=0')
        print('main app restarted')


def cmd_switch_source(robot, a):
    target = connect_adb(robot, 60)
    backup = os.path.join(SECRETS, 'switch-backup-' + stamp())
    os.makedirs(backup, exist_ok=True)
    old = {name: robot_signer(target, name, backup) for name in ('agent', 'main')}
    recorded = None
    if os.path.isfile(SOURCE_FILE):
        with open(SOURCE_FILE, encoding='utf-8-sig') as f:
            recorded = json.load(f)
    if not a.arg:
        shutil.rmtree(backup)
        for name in ('agent', 'main'):
            if old[name]:
                print('%-6s signed by %s  (versionCode %s)' % (name, old[name]['cert'], old[name]['version']))
            else:
                print('%-6s not installed' % name)
        print('source: %s, switched %s' % (recorded.get('source'), recorded.get('switched')) if recorded
              else 'source: not recorded (never switched from this PC)')
        return

    src = get_source(a.arg, a.tag)
    new = {}
    for name in ('agent', 'main'):
        f = os.path.join(src['dir'], APPS[name]['apk'])
        if not os.path.isfile(f):
            raise R2Error('missing ' + f)
        new[name] = {'file': f, 'cert': apk_cert(f), 'version': apk_version_code(f)}
    if new['agent']['cert'] != new['main']['cert']:
        raise R2Error('the two APKs in %s are signed with different keys' % src['label'])
    if src['note_cert'] and src['note_cert'] != new['main']['cert']:
        raise R2Error('the release notes name certificate %s, but the APKs are signed with %s'
                      % (src['note_cert'], new['main']['cert']))
    change = {name: not old[name] or old[name]['cert'] != new[name]['cert'] for name in ('agent', 'main')}
    if not old['agent']:
        raise R2Error('the agent is not installed: use r2.py install first')
    # The agent lives in /system. When there is also an over-the-air update in /data (push-agent), Android
    # uses the /system copy at boot only if it is at least as new; otherwise the switch would silently not happen.
    if change['agent'] and not old['agent']['path'].startswith('/system/') \
            and new['agent']['version'] < old['agent']['version']:
        raise R2Error("the new agent (versionCode %s) is older than the robot's over-the-air update (%s). "
                      'Pick a newer release.' % (new['agent']['version'], old['agent']['version']))

    print('From: %s' % (recorded.get('source') if recorded else 'current builds'))
    print('  agent  %s' % old['agent']['cert'])
    print('  main   %s' % (old['main']['cert'] if old['main'] else 'not installed'))
    print('To:   %s' % src['label'])
    print('  both   %s' % new['main']['cert'])
    if not change['agent'] and not change['main']:
        shutil.rmtree(backup)
        print('Same signer as on the robot: no switch needed. Update in place with push-main and push-agent.')
        return
    print('Changes: %s (reinstalled with the new key; settings are kept)'
          % ' and '.join(n for n in ('agent', 'main') if change[n]))
    if not a.yes and input('Compare the certificate with the one the source publishes. Type yes to switch: ').strip() != 'yes':
        shutil.rmtree(backup)
        print('cancelled, nothing changed')
        return

    # 1. Back up. The agent first, so its saved settings don't include the safe mode set next.
    print('agent settings: %d files' % save_app_settings(target, 'agent', backup))
    robot.call('POST', '/safe-mode?on=1')  # main app stopped and not relaunched while we work
    if old['main']:
        print('main settings: %d files' % save_app_settings(target, 'main', backup))
    with open(os.path.join(backup, 'switch.json'), 'w', encoding='utf-8') as f:
        json.dump({'from': recorded, 'to': src['label'], 'backup': backup}, f, indent=2)
    print('backup with the old APKs: ' + backup)

    # 2. Main app: uninstall (Android refuses an update from another key), install, put settings back.
    if change['main']:
        if old['main']:
            adb_shell(target, 'pm uninstall ' + APPS['main']['pkg'])
        out = adb('-s', target, 'install', '-r', new['main']['file'])
        if 'Success' not in out:
            print('WARNING: main app install failed: ' + out.strip(), file=sys.stderr)
            if old['main']:
                adb('-s', target, 'install', '-r', os.path.join(backup, APPS['main']['apk']))
                restore_app_settings(target, 'main', backup)
                print('the previous main app and its settings are back')
            robot.call('POST', '/safe-mode?on=0')
            raise R2Error('switch stopped; the agent was not changed')
        print('main app installed, %d settings files restored' % restore_app_settings(target, 'main', backup))
        # The agent's rollback copy is signed with the old key: drop it. The new build becomes the
        # rollback copy once it has run through probation.
        adb_shell(target, 'rm /data/data/%s/files/main-lastgood.apk' % APPS['agent']['pkg'])

    # 3. Agent: replace the /system copy and reboot. Android 4.4 accepts a new certificate for a system app
    #    at boot ("signature changed; retaining data"), drops an older over-the-air copy, and keeps the data.
    if change['agent']:
        adb_shell(target, 'mount -o remount,rw /system')
        adb('-s', target, 'push', new['agent']['file'], '/system/priv-app/R2Agent.apk')
        adb_shell(target, 'chmod 644 /system/priv-app/R2Agent.apk; sync; mount -o remount,ro /system')
        print('agent replaced in /system, rebooting (about a minute)')
        adb('-s', target, 'reboot')
        if not wait_robot(robot, 240):
            raise R2Error('the robot did not come back within 4 minutes. Backup: ' + backup)
        if not token_works(robot):
            print('WARNING: The agent no longer accepts the saved key, so it lost its settings in the switch.',
                  file=sys.stderr)
            print("To get them back: press the pairing button on R2, run 'r2.py fetch-token', then")
            print("'r2.py restore-settings %s --agent'. Please report this: docs/releases.md expects the data to stay."
                  % backup)
            return
        target = connect_adb(robot, 30)
        after = os.path.join(backup, 'after')
        os.makedirs(after, exist_ok=True)
        now = robot_signer(target, 'agent', after)
        if not now or now['cert'] != new['agent']['cert']:
            raise R2Error('the robot still runs the old agent (%s). Backup: %s' % (now and now['path'], backup))
        print('agent switched, settings kept')
    robot.call('POST', '/safe-mode?on=0')
    # The agent's own certificate is now the new one, so it trusts this source's updates; point its
    # self-update at the same repository (docs/security.md). An agent without self-update answers 404.
    if src['repo']:
        try:
            show(robot.call('POST', '/update/source?repo=' + q(src['repo'])))
        except R2Error as e:
            print("WARNING: could not set the agent's update source (%s); later: r2.py update-source %s"
                  % (e, src['repo']), file=sys.stderr)
    os.makedirs(SECRETS, exist_ok=True)
    with open(SOURCE_FILE, 'w', encoding='utf-8') as f:
        json.dump({'source': src['label'], 'repo': src['repo'], 'tag': src['tag'], 'cert': new['main']['cert'],
                   'switched': time.strftime('%Y-%m-%dT%H:%M:%S')}, f, indent=2)
    print('Done: the robot now runs %s. From now on update it with push-main and push-agent from this source.'
          % src['label'])


# ---- serial console (capture-serial.ps1, serial-cmd.ps1) -------------------------------------------

# ---- updating from this PC (update-fetch, update-push, update-build, update-serve). Plan: docs/releases.md ----
# R2's Android 4.4 can't reach GitHub over TLS, so this PC fetches the release (or builds it from a git checkout)
# and either pushes it to R2 or serves it as an update mirror that R2 downloads from (update-mirror).

DEFAULT_REPO = 'Sk3yNet/Fanhome_R2-D2_Custom_Software'
MIRROR = os.path.join(ROOT, 'apps', 'out', 'mirror')
MIRROR_PORT = 8780


def followed_repo():
    """The repository whose releases R2 follows, as far as this PC knows (switch-source records it)."""
    if os.path.isfile(SOURCE_FILE):
        with open(SOURCE_FILE, encoding='utf-8-sig') as f:
            repo = (json.load(f) or {}).get('repo')
        if repo:
            return repo
    return DEFAULT_REPO


def apk_pair(folder):
    """R2Main.apk and R2Agent.apk in folder, with their signer and versionCode. Both must have the same signer."""
    info = {}
    for name in ('main', 'agent'):
        f = os.path.join(folder, APPS[name]['apk'])
        if not os.path.isfile(f):
            raise R2Error('missing ' + f)
        info[name] = {'file': f, 'cert': apk_cert(f), 'version': apk_version_code(f)}
    if info['main']['cert'] != info['agent']['cert']:
        raise R2Error('the two APKs in %s are signed with different keys' % folder)
    return info


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 16), b''):
            h.update(chunk)
    return h.hexdigest()


def publish_mirror(info, repo, tag, published='', url=''):
    """Copies the APKs into the update mirror (MIRROR/<owner>/<repo>/<tag>/) and points its latest.json at them.
    The layout and fields are the ones the agent's update mirror reads (docs/releases.md)."""
    import re
    if not re.match(r'^[A-Za-z0-9-]+/[A-Za-z0-9._-]+$', repo) or '..' in repo:
        raise R2Error('not an owner/repo: ' + repo)
    if not re.match(r'^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$', tag) or '..' in tag:
        raise R2Error('not a usable tag: ' + tag)
    base = os.path.join(MIRROR, *repo.split('/'))
    folder = os.path.join(base, tag)
    os.makedirs(folder, exist_ok=True)
    assets = {}
    for name in ('main', 'agent'):
        apk = APPS[name]['apk']
        dst = os.path.join(folder, apk)
        if os.path.abspath(info[name]['file']) != os.path.abspath(dst):
            shutil.copyfile(info[name]['file'], dst)
        assets[apk] = {'path': '%s/%s' % (tag, apk), 'sha256': sha256_file(dst), 'size': os.path.getsize(dst),
                       'version_code': info[name]['version']}
    manifest = {'repo': repo, 'tag': tag, 'version': tag[1:] if re.match(r'^v\d', tag) else tag,
                'published': published or time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()), 'url': url,
                'cert': info['main']['cert'], 'assets': assets}
    tmp = os.path.join(base, 'latest.json.tmp')
    with open(tmp, 'w', encoding='utf-8') as f:
        json.dump(manifest, f, indent=2)
    os.replace(tmp, os.path.join(base, 'latest.json'))  # R2 never reads a half-written file
    return os.path.join(base, 'latest.json')


def installed_code(text):
    """versionCode from the agent's "name (code)" text in GET /update, or None."""
    import re
    m = re.search(r'\((\d+)\)\s*$', text or '')
    return int(m.group(1)) if m else None


def push_pair(robot, folder, note_cert=None):
    """Installs R2Main.apk, then R2Agent.apk from folder on R2 (the agent reboots R2), skipping what R2 already has.
    The agent checks each APK again (trusted signer, package, docs/security.md)."""
    info = apk_pair(folder)
    cert = info['main']['cert']
    if note_cert and note_cert != cert:
        raise R2Error('the release notes name certificate %s, but the APKs are signed with %s' % (note_cert, cert))
    try:
        own = robot.call('GET', '/trust').get('own') or []
    except R2Error as e:
        if e.code != 404:
            raise
        own = None  # an agent from before signed updates: Android's own signer check still applies
    if own is not None and cert not in own:
        raise R2Error('these APKs are signed with %s, but R2\'s apps with %s. Android won\'t update across signers: '
                      'use switch-source %s once (docs/releases.md)' % (cert, ', '.join(own) or '?', folder))
    try:
        status = robot.call('GET', '/update')
    except R2Error as e:
        if e.code != 404:
            raise
        status = {}
    pushed = []
    for name, path, key in (('main', '/main/install', 'main_installed'), ('agent', '/agent/install', 'agent_installed')):
        have, new = installed_code(status.get(key)), info[name]['version']
        label = 'R2 Main' if name == 'main' else 'R2 Agent'
        if have is not None and new <= have:
            print('%s: R2 already has versionCode %d (this one is %d), skipped' % (label, have, new))
            continue
        print('%s: installing versionCode %d%s' % (label, new, '' if have is None else ' over %d' % have))
        show(robot.call('POST', path, in_file=info[name]['file'], timeout=300))
        pushed.append(name)
    if not pushed:
        print('R2 is up to date')
    elif 'agent' in pushed:
        print('R2 reboots to load the new agent (about a minute); then: r2.py status')
    return pushed


def cmd_update_fetch(robot, a):
    src = get_source(a.arg or followed_repo(), a.tag)
    info = apk_pair(src['dir'])
    if src['note_cert'] and src['note_cert'] != info['main']['cert']:
        raise R2Error('the release notes name certificate %s, but the APKs are signed with %s'
                      % (src['note_cert'], info['main']['cert']))
    if src['repo']:
        repo, tag = src['repo'], src['tag']
    else:  # a folder of your own builds: offered under the repository R2 follows
        repo, tag = followed_repo(), a.tag or 'local-' + stamp()
    latest = publish_mirror(info, repo, tag, src['published'], src['url'])
    print('%s %s in the update mirror: %s' % (repo, tag, latest))
    print('  signed by %s; R2 Main versionCode %d, R2 Agent versionCode %d'
          % (info['main']['cert'], info['main']['version'], info['agent']['version']))
    print('Install it: r2.py update-push %s' % src['dir'])
    print('Or let R2 fetch it: r2.py update-serve, then r2.py update-mirror http://<this PC>:%d and r2.py update-install'
          % MIRROR_PORT)


def cmd_update_push(robot, a):
    src = get_source(a.arg or os.path.join(ROOT, 'apps', 'out'), a.tag)
    print('installing from %s' % src['label'])
    push_pair(robot, src['dir'], src['note_cert'])


def run_step(args, cwd=ROOT):
    print('> ' + ' '.join(args))
    try:
        return subprocess.run(args, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              universal_newlines=True)
    except OSError as e:
        raise R2Error('cannot run %s: %s' % (args[0], e))


def cmd_update_build(robot, a):
    if os.path.isdir(os.path.join(ROOT, '.git')) and shutil.which('git'):
        dirty = run_step(['git', 'status', '--porcelain', '--untracked-files=no']).stdout.strip()
        if dirty:
            print('local changes in the checkout: building them as they are, without git pull')
        else:
            r = run_step(['git', 'pull', '--ff-only'])
            print(r.stdout.strip())
            if r.returncode:
                raise R2Error('git pull failed (see above); fix the checkout, or build and push it yourself '
                              'with apps/build.sh and update-push')
    else:
        print('not a git checkout (or no git): building the files as they are')
    if os.name == 'nt':
        build = ['powershell', '-ExecutionPolicy', 'Bypass', '-File', os.path.join(ROOT, 'apps', 'build.ps1')]
    else:
        build = ['bash', os.path.join(ROOT, 'apps', 'build.sh'), 'all']
    r = run_step(build)
    print(r.stdout.rstrip())
    if r.returncode:
        raise R2Error('the build failed (see above)')
    push_pair(robot, os.path.join(ROOT, 'apps', 'out'))


def mirror_server(port, folder=None):
    """A plain HTTP server for the update mirror folder. The APKs are checked on R2 (sha256 from latest.json,
    then the trusted-signer check), so the transport needs no TLS, which R2 can't do with GitHub anyway."""
    import functools
    import http.server

    class Handler(http.server.SimpleHTTPRequestHandler):
        def list_directory(self, path):  # files only, no folder listings
            self.send_error(404)
            return None

        def log_message(self, fmt, *args):
            print('%s %s' % (self.address_string(), fmt % args))

    return http.server.ThreadingHTTPServer(('', port), functools.partial(Handler, directory=folder or MIRROR))


def lan_address():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(('192.0.2.1', 9))  # no packet is sent: this only picks the interface with the default route
        return s.getsockname()[0]
    except OSError:
        return '<this PC>'
    finally:
        s.close()


def cmd_update_serve(robot, a):
    if a.arg and not a.arg.isdigit():
        raise R2Error('usage: r2.py update-serve [port]  (default %d)' % MIRROR_PORT)
    port = int(a.arg) if a.arg else MIRROR_PORT
    found = []
    for dirpath, _, names in os.walk(MIRROR):
        if 'latest.json' in names:
            with open(os.path.join(dirpath, 'latest.json'), encoding='utf-8') as f:
                m = json.load(f)
            found.append('%s %s' % (m.get('repo'), m.get('tag')))
    if not found:
        raise R2Error('the update mirror is empty: run update-fetch (a release) or update-fetch apps/out first')
    server = mirror_server(port)
    print('serving %s on port %d: %s' % (MIRROR, port, ', '.join(sorted(found))))
    print('Point R2 at it once: r2.py update-mirror http://%s:%d  (then update-check / update-install)'
          % (lan_address(), port))
    print('Ctrl+C stops it. Your firewall may ask to allow incoming connections.')
    try:
        server.serve_forever()
    finally:
        server.server_close()


def open_serial(a):
    try:
        import serial  # pyserial
    except ImportError:
        raise R2Error('the serial commands need pyserial: python3 -m pip install pyserial')
    if not a.port:
        raise R2Error('pass --port, e.g. COM4, /dev/ttyUSB0 or /dev/tty.usbserial-XXXX')
    return serial.Serial(a.port, a.baud, bytesize=8, parity='N', stopbits=1, timeout=0)


def cmd_serial_capture(robot, a):
    out = a.out or 'boot.log'
    port = open_serial(a)
    total = 0
    deadline = time.monotonic() + a.seconds
    try:
        with open(out, 'w', encoding='utf-8', newline='') as w:
            while time.monotonic() < deadline:
                data = port.read(4096)
                if data:
                    text = data.decode('utf-8', errors='replace')
                    w.write(text)
                    w.flush()
                    total += len(text)
                else:
                    time.sleep(0.05)
    finally:
        port.close()
    print('Captured %d characters to %s' % (total, out))


def cmd_serial_cmd(robot, a):
    if not a.file or not os.path.isfile(a.file):
        raise R2Error('usage: r2.py serial-cmd --port P --file <commands.txt>  (examples: tools/serial-commands/)')
    with open(a.file, encoding='utf-8-sig') as f:
        commands = [l.rstrip('\r\n') for l in f if l.strip() and not l.startswith('#')]
    out = a.out or 'serial-cmd.log'
    port = open_serial(a)

    def drain(ms):
        end = time.monotonic() + ms / 1000.0
        buf = b''
        while time.monotonic() < end:
            d = port.read(4096)
            if d:
                buf += d
                end = time.monotonic() + min(ms, 1500) / 1000.0
            else:
                time.sleep(0.05)
        return buf.decode('utf-8', errors='replace')

    try:
        with open(out, 'a', encoding='utf-8', newline='') as w:
            # Quiet kernel messages on the console so command output stays readable.
            port.write(b'\n'); drain(500)
            port.write(b'echo 1 > /proc/sys/kernel/printk\n'); drain(500)
            for c in commands:
                w.write('===== %s =====\n' % c)
                port.write((c + '\n').encode('utf-8'))
                w.write(drain(a.wait_ms))
                w.write('\n')
                w.flush()
    finally:
        port.close()
    print('Done: ' + out)


COMMANDS = {
    'find': cmd_find,
    'status': simple('GET', '/status'),
    'log': simple('GET', '/log'),
    'log-previous': simple('GET', '/log?previous=1'),
    'actions': lambda robot, a: print('\n'.join(ACTIONS)),
    'chirp': cmd_chirp,
    'sounds': cmd_sounds,
    'sound': cmd_sound,
    'action': with_name('/action?name=', 'action <name>  (see: r2.py actions)'),
    'stop': simple('POST', '/stop'),
    'drive': simple('GET', '/drive'),
    'drive-lock': cmd_drive_lock,
    'drive-enable': cmd_drive_enable,
    'drive-disable': simple('POST', '/drive?enable=0'),
    'routines': cmd_routines,
    'routine': cmd_routine,
    'routine-put': cmd_routine_put,
    'routine-run': with_name('/routine/run?name=', 'routine-run <name>'),
    'routine-stop': simple('POST', '/routine/stop'),
    'routine-delete': with_name('/routine/delete?name=', 'routine-delete <name>'),
    'places': cmd_places,
    'place': lambda robot, a: show(robot.call('GET', '/place?name=' + q(need(a.arg, 'place <name>'))).get('place')),
    'place-put': lambda robot, a: put_file(robot, a, '/place', 'place-put <file.json>'),
    'place-delete': with_name('/place/delete?name=', 'place-delete <name>'),
    'goto': with_name('/goto?place=', 'goto <place|home>'),
    'tour': simple('POST', '/tour'),
    'here': with_name('/place/here?name=', 'here <home|place|unknown>'),
    'teach': with_name('/place/teach?what=there&name=', 'teach <place>'),
    'teach-back': with_name('/place/teach?what=back&name=', 'teach-back <place>'),
    'teach-turn': simple('POST', '/place/teach?what=turn'),
    'teach-save': simple('POST', '/place/teach/save'),
    'teach-cancel': simple('POST', '/place/teach/cancel'),
    'cues': cmd_cues,
    'cue': cmd_cue,
    'cue-put': cmd_cue_put,
    'cue-delete': with_name('/cue/delete?name=', 'cue-delete <name>'),
    'watch': simple('GET', '/watch'),
    'watch-start': cmd_watch_start,
    'watch-pause': lambda robot, a: post_watch(robot, {'state': 'paused'}),
    'watch-resume': cmd_watch_resume,
    'watch-stop': lambda robot, a: post_watch(robot, {'state': 'stopped'}),
    'watch-on': lambda robot, a: post_watch(robot, {'enabled': True}),
    'watch-off': lambda robot, a: post_watch(robot, {'enabled': False}),
    'event': with_name('/event?name=', 'event <name>'),
    'volume': cmd_volume,
    'liveliness': cmd_liveliness,
    'plugins': cmd_plugins,
    'plugin-on': cmd_plugin_switch,
    'plugin-off': cmd_plugin_switch,
    'plugin-grant': cmd_plugin_grant,
    'plugin-config': cmd_plugin_config,
    'logcat': cmd_logs,
    'dmesg': cmd_logs,
    'fetch-token': cmd_fetch_token,
    'adb': cmd_adb,
    'adb-off': simple('POST', '/adb?on=0'),
    'adb-always': simple('POST', '/adb?always=1'),
    'allow-subnets': cmd_allow_subnets,
    'hardening': simple('GET', '/hardening'),
    'harden-trial': simple('POST', '/hardening?firewall=1'),
    'harden-confirm': simple('POST', '/hardening/confirm'),
    'harden-off': simple('POST', '/hardening?all=0'),
    'harden-on': cmd_harden_on,
    'mqtt': cmd_mqtt,
    'push-main': cmd_push,
    'push-agent': cmd_push,
    'rollback': lambda robot, a: show(robot.call('POST', '/main/rollback', timeout=120)),
    'restart': simple('POST', '/main/restart'),
    'serial': cmd_serial,
    'safe-on': simple('POST', '/safe-mode?on=1'),
    'safe-off': simple('POST', '/safe-mode?on=0'),
    'wifi-station': simple('POST', '/wifi?mode=station'),
    'wifi-ap': simple('POST', '/wifi?mode=ap'),
    'wifi-scan': cmd_wifi_scan,
    'wifi-join': cmd_wifi_join,
    'hotspot': cmd_hotspot,
    'hotspot-password': cmd_hotspot_password,
    'import-identity': cmd_import_identity,
    'stock-on': simple('POST', '/stock?enable=1'),
    'stock-off': simple('POST', '/stock?enable=0'),
    'assist': cmd_assist,
    'assist-only': lambda robot, a: show(robot.call('POST', '/assist?host=' + q(a.arg or ''))),
    'pair': cmd_pair,
    'whoami': simple('GET', '/whoami'),
    'clients': cmd_clients,
    'client-add': cmd_client_add,
    'client-role': cmd_client_role,
    'client-revoke': with_name('/clients/revoke?id=', 'client-revoke <id>'),
    'rotate-owner': cmd_rotate_owner,
    'update': simple('GET', '/update'),
    'update-check': lambda robot, a: show(robot.call('POST', '/update/check', timeout=60)),
    'update-install': lambda robot, a: show(robot.call('POST', '/update/install', timeout=600)),
    'update-auto': cmd_update_auto,
    'update-source': cmd_update_source,
    'update-fetch': cmd_update_fetch,
    'update-push': cmd_update_push,
    'update-build': cmd_update_build,
    'update-serve': cmd_update_serve,
    'update-mirror': cmd_update_mirror,
    'trust': simple('GET', '/trust'),
    'trust-add': with_name('/trust?add=', 'trust-add <certificate sha256>'),
    'trust-remove': with_name('/trust?remove=', 'trust-remove <certificate sha256>'),
    'assist-reply': cmd_assist_reply,
    'reboot': simple('POST', '/reboot'),
    'shutdown': simple('POST', '/shutdown'),
    'sim-on': simple('POST', '/sim?on=1'),
    'sim-off': simple('POST', '/sim?on=0'),
    'sim-button': lambda robot, a: show(robot.call('POST', '/sim/button?value=' + q(need(a.arg, 'sim-button <n>')))),
    'switch-source': cmd_switch_source,
    'save-settings': cmd_save_settings,
    'restore-settings': cmd_restore_settings,
    'apk-info': cmd_apk_info,
    'install': cmd_install,
    'refresh-agent': cmd_refresh_agent,
    'restore': cmd_restore,
    'serial-capture': cmd_serial_capture,
    'serial-cmd': cmd_serial_cmd,
}


def parse(argv):
    p = argparse.ArgumentParser(prog='r2.py', description=__doc__.split('\n\n', 1)[0],
                                epilog=__doc__.split('\n\n', 2)[2], formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('command', metavar='command', choices=sorted(COMMANDS), help='see the list below')
    p.add_argument('arg', nargs='?', help='the command\'s argument, if it takes one')
    p.add_argument('--robot', '-r', help='robot IP (default: last one found, its hotspot, or listen for it)')
    p.add_argument('--user', '-u', help='mqtt: broker user name (asks for the password); "" clears it')
    p.add_argument('--role', choices=['read', 'control', 'admin'], help='client-add/client-role: access level')
    p.add_argument('--plain', action='store_true', help='client-add: a plain token (Home Assistant)')
    p.add_argument('--lines', '-n', type=int, default=500, help='logcat/dmesg: how many lines (default 500)')
    p.add_argument('--at', help='watch-start/watch-resume: film position, e.g. 1:02:03')
    p.add_argument('--tag', help='switch-source/update-fetch/update-push: a release tag instead of the latest release')
    p.add_argument('--yes', action='store_true', help='switch-source: do not ask before switching')
    p.add_argument('--agent', action='store_true', help="restore-settings: also restore the agent's settings")
    p.add_argument('--port', help='serial-*: serial port, e.g. COM4 or /dev/ttyUSB0')
    p.add_argument('--baud', type=int, default=115200, help='serial-*: baud rate (default 115200)')
    p.add_argument('--seconds', type=int, default=180, help='serial-capture: how long (default 180)')
    p.add_argument('--file', help='serial-cmd: one shell command per line (# comments)')
    p.add_argument('--wait-ms', type=int, default=3000, help='serial-cmd: wait per command (default 3000)')
    p.add_argument('--out', help='serial-*: log file')
    return p.parse_args(argv)


def main(argv=None):
    a = parse(sys.argv[1:] if argv is None else argv)
    try:
        COMMANDS[a.command](Robot(a.robot), a)
    except R2Error as e:
        print('r2.py: %s' % e, file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        return 130
    return 0


if __name__ == '__main__':
    sys.exit(main())
