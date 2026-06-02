package com.github.itskenny0.r1ha.core.ha

import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.TokenStore
import com.github.itskenny0.r1ha.core.prefs.Tokens
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Exchanges the stored refresh token for a fresh access token via HA's `/auth/token` endpoint.
 *
 * Home Assistant's access tokens expire after about 30 minutes by default. Without this, the WS
 * connection drops into [ConnectionState.AuthLost] the first time the access token expires —
 * which on the user's side looks like the app silently lost its login. With this, the repository
 * refreshes proactively before reconnect and reactively after an AuthLost transition.
 */
class TokenRefresher(
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
    private val tokens: TokenStore,
    private val clientId: String = "https://itskenny0.github.io/R1HA/",
) {
    @Serializable
    private data class RefreshResponse(
        @SerialName("access_token") val access_token: String,
        // HA's IndieAuth refresh spec says the refresh_token stays constant — but be defensive
        // in case a future HA version rotates them, so we don't end up holding a stale value.
        @SerialName("refresh_token") val refresh_token: String? = null,
        @SerialName("expires_in") val expires_in: Long,
        @SerialName("token_type") val token_type: String,
    )

    private val json = Json { ignoreUnknownKeys = true }

    // Serialise concurrent refresh callers. Without it, two simultaneous ensureFresh()
    // calls (heartbeat poll + WS auth handshake racing on cold start) both POST the same
    // refresh_token to /auth/token. If HA ever rotates refresh tokens the second call
    // races and may invalidate the first. The lock also coalesces work: the second
    // caller waits for the first to finish, re-reads the now-fresh expiry, and short-
    // circuits without making a redundant network round-trip.
    private val refreshMutex = Mutex()

    /**
     * If the stored access token is within [skewMillis] of expiry, exchange the refresh token
     * for a new access token and persist it. Returns true if the token is now valid (either
     * already-valid or freshly refreshed); false if there is no token, no server URL, or the
     * refresh attempt failed.
     */
    suspend fun ensureFresh(skewMillis: Long = 60_000L): Boolean {
        val current = tokens.load() ?: return false
        // Long-lived access token path: refreshToken is the empty sentinel and
        // expiresAtMillis is Long.MAX_VALUE. There's no refresh to do; the
        // caller can proceed with the stored access token as-is. If the LLAT
        // is in fact revoked or expired, HTTP 401s will surface from the
        // repository layer with the usual sign-out toast.
        if (current.refreshToken.isBlank()) return true
        if (current.expiresAtMillis > System.currentTimeMillis() + skewMillis) return true
        return refreshMutex.withLock {
            // Re-read inside the lock so a queued second caller sees the just-refreshed
            // expiry written by the first and skips its own network call.
            val latest = tokens.load() ?: return@withLock false
            if (latest.refreshToken.isBlank()) return@withLock true
            if (latest.expiresAtMillis > System.currentTimeMillis() + skewMillis) return@withLock true
            refresh(latest)
        }
    }

    /** Force a refresh regardless of remaining lifetime. Used after [ConnectionState.AuthLost]. */
    suspend fun forceRefresh(): Boolean = refreshMutex.withLock {
        val current = tokens.load() ?: return@withLock false
        // LLAT path: there's nothing to refresh. Return false so callers
        // surface "sign out & reconnect" toasts rather than silently looping.
        if (current.refreshToken.isBlank()) return@withLock false
        refresh(current)
    }

    private suspend fun refresh(current: Tokens): Boolean = withContext(Dispatchers.IO) {
        val serverUrl = settings.settings.first().server?.url ?: return@withContext false
        try {
            val body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", current.refreshToken)
                .add("client_id", clientId)
                .build()
            val req = Request.Builder()
                .url("${serverUrl.trimEnd('/')}/auth/token")
                .post(body)
                .build()
            val resp = http.newCall(req).execute().use { r ->
                val bodyStr = r.body?.string() ?: error("Empty refresh response")
                if (!r.isSuccessful) error("HTTP ${r.code}: $bodyStr")
                json.decodeFromString<RefreshResponse>(bodyStr)
            }
            val expiresAt = System.currentTimeMillis() + resp.expires_in * 1_000L
            tokens.save(
                current.copy(
                    accessToken = resp.access_token,
                    // Adopt a rotated refresh_token if HA sent one, otherwise keep the original.
                    refreshToken = resp.refresh_token ?: current.refreshToken,
                    expiresAtMillis = expiresAt,
                )
            )
            R1Log.i("TokenRefresher", "refreshed; new expiry in ${resp.expires_in}s")
            // Success route — routed through the level-gated R1Toast.push at INFO
            // so it only surfaces when the user has set the diagnostic toast
            // level to INFO or DEBUG. The user previously reported it appeared
            // even with toasts off, which is correct for unconditional Toaster
            // calls but inappropriate for a routine background operation that
            // the user doesn't need to be told about.
            com.github.itskenny0.r1ha.core.util.R1Toast.push(
                com.github.itskenny0.r1ha.core.util.R1Toast.Level.INFO,
                "TokenRefresher",
                "Session refreshed",
            )
            true
        } catch (t: Throwable) {
            R1Log.e("TokenRefresher", "refresh failed", t)
            // Failure path still goes through Toaster.show (which is now the
            // force-show user-facing route) — a failed refresh is something the
            // user needs to know about because the next HA call may 401.
            Toaster.error("Couldn't refresh session: ${t.message ?: "error"}. Sign out & reconnect")
            false
        }
    }
}
