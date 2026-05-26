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
}
