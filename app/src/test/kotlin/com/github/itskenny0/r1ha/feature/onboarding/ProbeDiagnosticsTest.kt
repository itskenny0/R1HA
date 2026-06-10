package com.github.itskenny0.r1ha.feature.onboarding

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException

class ProbeDiagnosticsTest {

    // ──────────────────────────────────────────────────────────────────────────────
    // probeFailureMessage: exception → actionable text
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `unknown host maps to a name-resolution hint`() {
        val msg = probeFailureMessage(UnknownHostException("homeassistant.lcal"))
        assertThat(msg).contains("Couldn't find that host")
        assertThat(msg).contains("same network")
        assertThat(msg).contains("homeassistant.lcal")
    }

    @Test
    fun `connection refused maps to an offline-or-wrong-port hint`() {
        val msg = probeFailureMessage(ConnectException("Connection refused"))
        assertThat(msg).contains("Couldn't reach the server")
        assertThat(msg).contains("port/protocol")
    }

    @Test
    fun `socket timeout maps to the same reachability hint`() {
        val msg = probeFailureMessage(SocketTimeoutException("timeout"))
        assertThat(msg).contains("Couldn't reach the server")
    }

    @Test
    fun `ssl handshake failure maps to a certificate hint, not raw trust-anchor text`() {
        // The raw JDK message ("Trust anchor for certification path not found")
        // must be framed, not surfaced as the headline.
        val msg = probeFailureMessage(
            SSLHandshakeException("Trust anchor for certification path not found"),
        )
        assertThat(msg).contains("Secure connection failed")
        assertThat(msg).contains("http://")
    }

    @Test
    fun `certificate exception wrapped as a cause still maps to the certificate hint`() {
        val wrapped = IOException("handshake failed").initCause(
            CertificateException("certificate expired"),
        )
        assertThat(probeFailureMessage(wrapped)).contains("Secure connection failed")
    }

    @Test
    fun `unknown exception falls back to a generic connect message with detail`() {
        val msg = probeFailureMessage(IllegalStateException("boom"))
        assertThat(msg).contains("Couldn't connect to the server")
        assertThat(msg).contains("boom")
    }

    @Test
    fun `message-less exception falls back to the class name as detail`() {
        assertThat(probeFailureMessage(IllegalStateException()))
            .contains("IllegalStateException")
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // probeStatusProblem: HTTP status → null (proceed) or message (fail with hint)
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `success and redirect statuses let the flow proceed`() {
        assertThat(probeStatusProblem(200)).isNull()
        assertThat(probeStatusProblem(204)).isNull()
        assertThat(probeStatusProblem(302)).isNull()
        assertThat(probeStatusProblem(308)).isNull()
    }

    @Test
    fun `401 and 403 blame an intercepting proxy, not credentials`() {
        for (code in intArrayOf(401, 403)) {
            val msg = probeStatusProblem(code)
            assertThat(msg).contains("HTTP $code")
            assertThat(msg).contains("reverse proxy")
            // Never frame the probe as a login failure: auth hasn't happened yet.
            assertThat(msg).doesNotContain("password")
        }
    }

    @Test
    fun `404 points at the wrong-web-server case`() {
        val msg = probeStatusProblem(404)
        assertThat(msg).contains("HTTP 404")
        assertThat(msg).contains("not another web server")
    }

    @Test
    fun `5xx suggests Home Assistant may still be starting`() {
        for (code in intArrayOf(500, 502, 503)) {
            val msg = probeStatusProblem(code)
            assertThat(msg).contains("HTTP $code")
            assertThat(msg).contains("starting up")
        }
    }

    @Test
    fun `other 4xx statuses get the generic doesn't-look-like-HA message`() {
        val msg = probeStatusProblem(418)
        assertThat(msg).contains("HTTP 418")
        assertThat(msg).contains("Double-check the address")
    }
}
