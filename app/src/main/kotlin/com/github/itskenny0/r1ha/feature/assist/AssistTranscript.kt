package com.github.itskenny0.r1ha.feature.assist

import java.util.Locale

/**
 * Pure, side-effect-free helpers backing the Assist transcript. Kept out of the
 * ViewModel / Composables so the turn-building, response classification, and
 * draft / macro rules are unit-testable without an Android runtime or a live
 * HaRepository.
 *
 * The transcript itself is just an ordered [List] of [AssistMessage]; these
 * functions append turns and answer the small predicates the UI asks ("can I
 * send?", "what colour is this bubble?") so both the ViewModel and the screen
 * read from one source of truth.
 */
object AssistTranscript {

    /**
     * Coarse classification of an assistant turn, derived from HA's tagged
     * `response_type`. Drives the bubble accent without the UI re-deriving the
     * mapping inline. User-side turns are [USER]; anything HA flags as an error
     * (or a turn we synthesise locally on failure / cancel) is [ERROR]; the
     * normal `action_done` / `query_answer` happy path is [REPLY].
     */
    enum class TurnKind { USER, REPLY, ERROR }

    /** HA's response_type string we treat as an error turn. */
    const val RESPONSE_TYPE_ERROR: String = "error"

    /** Cap on saved macro chips; oldest dropped past this so the chip row stays readable. */
    const val MAX_MACROS: Int = 12

    /** Classify a transcript message for accent / colour purposes. */
    fun kindOf(message: AssistMessage): TurnKind = when {
        message.fromUser -> TurnKind.USER
        message.responseType == RESPONSE_TYPE_ERROR -> TurnKind.ERROR
        else -> TurnKind.REPLY
    }

    /**
     * Trim a raw draft to what should actually be sent. Returns the trimmed
     * string; callers gate on [isSendable] for the empty / in-flight checks.
     */
    fun normalizeDraft(draft: String): String = draft.trim()

    /**
     * Whether a send should proceed: a non-blank draft and no request already
     * in flight. Centralises the rule the SEND button, the IME action, and the
     * macro path all share so they can't drift apart.
     */
    fun isSendable(draft: String, inFlight: Boolean): Boolean =
        !inFlight && normalizeDraft(draft).isNotEmpty()

    /** Append a user prompt to the transcript, returning a new list. */
    fun appendUserTurn(messages: List<AssistMessage>, text: String): List<AssistMessage> =
        messages + AssistMessage(text = normalizeDraft(text), fromUser = true)

    /**
     * Append an assistant reply. [responseType] is HA's tag (`action_done`,
     * `query_answer`, ...) carried through so [kindOf] can colour it later.
     */
    fun appendAssistTurn(
        messages: List<AssistMessage>,
        text: String,
        responseType: String?,
    ): List<AssistMessage> =
        messages + AssistMessage(text = text, fromUser = false, responseType = responseType)

    /**
     * Append a locally-synthesised error / cancel turn (network failure, slow
     * agent cancelled, ...). Always tagged [RESPONSE_TYPE_ERROR] so it reads as
     * a red bubble regardless of what HA would have said.
     */
    fun appendErrorTurn(messages: List<AssistMessage>, text: String): List<AssistMessage> =
        messages + AssistMessage(
            text = text,
            fromUser = false,
            responseType = RESPONSE_TYPE_ERROR,
        )

    /** Standard copy for a failed conversation/process round-trip. */
    fun errorTurnText(reason: String?): String = "(error: ${reason ?: "unknown"})"

    /** Standard copy for a user-cancelled in-flight turn. */
    fun cancelledTurnText(): String = "(cancelled)"

    /**
     * Add [text] to the saved-macro list: de-duplicates (an existing entry is
     * left in place rather than re-added) and caps the result at [MAX_MACROS]
     * by dropping the oldest. Returns the unchanged list when [text] is blank
     * or already present.
     */
    fun addMacro(existing: List<String>, text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed in existing) return existing
        return (existing + trimmed).takeLast(MAX_MACROS)
    }

    /**
     * Label for the agent chip in the top bar. Truncates long agent ids so the
     * chip doesn't overflow; a null / blank override reads as the HA default.
     */
    fun agentLabel(agentId: String?): String {
        val id = agentId?.takeIf { it.isNotBlank() } ?: return "AGENT: DEFAULT"
        return "AGENT: ${id.take(18).uppercase(Locale.US)}"
    }

    /** Normalise a free-form agent-picker entry: blank collapses to null (= HA default). */
    fun normalizeAgentId(raw: String?): String? = raw?.trim()?.takeIf { it.isNotBlank() }
}
