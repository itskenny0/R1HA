package com.github.itskenny0.r1ha.wear.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.OutlinedButton
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TextField
import androidx.wear.compose.material.TimeText
import com.github.itskenny0.r1ha.core.prefs.ServerConfig
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.TokenStore
import com.github.itskenny0.r1ha.core.prefs.Tokens
import com.github.itskenny0.r1ha.feature.onboarding.UrlNormalizer
import kotlinx.coroutines.launch

/**
 * First-run screen for the Wear OS app.
 *
 * Unlike the phone app, there is no OAuth / browser flow on the watch — the screen
 * only accepts a Long-Lived Access Token (LLAT) and a server URL.  The user obtains
 * the LLAT on their phone/desktop from HA's Profile → Security → Long-lived access
 * tokens and transfers it to the watch manually (or via a companion phone app in a
 * future enhancement).
 *
 * On successful save the [onConnected] callback navigates to [WearRoutes.CARD_STACK].
 */
@Composable
fun WearOnboardingScreen(
    settings: SettingsRepository,
    tokens: TokenStore,
    onConnected: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var serverUrl by remember { mutableStateOf("") }
    var accessToken by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val listState = rememberScalingLazyListState()

    Scaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    text = "HA Watch",
                    style = MaterialTheme.typography.title2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                Text(
                    text = "Server URL",
                    style = MaterialTheme.typography.caption1,
                    color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
                )
            }

            item {
                TextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it; errorMsg = null },
                    placeholder = { Text("https://ha.local:8123", fontSize = 11.sp) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
            }

            item {
                Text(
                    text = "Access Token",
                    style = MaterialTheme.typography.caption1,
                    color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
                )
            }

            item {
                TextField(
                    value = accessToken,
                    onValueChange = { accessToken = it; errorMsg = null },
                    placeholder = { Text("Long-lived token", fontSize = 11.sp) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
            }

            errorMsg?.let { msg ->
                item {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }

            item {
                Button(
                    onClick = {
                        val url = serverUrl.trim()
                        val token = accessToken.trim()
                        if (url.isBlank()) {
                            errorMsg = "Server URL required"
                            return@Button
                        }
                        if (token.isBlank()) {
                            errorMsg = "Access token required"
                            return@Button
                        }
                        saving = true
                        scope.launch {
                            runCatching {
                                val normalized = UrlNormalizer.normalize(url)
                                    ?: throw IllegalArgumentException("Invalid URL")
                                tokens.save(
                                    Tokens(
                                        accessToken = token,
                                        refreshToken = "",
                                        expiresAtMillis = Long.MAX_VALUE,
                                    )
                                )
                                settings.update { it.copy(server = ServerConfig(url = normalized)) }
                            }.onSuccess {
                                onConnected()
                            }.onFailure { e ->
                                errorMsg = e.message ?: "Failed to save settings"
                                saving = false
                            }
                        }
                    },
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(0.7f),
                ) {
                    Text(if (saving) "Saving…" else "Connect")
                }
            }
        }
    }
}
