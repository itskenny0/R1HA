package com.github.itskenny0.r1ha.feature.onboarding

import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster

/**
 * Renders [authorizeUrl] in an embedded [WebView] and intercepts the
 * `r1ha://auth-callback?code=…` redirect, extracting the authorization code
 * and forwarding it to [onCodeCaptured].
 */
@Composable
fun OAuthWebView(
    authorizeUrl: String,
    onCodeCaptured: (code: String) -> Unit,
    /** Called when the redirect arrives without a `code` query parameter. Passes through
     *  the `error` param from HA (e.g. "access_denied" when the user tapped Deny) so the
     *  caller can surface it in the UI instead of just resetting silently. */
    onMissingCode: (errorMessage: String?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // Keep the latest callbacks visible to the long-lived WebViewClient closure.
    val currentOnCode = rememberUpdatedState(onCodeCaptured)
    val currentOnMissing = rememberUpdatedState(onMissingCode)

    // Tracks whether the WebView is still loading its main frame. Drives
    // a small spinner overlay so the user sees something during the
    // initial /auth/authorize round-trip rather than a blank black
    // screen. Common on cold HA installs where the first request can
    // take a couple of seconds.
    var loading by remember { mutableStateOf(true) }

    val webView = remember(context) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    loading = true
                }
                override fun onPageFinished(view: WebView, url: String) {
                    loading = false
                }
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val uri = request.url
                    R1Log.d("OAuthWebView", "shouldOverride scheme=${uri.scheme} host=${uri.host}")
                    if (uri.scheme == "r1ha" && uri.host == "auth-callback") {
                        val code = uri.getQueryParameter("code")
                        val error = uri.getQueryParameter("error")
                        R1Log.i("OAuthWebView", "redirect captured code?=${!code.isNullOrBlank()} error=$error")
                        if (!code.isNullOrBlank()) {
                            Toaster.show("Captured auth code")
                            currentOnCode.value.invoke(code)
                        } else {
                            val msg = error?.let { "Redirect had error=$it" } ?: "Redirect had no code"
                            R1Log.w("OAuthWebView", msg)
                            Toaster.error(msg)
                            currentOnMissing.value.invoke(error)
                        }
                        return true
                    }
                    return false
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    // Only surface errors for the top-level (main-frame) navigation. Sub-resource
                    // failures (favicon, analytics, etc.) are noise. HA's login page still works
                    // even when those 404. We also tolerate the error firing for the very last
                    // hop into r1ha://auth-callback, which the WebView reports as "scheme not
                    // supported". That's expected and is handled by shouldOverrideUrlLoading.
                    if (!request.isForMainFrame) return
                    val url = request.url
                    if (url.scheme == "r1ha") return
                    val desc = runCatching { error.description?.toString() }.getOrNull() ?: "error"
                    R1Log.w("OAuthWebView", "main-frame load error: $desc ($url)")
                    Toaster.error("WebView: $desc")
                }
            }
            loadUrl(authorizeUrl)
        }
    }

    // If the screen leaves composition, drop the WebView so it doesn't leak.
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { webView },
            modifier = Modifier.fillMaxSize(),
        )
        if (loading) {
            // Spinner overlay during main-frame loads. Sits on a
            // semi-transparent backdrop so the user knows the WebView
            // is working even before the first paint lands.
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
    }
}
