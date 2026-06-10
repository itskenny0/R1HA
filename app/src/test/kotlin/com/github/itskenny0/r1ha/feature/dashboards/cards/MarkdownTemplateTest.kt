package com.github.itskenny0.r1ha.feature.dashboards.cards

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test

/**
 * Unit tests for [MarkdownTemplate], the pure decision logic behind the markdown
 * card's render_template subscription: template detection, variable building,
 * cache keying, error-level selection, and show_empty hiding.
 */
class MarkdownTemplateTest {

    @Test
    fun `detects jinja templates`() {
        assertThat(MarkdownTemplate.looksTemplated("{{ states('sun.sun') }}")).isTrue()
        assertThat(MarkdownTemplate.looksTemplated("{% if x %}a{% endif %}")).isTrue()
        assertThat(MarkdownTemplate.looksTemplated("{# comment #}")).isTrue()
    }

    @Test
    fun `static text is not templated`() {
        assertThat(MarkdownTemplate.looksTemplated("# Just markdown")).isFalse()
    }

    @Test
    fun `variables include config and user`() {
        val config = buildJsonObject { put("type", JsonPrimitive("markdown")) }
        val vars = MarkdownTemplate.buildVariables(config, "Ada")
        assertThat(vars["config"]).isEqualTo(config)
        assertThat((vars["user"] as JsonPrimitive).content).isEqualTo("Ada")
    }

    @Test
    fun `null user omitted from variables`() {
        val config = buildJsonObject {}
        val vars = MarkdownTemplate.buildVariables(config, null)
        assertThat(vars.containsKey("user")).isFalse()
        assertThat(vars.containsKey("config")).isTrue()
    }

    @Test
    fun `cache key differs by content scope and user`() {
        val a = MarkdownTemplate.cacheKey("x", listOf("sensor.a"), "Ada")
        val b = MarkdownTemplate.cacheKey("x", listOf("sensor.b"), "Ada")
        val c = MarkdownTemplate.cacheKey("x", listOf("sensor.a"), "Bob")
        val d = MarkdownTemplate.cacheKey("y", listOf("sensor.a"), "Ada")
        assertThat(a).isNotEqualTo(b)
        assertThat(a).isNotEqualTo(c)
        assertThat(a).isNotEqualTo(d)
    }

    @Test
    fun `cache key stable for identical inputs`() {
        val a = MarkdownTemplate.cacheKey("x", listOf("sensor.a"), "Ada")
        val b = MarkdownTemplate.cacheKey("x", listOf("sensor.a"), "Ada")
        assertThat(a).isEqualTo(b)
    }

    private fun err(level: MarkdownTemplate.ErrorLevel) =
        MarkdownTemplate.Result.Failed("msg", level)

    @Test
    fun `first error is always taken`() {
        val incoming = err(MarkdownTemplate.ErrorLevel.WARNING)
        assertThat(MarkdownTemplate.selectError(null, incoming)).isEqualTo(incoming)
    }

    @Test
    fun `error overwrites warning`() {
        val existing = err(MarkdownTemplate.ErrorLevel.WARNING)
        val incoming = err(MarkdownTemplate.ErrorLevel.ERROR)
        assertThat(MarkdownTemplate.selectError(existing, incoming)).isEqualTo(incoming)
    }

    @Test
    fun `warning does not downgrade existing error`() {
        val existing = err(MarkdownTemplate.ErrorLevel.ERROR)
        val incoming = err(MarkdownTemplate.ErrorLevel.WARNING)
        assertThat(MarkdownTemplate.selectError(existing, incoming)).isEqualTo(existing)
    }

    @Test
    fun `show_empty false hides empty result`() {
        assertThat(MarkdownTemplate.shouldHide("", showEmpty = false)).isTrue()
    }

    @Test
    fun `show_empty false keeps non-empty result`() {
        assertThat(MarkdownTemplate.shouldHide("x", showEmpty = false)).isFalse()
    }

    @Test
    fun `unrendered result is never hidden`() {
        assertThat(MarkdownTemplate.shouldHide(null, showEmpty = false)).isFalse()
    }

    @Test
    fun `show_empty true never hides`() {
        assertThat(MarkdownTemplate.shouldHide("", showEmpty = true)).isFalse()
    }
}
