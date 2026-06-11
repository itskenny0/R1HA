package com.github.itskenny0.r1ha.core.util

import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient

/**
 * WebChromeClient that routes the page's own console warnings and errors into
 * [R1Log], and from there into the log shipper. The embedded HA frontend fails
 * almost exclusively in-page (a custom panel's JS module 404s, an ingress
 * iframe refuses to attach, a lit render throws) and none of that is visible
 * to the Kotlin side otherwise — the WebView renders a header and goes quiet.
 *
 * Info/log/debug-level messages stay local: HA's frontend is chatty and would
 * crowd the shipped batches without adding signal. Messages are truncated to
 * keep a pathological repeated stack from flooding the queue.
 */
class ConsoleShippingChromeClient(private val tag: String) : WebChromeClient() {
    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
        val text = buildString {
            append(message.message().take(400))
            append(" (")
            // sourceId is the script URL; the last path segment is enough to
            // identify the chunk without shipping a full CDN-ish URL per line.
            append(message.sourceId()?.substringAfterLast('/') ?: "?")
            append(':')
            append(message.lineNumber())
            append(')')
        }
        when (message.messageLevel()) {
            ConsoleMessage.MessageLevel.ERROR -> R1Log.e(tag, "console: $text")
            ConsoleMessage.MessageLevel.WARNING -> R1Log.w(tag, "console: $text")
            else -> Unit
        }
        // false: let the WebView also print to logcat for local adb debugging.
        return false
    }
}
