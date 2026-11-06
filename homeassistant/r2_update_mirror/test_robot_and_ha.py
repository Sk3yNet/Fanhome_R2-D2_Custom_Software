"""Off-robot tests for the add-on's R2 side (health, updates, backups, the page's buttons) and its Home
Assistant side (MQTT discovery), against a pretend R2 agent and a pretend MQTT broker on localhost.
Run: python3 -m unittest discover -s homeassistant/r2_update_mirror -p 'test_*.py'"""

import json
import os
import shutil
import socket
import socketserver
import struct
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.parse
import urllib.request
import http.server

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import hass  # noqa: E402
import mirror  # noqa: E402
import r2robot  # noqa: E402

TOKEN = 'plain-token'


class FakeAgent(http.server.BaseHTTPRequestHandler):
    """Answers like R2's agent for a plain control token (docs/security.md)."""
    status = {}
    update = {}
    check = {}
    control_may_install = False
    installs = 0
    store = {}
    posted = []
    assist_mode = 'off'
    assist_host = ''

    def log_message(self, *a):
        pass

    def reply(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        u = urllib.parse.urlsplit(self.path)
        q = dict(urllib.parse.parse_qsl(u.query))
        if u.path == '/ping':
            return self.reply(200, {'ok': True})
        if self.headers.get('X-R2-Token') != TOKEN:
            return self.reply(401, {'error': 'unknown client'})
        C = FakeAgent
        lists = {'/routines': 'routines', '/places': 'places', '/cues': 'cues'}
        gets = {'/routine': ('routines', 'routine'), '/place': ('places', 'place'), '/cue': ('cues', 'cue')}
        if u.path == '/status':
            return self.reply(200, C.status)
        if u.path == '/update':
            return self.reply(200, dict(C.update, control_may_install=C.control_may_install))
        if u.path in lists:
            kind = lists[u.path]
            return self.reply(200, {kind: [{'name': n} for n in C.store.get(kind, {})] + [{'name': 'broken', 'error': 'bad'}]})
        if u.path in gets:
            kind, key = gets[u.path]
            return self.reply(200, {key: C.store[kind][q['name']]})
        self.reply(404, {'error': 'no'})

    def do_POST(self):
        u = urllib.parse.urlsplit(self.path)
        n = int(self.headers.get('Content-Length') or 0)
        body = self.rfile.read(n) if n else b''
        if self.headers.get('X-R2-Token') != TOKEN:
            return self.reply(401, {'error': 'unknown client'})
        C = FakeAgent
        if u.path == '/update/check':
            return self.reply(200, C.check)
        if u.path == '/update/install':
            if not C.control_may_install:
                return self.reply(403, {'error': 'installing updates needs an admin client'})
            C.installs += 1
            return self.reply(200, {'result': '0.4.0: R2 Main: installed'})
        q = dict(urllib.parse.parse_qsl(u.query, keep_blank_values=True))
        if u.path in ('/assist', '/volume', '/action'):  # R2 Main's voice settings, as GET /status shows them
            C.posted.append((u.path, q))
            st = C.status
            if u.path == '/assist':
                mode = q.get('mode') or C.assist_mode
                host = q['host'] if 'host' in q else C.assist_host
                C.assist_mode, C.assist_host = mode, host
                names = {'robot': 'wake word on the robot', 'ha': 'wake word in Home Assistant'}
                st['assist'] = 'assist: off' if mode == 'off' else 'assist: %s%s, waiting for Home Assistant on port 10700' % (
                    names[mode], ', only ' + host if host else '')
            elif u.path == '/volume':
                st['volume'] = int(q['percent'])
            else:
                flags = {'beeps-on': ('assist_reply', 'beeps'), 'beeps-off': ('assist_reply', 'voice'),
                         'mute': ('muted', True), 'unmute': ('muted', False), 'voice-on': ('voice', True),
                         'voice-off': ('voice', False), 'chirps-on': ('chirps', True), 'chirps-off': ('chirps', False)}
                k, v = flags[q['name']]
                st[k] = v
            return self.reply(200, {'result': 'sent to the main app'})
        if u.path in ('/routine', '/place', '/cue'):
            C.posted.append((u.path, json.loads(body)))
            return self.reply(200, {'result': 'saved'})
        self.reply(404, {'error': 'no'})


class FakeBroker(socketserver.ThreadingTCPServer):
    """Just enough MQTT 3.1.1: CONNECT, SUBSCRIBE, PUBLISH (recorded), PINGREQ; can send to the client."""
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self):
        self.published = {}
        self.subscribed = []
        self.conn = None
        self.connect_packet = None
        socketserver.ThreadingTCPServer.__init__(self, ('127.0.0.1', 0), self.Handler)

    def send(self, topic, payload):
        t = topic.encode()
        body = struct.pack('>H', len(t)) + t + payload.encode()
        self.conn.sendall(bytes([0x30, len(body)]) + body)

    class Handler(socketserver.BaseRequestHandler):
        def read(self, n):
            buf = b''
            while len(buf) < n:
                c = self.request.recv(n - len(buf))
                if not c:
                    raise OSError
                buf += c
            return buf

        def handle(self):
            srv = self.server
            try:
                while True:
                    first = self.read(1)[0]
                    mult, length = 1, 0
                    while True:
                        b = self.read(1)[0]
                        length += (b & 0x7f) * mult
                        if not b & 0x80:
                            break
                        mult *= 128
                    body = self.read(length) if length else b''
                    kind = first >> 4
                    if kind == 1:
                        srv.connect_packet = body
                        srv.conn = self.request
                        self.request.sendall(b'\x20\x02\x00\x00')
                    elif kind == 8:
                        tlen = struct.unpack('>H', body[2:4])[0]
                        srv.subscribed.append(body[4:4 + tlen].decode())
                        self.request.sendall(b'\x90\x03' + body[:2] + b'\x00')
                    elif kind == 3:
                        tlen = struct.unpack('>H', body[:2])[0]
                        srv.published[body[2:2 + tlen].decode()] = (body[2 + tlen:].decode(), bool(first & 1))
                    elif kind == 12:
                        self.request.sendall(b'\xd0\x00')
            except OSError:
                pass


def wait_for(cond, timeout=5):
    end = time.time() + timeout
    while time.time() < end:
        if cond():
            return True
        time.sleep(0.02)
    return False


class RobotAndHaTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.agent = http.server.ThreadingHTTPServer(('127.0.0.1', 0), FakeAgent)
        threading.Thread(target=cls.agent.serve_forever, daemon=True).start()
        cls.adb = socket.socket()
        cls.adb.bind(('127.0.0.1', 0))
        cls.adb.listen(5)

    @classmethod
    def tearDownClass(cls):
        cls.agent.shutdown()
        cls.agent.server_close()
        cls.adb.close()

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.dir)
        os.environ['NO_PROXY'] = '127.0.0.1'
        os.environ.pop('SUPERVISOR_TOKEN', None)
        FakeAgent.status = {'agent': '0.3.0', 'main_version': '0.3.0 (100)', 'adb_wifi': False, 'main_alive': True}
        FakeAgent.update = {'main_installed': '0.3.0 (100)', 'agent_installed': '0.3.0 (101)',
                            'mirror': 'http://192.168.2.20:8765'}
        FakeAgent.check = {'version': '0.4.0', 'update_available': True, 'url': 'https://github.com/x/releases/v0.4.0'}
        FakeAgent.control_may_install = False
        FakeAgent.installs = 0
        FakeAgent.store = {'routines': {'greet': {'name': 'greet', 'steps': [{'cmd': 'x'}]}},
                           'places': {'kitchen': {'name': 'kitchen', 'there_seconds': 4}},
                           'cues': {'a-new-hope': {'name': 'a-new-hope', 'cues': []}}}
        FakeAgent.posted = []
        FakeAgent.assist_mode, FakeAgent.assist_host = 'robot', ''
        FakeAgent.status.update({'assist': 'assist: wake word on the robot, connected to 192.168.2.20, idle, last heard '
                                           '"what time is it"', 'assist_reply': 'voice', 'volume': 60, 'muted': False,
                                 'voice': True, 'chirps': True, 'subtitle': 'It is 3 PM.', 'subtitle_s_ago': 12})

    def robot(self, token=TOKEN, adb_port=1):
        return r2robot.Robot('127.0.0.1', token, port=self.agent.server_address[1], adb_port=adb_port)

    def app(self, **opts):
        path = os.path.join(self.dir, 'options.json')
        with open(path, 'w') as f:
            json.dump(dict({'robot': '127.0.0.1', 'robot_token': TOKEN}, **opts), f)
        o = mirror.load_options(path)
        m = mirror.Mirror(os.path.join(self.dir, 'mirror'), o, api='http://127.0.0.1:1')
        a = mirror.App(m, o, self.dir, action_ips={'127.0.0.1'})
        a.robot = self.robot()
        return a

    def test_version_name(self):
        self.assertEqual(r2robot.version_name('0.3.0 (1015840)'), '0.3.0')
        self.assertEqual(r2robot.version_name('local-0.4.0-dirty (5)'), 'local-0.4.0-dirty')
        self.assertIsNone(r2robot.version_name('not installed'))

    def test_healthy_robot(self):
        h = self.robot().health()
        self.assertTrue(h['reachable'] and h['token_ok'])
        self.assertEqual(h['problems'], [])
        self.assertFalse(h['adb_port_open'])
        self.assertEqual(h['main_installed'], '0.3.0 (100)')
        self.assertTrue(any('update-control on' in n for n in h['notes']))

    def test_adb_open_although_reported_off_is_a_problem(self):
        h = self.robot(adb_port=self.adb.getsockname()[1]).health()
        self.assertTrue(h['adb_port_open'])
        self.assertTrue(any('reports it off' in p for p in h['problems']), h['problems'])
        FakeAgent.status.update(adb_wifi=True, adb_wifi_seconds_left=600)
        h = self.robot(adb_port=self.adb.getsockname()[1]).health()
        self.assertEqual(h['problems'], [])
        self.assertTrue(any('10 more minutes' in n for n in h['notes']))

    def test_other_problems(self):
        FakeAgent.status.update(main_alive=False, last_event='crash loop', safe_mode=True)
        FakeAgent.update['mirror'] = ''
        h = self.robot().health()
        self.assertEqual(len(h['problems']), 2, h['problems'])
        self.assertTrue(any('mirror' in n for n in h['notes']))
        h = self.robot(token='wrong').health()
        self.assertFalse(h['token_ok'])
        self.assertIn('refused robot_token', h['problems'][0])
        h = r2robot.Robot('127.0.0.1', TOKEN, port=1).health()
        self.assertFalse(h['reachable'])

    def test_update_state_and_install_from_the_page(self):
        a = self.app()
        a.refresh_robot(check=True)
        st = a.state()
        self.assertEqual((st['installed'], st['latest']), ('0.3.0', '0.4.0'))
        srv, base = self.serve(a)
        page = urllib.request.urlopen(base + '/', timeout=5).read().decode()
        self.assertIn('Update to <b>0.4.0</b> available', page)
        self.assertIn('allow it first', page)  # Install is greyed out until update-control on
        FakeAgent.control_may_install = True
        a.refresh_robot()
        self.assertNotIn('disabled', urllib.request.urlopen(base + '/', timeout=5).read().decode().split('Install on R2')[0][-200:])
        notes = []
        orig = hass.notify
        hass.notify = lambda t, m, ident='': notes.append((t, m))
        self.addCleanup(setattr, hass, 'notify', orig)
        r = urllib.request.urlopen(urllib.request.Request(base + '/action/install', data=b'', method='POST'), timeout=5)
        self.assertEqual(r.status, 200)  # followed the redirect back to the page
        self.assertTrue(wait_for(lambda: FakeAgent.installs == 1 and not a.in_progress))
        self.assertIn('installed', a.last_action)
        self.assertTrue(wait_for(lambda: notes))
        # Refused by R2 (not allowed): the failure is shown, nothing installed.
        FakeAgent.control_may_install = False
        a.install()
        self.assertTrue(wait_for(lambda: not a.in_progress and 'failed' in a.last_action))
        self.assertEqual(FakeAgent.installs, 1)

    def test_buttons_only_through_the_sidebar(self):
        a = self.app()
        a.action_ips = set()
        srv, base = self.serve(a)
        with self.assertRaises(urllib.error.HTTPError) as e:
            urllib.request.urlopen(urllib.request.Request(base + '/action/install', data=b'', method='POST'), timeout=5)
        self.assertEqual(e.exception.code, 403)
        self.assertNotIn('<button', urllib.request.urlopen(base + '/', timeout=5).read().decode())
        self.assertEqual(FakeAgent.installs, 0)
        # The sidebar's own port: buttons shown and working, whatever the caller's address.
        ing = mirror.Server(('127.0.0.1', 0), mirror.make_handler(a, ingress=True))
        threading.Thread(target=ing.serve_forever, daemon=True).start()
        self.addCleanup(ing.server_close)
        self.addCleanup(ing.shutdown)
        ibase = 'http://127.0.0.1:%d' % ing.server_address[1]
        self.assertIn('<button', urllib.request.urlopen(ibase + '/', timeout=5).read().decode())
        self.assertNotIn("Open this page from Home Assistant", urllib.request.urlopen(ibase + '/', timeout=5).read().decode())
        urllib.request.urlopen(urllib.request.Request(ibase + '/action/check', data=b'', method='POST'), timeout=5)
        self.assertIn('checking', a.last_action)

    def test_backup_and_restore(self):
        a = self.app(backup_settings=True, backup_keep=2)
        msg = a.backup_now()
        self.assertIn('1 routines, 1 places, 1 cues', msg)
        names = a.backups()
        self.assertEqual(len(names), 1)
        with open(os.path.join(self.dir, 'backups', names[0])) as f:
            b = json.load(f)
        self.assertEqual(b['routines']['greet']['steps'], [{'cmd': 'x'}])
        self.assertNotIn('broken', b['routines'])
        srv, base = self.serve(a)
        page = urllib.request.urlopen(base + '/', timeout=5).read().decode()
        self.assertIn(names[0], page)
        urllib.request.urlopen(urllib.request.Request(base + '/action/restore', method='POST',
                                                      data=urllib.parse.urlencode({'name': names[0]}).encode()), timeout=5)
        self.assertIn('restored 1 routines, 1 places, 1 cues', a.last_action)
        self.assertEqual(sorted(p for p, _ in FakeAgent.posted), ['/cue', '/place', '/routine'])
        self.assertEqual(a.restore('../options.json'), 'restore: no such backup')
        for i in range(3):  # keeps backup_keep
            time.sleep(1.05)
            a.backup_now()
        self.assertEqual(len(a.backups()), 2)

    def test_home_assistant_entities(self):
        broker = FakeBroker()
        threading.Thread(target=broker.serve_forever, daemon=True).start()
        self.addCleanup(broker.server_close)
        self.addCleanup(broker.shutdown)
        a = self.app()
        FakeAgent.control_may_install = True
        a.refresh_robot(check=True)
        link = hass.HassLink(a, {'host': '127.0.0.1', 'port': broker.server_address[1], 'username': 'u',
                                 'password': 'p'}, log=lambda m: None)
        a.hass = link
        link.start()
        self.addCleanup(link.client.stop)
        upd = 'homeassistant/update/r2_update_mirror/software/config'
        # The broker handles packets as they arrive: wait for the last state topic publish_state() sends.
        self.assertTrue(wait_for(lambda: upd in broker.published and 'r2_update_mirror/health/attributes' in broker.published))
        self.assertIn(b'u', broker.connect_packet)
        cfg = json.loads(broker.published[upd][0])
        self.assertTrue(broker.published[upd][1], 'discovery must be retained')
        self.assertEqual((cfg['command_topic'], cfg['payload_install'], cfg['device_class']),
                         ('r2_update_mirror/install', 'install', 'firmware'))
        sw = json.loads(broker.published['r2_update_mirror/software'][0])
        self.assertEqual((sw['installed_version'], sw['latest_version'], sw['in_progress']), ('0.3.0', '0.4.0', False))
        self.assertEqual(broker.published['r2_update_mirror/availability'][0], 'online')
        self.assertEqual(broker.published['r2_update_mirror/adb'][0], 'OFF')
        self.assertIn('r2_update_mirror/install', broker.subscribed)
        # R2 Main's own discovery shows up: the entities move onto R2's device.
        broker.send('homeassistant/sensor/abc123/battery/config',
                    json.dumps({'device': {'identifiers': ['r2d2_abc123'], 'name': 'R2'}}))
        self.assertTrue(wait_for(lambda: json.loads(broker.published[upd][0])['device'] == {'identifiers': ['r2d2_abc123']}))
        # Pressing Install in Home Assistant.
        broker.send('r2_update_mirror/install', 'install')
        self.assertTrue(wait_for(lambda: FakeAgent.installs == 1))
        # The last will: offline if the add-on drops off.
        self.assertIn(b'r2_update_mirror/availability', broker.connect_packet)

    def test_voice_state(self):
        v = r2robot.voice_state({'assist': 'assist: wake word in Home Assistant, only 192.168.2.20, answers in beeps, '
                                           'waiting for Home Assistant on port 10700', 'volume': 40})
        self.assertEqual((v['mode'], v['only'], v['connected'], v['volume']), ('ha', '192.168.2.20', False, 40))
        self.assertEqual(r2robot.voice_state({'assist': 'assist: off'})['mode'], 'off')
        self.assertIsNone(r2robot.voice_state({})['mode'])  # R2 Main not running or too old

    def test_voice_settings_from_the_page(self):
        a = self.app()
        a.ha_address = '192.168.2.20'
        a.refresh_robot()
        srv, base = self.serve(a)
        page = urllib.request.urlopen(base + '/', timeout=5).read().decode()
        self.assertIn('<h2>Voice</h2>', page)
        self.assertIn('Wake word &quot;hey R2-D2&quot; on the robot', page)
        self.assertIn('It is 3 PM.', page)
        self.assertIn('voice-assistants', page)

        def press(what, value):
            urllib.request.urlopen(urllib.request.Request(base + '/action/voice', method='POST', data=urllib.parse.urlencode(
                {'what': what, 'value': value}).encode()), timeout=10)
            return a.health['voice']

        self.assertEqual(press('mode', 'ha')['mode'], 'ha')
        self.assertEqual(press('only', 'this')['only'], '192.168.2.20')
        self.assertIn('(this Home Assistant)', urllib.request.urlopen(base + '/', timeout=5).read().decode())
        self.assertEqual(press('only', '')['only'], '')
        self.assertEqual(press('reply', 'beeps')['reply'], 'beeps')
        self.assertEqual(press('volume', '35')['volume'], 35)
        self.assertTrue(press('mute', 'on')['muted'])
        self.assertFalse(press('commands', 'off')['voice_commands'])
        self.assertFalse(press('chirps', 'off')['chirps'])
        n = len(FakeAgent.posted)
        for what, value in (('mode', 'loud'), ('volume', '101'), ('only', '10.0.0.9'), ('reply', 'shout'), ('reboot', '')):
            press(what, value)
            self.assertIn('voice: ', a.last_action)
        self.assertEqual(len(FakeAgent.posted), n)  # nothing invalid reached R2
        # Without the sidebar, no buttons and no changes.
        a.action_ips = set()
        self.assertNotIn('action/voice', urllib.request.urlopen(base + '/', timeout=5).read().decode())

    def serve(self, app):
        srv = mirror.Server(('127.0.0.1', 0), mirror.make_handler(app))
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        self.addCleanup(srv.server_close)
        self.addCleanup(srv.shutdown)
        return srv, 'http://127.0.0.1:%d' % srv.server_address[1]


if __name__ == '__main__':
    unittest.main()
