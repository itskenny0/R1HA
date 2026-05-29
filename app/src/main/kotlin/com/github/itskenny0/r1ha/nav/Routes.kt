package com.github.itskenny0.r1ha.nav

/** All top-level navigation destinations as stable string routes. */
object Routes {
    const val ONBOARDING = "onboarding"
    const val CARD_STACK = "card_stack"
    const val FAVORITES_PICKER = "favorites_picker"
    const val SETTINGS = "settings"

    /** Android-Settings-style subpages, each scoping the Settings screen to
     *  a single top-level group. Settings root opens at [SETTINGS]; tapping
     *  a group card navigates here. Each is a distinct back-stack entry so
     *  system-back returns to the root, not the previous app screen. */
    const val SETTINGS_CONNECTION = "settings/connection"
    const val SETTINGS_APPEARANCE = "settings/appearance"
    const val SETTINGS_BEHAVIOUR = "settings/behaviour"
    const val SETTINGS_INTEGRATIONS = "settings/integrations"
    const val SETTINGS_ADVANCED = "settings/advanced"
    const val SETTINGS_BROWSE = "settings/browse"
    /** Hardware-key bindings editor — drilled into from Settings → Behaviour. */
    const val SETTINGS_KEY_BINDINGS = "settings/key_bindings"
    /** Multi-device settings sync — opt-in toggle, interval, manual triggers, stats. */
    const val SETTINGS_SYNC = "settings/sync"
    /** IoT Camera Mode — opt-in surface that turns this device into an HA
     *  camera entity via MJPEG and/or MQTT auto-discovery. Per-device only. */
    const val SETTINGS_IOT_CAMERA = "settings/iot_camera"
    /** IoT Sensors Mode — sibling of [SETTINGS_IOT_CAMERA]. Publishes
     *  device hardware sensors + accepts commands via MQTT discovery. */
    const val SETTINGS_IOT_SENSORS = "settings/iot_sensors"
    /** MQTT broker config — host, port, auth, TLS. Shared by IoT Camera
     *  Mode (publishing camera frames + discovery) and the Dev menu's
     *  one-shot publish surface. Top-level rather than nested under
     *  Advanced because more than one user-facing feature consumes it. */
    const val SETTINGS_MQTT = "settings/mqtt"
    const val THEME_PICKER = "theme_picker"
    const val ABOUT = "about"
    const val DEV_MENU = "dev_menu"
    const val ASSIST = "assist"
    const val SCENES = "scenes"
    const val LOGBOOK = "logbook"
    const val TEMPLATE = "template"
    const val SERVICE_CALLER = "service_caller"
    const val NOTIFICATIONS = "notifications"
    const val CAMERAS = "cameras"
    const val WEATHER = "weather"
    const val PERSONS = "persons"
    const val CALENDARS = "calendars"
    const val LONG_LIVED_TOKEN = "long_lived_token"
    const val SYSTEM_HEALTH = "system_health"
    const val DASHBOARD = "dashboard"
    const val AREAS = "areas"
    const val LABELS = "labels"
    const val FLOORS = "floors"
    const val SERVICES = "services"
    const val SEARCH = "search"
    const val AUTOMATIONS = "automations"
    const val HELPERS = "helpers"
    const val ENERGY = "energy"
    const val DEVICES = "devices"
    const val ZONES = "zones"
    const val LOVELACE = "lovelace"
    const val DEVICE = "device"
    const val TODO = "todo"
    /** HA / Supervisor / add-on / integration update viewer + installer. */
    const val UPDATES = "updates"
    /** HA repairs / issues feed — surfaces server-side integration warnings + errors,
     *  same set HA's frontend shows under Settings > System > Repairs. */
    const val REPAIRS = "repairs"
    const val MEDIA_BROWSE = "media_browse"
    const val BACKUPS = "backups"
    /** Zigbee pairing surface — opens the network for joins via ZHA / Z2M / deCONZ
     *  and surfaces newly-discovered entities as they enrol. */
    const val ZHA_PAIRING = "zha_pairing"

    /** Voice satellite — push-to-talk surface that pipes mic audio at HA's
     *  assist pipeline (STT → conversation → TTS) and plays the response. */
    const val VOICE_SATELLITE = "voice_satellite"

    /** Native HA config_entries browser; per-row reload, no setup flow
     *  (HA's web UI owns dynamic setup schemas + OAuth handoffs). */
    const val INTEGRATIONS = "integrations"

    /** "Modified settings" subscreen — lists every registry entry whose
     *  current value differs from its default. Reached from a chip near
     *  the top of the main Settings screen. */
    const val MODIFIED_SETTINGS = "modified_settings"

    /** History drill-in route — carries the entity_id as a path
     *  segment. Use [historyRoute] from call sites so the encoding
     *  rule lives in one place. */
    const val HISTORY = "history/{entityId}"

    /** Build a concrete history-screen route for [entityId]. The
     *  entity_id stays unescaped because Compose Navigation parses
     *  StringType path segments as raw strings — `.` and `_` are
     *  allowed in route paths. */
    fun historyRoute(entityId: String): String = "history/$entityId"

    /** Full /api/error_log viewer with level chip filter, substring
     *  search, copy-to-clipboard, and auto-refresh. Replaces the
     *  WebView-only HA Settings → Logs panel. */
    const val LOGS = "logs"

    /** Read-only browser for HA's user registry (config/auth/list).
     *  Admin-only; surfaces a friendly "needs admin" state when the
     *  call returns auth_error. */
    const val USERS = "users"

    /** Tag registry editor — read every NFC / QR tag, rename or delete
     *  individual rows. Creation isn't included; tags self-register on
     *  first scan. */
    const val TAGS = "tags"

    /** Native browser for HA's installed automation + script blueprints,
     *  with a two-stage IMPORT FROM URL flow that previews via
     *  `blueprint/import` and commits via `blueprint/save`. */
    const val BLUEPRINTS = "blueprints"

    /** Long-term statistics chart. */
    const val STATISTICS = "statistics"

    /** Native dashboards list. Imports HA's Lovelace YAML and renders the
     *  views natively in Compose. Hidden on R1's small-screen tier; the
     *  entry row lives under Settings, Appearance. */
    const val DASHBOARDS = "dashboards"

    /**
     * Full-screen single-view renderer. Dashboard URL path is the first
     * path segment (`_default_` sentinel for the default dashboard so
     * the StringType nav arg never carries null); view path is the
     * second. Both segments are unescaped because Compose Navigation
     * StringType accepts `.` and `_` raw.
     */
    const val DASHBOARDS_VIEW = "dashboards/{dashboard}/{viewPath}"

    /** Build a concrete dashboards-view route. Null [dashboardUrlPath]
     *  is encoded as `_default_`. */
    fun dashboardsViewRoute(dashboardUrlPath: String?, viewPath: String): String =
        "dashboards/${dashboardUrlPath ?: "_default_"}/$viewPath"
}
