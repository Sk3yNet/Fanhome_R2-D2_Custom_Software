"""Talking to R2's agent API (port 7700) with a plain token, for the update mirror add-on.

Only what a plain control token may do (docs/security.md): read status, ask for an update check, start an
install if the owner allowed that (update-control on), and read or write routines, rooms and cues for the
settings backup. Never the owner key.
"""

import json
import re
import socket
import time
import urllib.error
import urllib.parse
import urllib.request

API_PORT = 7700
ADB_PORT = 5555


class RobotError(Exception):
    def __init__(self, msg, code=None):
        Exception.__init__(self, msg)
        self.code = code


def version_name(installed):
    """'0.3.0 (1015840)' -> '0.3.0'; 'not installed' and '' -> None."""
    if not installed or installed == 'not installed':
        return None
    return re.sub(r'\s*\(\d+\)\s*$', '', installed).strip() or None


class Robot:
    def __init__(self, host, token, port=API_PORT, adb_port=ADB_PORT):
        self.host = host.strip()
        self.token = token.strip()
        self.port = port
        self.adb_port = adb_port

    def call(self, method, path, body=None, timeout=15):
        url = 'http://%s:%d%s' % (self.host, self.port, path)
        data = body.encode('utf-8') if isinstance(body, str) else body
        req = urllib.request.Request(url, data=data, method=method)
        if self.token:
            req.add_header('X-R2-Token', self.token)
        if data is not None:
            req.add_header('Content-Type', 'application/json')
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                text = r.read().decode('utf-8', 'replace')
        except urllib.error.HTTPError as e:
            text = e.read().decode('utf-8', 'replace')
            try:
                msg = json.loads(text).get('error') or text
            except ValueError:
                msg = text.strip() or 'HTTP %d' % e.code
            raise RobotError(msg, e.code)
        except (urllib.error.URLError, OSError) as e:
            raise RobotError('R2 does not answer at %s (%s)' % (self.host, getattr(e, 'reason', e)))
        try:
            return json.loads(text)
        except ValueError:
            return {'result': text.strip()}

    def port_open(self, port, timeout=2.0):
        try:
            with socket.create_connection((self.host, port), timeout=timeout):
                return True
        except OSError:
            return False

    # ---- health ------------------------------------------------------------------------------------

    def health(self):
        """What the add-on page and Home Assistant show: reachable, versions, and a list of problems."""
        h = {'checked': int(time.time()), 'reachable': False, 'problems': [], 'notes': [], 'adb_port_open': None,
             'agent': None, 'main': None, 'main_installed': None, 'agent_installed': None,
             'control_may_install': None, 'mirror': None, 'token_ok': None}
        try:
            self.call('GET', '/ping', timeout=5)
            h['reachable'] = True
        except RobotError as e:
            h['problems'].append(str(e))
            return h
        h['adb_port_open'] = self.port_open(self.adb_port)
        if not self.token:
            h['notes'].append('Add robot_token (a plain token from `tools\\r2.ps1 client-add home-assistant -Plain`) '
                              'for versions, update and health details.')
            if h['adb_port_open']:
                h['problems'].append('adb over Wi-Fi is open (port %d): a root shell for anyone on this network.'
                                     % self.adb_port)
            return h
        try:
            st = self.call('GET', '/status')
            up = self.call('GET', '/update')
            h['token_ok'] = True
        except RobotError as e:
            if e.code in (401, 403):
                h['token_ok'] = False
                h['problems'].append('R2 refused robot_token (%s). Make a new one with '
                                     '`tools\\r2.ps1 client-add home-assistant -Plain`.' % e)
            else:
                h['problems'].append('status: %s' % e)
            return h
        h['agent'] = st.get('agent')
        h['main'] = st.get('main_version')
        h['main_installed'] = up.get('main_installed')
        h['agent_installed'] = up.get('agent_installed')
        h['control_may_install'] = up.get('control_may_install')
        h['mirror'] = up.get('mirror')
        h['adb_wifi_reported'] = st.get('adb_wifi')
        if h['adb_port_open']:
            if not st.get('adb_wifi'):
                h['problems'].append('adb over Wi-Fi answers on port %d although R2 reports it off: a root shell '
                                     'for anyone on this network. Run `tools\\r2.ps1 adb-off`, and reboot R2 if it '
                                     'stays open.' % self.adb_port)
            elif st.get('adb_wifi_always'):
                h['problems'].append('adb over Wi-Fi is on permanently (adb-always): a root shell for anyone on '
                                     'this network. `tools\\r2.ps1 adb-off` closes it.')
            else:
                h['notes'].append('adb over Wi-Fi is on for %s more minutes, then closes by itself.'
                                  % (int(st.get('adb_wifi_seconds_left') or 0) // 60))
        elif st.get('adb_wifi'):
            h['notes'].append('R2 reports adb over Wi-Fi on, but port %d does not answer.' % self.adb_port)
        if st.get('main_alive') is False:
            h['problems'].append("R2 Main isn't running (%s)." % (st.get('last_event') or 'no reason given'))
        if st.get('safe_mode'):
            h['problems'].append('R2 is in safe mode: R2 Main is kept stopped (`tools\\r2.ps1 safe-off`).')
        if st.get('passive'):
            h['problems'].append('The agent is passive because the factory app is enabled.')
        if not up.get('mirror'):
            h['notes'].append("R2 isn't reading updates from a mirror yet, so it can't update itself.")
        if up.get('control_may_install') is False:
            h['notes'].append('Installing from Home Assistant is off. Allow it once with '
                              '`tools\\r2.ps1 update-control on`.')
        return h

    # ---- updates -----------------------------------------------------------------------------------

    def check_update(self):
        return self.call('POST', '/update/check', timeout=90)

    def install_update(self):
        return self.call('POST', '/update/install', timeout=900)

    # ---- settings backup (routines, rooms, cue files) ----------------------------------------------

    KINDS = (  # list path, list key, get path, get key, put path
        ('routines', '/routines', 'routines', '/routine?name=', 'routine', '/routine'),
        ('places', '/places', 'places', '/place?name=', 'place', '/place'),
        ('cues', '/cues', 'cues', '/cue?name=', 'cue', '/cue'),
    )

    def backup(self):
        """Everything the token can read back and write again: routines, taught rooms and cue files."""
        out = {'made': int(time.time()), 'host': self.host, 'agent': None}
        try:
            out['agent'] = self.call('GET', '/status').get('agent')
        except RobotError:
            pass
        errors = []
        for kind, list_path, list_key, get_path, get_key, _ in self.KINDS:
            items = {}
            for entry in self.call('GET', list_path).get(list_key) or []:
                name = entry.get('name') if isinstance(entry, dict) else entry
                if not name or (isinstance(entry, dict) and entry.get('error')):
                    continue
                try:
                    items[name] = self.call('GET', get_path + urllib.parse.quote(name, safe='')).get(get_key)
                except RobotError as e:
                    errors.append('%s %s: %s' % (kind, name, e))
            out[kind] = items
        out['errors'] = errors
        return out

    def restore(self, backup):
        """Writes the backup's routines, rooms and cue files back (same names are replaced). Returns a summary."""
        done, failed = [], []
        for kind, _, _, _, _, put_path in self.KINDS:
            items = backup.get(kind) or {}
            ok = 0
            for name, body in items.items():
                if body is None:
                    continue
                try:
                    self.call('POST', put_path + '?name=' + urllib.parse.quote(name, safe=''),
                              body if isinstance(body, str) else json.dumps(body))
                    ok += 1
                except RobotError as e:
                    failed.append('%s %s: %s' % (kind, name, e))
            done.append('%d %s' % (ok, kind))
        return 'restored ' + ', '.join(done) + ('' if not failed else '; failed: ' + '; '.join(failed))
