package com.github.itskenny0.r1ha.core.ha

enum class Domain(val prefix: String) {
    LIGHT("light"),
    FAN("fan"),
    COVER("cover"),
    MEDIA_PLAYER("media_player"),
    // ── on/off-only domains ─────────────────────────────────────────────────────────────
    // All four use the same `turn_on`/`turn_off` services and the same "on"/"off" state
    // strings, so they share most of the plumbing. Kept as separate enum values so the
    // card label, glyph, and accent can differ per domain (a smart-plug card shouldn't
    // read "AUTOMATION").
    SWITCH("switch"),
    INPUT_BOOLEAN("input_boolean"),
    AUTOMATION("automation"),
    /** Smart locks — uses `lock.lock` / `lock.unlock` services; state is "locked"/"unlocked". */
    LOCK("lock"),
    /** Humidifiers + dehumidifiers — scalar `target_humidity` (0-100) via `set_humidity`. */
    HUMIDIFIER("humidifier"),
    /**
     * Thermostats. State is the HVAC mode ("off"/"heat"/"cool"/"auto"/…) rather than
     * "on"/"off", so isOn computation has a domain-specific branch (see DefaultHaRepository).
     * Currently exposed as a switch-only card — wheel turns the entity on/off via
     * climate.turn_on / climate.turn_off (which restores the previous HVAC mode). Driving
     * `target_temperature` from the wheel would need min_temp/max_temp from attrs to scale
     * percent into the temperature range, which is a refactor beyond the time budget.
     */
    CLIMATE("climate"),
    // ── Action-only domains ─────────────────────────────────────────────────────────────
    // No persistent on/off state, no scalar; just a "fire" trigger. Rendered as ActionCard
    // instead of SwitchCard / scalar card. Wheel input is ignored on these — they're
    // tap-only. The "state" of these entities is mostly a last-fired timestamp in HA;
    // scripts add an "on" state while running, the others stay stateless.
    SCENE("scene"),
    SCRIPT("script"),
    BUTTON("button"),
    /**
     * HA helper buttons — identical service shape to [BUTTON] (`input_button.press`),
     * fire-and-forget. Common in YAML dashboards as one-tap shortcuts that automations
     * react to. Rendered as ActionCard.
     */
    INPUT_BUTTON("input_button"),
    /**
     * Read-only sensors — temperature, humidity, power, etc. State is the reading itself,
     * `unit_of_measurement` from attributes is the suffix. No wheel input, no tap action;
     * rendered by SensorCard as a big numeric readout.
     */
    SENSOR("sensor"),
    /**
     * Binary sensors — door open/closed, motion detected, leak alarm. State is "on"/"off"
     * (HA convention: "on" = the affordance is triggered, "off" = quiet). Same SensorCard
     * variant as `sensor` but rendered as a binary state word + device-class label rather
     * than a numeric reading. Read-only.
     */
    BINARY_SENSOR("binary_sensor"),
    /**
     * `number` entities — MQTT-common, exposes a settable numeric scalar with explicit
     * `min` / `max` / `step` attributes. Many MQTT-Discovery integrations land here
     * (volume knobs, temperature setpoints that don't fit climate, pump speeds, etc.).
     * Service: `number.set_value` with `{value: <float>}`.
     */
    NUMBER("number"),
    /** Same as [NUMBER] but lives in HA's helpers (`input_number.X`). */
    INPUT_NUMBER("input_number"),
    /**
     * Valve entities — similar shape to covers (open/close/position/stop) but separate
     * domain so HA can distinguish water valves from window covers. Services mirror
     * cover (`open_valve`, `close_valve`, `set_valve_position`, `stop_valve`).
     */
    VALVE("valve"),
    /**
     * Robot vacuums. State is one of cleaning / docked / returning / paused / idle /
     * error. Services: vacuum.start, vacuum.stop, vacuum.pause, vacuum.return_to_base.
     * Rendered as a switch card with the state word visible — tap toggles
     * start ↔ return-to-base which is the natural "send the robot home" / "send it
     * out" intent users have on a card.
     */
    VACUUM("vacuum"),
    /**
     * Water heaters — same scalar shape as climate: target_temperature within
     * min_temp..max_temp. Services: water_heater.set_temperature, water_heater.turn_on,
     * water_heater.turn_off. Reuses the climate dispatch path.
     */
    WATER_HEATER("water_heater"),
    /**
     * Robot lawn mowers — same control-state shape as vacuum (mowing / docked /
     * returning / paused / error). Services: lawn_mower.start_mowing, pause, dock.
     */
    LAWN_MOWER("lawn_mower"),
    /**
     * `select` entities — a settable enum from HA's `options` attribute (e.g. fan mode
     * controllers offering auto/manual, mode switchers offering eco/normal/turbo).
     * State is the currently-selected option string; service is `select.select_option`
     * with `{option: "<value>"}`. Rendered as a dedicated card variant where the wheel
     * cycles through options and tap opens a full-screen picker overlay.
     */
    SELECT("select"),
    /** Helper-domain twin of [SELECT] — `input_select.*` shares the same service shape. */
    INPUT_SELECT("input_select"),
    /**
     * HA `counter.*` helpers — increment / decrement / reset with a
     * configurable step. The Helpers screen has bespoke ± rendering;
     * the card stack does not render counters (no card archetype), so
     * the HelpersScreen.CARD_STACK_FRIENDLY_KINDS guard hides the ★
     * pin affordance for this kind. Declared here so EntityId
     * construction succeeds for counter.* entities and the Helpers
     * VM doesn't throw on first load.
     */
    COUNTER("counter"),
    /**
     * HA `timer.*` helpers — start / pause / cancel countdown timers.
     * Same story as [COUNTER]: bespoke rendering on the Helpers
     * screen; no card-stack archetype yet. Declared here so the
     * EntityId for any timer.* entity is constructible.
     */
    TIMER("timer"),
    /**
     * HA `input_text.*` helpers — free-form text values. Read-only on
     * the Helpers screen (text-editing is poor UX on a wheel-input
     * device); not card-stack-friendly. Declared so EntityId works.
     */
    INPUT_TEXT("input_text"),
    /**
     * HA `input_datetime.*` helpers — date / time values. Read-only
     * here too. Declared so EntityId construction succeeds for the
     * Helpers VM's domain loop.
     */
    INPUT_DATETIME("input_datetime"),
    /**
     * Software-update entities — HA Core, Supervisor, OS, add-ons, integration
     * firmware. State is `"on"` when an update is available, `"off"` when up to
     * date. Attributes (`installed_version`, `latest_version`,
     * `release_summary`, `release_url`, `in_progress`, `update_percentage`,
     * `auto_update`, `title`, `entity_picture`, `supported_features`) drive
     * the dedicated Updates screen. Services: `update.install` (with optional
     * `version` and `backup` params), `update.skip`, `update.clear_skipped`.
     * No card-stack archetype: updates aren't a card-deck concept and are
     * surfaced from a dedicated review screen instead.
     */
    UPDATE("update"),
    /**
     * Alarm control panels (Ring, Bosch, Alarmo, MQTT alarms). State is the
     * armed status: `disarmed`, `armed_away`, `armed_home`, `armed_night`,
     * `armed_vacation`, `armed_custom_bypass`, `pending`, `arming`,
     * `triggered`, `disarming`. Services: `alarm_arm_away`, `alarm_arm_home`,
     * `alarm_arm_night`, `alarm_arm_vacation`, `alarm_arm_custom_bypass`,
     * `alarm_disarm`, `alarm_trigger`. Every service takes an optional `code`
     * data field; integrations that set `code_arm_required: true` reject
     * arming without a code, integrations with `code_format != null` reject
     * disarming without one. Renders as a switch-style card whose body shows
     * the current armed state and surfaces a PIN keypad on every action chip
     * when a code is required.
     */
    ALARM_CONTROL_PANEL("alarm_control_panel"),
    /**
     * IR / RF blasters and activity remotes. Two flavours land here:
     *  - Activity remotes (Harmony Hub, ESPHome IR with activities) — expose
     *    `current_activity` + `activity_list`; the RemotePanel renders one chip
     *    per activity, tap fires `remote.turn_on { activity: "<name>" }`.
     *  - Learned-command blasters (Broadlink RM Mini, Xiaomi IR) — commands aren't
     *    exposed via state attributes (they live in HA's storage per device), so
     *    the panel surfaces a hint pointing the user at the per-card custom
     *    actions feature: add chips with service `remote.send_command` and
     *    data `{"command":"<learned name>"}`.
     */
    REMOTE("remote"),
    /**
     * Person entities — HA's `person.*` tracker that aggregates a user's device
     * trackers into a single presence state. State is a zone name: `"home"`,
     * `"not_home"`, or a custom zone label (e.g. `"Work"`). Read-only from the
     * card stack's perspective (presence isn't something the wheel sets), so it
     * renders as a SensorCard showing the current zone. `isOn` reads as "home".
     */
    PERSON("person"),
    /**
     * Weather entities — `weather.*` forecast providers. State is the current
     * condition string (`"sunny"`, `"cloudy"`, `"rainy"`, etc.); attributes carry
     * `temperature`, `humidity`, `wind_speed`, and on older integrations a
     * `forecast` array. Modern integrations drop the attribute and expose
     * forecasts only through the `weather.get_forecasts` response-only service
     * (see [DefaultHaRepository.getWeatherForecasts]). Read-only; rendered by the
     * dedicated Weather screen, and as a SensorCard in the card stack.
     */
    WEATHER("weather"),
    /**
     * Catch-all for any domain the app has no dedicated archetype for (device_tracker, zone,
     * calendar, sun, image, event, tts, conversation, group, and anything new HA ships). These
     * entities have no card-stack rendering and can't be pinned (the favourites picker and the
     * card stack only list the archetypes above), but they are still real entities the user
     * owns, so the Universal Search surface includes them as read-only "find it by name" results
     * via [DefaultHaRepository.listAllEntitiesForSearch]. [prefix] is the empty sentinel because
     * the real prefix is recoverable from the entity_id string itself; OTHER is never produced by
     * [fromPrefix] / [isSupportedPrefix], only by [fromPrefixOrOther].
     */
    OTHER(""),
    ;

    /** Action-only domains — UI renders them as fire-and-forget ActionCard tiles. */
    val isAction: Boolean get() =
        this == SCENE || this == SCRIPT || this == BUTTON || this == INPUT_BUTTON

    /** Read-only sensor domains — UI renders them as SensorCard. No wheel, no tap.
     *  Includes input_text / input_datetime since they're effectively read-only
     *  text values from the card stack's perspective (no editing UX on a wheel-
     *  driven device); the Helpers screen handles them with bespoke rendering. */
    val isSensor: Boolean get() =
        this == SENSOR || this == BINARY_SENSOR ||
            this == INPUT_TEXT || this == INPUT_DATETIME ||
            this == PERSON || this == WEATHER

    /** Settable-enum domains — UI renders them as SelectCard. Wheel cycles options;
     *  tap opens a full-screen picker. */
    val isSelect: Boolean get() = this == SELECT || this == INPUT_SELECT

    companion object {
        // OTHER is the catch-all sentinel and must never be reachable by prefix lookup: its
        // empty prefix would otherwise shadow a malformed "" prefix and, worse, make
        // isSupportedPrefix("") return true. Exclude it from the reverse map entirely.
        private val byPrefix = entries.filter { it != OTHER }.associateBy { it.prefix }
        fun fromPrefix(prefix: String): Domain =
            byPrefix[prefix] ?: throw IllegalArgumentException("unknown domain prefix: '$prefix'")
        fun isSupportedPrefix(prefix: String): Boolean = prefix in byPrefix

        /** Lenient lookup used by [EntityId.domain]: maps any unrecognised prefix to [OTHER]
         *  rather than throwing, so an entity from a domain the app has no archetype for is
         *  still a constructible [EntityState] (read-only, search-only). */
        fun fromPrefixOrOther(prefix: String): Domain = byPrefix[prefix] ?: OTHER
    }
}
