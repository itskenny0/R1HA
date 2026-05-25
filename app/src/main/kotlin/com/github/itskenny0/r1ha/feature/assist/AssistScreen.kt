package com.github.itskenny0.r1ha.feature.assist

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import com.github.itskenny0.r1ha.ui.layout.AdaptiveContent
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.github.itskenny0.r1ha.ui.i18n.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1Button
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.components.r1RowPressable
import kotlinx.coroutines.launch

/**
 * Text-mode HA Assist surface — pipes a typed prompt into
 * `/api/conversation/process` and renders the response as a chat-style
 * transcript. Multi-turn context is threaded via the conversation_id HA
 * returns, so the user can chain prompts ("turn off the light" → "and
 * the kitchen one too") and HA's intent engine keeps the device-class
 * carry-forward.
 *
 * Audio (STT/TTS via the Assist pipeline WS) is a later iteration — the
 * R1 has a mic + speaker, so we can layer it on without re-architecting
 * the transcript model. The text path is the foundation.
 */
@Composable
fun AssistScreen(
    haRepository: HaRepository,
    settings: com.github.itskenny0.r1ha.core.prefs.SettingsRepository,
    wheelInput: com.github.itskenny0.r1ha.core.input.WheelInput,
    onBack: () -> Unit,
    /** Optional: open the voice-satellite surface. Null hides the chip. */
    onOpenVoiceSatellite: (() -> Unit)? = null,
) {
    val vm: AssistViewModel = viewModel(factory = AssistViewModel.factory(haRepository, settings))
    val ui by vm.ui.collectAsState()
    val listState = rememberLazyListState()
    // Wheel scroll for the transcript — long conversations span more
    // than one screen + the user wants to scroll back without
    // touching.
    com.github.itskenny0.r1ha.ui.components.WheelScrollFor(
        wheelInput = wheelInput,
        listState = listState,
        settings = settings,
    )
    // No auto-scroll-to-latest needed: the LazyColumn uses reverseLayout = true so
    // index 0 (the newest message, when we feed it messages.reversed()) is anchored
    // to the bottom of the viewport. New messages and IME-driven viewport shrinks
    // both keep the newest bubble in view automatically, which avoids the visible
    // 'second keyboard-length shift' that an animateScrollToItem on top of
    // imePadding produced.
    val focus = remember { FocusRequester() }
    // Honour the user's auto-open preference. Default OFF: opening
    // Assist no longer pops the keyboard automatically — the user
    // reported the empty-state recentering jarringly when the IME
    // shrinks the transcript area on a phone. Tapping the input
    // field on entry is one extra tap but keeps the layout stable.
    val appSettings by settings.settings.collectAsState(
        initial = com.github.itskenny0.r1ha.core.prefs.AppSettings(),
    )
    LaunchedEffect(appSettings.behavior.assistAutoOpenKeyboard) {
        if (appSettings.behavior.assistAutoOpenKeyboard) {
            kotlinx.coroutines.delay(80)
            runCatching { focus.requestFocus() }
        }
    }
    // Collect pre-filled drafts pushed by other screens (e.g. SearchScreen's
    // empty-state 'Ask Assist about <query>' CTA). The bus uses SharedFlow with
    // capacity 1 + DROP_OLDEST, so a draft staged before AssistScreen first
    // composes still gets picked up on its first frame.
    LaunchedEffect(Unit) {
        com.github.itskenny0.r1ha.core.util.AssistDraftBus.drafts.collect { staged ->
            vm.setDraft(staged)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding()
            .imePadding(),
    ) {
        // Agent picker dialog state — local to the screen because it's a
        // pure UI toggle. The agent id itself lives in settings so it
        // persists across navigations / restarts.
        val agentDialogOpen = remember { mutableStateOf(false) }
        val agentScope = rememberCoroutineScope()
        R1TopBar(
            title = "ASSIST",
            onBack = onBack,
            action = {
                // Tiny chip showing the currently-configured conversation
                // agent (or 'DEFAULT' when null). Tap opens the dialog so
                // users with multiple agents (OpenAI + local Llama, etc.)
                // can pick which one answers without round-tripping
                // through HA's web UI.
                val current = appSettings.behavior.assistAgentId
                Box(
                    modifier = Modifier
                        .clip(R1.ShapeS)
                        .background(R1.SurfaceMuted)
                        .border(1.dp, R1.Hairline, R1.ShapeS)
                        .r1Pressable(
                            onClick = { agentDialogOpen.value = true },
                            contentDescription = "Pick conversation agent",
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "AGENT: ${current?.take(18) ?: "DEFAULT"}",
                        style = R1.labelMicro,
                        color = R1.InkSoft,
                    )
                }
                if (onOpenVoiceSatellite != null) {
                    androidx.compose.foundation.layout.Spacer(
                        modifier = Modifier.width(6.dp),
                    )
                    Box(
                        modifier = Modifier
                            .clip(R1.ShapeS)
                            .background(R1.SurfaceMuted)
                            .border(1.dp, R1.Hairline, R1.ShapeS)
                            .r1Pressable(
                                onClick = onOpenVoiceSatellite,
                                contentDescription = "Open voice satellite",
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = "VOICE",
                            style = R1.labelMicro,
                            color = R1.AccentWarm,
                        )
                    }
                }
            },
        )
        if (agentDialogOpen.value) {
            AgentPickerDialog(
                current = appSettings.behavior.assistAgentId,
                onDismiss = { agentDialogOpen.value = false },
                onApply = { newId ->
                    agentDialogOpen.value = false
                    agentScope.launch {
                        settings.update { s ->
                            s.copy(behavior = s.behavior.copy(
                                assistAgentId = newId?.takeIf { it.isNotBlank() },
                            ))
                        }
                    }
                },
            )
        }
        // On tablets the chat + input stay within 800 dp centred so a long
        // conversation doesn't stretch bubbles across a 1280 dp panel.
        AdaptiveContent(modifier = Modifier.weight(1f)) {
        Column(modifier = Modifier.fillMaxSize()) {
        // Transcript — fills the remainder. Empty state shows a "How can I
        // help?" prompt mirroring HA's own Assist greeting so the screen
        // doesn't look broken before the first send.
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            if (ui.messages.isEmpty()) {
                // Empty-state anchors near the top of the transcript area (not
                // vertically centred) — when the IME opens and shrinks the
                // parent Box, a Center arrangement re-runs and the content
                // visibly jumps upward, which the user reported as 'viewport
                // scrolls up way too high'. Top-anchored content stays put
                // regardless of how the transcript area resizes.
                //
                // verticalScroll wrapper keeps the bottom example-prompt chips
                // reachable when the IME's imePadding() shrinks the parent
                // below the natural content height. Without it the bottom
                // chips slide under the input bar with no way to scroll to
                // them.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 22.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.Top,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(text = "HA ASSIST", style = R1.sectionHeader, color = R1.AccentWarm)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Type below or tap one of these prompts to start.",
                        style = R1.body,
                        color = R1.InkMuted,
                    )
                    Spacer(Modifier.height(14.dp))
                    val examples = listOf(
                        "Turn off the kitchen light",
                        "What's the temperature in the bedroom?",
                        "Run the dinner scene",
                        "Is anyone home?",
                    )
                    val isTablet = com.github.itskenny0.r1ha.ui.layout.currentWidthTier() ==
                        com.github.itskenny0.r1ha.ui.layout.WidthTier.TABLET
                    // 2-column grid on tablets (more horizontal room), single
                    // column on phones and R1.
                    if (isTablet) {
                        val rows = examples.chunked(2)
                        for (row in rows) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                for (example in row) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(R1.ShapeS)
                                            .background(R1.SurfaceMuted)
                                            .border(1.dp, R1.Hairline, R1.ShapeS)
                                            .r1Pressable(onClick = { vm.setDraft(example); vm.send() })
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                    ) {
                                        Text(text = example, style = R1.body, color = R1.Ink, maxLines = 2)
                                    }
                                }
                                // If row has only 1 item, fill the second slot with empty weight
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    } else {
                        for (example in examples) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .clip(R1.ShapeS)
                                    .background(R1.SurfaceMuted)
                                    .border(1.dp, R1.Hairline, R1.ShapeS)
                                    .r1Pressable(onClick = {
                                        vm.setDraft(example)
                                        vm.send()
                                    })
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                Text(text = example, style = R1.body, color = R1.Ink, maxLines = 2)
                            }
                        }
                    }
                }
            } else {
                // reverseLayout = true anchors the LazyColumn to the BOTTOM of its
                // viewport: declared item 0 sits just above the input row, item 1
                // above it, and so on upward. We feed it messages.reversed() so the
                // newest message is index 0 (bottom-most). When the IME opens and
                // imePadding shrinks the parent, the bottom edge stays fixed against
                // the IME — the newest bubble remains visible without any
                // animateScrollToItem on top, which was the source of the second
                // visible 'keyboard-length' shift. Older messages spill upward and
                // can be reached by scrolling.
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    reverseLayout = true,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
                ) {
                    // In-flight pip belongs at the very bottom (visually just above
                    // the input row). With reverseLayout the FIRST declared item is
                    // bottom-most, so this slot comes before the messages.
                    if (ui.inFlight) {
                        item("__inflight") {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(R1.ShapeS)
                                        .background(R1.SurfaceMuted)
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                ) {
                                    // Animate the trailing dots so a slow local-LLM Assist call
                                    // reads as "working" rather than "frozen". One dot at 0-500 ms,
                                    // two at 500-1000, three at 1000-1500, cycling.
                                    val transition = rememberInfiniteTransition(label = "assist-inflight")
                                    val phase by transition.animateFloat(
                                        initialValue = 0f,
                                        targetValue = 3f,
                                        animationSpec = infiniteRepeatable(
                                            animation = tween(durationMillis = 1500),
                                            repeatMode = RepeatMode.Restart,
                                        ),
                                        label = "assist-inflight-phase",
                                    )
                                    val dots = (phase.toInt().coerceIn(0, 2) + 1)
                                    Text(text = ".".repeat(dots), style = R1.labelMicro, color = R1.InkMuted)
                                }
                            }
                        }
                    }
                    items(items = ui.messages.asReversed(), key = { it.id }) { msg ->
                        AssistBubble(msg)
                    }
                }
            }
        }
        // Macro chip row — saved user prompts that fire on a single tap. Hidden
        // when empty so the input area doesn't reserve space until the user
        // actually saves one. Long-press a chip to delete it (with toast
        // confirmation so an accidental long-press is recoverable). Horizontal
        // scroll handles overflow when the row of chips exceeds the screen
        // width instead of wrapping and pushing the input row down.
        val macros = appSettings.behavior.assistMacros
        if (macros.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (macro in macros) {
                    Box(
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .clip(R1.ShapeS)
                            .background(R1.SurfaceMuted)
                            .border(1.dp, R1.Hairline, R1.ShapeS)
                            .r1RowPressable(
                                onTap = { vm.sendMacro(macro) },
                                onLongPress = {
                                    vm.deleteMacro(macro)
                                    com.github.itskenny0.r1ha.core.util.Toaster.show(
                                        "Macro removed",
                                    )
                                },
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = macro.take(40) + if (macro.length > 40) "…" else "",
                            style = R1.labelMicro,
                            color = R1.InkSoft,
                        )
                    }
                }
            }
        }
        // Input row — text field + SEND button. Plus a small RESET chip on
        // the left so the user can drop the conversation_id and start fresh
        // without backing out.
        // Voice-input launcher — fires the system RecognizerIntent which
        // shows the standard Android mic dialog. Returns the recognised
        // text via the activity result; we drop it into the draft field
        // and immediately send. No RECORD_AUDIO permission needed by the
        // app because the recognition UI runs in the system process.
        val voiceLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            val matches = result.data?.getStringArrayListExtra(
                android.speech.RecognizerIntent.EXTRA_RESULTS,
            )
            val best = matches?.firstOrNull()?.takeIf { it.isNotBlank() }
            if (best != null) {
                vm.setDraft(best)
                vm.send()
            } else {
                // System speech recognizer returned no usable transcript. Surface a
                // soft hint so the user knows the mic tap landed; otherwise the silent
                // bounce-back from the dialog is indistinguishable from a no-op tap.
                com.github.itskenny0.r1ha.core.util.Toaster.show("No speech captured")
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .border(1.dp, R1.Hairline, R1.ShapeS)
                    .r1Pressable(onClick = { vm.reset() })
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Text(text = "↺", style = R1.labelMicro, color = R1.InkSoft)
            }
            Spacer(Modifier.width(4.dp))
            // Save-as-macro chip — turns the current draft into a saved macro
            // chip rendered above the input row. Disabled when the draft is
            // blank so an empty tap doesn't save an unusable empty macro.
            // Filled accent when active so the affordance reads as "this will
            // do something" rather than dead chrome.
            val saveActive = ui.draft.isNotBlank()
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(
                        if (saveActive) R1.AccentWarm.copy(alpha = 0.18f)
                        else R1.Bg,
                    )
                    .border(
                        1.dp,
                        if (saveActive) R1.AccentWarm.copy(alpha = 0.5f) else R1.Hairline,
                        R1.ShapeS,
                    )
                    .r1Pressable(
                        onClick = { if (saveActive) vm.saveCurrentDraftAsMacro() },
                        contentDescription = "Save draft as macro",
                    )
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Text(
                    text = "★+",
                    style = R1.labelMicro,
                    color = if (saveActive) R1.AccentWarm else R1.InkMuted,
                )
            }
            Spacer(Modifier.width(4.dp))
            // Voice button — fires the system speech recognizer. Disabled
            // while a send is in flight so a quick voice tap doesn't queue a
            // second prompt over the first. Same hand-drawn AssistMicGlyph as
            // the chrome-row mic so the two surfaces agree on the iconography.
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .border(1.dp, R1.Hairline, R1.ShapeS)
                    .r1Pressable(onClick = {
                        if (ui.inFlight) return@r1Pressable
                        val intent = android.content.Intent(
                            android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH,
                        ).apply {
                            putExtra(
                                android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                            )
                            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Ask HA…")
                            putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                        }
                        // Some R1 ROMs (CipherOS especially) might not have a
                        // speech-recognition service installed — surface a toast
                        // rather than crashing on ActivityNotFoundException.
                        runCatching { voiceLauncher.launch(intent) }
                            .onFailure {
                                com.github.itskenny0.r1ha.core.util.Toaster.error(
                                    "No speech recognizer on this device",
                                )
                            }
                    })
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                com.github.itskenny0.r1ha.ui.components.AssistMicGlyph(size = 14.dp)
            }
            Spacer(Modifier.width(6.dp))
            Box(modifier = Modifier.weight(1f)) {
                R1TextField(
                    value = ui.draft,
                    onValueChange = { vm.setDraft(it) },
                    placeholder = "ask HA…",
                    monospace = false,
                    focusRequester = focus,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { vm.send() }),
                )
            }
            Spacer(Modifier.width(6.dp))
            // While in flight the SEND button morphs into STOP so a slow Assist
            // call (local-LLM agents take 5-30s on weaker hardware) is
            // interruptible instead of just disabled.
            R1Button(
                text = if (ui.inFlight) "STOP" else "SEND",
                onClick = { if (ui.inFlight) vm.cancel() else vm.send() },
                enabled = ui.inFlight || ui.draft.isNotBlank(),
                modifier = Modifier.widthIn(min = 64.dp),
            )
        }
        } // inner Column (transcript + input)
        } // AdaptiveContent
    }
}

@Composable
private fun AssistBubble(msg: AssistMessage) {
    val isUser = msg.fromUser
    val isError = msg.responseType == "error"
    val bg = when {
        isError -> R1.StatusRed.copy(alpha = 0.18f)
        isUser -> R1.AccentWarm.copy(alpha = 0.18f)
        else -> R1.SurfaceMuted
    }
    val textColor = when {
        isError -> R1.StatusRed
        else -> R1.Ink
    }
    // Long-press copies the bubble text. Useful for: replaying a working prompt
    // ("turn off the kitchen light" → reuse with a tweak), grabbing HA's response
    // (a sensor reading, a state list) to paste into a notes app, and quoting an
    // error message into a bug report. Long-press is the cheapest gesture that
    // doesn't conflict with tap-to-do-nothing (the rest of the bubble currently
    // has no tap affordance).
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    // Bubble max-width scales with the available container: narrower on R1/phones
    // so bubbles don't fill the whole width; wider on tablets (the chat is inside
    // an 800 dp AdaptiveContent island) so longer responses don't word-wrap
    // into single-word lines.
    val bubbleMaxWidth = if (com.github.itskenny0.r1ha.ui.layout.currentWidthTier() ==
        com.github.itskenny0.r1ha.ui.layout.WidthTier.TABLET) 540.dp else 240.dp
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = bubbleMaxWidth)
                .clip(R1.ShapeS)
                .background(bg)
                .border(1.dp, if (isUser) R1.AccentWarm else R1.Hairline, R1.ShapeS)
                .r1RowPressable(
                    onTap = {},
                    onLongPress = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(msg.text))
                        com.github.itskenny0.r1ha.core.util.Toaster.show("Copied")
                    },
                )
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(text = msg.text, style = R1.body, color = textColor)
        }
    }
}

/**
 * Conversation-agent picker. Free-form text input rather than a fetched list
 * because HA's WS API for enumerating agents/pipelines isn't wired through
 * this client yet — manual entry is the pragmatic MVP. Common values:
 * `homeassistant` (HA's built-in intent agent), `conversation.openai_conversation`
 * (the OpenAI integration), or a pipeline UUID for the assist_pipeline path.
 * Empty / blank input clears the override and routes back to HA's default.
 */
@Composable
private fun AgentPickerDialog(
    current: String?,
    onDismiss: () -> Unit,
    onApply: (String?) -> Unit,
) {
    var draft by remember { mutableStateOf(current.orEmpty()) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = R1.Bg,
        title = {
            Text(text = "CONVERSATION AGENT", style = R1.sectionHeader, color = R1.Ink)
        },
        text = {
            Column {
                Text(
                    text = "Agent ID to route Assist requests through. Leave blank to " +
                        "use HA's default. Examples:",
                    style = R1.body,
                    color = R1.InkMuted,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "homeassistant\nconversation.openai_conversation\n<pipeline UUID>",
                    style = R1.labelMicro,
                    color = R1.InkSoft,
                )
                Spacer(Modifier.height(10.dp))
                R1TextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = "homeassistant",
                    monospace = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onApply(draft) }),
                )
            }
        },
        confirmButton = {
            R1Button(
                text = "APPLY",
                onClick = { onApply(draft) },
            )
        },
        dismissButton = {
            R1Button(
                text = "USE DEFAULT",
                onClick = { onApply(null) },
                variant = com.github.itskenny0.r1ha.ui.components.R1ButtonVariant.Outlined,
            )
        },
    )
}
