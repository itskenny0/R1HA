package com.github.itskenny0.r1ha.feature.template

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.util.R1Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Drives the Templates surface — a Jinja2 evaluator backed by HA's
 * `/api/template`. Holds the editable template, the last result (or
 * error), and an in-flight flag so a slow render doesn't spawn racing
 * fetches if the user mashes RENDER.
 *
 * Why this lives in the app: HA ships a template editor in its
 * frontend, but reaching it from the R1 means context-switching to a
 * desktop. Iterating a template ("{{ states.sun.sun.state }}" → "what
 * about elevation?") in the same surface as the rest of HA control
 * keeps the feedback loop tight.
 */
class TemplateViewModel(
    private val haRepository: HaRepository,
    private val settings: com.github.itskenny0.r1ha.core.prefs.SettingsRepository,
) : ViewModel() {

    @Volatile
    private var historyDepth: Int = 5

    @androidx.compose.runtime.Stable
    data class UiState(
        val template: String = """{{ now().isoformat() }}""",
        val rendered: String = "",
        val error: String? = null,
        val inFlight: Boolean = false,
        /** Last 5 successfully-rendered templates, newest first. In-memory
         *  ViewModel state — clears on app restart by design (so a stale
         *  syntactically-incorrect template from yesterday doesn't haunt
         *  today's session). */
        val recent: List<String> = emptyList(),
        /** When true the screen is subscribed to live HA template events;
         *  every state change that affects the template's outputs re-renders.
         *  Off by default — REST-render is the simpler ask for one-off
         *  evaluations and doesn't tie up a WS subscription. */
        val live: Boolean = false,
        /** When true, edits to the template trigger a debounced REST render so
         *  the output tracks what the user is typing without a button tap.
         *  Distinct from [live]: AUTO re-renders on keystroke (debounced),
         *  LIVE re-renders on HA state change (WS subscription). The two are
         *  mutually exclusive; turning one on turns the other off. */
        val auto: Boolean = false,
        /** Classification of the current [error], used by the UI to pick a
         *  heading. Null when there is no error. */
        val errorKind: TemplateLogic.ErrorKind? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    /** The last template string we issued (or scheduled) an AUTO render for.
     *  Lets [scheduleAutoRender] skip redundant fires on identical input. */
    @Volatile
    private var lastAutoTemplate: String? = null

    /** Pending debounced AUTO render. Cancelled + replaced on each keystroke
     *  so only the trailing edit in a burst actually hits HA. */
    @Volatile
    private var autoJob: Job? = null

    fun setTemplate(value: String) {
        _ui.value = _ui.value.copy(template = value)
        // If the user edits the template while LIVE is on, drop the existing
        // subscription and resubscribe so the new template is what's evaluated.
        if (_ui.value.live) {
            viewModelScope.launch {
                liveSubscription?.cancel()
                liveSubscription = null
                startLiveSubscription()
            }
        } else if (_ui.value.auto) {
            scheduleAutoRender(value)
        }
    }

    /**
     * Toggle AUTO (debounced render-on-type). On = mutually exclusive with
     * LIVE, so flipping it on tears down any WS subscription and fires an
     * immediate first render of the current template. Off = cancels any
     * pending debounced render; the last result stays on screen.
     */
    fun setAuto(enabled: Boolean) {
        if (_ui.value.auto == enabled) return
        if (enabled) {
            // AUTO and LIVE are mutually exclusive — one owns the rendered value.
            if (_ui.value.live) setLive(false)
            _ui.value = _ui.value.copy(auto = true)
            // Render immediately so toggling on doesn't sit blank for the
            // debounce window; subsequent keystrokes go through the debounce.
            lastAutoTemplate = _ui.value.template
            render()
        } else {
            _ui.value = _ui.value.copy(auto = false)
            autoJob?.cancel()
            autoJob = null
        }
    }

    private fun scheduleAutoRender(value: String) {
        autoJob?.cancel()
        if (!TemplateLogic.shouldAutoRender(value, lastAutoTemplate)) {
            return
        }
        autoJob = viewModelScope.launch {
            delay(TemplateLogic.AUTO_DEBOUNCE_MS)
            lastAutoTemplate = value
            render()
        }
    }

    fun clearRecent() {
        _ui.value = _ui.value.copy(recent = emptyList())
    }

    /** Active render_template subscription handle. Held so [setLive] off + screen
     *  teardown can tear it down explicitly without leaking the WS subscription
     *  server-side. */
    @Volatile
    private var liveSubscription: HaRepository.TemplateSubscription? = null

    /**
     * Toggle LIVE mode. On = subscribe to render_template events; off = cancel any
     * active subscription and revert to manual RENDER. Toggling has no effect on
     * the displayed [UiState.rendered] until the next event lands.
     */
    fun setLive(enabled: Boolean) {
        if (_ui.value.live == enabled) return
        // LIVE and AUTO are mutually exclusive — one owns the rendered value.
        if (enabled && _ui.value.auto) {
            _ui.value = _ui.value.copy(auto = false)
            autoJob?.cancel()
            autoJob = null
        }
        _ui.value = _ui.value.copy(live = enabled, error = null, errorKind = null)
        if (enabled) {
            startLiveSubscription()
        } else {
            viewModelScope.launch {
                liveSubscription?.cancel()
                liveSubscription = null
            }
        }
    }

    private fun startLiveSubscription() {
        val template = _ui.value.template
        if (template.isBlank()) return
        viewModelScope.launch {
            haRepository.subscribeTemplate(template) { rendered ->
                // Renders land on the IO scope; push into _ui from there since
                // MutableStateFlow.value is thread-safe.
                _ui.value = _ui.value.copy(
                    rendered = TemplateLogic.formatRendered(rendered),
                    error = null,
                    errorKind = null,
                )
            }.fold(
                onSuccess = { sub ->
                    liveSubscription = sub
                    R1Log.i("Template", "live subscribe registered")
                },
                onFailure = { t ->
                    R1Log.w("Template", "live subscribe failed: ${t.message}")
                    val classified = TemplateLogic.classifyError(t.message ?: "Live subscribe failed")
                    _ui.value = _ui.value.copy(
                        live = false,
                        error = classified.message,
                        errorKind = classified.kind,
                    )
                },
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Best-effort teardown so a screen-exit doesn't leak the WS subscription.
        // viewModelScope is already cancelled by the time onCleared runs, so we
        // fire the unsubscribe on a short-lived detached IO scope rather than
        // runBlocking on the main thread: blocking here would stall the UI while
        // a (possibly dead) WS round-trips. cancel() is safe to run detached
        // because the subscription's inbound collector lives on the repository's
        // own scope, not this ViewModel's, so it survives until the frame lands.
        autoJob?.cancel()
        autoJob = null
        val sub = liveSubscription
        liveSubscription = null
        if (sub != null) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                runCatching { sub.cancel() }
            }
        }
    }

    fun render() {
        val template = _ui.value.template
        if (template.isBlank() || _ui.value.inFlight) return
        _ui.value = _ui.value.copy(inFlight = true, error = null, errorKind = null)
        viewModelScope.launch {
            historyDepth = settings.settings.first().integrations.recentHistoryDepth
                .coerceIn(0, 100)
            haRepository.renderTemplate(template).fold(
                onSuccess = { rendered ->
                    R1Log.i("Template", "rendered len=${rendered.length}")
                    // Push to recent (dedupe + cap honouring the depth setting).
                    val newRecent = (listOf(template) + _ui.value.recent.filterNot { it == template })
                        .take(historyDepth)
                    _ui.value = _ui.value.copy(
                        // Strip outer whitespace / quotes — HA wraps template
                        // output with the leading/trailing whitespace of
                        // the original template (e.g. spaces around
                        // `{{ … }}`); displaying raw makes the result
                        // panel start with a blank line.
                        rendered = TemplateLogic.formatRendered(rendered),
                        error = null,
                        errorKind = null,
                        inFlight = false,
                        recent = newRecent,
                    )
                },
                onFailure = { t ->
                    // HA's syntax-error path returns a 400 with the Jinja
                    // traceback in the body; surface it verbatim so the
                    // user can iterate without leaving the screen. Classify
                    // it so the panel can distinguish a template bug from a
                    // connection/auth failure.
                    R1Log.w("Template", "render failed: ${t.message}")
                    val classified = TemplateLogic.classifyError(t.message)
                    _ui.value = _ui.value.copy(
                        error = classified.message,
                        errorKind = classified.kind,
                        inFlight = false,
                    )
                },
            )
        }
    }

    companion object {
        fun factory(
            haRepository: HaRepository,
            settings: com.github.itskenny0.r1ha.core.prefs.SettingsRepository,
        ) = viewModelFactory {
            initializer { TemplateViewModel(haRepository, settings) }
        }
    }
}
