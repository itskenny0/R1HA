# R1HA

![R1HA: native Kotlin Home Assistant client](r1ha.png)

A native Kotlin/Compose Home Assistant client, born on the Rabbit R1 and equally at home on any Android 6.0+ phone, tablet, or wall-mounted kiosk.

The R1 was headed for the e-waste bin, which seemed like a waste of a perfectly good gadget: a bright little portrait panel and an actual physical scroll wheel, attached to a product nobody wanted anymore. So I flashed it with a real Android ROM and wrote it a Home Assistant client. It turns out a scroll wheel is a genuinely great way to dim a light. Each detent nudges the brightness, the slider overshoots and settles with a spring animation, and you never once fight a tiny touch slider in a WebView.

Where the official [Home Assistant Companion app](https://github.com/home-assistant/android) is fundamentally a WebView wrapped around HA's Lovelace frontend, R1HA renders everything natively in a Compose-first idiom, and falls back to a Lovelace WebView only for the long tail that doesn't fit (HACS cards, the automation editor, the configuration panel). The card stack and wheel idiom are tuned for the R1's small portrait display, but the layout adapts cleanly to handheld phones, wall-mounted tablets, and kiosk installs; touch replaces the wheel without the UI feeling like an afterthought.

## Highlights

**The wheel is the interface.** Spin it to adjust any scalar HA entity: light brightness, fan speed, cover position, media volume. The app listens for both `DPAD_UP/DOWN` and `VOLUME_UP/DOWN` keycodes, so it works across R1 ROM variants. On wheel-less devices the same controls respond to drag and tap.

**One entity, one screen.** Favourites live in a card stack: one full-screen card per entity, swipe up and down to flip through them, swipe sideways (or flick the wheel) to switch between rearrangeable tab groups. Long-press the hamburger and a per-card CUSTOMIZE sheet lets you override the name, glyph, position, tap action, and more, with a live preview.

**Your dashboards, rendered natively.** R1HA reads your HA `lovelace/config` and renders over two dozen card types in Compose, including conditional visibility and the modern tile-card feature rows. Anything it can't draw yet gets an honest UNSUPPORTED placeholder with a one-tap fallback into the real Lovelace frontend.

**A glanceable TODAY screen.** Greeting, weather, sun position, running timers, now playing, who's home, the next calendar event, total power draw, and any alerts, on one screen that can be set as the launch screen for kiosk installs.

**Talk to your house.** HA Assist with typed or dictated prompts and multi-turn context, plus a push-to-talk voice satellite that pipes mic audio through HA's assist pipeline (STT, conversation, TTS) and plays the answer back.

**A native energy view.** Current draw, production, today's kWh, the top consumers, a flow diagram, and CSV export, all computed from your sensors directly. No WebView, no Lovelace bundle.

**Admin from the couch.** A Jinja2 template evaluator, a service caller with JSON payload editor, browsers for devices, integrations, users, and tags, the repairs feed, the update list, backups, and a live log viewer. Most of HA's settings pages, without opening a laptop.

**The device becomes a peripheral.** Opt-in extras turn the phone itself into part of the smart home: an NFC tag reader, an iBeacon advertiser for presence detection, a webhook receiver HA can POST to, a one-shot MQTT publisher, and a Zigbee pairing flow that works against ZHA, Zigbee2MQTT, or deCONZ.

**Old hardware welcome.** The minimum is deliberately Android 6.0, so the phone gathering dust in your drawer can have a second life as a dedicated Home Assistant remote. Wide windows get real two-pane and multi-column layouts, not stretched phone screens.

## The full tour

The same ground as above, but exhaustively. Skim it; the app is searchable anyway.

### At a glance

The surfaces you check, not the ones you operate.

- **TODAY dashboard**: time-of-day greeting, current outdoor weather (condition glyph and temperature), sun position (above/below horizon, next rise/set), active HA timers with remaining time, currently-playing media with prev/play/next transport, who's home, the next calendar event, DRAW (total power consumption summed from every power-class sensor), a LIGHTS ON / CAMERAS / ALERTS metrics row, a BATTERIES LOW card surfaced when any battery sensor drops below 20%, and a preview of HA persistent alerts. Pull-to-refresh; auto-refreshes every 60 s. Reachable from Settings → Dashboard or Quick Actions, and can be made the launch screen via Settings → Behaviour → Start on Dashboard.
- **Quick Search**: substring search across every HA entity by name, entity_id, area, or domain ("climate" or "binary sensor" surfaces every entity of that kind), with ALL / CONTROLS / SENSORS / ACTIONS filter chips. Tap a result to fire it (scenes, scripts, buttons), toggle it (lights, switches, and the like), or get a detail toast (sensors). Long-press drills into that entity's `/history` view in HA's web UI. Settings → Quick Search.
- **Cameras**: live polling snapshots from every `camera.*` entity. LIST view shows the directory with a state chip; GRID view shows 2-column tiles polling every 8 s; tapping any camera opens a fullscreen overlay polling every 4 s.
- **Weather**: every `weather.*` entity with condition glyph, temperature, feels-like, and whichever secondary readings HA reports (humidity, wind with bearing and gust, pressure, visibility, UV index, dew point, cloud coverage). The forecast strip flips between HOURLY and DAILY when both exist, sourced from the modern `weather.get_forecasts` service with a fallback to the legacy `forecast` attribute so it stays populated on old installs too.
- **Who's home**: `person.*` and `device_tracker.*` in one directory, coloured by home/away state, with GPS-accuracy and source-type chips on device_trackers.
- **Calendars**: `calendar.*` entities with a NOW pill for events currently happening and an "IN 2H" / "IN 3D" hint for the next one up. Tap a row to see the events ahead (look-ahead window configurable, default 14 days) via HA's `/api/calendars/<id>` endpoint, grouped under day headers with an ALL-DAY pill, time range, location, and description per event. Calendars that expose a colour tint their row with it.
- **Recent Activity**: HA's logbook in reverse-chronological order, with 12 h / 24 h / 3 d windows and full-text search. Tap a row for that entity's history; long-press opens it in HA's web UI.
- **Notifications**: every `persistent_notification.*` entity with title, message, timestamp, and a DISMISS chip. Auto-refreshes every 30 s while open.
- **Areas**: HA's area registry with entity counts and expandable per-area entity lists, powered by a server-side Jinja template against `/api/template`.
- **Zones**: the `zone.*` registry with an abstract Canvas map up top (each zone a circle sized by its radius and positioned by lat/lon, occupied zones filled in accent) and a per-zone occupancy list showing which persons and device_trackers are in each zone right now. An OUTSIDE bucket collects the `not_home` people.
- **Energy**: DRAW (sum of every `device_class=power` sensor), PRODUCTION (heuristic sum of solar / pv / grid_export / production sensors), TODAY's kWh (sum of `device_class=energy` `total_increasing` sensors), and the top 5 current consumers ranked by W draw. WATER and GAS daily-total tiles appear when `device_class=water` / `device_class=gas` sensors exist. A flow diagram and a per-consumer breakdown bar show where the power is going, custom device names from HA's energy settings are honoured, and an EXPORT CSV action shares the current draw, today's totals, the consumer ranking, and the consumption history. Pull down to refresh on demand; auto-refreshes every 30 s otherwise.
- **History drill-in**: a full-screen view of any entity's recent state changes, with a 1 h / 6 h / 24 h / 7 d window picker, a 180-dp Compose Canvas chart with explicit min/max axis labels, press-and-hold scrubbing for the value under your finger, and a numeric summary (current / min / max / avg / sample count). Numeric entities draw a line; non-numeric ones (binary_sensor, person, climate mode, text sensors) fall back to a coloured state timeline with a swatch legend. Extra numeric series can be overlaid on a shared time axis. Reachable via the chart glyph on Search rows or by tapping any Recent Activity row.

### Control

The surfaces you act on.

- **Scroll wheel control**: spin to adjust any scalar HA entity (lights, fans, covers, media players), with a spring-animated slider that overshoots and settles on each turn.
- **Card stack with tabs**: one full-screen card per favourite entity; swipe up/down to flip between them, swipe left/right (or wheel-flick) to switch between rearrangeable tab groups.
- **Per-card customization**: long-press the card-stack hamburger to open a per-card CUSTOMIZE sheet organised as nested submenus (NAME / GLYPH / POSITION / TAP / LIGHTING / LOCK / RESET). Each section ships INHERIT and DEFAULT chips plus per-field overrides, with a live in-place preview, a 9-way position-pip picker, a per-card glyph override, and a tap-action override (toggle / fire / noop / open-detail). Lights gain favourite-colour swatches and covers/valves favourite-position chips: capture the current colour or position as a one-tap favourite on the entity's control surface.
- **Native dashboards**: reads your HA `lovelace/config` and renders every supported card type natively in Compose. Over two dozen card types: Entities, Glance, Tile, Button, Light, Gauge, Weather Forecast, Markdown, Heading, Sensor, Picture Glance, Picture Entity, Area, History Graph, Alarm Panel, Map, Thermostat, Media Control, Humidifier, Entity Filter, Statistic, Statistics Graph, Logbook, Clock, Shortcut, Distribution, Picture, Picture Elements, Horizontal Stack, Vertical Stack, Grid, and Conditional. Conditional cards support the full condition vocabulary (state, numeric_state, and, or, not, screen, user) and per-card `visibility:` gating, with a failed condition collapsing the card the way HA does. Tile cards render the modern feature rows across every controllable domain: covers (open/close, position, tilt, tilt-position), lights (brightness, colour temperature), fans (speed, direction, oscillation, preset modes), climate (HVAC, fan, preset, swing, swing-horizontal modes, target temperature), humidifiers (toggle, modes, target humidity), water heaters (operation modes), vacuums and lawn mowers (command rows), valves (open/close, position), locks (commands, open-door), alarm modes, counters, update install/skip, numeric inputs, select options, toggles, plus media-player playback / source / sound-mode / volume and weather temperature / precipitation forecast strips. Markdown cards honour tap, hold, and double-tap actions; heading cards carry an icon and a row of action badges; sections render their header and footer cards inline. A drag-and-drop dashboard editor applies per-view overrides, and an UNSUPPORTED placeholder surfaces anything not rendered yet with a one-tap fallback into the Lovelace WebView. Settings → Dashboards.
- **HA Assist**: type or dictate a prompt and HA's conversation engine handles it, with multi-turn context threaded across calls. The mic button uses the system speech recognizer, so no `RECORD_AUDIO` permission is needed.
- **Scenes & Scripts launcher**: tap-fire access to every `scene.*` / `script.*`, with substring search, kind filter chips, pull-to-refresh, and long-press for the `entity_id` and service name.
- **Automations**: every `automation.*` entity with an enabled-state chip, mode badge (single / parallel / queued / restart), running-instance count, and relative `last_triggered` timestamp. Tap to toggle; the right-edge RUN fires `automation.trigger` with `skip_condition: true`; the star pins it to the card stack; a RELOAD chip in the top bar fires `automation.reload`.
- **Helpers**: every HA helper domain (`input_boolean` toggles, `input_number` steppers, `counter`, `input_select` cycle-through-options, `input_text` inline editor with character-count clamp and password masking, `input_datetime` date/time dialog, `input_button` press, `timer` start/pause/cancel), with bucket-chip filters by kind and star-pinning to the card stack.
- **To-do lists**: every `todo.*` integration (shopping list, Local To-do, Google Tasks, CalDAV). A list-picker chip row switches between lists; the body splits into ACTIVE and COMPLETED sections matching the Lovelace card's unchecked/checked grouping, each row with a checkbox toggle, inline rename, and remove, plus a due date ("TODAY" / "OVERDUE") and description when the provider supplies them, and an add-item field at the bottom. REST-backed via `todo.get_items` (HA 2024.1+).
- **Master OFF actions**: one-tap mass off for all lights, all media, or all switches from the Scenes & Scripts screen; HA's `entity_id: "all"` trick under the hood.
- **Alarm control panel**: a native `alarm_control_panel.*` card with ARM HOME / ARM AWAY / ARM NIGHT / DISARM actions and an optional per-entity PIN keypad that gates every state change. The PIN is stored encrypted at rest alongside the OAuth tokens.
- **Quick Actions drawer**: long-press the chrome hamburger and it doubles as the navigation drawer, with a 2×4 BROWSE grid (Today · Assist · Search · Scenes · Automations · Energy · Alerts) putting every major surface one long-press and one tap away.
- **App shortcuts**: long-press the launcher icon for Search · Assist · Today · Automations.
- **Quick Settings tiles**: bind up to four HA entities to Android's notification-shade panel; each tile toggles its entity from anywhere without opening the app. Settings → Behaviour → Quick Settings tile, slots A through D.

### Admin and diagnostics

Most of what HA's Settings pages do, runnable from the device in your hand.

- **Templates evaluator**: POST a Jinja2 template to HA's `/api/template` and render it against live state, with example chips (Sun elevation, On lights count, Unavailable, Areas) for one-tap discovery, a RECENT history of past renders, and COPY to clipboard.
- **Service Caller**: fire any HA service (`automation.reload`, `homeassistant.check_config`, `persistent_notification.create`, and so on) with a JSON data payload editor, PASTE chip, RECENT history, and a result panel with copy-to-clipboard.
- **Services Browser**: a discoverable directory of every service HA exposes via `/api/services`, grouped by domain, with substring search and tap-to-copy into the Service Caller.
- **Updates**: every HA `update.*` entity (HA Core, Supervisor, OS, add-ons, integration firmware), sorted in-progress first, then available, then up-to-date. Each entry shows the installed→latest version diff, a release-summary peek with a link to the full notes, an AUTO badge for `auto_update` entities, one-tap INSTALL (with an optional "Back up first" toggle when the entity reports backup support), SKIP with clear-skipped, and a live install progress bar that goes determinate when HA reports a percentage.
- **Repairs**: HA's repairs/issues feed, the same set the HA frontend shows under Settings → System → Repairs, with severity-coloured rows and IGNORE / RESTORE buttons firing `repairs/ignore` server-side. Pulled live over the WebSocket.
- **Devices**: a native browser for HA's device registry with substring search across name, manufacturer, model, and area, expandable rows with full device metadata and the entity list each device contributes, and inline rename.
- **Integrations**: a native browser for HA's `config_entries` (Z-Wave, Zigbee, every cloud integration, every YAML-loaded platform), with a per-entry RELOAD chip firing `config_entries/reload`, error reasons surfaced when HA flags them, and a domain filter.
- **Blueprints**: list every imported blueprint (automation and script), preview the YAML, and IMPORT one by URL through HA's `blueprint/import` WS command.
- **Logs**: a full HA log viewer with level filter (DEBUG / INFO / WARNING / ERROR / CRITICAL). Tail-aware: shows the last N bytes the server returned, with a COPY chip for the currently-filtered text. Auto-refreshes every 10 s; pull down for on-demand.
- **Users**: a read-only browser for HA's user registry showing display name, owner flag, active flag, system-generated flag, and each user's auth providers.
- **Tags**: a native NFC and QR tag registry editor. List every tag HA knows, rename inline, delete, and see when each was last scanned.
- **Statistics**: a native long-term recorder chart. Pick any statistic-tracked entity, choose a period (5m / hour / day / week / month / year), and get a Compose Canvas chart with explicit min/max axes.
- **Backups**: every backup HA's `backup/info` endpoint knows about (HA Core 2024.4+) with name, timestamp, size, and protected flag; CREATE BACKUP NOW fires `backup.create` and refreshes once HA writes the new backup.
- **Media Browse**: navigate any media_player's library via `media_player/browse_media`. Type the entity_id, drill into folders, tap to play on the bound player.
- **System Health**: HA's `/api/config` (version, location, timezone, components, internal/external URLs), a NETWORK SECURITY panel showing the current TLS pinning and mTLS state, an inline PING chip measuring round-trip latency to `/api/config`, and the last ~32 KB of `/api/error_log` with a COPY chip for bug reports.
- **Lovelace WebView**: the in-app fallback to HA's own frontend for what isn't rendered natively (HACS custom cards, the automation visual editor, the full configuration panel). Token handoff happens via injected `localStorage.hassTokens`, so there is no second OAuth round-trip; system back navigates the WebView's history first and only falls through to popBackStack when the history is empty.

### Radios, hooks, and live wires

This is where the device stops being just a remote.

- **External automation intent**: Tasker, MacroDroid, and Automate can fire HA service calls through the app by broadcasting `com.github.itskenny0.r1ha.action.HA_SERVICE_CALL` with `ha_domain` / `ha_service` / `ha_entity_id` / `ha_data_json` extras. Opt-in via the Dev menu so the surface stays closed on fresh installs.
- **HA notification mirror**: opt-in posting of HA persistent_notifications into the Android notification shade, with a DISMISS action that fires `persistent_notification.dismiss` server-side.
- **Live template subscriptions**: a LIVE toggle on the Templates screen subscribes to HA's `render_template` WS command; every state change that affects the template re-renders in place.
- **Live activity tail**: a TAIL toggle on Recent Activity subscribes to HA's `logbook_entry` event stream. New entries prepend in real time while the REST window query backs the initial fill.
- **NFC tag scanning**: an opt-in foreground reader. Tap a tag against the device while R1HA is open and it fires HA's `tag_scanned` event with the tag UID as `tag_id`.
- **iBeacon advertiser**: an opt-in BLE peripheral broadcasting as an iBeacon (configurable UUID, major, minor). HA's iBeacon integration picks the device up as a device_tracker for presence and proximity automations.
- **Zigbee pairing**: opens the network for joins on whichever Zigbee backend HA is using (ZHA, Zigbee2MQTT, or deCONZ), surfaces newly-discovered entities as they enrol, and lets you rename and assign an area from a single sheet without leaving the app.
- **Webhook receiver**: an opt-in foreground TCP listener. HA's webhook automations can POST at `http://<device-ip>:<port>/webhook/<id>` and the body shows up as an expandable toast; handy for triggering on-device feedback from a server-side rule.
- **MQTT publish**: a one-shot client that connects to any broker, fires a single publish (configurable topic, payload, retain flag, TLS, auth), and disconnects. Implemented by hand so the APK doesn't pick up a daemon-style dependency for what is really a fire-and-forget message.
- **Voice satellite**: a push-to-talk surface that pipes mic audio at HA's assist pipeline (STT, conversation, TTS) over the existing WebSocket and plays the response. No wake-word yet; that needs an on-device model and is a separate cycle's work.

### Everyday comfort

The small things that decide whether you actually keep using an app.

- **Sign-in your way**: OAuth via an in-app WebView after entering your HA URL once, or a pasted long-lived access token for kiosk-style R1s. Either way, tokens are encrypted at rest with an AndroidKeystore-wrapped AES-256/GCM key.
- **Gesture-first navigation**: swipe left for Settings, right for the Favourites picker, tap the value area to toggle on/off; small chevron-back buttons on every sub-screen plus full system-back support.
- **Three themes**: Pragmatic Hybrid (the default), Minimal Dark, and Colourful Cards (six per-entity gradient palettes with legibility scrims), switchable live in Settings with a side-by-side preview and an accent-colour override.
- **Backup & restore**: export and import your favourites, tabs, and settings as a single JSON file from Settings.
- **A home-screen widget**: a single launcher tile; tap to open the app from anywhere on your launcher.
- **Background entity-cache refresh**: an opt-in JobService warms the entity cache every ~15 min while the app is closed, so Quick Tile state and cold-start paint stay fresh.
- **What's new after updates**: a one-shot WHAT'S NEW panel summarises each release the first time you launch it, reopenable any time from Settings → About. Fresh installs skip it, and a toggle in About (or the panel's own corner menu) turns it off for good.
- **Fully configurable**: wheel step (1/2/5/10%) and acceleration, haptics, keep-screen-on, display mode, on/off pill, area labels, position dots, plus appearance controls for global text size, 12/24-hour clock, list density, relative vs absolute timestamps, and a reduce-motion mode that turns transitions into instant cuts.
- **Built for the R1, scales beyond it**: designed around the R1's small portrait display and physical scroll wheel (handling both `DPAD_UP/DOWN` and `VOLUME_UP/DOWN` keycodes across ROM variants), while wide windows get real layouts instead of stretched phone screens: Devices, Areas, and Cameras browse two-pane with the list beside the detail, Scenes and Helpers flow into multiple columns, charts grow with the window, and History composes its chart beside the numeric summary.

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

R1HA also ships a deliberately reduced build called **R1HAL**. It keeps the card stack (the heart of the app) and the entity drill-ins reached from a card's more-info sheet (history, logbook, media browsing), and drops everything else: dashboards, energy, automations, scenes, cameras, the voice satellite, the IoT camera and sensor modes, widgets, Quick Settings tiles, and the rest of the management surface. The dropped screens are compiled out, not just hidden. The permissions those features needed (camera, microphone, NFC, Bluetooth, foreground services, write-settings, post-notifications) are stripped from the build too, so R1HAL requests little more than network access (plus install-packages, for its self-updater).

R1HAL is its own app, not a mode you toggle. It has a separate package id, so it installs and runs **alongside** a full R1HA rather than replacing it. It shows up as "R1HAL" in the app drawer with a yellow icon and a yellow accent so the two are never confused.

It runs on the same Android 6.0 floor as the full app. The current Jetpack Compose toolkit hard-requires Android 6.0, so that is genuinely the lowest the card stack can go; R1HAL is about a smaller, single-purpose app rather than a lower OS requirement.

**You may actually prefer R1HAL even on a modern phone.** If all you want is a fast, focused Home Assistant remote built around the card stack, without the dozens of extra screens and the broad permission set the full app carries, the slim build is the leaner, lower-footprint choice. Pick R1HA for the complete tour; pick R1HAL when less is more.

## Install

Download the latest `r1ha-YYYY.MM.DD.HHmm.apk` from the [Releases](../../releases) page and install it:

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

There are three product flavors: `github` (includes the in-app self-updater), `fdroid` (relies on the F-Droid client for updates), and `legacy` (the slim "R1HAL" build, see below). Swap `Github` for `Fdroid` or `Legacy` in the task name to build another one.

The local build uses today's date as the version (`YYYYMMDD` for `versionCode`, `YYYY.MM.DD` for `versionName`); CI passes `APP_VERSION_CODE` / `APP_VERSION_NAME` from the release tag.

## Releasing

Releases are date-tagged. Push a tag in the form `r1ha-YYYYMMDD-HHmm` (UTC) so same-day reships get distinct version names:

```bash
git tag "r1ha-$(date -u +%Y%m%d-%H%M)"
git push origin "r1ha-$(date -u +%Y%m%d-%H%M)"
```

The release workflow builds the APK, renames it to `r1ha-YYYY.MM.DD.HHmm.apk`, generates release notes from `git log` since the previous tag, and attaches the APK to a stable GitHub Release; no keystore management or repository secrets required. The legacy date-only `r1ha-YYYYMMDD` tag form is still accepted, with its time defaulting to `0000`.

## License

Released into the public domain via [The Unlicense](LICENSE). Take it, fork it, ship it, no strings.
