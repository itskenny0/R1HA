package com.github.itskenny0.r1ha.feature.assist

/**
 * Pure, Compose-free label builders for the Assist (HA Conversation) surface.
 * Kept out of [AssistScreen] so the spoken wording is unit-testable without a
 * Compose runtime and lives in one auditable place.
 *
 * The transcript is a chat: who said what matters to a screen-reader user who
 * can't see the left/right bubble alignment or the warm/red accent. These
 * helpers prefix each bubble with the speaker ("You said" / "Assistant" /
 * "Assistant error") so the conversation reads sensibly turn by turn, and they
 * phrase the in-flight "listening"/"thinking" states in words for the polite
 * live region.
 */
object AssistA11y {

    /**
     * Merged content description for one transcript bubble. The visible cue for
     * speaker is purely positional (user bubbles right + warm accent, replies
     * left + muted, errors red), none of which a screen reader perceives, so we
     * spell out the speaker in words and read the bubble text after it, e.g.
     * "You said, turn off the kitchen light" or
     * "Assistant error, network timeout".
     */
    fun bubbleDescription(kind: AssistTranscript.TurnKind, text: String): String {
        val speaker = when (kind) {
            AssistTranscript.TurnKind.USER -> "You said"
            AssistTranscript.TurnKind.REPLY -> "Assistant"
            AssistTranscript.TurnKind.ERROR -> "Assistant error"
        }
        val body = text.trim()
        return if (body.isEmpty()) speaker else "$speaker, $body"
    }

    /** Spoken hint for the long-press copy gesture on a bubble. */
    fun bubbleActionLabel(): String = "Long press to copy"

    /**
     * Phrase announced through the transcript's polite live region while a
     * request is in flight. Distinguishes the typed path ("Sending your
     * message, waiting for a reply") so the dot animation, which a screen
     * reader can't see, is conveyed as progress.
     */
    fun inFlightAnnounce(): String = "Sending your message, waiting for a reply"

    /** Live-region phrase once a turn settles, so the reader hears it finished. */
    fun settledAnnounce(): String = "Reply received"

    /** Empty-transcript live-region phrase: nothing pending, ready for input. */
    fun idleAnnounce(): String = ""

    /**
     * The single live-region line for the transcript area, derived from the two
     * state bits the UI already tracks. Centralised so the screen never
     * re-derives the wording inline and the empty / in-flight / settled cases
     * stay consistent.
     */
    fun transcriptAnnounce(inFlight: Boolean, hasMessages: Boolean): String = when {
        inFlight -> inFlightAnnounce()
        hasMessages -> settledAnnounce()
        else -> idleAnnounce()
    }

    /** Spoken label for the SEND / STOP control, reflecting its current role. */
    fun sendControlLabel(inFlight: Boolean): String =
        if (inFlight) "Stop waiting for a reply" else "Send message"

    /** Spoken label for the microphone (system speech recognizer) control. */
    fun micControlLabel(): String = "Speak your message"

    /** Spoken label for the new-conversation reset control. */
    fun resetControlLabel(): String = "Start a new conversation"

    /**
     * Spoken label for the save-draft-as-macro control. Conveys in words
     * whether tapping will do anything (the visible cue is only the warm vs
     * muted star tint, which a screen reader can't see).
     */
    fun saveMacroControlLabel(canSave: Boolean): String =
        if (canSave) "Save this message as a macro" else "Save as macro, type a message first"

    /**
     * Spoken label for an example-prompt chip on the empty state. Tapping it
     * sends the prompt immediately, so the label says so rather than leaving
     * the reader to guess that the chip is a shortcut.
     */
    fun examplePromptLabel(prompt: String): String = "Send example, $prompt"

    /**
     * Spoken label for a saved-macro chip. Folds the tap (send) and long-press
     * (delete) affordances into one phrase so the reader knows both actions.
     */
    fun macroChipLabel(macro: String): String = "Macro, $macro. Long press to remove"
}
