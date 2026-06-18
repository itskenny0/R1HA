package com.github.itskenny0.r1ha.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.prefs.ServerConfig
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.TokenStore
import com.github.itskenny0.r1ha.core.prefs.Tokens
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

class OnboardingViewModel(
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
    private val tokens: TokenStore,
) : ViewModel() {

    sealed interface State {
        data object Idle : State
        data object Probing : State
        /**
         * Server responded with an authorize URL ready to open in the WebView. Carries
         * [baseUrl] alongside [authorizeUrl] so the token exchange POSTs to the same path-
         * prefixed HA installation the user signed into (e.g. `https://example.com/ha`).
         * Deriving the base from `authorizeUrl`'s host alone would strip the `/ha` prefix
         * and POST to the wrong path.
         */
        data class ReadyToAuth(val authorizeUrl: String, val baseUrl: String) : State
        data object Exchanging : State
        data object Done : State
        data class Error(val message: String) : State
    }

    private val _state: MutableStateFlow<State> = MutableStateFlow(State.Idle)
    val state: StateFlow<State> get() = _state

    @Serializable
    data class TokenResponse(
        @SerialName("access_token") val access_token: String,
        @SerialName("refresh_token") val refresh_token: String,
        @SerialName("expires_in") val expires_in: Long,
        @SerialName("token_type") val token_type: String,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Normalises whatever the user typed into a usable server base URL. The string
     * logic lives in [normalizeServerUrl] in UrlNormalizer.kt so it's unit-testable
     * without the VM's lifecycle / coroutine harness.
     */
    private fun normalizeUrl(raw: String): String = normalizeServerUrl(raw)

    /** Validates [rawUrl], probes reachability, then constructs the OAuth authorize URL. */
    fun probe(rawUrl: String) {
        val baseUrl = normalizeUrl(rawUrl)
        if (baseUrl.isBlank()) {
            _state.value = State.Error("Please enter your Home Assistant URL.")
            Toaster.error("Empty URL")
            return
        }
        R1Log.i("Onboarding.probe", "start baseUrl=$baseUrl")
        _state.value = State.Probing
        viewModelScope.launch {
            try {
                val httpCode = withContext(Dispatchers.IO) {
                    val req = Request.Builder()
                        .url("$baseUrl/auth/authorize?response_type=code&client_id=https%3A%2F%2Fitskenny0.github.io%2FR1HA%2F&redirect_uri=r1ha://auth-callback")
                        // GET, not HEAD: HA serves /auth/authorize as a GET-only
                        // route, and on many setups (aiohttp without auto-HEAD, or
                        // behind a reverse proxy) a HEAD there answers 405, which the
                        // probe then mis-read as "not a Home Assistant login page" and
                        // blocked sign-in even though the page loads fine in a browser.
                        // GET is exactly what the OAuth WebView loads next, so a 200
                        // here means the real login page is reachable. The body (a few
                        // KB of login HTML) is discarded — we only read the status.
                        .get()
                        .build()
                    http.newCall(req).execute().use { it.code }
                }
                R1Log.i("Onboarding.probe", "GET returned HTTP $httpCode")
                // A response is not the same as a usable HA login page: a 404 from
                // some other web server on the host, or a reverse proxy demanding
                // its own auth, would previously sail into the WebView and strand
                // the user on an error page with no hint. Fail here with one.
                val statusProblem = probeStatusProblem(httpCode)
                if (statusProblem != null) {
                    Toaster.error("Probe failed: HTTP $httpCode")
                    _state.value = State.Error(statusProblem)
                    return@launch
                }
                // NB: we deliberately do NOT write settings.server here. Doing so triggered
                // the URL-change observer in HaRepository which tried to connectFromSettings
                // immediately. But tokens haven't been exchanged yet, so it toasted
                // "Authentication tokens missing" before the user even saw the WebView. The
                // URL gets written by exchangeCode() right after a successful token POST.
                val authorizeUrl = "$baseUrl/auth/authorize?response_type=code&client_id=https%3A%2F%2Fitskenny0.github.io%2FR1HA%2F&redirect_uri=r1ha%3A%2F%2Fauth-callback"
                _state.value = State.ReadyToAuth(authorizeUrl = authorizeUrl, baseUrl = baseUrl)
            } catch (e: Exception) {
                R1Log.e("Onboarding.probe", "failed", e)
                Toaster.error("Probe failed: ${e.message}")
                // Exception → actionable-message mapping lives in
                // ProbeDiagnostics.kt (pure, unit-tested): DNS vs route vs TLS
                // vs generic transport, all framed as "couldn't connect" rather
                // than "login failed" since auth only happens later in the WebView.
                _state.value = State.Error(probeFailureMessage(e))
            }
        }
    }

    /** Called by the WebView once the r1ha://auth-callback?code=… redirect is intercepted. */
    fun exchangeCode(code: String, serverUrl: String) {
        R1Log.i("Onboarding.exchange", "start serverUrl=$serverUrl codeLen=${code.length}")
        if (serverUrl.isBlank()) {
            // Defensive: if the WebView screen couldn't extract a serverUrl, bail loudly rather
            // than POST to "/auth/token" (no host) and fail with a vague error.
            _state.value = State.Error("Lost server URL during login; please retry.")
            Toaster.error("Lost server URL")
            return
        }
        _state.value = State.Exchanging
        viewModelScope.launch {
            try {
                val tokenResponse = withContext(Dispatchers.IO) {
                    val body = FormBody.Builder()
                        .add("grant_type", "authorization_code")
                        .add("code", code)
                        .add("client_id", "https://itskenny0.github.io/R1HA/")
                        .add("redirect_uri", "r1ha://auth-callback")
                        .build()
                    val req = Request.Builder()
                        .url("$serverUrl/auth/token")
                        .post(body)
                        .build()
                    http.newCall(req).execute().use { resp ->
                        val bodyStr = resp.body?.string()
                            ?: throw IllegalStateException("Empty token response")
                        if (!resp.isSuccessful) {
                            throw IllegalStateException("Token exchange failed (${resp.code}): $bodyStr")
                        }
                        json.decodeFromString<TokenResponse>(bodyStr)
                    }
                }
                R1Log.i("Onboarding.exchange", "token POST OK; saving")
                val expiresAtMillis = System.currentTimeMillis() + tokenResponse.expires_in * 1_000L
                tokens.save(
                    Tokens(
                        accessToken = tokenResponse.access_token,
                        refreshToken = tokenResponse.refresh_token,
                        expiresAtMillis = expiresAtMillis,
                    )
                )
                R1Log.i("Onboarding.exchange", "tokens.save returned")
                // Re-persist the server URL alongside the tokens. probe() also writes this,
                // but doubling up means a successful login always lands a usable server config
                // even if the probe-time write was lost for any reason.
                R1Log.i("Onboarding.exchange", "calling settings.update(server=$serverUrl)")
                settings.update { it.copy(server = ServerConfig(url = serverUrl)) }
                R1Log.i("Onboarding.exchange", "settings.update returned")
                _state.value = State.Done
                Toaster.show("Sign-in complete")
            } catch (e: Exception) {
                R1Log.e("Onboarding.exchange", "failed", e)
                Toaster.error("Token exchange FAILED: ${e.message}")
                _state.value = State.Error("Token exchange failed: ${e.message}")
            }
        }
    }

    fun resetError() {
        _state.value = State.Idle
    }

    /**
     * Transition to [State.Error] with [message]. Called when the OAuth WebView reports a
     * redirect that didn't carry an authorization code (typically the user tapped Deny). The
     * URL entry form already renders [State.Error] messages, so this gives the user concrete
     * feedback instead of a silent reset.
     */
    fun failOnboarding(message: String) {
        _state.value = State.Error(message)
    }

    companion object {
        fun factory(http: OkHttpClient, settings: SettingsRepository, tokens: TokenStore) =
            viewModelFactory {
                initializer {
                    OnboardingViewModel(http = http, settings = settings, tokens = tokens)
                }
            }
    }
}
