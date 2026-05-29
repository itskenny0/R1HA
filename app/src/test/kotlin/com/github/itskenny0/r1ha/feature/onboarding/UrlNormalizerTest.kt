package com.github.itskenny0.r1ha.feature.onboarding

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Locale

class UrlNormalizerTest {

    // ──────────────────────────────────────────────────────────────────────────────
    // Trim / empty handling
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `blank input stays blank`() {
        assertThat(normalizeServerUrl("")).isEqualTo("")
        assertThat(normalizeServerUrl("   ")).isEqualTo("")
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertThat(normalizeServerUrl("  192.168.1.10  ")).isEqualTo("http://192.168.1.10:8123")
    }

    @Test
    fun `a single trailing slash is stripped`() {
        assertThat(normalizeServerUrl("http://homeassistant.local:8123/"))
            .isEqualTo("http://homeassistant.local:8123")
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Explicit protocol is respected verbatim
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `https stays https even on a private IP`() {
        // A user running HA behind a local TLS proxy gets to keep that choice.
        assertThat(normalizeServerUrl("https://192.168.1.10:8123"))
            .isEqualTo("https://192.168.1.10:8123")
    }

    @Test
    fun `http stays http even on a public host`() {
        // If the user explicitly typed http for a public host, don't second-guess.
        assertThat(normalizeServerUrl("http://ha.example.com:8123"))
            .isEqualTo("http://ha.example.com:8123")
    }

    @Test
    fun `mixed-case scheme is recognised`() {
        assertThat(normalizeServerUrl("HTTP://192.168.1.10:8123"))
            .isEqualTo("HTTP://192.168.1.10:8123")
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Protocol inference for unprefixed inputs
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `public DNS host gets https`() {
        assertThat(normalizeServerUrl("ha.example.com")).isEqualTo("https://ha.example.com")
    }

    @Test
    fun `nabu casa cloud host gets https`() {
        assertThat(normalizeServerUrl("my-instance.ui.nabu.casa"))
            .isEqualTo("https://my-instance.ui.nabu.casa")
    }

    @Test
    fun `mDNS host gets http`() {
        assertThat(normalizeServerUrl("homeassistant.local"))
            .isEqualTo("http://homeassistant.local:8123")
    }

    @Test
    fun `localhost gets http`() {
        assertThat(normalizeServerUrl("localhost"))
            .isEqualTo("http://localhost:8123")
    }

    @Test
    fun `bare hostname without dots gets http`() {
        // e.g. a Docker container hostname or `hassio` on the LAN
        assertThat(normalizeServerUrl("hassio"))
            .isEqualTo("http://hassio:8123")
    }

    @Test
    fun `192_168 IP gets http`() {
        assertThat(normalizeServerUrl("192.168.1.10"))
            .isEqualTo("http://192.168.1.10:8123")
    }

    @Test
    fun `10-block IP gets http`() {
        assertThat(normalizeServerUrl("10.0.0.5"))
            .isEqualTo("http://10.0.0.5:8123")
    }

    @Test
    fun `172_16_0_0 to 172_31_255_255 IPs get http`() {
        // RFC 1918 boundary cases: 172.15.* and 172.32.* are NOT private.
        assertThat(normalizeServerUrl("172.16.0.1")).isEqualTo("http://172.16.0.1:8123")
        assertThat(normalizeServerUrl("172.31.255.254")).isEqualTo("http://172.31.255.254:8123")
        assertThat(normalizeServerUrl("172.15.0.1")).isEqualTo("https://172.15.0.1")
        assertThat(normalizeServerUrl("172.32.0.1")).isEqualTo("https://172.32.0.1")
    }

    @Test
    fun `127 loopback IP gets http`() {
        assertThat(normalizeServerUrl("127.0.0.1")).isEqualTo("http://127.0.0.1:8123")
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Default-port handling
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `explicit port is preserved`() {
        assertThat(normalizeServerUrl("192.168.1.10:9999"))
            .isEqualTo("http://192.168.1.10:9999")
    }

    @Test
    fun `default port is added only on http`() {
        // http → 8123 default
        assertThat(normalizeServerUrl("http://192.168.1.10"))
            .isEqualTo("http://192.168.1.10:8123")
        // https → leave alone (public deployments use :443 implicitly)
        assertThat(normalizeServerUrl("https://ha.example.com"))
            .isEqualTo("https://ha.example.com")
    }

    @Test
    fun `explicit port survives autocorrection`() {
        assertThat(normalizeServerUrl("http://192.168.1.10:443"))
            .isEqualTo("http://192.168.1.10:443")
    }

    @Test
    fun `uppercase scheme casing is preserved when the default port is added`() {
        // A typed HTTP:// without a port must keep its casing, not be silently
        // downcased to http:// while gaining the :8123 default.
        assertThat(normalizeServerUrl("HTTP://192.168.1.10"))
            .isEqualTo("HTTP://192.168.1.10:8123")
    }

    @Test
    fun `explicit-protocol URL with a path is preserved verbatim`() {
        // The user with HA reverse-proxied at /ha needs us to NOT insert :8123 in
        // the middle of their URL. Explicit http:// means hands-off.
        assertThat(normalizeServerUrl("http://example.com/ha"))
            .isEqualTo("http://example.com/ha")
    }

    @Test
    fun `bare local host with a path infers http but does not insert a port`() {
        // The presence of a path implies a reverse-proxy setup; we don't want to
        // jam :8123 between host and path and break it. Protocol inference still
        // runs.
        assertThat(normalizeServerUrl("192.168.1.10/lovelace"))
            .isEqualTo("http://192.168.1.10/lovelace")
    }

    @Test
    fun `bare public host with a path infers https and keeps the path`() {
        // Public DNS + path → https on implicit :443, path preserved.
        assertThat(normalizeServerUrl("ha.example.com/dashboard"))
            .isEqualTo("https://ha.example.com/dashboard")
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Locale-invariant host folding (Turkish dotted-I)
    //
    // The protocol heuristic folds the host to lower case before comparing it against
    // "localhost", ".local", etc. Under the Turkish locale the default lowercase()
    // maps ASCII 'I' to the dotless 'ı', so a host whose dotted-I participates in a
    // comparison could fold differently and flip http/https. UrlNormalizer pins the
    // fold to Locale.ROOT; these tests lock in that the verdict is the same regardless
    // of the JVM's default locale so a future-added literal containing 'i' stays safe.
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `mDNS host with a dotted-I folds the same under the Turkish locale`() {
        // "HASSIO.local" contains an 'I'; the .local suffix must still route to http
        // whether the JVM default locale lowercases with the Turkish or root rules.
        val expected = normalizeServerUrl("HASSIO.local")
        withDefaultLocale(Locale.forLanguageTag("tr-TR")) {
            assertThat(normalizeServerUrl("HASSIO.local")).isEqualTo(expected)
        }
        assertThat(expected).isEqualTo("http://HASSIO.local:8123")
    }

    @Test
    fun `public host with a dotted-I folds the same under the Turkish locale`() {
        // A public DNS host carrying an 'I' must resolve to https identically under
        // either locale; the fold is pinned to Locale.ROOT inside the normaliser.
        val expected = normalizeServerUrl("ISP-PORTAL.example.com")
        withDefaultLocale(Locale.forLanguageTag("tr-TR")) {
            assertThat(normalizeServerUrl("ISP-PORTAL.example.com")).isEqualTo(expected)
        }
        assertThat(expected).isEqualTo("https://ISP-PORTAL.example.com")
    }

    private inline fun withDefaultLocale(locale: Locale, block: () -> Unit) {
        val previous = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            block()
        } finally {
            Locale.setDefault(previous)
        }
    }
}
