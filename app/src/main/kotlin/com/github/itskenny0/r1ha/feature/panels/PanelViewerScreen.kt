package com.github.itskenny0.r1ha.feature.panels

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
 * Authenticated WebView for a pinned HA sidebar panel (custom integrations, iframe
 * panels, and any panel R1HA doesn't render natively). The panel URL is built from
 * the server's base URL plus the panel's url_path (e.g. `http://ha.local:8123/hacs`).
 *
 * Auth handoff mirrors [com.github.itskenny0.r1ha.feature.lovelace.LovelaceScreen]:
 * the access token is pre-pasted into localStorage's `hassTokens` key on the first
 * page start so HA's frontend picks it up without a second OAuth round-trip. The same
 * mixed-content policy and algorithmic-darkening setup is applied.
 *
 * Error handling: main-frame load errors surface a dismissable overlay. If the panel's
 * url_path is no longer registered on the server, HA's frontend will redirect to 404 or
 * show its own "not found" page; we don't need special-case handling for that because
 * the user will see HA's error in the WebView rather than a silent blank screen.
 *
 * [panelUrlPath] is HA's stable identifier for this panel (e.g. "hacs", "esphome").
 * [title] is the display label shown in the top bar; sourced from [PinnedPanel.title]
 * and falls back to the url_path in uppercase when absent.
 */
@Composable
fun PanelViewerScreen(
    settings: SettingsRepository,
    tokens: TokenStore,
    refresher: TokenRefresher,
    panelUrlPath: String,
    title: String,
    onBack: () -> Unit,
) {
    val serverUrl by produceState<String?>(null, settings) {
        value = runCatching { settings.settings.first().server?.url }.getOrNull()
    }
    // Refresh BEFORE reading: the app's own WS stays authenticated long after
    // the stored access token expires, so without this the seeded token can be
    // hours dead while the app looks connected — HA then rejects the WebView's
    // connection and the frontend wipes the envelope and shows its login mask.
    // ensureFresh is best-effort: on failure we fall through to the stored
    // token (plus its real expiry) and let the frontend's own refresh try.
    val tokenInfo by produceState<TokenInfo?>(null, tokens, refresher) {
        value = runCatching {
            refresher.ensureFresh()
            val t = tokens.load() ?: return@runCatching TokenInfo(null, null, 0L)
            TokenInfo(
                t.accessToken,
                t.refreshToken.takeIf { it.isNotBlank() },
                t.expiresAtMillis,
            )
        }.getOrNull()
    }
    val accessToken = tokenInfo?.accessToken
    val refreshToken = tokenInfo?.refreshToken
    var loading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val onBackState = rememberUpdatedState(onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        // Uppercase the title to match R1HA's all-caps top-bar convention.
        R1TopBar(title = title.uppercase(), onBack = onBack)

        val url = serverUrl
        if (url == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No HA server configured. Sign in via Settings first.",
                    style = R1.body,
                    color = R1.InkMuted,
                )
            }
            return@Column
        }

        // Build the panel URL from the server base and the url_path.
        val panelUrl = "${url.trimEnd('/')}/$panelUrlPath"

        // Do NOT mount the WebView until the token load has RESOLVED. The
        // WebViewClient is created once inside remember and captures the token
        // values from that composition; mounting while the async TokenStore read
        // was still in flight captured null forever, the hassTokens injection
        // never ran, and the frontend showed its login mask on every open. The
        // root cause of the persistent sign-in prompt.
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
            PanelWebView(
                panelUrl = panelUrl,
                serverUrl = url,
                accessToken = accessToken,
                refreshToken = refreshToken,
                tokenExpiresAtMillis = tokenInfo?.expiresAtMillis ?: 0L,
                onLoadingChange = {
                    loading = it
                    if (it) errorMessage = null
                },
                onError = { errorMessage = it },
                onBackHandled = { onBackState.value() },
                modifier = Modifier.fillMaxSize(),
            )
            if (loading) {
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
                            text = "LOADING PANEL",
                            style = R1.labelMicro,
                            color = R1.InkSoft,
                        )
                    }
                }
            } else {
                errorMessage?.let { msg ->
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
private fun PanelWebView(
    panelUrl: String,
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
    // Defense in depth for the stale-capture bug: the client reads the CURRENT
    // token values through rememberUpdatedState instead of whatever the first
    // composition happened to capture.
    val liveAccessToken = rememberUpdatedState(accessToken)
    val liveRefreshToken = rememberUpdatedState(refreshToken)
    val liveExpiresAt = rememberUpdatedState(tokenExpiresAtMillis)
    val webView = remember(context, panelUrl) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Lock HTTPS servers to NEVER_ALLOW for mixed content; allow
            // HTTP servers everything (the whole session is already plaintext).
            // Matches the policy in LovelaceScreen's WebView factory.
            settings.mixedContentMode = if (serverUrl.startsWith("https://", ignoreCase = true)) {
                android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            } else {
                android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            if (androidx.webkit.WebViewFeature.isFeatureSupported(
                    androidx.webkit.WebViewFeature.ALGORITHMIC_DARKENING,
                )
            ) {
                androidx.webkit.WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true)
            }
            // Primary injection path: a document-start script is GUARANTEED to
            // run before any page script, so the frontend's auth bootstrap can
            // never race past it (evaluateJavascript at onPageStarted can lose
            // that race on a cached load — observed on device as a bounce to
            // /auth/authorize within ~40ms of page start). Feature-gated; the
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
                    R1Log.w("PanelViewer", "doc-start inject skipped: unparseable server url")
                }
            }
            // On a cold WebView (fresh localStorage) HA's frontend can run its
            // auth check before the async token injection below executes and
            // bounce us to the login mask, which never re-checks. When that
            // happens we retry the panel exactly once AFTER the injection
            // callback confirms the tokens are written, so the retry lands
            // signed in. One-shot so a genuinely broken token can't loop.
            var authBounceDone = false
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(
                    view: WebView,
                    url: String,
                    favicon: android.graphics.Bitmap?,
                ) {
                    onLoadingChange(true)
                    // Inject the hassTokens localStorage entry so HA's frontend
                    // picks up the session without a second OAuth round-trip.
                    // Guarded with !localStorage.getItem('hassTokens') so we only
                    // inject on the first page-start and never clobber a fresh
                    // frontend-refreshed token on subsequent navigations.
                    val accessToken = liveAccessToken.value
                    if (accessToken.isNullOrBlank()) {
                        // This branch hid the stale-capture bug: it was silent, so
                        // shipped logs carried no evidence. Never again.
                        R1Log.w("PanelViewer", "token inject skipped: no access token at page start url=$url")
                    }
                    if (!accessToken.isNullOrBlank()) {
                        // Same guarded script as the document-start registration
                        // (HassTokensInjection validates/repairs the stored
                        // envelope rather than blindly checking presence — the
                        // frontend writes the literal string "null" after a
                        // failed connect, which a presence check mistakes for a
                        // real session). Running it here too gives a readback to
                        // ship in the logs: state=ours means the doc-start path
                        // already seeded it, injected=true means this run did.
                        val script = HassTokensInjection.buildScript(
                            accessToken = accessToken,
                            refreshToken = liveRefreshToken.value,
                            expiresAtMillis = liveExpiresAt.value,
                            nowMillis = System.currentTimeMillis(),
                        )
                        view.evaluateJavascript(script) { readback ->
                            R1Log.i("PanelViewer", "token inject: $readback url=$url")
                            if (!authBounceDone && url.contains("/auth/authorize")) {
                                authBounceDone = true
                                R1Log.i("PanelViewer", "auth race lost; retrying panel with seeded tokens")
                                view.post { view.loadUrl(panelUrl) }
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
                    // Keep navigation on HA's own host; route external links
                    // (GitHub, integration docs, etc.) to the system browser.
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
                    R1Log.w("PanelViewer", "WebView error: $desc (${request.url})")
                    onError("WebView: $desc")
                    onLoadingChange(false)
                }
            }
            loadUrl(panelUrl)
        }
    }

    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }
    BackHandler(enabled = true) {
        if (webView.canGoBack()) webView.goBack() else onBackHandled()
    }

    AndroidView(factory = { webView }, modifier = modifier)
}

/** Resolved token state for the WebView mount gate: null while the (refresh +)
 *  load is in flight, non-null with null fields when no token is stored. */
private data class TokenInfo(
    val accessToken: String?,
    val refreshToken: String?,
    val expiresAtMillis: Long,
)
