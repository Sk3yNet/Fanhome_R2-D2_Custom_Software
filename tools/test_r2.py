"""Off-robot tests for tools/r2.py: each command against a pretend agent on localhost.
Run: python3 -m unittest discover -s tools -p 'test_*.py'"""

import contextlib
import http.server
import io
import json
import os
import re
import sys
import threading
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import r2  # noqa: E402


class FakeAgent(http.server.BaseHTTPRequestHandler):
    requests = []
    replies = {}

    def log_message(self, *args):
        pass

    def handle_any(self):
        n = int(self.headers.get('Content-Length') or 0)
        body = self.rfile.read(n) if n else b''
        FakeAgent.requests.append({'method': self.command, 'path': self.path, 'body': body,
                                   'token': self.headers.get('X-R2-Token'),
                                   'signed': {k: self.headers.get(k) for k in ('X-R2-Client', 'X-R2-Time', 'X-R2-Nonce',
                                                                                'X-R2-Body', 'X-R2-Signature')},
                                   'type': self.headers.get('Content-Type')})
        code, ctype, payload = FakeAgent.replies.get(self.path.split('?')[0],
                                                     (200, 'application/json', {'result': 'ok'}))
        data = payload.encode() if isinstance(payload, str) else json.dumps(payload).encode()
        self.send_response(code)
        self.send_header('Content-Type', ctype)
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    do_GET = do_POST = handle_any


class R2CliTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), FakeAgent)
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()
        cls.port = cls.server.server_address[1]

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def setUp(self):
        FakeAgent.requests = []
        FakeAgent.replies = {}
        patches = [mock.patch.object(r2, 'API_PORT', self.port), mock.patch.object(r2, 'read_credentials', lambda: ('owner', 'tok'))]
        for p in patches:
            p.start()
            self.addCleanup(p.stop)

    def run_cli(self, *args):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = r2.main(list(args) + ['--robot', '127.0.0.1'])
        return code, out.getvalue(), err.getvalue()

    def last(self):
        return FakeAgent.requests[-1]

    def test_simple_commands_hit_the_same_endpoints_as_r2_ps1(self):
        cases = {
            'status': ('GET', '/status'), 'log': ('GET', '/log'), 'log-previous': ('GET', '/log?previous=1'),
            'stop': ('POST', '/stop'), 'routine-stop': ('POST', '/routine/stop'),
            'adb-off': ('POST', '/adb?on=0'), 'adb-always': ('POST', '/adb?always=1'),
            'rollback': ('POST', '/main/rollback'), 'restart': ('POST', '/main/restart'),
            'safe-on': ('POST', '/safe-mode?on=1'), 'safe-off': ('POST', '/safe-mode?on=0'),
            'wifi-station': ('POST', '/wifi?mode=station'), 'wifi-ap': ('POST', '/wifi?mode=ap'),
            'stock-on': ('POST', '/stock?enable=1'), 'stock-off': ('POST', '/stock?enable=0'),
            'reboot': ('POST', '/reboot'), 'shutdown': ('POST', '/shutdown'),
            'sim-on': ('POST', '/sim?on=1'), 'sim-off': ('POST', '/sim?on=0'),
        }
        for cmd, (method, path) in cases.items():
            with self.subTest(cmd):
                code, _, err = self.run_cli(cmd)
                self.assertEqual(code, 0, err)
                self.assertEqual((self.last()['method'], self.last()['path']), (method, path))
                self.assertEqual(self.last()['token'], 'tok')

    def test_arguments_are_url_encoded(self):
        self.run_cli('action', 'who are you')
        self.assertEqual(self.last()['path'], '/action?name=who%20are%20you')
        self.run_cli('routine-run', 'a&b')
        self.assertEqual(self.last()['path'], '/routine/run?name=a%26b')
        self.run_cli('routine-delete', 'x')
        self.assertEqual(self.last()['path'], '/routine/delete?name=x')
        self.run_cli('allow-subnets', '10.0.20.0/24,10.0.30.5')
        self.assertEqual(self.last()['path'], '/clients/allow?subnets=10.0.20.0%2F24%2C10.0.30.5')
        self.run_cli('assist-only')
        self.assertEqual(self.last()['path'], '/assist?host=')
        self.run_cli('sim-button', '3')
        self.assertEqual(self.last()['path'], '/sim/button?value=3')

    def test_usage_errors_send_nothing(self):
        for args in (['action'], ['volume', 'loud'], ['assist', 'maybe'], ['routine-put', 'nope.json'],
                     ['wifi-join'], ['serial']):
            with self.subTest(args):
                code, _, err = self.run_cli(*args)
                self.assertEqual(code, 1)
                self.assertIn('usage', err)
        self.assertEqual(FakeAgent.requests, [])

    def test_result_text_is_printed_plainly(self):
        FakeAgent.replies['/volume'] = (200, 'application/json', {'result': 'volume 40%'})
        code, out, _ = self.run_cli('volume', '40')
        self.assertEqual((code, out), (0, 'volume 40%\n'))
        self.assertEqual(self.last()['path'], '/volume?percent=40')

    def test_http_error_shows_the_agent_message(self):
        FakeAgent.replies['/status'] = (401, 'application/json', {'error': 'missing or wrong X-R2-Token'})
        code, _, err = self.run_cli('status')
        self.assertEqual(code, 1)
        self.assertIn('HTTP 401: missing or wrong X-R2-Token', err)

    def test_routine_put_sends_the_file_as_json(self):
        path = os.path.join(r2.ROOT, 'docs', 'routines')
        files = [f for f in os.listdir(path) if f.endswith('.json')] if os.path.isdir(path) else []
        if not files:
            self.skipTest('no example routines')
        self.run_cli('routine-put', os.path.join(path, files[0]))
        self.assertEqual(self.last()['type'], 'application/json')
        json.loads(self.last()['body'].decode('utf-8'))

    def test_routines_table(self):
        FakeAgent.replies['/routines'] = (200, 'application/json', {
            'running': 'party', 'last_routine': 'party',
            'routines': [{'name': 'party', 'steps': 4, 'timeout': 60, 'description': 'dance'}]})
        code, out, _ = self.run_cli('routines')
        self.assertEqual(code, 0)
        self.assertTrue(out.startswith('running: party   last: party\n'))
        self.assertIn('party', out.splitlines()[3])

    def test_logcat_passes_lines_and_filter(self):
        FakeAgent.replies['/logcat'] = (200, 'text/plain; charset=utf-8', 'a line\n')
        code, out, _ = self.run_cli('logcat', 'R2 Main', '--lines', '50')
        self.assertEqual((code, out), (0, 'a line\n'))
        self.assertEqual(self.last()['path'], '/logcat?lines=50&grep=R2%20Main')

    def test_mqtt(self):
        self.run_cli('mqtt', 'off')
        self.assertEqual(json.loads(self.last()['body']), {'enabled': False})
        self.run_cli('mqtt', '10.0.0.5')
        self.assertEqual(json.loads(self.last()['body']), {'host': '10.0.0.5', 'port': 1883})
        with mock.patch.object(r2.getpass, 'getpass', return_value='pw'):
            self.run_cli('mqtt', '10.0.0.5:1884', '--user', 'ha')
        self.assertEqual(json.loads(self.last()['body']),
                         {'host': '10.0.0.5', 'port': 1884, 'username': 'ha', 'password': 'pw'})

    def test_wifi_join_asks_for_the_password(self):
        with mock.patch.object(r2.getpass, 'getpass', return_value='secret'):
            self.run_cli('wifi-join', 'Home')
        self.assertEqual(self.last()['path'], '/wifi/join')
        self.assertEqual(json.loads(self.last()['body']), {'ssid': 'Home', 'password': 'secret'})

    def test_watch_with_me(self):
        self.run_cli('watch-start', 'film', '--at', '1:02:03')
        self.assertEqual(self.last()['path'], '/watch')
        self.assertEqual(json.loads(self.last()['body']), {'state': 'playing', 'cue': 'film', 'position': '1:02:03'})
        self.run_cli('watch-start', 'film')
        self.assertEqual(json.loads(self.last()['body'])['position'], 0)
        self.run_cli('watch-resume')
        self.assertEqual(json.loads(self.last()['body']), {'state': 'playing'})
        for cmd, body in (('watch-pause', {'state': 'paused'}), ('watch-stop', {'state': 'stopped'}),
                          ('watch-on', {'enabled': True}), ('watch-off', {'enabled': False})):
            self.run_cli(cmd)
            self.assertEqual(json.loads(self.last()['body']), body)
        self.run_cli('cue-delete', 'my film')
        self.assertEqual(self.last()['path'], '/cue/delete?name=my%20film')
        FakeAgent.replies['/cues'] = (200, 'application/json', {'cues': [{'name': 'f', 'match': ['A', 'B'], 'cues': 3}]})
        _, out, _ = self.run_cli('cues')
        self.assertIn('A, B', out)

    def test_drive_lock(self):
        for args, path in ((['drive'], '/drive'), (['drive-lock'], '/drive'), (['drive-lock', 'on'], '/drive?lock=1'),
                           (['drive-lock', 'off'], '/drive?lock=0'), (['drive-enable'], '/drive?enable=1&minutes=10'),
                           (['drive-enable', '30'], '/drive?enable=1&minutes=30'), (['drive-disable'], '/drive?enable=0')):
            with self.subTest(args):
                self.assertEqual(self.run_cli(*args)[0], 0)
                self.assertEqual(self.last()['path'], path)
        self.assertEqual(self.run_cli('drive-lock', 'maybe')[0], 1)

    def test_signature_matches_the_agent_test_vector(self):
        # SecurityTest's vector (apps/agent tests): the agent, the web page and r2.ps1 compute the same.
        h = r2.signed_headers('owner', 'test-key', 'POST', '/action?name=dance%20party&b=2', r2.EMPTY_SHA256,
                              1700000000, nonce='abcdefghijklmnop0123')
        self.assertEqual(h['X-R2-Signature'], 'a5eea87075222e27ce5763e65e8e82294456c2199b66cb6e1365b5c40faccd2f')
        self.assertEqual(r2.canonical('GET', '/x?r2sig=1&b=a+b&a=%26', 5, 'n', 'h'), 'R2-HMAC-SHA256\nGET\n/x\na=&&b=a b\n5\nn\nh')

    def test_requests_are_signed_when_the_agent_supports_it(self):
        FakeAgent.replies['/ping'] = (200, 'application/json', {'r2agent': True, 'time': 1700000000})
        with mock.patch.object(r2.time, 'time', return_value=1699999000.0):
            code, _, err = self.run_cli('serial', '{"cmd":"mode","mode":13}')
        self.assertEqual(code, 0, err)
        req = self.last()
        self.assertIsNone(req['token'], 'a signing key must never be sent in plain text')
        s = req['signed']
        self.assertEqual((s['X-R2-Client'], s['X-R2-Time']), ('owner', '1700000000'))  # the robot's clock
        self.assertEqual(s['X-R2-Body'], r2.hashlib.sha256(req['body']).hexdigest())
        want = r2.signed_headers('owner', 'tok', 'POST', '/serial', s['X-R2-Body'], 1700000000, nonce=s['X-R2-Nonce'])
        self.assertEqual(s['X-R2-Signature'], want['X-R2-Signature'])

    def test_clients_and_updates(self):
        FakeAgent.replies['/clients/add'] = (200, 'application/json', {'id': 'c1', 'key': 'k', 'role': 'control',
                                                                        'plain_token': True})
        code, out, _ = self.run_cli('client-add', 'home assistant', '--plain')
        self.assertEqual(self.last()['path'], '/clients/add?name=home%20assistant&role=control&plain=1')
        self.assertIn('token: k', out)
        self.run_cli('client-role', 'c1', '--role', 'read')
        self.assertEqual(self.last()['path'], '/clients/role?id=c1&role=read')
        self.assertEqual(self.run_cli('client-role', 'c1')[0], 1)
        self.run_cli('client-revoke', 'c1')
        self.assertEqual(self.last()['path'], '/clients/revoke?id=c1')
        self.run_cli('update-auto', 'on')
        self.assertEqual(self.last()['path'], '/update/auto?on=1')
        self.run_cli('update-source', 'someone/fork')
        self.assertEqual(self.last()['path'], '/update/source?repo=someone%2Ffork')
        self.assertEqual(self.run_cli('update-source', 'bad repo')[0], 1)
        self.run_cli('trust-add', 'ab12')
        self.assertEqual(self.last()['path'], '/trust?add=ab12')
        FakeAgent.replies['/clients/pair'] = (200, 'application/json', {'id': 'pc1', 'key': 'secret', 'role': 'admin'})
        with mock.patch.object(r2, 'write_secret') as ws:
            self.run_cli('pair')
        ws.assert_called_once_with('robot-client.txt', 'pc1:secret')

    def test_liveliness(self):
        FakeAgent.replies['/status'] = (200, 'application/json', {'liveliness': 'chatty'})
        self.assertEqual(self.run_cli('liveliness')[1], 'chatty\n')
        self.run_cli('liveliness', 'Quiet')
        self.assertEqual(self.last()['path'], '/action?name=liveliness-quiet')
        self.assertEqual(self.run_cli('liveliness', 'loud')[0], 1)

    def test_hotspot(self):
        FakeAgent.replies['/hotspot'] = (200, 'application/json', {'ssid': 'R2-D2', 'password': 'pw12345678'})
        code, out, _ = self.run_cli('hotspot')
        self.assertEqual((code, out), (0, 'name:     R2-D2\npassword: pw12345678\n'))
        self.assertEqual((self.last()['method'], self.last()['token']), ('GET', 'tok'))
        self.run_cli('hotspot-password', 'new')
        self.assertEqual(json.loads(self.last()['body']), {'password': ''})
        with mock.patch.object(r2.getpass, 'getpass', return_value='longpassword'):
            self.run_cli('hotspot-password')
        self.assertEqual(json.loads(self.last()['body']), {'password': 'longpassword'})

    def test_wifi_scan_sorted_by_signal(self):
        FakeAgent.replies['/wifi/scan'] = (200, 'application/json', {
            'networks': [{'ssid': 'weak', 'rssi': -80}, {'ssid': 'strong', 'rssi': -40}]})
        _, out, _ = self.run_cli('wifi-scan')
        names = [l.split()[0] for l in out.splitlines()[2:]]
        self.assertEqual(names, ['strong', 'weak'])

    def test_push_main_uploads_the_apk(self):
        apk = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'test_r2.py')
        self.run_cli('push-main', apk)
        self.assertEqual(self.last()['path'], '/main/install')
        self.assertEqual(self.last()['type'], 'application/vnd.android.package-archive')
        with open(apk, 'rb') as f:
            self.assertEqual(self.last()['body'], f.read())

    def test_fetch_token_in_setup_window(self):
        token = 'ab' * 24
        FakeAgent.replies['/setup/token'] = (200, 'application/json', {'token': token})
        with mock.patch.object(r2, 'write_secret') as ws:
            code, _, err = self.run_cli('fetch-token')
        self.assertEqual(code, 0, err)
        ws.assert_called_once_with('robot-token.txt', token)
        self.assertIsNone(self.last()['token'])

    def test_identity_body_from_factory_prefs(self):
        xml = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
               '<string name="robot_name">R2</string>\n<string name="robot_udid">abc-123</string>\n'
               '<string name="clientList">[{&quot;id&quot;:&quot;p1&quot;}]</string>\n</map>')
        self.assertEqual(json.loads(r2.identity_body(xml)),
                         {'name': 'R2', 'uuid': 'abc-123', 'clients': [{'id': 'p1'}]})
        with self.assertRaises(r2.R2Error):
            r2.identity_body('cat: No such file or directory')

    def test_every_r2_ps1_command_exists(self):
        with open(os.path.join(r2.TOOLS, 'r2.ps1'), encoding='utf-8') as f:
            switch = f.read().split('switch ($Command)', 1)[1]
        ps_cmds = set(re.findall(r"^\s+'([a-z-]+)'\s+\{", switch, re.M))
        for group in re.findall(r"\$_ -in ((?:'[a-z-]+',?\s*)+)", switch):
            ps_cmds |= set(re.findall(r"'([a-z-]+)'", group))
        self.assertTrue(ps_cmds, 'could not read r2.ps1')
        self.assertEqual(sorted(ps_cmds - set(r2.COMMANDS)), [], 'r2.ps1 commands missing from r2.py')
        actions = re.search(r"'actions'\s+\{\s*'([^']*)'", switch).group(1).split()
        self.assertEqual(sorted(set(actions) - set(r2.ACTIONS)), [], 'r2.ps1 actions missing from r2.py ACTIONS')


if __name__ == '__main__':
    unittest.main()
