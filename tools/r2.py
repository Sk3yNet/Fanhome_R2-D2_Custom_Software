#!/usr/bin/env python3
"""Talk to the R2 Agent over Wi-Fi, from Windows, macOS or Linux.

The Python version of tools/r2.ps1 and tools/install-on-robot.ps1, with the same commands.
Needs Python 3.8+ and nothing else (the serial-* commands also need pyserial).

Usage: python3 tools/r2.py <command> [arg] [--robot <ip>] [--user <name>] [--lines N]
  find                     listen for the robot's UDP announcement (port 7702)
  status | log             agent state / agent event log
  log-previous             agent event log from the previous boot (e.g. after a crash or power loss)
  action <name>            run a robot action, e.g. dance, wake, lightsaber, leia (list: actions)
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
  volume <0-100>           droid sounds and music volume (kept across reboots)
  liveliness [level]       how much R2 does on his own: off, quiet, chatty or full (docs/liveliness.md);
                           no level shows the current one
  logcat [text] [--lines N] Android log (all apps, incl. main-app crash traces), optionally only lines containing text
  dmesg [text] [--lines N]  kernel log
  adb [minutes]            switch on adb over Wi-Fi for a while (default 30 min) and connect
                           (root shell: adb shell). It switches itself off again.
  adb-off                  switch adb over Wi-Fi off now (adb is back on USB)
  adb-always               keep adb over Wi-Fi on, also after reboots (trusted networks only)
  allow-subnets [list]     also accept API clients from these subnets, e.g. 10.0.20.0/24,10.0.30.5
                           (empty string '': only the robot's own subnet; no argument: show the list)
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
  trust | trust-add <sha256> | trust-remove <sha256>
                           signing certificates whose APKs R2 installs (its own is always trusted)
  reboot | shutdown

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

ACTIONS = ('stop wake turn-left turn-right turn-around forward circle patrol dance shake-head who-are-you noise '
           'not-recognized lightsaber arm short-lcd long-lcd flash-front flash-back leia luke mute unmute pair '
           'phone-app-on phone-app-off voice-on voice-off face-on face-off liveliness-off liveliness-quiet liveliness-chatty '
           'liveliness-full').split()


class R2Error(Exception):
    pass


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
            raise R2Error('HTTP %d: %s' % (e.code, msg))
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


def cmd_routines(robot, a):
    d = robot.call('GET', '/routines')
    line = 'running: %s' % (d.get('running') or '')
    if d.get('last_routine'):
        line += '   last: %s' % d['last_routine']
    print(line)
    print(table(d.get('routines') or [],
                ['name', 'steps', 'timeout', 'while_charging', 'replaces_action', 'description', 'error']))


def cmd_routine(robot, a):
    name = need(a.arg, 'routine <name>')
    show(robot.call('GET', '/routine?name=' + q(name)).get('routine'))


def cmd_routine_put(robot, a):
    if not a.arg or not os.path.isfile(a.arg):
        raise R2Error('usage: r2.py routine-put <file.json>  (examples: docs/routines/)')
    with open(a.arg, encoding='utf-8-sig') as f:
        show(robot.call('POST', '/routine', f.read()))


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


# ---- serial console (capture-serial.ps1, serial-cmd.ps1) -------------------------------------------

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
    'volume': cmd_volume,
    'liveliness': cmd_liveliness,
    'logcat': cmd_logs,
    'dmesg': cmd_logs,
    'fetch-token': cmd_fetch_token,
    'adb': cmd_adb,
    'adb-off': simple('POST', '/adb?on=0'),
    'adb-always': simple('POST', '/adb?always=1'),
    'allow-subnets': cmd_allow_subnets,
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
    'trust': simple('GET', '/trust'),
    'trust-add': with_name('/trust?add=', 'trust-add <certificate sha256>'),
    'trust-remove': with_name('/trust?remove=', 'trust-remove <certificate sha256>'),
    'reboot': simple('POST', '/reboot'),
    'shutdown': simple('POST', '/shutdown'),
    'sim-on': simple('POST', '/sim?on=1'),
    'sim-off': simple('POST', '/sim?on=0'),
    'sim-button': lambda robot, a: show(robot.call('POST', '/sim/button?value=' + q(need(a.arg, 'sim-button <n>')))),
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
