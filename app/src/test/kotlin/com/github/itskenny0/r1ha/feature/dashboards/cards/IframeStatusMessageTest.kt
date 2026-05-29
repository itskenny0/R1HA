package com.github.itskenny0.r1ha.feature.dashboards.cards

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * Unit tests for [iframeStatusMessage], the pure helper that picks the
 * placeholder copy an iframe / webpage card shows when its url can't be
 * resolved into something the WebView can load. JVM-only (no Compose / Android)
 * so it runs under plain JUnit5.
 */
class IframeStatusMessageTest {

    @Test
    fun blankUrlReportsMissingAddress() {
        assertThat(iframeStatusMessage(null, "https://ha.example"))
            .isEqualTo("No web address set for this card.")
        assertThat(iframeStatusMessage("", "https://ha.example"))
            .isEqualTo("No web address set for this card.")
        assertThat(iframeStatusMessage("   ", "https://ha.example"))
            .isEqualTo("No web address set for this card.")
    }

    @Test
    fun relativePathWithoutServerReportsMissingServer() {
        assertThat(iframeStatusMessage("/local/panel.html", null))
            .isEqualTo("Set the Home Assistant server address to load this page.")
        assertThat(iframeStatusMessage("/local/panel.html", ""))
            .isEqualTo("Set the Home Assistant server address to load this page.")
        assertThat(iframeStatusMessage("  /local/panel.html  ", "   "))
            .isEqualTo("Set the Home Assistant server address to load this page.")
    }

    @Test
    fun unsupportedSchemeReportsGenericFailure() {
        // A relative path WITH a server resolves fine upstream, so the only inputs
        // that reach here with a non-empty, non-"/" value are unsupported schemes.
        assertThat(iframeStatusMessage("ftp://example.com/file", "https://ha.example"))
            .isEqualTo("Can't display this web address.")
        assertThat(iframeStatusMessage("javascript:alert(1)", "https://ha.example"))
            .isEqualTo("Can't display this web address.")
    }

    @Test
    fun copyHasNoEmDash() {
        val all = listOf(
            iframeStatusMessage(null, null),
            iframeStatusMessage("/local/x", null),
            iframeStatusMessage("ftp://x", "https://ha"),
        )
        val emDash = "—"
        all.forEach { assertThat(it).doesNotContain(emDash) }
    }

    @Test
    fun serverHintIsLocaleStable() {
        // Pins the Locale.US expectation the rest of the suite uses; guards against
        // a future locale-sensitive lowercasing surprise in the helper.
        val msg = iframeStatusMessage("/x", null)
        assertThat(msg.lowercase(Locale.US)).contains("server")
    }
}
