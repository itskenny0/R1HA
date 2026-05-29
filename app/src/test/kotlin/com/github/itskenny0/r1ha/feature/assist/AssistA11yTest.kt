package com.github.itskenny0.r1ha.feature.assist

import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AssistA11yTest {

    @BeforeEach
    fun setLocale() {
        Locale.setDefault(Locale.US)
    }

    @Test
    fun bubble_prefixesSpeaker() {
        assertThat(
            AssistA11y.bubbleDescription(AssistTranscript.TurnKind.USER, "turn off the light"),
        ).isEqualTo("You said, turn off the light")
        assertThat(
            AssistA11y.bubbleDescription(AssistTranscript.TurnKind.REPLY, "Done"),
        ).isEqualTo("Assistant, Done")
        assertThat(
            AssistA11y.bubbleDescription(AssistTranscript.TurnKind.ERROR, "network timeout"),
        ).isEqualTo("Assistant error, network timeout")
    }

    @Test
    fun bubble_blankBodyReadsSpeakerOnly() {
        assertThat(
            AssistA11y.bubbleDescription(AssistTranscript.TurnKind.REPLY, "   "),
        ).isEqualTo("Assistant")
    }

    @Test
    fun transcriptAnnounce_reflectsState() {
        assertThat(AssistA11y.transcriptAnnounce(inFlight = true, hasMessages = true))
            .isEqualTo("Sending your message, waiting for a reply")
        assertThat(AssistA11y.transcriptAnnounce(inFlight = true, hasMessages = false))
            .isEqualTo("Sending your message, waiting for a reply")
        assertThat(AssistA11y.transcriptAnnounce(inFlight = false, hasMessages = true))
            .isEqualTo("Reply received")
        assertThat(AssistA11y.transcriptAnnounce(inFlight = false, hasMessages = false))
            .isEqualTo("")
    }

    @Test
    fun sendControl_reflectsInFlight() {
        assertThat(AssistA11y.sendControlLabel(inFlight = false)).isEqualTo("Send message")
        assertThat(AssistA11y.sendControlLabel(inFlight = true)).isEqualTo("Stop waiting for a reply")
    }

    @Test
    fun saveMacroControl_conveysEnablement() {
        assertThat(AssistA11y.saveMacroControlLabel(canSave = true))
            .isEqualTo("Save this message as a macro")
        assertThat(AssistA11y.saveMacroControlLabel(canSave = false))
            .isEqualTo("Save as macro, type a message first")
    }

    @Test
    fun chipLabels_describeActions() {
        assertThat(AssistA11y.examplePromptLabel("Is anyone home?"))
            .isEqualTo("Send example, Is anyone home?")
        assertThat(AssistA11y.macroChipLabel("Goodnight"))
            .isEqualTo("Macro, Goodnight. Long press to remove")
    }
}
