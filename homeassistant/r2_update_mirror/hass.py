"""Home Assistant side of the update mirror add-on: the Supervisor API (MQTT broker details, this machine's
address, notifications) and the MQTT discovery entities (an update entity with an Install button, a
Check button, and two health sensors)."""

import json
import os
import urllib.request

import mqtt_lite

PREFIX = 'homeassistant'
BASE = 'r2_update_mirror'
NODE = 'r2_update_mirror'
SUPERVISOR = 'http://supervisor'


def supervisor_token():
    """The Supervisor's token. The s6 init in Home Assistant's base images may keep the container's
    environment from the command it starts; it also leaves a copy here."""
    token = os.environ.get('SUPERVISOR_TOKEN')
    if not token:
        try:
            with open('/run/s6/container_environment/SUPERVISOR_TOKEN') as f:
                token = f.read().strip()
        except OSError:
            token = None
    return token or None


def supervisor(path, method='GET', body=None, timeout=10):
    """Supervisor API call; None outside Home Assistant or on any error."""
    token = supervisor_token()
    if not token:
        return None
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(SUPERVISOR + path, data=data, method=method,
                                 headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            out = json.loads(r.read().decode('utf-8') or '{}')
    except Exception:
        return None
    return out.get('data', out) if isinstance(out, dict) else out


def notify(title, message, ident='r2_update_mirror'):
    """A persistent notification in Home Assistant (needs homeassistant_api in config.yaml)."""
    return supervisor('/core/api/services/persistent_notification/create', 'POST',
                      {'title': title, 'message': message, 'notification_id': ident}) is not None


def lan_address():
    """This machine's address on the primary network interface, or None outside Home Assistant."""
    for iface in (supervisor('/network/info') or {}).get('interfaces') or []:
        if iface.get('primary') and iface.get('ipv4') and iface['ipv4'].get('address'):
            return iface['ipv4']['address'][0].split('/')[0]
    return None


def mirror_url():
    """http://<this machine's LAN address>:<host port of 8765>, or None outside Home Assistant."""
    info = supervisor('/addons/self/info') or {}
    port = (info.get('network') or {}).get('8765/tcp')
    if not port:
        return None
    addr = lan_address()
    return 'http://%s:%d' % (addr, port) if addr else None


def mqtt_settings():
    """The broker the Supervisor offers (the Mosquitto add-on), or None."""
    s = supervisor('/services/mqtt')
    if not s or not s.get('host'):
        return None
    return {'host': s['host'], 'port': int(s.get('port') or 1883), 'username': s.get('username') or '',
            'password': s.get('password') or ''}


class HassLink:
    """Publishes the entities and calls back on Install / Check. app needs: state() -> dict with
    installed, latest, release_url, release_summary, in_progress, reachable, adb_open, problems; and
    install(), check_now()."""

    def __init__(self, app, broker, log=print):
        self.app = app
        self.log = log
        self.device = {'identifiers': ['r2d2_update_mirror'], 'name': 'R2-D2 updates',
                       'manufacturer': 'Fanhome R2-D2, custom software', 'model': 'Update mirror add-on'}
        self.r2_device_seen = False
        self.client = mqtt_lite.Mqtt(broker['host'], broker['port'], broker['username'], broker['password'],
                                     client_id='r2-update-mirror', will=(BASE + '/availability', 'offline', True),
                                     on_message=self.on_message, on_connect=self.on_connect, log=log)
        self.client.subscribe(BASE + '/install')
        self.client.subscribe(BASE + '/check')
        # R2 Main announces R2 as a device (docs/home-assistant-voice.md): put the entities on it.
        self.client.subscribe(PREFIX + '/sensor/+/battery/config')

    def start(self):
        self.client.start()

    def on_connect(self):
        self.publish_discovery()
        self.client.publish(BASE + '/availability', 'online', retain=True)
        self.publish_state()

    def on_message(self, topic, payload):
        text = payload.decode('utf-8', 'replace')
        if topic == BASE + '/install':
            self.log('Home Assistant: install requested')
            self.app.install()
        elif topic == BASE + '/check':
            self.log('Home Assistant: check requested')
            self.app.check_now()
        elif topic.startswith(PREFIX + '/sensor/') and text:
            try:
                ids = (json.loads(text).get('device') or {}).get('identifiers') or []
            except ValueError:
                return
            r2 = [i for i in ids if isinstance(i, str) and i.startswith('r2d2_') and i != 'r2d2_update_mirror']
            if r2 and self.device.get('identifiers') != r2[:1]:
                self.device = {'identifiers': r2[:1]}
                self.r2_device_seen = True
                self.log('Home Assistant: adding the update entities to R2 (%s)' % r2[0])
                self.publish_discovery()

    def discovery(self):
        avail = {'availability_topic': BASE + '/availability'}
        origin = {'name': 'R2-D2 update mirror', 'url': 'https://github.com/Sk3yNet/Fanhome_R2-D2_Custom_Software'}

        def entity(name, uid, **kw):
            d = dict(name=name, unique_id=NODE + '_' + uid, device=self.device,
                     origin=origin, **avail)
            d.update(kw)
            return d

        return {
            PREFIX + '/update/%s/software/config' % NODE: entity(
                'Software', 'software', state_topic=BASE + '/software', command_topic=BASE + '/install',
                payload_install='install', device_class='firmware', entity_picture=None,
                title='R2-D2 custom software'),
            PREFIX + '/button/%s/check/config' % NODE: entity(
                'Check for updates', 'check_updates', command_topic=BASE + '/check', payload_press='check',
                entity_category='config', icon='mdi:update'),
            PREFIX + '/binary_sensor/%s/reachable/config' % NODE: entity(
                'Reachable', 'reachable', state_topic=BASE + '/reachable', device_class='connectivity',
                entity_category='diagnostic'),
            PREFIX + '/binary_sensor/%s/adb/config' % NODE: entity(
                'adb over Wi-Fi open', 'adb_open', state_topic=BASE + '/adb', device_class='problem',
                entity_category='diagnostic', icon='mdi:console-network'),
            PREFIX + '/binary_sensor/%s/health/config' % NODE: entity(
                'Problems', 'problems', state_topic=BASE + '/health', device_class='problem',
                json_attributes_topic=BASE + '/health/attributes', entity_category='diagnostic'),
        }

    def publish_discovery(self):
        for topic, cfg in self.discovery().items():
            self.client.publish(topic, json.dumps({k: v for k, v in cfg.items() if v is not None}), retain=True)

    def publish_state(self):
        s = self.app.state()
        sw = {'installed_version': s.get('installed'), 'latest_version': s.get('latest'),
              'in_progress': bool(s.get('in_progress')), 'release_url': s.get('release_url') or None,
              'release_summary': (s.get('release_summary') or '')[:250] or None}
        self.client.publish(BASE + '/software', json.dumps(sw), retain=True)
        onoff = lambda v: 'ON' if v else 'OFF'
        if s.get('reachable') is not None:
            self.client.publish(BASE + '/reachable', onoff(s['reachable']), retain=True)
        if s.get('adb_open') is not None:
            self.client.publish(BASE + '/adb', onoff(s['adb_open']), retain=True)
        self.client.publish(BASE + '/health', onoff(s.get('problems')), retain=True)
        self.client.publish(BASE + '/health/attributes', json.dumps({'problems': s.get('problems') or [],
                                                                     'notes': s.get('notes') or []}), retain=True)
