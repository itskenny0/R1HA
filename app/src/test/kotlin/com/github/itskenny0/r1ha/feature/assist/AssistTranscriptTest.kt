package com.github.itskenny0.r1ha.feature.assist

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit tests for the pure transcript / turn-formatting helpers in
 * AssistTranscript.kt: response classification, turn appending, send / macro
 * predicates, and the agent-chip label formatting. No Android runtime or live
 * HaRepository required.
 */
class AssistTranscriptTest {

    // --- kindOf ----------------------------------------------------------

    @Test
    fun `user message classifies as USER regardless of responseType`() {
        val msg = AssistMessage(text = "hi", fromUser = true, responseType = "error")
        assertThat(AssistTranscript.kindOf(msg)).isEqualTo(AssistTranscript.TurnKind.USER)
    }

    @Test
    fun `assistant error responseType classifies as ERROR`() {
        val msg = AssistMessage(text = "boom", fromUser = false, responseType = "error")
        assertThat(AssistTranscript.kindOf(msg)).isEqualTo(AssistTranscript.TurnKind.ERROR)
    }

    @Test
    fun `assistant happy-path responseType classifies as REPLY`() {
        val done = AssistMessage(text = "done", fromUser = false, responseType = "action_done")
        val answer = AssistMessage(text = "21C", fromUser = false, responseType = "query_answer")
        val untagged = AssistMessage(text = "ok", fromUser = false, responseType = null)
        assertThat(AssistTranscript.kindOf(done)).isEqualTo(AssistTranscript.TurnKind.REPLY)
        assertThat(AssistTranscript.kindOf(answer)).isEqualTo(AssistTranscript.TurnKind.REPLY)
        assertThat(AssistTranscript.kindOf(untagged)).isEqualTo(AssistTranscript.TurnKind.REPLY)
    }

    // --- normalizeDraft / isSendable -------------------------------------

    @Test
    fun `normalizeDraft trims surrounding whitespace`() {
        assertThat(AssistTranscript.normalizeDraft("  turn off  ")).isEqualTo("turn off")
    }

    @Test
    fun `isSendable false when blank`() {
        assertThat(AssistTranscript.isSendable("   ", inFlight = false)).isFalse()
        assertThat(AssistTranscript.isSendable("", inFlight = false)).isFalse()
    }

    @Test
    fun `isSendable false when in flight even with text`() {
        assertThat(AssistTranscript.isSendable("hello", inFlight = true)).isFalse()
    }

    @Test
    fun `isSendable true for non-blank draft and idle`() {
        assertThat(AssistTranscript.isSendable("  hello  ", inFlight = false)).isTrue()
    }

    // --- append turns ----------------------------------------------------

    @Test
    fun `appendUserTurn adds a trimmed user message preserving order`() {
        val start = listOf(AssistMessage(text = "earlier", fromUser = false))
        val next = AssistTranscript.appendUserTurn(start, "  do it  ")
        assertThat(next).hasSize(2)
        assertThat(next.last().fromUser).isTrue()
        assertThat(next.last().text).isEqualTo("do it")
        // original list is untouched (pure)
        assertThat(start).hasSize(1)
    }

    @Test
    fun `appendAssistTurn carries responseType through`() {
        val next = AssistTranscript.appendAssistTurn(emptyList(), "Turned off", "action_done")
        assertThat(next).hasSize(1)
        assertThat(next.last().fromUser).isFalse()
        assertThat(next.last().responseType).isEqualTo("action_done")
        assertThat(AssistTranscript.kindOf(next.last())).isEqualTo(AssistTranscript.TurnKind.REPLY)
    }

    @Test
    fun `appendErrorTurn always tags error`() {
        val next = AssistTranscript.appendErrorTurn(emptyList(), AssistTranscript.cancelledTurnText())
        assertThat(next.last().responseType).isEqualTo(AssistTranscript.RESPONSE_TYPE_ERROR)
        assertThat(AssistTranscript.kindOf(next.last())).isEqualTo(AssistTranscript.TurnKind.ERROR)
    }

    @Test
    fun `errorTurnText falls back to unknown when reason null`() {
        assertThat(AssistTranscript.errorTurnText(null)).isEqualTo("(error: unknown)")
        assertThat(AssistTranscript.errorTurnText("timeout")).isEqualTo("(error: timeout)")
    }

    @Test
    fun `cancelledTurnText is the cancel marker`() {
        assertThat(AssistTranscript.cancelledTurnText()).isEqualTo("(cancelled)")
    }

    // --- addMacro --------------------------------------------------------

    @Test
    fun `addMacro appends a trimmed entry`() {
        assertThat(AssistTranscript.addMacro(emptyList(), "  scene  ")).containsExactly("scene")
    }

    @Test
    fun `addMacro ignores blank`() {
        val existing = listOf("a")
        assertThat(AssistTranscript.addMacro(existing, "   ")).isSameInstanceAs(existing)
    }

    @Test
    fun `addMacro de-dupes existing without reordering`() {
        val existing = listOf("a", "b")
        assertThat(AssistTranscript.addMacro(existing, "a")).isSameInstanceAs(existing)
    }

    @Test
    fun `addMacro caps at MAX_MACROS dropping oldest`() {
        val full = (1..AssistTranscript.MAX_MACROS).map { "m$it" }
        val next = AssistTranscript.addMacro(full, "new")
        assertThat(next).hasSize(AssistTranscript.MAX_MACROS)
        assertThat(next.first()).isEqualTo("m2")
        assertThat(next.last()).isEqualTo("new")
    }

    // --- agentLabel / normalizeAgentId -----------------------------------

    @Test
    fun `agentLabel reads DEFAULT for null or blank`() {
        assertThat(AssistTranscript.agentLabel(null)).isEqualTo("AGENT: DEFAULT")
        assertThat(AssistTranscript.agentLabel("   ")).isEqualTo("AGENT: DEFAULT")
    }

    @Test
    fun `agentLabel uppercases and truncates long ids`() {
        val label = AssistTranscript.agentLabel("conversation.openai_conversation")
        assertThat(label).isEqualTo("AGENT: CONVERSATION.OPENA")
        assertThat(label.removePrefix("AGENT: ")).hasLength(18)
    }

    @Test
    fun `normalizeAgentId collapses blank to null and trims`() {
        assertThat(AssistTranscript.normalizeAgentId("  ")).isNull()
        assertThat(AssistTranscript.normalizeAgentId(null)).isNull()
        assertThat(AssistTranscript.normalizeAgentId("  homeassistant ")).isEqualTo("homeassistant")
    }
}
