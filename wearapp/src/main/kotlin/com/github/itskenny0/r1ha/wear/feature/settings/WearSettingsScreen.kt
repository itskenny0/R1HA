package com.github.itskenny0.r1ha.wear.feature.settings

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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TextField
import androidx.wear.compose.material.TimeText
import com.github.itskenny0.r1ha.core.prefs.AppSettings
import com.github.itskenny0.r1ha.core.prefs.ServerConfig
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.TokenStore
import com.github.itskenny0.r1ha.core.prefs.Tokens
import com.github.itskenny0.r1ha.feature.onboarding.UrlNormalizer
import kotlinx.coroutines.launch

/**
 * Settings screen — change server URL / token, or disconnect.
 *
 * Deliberately minimal: the watch is not the primary configuration surface.
 * Complex settings (acceleration curves, theme, entity overrides) are
 * managed on the phone app and shared via the same DataStore file.
 */
@Composable
fun WearSettingsScreen(
    settings: SettingsRepository,
    tokens: TokenStore,
    onDisconnect: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val currentSettings by settings.settings.collectAsStateWithLifecycle(
        initialValue = AppSettings()
    )

    var serverUrl by remember(currentSettings.server) {
        mutableStateOf(currentSettings.server?.url ?: "")
    }
    var accessToken by remember { mutableStateOf("") }  // Never pre-fill the token for security.
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    val listState = rememberScalingLazyListState()

    Scaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.title3,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
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
                    onValueChange = { serverUrl = it; saveError = null },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                )
            }

            item {
                Text(
                    text = "New Token (optional)",
                    style = MaterialTheme.typography.caption1,
                    color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
                )
            }

            item {
                TextField(
                    value = accessToken,
                    onValueChange = { accessToken = it; saveError = null },
                    placeholder = { Text("Leave blank to keep current") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                )
            }

            saveError?.let { err ->
                item {
                    Text(
                        text = err,
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.error,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            item {
                Button(
                    onClick = {
                        val url = serverUrl.trim()
                        if (url.isBlank()) { saveError = "URL required"; return@Button }
                        saving = true
                        scope.launch {
                            runCatching {
                                val normalized = UrlNormalizer.normalize(url)
                                    ?: throw IllegalArgumentException("Invalid URL")
                                if (accessToken.isNotBlank()) {
                                    tokens.save(
                                        Tokens(
                                            accessToken = accessToken.trim(),
                                            refreshToken = "",
                                            expiresAtMillis = Long.MAX_VALUE,
                                        )
                                    )
                                }
                                settings.update { it.copy(server = ServerConfig(url = normalized)) }
                            }.onSuccess {
                                onBack()
                            }.onFailure { e ->
                                saveError = e.message ?: "Save failed"
                                saving = false
                            }
                        }
                    },
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(0.7f),
                ) {
                    Text(if (saving) "Saving…" else "Save")
                }
            }

            item {
                // Disconnect clears settings and returns to onboarding.
                Chip(
                    label = { Text("Disconnect") },
                    onClick = {
                        scope.launch {
                            settings.update { it.copy(server = null) }
                            tokens.clear()
                            onDisconnect()
                        }
                    },
                    colors = ChipDefaults.outlinedChipColors(
                        contentColor = MaterialTheme.colors.error,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }
}
