package com.github.itskenny0.r1ha.feature.template

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure-logic coverage for the Templates surface: AUTO-render gating,
 * output normalisation, and render-error classification. No Compose,
 * coroutine, or repository dependencies.
 */
class TemplateLogicTest {

    // ── shouldAutoRender ────────────────────────────────────────────────────

    @Test
    fun `blank or whitespace template never auto-renders`() {
        assertThat(TemplateLogic.shouldAutoRender("", null)).isFalse()
        assertThat(TemplateLogic.shouldAutoRender("   ", null)).isFalse()
        assertThat(TemplateLogic.shouldAutoRender("\n\t", "prev")).isFalse()
    }

    @Test
    fun `unchanged template does not re-fire`() {
        assertThat(TemplateLogic.shouldAutoRender("{{ x }}", "{{ x }}")).isFalse()
    }

    @Test
    fun `changed non-blank template auto-renders`() {
        assertThat(TemplateLogic.shouldAutoRender("{{ x }}", null)).isTrue()
        assertThat(TemplateLogic.shouldAutoRender("{{ y }}", "{{ x }}")).isTrue()
    }

    @Test
    fun `debounce window is a sane positive value`() {
        assertThat(TemplateLogic.AUTO_DEBOUNCE_MS).isGreaterThan(0L)
        assertThat(TemplateLogic.AUTO_DEBOUNCE_MS).isAtMost(2000L)
    }

    // ── formatRendered ──────────────────────────────────────────────────────

    @Test
    fun `formatRendered trims surrounding whitespace`() {
        assertThat(TemplateLogic.formatRendered("  on  ")).isEqualTo("on")
        assertThat(TemplateLogic.formatRendered("\nresult\n")).isEqualTo("result")
    }

    @Test
    fun `formatRendered strips outer quotes`() {
        assertThat(TemplateLogic.formatRendered("\"quoted\"")).isEqualTo("quoted")
        assertThat(TemplateLogic.formatRendered("  \"spaced quoted\"  ")).isEqualTo("spaced quoted")
    }

    @Test
    fun `formatRendered preserves interior content and newlines`() {
        assertThat(TemplateLogic.formatRendered("a\nb\nc")).isEqualTo("a\nb\nc")
        // A lone quote on one side is not a wrapping pair; leave it.
        assertThat(TemplateLogic.formatRendered("\"unbalanced")).isEqualTo("\"unbalanced")
    }

    @Test
    fun `formatRendered handles empty input`() {
        assertThat(TemplateLogic.formatRendered("")).isEqualTo("")
        assertThat(TemplateLogic.formatRendered("   ")).isEqualTo("")
    }

    // ── classifyError ───────────────────────────────────────────────────────

    @Test
    fun `null or blank error yields a default unknown message`() {
        val a = TemplateLogic.classifyError(null)
        assertThat(a.kind).isEqualTo(TemplateLogic.ErrorKind.UNKNOWN)
        assertThat(a.message).isEqualTo("Render failed")

        val b = TemplateLogic.classifyError("   ")
        assertThat(b.message).isEqualTo("Render failed")
    }

    @Test
    fun `jinja syntax errors classify as template`() {
        assertThat(
            TemplateLogic.classifyError("TemplateSyntaxError: unexpected end of template").kind,
        ).isEqualTo(TemplateLogic.ErrorKind.TEMPLATE)
        assertThat(
            TemplateLogic.classifyError("UndefinedError: 'foo' is undefined").kind,
        ).isEqualTo(TemplateLogic.ErrorKind.TEMPLATE)
        assertThat(
            TemplateLogic.classifyError("No filter named 'bogus'.").kind,
        ).isEqualTo(TemplateLogic.ErrorKind.TEMPLATE)
    }

    @Test
    fun `auth and connectivity errors classify as connection`() {
        assertThat(
            TemplateLogic.classifyError("Home Assistant returned HTTP 401 for /api/template.").kind,
        ).isEqualTo(TemplateLogic.ErrorKind.CONNECTION)
        assertThat(
            TemplateLogic.classifyError("Server URL not configured.").kind,
        ).isEqualTo(TemplateLogic.ErrorKind.CONNECTION)
        assertThat(
            TemplateLogic.classifyError("failed to connect to ha.local").kind,
        ).isEqualTo(TemplateLogic.ErrorKind.CONNECTION)
        assertThat(
            TemplateLogic.classifyError("timeout").kind,
        ).isEqualTo(TemplateLogic.ErrorKind.CONNECTION)
    }

    @Test
    fun `connection markers win over an api-template mention`() {
        // The URL "/api/template" contains "template" but a 401 is a
        // connection problem, not a Jinja error.
        val c = TemplateLogic.classifyError(
            "Home Assistant returned HTTP 401 for /api/template after refresh.",
        )
        assertThat(c.kind).isEqualTo(TemplateLogic.ErrorKind.CONNECTION)
    }

    @Test
    fun `unrecognised error falls back to unknown but keeps its message`() {
        val c = TemplateLogic.classifyError("something odd happened")
        assertThat(c.kind).isEqualTo(TemplateLogic.ErrorKind.UNKNOWN)
        assertThat(c.message).isEqualTo("something odd happened")
    }

    @Test
    fun `classification is case-insensitive`() {
        assertThat(
            TemplateLogic.classifyError("TEMPLATESYNTAXERROR").kind,
        ).isEqualTo(TemplateLogic.ErrorKind.TEMPLATE)
        assertThat(
            TemplateLogic.classifyError("UNAUTHORIZED").kind,
        ).isEqualTo(TemplateLogic.ErrorKind.CONNECTION)
    }

    // ── headingFor ──────────────────────────────────────────────────────────

    @Test
    fun `headingFor maps each kind to a distinct label`() {
        assertThat(TemplateLogic.headingFor(TemplateLogic.ErrorKind.TEMPLATE)).isEqualTo("TEMPLATE ERROR")
        assertThat(TemplateLogic.headingFor(TemplateLogic.ErrorKind.CONNECTION)).isEqualTo("CONNECTION ERROR")
        assertThat(TemplateLogic.headingFor(TemplateLogic.ErrorKind.UNKNOWN)).isEqualTo("ERROR")
    }

    // ── examples ────────────────────────────────────────────────────────────

    @Test
    fun `examples include the canonical states call, now, and a states loop`() {
        val templates = TemplateLogic.examples.map { it.template }
        assertThat(templates).contains("{{ states('sun.sun') }}")
        assertThat(templates).contains("{{ now() }}")
        assertThat(templates.any { it.contains("for ") && it.contains("states.") }).isTrue()
    }

    @Test
    fun `every example has a non-blank label and a jinja delimiter`() {
        assertThat(TemplateLogic.examples).isNotEmpty()
        for (e in TemplateLogic.examples) {
            assertThat(e.label).isNotEmpty()
            assertThat(e.template.contains("{{") || e.template.contains("{%")).isTrue()
        }
    }
}
