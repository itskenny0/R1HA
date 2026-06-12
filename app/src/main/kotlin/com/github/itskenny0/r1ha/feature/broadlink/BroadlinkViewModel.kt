package com.github.itskenny0.r1ha.feature.broadlink

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.ServiceCall
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Drives the Broadlink IR/RF console: remote discovery, the HA-resident
 * command catalog (browse / fire / rename / delete), the guided learn
 * flow, registering codes learned outside the app, the Broadlink-scoped
 * automations pane, and pin-to-deck exports.
 *
 * THE CATALOG LIVES IN HA, not in app storage: each learned command is one
 * automation tagged with the R1HA|Broadlink description marker
 * ([BroadlinkMarker]). Reading = list automation entities, fetch their
 * config bodies, keep the marked ones ([BroadlinkCatalog.parseEntry]);
 * results are cached in this ViewModel for snappy UI but never persisted,
 * so a reinstall (or a second device) sees the same catalog.
 *
 * FIRE PATH POLICY: a catalog command normally fires via
 * `automation.trigger` on its automation; the automation is the single
 * source of execution, so an HA-side edit to its action (different
 * repeats, an added delay) is honored everywhere, including pinned deck
 * cards. The one exception is a fire-time repeats override (the ×N
 * stepper): the automation body deliberately stores no num_repeats, so
 * ×N fires `remote.send_command` directly from the parsed marker
 * metadata. Same rule for pinned cards: ×1 pins an automation.trigger
 * card, ×N pins a send_command card carrying num_repeats.
 */
class BroadlinkViewModel(
    private val haRepository: HaRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    data class RemoteOption(
        val entityId: String,
        val name: String,
        val available: Boolean,
    )

    /** Learn-flow state machine. FORM collects the slots; CAPTURING holds
     *  while HA waits for the physical button press; CAPTURED offers
     *  TEST FIRE + SAVE; FAILED offers retry with the error visible. */
    enum class LearnPhase { FORM, CAPTURING, CAPTURED, FAILED }

    data class LearnState(
        val phase: LearnPhase = LearnPhase.FORM,
        val remoteEntityId: String = "",
        val deviceName: String = "",
        val commandName: String = "",
        val type: String = "ir",
        val alternative: Boolean = false,
        val error: String? = null,
        /** Wall-clock start of the capture phase; drives the elapsed
         *  readout on the capture screen. */
        val startedAtMillis: Long = 0L,
        val saved: Boolean = false,
    )

    data class AutomationRow(
        val entityId: String,
        val name: String,
        val enabled: Boolean,
        val available: Boolean,
        /** HA config-store id (`attributes.id`). Null = YAML-managed:
         *  no config body is fetchable and the Broadlink filter falls
         *  back to the name heuristic for this row. */
        val configId: String?,
        /** ISO instant of the automation's last run, from the
         *  `last_triggered` attribute; seeds the catalog tiles' "fired
         *  ago" labels. */
        val lastTriggered: String? = null,
        /** Raw config JSON once fetched; null until loaded or when
         *  unavailable. */
        val configBody: String? = null,
    )

    data class UiState(
        val loadingRemotes: Boolean = true,
        val remotes: List<RemoteOption> = emptyList(),
        val remotesError: String? = null,
        val selectedRemote: String = "",
        /** Fire keys with a send in flight; drives the per-tile firing
         *  indicator and double-tap suppression. */
        val firing: Set<String> = emptySet(),
        val learn: LearnState = LearnState(),
        val automationsLoading: Boolean = false,
        val automations: List<AutomationRow> = emptyList(),
        val automationsError: String? = null,
        /** True once config bodies were fetched, i.e. the BROADLINK filter
         *  is body-based rather than purely name-heuristic. */
        val configsFetched: Boolean = false,
        val creatingAutomation: Boolean = false,
        /** The HA-resident command catalog, derived from [automations]
         *  once bodies are in. Cached across reload failures so an
         *  offline blip never blanks a populated screen. */
        val catalog: List<BroadlinkCatalog.Entry> = emptyList(),
        /** True after at least one full successful catalog read; gates
         *  the empty-state copy so "nothing catalogued" is only claimed
         *  once HA has actually answered. */
        val catalogLoaded: Boolean = false,
        /** A catalog write (save / rename / delete) in flight. */
        val savingCommand: Boolean = false,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    private var learnJob: Job? = null

    companion object {
        fun firingKey(deviceName: String, commandName: String): String =
            "$deviceName $commandName"

        fun factory(haRepository: HaRepository, settings: SettingsRepository) =
            viewModelFactory {
                initializer { BroadlinkViewModel(haRepository, settings) }
            }
    }

    // ── Remotes ─────────────────────────────────────────────────────────

    fun refreshRemotes() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loadingRemotes = true, remotesError = null)
            haRepository.listRawEntitiesByDomain("remote").fold(
                onSuccess = { rows ->
                    val remotes = rows.map { row ->
                        RemoteOption(
                            entityId = row.entityId,
                            name = row.friendlyName,
                            available = row.state.lowercase() !in setOf("unavailable", "unknown"),
                        )
                    }.sortedBy { it.name.lowercase() }
                    val selected = _ui.value.selectedRemote
                        .takeIf { id -> remotes.any { it.entityId == id } }
                        ?: remotes.firstOrNull()?.entityId.orEmpty()
                    _ui.value = _ui.value.copy(
                        loadingRemotes = false,
                        remotes = remotes,
                        selectedRemote = selected,
                    )
                },
                onFailure = { t ->
                    R1Log.w("Broadlink", "remote list failed: ${t.message}")
                    _ui.value = _ui.value.copy(loadingRemotes = false, remotesError = t.message)
                },
            )
        }
    }

    fun selectRemote(entityId: String) {
        _ui.value = _ui.value.copy(selectedRemote = entityId)
    }

    // ── Fire ────────────────────────────────────────────────────────────

    /** Fire a catalog entry. Repeats == 1 triggers the automation (the
     *  single source of execution; see the class KDoc); repeats > 1 is a
     *  fire-time option the automation body doesn't carry, so it sends
     *  remote.send_command directly from the marker metadata. Read-only
     *  entries always trigger (no metadata to send directly). */
    fun fireEntry(entry: BroadlinkCatalog.Entry, repeats: Int = 1) {
        val key = entry.automationId
        if (key in _ui.value.firing) return
        _ui.value = _ui.value.copy(firing = _ui.value.firing + key)
        viewModelScope.launch {
            val meta = entry.meta
            val call = if (repeats > 1 && meta != null) {
                ServiceCall(
                    target = EntityId(meta.remote),
                    service = "send_command",
                    data = buildJsonObject {
                        put("device", JsonPrimitive(meta.device))
                        put("command", JsonPrimitive(meta.command))
                        put("num_repeats", JsonPrimitive(repeats))
                    },
                )
            } else {
                ServiceCall(
                    target = EntityId(entry.entityId),
                    service = "trigger",
                    data = buildJsonObject { put("skip_condition", JsonPrimitive(true)) },
                )
            }
            haRepository.call(call).fold(
                onSuccess = {
                    R1Log.i("Broadlink", "fired ${entry.alias} x$repeats")
                    // In-memory stamp only; the durable record is the
                    // automation's own last_triggered, re-read on refresh.
                    val firedAt = java.time.Instant.now().toString()
                    _ui.value = _ui.value.copy(
                        catalog = _ui.value.catalog.map {
                            if (it.automationId == entry.automationId) {
                                it.copy(lastTriggered = firedAt)
                            } else it
                        },
                    )
                },
                onFailure = { t ->
                    // haRepository.call already toasts the failure detail.
                    R1Log.w("Broadlink", "fire ${entry.alias} failed: ${t.message}")
                },
            )
            _ui.value = _ui.value.copy(firing = _ui.value.firing - key)
        }
    }

    /** Direct remote.send_command for commands that have no catalog
     *  automation (yet): the learn flow's TEST FIRE and the register
     *  form's TEST button. */
    fun testFire(remoteEntityId: String, deviceName: String, commandName: String) {
        val key = firingKey(deviceName, commandName)
        if (key in _ui.value.firing) return
        _ui.value = _ui.value.copy(firing = _ui.value.firing + key)
        viewModelScope.launch {
            haRepository.call(
                ServiceCall(
                    target = EntityId(remoteEntityId),
                    service = "send_command",
                    data = buildJsonObject {
                        put("device", JsonPrimitive(deviceName))
                        put("command", JsonPrimitive(commandName))
                    },
                ),
            ).fold(
                onSuccess = { R1Log.i("Broadlink", "test-fired $deviceName/$commandName") },
                onFailure = { t ->
                    R1Log.w("Broadlink", "test fire $deviceName/$commandName failed: ${t.message}")
                },
            )
            _ui.value = _ui.value.copy(firing = _ui.value.firing - key)
        }
    }

    // ── Catalog writes (all land in HA) ─────────────────────────────────

    /** Create or replace the tagged automation for [meta]. The id is
     *  deterministic, so re-learning a command overwrites its own record;
     *  in that case the existing alias (a user rename) is preserved. */
    private suspend fun persistCommandAutomation(meta: BroadlinkMarker.CommandMeta): Result<Unit> {
        val id = BroadlinkMarker.automationIdFor(meta.remote, meta.device, meta.command)
        val alias = _ui.value.catalog.firstOrNull { it.automationId == id }?.alias
            ?: BroadlinkMarker.defaultAlias(meta)
        val config = BroadlinkCards.commandAutomationConfig(alias = alias, meta = meta)
        return haRepository.saveAutomationConfig(automationId = id, config = config)
            .onSuccess {
                // HA reloads automations on config save; small grace so the
                // fresh entity is listed when we re-pull the catalog.
                kotlinx.coroutines.delay(600L)
                loadAutomations()
            }
    }

    /** Rename = read-modify-write of the automation config body, changing
     *  only the alias. The full body round-trips so HA-side edits to the
     *  action (extra delays, repeats) survive an in-app rename. */
    fun renameEntry(entry: BroadlinkCatalog.Entry, newAlias: String) {
        val meta = entry.meta ?: return // read-only rows are never renamed
        val alias = newAlias.trim().ifBlank { BroadlinkMarker.defaultAlias(meta) }
        if (_ui.value.savingCommand) return
        _ui.value = _ui.value.copy(savingCommand = true)
        viewModelScope.launch {
            haRepository.fetchAutomationConfig(entry.automationId).mapCatching { body ->
                val obj = kotlinx.serialization.json.Json.parseToJsonElement(body) as? JsonObject
                    ?: error("Unexpected automation config shape")
                JsonObject(obj.toMutableMap().apply { put("alias", JsonPrimitive(alias)) })
            }.fold(
                onSuccess = { updated ->
                    haRepository.saveAutomationConfig(entry.automationId, updated).fold(
                        onSuccess = {
                            _ui.value = _ui.value.copy(
                                catalog = _ui.value.catalog.map {
                                    if (it.automationId == entry.automationId) {
                                        it.copy(alias = alias)
                                    } else it
                                },
                            )
                            Toaster.show("Renamed to '$alias'")
                        },
                        onFailure = { t ->
                            Toaster.error("Rename failed: ${t.message ?: "unknown"}")
                        },
                    )
                },
                onFailure = { t ->
                    Toaster.error("Rename failed: ${t.message ?: "unknown"}")
                },
            )
            _ui.value = _ui.value.copy(savingCommand = false)
        }
    }

    /** Delete = remove HA's stored code (remote.delete_command) AND the
     *  tagged automation. The automation goes second: if the code delete
     *  fails (already gone server-side) the catalog record is still
     *  removed so no ghost row survives, and the toast says which half
     *  happened. Read-only entries never reach here (UI gates them). */
    fun deleteEntry(entry: BroadlinkCatalog.Entry) {
        viewModelScope.launch {
            val meta = entry.meta
            val codeResult = if (meta != null) {
                haRepository.callRawService(
                    domain = "remote",
                    service = "delete_command",
                    data = buildJsonObject {
                        put("entity_id", JsonPrimitive(meta.remote))
                        put("device", JsonPrimitive(meta.device))
                        put("command", JsonPrimitive(meta.command))
                    },
                )
            } else Result.success("")
            haRepository.deleteAutomationConfig(entry.automationId).fold(
                onSuccess = {
                    _ui.value = _ui.value.copy(
                        catalog = _ui.value.catalog.filterNot {
                            it.automationId == entry.automationId
                        },
                        automations = _ui.value.automations.filterNot {
                            it.entityId == entry.entityId
                        },
                    )
                    codeResult.fold(
                        onSuccess = { Toaster.show("Deleted '${entry.alias}' from HA") },
                        onFailure = { t ->
                            R1Log.w("Broadlink", "HA code delete failed: ${t.message}")
                            Toaster.error(
                                "Catalog entry removed; HA code delete failed: " +
                                    (t.message ?: "unknown"),
                            )
                        },
                    )
                },
                onFailure = { t ->
                    R1Log.w("Broadlink", "automation delete ${entry.automationId} failed: ${t.message}")
                    Toaster.error("Couldn't delete the catalog automation: ${t.message ?: "unknown"}")
                },
            )
        }
    }

    /** REGISTER EXISTING: catalog a code learned outside the app by
     *  creating its tagged automation. Names must match what HA has
     *  stored; the TEST button on the form fires send_command so the user
     *  can verify before saving. */
    fun registerExisting(
        remoteEntityId: String,
        deviceName: String,
        commandName: String,
        type: String,
        notes: String,
    ) {
        if (_ui.value.savingCommand) return
        _ui.value = _ui.value.copy(savingCommand = true)
        viewModelScope.launch {
            persistCommandAutomation(
                BroadlinkMarker.CommandMeta(
                    remote = remoteEntityId,
                    device = deviceName.trim(),
                    command = commandName.trim(),
                    type = type,
                    notes = notes.trim(),
                ),
            ).fold(
                onSuccess = { Toaster.show("Registered '${commandName.trim()}' in HA") },
                onFailure = { t ->
                    Toaster.error("Register failed: ${t.message ?: "unknown"}")
                },
            )
            _ui.value = _ui.value.copy(savingCommand = false)
        }
    }

    // ── Learn flow ──────────────────────────────────────────────────────

    fun updateLearnForm(transform: (LearnState) -> LearnState) {
        _ui.value = _ui.value.copy(learn = transform(_ui.value.learn))
    }

    fun resetLearn(prefillRemote: String = "", prefillDevice: String = "") {
        learnJob?.cancel()
        learnJob = null
        _ui.value = _ui.value.copy(
            learn = LearnState(
                remoteEntityId = prefillRemote.ifBlank { _ui.value.selectedRemote },
                deviceName = prefillDevice,
            ),
        )
    }

    fun startCapture() {
        val l = _ui.value.learn
        if (l.remoteEntityId.isBlank() || l.deviceName.isBlank() || l.commandName.isBlank()) {
            Toaster.error("Remote, device and command are all required")
            return
        }
        if (learnJob?.isActive == true) return
        _ui.value = _ui.value.copy(
            learn = l.copy(
                phase = LearnPhase.CAPTURING,
                error = null,
                saved = false,
                startedAtMillis = System.currentTimeMillis(),
            ),
        )
        learnJob = viewModelScope.launch {
            haRepository.learnRemoteCommand(
                entityId = l.remoteEntityId,
                device = l.deviceName.trim(),
                command = l.commandName.trim(),
                commandType = l.type,
                alternative = l.alternative,
            ).fold(
                onSuccess = {
                    R1Log.i("Broadlink", "learned ${l.deviceName}/${l.commandName} (${l.type})")
                    _ui.value = _ui.value.copy(
                        learn = _ui.value.learn.copy(phase = LearnPhase.CAPTURED, error = null),
                    )
                },
                onFailure = { t ->
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    R1Log.w("Broadlink", "learn failed: ${t.message}")
                    _ui.value = _ui.value.copy(
                        learn = _ui.value.learn.copy(
                            phase = LearnPhase.FAILED,
                            error = t.message ?: "Capture failed",
                        ),
                    )
                },
            )
        }
    }

    /** Abandon the client-side wait. HA's capture window stays open
     *  server-side until the integration's own timeout lapses; the UI
     *  says so rather than pretending the blaster stopped listening. */
    fun cancelCapture() {
        learnJob?.cancel()
        learnJob = null
        _ui.value = _ui.value.copy(
            learn = _ui.value.learn.copy(phase = LearnPhase.FORM, error = null),
        )
        Toaster.show("Stopped waiting. The blaster may keep listening briefly")
    }

    fun retryCapture() {
        _ui.value = _ui.value.copy(
            learn = _ui.value.learn.copy(phase = LearnPhase.FORM, error = null),
        )
    }

    fun testFireLearned() {
        val l = _ui.value.learn
        testFire(l.remoteEntityId, l.deviceName.trim(), l.commandName.trim())
    }

    /** SAVE on the captured screen: the code already lives on HA; this
     *  creates the R1HA-tagged automation that IS the catalog record, so
     *  the command is browseable + fireable from any install. */
    fun saveLearned() {
        val l = _ui.value.learn
        if (l.saved || _ui.value.savingCommand) return
        _ui.value = _ui.value.copy(savingCommand = true)
        viewModelScope.launch {
            persistCommandAutomation(
                BroadlinkMarker.CommandMeta(
                    remote = l.remoteEntityId,
                    device = l.deviceName.trim(),
                    command = l.commandName.trim(),
                    type = l.type,
                ),
            ).fold(
                onSuccess = {
                    _ui.value = _ui.value.copy(learn = _ui.value.learn.copy(saved = true))
                    Toaster.show("Saved '${l.commandName.trim()}' to HA")
                },
                onFailure = { t ->
                    // The captured code itself is safe on HA; only the
                    // catalog record failed, so SAVE stays available.
                    Toaster.error("Save failed: ${t.message ?: "unknown"}")
                },
            )
            _ui.value = _ui.value.copy(savingCommand = false)
        }
    }

    /** Keep remote + device, clear the command slot, back to the form. */
    fun learnAnother() {
        val l = _ui.value.learn
        _ui.value = _ui.value.copy(
            learn = LearnState(
                remoteEntityId = l.remoteEntityId,
                deviceName = l.deviceName,
                type = l.type,
            ),
        )
    }

    // ── Automations + catalog read ──────────────────────────────────────

    /** One loader feeds both surfaces: the automations pane's rows and the
     *  command catalog (derived from the marked config bodies). On failure
     *  the previous catalog stays cached in [UiState.catalog]; the UI
     *  shows the error with a retry instead of silently blanking. */
    fun loadAutomations() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(automationsLoading = true, automationsError = null)
            haRepository.listRawEntitiesByDomain("automation").fold(
                onSuccess = { rows ->
                    val base = rows.map { row ->
                        AutomationRow(
                            entityId = row.entityId,
                            name = row.friendlyName,
                            enabled = row.state.equals("on", ignoreCase = true),
                            available = row.state.lowercase() in setOf("on", "off"),
                            configId = (row.attributes["id"] as? JsonPrimitive)?.content
                                ?.takeIf { it.isNotBlank() },
                            lastTriggered = (row.attributes["last_triggered"] as? JsonPrimitive)
                                ?.content?.takeIf { it.isNotBlank() && it != "null" },
                        )
                    }.sortedBy { it.name.lowercase() }
                    _ui.value = _ui.value.copy(automationsLoading = false, automations = base)
                    fetchConfigBodies(base)
                },
                onFailure = { t ->
                    R1Log.w("Broadlink", "automation list failed: ${t.message}")
                    _ui.value = _ui.value.copy(
                        automationsLoading = false,
                        automationsError = t.message ?: "Couldn't reach Home Assistant",
                    )
                },
            )
        }
    }

    /** Pull config bodies for UI-managed rows: the BROADLINK filter can
     *  then inspect actions instead of guessing from names, and the
     *  catalog is exactly the marker-tagged subset. Sequential on
     *  purpose: tens of small GETs against a possibly-strict HA beat a
     *  parallel stampede, and the list is already rendered. */
    private fun fetchConfigBodies(rows: List<AutomationRow>) {
        viewModelScope.launch {
            val bodies = HashMap<String, String>()
            for (row in rows) {
                val id = row.configId ?: continue
                haRepository.fetchAutomationConfig(id).onSuccess { bodies[row.entityId] = it }
            }
            val withBodies = _ui.value.automations.map { r ->
                bodies[r.entityId]?.let { r.copy(configBody = it) } ?: r
            }
            val entries = withBodies.mapNotNull { row ->
                val id = row.configId ?: return@mapNotNull null
                val body = row.configBody ?: return@mapNotNull null
                BroadlinkCatalog.parseEntry(
                    automationId = id,
                    entityId = row.entityId,
                    configJson = body,
                    lastTriggered = row.lastTriggered,
                )
            }
            _ui.value = _ui.value.copy(
                automations = withBodies,
                configsFetched = true,
                catalog = entries,
                catalogLoaded = true,
            )
        }
    }

    fun setAutomationEnabled(row: AutomationRow, enabled: Boolean) {
        if (!row.available) {
            Toaster.error("'${row.name}' is unavailable")
            return
        }
        _ui.value = _ui.value.copy(
            automations = _ui.value.automations.map {
                if (it.entityId == row.entityId) it.copy(enabled = enabled) else it
            },
        )
        viewModelScope.launch {
            haRepository.call(
                ServiceCall(
                    target = EntityId(row.entityId),
                    service = if (enabled) "turn_on" else "turn_off",
                    data = JsonObject(emptyMap()),
                ),
            ).onFailure { t ->
                R1Log.w("Broadlink", "toggle ${row.entityId} failed: ${t.message}")
                _ui.value = _ui.value.copy(
                    automations = _ui.value.automations.map {
                        if (it.entityId == row.entityId) it.copy(enabled = !enabled) else it
                    },
                )
            }
        }
    }

    fun triggerAutomation(row: AutomationRow) {
        if (!row.available) {
            Toaster.error("'${row.name}' is unavailable")
            return
        }
        viewModelScope.launch {
            haRepository.call(
                ServiceCall(
                    target = EntityId(row.entityId),
                    service = "trigger",
                    data = buildJsonObject { put("skip_condition", JsonPrimitive(true)) },
                ),
            ).fold(
                onSuccess = { Toaster.show("Triggered '${row.name}'") },
                onFailure = { t ->
                    R1Log.w("Broadlink", "trigger ${row.entityId} failed: ${t.message}")
                },
            )
        }
    }

    fun createAutomation(
        alias: String,
        trigger: BroadlinkCards.Trigger,
        remoteEntityId: String,
        deviceName: String,
        commandName: String,
        repeats: Int,
        onCreated: () -> Unit,
    ) {
        if (_ui.value.creatingAutomation) return
        _ui.value = _ui.value.copy(creatingAutomation = true)
        viewModelScope.launch {
            val config = BroadlinkCards.automationConfig(
                alias = alias.trim(),
                trigger = trigger,
                remoteEntityId = remoteEntityId,
                deviceName = deviceName,
                commandName = commandName,
                repeats = repeats,
            )
            haRepository.saveAutomationConfig(
                automationId = BroadlinkCards.newAutomationId(System.currentTimeMillis()),
                config = config,
            ).fold(
                onSuccess = {
                    Toaster.show("Automation '${alias.trim()}' created")
                    // HA reloads automations on config save; small grace so
                    // the fresh entity is listed when we re-pull.
                    kotlinx.coroutines.delay(600L)
                    loadAutomations()
                    onCreated()
                },
                onFailure = { t ->
                    R1Log.w("Broadlink", "create automation failed: ${t.message}")
                    Toaster.error("Create failed: ${t.message ?: "unknown"}")
                },
            )
            _ui.value = _ui.value.copy(creatingAutomation = false)
        }
    }

    // ── Pin to deck ─────────────────────────────────────────────────────

    /** Pin a catalog command. ×1 pins an automation.trigger card (same
     *  single-source-of-execution rule as in-app fires); ×N pins a
     *  send_command card because num_repeats only exists at fire time. */
    fun pinEntryToPage(
        pageId: String,
        entry: BroadlinkCatalog.Entry,
        label: String,
        repeats: Int = 1,
    ) {
        val meta = entry.meta
        val card = if (repeats > 1 && meta != null) {
            BroadlinkCards.commandButtonCard(
                remoteEntityId = meta.remote,
                deviceName = meta.device,
                commandName = meta.command,
                label = label,
                repeats = repeats,
            )
        } else {
            BroadlinkCards.automationButtonCard(entry.entityId, label)
        }
        appendPinnedCard(pageId, card.toString(), label)
    }

    fun pinAutomationToPage(pageId: String, automationEntityId: String, label: String) {
        appendPinnedCard(
            pageId,
            BroadlinkCards.automationButtonCard(automationEntityId, label).toString(),
            label,
        )
    }

    private fun appendPinnedCard(pageId: String, cardJson: String, label: String) {
        viewModelScope.launch {
            var pageName: String? = null
            settings.update { s ->
                val page = s.pages.firstOrNull { it.id == pageId } ?: return@update s
                pageName = page.name
                s.copy(
                    pages = s.pages.map { p ->
                        if (p.id == pageId) p.copy(pinnedCards = p.pinnedCards + cardJson) else p
                    },
                )
            }
            val name = pageName
            if (name != null) {
                Toaster.show("Pinned '$label' to $name")
            } else {
                Toaster.error("Page no longer exists")
            }
        }
    }
}
