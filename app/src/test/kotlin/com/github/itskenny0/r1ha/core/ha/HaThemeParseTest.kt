package com.github.itskenny0.r1ha.core.ha

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class HaThemeParseTest {

    private fun parse(json: String): HaThemeCatalogue =
        parseHaThemeCatalogue(Json.parseToJsonElement(json))

    @Test fun `parses default_theme and default_dark_theme`() {
        val cat = parse("""
            {
              "default_theme": "my_theme",
              "default_dark_theme": "my_dark_theme",
              "themes": {}
            }
        """.trimIndent())
        assertThat(cat.defaultTheme).isEqualTo("my_theme")
        assertThat(cat.defaultDarkTheme).isEqualTo("my_dark_theme")
    }

    @Test fun `null default_dark_theme is treated as absent`() {
        val cat = parse("""
            {
              "default_theme": "default",
              "default_dark_theme": null,
              "themes": {}
            }
        """.trimIndent())
        assertThat(cat.defaultDarkTheme).isNull()
    }

    @Test fun `parses theme base vars and dark mode vars`() {
        val cat = parse("""
            {
              "default_theme": "T",
              "default_dark_theme": null,
              "themes": {
                "T": {
                  "primary-color": "#ff0000",
                  "accent-color": "#00ff00",
                  "modes": {
                    "dark": {
                      "primary-color": "#0000ff"
                    },
                    "light": {
                      "primary-color": "#ffaa00"
                    }
                  }
                }
              }
            }
        """.trimIndent())
        assertThat(cat.themes).hasSize(1)
        val entry = cat.themes["T"]!!
        assertThat(entry.vars["primary-color"]).isEqualTo("#ff0000")
        assertThat(entry.vars["accent-color"]).isEqualTo("#00ff00")
        assertThat(entry.darkVars!!["primary-color"]).isEqualTo("#0000ff")
        assertThat(entry.lightVars!!["primary-color"]).isEqualTo("#ffaa00")
        // modes key must not appear in base vars
        assertThat(entry.vars.containsKey("modes")).isFalse()
    }

    @Test fun `theme without modes has null dark and light vars`() {
        val cat = parse("""
            {
              "default_theme": "simple",
              "default_dark_theme": null,
              "themes": {
                "simple": {
                  "primary-color": "#aabbcc"
                }
              }
            }
        """.trimIndent())
        val entry = cat.themes["simple"]!!
        assertThat(entry.darkVars).isNull()
        assertThat(entry.lightVars).isNull()
    }

    @Test fun `null payload returns EMPTY`() {
        assertThat(parseHaThemeCatalogue(null)).isEqualTo(HaThemeCatalogue.EMPTY)
    }

    @Test fun `missing themes key returns empty map with correct defaults`() {
        val cat = parse("""
            {
              "default_theme": "myTheme",
              "default_dark_theme": null
            }
        """.trimIndent())
        assertThat(cat.themes).isEmpty()
        assertThat(cat.defaultTheme).isEqualTo("myTheme")
    }
}
