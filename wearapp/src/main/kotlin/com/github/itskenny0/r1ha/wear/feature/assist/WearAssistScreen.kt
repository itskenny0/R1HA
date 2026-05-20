package com.github.itskenny0.r1ha.wear.feature.assist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.feature.assist.AssistMessage
import com.github.itskenny0.r1ha.feature.assist.AssistViewModel

/**
 * Wear OS HA Assist screen.
 *
 * Pipes typed prompts into HA's `/api/conversation/process` and renders
 * the conversation as a scrolling transcript. Multi-turn context is
 * threaded via conversation_id — same logic as the phone screen but
 * in a Wear-idiomatic layout.
 *
 * The transcript lives in a [LazyColumn] (reverseLayout = true so the
 * newest message anchors at the bottom). The input field sits below it.
 * The watch's built-in keyboard handles text entry.
 */
@Composable
fun WearAssistScreen(
    haRepository: HaRepository,
    onBack: () -> Unit,
) {
    val vm: AssistViewModel = viewModel(
        factory = AssistViewModel.factory(haRepository),
    )
    val ui by vm.ui.collectAsState()
    val listState = rememberLazyListState()

    // Scroll to latest message whenever the list grows.
    LaunchedEffect(ui.messages.size) {
        if (ui.messages.isNotEmpty()) {
            listState.animateScrollToItem(0) // reverseLayout = true, index 0 = newest
        }
    }

    Scaffold(timeText = { TimeText() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
        ) {
            // Title
            Text(
                text = "🎤 Assist",
                style = MaterialTheme.typography.title3,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 4.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )

            // Transcript
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (ui.inFlight) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(2.dp),
                            horizontalArrangement = Arrangement.Start,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .width(16.dp)
                                    .height(16.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                }
                items(ui.messages.reversed()) { msg ->
                    AssistBubble(msg)
                }
                if (ui.messages.isEmpty() && !ui.inFlight) {
                    item {
                        Text(
                            text = "How can I help?",
                            style = MaterialTheme.typography.body2,
                            color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // Input row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextField(
                    value = ui.draft,
                    onValueChange = { vm.setDraft(it) },
                    placeholder = { Text("Ask HA…", style = MaterialTheme.typography.caption2) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { vm.send() }),
                    colors = TextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colors.surface,
                        focusedContainerColor = MaterialTheme.colors.surface,
                    ),
                    textStyle = MaterialTheme.typography.caption1.copy(
                        color = MaterialTheme.colors.onSurface,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(4.dp))
                Button(
                    onClick = { vm.send() },
                    enabled = ui.draft.isNotBlank() && !ui.inFlight,
                    colors = ButtonDefaults.primaryButtonColors(),
                    modifier = Modifier
                        .width(40.dp)
                        .height(40.dp),
                ) {
                    Text("▶", style = MaterialTheme.typography.caption2)
                }
            }
        }
    }
}

@Composable
private fun AssistBubble(msg: AssistMessage) {
    val bgColor = when {
        msg.fromUser -> MaterialTheme.colors.primary.copy(alpha = 0.25f)
        msg.responseType == "error" -> MaterialTheme.colors.error.copy(alpha = 0.2f)
        else -> MaterialTheme.colors.surface
    }
    Box(
        modifier = Modifier
            .then(
                if (msg.fromUser)
                    Modifier.padding(start = 24.dp, end = 4.dp)
                else
                    Modifier.padding(start = 4.dp, end = 24.dp)
            )
            .clip(RoundedCornerShape(8.dp))
            .let {
                it.then(
                    Modifier.background(bgColor)
                )
            }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = msg.text,
            style = MaterialTheme.typography.caption1,
            color = MaterialTheme.colors.onSurface,
        )
    }
}
