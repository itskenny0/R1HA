# R1HA

![R1HA: native Kotlin Home Assistant client](r1ha.png)

A native Kotlin/Compose Home Assistant client, born on the Rabbit R1 and equally at home on any Android 6.0+ phone, tablet, or wall-mounted kiosk.

The R1 was headed for the e-waste bin, which seemed like a waste of a perfectly good gadget: a bright little portrait panel and an actual physical scroll wheel, attached to a product nobody wanted anymore. So I flashed it with a real Android ROM and wrote it a Home Assistant client. It turns out a scroll wheel is a genuinely great way to dim a light. Each detent nudges the brightness, the slider overshoots and settles with a spring animation, and you never once fight a tiny touch slider in a WebView.

Where the official [Home Assistant Companion app](https://github.com/home-assistant/android) is fundamentally a WebView wrapped around HA's Lovelace frontend, R1HA renders everything natively in a Compose-first idiom, and falls back to a Lovelace WebView only for the long tail that doesn't fit (HACS cards, the automation editor, the configuration panel). The card stack and wheel idiom are tuned for the R1's small portrait display, but the layout adapts cleanly to handheld phones, wall-mounted tablets, and kiosk installs; touch replaces the wheel without the UI feeling like an afterthought.

## What it does

**The wheel is the interface.** Spin it to adjust any scalar HA entity: light brightness, fan speed, cover position, media volume. A spring-animated slider overshoots and settles on each turn, so dimming a light feels physical rather than fiddly. The app listens for both `DPAD_UP/DOWN` and `VOLUME_UP/DOWN` keycodes, so it works across R1 ROM variants, and on wheel-less devices the same controls respond to drag and tap.

**One entity, one screen.** Your favourites live in a card stack: one full-screen card per entity, swipe up and down to flip through them, swipe sideways (or flick the wheel) to switch between rearrangeable tab groups. Long-press the hamburger and a per-card CUSTOMIZE sheet, organised as nested NAME / GLYPH / POSITION / TAP / LIGHTING / LOCK submenus, lets you override the name, glyph, 9-way position, and tap action with a live in-place preview. Lights gain favourite-colour swatches and covers favourite-position chips, captured in one tap from the control surface.

**Your dashboards, rendered natively.** R1HA reads your HA `lovelace/config` and draws over two dozen card types in Compose: Entities, Glance, Tile, Button, Light, Gauge, Weather, Markdown, Heading, Sensor, the Picture variants, Area, History Graph, Alarm Panel, Map, Thermostat, Media Control, Humidifier, the stacks and grids, Conditional, and more. Conditional cards honour the full condition vocabulary and per-card `visibility:` gating, and tile cards render the modern feature rows across every controllable domain, from cover tilt and light colour temperature to climate modes, vacuum commands, and media transport. A drag-and-drop editor applies per-view overrides. Anything it cannot draw yet gets an honest UNSUPPORTED placeholder with a one-tap fallback into the real Lovelace frontend.

**A glanceable TODAY screen.** A time-of-day greeting, current weather, sun position, running timers, now playing with transport controls, who's home, the next calendar event, total power draw, a LIGHTS ON / CAMERAS / ALERTS row, a BATTERIES LOW card when any sensor drops below 20%, and a preview of HA's persistent alerts, all on one screen that auto-refreshes every 60 seconds and can be set as the launch screen for kiosk installs.

**Everything else is browsable, natively.** Quick Search spans every entity by name, id, area, or domain, with filter chips and tap-to-fire. Beyond it sit native browsers for cameras (list, grid, and a polling fullscreen view), weather with hourly and daily forecasts, who's home, calendars, the logbook with a live tail, persistent notifications, areas, and zones drawn on an abstract Canvas map. Any entity drills into a full-screen history chart with a window picker, press-and-hold scrubbing, and a numeric summary; numeric entities draw a line and the rest fall back to a coloured state timeline.

**Control surfaces for everything that acts.** Tap-fire launchers for scenes and scripts (with master OFF actions for all lights, media, or switches), an automations list with mode badges and one-tap RUN, every helper domain from steppers to datetime dialogs, to-do lists across shopping-list and CalDAV-style integrations, and a native alarm panel with an optional encrypted PIN keypad. A long-press BROWSE drawer, launcher app shortcuts, and up to four Quick Settings tiles put the major surfaces one gesture away.

**Talk to your house.** HA Assist takes typed or dictated prompts with multi-turn context threaded across calls, using the system speech recognizer so no microphone permission is required. A separate push-to-talk voice satellite pipes mic audio through HA's full assist pipeline (STT, conversation, TTS) over the existing WebSocket and plays the answer back.

**A native energy view.** Current draw, production, today's kWh, and the top consumers, with water and gas tiles when those sensors exist, a flow diagram, a per-consumer breakdown, and CSV export, all computed from your sensors directly. No WebView, no Lovelace bundle.

**Admin from the couch.** A Jinja2 template evaluator (with a LIVE re-render toggle), a service caller with a JSON payload editor, a services browser, and native browsers for devices, integrations, blueprints, users, and NFC/QR tags. Add the repairs feed, the update list with install progress, long-term statistics charts, backups, media-library browsing, a live log viewer, and a System Health panel, and most of HA's settings pages are runnable from the device in your hand.

**The device becomes a peripheral.** Opt-in extras turn the phone itself into part of the smart home: an NFC tag reader, an iBeacon advertiser for presence detection, a webhook receiver HA can POST to, a one-shot MQTT publisher, and a Zigbee pairing flow that works against ZHA, Zigbee2MQTT, or deCONZ. Tasker, MacroDroid, and Automate can fire HA service calls through a broadcast intent, and HA's persistent notifications can mirror into the Android shade.

**Comfortable to live with.** Sign in with OAuth or a pasted long-lived token, with everything encrypted at rest behind an AndroidKeystore-wrapped AES-256/GCM key. Navigation is gesture-first, three themes (including six colourful per-entity gradient palettes) switch live with an accent override, and a deep settings screen covers wheel step and acceleration, haptics, clock format, list density, text size, and a reduce-motion mode. Round it out with backup and restore to a single JSON file, a home-screen widget, background cache refresh, and a one-shot WHAT'S NEW panel after each update.

**Old hardware welcome.** The minimum is deliberately Android 6.0, so the phone gathering dust in your drawer can have a second life as a dedicated Home Assistant remote. Wide windows get real two-pane and multi-column layouts, not stretched phone screens.

## Devices and compatibility

You need three things:

- **Android 6.0 (Marshmallow) or newer** on any phone, tablet, or wall-mounted kiosk display.
- A reachable **Home Assistant** instance, on the local network or via a remote URL.
- For the primary target, a **Rabbit R1** running **LineageOS 21 GSI** (Android 14) or **CipherOS** (Android 16). On the LineageOS GSI, run `adb shell wm density 180` once for sane UI scaling.

The app is built and tested against modern Android (the R1 itself runs Android 14/16), but the minimum is deliberately low so an old phone gathering dust can have a second life as a dedicated Home Assistant remote. Older devices are not first-class citizens: the core experience (browsing entities, toggling, scenes, the assistant, search, dashboards) runs all the way down to Android 6.0, while a few extras that depend on newer OS features degrade gracefully or sit out.

| Feature | Works from | Below that |
| --- | --- | --- |
| Core app: entities, control, scenes, assist, search, dashboards | Android 6.0 | n/a |
| Quick Settings tiles | Android 7.0 | Not offered; the rest of the app is unaffected |
| Per-channel notification settings | Android 8.0 | Notifications still post; the OS just has no channels to tune |
| Rich haptics (predefined effects, amplitude control) | Android 8.0 | Falls back to a plain short vibration |
| Adaptive (masked) launcher icon | Android 8.0 | Shows a plain square icon |

Nothing in that table blocks the app from installing or running; it is only about which conveniences light up where. The oldest version I have actually tested on is Android 9 (Pie). Anything between the 6.0 floor and there should work but is untested; if you run the app on something older, reports are welcome.

## R1HAL: the slim build

R1HA also ships a deliberately reduced build called **R1HAL**, attached to every release as `r1ha-legacy-YYYY.MM.DD.HHmm.apk`. It keeps the card stack (the heart of the app) and the entity drill-ins reached from a card's more-info sheet (history, logbook, media browsing), and drops everything else: dashboards, energy, automations, scenes, cameras, the voice satellite, the IoT camera and sensor modes, widgets, Quick Settings tiles, and the rest of the management surface. The dropped screens are compiled out, not just hidden. The permissions those features needed (camera, microphone, NFC, Bluetooth, foreground services, write-settings, post-notifications) are stripped from the build too, so R1HAL requests little more than network access (plus install-packages, for its self-updater).

R1HAL is its own app, not a mode you toggle. It has a separate package id, so it installs and runs **alongside** a full R1HA rather than replacing it. It shows up as "R1HAL" in the app drawer with a yellow icon and a yellow accent so the two are never confused.

It runs on the same Android 6.0 floor as the full app. The current Jetpack Compose toolkit hard-requires Android 6.0, so that is genuinely the lowest the card stack can go; R1HAL is about a smaller, single-purpose app rather than a lower OS requirement.

**You may actually prefer R1HAL even on a modern phone.** If all you want is a fast, focused Home Assistant remote built around the card stack, without the dozens of extra screens and the broad permission set the full app carries, the slim build is the leaner, lower-footprint choice. Pick R1HA for the complete tour; pick R1HAL when less is more.

## Install

Grab the latest APK from the [Releases](../../releases) page: `r1ha-YYYY.MM.DD.HHmm.apk` for the full app, or `r1ha-legacy-YYYY.MM.DD.HHmm.apk` for the slim R1HAL build. Install it with adb:

```bash
adb install r1ha-YYYY.MM.DD.HHmm.apk
```

Or copy the APK to the device and open it with a file manager.

## Build from source

You need JDK 17+ and an Android SDK with `platforms;android-35` and `build-tools;35.0.0`.

```bash
git clone https://github.com/itskenny0/R1HA.git
cd R1HA
./gradlew :app:assembleGithubDebug
adb install app/build/outputs/apk/github/debug/app-github-debug.apk
```

There are three product flavors: `github` (includes the in-app self-updater), `fdroid` (relies on the F-Droid client for updates), and `legacy` (the slim "R1HAL" build, described above). Swap `Github` for `Fdroid` or `Legacy` in the task name to build another one.

The local build uses today's date as the version (`YYYYMMDD` for `versionCode`, `YYYY.MM.DD` for `versionName`); CI passes `APP_VERSION_CODE` / `APP_VERSION_NAME` from the release tag.

## Releasing

Releases are date-tagged. Push a tag in the form `r1ha-YYYYMMDD-HHmm` (UTC) so same-day reships get distinct version names:

```bash
git tag "r1ha-$(date -u +%Y%m%d-%H%M)"
git push origin "r1ha-$(date -u +%Y%m%d-%H%M)"
```

The release workflow builds the APKs, renames them to `r1ha-YYYY.MM.DD.HHmm.apk` and `r1ha-legacy-YYYY.MM.DD.HHmm.apk`, generates release notes from `git log` since the previous tag, and attaches them to a stable GitHub Release; no keystore management or repository secrets required. The legacy date-only `r1ha-YYYYMMDD` tag form is still accepted, with its time defaulting to `0000`.

## License

Released into the public domain via [The Unlicense](LICENSE). Take it, fork it, ship it, no strings.
