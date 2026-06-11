package com.github.itskenny0.r1ha.core.ha

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HassTokensInjectionTest {

    private val now = 1_700_000_000_000L

    // -- envelopeExpiry ------------------------------------------------------

    @Test
    fun `real expiry passes through unchanged`() {
        val at = now + 12 * 60 * 1000L
        assertEquals(at, HassTokensInjection.envelopeExpiry(at, now))
    }

    @Test
    fun `past expiry passes through so the frontend refreshes before connecting`() {
        // Stamping a future expiry on a dead token makes the frontend connect
        // with it, fail, and wipe the envelope. The honest past value routes
        // it through refreshAccessToken first, which succeeds.
        val at = now - 60 * 60 * 1000L
        assertEquals(at, HassTokensInjection.envelopeExpiry(at, now))
    }

    @Test
    fun `LLAT sentinel maps to a far-future epoch instead of MAX_VALUE`() {
        val out = HassTokensInjection.envelopeExpiry(Long.MAX_VALUE, now)
        assertTrue(out > now + 365L * 24 * 60 * 60 * 1000)
        assertTrue(out < now + 11L * 365 * 24 * 60 * 60 * 1000)
    }

    // -- buildScript ---------------------------------------------------------

    @Test
    fun `script embeds tokens clientId and the real expiry`() {
        val script = HassTokensInjection.buildScript(
            accessToken = "AT123",
            refreshToken = "RT456",
            expiresAtMillis = now + 1000,
            nowMillis = now,
        )
        assertTrue(script.contains("\"AT123\""))
        assertTrue(script.contains("\"RT456\""))
        assertTrue(script.contains("\"$HA_OAUTH_CLIENT_ID\""))
        assertTrue(script.contains("expires: ${now + 1000}"))
        // hassUrl must be computed in page from location, never baked in.
        assertTrue(script.contains("location.protocol + \"//\" + location.host"))
    }

    @Test
    fun `script validates the stored envelope instead of a bare presence check`() {
        val script = HassTokensInjection.buildScript("a", "r", now, now)
        // The frontend writes the literal string "null" after wiping auth; a
        // presence-only guard mistakes that for a session and never re-seeds.
        assertTrue(script.contains("JSON.parse"))
        for (state in listOf("wiped", "foreign", "stale", "unparseable", "ours", "frontend")) {
            assertTrue("missing state \"$state\"", script.contains("\"$state\""))
        }
        assertFalse(script.contains("if (!localStorage.getItem"))
    }

    @Test
    fun `null refresh token serialises as empty string`() {
        val script = HassTokensInjection.buildScript("a", null, now, now)
        assertTrue(script.contains("refresh_token: \"\""))
    }

    @Test
    fun `script never embeds a quote-breaking token verbatim`() {
        val script = HassTokensInjection.buildScript("""a"b\c""", null, now, now)
        assertTrue(script.contains("""a\"b\\c"""))
    }

    // -- jsString ------------------------------------------------------------

    @Test
    fun `jsString escapes quotes and backslashes`() {
        assertEquals("\"plain\"", HassTokensInjection.jsString("plain"))
        assertEquals("\"a\\\"b\"", HassTokensInjection.jsString("a\"b"))
        assertEquals("\"a\\\\b\"", HassTokensInjection.jsString("a\\b"))
    }

    // -- originRule ----------------------------------------------------------

    @Test
    fun `origin rule drops paths and keeps explicit ports`() {
        assertEquals("https://ha.n8.gs", HassTokensInjection.originRule("https://ha.n8.gs"))
        assertEquals("https://ha.n8.gs", HassTokensInjection.originRule("https://ha.n8.gs/lovelace/0"))
        assertEquals(
            "http://192.168.1.5:8123",
            HassTokensInjection.originRule("http://192.168.1.5:8123/"),
        )
    }

    @Test
    fun `origin rule lowercases scheme and host`() {
        assertEquals("https://ha.example.com", HassTokensInjection.originRule("HTTPS://HA.Example.Com"))
    }

    @Test
    fun `origin rule is null for garbage`() {
        assertNull(HassTokensInjection.originRule("not a url"))
        assertNull(HassTokensInjection.originRule(""))
    }
}
