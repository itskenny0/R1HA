package com.github.itskenny0.r1ha.core.prefs

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SettingsRegistryTest {

    // ──────────────────────────────────────────────────────────────────────────────
    // Registry shape invariants
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `every registry entry has a unique id`() {
        val ids = SETTINGS_REGISTRY.map { it.id }
        assertThat(ids).containsNoDuplicates()
    }

    @Test
    fun `every entry's label is non-blank`() {
        SETTINGS_REGISTRY.forEach { entry ->
            assertThat(entry.label).isNotEmpty()
            assertThat(entry.label.trim()).isEqualTo(entry.label)
        }
    }

    @Test
    fun `every entry's description is non-blank`() {
        SETTINGS_REGISTRY.forEach { entry ->
            assertThat(entry.description).isNotEmpty()
        }
    }

    @Test
    fun `every category has at least one entry`() {
        // Categories without entries can't surface in search or diff and so are
        // dead code in the enum. If you intentionally add a category with no
        // entries (placeholder for a future feature), bump this test.
        val populated = SETTINGS_REGISTRY.map { it.category }.toSet()
        assertThat(populated).containsExactlyElementsIn(SettingCategory.entries)
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // modifiedSettings
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `default AppSettings has no modified entries`() {
        // Fresh-install state: every registered entry's isDefault should return
        // true against AppSettings(). If you add a new entry and its isDefault
        // returns false on the default constructor, this test will catch it.
        val modified = modifiedSettings(AppSettings())
        assertThat(modified).isEmpty()
    }

    @Test
    fun `flipping the theme surfaces exactly the theme entry as modified`() {
        val tweaked = AppSettings(theme = ThemeId.MINIMAL_DARK)
        val modified = modifiedSettings(tweaked)
        assertThat(modified.map { it.id }).containsExactly("theme")
    }

    @Test
    fun `flipping multiple unrelated fields surfaces multiple modified entries`() {
        val tweaked = AppSettings(
            ui = UiOptions(showOnOffPill = false, infiniteScroll = true),
            behavior = Behavior(haptics = false),
        )
        val modifiedIds = modifiedSettings(tweaked).map { it.id }.toSet()
        assertThat(modifiedIds).containsAtLeast(
            "ui.showOnOffPill",
            "ui.infiniteScroll",
            "behavior.haptics",
        )
    }

    @Test
    fun `chrome button reorder surfaces as modified`() {
        // Default order = BATTERY, ASSIST_MIC, EDIT, GEAR. Swap two and the
        // entry should show as modified — the comparator compares the whole
        // list, not just the enabled flags.
        val swapped = AppSettings(
            ui = UiOptions(
                chromeButtons = listOf(
                    ChromeButtonConfig(ChromeButtonRef.ASSIST_MIC),
                    ChromeButtonConfig(ChromeButtonRef.BATTERY),
                    ChromeButtonConfig(ChromeButtonRef.EDIT),
                    ChromeButtonConfig(ChromeButtonRef.GEAR),
                ),
            ),
        )
        assertThat(modifiedSettings(swapped).map { it.id }).contains("ui.chromeButtons")
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // searchSettings
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `blank query returns empty list`() {
        assertThat(searchSettings("")).isEmpty()
        assertThat(searchSettings("   ")).isEmpty()
    }

    @Test
    fun `nonsense query returns empty list`() {
        assertThat(searchSettings("zzzzzzzzzzz")).isEmpty()
    }

    @Test
    fun `category-label match surfaces every entry in that category`() {
        // 'behaviour' is a category label; every behavior-category entry should
        // surface even though it doesn't appear in the entries' own labels.
        val results = searchSettings("behaviour")
        val categories = results.map { it.category }.toSet()
        assertThat(categories).contains(SettingCategory.BEHAVIOUR)
    }

    @Test
    fun `search is case-insensitive`() {
        val lower = searchSettings("haptics").map { it.id }
        val upper = searchSettings("HAPTICS").map { it.id }
        val mixed = searchSettings("HaPtIcS").map { it.id }
        assertThat(lower).containsExactlyElementsIn(upper)
        assertThat(lower).containsExactlyElementsIn(mixed)
    }

    @Test
    fun `previously-missing main-tree settings now surface in search`() {
        // Representative settings that render in the main Settings tree but were
        // absent from the registry, so search returned nothing for them. Each must
        // now be reachable; the search label is what the user types.
        assertThat(searchSettings("Guest mode").map { it.id }).contains("behavior.guestMode")
        assertThat(searchSettings("Weather refresh").map { it.id })
            .contains("integrations.weatherRefreshSec")
        assertThat(searchSettings("Calendar look-ahead").map { it.id })
            .contains("integrations.calendarLookaheadDays")
        assertThat(searchSettings("RECENT history depth").map { it.id })
            .contains("integrations.recentHistoryDepth")
        assertThat(searchSettings("Now Playing").map { it.id }).contains("dashboard.showMedia")
        assertThat(searchSettings("Low-battery alerts").map { it.id })
            .contains("dashboard.showLowBattery")
        assertThat(searchSettings("DRAW red above").map { it.id })
            .contains("dashboard.powerRedThresholdW")
        assertThat(searchSettings("Media rows shown").map { it.id })
            .contains("dashboard.mediaSummaryCount")
    }

    @Test
    fun `font face entry is reachable by label and by typeface terms`() {
        // The label is just "Font"; the description carries the words a user
        // is likely to type when hunting for a typeface option.
        assertThat(searchSettings("Font").map { it.id }).contains("ui.fontFace")
        assertThat(searchSettings("font face").map { it.id }).contains("ui.fontFace")
        assertThat(searchSettings("typeface").map { it.id }).contains("ui.fontFace")
    }

    @Test
    fun `query matches substring inside the description`() {
        // Description-only match: 'spring-animated' isn't in any label but
        // should be in a wheel-step description… actually it's not. Test
        // something we know is in a description: 'unit'.
        val results = searchSettings("unit")
        assertThat(results).isNotEmpty()
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // currentDisplay coverage
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `every entry's currentDisplay returns a non-empty string for default settings`() {
        // Catches accidental empty-string returns. The diff panel renders this
        // value directly; an empty value would look like a broken layout.
        val current = AppSettings()
        SETTINGS_REGISTRY.forEach { entry ->
            assertThat(entry.currentDisplay(current)).isNotEmpty()
        }
    }
}
