"""A small MQTT 3.1.1 client (QoS 0, retained publishes, subscriptions, a last will), standard library only.

Enough for Home Assistant's MQTT discovery: the add-on publishes an update entity and two health sensors
and listens for the Install and Check buttons. Reconnects by itself.
"""

import socket
import struct
import threading
import time


def _str(s):
    b = s.encode('utf-8') if isinstance(s, str) else s
    return struct.pack('>H', len(b)) + b


def _packet(first, body):
    n, length = len(body), b''
    while True:
        byte, n = n % 128, n // 128
        length += bytes([byte | (0x80 if n else 0)])
        if not n:
            break
    return bytes([first]) + length + body


class Mqtt:
    def __init__(self, host, port=1883, username='', password='', client_id='r2-update-mirror',
                 will=None, on_message=None, on_connect=None, keepalive=60, log=print):
        self.host, self.port = host, port
        self.username, self.password = username, password
        self.client_id = client_id
        self.will = will  # (topic, payload, retain)
        self.on_message = on_message
        self.on_connect = on_connect
        self.keepalive = keepalive
        self.log = log
        self.subs = []
        self.sock = None
        self.lock = threading.Lock()
        self.connected = threading.Event()
        self.stopped = False
        self.next_id = 1

    # ---- public ------------------------------------------------------------------------------------

    def start(self):
        threading.Thread(target=self._run, name='mqtt', daemon=True).start()

    def stop(self):
        self.stopped = True
        with self.lock:
            if self.sock:
                try:
                    self.sock.sendall(b'\xe0\x00')  # DISCONNECT: no last will
                    self.sock.close()
                except OSError:
                    pass

    def subscribe(self, topic):
        if topic not in self.subs:
            self.subs.append(topic)
        if self.connected.is_set():
            self._send_subscribe(topic)

    def publish(self, topic, payload, retain=False):
        if isinstance(payload, str):
            payload = payload.encode('utf-8')
        pkt = _packet(0x30 | (1 if retain else 0), _str(topic) + payload)
        with self.lock:
            if not self.sock or not self.connected.is_set():
                return False
            try:
                self.sock.sendall(pkt)
                return True
            except OSError:
                return False

    # ---- connection --------------------------------------------------------------------------------

    def _connect(self):
        s = socket.create_connection((self.host, self.port), timeout=10)
        flags = 0x02  # clean session
        payload = _str(self.client_id)
        if self.will:
            topic, msg, retain = self.will
            flags |= 0x04 | (0x20 if retain else 0)
            payload += _str(topic) + _str(msg)
        if self.username:
            flags |= 0x80
            payload += _str(self.username)
            if self.password:
                flags |= 0x40
                payload += _str(self.password)
        s.sendall(_packet(0x10, _str('MQTT') + bytes([4, flags]) + struct.pack('>H', self.keepalive) + payload))
        first, body = self._read(s)
        if first >> 4 != 2 or len(body) < 2:
            raise OSError('no CONNACK from the broker')
        if body[1] != 0:
            raise OSError({4: 'bad user name or password', 5: 'not authorised'}.get(body[1], 'refused (%d)' % body[1]))
        s.settimeout(self.keepalive / 2.0)
        return s

    def _send_subscribe(self, topic):
        with self.lock:
            pid, self.next_id = self.next_id, self.next_id % 65535 + 1
            try:
                self.sock.sendall(_packet(0x82, struct.pack('>H', pid) + _str(topic) + b'\x00'))
            except OSError:
                pass

    @staticmethod
    def _read_exact(s, n):
        buf = b''
        while len(buf) < n:
            chunk = s.recv(n - len(buf))
            if not chunk:
                raise OSError('connection closed')
            buf += chunk
        return buf

    def _read(self, s):
        first = self._read_exact(s, 1)[0]
        mult, length = 1, 0
        while True:
            b = self._read_exact(s, 1)[0]
            length += (b & 0x7f) * mult
            if not b & 0x80:
                break
            mult *= 128
        return first, self._read_exact(s, length) if length else b''

    def _run(self):
        delay = 2
        while not self.stopped:
            try:
                s = self._connect()
                with self.lock:
                    self.sock = s
                self.connected.set()
                delay = 2
                self.log('MQTT: connected to %s:%d' % (self.host, self.port))
                for t in self.subs:
                    self._send_subscribe(t)
                if self.on_connect:
                    self.on_connect()
                last_ping = time.time()
                while not self.stopped:
                    try:
                        first, body = self._read(s)
                    except socket.timeout:
                        first = None
                    if first is not None and first >> 4 == 3:  # PUBLISH
                        tlen = struct.unpack('>H', body[:2])[0]
                        topic = body[2:2 + tlen].decode('utf-8', 'replace')
                        rest = body[2 + tlen:]
                        if (first >> 1) & 3:  # QoS 1/2: skip the packet id (we only subscribe at QoS 0)
                            rest = rest[2:]
                        if self.on_message:
                            try:
                                self.on_message(topic, rest)
                            except Exception as e:  # a bad handler must not drop the connection
                                self.log('MQTT: handler for %s failed: %s' % (topic, e))
                    if time.time() - last_ping >= self.keepalive / 2.0:
                        with self.lock:
                            s.sendall(b'\xc0\x00')  # PINGREQ
                        last_ping = time.time()
            except OSError as e:
                if not self.stopped:
                    self.log('MQTT: %s:%d: %s; retrying in %d s' % (self.host, self.port, e, delay))
            self.connected.clear()
            with self.lock:
                if self.sock:
                    try:
                        self.sock.close()
                    except OSError:
                        pass
                self.sock = None
            if self.stopped:
                break
            time.sleep(delay)
            delay = min(delay * 2, 60)
