package com.github.itskenny0.r1ha.feature.assist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.util.R1Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Drives the Assist (HA Conversation) surface. Holds the conversation
 * transcript and the [conversationId] threaded across HA's
 * `conversation/process` calls so multi-turn context survives (so the
 * user can say "turn off the light" then "the kitchen one" and HA's
 * intent engine carries the device-class state forward).
 *
 * Text-only for now — no STT / TTS. The R1 has a microphone and the HA
 * Assist pipeline can do server-side STT, so adding voice is a follow-
 * up rather than a refactor: the audio frames pump into the same
 * /api/conversation/process endpoint or the dedicated Assist pipeline
 * websocket.
 */
@androidx.compose.runtime.Stable
data class AssistMessage(
    val text: String,
    val fromUser: Boolean,
    /** HA's response_type ("action_done" / "query_answer" / "error") for
     *  the bubble's accent colour; null for user-side messages. */
    val responseType: String? = null,
    /** Monotonically-increasing id so LazyColumn can key the rows
     *  stably even when two identical-text messages land back-to-back. */
    val id: Long = System.nanoTime(),
)

@androidx.compose.runtime.Stable
data class AssistUiState(
    val messages: List<AssistMessage> = emptyList(),
    val inFlight: Boolean = false,
    val draft: String = "",
    /** True once HA has handed back a conversation_id, i.e. there is a live
     *  multi-turn thread the RESET control can actually clear. Lets the screen
     *  disable RESET on a fresh open so it doesn't read as a live control with
     *  nothing to do. */
    val conversationActive: Boolean = false,
    /** True when HA replied with `continue_conversation`, i.e. it expects a
     *  follow-up turn and the input should stay primed. Mirrors HA's own
     *  Assist UI re-opening the mic after a turn that asks a question. */
    val awaitingFollowUp: Boolean = false,
)

class AssistViewModel(
    private val haRepository: HaRepository,
    private val settings: com.github.itskenny0.r1ha.core.prefs.SettingsRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(AssistUiState())
    val ui: StateFlow<AssistUiState> = _ui

    /** Threaded across calls so HA's intent engine can carry context. */
    private var conversationId: String? = null

    fun setDraft(value: String) {
        _ui.value = _ui.value.copy(draft = value)
    }

    /** Job for the currently-in-flight conversation.process call. Tracked so [cancel]
     *  can abort a slow local-LLM Assist round-trip; without this the SEND button
     *  just sits disabled while the user waits for the timeout. */
    private var sendJob: kotlinx.coroutines.Job? = null

    fun cancel() {
        // Guard: only synthesise a cancel turn when something is actually in
        // flight. Without this a stray cancel (e.g. STOP tapped right as the
        // reply lands) appends a spurious "(cancelled)" bubble after a
        // successful turn.
        if (!_ui.value.inFlight) return
        sendJob?.cancel()
        sendJob = null
        _ui.value = _ui.value.copy(
            inFlight = false,
            awaitingFollowUp = false,
            messages = AssistTranscript.appendErrorTurn(
                _ui.value.messages,
                AssistTranscript.cancelledTurnText(),
            ),
        )
    }

    fun send() {
        if (!AssistTranscript.isSendable(_ui.value.draft, _ui.value.inFlight)) return
        val text = AssistTranscript.normalizeDraft(_ui.value.draft)
        _ui.value = _ui.value.copy(
            messages = AssistTranscript.appendUserTurn(_ui.value.messages, text),
            draft = "",
            inFlight = true,
        )
        sendJob?.cancel()
        sendJob = viewModelScope.launch {
            // Read the user-picked agent fresh on every send so picking a new
            // agent mid-conversation takes effect on the very next turn rather
            // than waiting for screen recompose. Null = HA's default agent.
            val agentId = settings.settings.first().behavior.assistAgentId
            val result = haRepository.conversationProcess(
                text = text,
                conversationId = conversationId,
                agentId = agentId,
            )
            result.fold(
                onSuccess = { response ->
                    R1Log.i(
                        "Assist",
                        "type=${response.responseType} convId=${response.conversationId} speech=${response.speech}",
                    )
                    conversationId = response.conversationId ?: conversationId
                    _ui.value = _ui.value.copy(
                        messages = AssistTranscript.appendAssistTurn(
                            _ui.value.messages,
                            text = response.speech,
                            responseType = response.responseType,
                        ),
                        inFlight = false,
                        conversationActive = conversationId != null,
                        // HA signals continue_conversation when it asked a
                        // question and expects an immediate follow-up. The repo
                        // doesn't surface that flag yet (see SHARED CHANGE
                        // REQUESTS), so this stays false until the field lands;
                        // wiring it here means the UI lights up the moment the
                        // plumbing is added with no further screen change.
                        awaitingFollowUp = false,
                    )
                },
                onFailure = { t ->
                    R1Log.w("Assist", "process failed: ${t.message}")
                    _ui.value = _ui.value.copy(
                        messages = AssistTranscript.appendErrorTurn(
                            _ui.value.messages,
                            AssistTranscript.errorTurnText(t.message),
                        ),
                        inFlight = false,
                        awaitingFollowUp = false,
                    )
                },
            )
        }
    }

    /**
     * Send a macro directly — bypasses the draft field so a tap on a macro chip
     * fires immediately without first populating the input. Multi-tapping the
     * same macro fires it each time (HA's conversation engine handles dedupe at
     * its end; we don't second-guess intent). No-ops while a request is in
     * flight so a frantic tap-tap-tap can't pile concurrent requests onto HA.
     */
    fun sendMacro(text: String) {
        if (text.isBlank() || _ui.value.inFlight) return
        _ui.value = _ui.value.copy(draft = text)
        send()
    }

    fun saveCurrentDraftAsMacro() {
        val text = _ui.value.draft.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            settings.update { s ->
                // De-dupes and caps at AssistTranscript.MAX_MACROS, dropping the
                // oldest so the chip row stays readable on narrow screens.
                val next = AssistTranscript.addMacro(s.behavior.assistMacros, text)
                if (next === s.behavior.assistMacros) return@update s
                s.copy(behavior = s.behavior.copy(assistMacros = next))
            }
            com.github.itskenny0.r1ha.core.util.Toaster.show("Macro saved")
        }
    }

    fun deleteMacro(text: String) {
        viewModelScope.launch {
            settings.update { s ->
                s.copy(behavior = s.behavior.copy(
                    assistMacros = s.behavior.assistMacros - text,
                ))
            }
        }
    }

    /** Start a fresh conversation — drops the threaded id so the next
     *  send re-anchors HA's context. The transcript is also cleared so
     *  the UI doesn't pretend the previous turn is still live. */
    fun reset() {
        conversationId = null
        _ui.value = AssistUiState()
    }

    companion object {
        fun factory(
            haRepository: HaRepository,
            settings: com.github.itskenny0.r1ha.core.prefs.SettingsRepository,
        ) = viewModelFactory {
            initializer { AssistViewModel(haRepository, settings) }
        }
    }
}
