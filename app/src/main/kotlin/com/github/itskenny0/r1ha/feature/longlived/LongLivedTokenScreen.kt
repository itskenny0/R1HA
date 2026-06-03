package com.github.itskenny0.r1ha.feature.longlived

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.prefs.ServerConfig
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.TokenStore
import com.github.itskenny0.r1ha.core.prefs.Tokens
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.theme.responsiveType
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import com.github.itskenny0.r1ha.ui.components.R1Button
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.layout.AdaptiveContent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Long-lived access token setup — alternative to the OAuth flow for
 * users who would rather paste a token from HA's `My profile` page
 * than authenticate in the WebView. Common with kiosk-style HA setups
 * (R1s mounted in cars, on walls, etc.) where the LLAT lives forever
 * and OAuth's refresh dance is just paperwork.
 *
 * Storage shape: `Tokens(accessToken = LLAT, refreshToken = "",
 * expiresAtMillis = Long.MAX_VALUE)`. The empty-refresh sentinel is
 * the contract [com.github.itskenny0.r1ha.core.ha.TokenRefresher]
 * checks before attempting a refresh — for LLATs it skips the refresh
 * call entirely.
 */
@Composable
fun LongLivedTokenScreen(
    settings: SettingsRepository,
    tokens: TokenStore,
    haRepository: HaRepository,
    wheelInput: com.github.itskenny0.r1ha.core.input.WheelInput,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // FLAG_SECURE keeps the token paste field out of Android's recents thumbnails and
    // out of screen-recording captures. Applied via DisposableEffect so it's only on
    // while this screen is composed; other surfaces remain unaffected.
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window
        window?.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
        )
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    val current by settings.settings.collectAsState(initial = null)
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Pre-fill the URL field with the currently-configured server (if any)
    // so users editing their token don't have to retype the URL.
    LaunchedEffect(current) {
        if (url.isBlank()) {
            url = current?.server?.url.orEmpty()
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding()
            .imePadding(),
    ) {
        R1TopBar(title = "LONG-LIVED TOKEN", onBack = onBack)
        AdaptiveContent(modifier = Modifier.weight(1f)) {
        val scrollState = rememberScrollState()
        com.github.itskenny0.r1ha.ui.components.WheelScrollForScrollState(
            wheelInput = wheelInput,
            scrollState = scrollState,
            settings = settings,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = R1.space.xl, vertical = R1.space.m)
                .verticalScroll(scrollState),
        ) {
            Text(
                text = "Skip OAuth: paste an HA long-lived access token. Generate one " +
                    "from HA Profile → Long-Lived Access Tokens. Stored encrypted at " +
                    "rest (AndroidKeystore-wrapped AES-256-GCM) just like the OAuth path.",
                style = responsiveType(R1.body),
                color = R1.InkMuted,
            )
            Spacer(Modifier.height(R1.space.l))
            Text(text = "HA URL", style = responsiveType(R1.labelMicro), color = R1.InkSoft)
            Spacer(Modifier.height(R1.space.xs))
            R1TextField(
                value = url,
                onValueChange = { url = it },
                placeholder = "https://homeassistant.local:8123",
                monospace = true,
                modifier = Modifier.fillMaxWidth(),
            )
            // Live preview of the normalised URL, same affordance as the
            // Onboarding flow's URL field. Only shown when the normaliser
            // actually changes something (e.g. a bare host gets http://
            // and :8123 added) so the line stays absent for fully-typed
            // URLs.
            val normalisedUrlPreview = remember(url) {
                com.github.itskenny0.r1ha.feature.onboarding.normalizeServerUrl(url)
            }
            if (normalisedUrlPreview.isNotBlank() &&
                normalisedUrlPreview != url.trim().trimEnd('/')) {
                Spacer(Modifier.height(R1.space.xs))
                Text(
                    text = "Will save: $normalisedUrlPreview",
                    style = responsiveType(R1.labelMicro),
                    color = R1.InkSoft,
                )
            }
            Spacer(Modifier.height(R1.space.m))
            // Mask the token field by default with an eye-toggle to reveal. The token
            // is highly sensitive (root access to HA); over-the-shoulder users could
            // memorise it from the screen otherwise.
            var revealed by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "ACCESS TOKEN",
                    style = responsiveType(R1.labelMicro),
                    color = R1.InkSoft,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .heightIn(min = R1.MinTarget)
                        .clip(R1.ShapeS)
                        .background(R1.SurfaceMuted)
                        .border(1.dp, R1.Hairline, R1.ShapeS)
                        .r1Pressable(
                            onClick = { revealed = !revealed },
                            contentDescription = if (revealed) {
                                "Hide access token"
                            } else {
                                "Reveal access token"
                            },
                        )
                        .wrapContentHeight(Alignment.CenterVertically)
                        .padding(horizontal = R1.space.s, vertical = R1.space.xs),
                ) {
                    Text(
                        text = if (revealed) "HIDE" else "REVEAL",
                        style = responsiveType(R1.labelMicro),
                        color = R1.InkSoft,
                    )
                }
            }
            Spacer(Modifier.height(R1.space.xs))
            // Keep the field enabled even while masked so the user can still backspace /
            // append to fix a typo without REVEAL-ing the token first; just show the dot
            // mask in the display value. Only suppress the mask when the user explicitly
            // wants to read what they pasted.
            R1TextField(
                value = if (revealed || token.isEmpty()) token
                else "•".repeat(token.length.coerceAtMost(48)),
                onValueChange = { typed ->
                    // If the field is masked, treat every input as a fresh value (paste
                    // overwrites). Without this the user typing a single char while masked
                    // would land beside the mask glyphs and corrupt the real token.
                    token = if (revealed) typed
                    else if (typed.length < token.length) typed.dropLast(token.length - typed.length).let { token.take(it.length) }
                    else token + typed.drop(token.length).filterNot { it == '•' }
                },
                placeholder = "eyJhbGciOiJIUzI1NiIs…",
                monospace = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp),
            )
            // Light-touch paste validation: real HA long-lived tokens are JWTs
            // (three base64url segments separated by dots, ~200 chars long). When the
            // user pastes something that obviously isn't, surface a one-line hint so
            // they don't try to sign in with a garbage value. Validate the whitespace-
            // stripped form so a token pasted with a trailing newline (the usual result
            // of copying from HA's profile page) isn't flagged as malformed.
            val looksLikeJwt = remember(token) {
                val cleaned = token.filterNot { it.isWhitespace() }
                cleaned.isBlank() || (cleaned.count { it == '.' } == 2 && cleaned.length in 50..2000)
            }
            if (!looksLikeJwt) {
                Spacer(Modifier.height(R1.space.xxs))
                Text(
                    text = "Doesn't look like a HA token. Generate one via your HA profile → Long-lived access tokens.",
                    style = responsiveType(R1.labelMicro),
                    color = R1.StatusAmber,
                )
            }
            Spacer(Modifier.height(R1.space.l))
            Row(verticalAlignment = Alignment.CenterVertically) {
                R1Button(
                    text = if (saving) "SAVING…" else "SAVE & CONNECT",
                    // Mirror the Onboarding form's accept-any-shaped-URL policy:
                    // the normaliser below turns a bare host into a full URL, so
                    // the button is enabled as soon as both fields have content.
                    enabled = !saving && url.isNotBlank() && token.isNotBlank(),
                    onClick = {
                        saving = true
                        error = null
                        scope.launch {
                            try {
                                // Same normaliser the Onboarding flow uses: pick http://
                                // vs https:// from the host shape, default port to 8123
                                // for LAN targets, leave explicit-protocol URLs alone.
                                val normalisedUrl =
                                    com.github.itskenny0.r1ha.feature.onboarding
                                        .normalizeServerUrl(url)
                                require(normalisedUrl.isNotBlank()) { "Empty URL" }
                                // Strip ALL whitespace, not just the ends: a JWT's
                                // alphabet is base64url (no spaces or newlines), so any
                                // whitespace came from the clipboard (trailing newline,
                                // a line-wrap injected by a clipboard manager). Leaving
                                // an embedded newline in would produce a malformed
                                // "Authorization: Bearer <broken>" header and a confusing
                                // 401 that looks like a bad token rather than a paste glitch.
                                val cleanedToken = token.filterNot { it.isWhitespace() }
                                require(cleanedToken.isNotBlank()) { "Empty token" }
                                val newServer = ServerConfig(url = normalisedUrl, haVersion = null)
                                settings.update { it.copy(server = newServer) }
                                tokens.save(
                                    Tokens(
                                        accessToken = cleanedToken,
                                        refreshToken = "",
                                        // Far-future expiry so ensureFresh's
                                        // skew check is always satisfied — the
                                        // empty refreshToken is the real
                                        // 'don't refresh' signal but a Long.MAX
                                        // expiry is good documentation too.
                                        expiresAtMillis = Long.MAX_VALUE,
                                    ),
                                )
                                R1Log.i("LLAT", "saved long-lived token for $normalisedUrl")
                                // Kick off the connection — the repository
                                // will pick up the new server + token on the
                                // next WS attempt. reconnectNow makes that
                                // attempt happen immediately rather than on
                                // the next backoff fire.
                                haRepository.reconnectNow()
                                Toaster.show("Long-lived token saved · connecting…")
                                onBack()
                            } catch (t: Throwable) {
                                R1Log.w("LLAT", "save failed: ${t.message}")
                                error = t.message ?: "Save failed"
                            } finally {
                                saving = false
                            }
                        }
                    },
                )
                Spacer(Modifier.width(R1.space.s))
                Text(
                    text = "no refresh; revoke from HA to invalidate",
                    style = responsiveType(R1.labelMicro),
                    color = R1.InkMuted,
                    modifier = Modifier.weight(1f),
                )
            }
            val e = error
            if (e != null) {
                Spacer(Modifier.height(R1.space.m))
                // Match the Onboarding flow's ErrorPanel: a StatusRed left rail
                // plus a labelled heading so a save failure reads as a distinct
                // error state rather than a stray red line.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(R1.ShapeM)
                        .background(R1.StatusRed.copy(alpha = 0.08f))
                        .border(1.dp, R1.StatusRed.copy(alpha = 0.5f), R1.ShapeM)
                        .padding(R1.space.m)
                        .semantics(mergeDescendants = true) {
                            contentDescription = "Save failed: $e"
                        },
                ) {
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .heightIn(min = 28.dp)
                            .background(R1.StatusRed),
                    )
                    Spacer(Modifier.width(R1.space.s))
                    Column {
                        Text(
                            text = "SAVE FAILED",
                            style = responsiveType(R1.labelMicro),
                            color = R1.StatusRed,
                        )
                        Spacer(Modifier.height(R1.space.xxs))
                        Text(text = e, style = responsiveType(R1.body), color = R1.Ink)
                    }
                }
            }
        }
        } // AdaptiveContent
    }
}
