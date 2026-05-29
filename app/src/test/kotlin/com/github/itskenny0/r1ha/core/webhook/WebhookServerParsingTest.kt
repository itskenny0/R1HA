package com.github.itskenny0.r1ha.core.webhook

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import org.junit.Test

/**
 * Unit tests for [WebhookServer]'s pure request-parsing helpers. These are the
 * byte-level read paths extracted out of the socket loop so they can be exercised
 * without a real ServerSocket:
 *
 *  - [WebhookServer.readHeaderLine] reads CRLF-delimited ASCII (request line + headers).
 *  - [WebhookServer.readBody] reads exactly Content-Length BYTES and decodes UTF-8.
 *
 * The body test is the regression guard for the original bug: the old code read into
 * a CharArray sized by the byte count, so a multibyte-UTF-8 body never satisfied the
 * read loop and blocked until the socket timeout (and could truncate).
 */
class WebhookServerParsingTest {

    @Test
    fun `readHeaderLine strips CRLF and returns the line`() {
        val input = ByteArrayInputStream("POST /webhook/abc HTTP/1.1\r\n".toByteArray(Charsets.US_ASCII))
        assertThat(WebhookServer.readHeaderLine(input)).isEqualTo("POST /webhook/abc HTTP/1.1")
    }

    @Test
    fun `readHeaderLine tolerates bare LF`() {
        val input = ByteArrayInputStream("Host: example\n".toByteArray(Charsets.US_ASCII))
        assertThat(WebhookServer.readHeaderLine(input)).isEqualTo("Host: example")
    }

    @Test
    fun `readHeaderLine returns empty string for the blank header-terminator line`() {
        val input = ByteArrayInputStream("\r\n".toByteArray(Charsets.US_ASCII))
        assertThat(WebhookServer.readHeaderLine(input)).isEqualTo("")
    }

    @Test
    fun `readHeaderLine returns null at clean end of stream`() {
        val input = ByteArrayInputStream(ByteArray(0))
        assertThat(WebhookServer.readHeaderLine(input)).isNull()
    }

    @Test
    fun `readHeaderLine reads consecutive lines leaving the cursor at the body`() {
        val raw = "POST /webhook/abc HTTP/1.1\r\nContent-Length: 5\r\n\r\nhello"
        val input = ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8))
        assertThat(WebhookServer.readHeaderLine(input)).isEqualTo("POST /webhook/abc HTTP/1.1")
        assertThat(WebhookServer.readHeaderLine(input)).isEqualTo("Content-Length: 5")
        assertThat(WebhookServer.readHeaderLine(input)).isEqualTo("")
        // Cursor now sits exactly at the body; remaining bytes are the payload.
        assertThat(input.readBytes().toString(Charsets.UTF_8)).isEqualTo("hello")
    }

    @Test
    fun `readBody decodes a plain ASCII body of exactly Content-Length bytes`() {
        val body = "{\"a\":1}"
        val bytes = body.toByteArray(Charsets.UTF_8)
        val input = ByteArrayInputStream(bytes)
        assertThat(WebhookServer.readBody(input, bytes.size)).isEqualTo(body)
    }

    @Test
    fun `readBody reads multibyte UTF-8 by BYTE count not char count`() {
        // Each of these is multibyte in UTF-8: é (2 bytes), 日本語 (3 bytes each),
        // emoji (4 bytes). The char count is far smaller than the byte count, which
        // is exactly the case the old CharArray(contentLength) read loop mishandled.
        val body = "café 日本語 🚀"
        val bytes = body.toByteArray(Charsets.UTF_8)
        assertThat(bytes.size).isGreaterThan(body.length)
        val input = ByteArrayInputStream(bytes)
        val result = WebhookServer.readBody(input, bytes.size)
        assertThat(result).isEqualTo(body)
        // The whole stream was consumed: no leftover, no truncation.
        assertThat(input.read()).isEqualTo(-1)
    }

    @Test
    fun `readBody stops early without hanging if the stream ends short`() {
        val partial = "hi".toByteArray(Charsets.UTF_8)
        val input = ByteArrayInputStream(partial)
        // Claim more bytes than are present; readBody must return what arrived
        // rather than spin (the socket read-timeout is the real-world backstop).
        assertThat(WebhookServer.readBody(input, 100)).isEqualTo("hi")
    }

    @Test
    fun `readBody returns empty for non-positive length`() {
        val input = ByteArrayInputStream("ignored".toByteArray(Charsets.UTF_8))
        assertThat(WebhookServer.readBody(input, 0)).isEqualTo("")
        assertThat(WebhookServer.readBody(input, -1)).isEqualTo("")
    }
}
