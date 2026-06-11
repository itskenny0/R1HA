package com.github.itskenny0.r1ha.core.ha

/**
 * Builds the JavaScript that seeds HA's frontend session into a WebView.
 *
 * HA's frontend bootstraps auth from the `hassTokens` localStorage key: hawsjs'
 * getAuth parses it and accepts it when `data.hassUrl` equals
 * `${location.protocol}//${location.host}` exactly. Three subtleties make a
 * naive `if (!getItem) setItem` injection unreliable, all observed on device:
 *
 *  1. After a failed connection the frontend calls saveTokens(null), which
 *     writes the literal string "null" into the key. A presence check treats
 *     that as a real envelope and never re-injects, so one bad connect poisons
 *     every subsequent open.
 *  2. The app's own WebSocket stays authenticated long after the stored access
 *     token expires, so the value we inject can be hours dead while the app
 *     looks connected. Stamping a fabricated future `expires` on it stops the
 *     frontend from refreshing first, so it connects with the dead token, gets
 *     ERR_INVALID_AUTH, and wipes the envelope (see 1).
 *  3. evaluateJavascript at onPageStarted races the page's own scripts; on a
 *     cached load the frontend can read localStorage before the injection
 *     runs. Callers should ALSO register this script via
 *     WebViewCompat.addDocumentStartJavaScript where supported, which is
 *     guaranteed to run before any page script.
 *
 * The script therefore validates whatever is stored (parse, hassUrl match,
 * non-expired) and replaces anything invalid, then returns a JSON readback
 * describing what it found and did, so shipped logs show the actual decision
 * instead of a bare presence boolean.
 */
object HassTokensInjection {

    /** Sentinel expiry [com.github.itskenny0.r1ha.core.prefs.Tokens] uses for
     *  long-lived access tokens, which have no refresh token to renew with. */
    private const val NO_EXPIRY = Long.MAX_VALUE

    /** Envelope expiry for long-lived access tokens: far enough out that the
     *  frontend never attempts a refresh (it has no refresh token to use), but
     *  a real epoch value so the arithmetic stays sane. Ten years, matching
     *  HA's own LLAT lifetime. */
    private const val LLAT_VALIDITY_MILLIS = 10L * 365 * 24 * 60 * 60 * 1000

    /**
     * The `expires` epoch-millis to write into the envelope. The stored expiry
     * is passed through UNCHANGED even when already in the past: hawsjs checks
     * `Date.now() > expires` before connecting and refreshes first when stale,
     * and that refresh succeeds because the envelope carries the app's real
     * clientId and refresh token. A fabricated future expiry would instead
     * connect with the dead token and get the envelope wiped.
     */
    fun envelopeExpiry(expiresAtMillis: Long, nowMillis: Long): Long =
        if (expiresAtMillis == NO_EXPIRY) nowMillis + LLAT_VALIDITY_MILLIS else expiresAtMillis

    /**
     * The guarded seed script. Validates the stored envelope and overwrites it
     * unless it is a parseable token set for THIS host that hasn't expired.
     * (A live foreign-token case is a frontend-side refresh of our own session
     * or a manual in-WebView login; both are healthier than ours, keep them.)
     *
     * The final expression is a JSON readback string so evaluateJavascript
     * callers can log the decision: `state` is what was found (absent / wiped /
     * unparseable / foreign / stale / ours / frontend), `injected` whether this
     * run replaced it.
     */
    fun buildScript(
        accessToken: String,
        refreshToken: String?,
        expiresAtMillis: Long,
        nowMillis: Long,
    ): String {
        val expires = envelopeExpiry(expiresAtMillis, nowMillis)
        return """
            (function () {
              var want = {
                access_token: ${jsString(accessToken)},
                token_type: "Bearer",
                expires_in: 1800,
                refresh_token: ${jsString(refreshToken ?: "")},
                hassUrl: location.protocol + "//" + location.host,
                clientId: ${jsString(HA_OAUTH_CLIENT_ID)},
                expires: $expires
              };
              var raw = null;
              try { raw = localStorage.getItem("hassTokens"); } catch (e) {}
              var state = "absent";
              var keep = false;
              if (raw) {
                try {
                  var d = JSON.parse(raw);
                  if (!d) {
                    state = "wiped";
                  } else if (!d.access_token || d.hassUrl !== want.hassUrl) {
                    state = "foreign";
                  } else if (typeof d.expires === "number" && d.expires < Date.now()) {
                    state = "stale";
                  } else {
                    state = d.access_token === want.access_token ? "ours" : "frontend";
                    keep = true;
                  }
                } catch (e) { state = "unparseable"; }
              }
              if (!keep) {
                try { localStorage.setItem("hassTokens", JSON.stringify(want)); } catch (e) {}
              }
              return JSON.stringify({
                state: state,
                injected: !keep,
                origin: want.hassUrl,
                expMin: Math.round((want.expires - Date.now()) / 60000)
              });
            })();
        """.trimIndent()
    }

    /**
     * Hardening shim for an upstream HA frontend bug, registered at document
     * start alongside the token script. ha-menu-button's willUpdate guards the
     * OLD hass with `?.` but dereferences `this.hass.kioskMode` bare, so any
     * update that runs before `hass` is first assigned throws an uncaught
     * TypeError inside lit's async update — observed from the device as
     * `reading 'kioskMode' of undefined` on every cold panel open, with the
     * panel content wedged invisible behind a drawn header. Narrow viewports
     * (the R1, phones) are exactly where the element composes early, which is
     * why desktop browsers never see it. The patch defers the element's
     * willUpdate until hass exists; lit re-runs it when hass is assigned.
     */
    fun hardenFrontendScript(): String = """
        (function () {
          try {
            customElements.whenDefined("ha-menu-button").then(function () {
              var proto = customElements.get("ha-menu-button").prototype;
              var orig = proto.willUpdate;
              if (typeof orig !== "function") return;
              proto.willUpdate = function (changed) {
                if (!this.hass) return;
                return orig.call(this, changed);
              };
            });
          } catch (e) { /* never break the page over a missing shim */ }
        })();
    """.trimIndent()

    /** Quote-and-escape a value for embedding in the script. The token alphabet
     *  is base64-ish (alnum + `-_./=`) so only quote + backslash bite; both are
     *  covered defensively. */
    fun jsString(raw: String): String =
        "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /**
     * The allowed-origin rule for addDocumentStartJavaScript, derived from the
     * configured server URL: scheme://host[:port], lowercased host, explicit
     * port kept. Null when the URL doesn't parse (caller skips registration
     * and relies on the onPageStarted fallback).
     */
    fun originRule(serverUrl: String): String? {
        val uri = runCatching { java.net.URI(serverUrl.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val port = uri.port
        return if (port == -1) "$scheme://$host" else "$scheme://$host:$port"
    }
}
