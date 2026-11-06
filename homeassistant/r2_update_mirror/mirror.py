#!/usr/bin/env python3
"""R2-D2 update mirror: a Home Assistant add-on (docs/update-mirror.md).

R2's Android 4.4 can't open a TLS connection to GitHub, so this downloads each new release of the
repositories R2 follows, checks it, and serves it over plain HTTP on the local network in the layout the
agent reads (apps/agent/src/dev/r2hack/agent/UpdateMirror.java):

    <data>/<owner>/<repo>/latest.json
    <data>/<owner>/<repo>/<tag>/R2Main.apk, R2Agent.apk

The mirror is only a delivery route. R2 still installs nothing that isn't signed by a certificate it
trusts, for the right package, with a newer versionCode. The checks here catch problems early and show
them on the add-on's page: the files must match the release's SHA256SUMS, both APKs must be signed by the
same certificate, that certificate must be the one the release notes name, and, if you list some in
trusted_certs, one of those.

Python 3 standard library only. Run outside Home Assistant with --options <file.json> --data <folder>;
`mirror.py apk-info <file.apk>` prints what it reads from an APK (CI compares it with the Android SDK's).
"""

import argparse
import hashlib
import html
import http.server
import json
import os
import re
import shutil
import socketserver
import struct
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile

ADDON_VERSION = '1.0.0'
DEFAULT_REPO = 'Sk3yNet/Fanhome_R2-D2_Custom_Software'
ASSETS = ('R2Main.apk', 'R2Agent.apk')
MAX_APK = 64 * 1024 * 1024  # the agent's limit too
REPO_RE = re.compile(r'^[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}$')
TAG_RE = re.compile(r'^[A-Za-z0-9._+-]{1,80}$')
CERT_RE = re.compile(r'^[0-9a-f]{64}$')
GITHUB_API = 'https://api.github.com'


class MirrorError(Exception):
    pass


def log(msg):
    print(time.strftime('%Y-%m-%d %H:%M:%S ') + msg, flush=True)


# ---- reading APKs (the same as tools/r2.py apk_cert / apk_version_code) ---------------------------

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
    with zipfile.ZipFile(apk) as z:
        for name in z.namelist():
            if re.match(match, name):
                return z.read(name)
    return None


def apk_cert(apk):
    """SHA-256 of the signing certificate (v1 JAR signature), as apksigner and the release notes give it."""
    p7 = _read_apk_entry(apk, r'^META-INF/[^/]+\.(RSA|DSA|EC)$')
    if not p7:
        raise MirrorError('%s has no v1 (JAR) signature' % os.path.basename(apk))
    try:
        _, s, e = _der(p7, 0)
        ci = _der_children(p7, s, e)
        _, s, e = _der(p7, ci[1][2])
        for tag, start, cs, ce in _der_children(p7, s, e):
            if tag == 0xa0:
                _, _, cert_end = _der(p7, cs)
                return hashlib.sha256(p7[cs:cert_end]).hexdigest()
    except (IndexError, ValueError):
        pass
    raise MirrorError('%s: no certificate in its signature' % os.path.basename(apk))


def apk_version_code(apk):
    """android:versionCode from the binary AndroidManifest.xml."""
    b = _read_apk_entry(apk, r'^AndroidManifest\.xml$')
    name = os.path.basename(apk)
    if not b or len(b) < 8:
        raise MirrorError('%s has no AndroidManifest.xml' % name)
    u16 = lambda o: struct.unpack_from('<H', b, o)[0]
    u32 = lambda o: struct.unpack_from('<I', b, o)[0]
    try:
        if u16(0) != 0x0003:
            raise MirrorError('%s: AndroidManifest.xml is not binary XML' % name)
        res_ids = []
        off = u16(2)
        while off + 8 <= len(b):
            ctype, header_size, size = u16(off), u16(off + 2), u32(off + 4)
            if size < 8:
                break
            if ctype == 0x0180:
                res_ids = [u32(off + header_size + 4 * i) for i in range((size - header_size) // 4)]
            elif ctype == 0x0102:
                ext = off + header_size
                attr_start, attr_size, attr_count = u16(ext + 8), u16(ext + 10), u16(ext + 12)
                for i in range(attr_count):
                    at = ext + attr_start + i * attr_size
                    attr = u32(at + 4)
                    if attr < len(res_ids) and res_ids[attr] == 0x0101021b:  # android:versionCode
                        return struct.unpack_from('<i', b, at + 16)[0]
                raise MirrorError('%s: no versionCode in the manifest' % name)
            off += size
    except struct.error:
        pass
    raise MirrorError('%s: manifest tag not found' % name)


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(65536), b''):
            h.update(chunk)
    return h.hexdigest()


# ---- options ----------------------------------------------------------------------------------------

def load_options(path):
    try:
        with open(path) as f:
            o = json.load(f)
    except FileNotFoundError:
        o = {}
    repos = [r.strip() for r in (o.get('repositories') or [DEFAULT_REPO]) if r and r.strip()]
    for r in repos:
        if not REPO_RE.match(r) or '..' in r:
            raise MirrorError('repositories: %r should look like owner/name' % r)
    certs = [c.strip().lower().replace(':', '') for c in (o.get('trusted_certs') or []) if c and c.strip()]
    for c in certs:
        if not CERT_RE.match(c):
            raise MirrorError('trusted_certs: %r is not a SHA-256 (64 hex digits)' % c)
    return {
        'repositories': repos or [DEFAULT_REPO],
        'check_hours': max(1, min(168, int(o.get('check_hours', 6)))),
        'prereleases': bool(o.get('prereleases', False)),
        'keep_releases': max(1, min(10, int(o.get('keep_releases', 2)))),
        'trusted_certs': certs,
        'github_token': (o.get('github_token') or '').strip(),
        'port': int(o.get('port', 8765)),
    }


# ---- fetching releases ------------------------------------------------------------------------------

class Mirror:
    def __init__(self, data_dir, options, api=GITHUB_API):
        self.data = os.path.abspath(data_dir)
        self.opts = options
        self.api = api.rstrip('/')
        self.state = {}  # repo -> {'checked': t, 'result': str, 'ok': bool}
        self.lock = threading.Lock()
        os.makedirs(self.data, exist_ok=True)

    def _get(self, url, accept, to=None, max_bytes=1024 * 1024):
        headers = {'User-Agent': 'r2-update-mirror/' + ADDON_VERSION, 'Accept': accept}
        # The token goes to the API only, never to the file host a download redirects to.
        if self.opts['github_token'] and url.startswith(self.api + '/repos/'):
            headers['Authorization'] = 'Bearer ' + self.opts['github_token']
        req = urllib.request.Request(url, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                length = r.headers.get('Content-Length')
                if length and int(length) > max_bytes:
                    raise MirrorError('download too large: ' + url)
                out = open(to, 'wb') if to else None
                buf, total = [], 0
                try:
                    while True:
                        chunk = r.read(65536)
                        if not chunk:
                            break
                        total += len(chunk)
                        if total > max_bytes:
                            raise MirrorError('download too large: ' + url)
                        if out:
                            out.write(chunk)
                        else:
                            buf.append(chunk)
                finally:
                    if out:
                        out.close()
                return None if to else b''.join(buf)
        except urllib.error.HTTPError as e:
            if e.code == 404:
                raise MirrorError('not found (no such repository, no release yet, or a private repository): ' + url)
            if e.code in (403, 429):
                raise MirrorError('GitHub refused (rate limit? set github_token): HTTP %d' % e.code)
            raise MirrorError('HTTP %d from %s' % (e.code, url))
        except urllib.error.URLError as e:
            raise MirrorError('could not reach %s: %s' % (urllib.parse.urlsplit(url).netloc, e.reason))

    def pick_release(self, repo):
        releases = json.loads(self._get('%s/repos/%s/releases?per_page=20' % (self.api, repo),
                                        'application/vnd.github+json').decode('utf-8'))
        for rel in releases:
            if rel.get('draft') or (rel.get('prerelease') and not self.opts['prereleases']):
                continue
            names = {a.get('name') for a in rel.get('assets') or []}
            if set(ASSETS) <= names and 'SHA256SUMS' in names:
                return rel
        raise MirrorError('no release of %s has R2Main.apk, R2Agent.apk and SHA256SUMS%s'
                          % (repo, '' if self.opts['prereleases'] else ' (pre-releases are skipped)'))

    def repo_dir(self, repo):
        owner, name = repo.split('/')
        return os.path.join(self.data, owner, name)

    def manifest(self, repo):
        try:
            with open(os.path.join(self.repo_dir(repo), 'latest.json')) as f:
                return json.load(f)
        except (OSError, ValueError):
            return None

    def update(self, repo):
        """Mirrors the newest release of repo if it isn't here yet. Returns a one-line result."""
        rel = self.pick_release(repo)
        tag = rel.get('tag_name') or ''
        if not TAG_RE.match(tag) or '..' in tag:
            raise MirrorError('release tag %r is not usable as a folder name' % tag)
        have = self.manifest(repo)
        rdir = self.repo_dir(repo)
        if have and have.get('tag') == tag and all(
                os.path.isfile(os.path.join(rdir, have['assets'][n]['path'])) for n in ASSETS):
            return 'up to date: %s' % tag
        urls = {a['name']: a['browser_download_url'] for a in rel['assets']}
        sums = parse_sha256sums(self._get(urls['SHA256SUMS'], 'application/octet-stream').decode('utf-8', 'replace'))
        tmp = os.path.join(rdir, '.incoming')
        shutil.rmtree(tmp, ignore_errors=True)
        os.makedirs(tmp)
        try:
            assets = {}
            for name in ASSETS:
                if name not in sums:
                    raise MirrorError('SHA256SUMS has no line for %s' % name)
                path = os.path.join(tmp, name)
                self._get(urls[name], 'application/octet-stream', to=path, max_bytes=MAX_APK)
                sha = sha256_file(path)
                if sha != sums[name]:
                    raise MirrorError('%s does not match SHA256SUMS (damaged or replaced download)' % name)
                assets[name] = {'path': '%s/%s' % (tag, name), 'sha256': sha, 'size': os.path.getsize(path),
                                'version_code': apk_version_code(path), 'cert': apk_cert(path)}
            cert = check_signers(assets, rel.get('body') or '', self.opts['trusted_certs'])
            dest = os.path.join(rdir, tag)
            shutil.rmtree(dest, ignore_errors=True)
            os.rename(tmp, dest)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)
        man = {'repo': repo, 'tag': tag, 'version': re.sub(r'^v', '', tag), 'published': rel.get('published_at', ''),
               'url': rel.get('html_url', ''), 'cert': cert, 'mirrored': int(time.time()), 'assets': assets}
        write_json(os.path.join(rdir, 'latest.json'), man)
        self.prune(repo, tag)
        return 'mirrored %s (signed by %s...)' % (tag, cert[:16])

    def prune(self, repo, current):
        """Keeps the newest keep_releases tag folders (by the time they were mirrored)."""
        rdir = self.repo_dir(repo)
        dirs = [d for d in os.listdir(rdir) if os.path.isdir(os.path.join(rdir, d)) and not d.startswith('.')]
        dirs.sort(key=lambda d: (d == current, os.path.getmtime(os.path.join(rdir, d))), reverse=True)
        for d in dirs[self.opts['keep_releases']:]:
            shutil.rmtree(os.path.join(rdir, d), ignore_errors=True)
            log('%s: removed old release %s' % (repo, d))

    def update_all(self):
        for repo in self.opts['repositories']:
            try:
                result, ok = self.update(repo), True
            except Exception as e:  # keep serving what we have
                result, ok = 'check failed: %s' % e, False
            with self.lock:
                self.state[repo] = {'checked': int(time.time()), 'result': result, 'ok': ok}
            log('%s: %s' % (repo, result))

    def run_forever(self):
        while True:
            self.update_all()
            time.sleep(self.opts['check_hours'] * 3600)


def parse_sha256sums(text):
    sums = {}
    for line in text.splitlines():
        m = re.match(r'^([0-9a-fA-F]{64})\s+\*?(\S+)\s*$', line)
        if m:
            sums[os.path.basename(m.group(2))] = m.group(1).lower()
    return sums


def check_signers(assets, notes, trusted):
    """The one certificate both APKs are signed with; refuses mixed signers, a certificate that isn't the
    one the release notes name, and, when trusted is set, one that isn't in it."""
    certs = {a['cert'] for a in assets.values()}
    if len(certs) != 1:
        raise MirrorError('R2Main.apk and R2Agent.apk are signed with different keys')
    cert = certs.pop()
    named = re.findall(r'certificate SHA-256:\s*`?([0-9a-fA-F:]{64,95})`?', notes)
    named = [n.replace(':', '').lower() for n in named]
    if named and cert not in named:
        raise MirrorError('the APKs are signed by %s, but the release notes name %s' % (cert, named[0]))
    if trusted and cert not in trusted:
        raise MirrorError('signed by %s, which is not in trusted_certs' % cert)
    return cert


def write_json(path, obj):
    tmp = path + '.tmp'
    with open(tmp, 'w') as f:
        json.dump(obj, f, indent=2)
    os.replace(tmp, path)


# ---- serving ----------------------------------------------------------------------------------------

def make_handler(mirror):
    class Handler(http.server.BaseHTTPRequestHandler):
        server_version = 'r2-update-mirror/' + ADDON_VERSION

        def log_message(self, fmt, *args):
            if not self.path.endswith('.apk'):
                return
            log('%s %s' % (self.client_address[0], fmt % args))

        def do_HEAD(self):
            self.do_GET(head=True)

        def do_GET(self, head=False):
            path = urllib.parse.urlsplit(self.path).path
            if path in ('/', '/index.html'):
                return self.send(200, 'text/html; charset=utf-8', status_page(mirror, self.headers.get('Host')).encode(), head)
            if path == '/status.json':
                return self.send(200, 'application/json', json.dumps(status(mirror), indent=2).encode(), head)
            file = resolve(mirror.data, path)
            if not file:
                return self.send(404, 'text/plain', b'not found\n', head)
            ctype = 'application/json' if file.endswith('.json') else 'application/vnd.android.package-archive'
            size = os.path.getsize(file)
            self.send_response(200)
            self.send_header('Content-Type', ctype)
            self.send_header('Content-Length', str(size))
            self.send_header('Cache-Control', 'no-cache')
            self.end_headers()
            if not head:
                with open(file, 'rb') as f:
                    shutil.copyfileobj(f, self.wfile)

        def send(self, code, ctype, body, head):
            self.send_response(code)
            self.send_header('Content-Type', ctype)
            self.send_header('Content-Length', str(len(body)))
            self.send_header('Cache-Control', 'no-cache')
            self.end_headers()
            if not head:
                self.wfile.write(body)

    return Handler


def resolve(root, url_path):
    """The file under root for a URL path, or None. Only <owner>/<repo>/latest.json and
    <owner>/<repo>/<tag>/<asset>.apk are served."""
    parts = urllib.parse.unquote(url_path).strip('/').split('/')
    ok = (len(parts) == 3 and parts[2] == 'latest.json') or (len(parts) == 4 and parts[3] in ASSETS)
    if not ok or any(p in ('', '.', '..') or not re.match(r'^[A-Za-z0-9._+-]+$', p) for p in parts):
        return None
    file = os.path.realpath(os.path.join(root, *parts))
    if not file.startswith(os.path.realpath(root) + os.sep) or not os.path.isfile(file):
        return None
    return file


def status(mirror):
    out = []
    with mirror.lock:
        state = dict(mirror.state)
    for repo in mirror.opts['repositories']:
        man = mirror.manifest(repo) or {}
        out.append({'repo': repo, 'tag': man.get('tag'), 'cert': man.get('cert'),
                    'published': man.get('published'), 'state': state.get(repo)})
    return {'version': ADDON_VERSION, 'check_hours': mirror.opts['check_hours'], 'repositories': out}


def status_page(mirror, host):
    s = status(mirror)
    host = host or 'this-machine:%d' % mirror.opts['port']
    if not re.match(r'^[A-Za-z0-9.\-\[\]:]+$', host):
        host = 'this-machine:%d' % mirror.opts['port']
    rows = []
    for r in s['repositories']:
        st = r['state'] or {}
        when = time.strftime('%Y-%m-%d %H:%M', time.localtime(st['checked'])) if st.get('checked') else 'not yet'
        rows.append('<tr><td>%s</td><td>%s</td><td><code>%s</code></td><td class="%s">%s<br><small>%s</small></td></tr>' % (
            html.escape(r['repo']), html.escape(r['tag'] or '-'), html.escape(r['cert'] or '-'),
            'ok' if st.get('ok', True) else 'bad', html.escape(st.get('result', 'waiting for the first check')),
            html.escape(when)))
    cmd = 'tools\\r2.ps1 update-mirror http://%s' % host
    return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<title>R2-D2 update mirror</title><style>
body{font:15px system-ui,sans-serif;margin:16px;max-width:900px;color:#222;background:#fff}
table{border-collapse:collapse;width:100%%}td,th{border-bottom:1px solid #ddd;padding:6px;text-align:left;vertical-align:top}
code{word-break:break-all;font-size:12px}.bad{color:#b00}pre{background:#f4f4f4;padding:8px;overflow-x:auto}
@media (prefers-color-scheme: dark){body{background:#111;color:#ddd}pre{background:#222}td,th{border-color:#333}.bad{color:#f66}}
</style></head><body>
<h1>R2-D2 update mirror</h1>
<p>Copies R2's signed releases from GitHub every %d hours and serves them to R2 on this network.
R2 installs only APKs signed by a certificate it trusts, so this mirror can't push anything else onto it.</p>
<table><tr><th>Repository</th><th>Release</th><th>Signing certificate (SHA-256)</th><th>Last check</th></tr>%s</table>
<h2>Point R2 at this mirror (once)</h2>
<pre>%s
tools\\r2.ps1 update-check
tools\\r2.ps1 update-install</pre>
<p>Or with <code>tools/r2.py</code> on Linux or macOS. Use this machine's IP address if R2 can't resolve its name.
<code>update-auto on</code> makes R2 check every 6 hours and install while idle. Restart the add-on to check GitHub now.</p>
<p><small>Add-on %s · <a href="status.json">status.json</a></small></p>
</body></html>""" % (s['check_hours'], ''.join(rows), html.escape(cmd), ADDON_VERSION)


class Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    p.add_argument('command', nargs='?', default='serve', choices=['serve', 'once', 'apk-info'])
    p.add_argument('file', nargs='?')
    p.add_argument('--options', default='/data/options.json')
    p.add_argument('--data', default='/data/mirror')
    a = p.parse_args(argv)
    if a.command == 'apk-info':
        print('cert %s  versionCode %d' % (apk_cert(a.file), apk_version_code(a.file)))
        return 0
    opts = load_options(a.options)
    mirror = Mirror(a.data, opts)
    if a.command == 'once':
        mirror.update_all()
        return 0 if all(s['ok'] for s in mirror.state.values()) else 1
    log('R2-D2 update mirror %s: %s, every %d h, port %d' % (
        ADDON_VERSION, ', '.join(opts['repositories']), opts['check_hours'], opts['port']))
    threading.Thread(target=mirror.run_forever, daemon=True).start()
    Server(('', opts['port']), make_handler(mirror)).serve_forever()
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except MirrorError as e:
        print('error: %s' % e, file=sys.stderr)
        sys.exit(1)
