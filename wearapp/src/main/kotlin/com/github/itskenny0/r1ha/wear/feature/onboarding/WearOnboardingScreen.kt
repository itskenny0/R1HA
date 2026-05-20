package com.github.itskenny0.r1ha.wear.feature.onboarding

import android.app.Activity
import android.app.RemoteInput
import android.os.Bundle
import android.text.InputType
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.input.RemoteInputIntentHelper
import com.github.itskenny0.r1ha.core.prefs.ServerConfig
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.TokenStore
import com.github.itskenny0.r1ha.core.prefs.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

private const val CLIENT_ID = "https://itskenny0.github.io/Rabbit-R1-HA/"
private const val REDIRECT_URI = "r1ha://auth-callback"

/**
 * First-run screen for the Wear OS app.
 *
 * The user enters their Home Assistant server URL plus username and password.
 * This screen performs HA's three-step credential login flow entirely on the
 * watch, so no long-lived access token needs to be generated and manually
 * typed on the small watch keyboard:
 *
 *  1. POST /auth/login_flow → obtain a flow_id
 *  2. POST /auth/login_flow/{flow_id} with username + password → obtain an auth code
 *  3. POST /auth/token with the auth code → obtain access + refresh tokens
 *
 * On successful save the [onConnected] callback navigates to [WearRoutes.CARD_STACK].
 */
@Composable
fun WearOnboardingScreen(
    settings: SettingsRepository,
    tokens: TokenStore,
    http: OkHttpClient,
    onConnected: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var serverUrl by rememberSaveable { mutableStateOf("") }
    var username  by rememberSaveable { mutableStateOf("") }
    var password  by rememberSaveable { mutableStateOf("") }
    var saving    by rememberSaveable { mutableStateOf(false) }
    var errorMsg  by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(timeText = { TimeText() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Sign in to HA",
                style = MaterialTheme.typography.title2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(4.dp))

            // Each field is a Chip that opens the watch's native RemoteInput
            // keyboard dialog — the only reliable text input method on Wear OS,
            // especially on Samsung Galaxy Watch where the standard TextField
            // IME connection resets to one character per keystroke.
            WearInputChip(
                label = "Server URL",
                value = serverUrl,
                placeholder = "hass.example.com",
                inputKey = "server_url",
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
                onValueChange = { serverUrl = it; errorMsg = null },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "Just the hostname — https:// is added automatically",
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
                textAlign = TextAlign.Center,
                fontSize = 9.sp,
                modifier = Modifier.fillMaxWidth(),
            )

            WearInputChip(
                label = "Username",
                value = username,
                placeholder = "homeassistant",
                inputKey = "username",
                onValueChange = { username = it; errorMsg = null },
                modifier = Modifier.fillMaxWidth(),
            )

            WearInputChip(
                label = "Password",
                value = password,
                placeholder = "tap to enter",
                inputKey = "password",
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                maskValue = true,
                onValueChange = { password = it; errorMsg = null },
                modifier = Modifier.fillMaxWidth(),
            )

            if (errorMsg != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = errorMsg!!,
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.error,
                    textAlign = TextAlign.Center,
                    fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(4.dp))

            Button(
                onClick = {
                    val rawUrl = serverUrl.trim()
                    val user = username.trim()
                    val pass = password
                    when {
                        rawUrl.isBlank() -> { errorMsg = "Server URL required"; return@Button }
                        user.isBlank()   -> { errorMsg = "Username required"; return@Button }
                        pass.isBlank()   -> { errorMsg = "Password required"; return@Button }
                    }
                    saving = true
                    errorMsg = null
                    scope.launch {
                        runCatching {
                            val normalized =
                                com.github.itskenny0.r1ha.feature.onboarding.normalizeServerUrl(rawUrl)
                            withContext(Dispatchers.IO) {
                                loginWithCredentials(http, normalized, user, pass)
                            }.let { (accessToken, refreshToken, expiresIn) ->
                                tokens.save(
                                    Tokens(
                                        accessToken = accessToken,
                                        refreshToken = refreshToken,
                                        expiresAtMillis = System.currentTimeMillis() + expiresIn * 1_000L,
                                    )
                                )
                                settings.update { it.copy(server = ServerConfig(url = normalized)) }
                            }
                        }.onSuccess {
                            onConnected()
                        }.onFailure { e ->
                            errorMsg = e.message ?: "Connection failed"
                            saving = false
                        }
                    }
                },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(0.7f),
            ) {
                Text(if (saving) "Signing in…" else "Connect")
            }
        }
    }
}

/**
 * A Chip that opens the Wear OS native keyboard dialog (RemoteInput) when tapped.
 *
 * Unlike Material3's TextField, which loses IME state on Samsung Galaxy Watch
 * (Samsung's keyboard operates as a separate Activity that returns text via
 * ActivityResult, not via the inline InputConnection protocol), RemoteInput is
 * the official Wear OS text-input mechanism and works correctly on all devices.
 *
 * Tapping the chip opens the watch's input method (keyboard / voice / emoji),
 * the user completes input and taps "Done", and the full text is returned to
 * [onValueChange] in one shot — no per-character accumulation issues.
 */
@Composable
private fun WearInputChip(
    label: String,
    value: String,
    placeholder: String,
    inputKey: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    maskValue: Boolean = false,
    inputType: Int = InputType.TYPE_CLASS_TEXT,
) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val bundle = RemoteInput.getResultsFromIntent(result.data ?: return@rememberLauncherForActivityResult)
            val text = bundle?.getCharSequence(inputKey)?.toString()
            if (text != null) onValueChange(text)
        }
    }

    Chip(
        onClick = {
            val extras = Bundle().apply { putInt("android.view.inputmethod.InputType", inputType) }
            val remoteInput = RemoteInput.Builder(inputKey).setLabel(label).addExtras(extras).build()
            val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
            RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(remoteInput))
            // Also put the type hint directly on the intent in case the
            // keyboard activity reads it there instead of from the RemoteInput bundle.
            intent.putExtra("android.view.inputmethod.InputType", inputType)
            launcher.launch(intent)
        },
        label = {
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                    fontSize = 9.sp,
                )
                val display = when {
                    value.isBlank() -> placeholder
                    maskValue       -> "•".repeat(minOf(value.length, 12))
                    else            -> value
                }
                Text(
                    text = display,
                    style = MaterialTheme.typography.body2,
                    color = if (value.isBlank())
                        MaterialTheme.colors.onSurface.copy(alpha = 0.4f)
                    else
                        MaterialTheme.colors.onSurface,
                    maxLines = 1,
                )
            }
        },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = modifier,
    )
}


/**
 * Performs HA's three-step credential login flow and returns
 * (accessToken, refreshToken, expiresInSeconds).
 *
 * Must be called on an IO dispatcher.
 */
private fun loginWithCredentials(
    http: OkHttpClient,
    baseUrl: String,
    username: String,
    password: String,
): Triple<String, String, Long> {
    val json = "application/json".toMediaType()

    // 1. Start login flow
    val startBody = JSONObject().apply {
        put("client_id", CLIENT_ID)
        put("handler", org.json.JSONArray().apply { put("homeassistant"); put(JSONObject.NULL) })
        put("redirect_uri", REDIRECT_URI)
    }.toString().toRequestBody(json)

    val flowId = http.newCall(
        Request.Builder().url("$baseUrl/auth/login_flow").post(startBody).build()
    ).execute().use { resp ->
        val body = resp.body?.string() ?: throw IllegalStateException("Empty response from server")
        if (!resp.isSuccessful) throw IllegalStateException("Server error ${resp.code}: ${body.take(200)}")
        JSONObject(body).getString("flow_id")
    }

    // 2. Submit credentials
    val credBody = JSONObject().apply {
        put("client_id", CLIENT_ID)
        put("username", username)
        put("password", password)
    }.toString().toRequestBody(json)

    val authCode = http.newCall(
        Request.Builder().url("$baseUrl/auth/login_flow/$flowId").post(credBody).build()
    ).execute().use { resp ->
        val bodyStr = resp.body?.string() ?: throw IllegalStateException("Empty credential response")
        val obj = try { JSONObject(bodyStr) } catch (e: Exception) {
            throw IllegalStateException("Unexpected response (${resp.code}): ${bodyStr.take(200)}")
        }
        when (obj.optString("type")) {
            "create_entry" -> obj.optString("result").takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("No auth code in response: ${bodyStr.take(200)}")
            "form" -> {
                val stepId = obj.optString("step_id")
                if (stepId == "mfa") throw IllegalStateException("MFA is required — not supported on watch yet")
                val errKey = obj.optJSONObject("errors")?.optString("base") ?: ""
                throw IllegalStateException(
                    when (errKey) {
                        "invalid_auth" -> "Invalid username or password"
                        "invalid_mfa_code" -> "Invalid MFA code"
                        else -> "Login failed (step=$stepId): ${bodyStr.take(150)}"
                    }
                )
            }
            else -> throw IllegalStateException("Unexpected flow type: ${bodyStr.take(200)}")
        }
    }

    // 3. Exchange auth code for tokens
    val tokenBody = FormBody.Builder()
        .add("grant_type", "authorization_code")
        .add("code", authCode)
        .add("client_id", CLIENT_ID)
        .add("redirect_uri", REDIRECT_URI)
        .build()

    return http.newCall(
        Request.Builder().url("$baseUrl/auth/token").post(tokenBody).build()
    ).execute().use { resp ->
        val bodyStr = resp.body?.string() ?: throw IllegalStateException("Empty token response")
        if (!resp.isSuccessful) throw IllegalStateException("Token exchange failed (${resp.code}): ${bodyStr.take(200)}")
        val obj = JSONObject(bodyStr)
        Triple(
            obj.getString("access_token"),
            obj.optString("refresh_token", ""),
            obj.getLong("expires_in"),
        )
    }
}


