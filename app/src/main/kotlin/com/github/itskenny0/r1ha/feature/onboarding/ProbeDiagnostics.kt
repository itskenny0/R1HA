package com.github.itskenny0.r1ha.feature.onboarding

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/**
 * Pure mappers from probe failures to messages the user can actually act on.
 * Split out of OnboardingViewModel for the same reason as [normalizeServerUrl]:
 * the mapping is string-in / string-out and deserves direct unit tests without
 * the VM's coroutine + lifecycle harness. Tests in ProbeDiagnosticsTest.
 *
 * Two failure shapes reach the user from a probe:
 *  - the transport threw ([probeFailureMessage]): DNS, route, TLS, timeout;
 *  - the server answered, but with a status that means "this isn't a usable
 *    Home Assistant login page" ([probeStatusProblem]): a 404 from some other
 *    web server on the same host, a reverse proxy demanding its own auth, an
 *    HA that's mid-restart and 500ing.
 *
 * Both frame the problem as "couldn't connect", never "login failed": auth
 * only happens later in the WebView, so blaming credentials here would send
 * the user down the wrong debugging path.
 */
internal fun probeFailureMessage(e: Throwable): String {
    val detail = e.message ?: e.javaClass.simpleName
    return when {
        e is UnknownHostException ->
            "Couldn't find that host. Check the address (and that you're on the same network as Home Assistant): $detail"
        // TLS failures get their own branch because the raw JDK text ("Trust
        // anchor for certification path not found") is gibberish to the
        // self-signed-cert crowd this mostly hits, and the fix (use the
        // http:// LAN address, or a trusted cert) is concrete.
        e is SSLException || e is CertificateException || e.cause is CertificateException ->
            "Secure connection failed: this device doesn't trust the server's certificate. " +
                "Self-signed setups usually work over the http:// LAN address instead: $detail"
        e is ConnectException || e is SocketTimeoutException ->
            "Couldn't reach the server. It may be offline, or the port/protocol may be wrong: $detail"
        else ->
            "Couldn't connect to the server: $detail"
    }
}

/**
 * Null when [code] is a status the OAuth flow can proceed past (HA serves
 * /auth/authorize with 200; a redirect is fine, the WebView follows it).
 * Otherwise a message explaining what answered and what to check, so a probe
 * that "succeeds" against the wrong web server fails here with a real hint
 * instead of stranding the user on an error page inside the WebView.
 */
internal fun probeStatusProblem(code: Int): String? = when {
    code in 200..399 -> null
    code == 401 || code == 403 ->
        "The server answered but refused the login page (HTTP $code). A reverse proxy " +
            "with its own authentication can block Home Assistant's login; try the " +
            "address you normally use in a browser."
    code == 404 ->
        "The server answered but has no Home Assistant login page (HTTP 404). Check that " +
            "the address points at Home Assistant itself, not another web server on the same host."
    code >= 500 ->
        "The server answered with an error (HTTP $code). Home Assistant may still be " +
            "starting up; try again in a minute."
    else ->
        "The server answered with HTTP $code, which doesn't look like a Home Assistant " +
            "login page. Double-check the address."
}
