package com.github.itskenny0.r1ha.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.TokenStore
import com.github.itskenny0.r1ha.core.sync.SyncCategory
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.util.Toaster
import com.github.itskenny0.r1ha.feature.sync.SyncOnboardingStep
import com.github.itskenny0.r1ha.ui.components.R1Button
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Onboarding host. Drives the user through:
 *   01 LINK       URL entry, normaliser preview, probe.
 *   02 AUTHORISE  in-app OAuth WebView (or LLAT escape hatch).
 *   03 SYNC       opt in / out of cross-device settings mirror.
 *
 * Sync is the last gate before card-stack; it covers fullscreen as its
 * own overlay so any underlying onboarding state (Done, freshly-saved
 * LLAT) gets the same prompt. Choice persists `haSyncPromptSeen` so the
 * post-launch [com.github.itskenny0.r1ha.feature.sync.HaSyncOnboardingPrompt]
 * does not re-fire for fresh installs.
 */
@Composable
fun OnboardingScreen(
    settings: SettingsRepository,
    tokens: TokenStore,
    onComplete: () -> Unit,
    /** Optional escape hatch. When set, the URL entry form shows a small
     *  link below CONNECT that jumps to the LLAT setup screen. Lets
     *  kiosk-style installs skip OAuth entirely without first having to
     *  OAuth in to reach Settings, LLAT (chicken-and-egg). Null
     *  disables the link. */
    onOpenLongLivedToken: (() -> Unit)? = null,
    http: OkHttpClient = remember { OkHttpClient() },
) {
    val vm: OnboardingViewModel = viewModel(
        factory = OnboardingViewModel.factory(http = http, settings = settings, tokens = tokens),
    )
    val state by vm.state.collectAsStateWithLifecycle()

    // Sync onboarding gate. Authentication completing (OAuth Done OR a
    // fresh LLAT detected on resume) flips this true; the sync step
    // covers everything until the user picks a path, then onComplete().
    var awaitingSyncChoice by rememberSaveable { mutableStateOf(false) }
    val syncScope = rememberCoroutineScope()

    LaunchedEffect(state) {
        if (state is OnboardingViewModel.State.Done) awaitingSyncChoice = true
    }

    // The LLAT escape hatch saves directly to TokenStore without running
    // the OAuth state machine, so OnboardingViewModel never reaches Done.
    // Observe the activity lifecycle and re-check token presence on
    // ON_RESUME: when the user comes back from the LLAT screen with a
    // freshly-saved token, route through the sync step so they get the
    // same offer fresh OAuth users do.
    val lifecycleOwner = LocalLifecycleOwner.current
    val resumeScope = rememberCoroutineScope()
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                resumeScope.launch {
                    if (tokens.load() != null) awaitingSyncChoice = true
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (awaitingSyncChoice) {
        SyncOnboardingStep(
            onAcceptAll = {
                syncScope.launch {
                    settings.update { s ->
                        s.copy(
                            integrations = s.integrations.copy(
                                haSyncEnabled = true,
                                // Recommended default: everything except wheel
                                // + input, which is per-device.
                                haSyncExcludedCategories = setOf(SyncCategory.WHEEL_INPUT.name),
                                haSyncPromptSeen = true,
                            ),
                        )
                    }
                    Toaster.show("Sync on. Wheel + input stay local. Refine in Settings, Sync.")
                    onComplete()
                }
            },
            onAcceptWithExclusions = { excludedNames ->
                syncScope.launch {
                    settings.update { s ->
                        s.copy(
                            integrations = s.integrations.copy(
                                haSyncEnabled = true,
                                haSyncExcludedCategories = excludedNames,
                                haSyncPromptSeen = true,
                            ),
                        )
                    }
                    onComplete()
                }
            },
            onDecline = {
                syncScope.launch {
                    settings.update { s ->
                        s.copy(
                            integrations = s.integrations.copy(haSyncPromptSeen = true),
                        )
                    }
                    onComplete()
                }
            },
        )
        return
    }

    when (val s = state) {
        is OnboardingViewModel.State.ReadyToAuth -> {
            // Back-press inside the OAuth WebView drops the user back to URL
            // entry instead of exiting the app.
            BackHandler { vm.resetError() }
            OAuthWebView(
                authorizeUrl = s.authorizeUrl,
                // Use the baseUrl the user originally probed so path-prefixed
                // HA setups (e.g. https://example.com/ha) keep their prefix
                // on /auth/token.
                onCodeCaptured = { code -> vm.exchangeCode(code, s.baseUrl) },
                // If HA redirects without a `code` query parameter, typically
                // because the user tapped Deny, drop them back to the URL
                // entry form with the HA error surfaced as a visible message
                // rather than leaving the WebView pinned on HA's error page
                // with no clear next step.
                onMissingCode = { errorMessage ->
                    vm.failOnboarding(
                        errorMessage?.let { "Login was cancelled or rejected ($it)" }
                            ?: "Login did not complete. Please try again.",
                    )
                },
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding(),
            )
        }

        is OnboardingViewModel.State.Exchanging -> ExchangingStep()

        is OnboardingViewModel.State.Done -> {
            // LaunchedEffect above flips awaitingSyncChoice; render nothing
            // while the sync overlay paints over us.
            Box(Modifier.fillMaxSize().background(R1.Bg))
        }

        else -> {
            // Idle / Probing / Error all use the URL entry form. Error is
            // shown inline so the user doesn't lose their typed URL.
            UrlEntryForm(
                isProbing = s is OnboardingViewModel.State.Probing,
                error = (s as? OnboardingViewModel.State.Error)?.message,
                onProbe = { vm.probe(it) },
                onErrorDismiss = { vm.resetError() },
                onUseLongLivedToken = onOpenLongLivedToken,
            )
        }
    }
}

/**
 * Step-marker callout shown above each screen title (01 LINK, 02 AUTHORISE,
 * 03 SYNC). Reads as part of a guided sequence with a known end so the
 * user knows roughly where they are in the flow.
 */
@Composable
private fun StepCallout(number: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = number, style = R1.labelMicro, color = R1.AccentWarm)
        Spacer(Modifier.size(6.dp))
        Box(modifier = Modifier.size(width = 14.dp, height = 1.dp).background(R1.AccentWarm))
        Spacer(Modifier.size(6.dp))
        Text(text = label, style = R1.labelMicro, color = R1.AccentWarm)
    }
}

/** "02 AUTHORISE: exchanging tokens" interstitial. Labelled-progress so a
 *  user staring at a bare spinner on a black screen doesn't think the app
 *  has hung. */
@Composable
private fun ExchangingStep() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding()
            .padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        StepCallout(number = "02", label = "AUTHORISE")
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Exchanging tokens",
            style = R1.screenTitle,
            color = R1.Ink,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Swapping the authorisation code for an access token. " +
                "One round-trip, usually a second.",
            style = R1.body,
            color = R1.InkMuted,
        )
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = R1.AccentWarm,
            )
            Spacer(Modifier.size(10.dp))
            Text(
                text = "WORKING",
                style = R1.labelMicro,
                color = R1.InkSoft,
            )
        }
    }
}

/** "01 LINK" screen: URL entry with live normalisation preview, an
 *  OPEN-IN-BROWSER chip for sanity-checking, inline error reporting, and
 *  the LLAT escape hatch. */
@Composable
private fun UrlEntryForm(
    isProbing: Boolean,
    error: String?,
    onProbe: (String) -> Unit,
    onErrorDismiss: () -> Unit,
    onUseLongLivedToken: (() -> Unit)? = null,
) {
    // Start empty so the placeholder ("http://homeassistant.local:8123")
    // is what the user sees first. They can type a bare host like
    // "192.168.1.10" and let the normaliser pick the protocol + port, or
    // paste a full URL.
    var urlText by rememberSaveable { mutableStateOf("") }
    val scrollState = rememberScrollState()
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(imeBottom) {
        if (imeBottom > 0) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding()
            .imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxHeight()
                .verticalScroll(scrollState)
                .padding(horizontal = 22.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            StepCallout(number = "01", label = "LINK")
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Point me at\nHome Assistant.",
                style = R1.screenTitle,
                color = R1.Ink,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Type a host. Protocol and port are optional: " +
                    "local hosts default to http:// :8123, public domains " +
                    "default to https:// :443.",
                style = R1.body,
                color = R1.InkMuted,
            )
            Spacer(Modifier.height(8.dp))
            ExampleHostsBlock()
            Spacer(Modifier.height(22.dp))

            // ── Field ────────────────────────────────────────────────
            Text(
                text = "URL",
                style = R1.labelMicro,
                color = R1.InkMuted,
            )
            Spacer(Modifier.height(8.dp))
            R1TextField(
                value = urlText,
                onValueChange = {
                    if (error != null) onErrorDismiss()
                    urlText = it
                },
                placeholder = "http://homeassistant.local:8123",
                isError = error != null,
                enabled = !isProbing,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { onProbe(urlText) }),
                modifier = Modifier.fillMaxWidth(),
            )

            // Live preview of the normalised URL. Surfaces protocol
            // inference + default-port heuristic before CONNECT so
            // "why is it adding :8123?" has an immediate answer. Only
            // shown when the preview differs from the raw input.
            val normalised = remember(urlText) { normalizeServerUrl(urlText) }
            if (normalised.isNotBlank() && normalised != urlText.trim().trimEnd('/')) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "WILL PROBE",
                        style = R1.labelMicro,
                        color = R1.InkSoft,
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = normalised,
                        style = R1.numeralS,
                        color = R1.Ink,
                    )
                }
            }
            // OPEN IN BROWSER chip: sanity-check the normalised URL in
            // the system browser before committing to OAuth. Common
            // pre-onboarding diagnostic for "is HA even reachable on
            // this LAN" questions.
            if (normalised.isNotBlank() && normalised.startsWith("http", ignoreCase = true)) {
                val ctx = LocalContext.current
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .clip(R1.ShapeS)
                        .background(R1.SurfaceMuted)
                        .border(1.dp, R1.Hairline, R1.ShapeS)
                        .r1Pressable(onClick = {
                            runCatching {
                                ctx.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(normalised),
                                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        })
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = "OPEN IN BROWSER",
                        style = R1.labelMicro,
                        color = R1.InkSoft,
                    )
                }
            }

            if (error != null) {
                Spacer(Modifier.height(14.dp))
                ErrorPanel(message = error)
            }

            Spacer(Modifier.height(24.dp))

            R1Button(
                text = if (isProbing) "PROBING" else "CONNECT",
                onClick = { onProbe(urlText) },
                enabled = !isProbing && urlText.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                leadingContent = if (isProbing) {
                    {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(14.dp)
                                .padding(end = 8.dp),
                            strokeWidth = 2.dp,
                            color = R1.Bg,
                        )
                    }
                } else null,
            )

            // ── LLAT escape hatch ───────────────────────────────────
            // Without this link the user has to OAuth first to reach
            // Settings, LLAT (pointless if they specifically don't want
            // OAuth). Muted styling so it doesn't compete with CONNECT
            // as the primary action.
            if (onUseLongLivedToken != null) {
                Spacer(Modifier.height(20.dp))
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "USE A LONG-LIVED TOKEN INSTEAD",
                        style = R1.labelMicro,
                        color = R1.AccentWarm,
                        modifier = Modifier
                            .r1Pressable(onClick = onUseLongLivedToken)
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                    )
                }
            }
            // Bottom spacer so the LLAT link clears the IME / nav bar on
            // short displays (R1's 320dp tall screen).
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Compact two-column example list. Monospace hosts on the left, plain
 *  description on the right, so the typeahead-able strings stand out from
 *  the explanatory text. Tighter than the original bullet list and fits
 *  the R1's portrait screen without scrolling. */
@Composable
private fun ExampleHostsBlock() {
    val examples = listOf(
        "192.168.1.10" to "lan ip",
        "homeassistant.local" to "mdns",
        "ha.mydomain.com" to "public",
    )
    Column {
        examples.forEach { (host, kind) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = host,
                    style = R1.numeralS,
                    color = R1.Ink,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = kind.uppercase(),
                    style = R1.labelMicro,
                    color = R1.InkSoft,
                )
            }
        }
    }
}

/** Inline error panel: left rail in StatusRed + the message in body type.
 *  Replaces the bare red Text so the error has the same visual weight as
 *  the form chrome around it. */
@Composable
private fun ErrorPanel(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(R1.StatusRed.copy(alpha = 0.08f))
            .border(1.dp, R1.StatusRed.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .padding(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 2.dp, height = 28.dp)
                .background(R1.StatusRed),
        )
        Spacer(Modifier.size(10.dp))
        Column {
            Text(
                text = "PROBE FAILED",
                style = R1.labelMicro,
                color = R1.StatusRed,
            )
            Spacer(Modifier.size(2.dp))
            Text(
                text = message,
                style = R1.body,
                color = R1.Ink,
            )
        }
    }
}

