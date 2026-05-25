package com.github.itskenny0.r1ha.feature.lovelace

import android.annotation.SuppressLint
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import com.github.itskenny0.r1ha.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.TokenStore
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import kotlinx.coroutines.flow.first

/**
 * Lovelace WebView — the escape-hatch surface that hosts HA's own
 * frontend inside our app for everything we don't render natively
 * (custom HACS cards, the automation visual editor, the configuration
 * panel, the full Energy dashboard's bar charts, etc.).
 *
 * The HA Companion app is fundamentally a WebView wrapper around the
 * Lovelace frontend; we're the inverse — native first, WebView as a
 * fallback. This screen makes the WebView fallback an explicit,
 * navigable surface rather than relying on the user to launch a
 * separate browser via 'Open HA web UI' in Settings.
 *
 * Auth handoff: the page is loaded with the user's access token
 * pre-pasted into HA's `hassConnection` initial-state via a brief
 * JavaScript shim, mirroring the official Companion's auth handoff
 * pattern. Without this, the WebView would land on HA's login screen
 * and the user would have to OAuth a second time. Falls back to
 * loading the bare URL when the token isn't yet provisioned (e.g.
 * the user hasn't completed onboarding).
 */
@Composable
fun LovelaceScreen(
    settings: SettingsRepository,
    tokens: TokenStore,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    // Resolve the server URL + access token once; both come from
    // settings/tokens via produceState so the WebView only loads
    // once we have something to point it at.
    val serverUrl by produceState<String?>(null, settings) {
        value = runCatching { settings.settings.first().server?.url }.getOrNull()
    }
    // Pull both the access AND refresh tokens — the refresh token is
    // what lets HA's frontend keep its own session alive past the
    // access_token expiry (typically 30 min). Without it the user
    // would have to OAuth a second time inside the WebView after
    // half an hour.
    val tokenPair by produceState<Pair<String?, String?>?>(null, tokens) {
        value = runCatching {
            val t = tokens.load() ?: return@runCatching null to null
            t.accessToken to t.refreshToken.takeIf { it.isNotBlank() }
        }.getOrNull()
    }
    val accessToken = tokenPair?.first
    val refreshToken = tokenPair?.second
    var loading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val onBackState = rememberUpdatedState(onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        R1TopBar(title = "LOVELACE", onBack = onBack)
        val url = serverUrl
        if (url == null) {
            // Not signed in — same friendly empty-state as other
            // surfaces when the server isn't configured.
            Box(
                modifier = Modifier.fillMaxSize().padding(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No HA server configured. Sign in via Settings → SERVER first.",
                    style = R1.body,
                    color = R1.InkMuted,
                )
            }
            return@Column
        }
        Box(modifier = Modifier.fillMaxSize()) {
            LovelaceWebView(
                serverUrl = url,
                accessToken = accessToken,
                refreshToken = refreshToken,
                onLoadingChange = { loading = it },
                onError = { errorMessage = it },
                onBackHandled = { onBackState.value() },
                modifier = Modifier.fillMaxSize(),
            )
            if (loading) {
                // Spinner overlay during main-frame loads — same idiom
                // as OAuthWebView.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(R1.Bg.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = R1.AccentWarm,
                    )
                }
            }
            errorMessage?.let { msg ->
                Box(
                    modifier = Modifier
                        .padding(12.dp)
                        .clip(R1.ShapeS)
                        .background(R1.StatusRed.copy(alpha = 0.12f))
                        .border(1.dp, R1.StatusRed.copy(alpha = 0.4f), R1.ShapeS)
                        .r1Pressable(onClick = { errorMessage = null })
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(text = msg, style = R1.labelMicro, color = R1.StatusRed)
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LovelaceWebView(
    serverUrl: String,
    accessToken: String?,
    refreshToken: String?,
    onLoadingChange: (Boolean) -> Unit,
    onError: (String) -> Unit,
    onBackHandled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val webView = remember(context, serverUrl) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Mixed-content policy keyed to the base URL scheme. The previous
            // COMPATIBILITY_MODE blanket-allowed HTTP subresources on an HTTPS
            // page — a sub-resource downgrade attacker could swap a CDN-served
            // dashboard asset for an HTTP-served one and inject JS into the
            // HA frontend. Lock HTTPS bases to NEVER_ALLOW (HA's own assets are
            // all same-origin HTTPS so this never affects the legit flow), and
            // keep ALWAYS_ALLOW for HTTP bases (the entire load is already
            // plaintext so blocking mixed content would just break the page
            // without adding security).
            settings.mixedContentMode = if (serverUrl.startsWith("https://", ignoreCase = true)) {
                android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            } else {
                android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            // Honour the system / in-app dark theme. HA's frontend reads
            // prefers-color-scheme to flip its own theme tokens; without this
            // the embedded view always rendered light regardless of the
            // surrounding app theme. API 33+ feature; guarded with the support
            // check so older Androids get the existing behaviour.
            if (androidx.webkit.WebViewFeature.isFeatureSupported(
                    androidx.webkit.WebViewFeature.ALGORITHMIC_DARKENING,
                )
            ) {
                androidx.webkit.WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true)
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    onLoadingChange(true)
                    // Pre-paste the access token into localStorage so
                    // the frontend's hassConnection picks it up without
                    // a second OAuth round-trip. HA stores tokens
                    // under the 'hassTokens' key as a JSON envelope
                    // (access_token + token_type + expires_in +
                    // refresh_token); we synthesise a minimal one with
                    // a far-future expiry so the frontend doesn't try
                    // to refresh.
                    if (!accessToken.isNullOrBlank()) {
                        // Build the hassTokens envelope HA's frontend
                        // reads from localStorage on bootstrap. Passing
                        // refresh_token + a realistic expires_in lets
                        // the frontend's own refresh path kick in when
                        // the access token expires, so the user doesn't
                        // get bounced to a login screen after ~30 min.
                        //
                        // Guard with !localStorage.getItem('hassTokens')
                        // so we only inject on the FIRST page-start
                        // (before any frontend bootstrap). Without this
                        // we'd overwrite the frontend's freshly-
                        // refreshed tokens on every subsequent
                        // navigation, defeating the refresh flow.
                        val expiresAt = System.currentTimeMillis() + 30 * 60 * 1000
                        val script = "if (!localStorage.getItem('hassTokens')) { " +
                            "localStorage.setItem('hassTokens', " +
                            "JSON.stringify({" +
                            "access_token: ${jsString(accessToken)}," +
                            "token_type: 'Bearer'," +
                            "expires_in: 1800," +
                            "refresh_token: ${jsString(refreshToken ?: "")}," +
                            "hassUrl: ${jsString(serverUrl.trimEnd('/'))}," +
                            "clientId: null," +
                            "expires: $expiresAt" +
                            "})); }"
                        view.evaluateJavascript(script) { }
                    }
                }
                override fun onPageFinished(view: WebView, url: String) {
                    onLoadingChange(false)
                }
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    // Keep navigation inside HA's own host (the configured
                    // server URL); route every other link to the system
                    // browser. HACS market cards link to GitHub repos,
                    // integration docs link to home-assistant.io, etc.; we
                    // don't want those navigating inside the in-app WebView
                    // and trapping the user away from HA.
                    val target = request.url ?: return false
                    val configured = runCatching {
                        android.net.Uri.parse(serverUrl).host
                    }.getOrNull()
                    if (configured != null && target.host == configured) return false
                    return runCatching {
                        view.context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                target,
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                        true
                    }.getOrDefault(false)
                }
                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    if (!request.isForMainFrame) return
                    val desc = runCatching { error.description?.toString() }.getOrNull() ?: "error"
                    R1Log.w("Lovelace", "WebView error: $desc (${request.url})")
                    onError("WebView: $desc")
                    onLoadingChange(false)
                }
            }
            // Load the dashboard root. HA's frontend redirects to the
            // default Lovelace view at /lovelace; we just point at /
            // and let HA's own routing decide.
            loadUrl("${serverUrl.trimEnd('/')}/")
        }
    }

    // Tear down on disposal to avoid leaks. The WebView is a heavy
    // native peer — leaving it alive after the screen pops would
    // continue running the HA frontend in the background.
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }
    // System back navigates the WebView's history first; falls
    // through to the screen's onBack when there's no history left.
    BackHandler(enabled = true) {
        if (webView.canGoBack()) webView.goBack() else onBackHandled()
    }

    AndroidView(factory = { webView }, modifier = modifier)
}

/** Quote-and-escape a value for safe embedding in the JavaScript
 *  injection script. JSON.stringify would round-trip the token's
 *  hex characters fine, but we'd still need to wrap the result in
 *  string-quote delimiters; doing the escape ourselves keeps the
 *  injection compact. The token alphabet is base64-ish (alnum +
 *  `-_./=`) so the only escapes that bite are quote + backslash;
 *  we cover both defensively. */
private fun jsString(raw: String): String =
    "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

