"""Off-robot tests for the update mirror add-on, against a pretend GitHub on localhost.
Run: python3 -m unittest discover -s homeassistant/r2_update_mirror -p 'test_*.py'"""

import hashlib
import http.server
import io
import json
import os
import shutil
import struct
import sys
import tempfile
import threading
import unittest
import urllib.request
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import mirror  # noqa: E402

REPO = 'Sk3yNet/Fanhome_R2-D2_Custom_Software'


def der(tag, content):
    n = len(content)
    if n < 0x80:
        head = bytes([tag, n])
    else:
        k = (n.bit_length() + 7) // 8
        head = bytes([tag, 0x80 | k]) + n.to_bytes(k, 'big')
    return head + content


def fake_cert(name):
    """Any DER SEQUENCE will do as the 'certificate': the mirror hashes its bytes, as apksigner does."""
    return der(0x30, der(0x0c, name.encode()) + der(0x04, b'x' * 200))


def pkcs7(cert):
    oid = der(0x06, b'\x2a\x86\x48\x86\xf7\x0d\x01\x07\x02')
    signed = der(0x30, der(0x02, b'\x01') + der(0x31, b'') + der(0x30, der(0x06, b'\x2a\x86\x48\x86\xf7\x0d\x01\x07\x01'))
                 + der(0xa0, cert) + der(0x31, b''))
    return der(0x30, oid + der(0xa0, signed))


def binary_manifest(version_code):
    """A minimal binary AndroidManifest.xml: a resource map and a <manifest> tag with android:versionCode."""
    res_map = struct.pack('<HHI', 0x0180, 8, 12) + struct.pack('<I', 0x0101021b)
    ext = struct.pack('<IIHHHHHH', 0xffffffff, 1, 20, 20, 1, 0, 0, 0)
    attr = struct.pack('<IIIHBBi', 0xffffffff, 0, 0xffffffff, 8, 0, 0x10, version_code)
    tag = struct.pack('<HHI', 0x0102, 16, 16 + len(ext) + len(attr)) + struct.pack('<II', 1, 0xffffffff) + ext + attr
    body = res_map + tag
    return struct.pack('<HHI', 0x0003, 8, 8 + len(body)) + body


def fake_apk(cert, version_code, extra=b''):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, 'w') as z:
        z.writestr('AndroidManifest.xml', binary_manifest(version_code))
        z.writestr('classes.dex', b'dex' + extra)
        z.writestr('META-INF/CERT.RSA', pkcs7(cert))
    return buf.getvalue()


class FakeGitHub(http.server.BaseHTTPRequestHandler):
    releases = []
    files = {}
    auth = []

    def log_message(self, *args):
        pass

    def do_GET(self):
        FakeGitHub.auth.append((self.path, self.headers.get('Authorization')))
        if self.path.startswith('/repos/%s/releases' % REPO):
            body = json.dumps(FakeGitHub.releases).encode()
        elif self.path in FakeGitHub.files:
            body = FakeGitHub.files[self.path]
        else:
            self.send_response(404)
            self.end_headers()
            return
        self.send_response(200)
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)


class MirrorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.gh = http.server.ThreadingHTTPServer(('127.0.0.1', 0), FakeGitHub)
        threading.Thread(target=cls.gh.serve_forever, daemon=True).start()
        cls.base = 'http://127.0.0.1:%d' % cls.gh.server_address[1]
        cls.cert = fake_cert('CN=release')
        cls.cert_sha = hashlib.sha256(cls.cert).hexdigest()

    @classmethod
    def tearDownClass(cls):
        cls.gh.shutdown()
        cls.gh.server_close()

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.dir)
        FakeGitHub.releases, FakeGitHub.files, FakeGitHub.auth = [], {}, []
        os.environ.pop('HTTPS_PROXY', None)
        os.environ['NO_PROXY'] = '127.0.0.1'

    def opts(self, **kw):
        path = os.path.join(self.dir, 'options.json')
        with open(path, 'w') as f:
            json.dump(kw, f)
        return mirror.load_options(path)

    def release(self, tag, main=None, agent=None, sums=None, prerelease=False, notes=None):
        main = main or fake_apk(self.cert, 100, tag.encode())
        agent = agent or fake_apk(self.cert, 101, tag.encode())
        if sums is None:
            sums = '%s  R2Agent.apk\n%s  R2Main.apk\n' % (hashlib.sha256(agent).hexdigest(), hashlib.sha256(main).hexdigest())
        assets = []
        for name, data in (('R2Main.apk', main), ('R2Agent.apk', agent), ('SHA256SUMS', sums.encode())):
            path = '/dl/%s/%s' % (tag, name)
            FakeGitHub.files[path] = data
            assets.append({'name': name, 'browser_download_url': self.base + path})
        if notes is None:
            notes = 'Signing certificate SHA-256:\n`%s`\n' % self.cert_sha
        rel = {'tag_name': tag, 'draft': False, 'prerelease': prerelease, 'published_at': '2026-11-06T12:00:00Z',
               'html_url': 'https://github.com/%s/releases/tag/%s' % (REPO, tag), 'body': notes, 'assets': assets}
        FakeGitHub.releases.insert(0, rel)
        return main, agent

    def mirror(self, **kw):
        return mirror.Mirror(os.path.join(self.dir, 'data'), self.opts(**kw), api=self.base)

    def test_mirrors_a_release_in_the_layout_the_agent_reads(self):
        main, agent = self.release('v0.3.0')
        m = self.mirror(github_token='tkn')
        self.assertTrue(m.update(REPO).startswith('mirrored v0.3.0'))
        man = m.manifest(REPO)
        self.assertEqual(man['repo'], REPO)
        self.assertEqual((man['tag'], man['version'], man['cert']), ('v0.3.0', '0.3.0', self.cert_sha))
        a = man['assets']['R2Main.apk']
        self.assertEqual(a['path'], 'v0.3.0/R2Main.apk')
        self.assertEqual((a['sha256'], a['size'], a['version_code']), (hashlib.sha256(main).hexdigest(), len(main), 100))
        self.assertEqual(man['assets']['R2Agent.apk']['version_code'], 101)
        with open(os.path.join(m.repo_dir(REPO), 'v0.3.0', 'R2Agent.apk'), 'rb') as f:
            self.assertEqual(f.read(), agent)
        # The same fields as the example the agent's test reads (apps/agent/test/.../UpdateMirrorTest.java).
        with open(os.path.join(HERE, 'example-latest.json')) as f:
            example = json.load(f)
        self.assertEqual(sorted(man), sorted(example))
        self.assertEqual(sorted(a), sorted(example['assets']['R2Main.apk']))
        # The token goes to the API only, not to file downloads.
        for path, auth in FakeGitHub.auth:
            self.assertEqual(auth, 'Bearer tkn' if path.startswith('/repos/') else None, path)
        self.assertEqual(m.update(REPO), 'up to date: v0.3.0')

    def test_refuses_bad_releases_and_keeps_the_old_one(self):
        self.release('v1')
        m = self.mirror()
        m.update(REPO)
        cases = {
            'v2': dict(sums='%s  R2Main.apk\n%s  R2Agent.apk\n' % ('0' * 64, '0' * 64)),
            'v3': dict(main=fake_apk(fake_cert('CN=someone-else'), 5)),
            'v4': dict(notes='Signing certificate SHA-256:\n`%s`\n' % ('ab' * 32)),
        }
        want = {'v2': 'does not match SHA256SUMS', 'v3': 'different keys', 'v4': 'release notes name'}
        for tag, kw in cases.items():
            self.release(tag, **kw)
            with self.assertRaises(mirror.MirrorError) as e:
                m.update(REPO)
            self.assertIn(want[tag], str(e.exception))
            self.assertEqual(m.manifest(REPO)['tag'], 'v1')
            self.assertFalse(os.path.exists(os.path.join(m.repo_dir(REPO), tag)))
            FakeGitHub.releases.pop(0)

    def test_trusted_certs(self):
        self.release('v1')
        with self.assertRaises(mirror.MirrorError):
            self.mirror(trusted_certs=['cd' * 32]).update(REPO)
        colons = ':'.join(self.cert_sha[i:i + 2].upper() for i in range(0, 64, 2))
        self.assertTrue(self.mirror(trusted_certs=[colons]).update(REPO).startswith('mirrored'))

    def test_prereleases_skipped_unless_asked(self):
        self.release('v1')
        self.release('v2-beta.1', prerelease=True)
        self.assertIn('v1', self.mirror().update(REPO))
        shutil.rmtree(os.path.join(self.dir, 'data'))
        self.assertIn('v2-beta.1', self.mirror(prereleases=True).update(REPO))

    def test_keeps_only_the_newest_releases(self):
        m = self.mirror(keep_releases=2)
        for tag in ('v1', 'v2', 'v3'):
            self.release(tag)
            m.update(REPO)
        self.assertEqual(sorted(d for d in os.listdir(m.repo_dir(REPO)) if not d.endswith('.json')), ['v2', 'v3'])

    def test_serves_only_mirror_files(self):
        main, _ = self.release('v1')
        m = self.mirror()
        m.update_all()
        srv, base = self.serve(mirror.App(m, m.opts, self.dir, action_ips=set()))
        get = lambda p: urllib.request.urlopen(base + p, timeout=5).read()
        man = json.loads(get('/%s/latest.json' % REPO))
        self.assertEqual(get('/%s/%s' % (REPO, man['assets']['R2Main.apk']['path'])), main)
        page = get('/').decode()
        self.assertIn('update-mirror http://127.0.0.1:%d' % srv.server_address[1], page)
        self.assertIn(self.cert_sha, page)
        self.assertTrue(json.loads(get('/status.json'))['repositories'][0]['state']['ok'])
        with open(os.path.join(self.dir, 'options.json'), 'w') as f:
            f.write('secret')
        for bad in ('/options.json', '/../options.json', '/%2e%2e/options.json', '/Sk3yNet/../../options.json',
                    '/%s/v1/SHA256SUMS' % REPO, '/%s/.incoming/R2Main.apk' % REPO, '/%s/v1/../latest.json' % REPO):
            with self.assertRaises(urllib.error.HTTPError, msg=bad) as e:
                get(bad)
            self.assertEqual(e.exception.code, 404)

    def serve(self, app):
        srv = mirror.Server(('127.0.0.1', 0), mirror.make_handler(app))
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        self.addCleanup(srv.server_close)
        self.addCleanup(srv.shutdown)
        return srv, 'http://127.0.0.1:%d' % srv.server_address[1]

    def test_options(self):
        self.assertEqual(self.opts()['repositories'], [REPO])
        for typed, want in (('192.168.2.103', ('192.168.2.103', 7700)), ('http://192.168.2.103:7700', ('192.168.2.103', 7700)),
                            ('HTTP://r2.lan:8000/', ('r2.lan', 8000)), (' 10.0.0.5 ', ('10.0.0.5', 7700))):
            o = self.opts(robot=typed)
            self.assertEqual((o['robot'], o['robot_port']), want, typed)
        self.assertEqual((self.opts()['robot'], self.opts()['backup_settings'], self.opts()['ha_entities']), ('', False, True))
        for bad in ({'repositories': ['not a repo']}, {'repositories': ['a/..']}, {'trusted_certs': ['xyz']},
                    {'robot': 'ftp://x/'}, {'robot': 'http://x:99999'}, {'robot': 'x/path'}):
            with self.assertRaises(mirror.MirrorError):
                self.opts(**bad)

    def test_apk_info_matches_r2_py(self):
        sys.path.insert(0, os.path.join(HERE, '..', '..', 'tools'))
        import r2
        path = os.path.join(self.dir, 'x.apk')
        with open(path, 'wb') as f:
            f.write(fake_apk(self.cert, 4242))
        self.assertEqual((mirror.apk_cert(path), mirror.apk_version_code(path)), (r2.apk_cert(path), r2.apk_version_code(path)))
        self.assertEqual(mirror.apk_version_code(path), 4242)


if __name__ == '__main__':
    unittest.main()
