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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
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
import com.github.itskenny0.r1ha.core.ha.HassTokensInjection
import com.github.itskenny0.r1ha.core.ha.TokenRefresher
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
    refresher: TokenRefresher,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    // Resolve the server URL + access token once; both come from
    // settings/tokens via produceState so the WebView only loads
    // once we have something to point it at.
    val serverUrl by produceState<String?>(null, settings) {
        value = runCatching { settings.settings.first().server?.url }.getOrNull()
    }
    // Refresh BEFORE reading (see PanelViewerScreen): the stored access token
    // can be long expired while the app's own WS connection looks healthy, and
    // seeding a dead token gets the envelope wiped by the frontend. Then pull
    // both tokens plus the REAL expiry — the refresh token and true expiry are
    // what let HA's frontend keep its own session alive past the access-token
    // lifetime (typically 30 min) without bouncing to the login mask.
    val tokenInfo by produceState<Triple<String?, String?, Long>?>(null, tokens, refresher) {
        value = runCatching {
            refresher.ensureFresh()
            val t = tokens.load() ?: return@runCatching Triple(null, null, 0L)
            Triple(
                t.accessToken,
                t.refreshToken.takeIf { it.isNotBlank() },
                t.expiresAtMillis,
            )
        }.getOrNull()
    }
    val accessToken = tokenInfo?.first
    val refreshToken = tokenInfo?.second
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
        // Do NOT mount the WebView until the token load has RESOLVED: the
        // WebViewClient captures the token values at first composition (see
        // PanelViewerScreen for the full story); mounting early captured null
        // forever and the injection never ran.
        if (tokenInfo == null) {
            Box(
                modifier = Modifier.fillMaxSize().background(R1.Bg),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = R1.AccentWarm,
                )
            }
            return@Column
        }
        Box(modifier = Modifier.fillMaxSize()) {
            LovelaceWebView(
                serverUrl = url,
                accessToken = accessToken,
                refreshToken = refreshToken,
                tokenExpiresAtMillis = tokenInfo?.third ?: 0L,
                // A fresh main-frame load also clears a stale error so an
                // in-page retry / back navigation resets the overlay.
                onLoadingChange = {
                    loading = it
                    if (it) errorMessage = null
                },
                onError = { errorMessage = it },
                onBackHandled = { onBackState.value() },
                modifier = Modifier.fillMaxSize(),
            )
            if (loading) {
                // Opaque overlay during main-frame loads so the WebView's
                // blank pre-render never flashes through.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(R1.Bg),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = R1.AccentWarm,
                        )
                        Spacer(Modifier.size(R1.space.s))
                        Text(
                            text = "LOADING DASHBOARD",
                            style = R1.labelMicro,
                            color = R1.InkSoft,
                        )
                    }
                }
            } else {
                errorMessage?.let { msg ->
                    // Main-frame failure: same centred layout with the error in
                    // place of the loading label. No opaque background so the
                    // WebView stays visible (and usable) underneath for retry /
                    // back; tap the message to dismiss it.
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(R1.ShapeS)
                                .background(R1.Bg.copy(alpha = 0.85f))
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
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LovelaceWebView(
    serverUrl: String,
    accessToken: String?,
    refreshToken: String?,
    tokenExpiresAtMillis: Long,
    onLoadingChange: (Boolean) -> Unit,
    onError: (String) -> Unit,
    onBackHandled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Defense in depth for the stale-capture bug: read CURRENT token values,
    // not whatever the first composition captured.
    val liveAccessToken = rememberUpdatedState(accessToken)
    val liveRefreshToken = rememberUpdatedState(refreshToken)
    val liveExpiresAt = rememberUpdatedState(tokenExpiresAtMillis)
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
            // Primary injection path (see PanelViewerScreen): a document-start
            // script runs before ANY page script, so the frontend's auth
            // bootstrap can never race past it. Feature-gated; the
            // onPageStarted path below stays as the fallback and the logger.
            if (!accessToken.isNullOrBlank() &&
                androidx.webkit.WebViewFeature.isFeatureSupported(
                    androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT,
                )
            ) {
                val rule = HassTokensInjection.originRule(serverUrl)
                if (rule != null) {
                    androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                        this,
                        HassTokensInjection.buildScript(
                            accessToken = accessToken,
                            refreshToken = refreshToken,
                            expiresAtMillis = tokenExpiresAtMillis,
                            nowMillis = System.currentTimeMillis(),
                        ),
                        setOf(rule),
                    )
                } else {
                    R1Log.w("LovelaceScreen", "doc-start inject skipped: unparseable server url")
                }
            }
            // Same cold-start race fix as PanelViewerScreen: a fresh WebView's
            // frontend can check auth before the async injection runs and bounce
            // to the login mask; retry the target once after the injection
            // callback confirms the tokens landed.
            var authBounceDone = false
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
                    val accessToken = liveAccessToken.value
                    if (accessToken.isNullOrBlank()) {
                        R1Log.w("LovelaceScreen", "token inject skipped: no access token at page start url=$url")
                    }
                    if (!accessToken.isNullOrBlank()) {
                        // Same guarded script as the document-start registration
                        // (HassTokensInjection validates/repairs the stored
                        // envelope instead of a bare presence check — the
                        // frontend writes the literal string "null" after a
                        // failed connect). The readback ships in the logs:
                        // state=ours means the doc-start path already seeded
                        // it, injected=true means this run did.
                        val script = HassTokensInjection.buildScript(
                            accessToken = accessToken,
                            refreshToken = liveRefreshToken.value,
                            expiresAtMillis = liveExpiresAt.value,
                            nowMillis = System.currentTimeMillis(),
                        )
                        view.evaluateJavascript(script) { readback ->
                            R1Log.i("LovelaceScreen", "token inject: $readback url=$url")
                            if (!authBounceDone && url.contains("/auth/authorize")) {
                                authBounceDone = true
                                R1Log.i("LovelaceScreen", "auth race lost; retrying with seeded tokens")
                                view.post { view.loadUrl("${serverUrl.trimEnd('/')}/") }
                            }
                        }
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

