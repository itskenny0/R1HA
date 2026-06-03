package com.github.itskenny0.r1ha.feature.voicesat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.TokenStore
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.theme.responsiveType
import com.github.itskenny0.r1ha.core.voice.VoiceSatelliteEngine
import com.github.itskenny0.r1ha.ui.components.LocalWindowTier
import com.github.itskenny0.r1ha.ui.components.WindowTier
import com.github.itskenny0.r1ha.ui.components.R1Button
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.layout.AdaptiveContent

/**
 * Voice satellite — push-to-talk surface for HA's assist pipeline.
 *
 * Tap to start: opens the pipeline, asks the OS for the mic if needed, then
 * starts streaming PCM 16 kHz mono frames at HA over the existing WebSocket.
 * Tap again (or wait for HA's STT to complete on its own) and the pipeline
 * continues through intent, then TTS; the TTS audio plays automatically.
 *
 * The hero mic communicates the pipeline stage at a glance: it pulses while
 * HA is listening, shifts colour for thinking / speaking, and surfaces the
 * STT transcript plus the assistant reply below it. Wake-word detection is
 * out of scope for this surface (it needs an always-on on-device model); this
 * satellite is push-to-talk only.
 */
@Composable
fun VoiceSatelliteScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    tokens: TokenStore,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val engine = remember { VoiceSatelliteEngine(haRepository, settings, tokens) }
    LaunchedEffect(engine) { engine.attachContext(context) }
    DisposableEffect(engine) {
        onDispose { engine.release() }
    }
    val state by engine.state.collectAsStateWithLifecycle()
    var hasMicPerm by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }
    // Tracks whether the user has been through a denial. After a denial the
    // launcher may stop showing the system dialog (permanent deny), so we
    // surface an inline hint to send them to app settings instead of silently
    // doing nothing on tap.
    var permDenied by remember { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasMicPerm = granted
        permDenied = !granted
        if (granted) {
            engine.start(pipelineId = null, conversationId = null, appContext = context)
        }
    }

    // Start / stop is driven purely by the current state so the tap target and
    // its semantics stay in lockstep with what the engine will actually do.
    val onMicTap = {
        when (state) {
            // Only Listening has live audio to cut off. Connecting / Thinking /
            // Speaking are in HA's hands now; a tap there is a no-op so we don't
            // orphan an in-flight pipeline that the engine can't cancel cleanly
            // mid-open.
            is VoiceSatelliteEngine.State.Listening -> engine.stop()
            is VoiceSatelliteEngine.State.Connecting,
            is VoiceSatelliteEngine.State.Thinking,
            is VoiceSatelliteEngine.State.Speaking,
            -> Unit
            else -> {
                if (!hasMicPerm) {
                    permDenied = false
                    permLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                } else {
                    engine.start(
                        pipelineId = null,
                        conversationId = null,
                        appContext = context,
                    )
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        R1TopBar(title = "VOICE SATELLITE", onBack = onBack)
        AdaptiveContent(modifier = Modifier.weight(1f)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = R1.space.l, vertical = R1.space.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = statusLabel(state),
                    style = responsiveType(R1.screenTitle),
                    color = R1.Ink,
                    // Announce stage changes for screen readers as the pipeline
                    // advances wake, listen, think, speak.
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
                )
                Spacer(Modifier.height(R1.space.s))
                val sub = subLabel(state)
                if (sub != null) {
                    Text(
                        text = sub,
                        style = responsiveType(R1.labelMicro),
                        color = R1.InkSoft,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(R1.space.xxl))

                MicHero(state = state, hasMicPerm = hasMicPerm, onTap = onMicTap)

                Spacer(Modifier.height(R1.space.xl))

                // Transcript panel: STT result on top, assistant reply below.
                // Both fade in as the pipeline progresses through stt-end and
                // intent-end events. We read both off whichever post-listen
                // state is current.
                val sttText = (state as? VoiceSatelliteEngine.State.Thinking)?.sttText
                    ?: (state as? VoiceSatelliteEngine.State.Speaking)?.sttText
                    ?: (state as? VoiceSatelliteEngine.State.Done)?.sttText
                val response = (state as? VoiceSatelliteEngine.State.Speaking)?.responseText
                    ?: (state as? VoiceSatelliteEngine.State.Done)?.responseText

                // On roomy tiers the surrounding Column is wide, so cap the
                // transcript's line length and centre it under the hero rather
                // than letting it run as one wall-wide line. On mini / compact
                // the cap is generous enough that it just fills the panel.
                val bigTier = LocalWindowTier.current.tier.isAtLeast(WindowTier.MEDIUM)
                val transcriptAlign = if (bigTier) TextAlign.Center else TextAlign.Start
                val transcriptModifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .padding(horizontal = R1.space.s)

                AnimatedVisibility(
                    visible = !sttText.isNullOrBlank(),
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Text(
                        text = "You: ${sttText.orEmpty()}",
                        style = responsiveType(R1.body),
                        color = R1.Ink,
                        textAlign = transcriptAlign,
                        modifier = transcriptModifier,
                    )
                }
                Spacer(Modifier.height(R1.space.m))
                AnimatedVisibility(
                    visible = !response.isNullOrBlank(),
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Text(
                        text = "HA: ${response.orEmpty()}",
                        style = responsiveType(R1.body),
                        color = R1.AccentCool,
                        textAlign = transcriptAlign,
                        modifier = transcriptModifier,
                    )
                }

                if (state is VoiceSatelliteEngine.State.Error) {
                    Spacer(Modifier.height(R1.space.xl))
                    R1Button(
                        text = "RETRY",
                        onClick = {
                            if (!hasMicPerm) {
                                permDenied = false
                                permLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                            } else {
                                engine.start(
                                    pipelineId = null,
                                    conversationId = null,
                                    appContext = context,
                                )
                            }
                        },
                    )
                }

                // Mic permission is required before the satellite can do
                // anything. After an outright denial the system dialog may stop
                // appearing, so point the user at app settings rather than
                // leaving the tap looking broken.
                if (!hasMicPerm && permDenied) {
                    Spacer(Modifier.height(R1.space.m))
                    Text(
                        text = "Microphone access is off. Enable it in system settings, then tap to talk.",
                        style = responsiveType(R1.labelMicro),
                        color = R1.StatusAmber,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 560.dp)
                            .padding(horizontal = R1.space.s),
                    )
                }
            }
        }
    }
}

/**
 * The big circular tap target. Colour and motion are bound to the pipeline
 * stage: a slow pulse while listening, a static fill while HA thinks / speaks,
 * green when done, red on error. The accent cross-fades between states so the
 * transition reads as one continuous control rather than a hard cut.
 */
@Composable
private fun MicHero(
    state: VoiceSatelliteEngine.State,
    hasMicPerm: Boolean,
    onTap: () -> Unit,
) {
    val targetAccent = when (state) {
        is VoiceSatelliteEngine.State.Listening -> R1.AccentWarm
        is VoiceSatelliteEngine.State.Connecting -> R1.AccentNeutral
        is VoiceSatelliteEngine.State.Thinking -> R1.StatusAmber
        is VoiceSatelliteEngine.State.Speaking -> R1.AccentCool
        is VoiceSatelliteEngine.State.Error -> R1.StatusRed
        is VoiceSatelliteEngine.State.Done -> R1.AccentGreen
        else -> R1.SurfaceMuted
    }
    val accent by animateColorAsState(targetValue = targetAccent, label = "voicesat-accent")

    // Gentle breathing scale while listening so an idle-vs-live glance is
    // unambiguous on the small R1 screen. Static otherwise.
    val listening = state is VoiceSatelliteEngine.State.Listening
    val transition = rememberInfiniteTransition(label = "voicesat-pulse")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (listening) 1.06f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "voicesat-pulse-scale",
    )

    // Hero diameter scales with the tier. The R1 / compact baseline stays the
    // hand-tuned 160dp (R1.space.xxl * 5) so the mini panel renders exactly as
    // before; roomier tiers step the hero up modestly so it reads as the focal
    // control on a large panel instead of being marooned in whitespace. The
    // largest step (240dp) still fits comfortably inside the capped content
    // column.
    val heroSize = when (LocalWindowTier.current.tier) {
        WindowTier.R1, WindowTier.COMPACT -> R1.space.xxl * 5
        WindowTier.MEDIUM -> R1.space.xxl * 6
        WindowTier.EXPANDED -> R1.space.xxl * 6.75f
        WindowTier.EXTRA_LARGE -> R1.space.xxl * 7.5f
    }

    val cd = micContentDescription(state, hasMicPerm)
    Box(
        modifier = Modifier
            .size(heroSize)
            .scale(if (listening) pulse else 1f)
            .clip(CircleShape)
            .background(accent)
            .border(width = 1.dp, color = R1.Hairline, shape = CircleShape)
            .r1Pressable(onClick = onTap, contentDescription = cd),
        contentAlignment = Alignment.Center,
    ) {
        // Bare label rather than dragging in vector-asset tooling: a verb that
        // names what a tap does (or the stage HA is in when a tap is a no-op).
        Text(
            text = when (state) {
                is VoiceSatelliteEngine.State.Listening -> "STOP"
                is VoiceSatelliteEngine.State.Connecting -> "..."
                is VoiceSatelliteEngine.State.Thinking -> "THINK"
                is VoiceSatelliteEngine.State.Speaking -> "SPEAK"
                else -> "TALK"
            },
            style = responsiveType(R1.titleCard),
            color = R1.Bg,
        )
    }
}

private fun micContentDescription(
    state: VoiceSatelliteEngine.State,
    hasMicPerm: Boolean,
): String = when {
    !hasMicPerm -> "Grant microphone access and start talking to Home Assistant"
    state is VoiceSatelliteEngine.State.Listening -> "Listening. Tap to stop and send."
    state is VoiceSatelliteEngine.State.Connecting -> "Connecting to Home Assistant"
    state is VoiceSatelliteEngine.State.Thinking -> "Home Assistant is thinking"
    state is VoiceSatelliteEngine.State.Speaking -> "Home Assistant is speaking"
    else -> "Tap to talk to Home Assistant"
}

private fun statusLabel(state: VoiceSatelliteEngine.State): String = when (state) {
    is VoiceSatelliteEngine.State.Idle -> "READY"
    is VoiceSatelliteEngine.State.Connecting -> "CONNECTING"
    is VoiceSatelliteEngine.State.Listening -> "LISTENING"
    is VoiceSatelliteEngine.State.Thinking -> "THINKING"
    is VoiceSatelliteEngine.State.Speaking -> "SPEAKING"
    is VoiceSatelliteEngine.State.Done -> "DONE"
    is VoiceSatelliteEngine.State.Error -> "ERROR"
}

private fun subLabel(state: VoiceSatelliteEngine.State): String? = when (state) {
    is VoiceSatelliteEngine.State.Idle -> "Tap to talk to Home Assistant"
    is VoiceSatelliteEngine.State.Connecting -> "Opening pipeline..."
    is VoiceSatelliteEngine.State.Listening -> "Tap to stop"
    is VoiceSatelliteEngine.State.Thinking -> "Running intent"
    is VoiceSatelliteEngine.State.Speaking -> "Playing response"
    is VoiceSatelliteEngine.State.Done -> "Tap to talk again"
    is VoiceSatelliteEngine.State.Error -> state.message
}
