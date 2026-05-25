package com.github.itskenny0.r1ha.feature.voicesat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.CircleShape
import com.github.itskenny0.r1ha.ui.i18n.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.TokenStore
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.voice.VoiceSatelliteEngine
import com.github.itskenny0.r1ha.ui.components.R1Button
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * Voice satellite — push-to-talk surface for HA's assist pipeline.
 *
 * Tap to start: opens the pipeline, asks the OS for the mic if needed, then
 * starts streaming PCM 16 kHz mono frames at HA over the existing WebSocket.
 * Tap again (or wait for HA's STT to complete on its own) and the pipeline
 * continues through intent → TTS; the TTS audio plays automatically.
 *
 * Wake-word detection is not part of this surface — a wake-word frontend
 * would need an on-device model (Porcupine, OpenWakeWord) plus its own
 * always-on audio path; layering it on top of this pipeline machinery is a
 * separate change.
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
    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasMicPerm = granted
        if (granted) {
            engine.start(pipelineId = null, conversationId = null, appContext = context)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(R1.Bg)) {
        R1TopBar(title = "VOICE SATELLITE", onBack = onBack)
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = statusLabel(state),
                style = R1.screenTitle,
                color = R1.Ink,
            )
            Spacer(Modifier.height(8.dp))
            val sub = subLabel(state)
            if (sub != null) {
                Text(text = sub, style = R1.labelMicro, color = R1.InkSoft)
            }
            Spacer(Modifier.height(48.dp))

            // Big circular mic. Tap toggles record/stop. Colour responds to
            // state so the user can tell whether HA is still listening or
            // has moved on to thinking / speaking.
            val accent = when (state) {
                is VoiceSatelliteEngine.State.Listening -> R1.AccentWarm
                is VoiceSatelliteEngine.State.Thinking -> R1.StatusAmber
                is VoiceSatelliteEngine.State.Speaking -> R1.AccentCool
                is VoiceSatelliteEngine.State.Error -> R1.StatusRed
                is VoiceSatelliteEngine.State.Done -> R1.AccentGreen
                else -> R1.SurfaceMuted
            }
            Box(
                modifier = Modifier
                    .size(180.dp)
                    .clip(CircleShape)
                    .background(accent)
                    .border(width = 2.dp, color = R1.Hairline, shape = CircleShape)
                    .r1Pressable(
                        onClick = {
                            when (state) {
                                is VoiceSatelliteEngine.State.Listening,
                                is VoiceSatelliteEngine.State.Connecting,
                                -> engine.stop()
                                else -> {
                                    if (!hasMicPerm) {
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
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                // Bare glyph: a single dot when idle, vertical bars while
                // listening. Kept low-fi rather than dragging in vector-asset
                // tooling.
                Text(
                    text = when (state) {
                        is VoiceSatelliteEngine.State.Listening -> "STOP"
                        is VoiceSatelliteEngine.State.Connecting -> "…"
                        is VoiceSatelliteEngine.State.Thinking -> "THINK"
                        is VoiceSatelliteEngine.State.Speaking -> "SPEAK"
                        else -> "TALK"
                    },
                    style = R1.titleCard,
                    color = R1.Bg,
                )
            }

            Spacer(Modifier.height(32.dp))

            // Transcript panel — STT result on top, assistant response below.
            // Both fade in as the pipeline progresses through stt-end and
            // intent-end events.
            val sttText = (state as? VoiceSatelliteEngine.State.Thinking)?.sttText
                ?: (state as? VoiceSatelliteEngine.State.Speaking)?.sttText
                ?: (state as? VoiceSatelliteEngine.State.Done)?.sttText
            val response = (state as? VoiceSatelliteEngine.State.Speaking)?.responseText
                ?: (state as? VoiceSatelliteEngine.State.Done)?.responseText
            if (!sttText.isNullOrBlank()) {
                Text(
                    text = "You: $sttText",
                    style = R1.body,
                    color = R1.Ink,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            if (!response.isNullOrBlank()) {
                Text(
                    text = "HA: $response",
                    style = R1.body,
                    color = R1.AccentCool,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }

            if (state is VoiceSatelliteEngine.State.Error) {
                Spacer(Modifier.height(24.dp))
                R1Button(
                    text = "RETRY",
                    onClick = {
                        engine.start(
                            pipelineId = null,
                            conversationId = null,
                            appContext = context,
                        )
                    },
                )
            }
        }
    }
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
    is VoiceSatelliteEngine.State.Connecting -> "Opening pipeline…"
    is VoiceSatelliteEngine.State.Listening -> "Tap to stop"
    is VoiceSatelliteEngine.State.Thinking -> "Running intent"
    is VoiceSatelliteEngine.State.Speaking -> "Playing response"
    is VoiceSatelliteEngine.State.Done -> "Tap to talk again"
    is VoiceSatelliteEngine.State.Error -> state.message
}
