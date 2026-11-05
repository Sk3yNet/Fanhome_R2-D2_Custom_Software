# Plugins (R2 Main)

A plugin is an integration or behavior inside R2 Main that reacts to the robot's events and acts on the
robot only through a permission-checked context. Home Assistant over MQTT is the first one.

## Loading model: built into R2 Main
Plugins are Java classes compiled into R2 Main and listed in `Brain` (`new PluginManager(..., Arrays.asList(ha, new BatteryAlertPlugin()))`).
They ship and update with R2 Main, and are signed with it.

Separate plugin APKs found by an intent filter were the other option. They would get Android's per-app
sandbox, but they need an IPC layer (a bound service per plugin), a way to install them that the
signed-updates work (ROADMAP section 3) has to cover first, and on Android 4.4 the sandbox is only as good
as the unpatched kernel (section 4). The interface is kept small and JSON-shaped so it can be
put behind a bound service later: `PluginContext` would stay the one place permissions are checked.

## Writing one
Implement `Plugin` (`apps/main/src/dev/r2hack/main/Plugin.java`):

| Method | When |
|---|---|
| `name()`, `description()` | lower-case letters, digits and `-`; one line for `GET /plugins` |
| `permissions()` | what the plugin asks for (below). It never gets more |
| `enabledByDefault()` | whether it runs on a robot nobody has configured |
| `start(ctx, config)` | enabled (at startup or by the API), with its stored config |
| `configure(config)` | its config changed (`POST /plugin/config`) |
| `onEvent(type, data)` | an event, only with the `status` permission |
| `stop()` | disabled, or R2 Main stops: end your threads and connections |
| `status()` | one line for `GET /plugins` |

Events: `state` (the robot's state changed; `data` is the same object as `ctx.state()`), `running` (the
action or routine that runs changed: `{"name","last_routine"}`), `button` (a robot button: `{"value":1-6}`,
see [serial-protocol.md](serial-protocol.md)), `routines` (one was saved or deleted: `{"names":[...]}`).

Each running plugin has its own thread and event queue, so a slow plugin never holds up R2 Main or the
others. State events are merged: a plugin that falls behind gets the latest state once. If `start` throws,
or a plugin throws 5 times in a row, it is stopped and `GET /plugins` shows why; it starts again when it
is enabled again or R2 Main restarts. After `stop`, its context refuses every call.

The state object: `battery` (-1 unknown), `charging` (0/1/2), `lightsaber`, `arm`, `lcd_s`, `lcd_l`
(booleans), `projector` (0/1/2), `mode` (ready, sleeping, pairing, patrol, phone control), `action`,
`error`, `muted`, `voice`, `face`, `volume`, `name`, `uuid`, `version`, `watch` (watch-with-me's status).

`BatteryAlertPlugin.java` is a short worked example (off by default).
Plugins avoid Android classes so they can be tested on a plain JVM; see `apps/main/test/dev/r2hack/main/PluginTest.java`, run by `apps/test.sh`.

## Permissions
| Permission | `PluginContext` calls |
|---|---|
| `status` | `state()`, and every event |
| `actions` | `action(name)` for the built-in actions (dance, lightsaber, leia...), `projector(mode)`, `watchUpdate(json)` (a film's state for [watch-with-me](watch-with-me.md)) |
| `drive` | `drive(power, angle)`, `head(angle)`, `controller(cmd)` for `move`, `head-angle`, `head-shift`, `head-dir`, `mode`, `projector`, `arm`, `lightsaber`, `lcd` |
| `routines.run` | `routineNames()`, `runRoutine(name)` |
| `routines.edit` | `saveRoutine(json)`, `deleteRoutine(name)` |
| `sound` | `playSound(number)` |
| `lights` | `led(r, b, y, g)` |
| `settings` | `setVolume(percent)`, `setWatch(on)` (the watch-with-me switch), and the actions mute, unmute, voice-on/off, face-on/off |
| (none) | `stop()`, `log(msg)`, `saveConfig(json)` |

What no plugin can do, whatever it is granted, because `PluginContext` has no call for it: install or
update software, reboot or shut down, adb, Wi-Fi, pairing (`pair`), the phone app switch
(`phone-app-on/off`), API tokens and clients, and the controller's `shut-down`, `reset-wdt`, `debug` and
power commands. Plugin commands go the same way as everyone else's, so the agent's motion watchdog,
low-battery stop and emergency stop apply to them too.

These fit the agent API's client levels (security phase 2, `Permissions.java` in the agent): `status`
is part of **read**, all the others are part of **control**, and nothing a plugin can get is **admin**.
Changing what a plugin may do (`POST /plugin`, `POST /plugin/config`) is an owner's job, so those routes
belong at the **admin** level; `GET /plugins` is **read**.

## Managing plugins
| API | `tools\r2.ps1` | |
|---|---|---|
| `GET /plugins` | `plugins` | each plugin: enabled, running and its status, `asks_for`, `granted`, config (values of keys with password, token, secret or key in their name show as `(set)`), and the permission list |
| `POST /plugin?name=N&enabled=1\|0` | `plugin-on N` · `plugin-off N` | kept across restarts |
| `POST /plugin?name=N&grant=status,sound` | `plugin-grant N=status,sound` | what it may do: some or all of what it asks for (empty: nothing but stop and log). It restarts with the new set |
| `POST /plugin/config?name=N` (JSON body) | `plugin-config N=file.json` | merged into its config; a `null` value removes a key |

Settings live in R2 Main's `files/plugins.json`, so they survive R2 Main updates.

## Built-in plugins
**`home-assistant`** (on by default; asks for `status`, `actions`, `routines.run`, `settings`): Home
Assistant over MQTT discovery, see [architecture.md](architecture.md#home-assistant-over-mqtt-r2-main).
The broker is still set with `POST /mqtt`; disabling the plugin disconnects it, and `GET /mqtt` says so.
Taking permissions away removes what needs them: without `routines.run` there are no routine buttons,
without `settings` the volume and the mute, voice, face and watch-with-me switches are ignored, and without `status` it
doesn't start. It never asks for `drive` or `routines.edit`.

**`battery-alert`** (off by default; asks for `status`, `sound`): plays a sound once when the battery
falls below a threshold while R2 isn't charging. Config: `{"below": 25, "sound": 3}`.
