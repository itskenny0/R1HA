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
/**
 * The OAuth client id this app registered with HA (the GitHub Pages URL; see the
 * onboarding flow). HA binds refresh tokens to the client id that issued them, so
 * EVERY place that refreshes a token (this class, and the hassTokens envelope the
 * WebView screens inject for HA's own frontend) must present this exact value.
 */
const val HA_OAUTH_CLIENT_ID = "https://itskenny0.github.io/R1HA/"

/** How a refresh attempt failed, which decides whether the user hears about it. */
internal enum class RefreshFailureKind {
    /** Network unreachable / DNS down / server 5xx / timeout: self-healing, the
     *  connection chrome already shows offline, so it stays silent and triggers
     *  a short network-backoff. */
    TRANSIENT,

    /** HA rejected the grant (4xx from /auth/token): the refresh token is dead
     *  and will NOT recover by waiting, so the user must re-authenticate. */
    AUTH,
}

/** Thrown for a 4xx from the token endpoint so the catch can tell a dead refresh
 *  token apart from a network blip. */
internal class AuthRejectedException(val code: Int, message: String) : Exception(message)

/** A 4xx from /auth/token means the grant itself was refused (bad / revoked
 *  refresh token, wrong client_id). 5xx is a server hiccup, retry-worthy, so it
 *  is NOT an auth rejection. */
internal fun isAuthRejectionCode(code: Int): Boolean = code in 400..499

/** Classify a refresh throwable. Only an explicit [AuthRejectedException] is a
 *  real auth failure; everything else (UnknownHostException, connect/timeout,
 *  empty body, parse error, server 5xx) is transient and self-healing. */
internal fun classifyRefreshFailure(t: Throwable): RefreshFailureKind =
    if (t is AuthRejectedException) RefreshFailureKind.AUTH else RefreshFailureKind.TRANSIENT

class TokenRefresher(
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
    private val tokens: TokenStore,
    private val clientId: String = HA_OAUTH_CLIENT_ID,
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

    // Network-failure backoff. After a TRANSIENT failure, hold off re-POSTing to
    // /auth/token until this monotone deadline, so a sustained outage (DNS down,
    // server unreachable for tens of minutes) doesn't fire one refresh request
    // per caller per heartbeat. The mutex already coalesces CONCURRENT callers;
    // this coalesces SEQUENTIAL ones across the outage. A success or an AUTH
    // failure clears it (waiting won't revive a dead refresh token). Volatile:
    // read on the hot ensureFresh path without taking the lock first.
    @Volatile private var transientCooldownUntilMs: Long = 0L
    // Rate-limit the user-facing auth toast so even a fast reconnect loop hitting
    // a genuinely dead token shows the sign-out prompt occasionally, not per try.
    @Volatile private var lastAuthToastAtMs: Long = 0L

    /**
     * If the stored access token is within [skewMillis] of expiry, exchange the refresh token
     * for a new access token and persist it. Returns true if the token is now valid (either
     * already-valid or freshly refreshed); false if there is no token, no server URL, the
     * refresh attempt failed, or we are inside the post-failure network backoff.
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
            if (System.currentTimeMillis() < transientCooldownUntilMs) return@withLock false
            refresh(latest)
        }
    }

    /** Force a refresh regardless of remaining lifetime. Used after [ConnectionState.AuthLost].
     *  Still respects the transient network backoff: an AuthLost during a DNS outage shouldn't
     *  hammer /auth/token any harder than the heartbeat path does. */
    suspend fun forceRefresh(): Boolean = refreshMutex.withLock {
        val current = tokens.load() ?: return@withLock false
        // LLAT path: there's nothing to refresh. Return false so callers
        // surface "sign out & reconnect" toasts rather than silently looping.
        if (current.refreshToken.isBlank()) return@withLock false
        if (System.currentTimeMillis() < transientCooldownUntilMs) return@withLock false
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
                if (!r.isSuccessful) {
                    // 4xx = the grant was refused (dead/revoked refresh token);
                    // 5xx falls through to the generic error() and is treated as
                    // transient (server hiccup, retry-worthy).
                    if (isAuthRejectionCode(r.code)) throw AuthRejectedException(r.code, bodyStr)
                    error("HTTP ${r.code}: $bodyStr")
                }
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
            // A success clears any standing network backoff.
            transientCooldownUntilMs = 0L
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
            when (classifyRefreshFailure(t)) {
                RefreshFailureKind.AUTH -> {
                    // The refresh token is genuinely dead; waiting won't fix it,
                    // so DON'T set the backoff (callers should be free to retry
                    // the moment the user re-auths). Surface the sign-out prompt,
                    // rate-limited so a reconnect loop doesn't stack toasts.
                    R1Log.e("TokenRefresher", "refresh rejected by HA (auth)", t)
                    val now = System.currentTimeMillis()
                    if (now - lastAuthToastAtMs >= AUTH_TOAST_MIN_INTERVAL_MS) {
                        lastAuthToastAtMs = now
                        Toaster.error("Session expired. Sign out and reconnect.")
                    }
                }
                RefreshFailureKind.TRANSIENT -> {
                    // Network unreachable / DNS down / server 5xx: self-healing.
                    // Stay SILENT (the connection chrome already shows offline; a
                    // per-failure toast spammed the user 30+ times during one DNS
                    // outage) and arm the backoff so we stop re-POSTing per caller
                    // until the cooldown elapses.
                    R1Log.w("TokenRefresher", "refresh failed (transient): ${t.message}")
                    transientCooldownUntilMs = System.currentTimeMillis() + TRANSIENT_COOLDOWN_MS
                }
            }
            false
        }
    }

    companion object {
        /** Hold off re-POSTing /auth/token for this long after a transient failure. */
        internal const val TRANSIENT_COOLDOWN_MS = 30_000L

        /** Minimum spacing between user-facing "session expired" toasts. */
        internal const val AUTH_TOAST_MIN_INTERVAL_MS = 5 * 60_000L
    }
}
