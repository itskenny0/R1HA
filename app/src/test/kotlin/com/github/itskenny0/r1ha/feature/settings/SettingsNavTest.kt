package com.github.itskenny0.r1ha.feature.settings

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SettingsNavTest {

    // ── Tree shape ────────────────────────────────────────────────────────

    @Test
    fun `ROOT is the only node without a parent`() {
        val rootless = SettingsNode.entries.filter { it.parent == null }
        assertThat(rootless).containsExactly(SettingsNode.ROOT)
    }

    @Test
    fun `every parent chain terminates at ROOT`() {
        SettingsNode.entries.forEach { node ->
            var n: SettingsNode? = node
            var hops = 0
            while (n != null && n != SettingsNode.ROOT) {
                n = n.parent
                hops++
                check(hops < 16) { "cycle or runaway chain at $node" }
            }
            assertThat(n).isEqualTo(SettingsNode.ROOT)
        }
    }

    @Test
    fun `tree reaches four levels deep`() {
        // ROOT(0) -> Appearance(1) -> Cards(2) -> Value bar & pip(3)
        assertThat(SettingsNode.ROOT.depth).isEqualTo(0)
        assertThat(SettingsNode.APPEARANCE.depth).isEqualTo(1)
        assertThat(SettingsNode.APPEARANCE_CARDS.depth).isEqualTo(2)
        assertThat(SettingsNode.APPEARANCE_CARDS_VALUEBAR.depth).isEqualTo(3)
        assertThat(SettingsNode.entries.maxOf { it.depth }).isEqualTo(3)
    }

    @Test
    fun `every node has a non-blank title`() {
        SettingsNode.entries.forEach { assertThat(it.title).isNotEmpty() }
    }

    // ── Back-stack ────────────────────────────────────────────────────────

    @Test
    fun `default back-stack sits at ROOT`() {
        val stack = SettingsBackStack()
        assertThat(stack.current).isEqualTo(SettingsNode.ROOT)
        assertThat(stack.atRoot).isTrue()
    }

    @Test
    fun `push descends and pop ascends exactly one level`() {
        var stack = SettingsBackStack()
        stack = stack.push(SettingsNode.APPEARANCE)
        stack = stack.push(SettingsNode.APPEARANCE_CARDS)
        assertThat(stack.current).isEqualTo(SettingsNode.APPEARANCE_CARDS)
        assertThat(stack.atRoot).isFalse()

        val popped = stack.pop()
        assertThat(popped).isInstanceOf(PopResult.Popped::class.java)
        val after = (popped as PopResult.Popped).stack
        assertThat(after.current).isEqualTo(SettingsNode.APPEARANCE)
    }

    @Test
    fun `pop at ROOT reports Exit`() {
        val stack = SettingsBackStack()
        assertThat(stack.pop()).isEqualTo(PopResult.Exit)
    }

    @Test
    fun `pushing the current node is a no-op`() {
        val stack = SettingsBackStack().push(SettingsNode.INPUT)
        val again = stack.push(SettingsNode.INPUT)
        assertThat(again.path).isEqualTo(stack.path)
    }

    @Test
    fun `back-stack must be rooted at ROOT`() {
        try {
            SettingsBackStack(listOf(SettingsNode.APPEARANCE))
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    // ── Focus deep-link mapping ───────────────────────────────────────────

    @Test
    fun `focus paths are rooted and lead to the right node`() {
        assertThat(focusPathForSection("SERVER")).isEqualTo(
            listOf(SettingsNode.ROOT, SettingsNode.CONNECTION),
        )
        assertThat(focusPathForSection("CARD UI").last()).isEqualTo(SettingsNode.APPEARANCE_CARDS)
        assertThat(focusPathForSection("SCROLL WHEEL").last()).isEqualTo(SettingsNode.INPUT_WHEEL)
        focusPathForSection("DASHBOARD").let { path ->
            assertThat(path.first()).isEqualTo(SettingsNode.ROOT)
            assertThat(path.last()).isEqualTo(SettingsNode.DASHBOARD)
        }
    }

    @Test
    fun `every section name the registry maps yields a deep focus path`() {
        // sectionNameForCategory covers every SettingCategory; each should
        // resolve to a path that drills past ROOT so a deep link actually moves.
        com.github.itskenny0.r1ha.core.prefs.SettingCategory.entries.forEach { cat ->
            val section = sectionNameForCategory(cat)
            assertThat(focusPathForSection(section).size).isGreaterThan(1)
        }
    }

    @Test
    fun `unknown section falls back to ROOT only`() {
        assertThat(focusPathForSection("NONSENSE")).isEqualTo(listOf(SettingsNode.ROOT))
    }

    // ── Summary formatting ────────────────────────────────────────────────

    @Test
    fun `prettyEnumName title-cases an underscore enum name`() {
        assertThat(prettyEnumName("PRAGMATIC_HYBRID")).isEqualTo("Pragmatic hybrid")
        assertThat(prettyEnumName("RAW")).isEqualTo("Raw")
    }

    @Test
    fun `nightWindowSummary renders an arrow range`() {
        assertThat(nightWindowSummary(22, 6)).isEqualTo("22:00 → 6:00")
    }
}
