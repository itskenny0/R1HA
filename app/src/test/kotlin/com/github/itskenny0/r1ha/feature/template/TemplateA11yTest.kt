package com.github.itskenny0.r1ha.feature.template

import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TemplateA11yTest {

    @BeforeEach
    fun setLocale() {
        Locale.setDefault(Locale.US)
    }

    @Test
    fun `editor label is descriptive`() {
        assertThat(TemplateA11y.editorLabel()).contains("Jinja2")
        assertThat(TemplateA11y.editorLabel()).contains("render")
    }

    @Test
    fun `example chip label names the example and says it renders`() {
        assertThat(TemplateA11y.exampleChipLabel("Sun state"))
            .isEqualTo("Insert and render example Sun state")
    }

    @Test
    fun `blank example chip label falls back`() {
        assertThat(TemplateA11y.exampleChipLabel("   "))
            .isEqualTo("Insert and render example example")
    }

    @Test
    fun `auto toggle label reflects state in words`() {
        assertThat(TemplateA11y.autoToggleLabel(false)).startsWith("Auto render off")
        assertThat(TemplateA11y.autoToggleLabel(true)).startsWith("Auto render on")
    }

    @Test
    fun `live toggle label reflects state in words`() {
        assertThat(TemplateA11y.liveToggleLabel(false)).startsWith("Live render off")
        assertThat(TemplateA11y.liveToggleLabel(true)).startsWith("Live render on")
    }

    @Test
    fun `result label merges heading and body`() {
        assertThat(TemplateA11y.resultLabel("RENDERED", "42"))
            .isEqualTo("RENDERED. 42")
    }

    @Test
    fun `result label includes error body verbatim so it is announced`() {
        val err = "TemplateSyntaxError: unexpected end of template"
        assertThat(TemplateA11y.resultLabel("TEMPLATE ERROR", err))
            .isEqualTo("TEMPLATE ERROR. $err")
    }

    @Test
    fun `result label drops empty body`() {
        assertThat(TemplateA11y.resultLabel("RENDERED", "   ")).isEqualTo("RENDERED")
    }

    @Test
    fun `result label falls back on blank heading`() {
        assertThat(TemplateA11y.resultLabel("  ", "x")).isEqualTo("Result. x")
    }

    @Test
    fun `recent row label includes the template`() {
        assertThat(TemplateA11y.recentRowLabel("{{ now() }}"))
            .isEqualTo("Recent template {{ now() }}. Tap to load and render.")
    }

    @Test
    fun `blank recent row label falls back`() {
        assertThat(TemplateA11y.recentRowLabel("   "))
            .isEqualTo("Recent template. Tap to load and render.")
    }
}
