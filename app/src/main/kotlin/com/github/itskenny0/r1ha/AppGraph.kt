package com.github.itskenny0.r1ha

import android.content.Context
import com.github.itskenny0.r1ha.core.ha.DefaultHaRepository
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.HaWebSocketClient
import com.github.itskenny0.r1ha.core.ha.TokenRefresher
import com.github.itskenny0.r1ha.core.input.KeyBindings
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.TokenStore
import com.github.itskenny0.r1ha.core.security.SecurityPolicyStore
import com.github.itskenny0.r1ha.core.util.R1Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Manual dependency-injection container. One instance lives on [App]; activities/fragments
 * access it via `(application as App).graph`.
 *
 * Construction is lazy so the first access triggers real allocation only once.
 */
class AppGraph(context: Context) {

    private val appContext: Context = context.applicationContext

    /** SharedPreferences-backed TLS pinning policy. Read synchronously at the first
     *  [okHttp] access so the CertificatePinner can be wired before any HTTP traffic
     *  flows; mutations only take effect on next process start, which the Settings UI
     *  surfaces to the user. */
    val securityPolicy: SecurityPolicyStore by lazy { SecurityPolicyStore(appContext) }

    val okHttp: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            // WebSocket ping frames every 30s: detects half-open connections that the OS or
            // intermediate routers silently dropped (very common on Wi-Fi after device sleep).
            // OkHttp surfaces a missing PONG as onFailure, which our state machine treats as
            // a Disconnected and schedules a backoff reconnect.
            .pingInterval(30, TimeUnit.SECONDS)
        attachCertificatePinner(builder)
        attachMtlsKeystore(builder)
        builder.build()
    }

    /**
     * If the security policy enables mTLS and a readable PKCS12 keystore is
     * configured, build an [javax.net.ssl.SSLContext] that presents the
     * client certificate during TLS handshake and attach its socket
     * factory to [builder]. Any failure (file missing, wrong password,
     * malformed PKCS12) is logged and treated as if mTLS were off rather
     * than throwing at app startup — a broken cert shouldn't brick the
     * client entirely, the user needs the rest of the app to navigate
     * back to Settings.
     */
    private fun attachMtlsKeystore(builder: OkHttpClient.Builder) {
        val policy = securityPolicy.current()
        if (!policy.mtlsEnabled) return
        val path = policy.mtlsKeystorePath ?: run {
            R1Log.w("AppGraph", "mTLS enabled but no keystore path configured")
            return
        }
        val file = java.io.File(path)
        if (!file.exists() || !file.canRead()) {
            R1Log.w("AppGraph", "mTLS keystore not readable: $path")
            return
        }
        runCatching {
            val keystore = java.security.KeyStore.getInstance("PKCS12")
            file.inputStream().use { input ->
                keystore.load(input, policy.mtlsKeystorePassword.toCharArray())
            }
            val kmf = javax.net.ssl.KeyManagerFactory.getInstance(
                javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm(),
            )
            kmf.init(keystore, policy.mtlsKeystorePassword.toCharArray())
            // Use the default trust manager so server cert validation still
            // goes through the system trust store (or through the
            // CertificatePinner we may have already attached). We don't
            // override server trust here: that's [attachCertificatePinner]'s
            // job and the two are orthogonal.
            val tmf = javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm(),
            )
            tmf.init(null as java.security.KeyStore?)
            val ctx = javax.net.ssl.SSLContext.getInstance("TLS")
            ctx.init(kmf.keyManagers, tmf.trustManagers, java.security.SecureRandom())
            val x509Tm = tmf.trustManagers.filterIsInstance<javax.net.ssl.X509TrustManager>()
                .firstOrNull()
                ?: error("no X509TrustManager in default factory")
            builder.sslSocketFactory(ctx.socketFactory, x509Tm)
            R1Log.i("AppGraph", "mTLS active: keystore=$path")
        }.onFailure { t ->
            R1Log.w("AppGraph", "mTLS keystore load failed: ${t.message}", t)
        }
    }

    /**
     * Build a [CertificatePinner] from the current security policy and attach it to
     * [builder]. No-op when pinning is disabled or no pins are configured, when the
     * server URL isn't set yet, or when the host can't be parsed: a pinner that
     * doesn't know which host to pin would silently let everything through, and a
     * pinner aimed at the wrong host would silently lock the user out.
     *
     * Reads the host out of SharedPreferences directly. The SettingsRepository
     * shadow store mirrors it there before DataStore writes; we use the shadow
     * because it's synchronous and always at-least-as-fresh as DataStore.
     */
    private fun attachCertificatePinner(builder: OkHttpClient.Builder) {
        val policy = securityPolicy.current()
        if (!policy.tlsPinningEnabled || policy.sha256Pins.isEmpty()) return
        val shadow = appContext.getSharedPreferences("r1ha_shadow", Context.MODE_PRIVATE)
        val url = shadow.getString("server.url", null) ?: return
        // java.net.URI is robust enough for the URL shapes the user types into the
        // onboarding screen (with or without scheme, with or without trailing slash);
        // OkHttp's HttpUrl.parse() is stricter and rejects schemeless inputs that we
        // happily accept elsewhere in the app.
        val host = runCatching {
            val withScheme = if (url.startsWith("http")) url else "https://$url"
            java.net.URI(withScheme).host
        }.getOrNull() ?: return
        if (host.isBlank()) return
        val pinner = CertificatePinner.Builder().apply {
            policy.sha256Pins.forEach { pin ->
                add(host, "sha256/$pin")
            }
        }.build()
        builder.certificatePinner(pinner)
        R1Log.i("AppGraph", "TLS pinning ON: host=$host pins=${policy.sha256Pins.size}")
    }

    val settings: SettingsRepository by lazy {
        SettingsRepository(appContext)
    }

    val tokens: TokenStore by lazy {
        TokenStore(appContext)
    }

    val wsClient: HaWebSocketClient by lazy {
        // Share the OkHttpClient so the WS connection inherits its 30-second ping interval —
        // the no-arg HaWebSocketClient constructor builds a fresh client without it, which
        // left the production WS unable to detect half-open connections after device sleep.
        HaWebSocketClient(
            http = okHttp,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
    }

    val tokenRefresher: TokenRefresher by lazy {
        TokenRefresher(http = okHttp, settings = settings, tokens = tokens)
    }

    /** Single-process scope for the persister's debounce loop. Same SupervisorJob
     *  pattern as the WS client so a crash in the disk writer can't kill the rest
     *  of the app. */
    private val persisterScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Disk-backed snapshot of the HA entity cache — used by [haRepository] to
     *  paint cards at cold start from the user's last-known state, before the
     *  WS connects. Tiny JSON file in the app's files dir; lazy so test
     *  contexts that never build the repository don't pay the construction
     *  cost. */
    val entityCachePersister: com.github.itskenny0.r1ha.core.ha.EntityStateCachePersister by lazy {
        com.github.itskenny0.r1ha.core.ha.EntityStateCachePersister.forContext(
            appContext, persisterScope,
        )
    }

    val haRepository: HaRepository by lazy {
        // Persister is wired but DefaultHaRepository.start() consults
        // advanced.persistCacheToDisk and skips the on-disk wiring when the
        // user hasn't opted in. So passing the instance here is cheap (lazy)
        // and lets us promote the feature without re-injecting later.
        DefaultHaRepository(
            ws = wsClient,
            http = okHttp,
            settings = settings,
            tokens = tokens,
            refresher = tokenRefresher,
            persister = entityCachePersister,
        )
    }

    val wheelInput: WheelInput by lazy {
        WheelInput()
    }

    /**
     * Local-only Lovelace overrides store. Backs the native dashboards
     * editor: reorder / replace / delete / append operations land in a
     * separate DataStore file so the main settings preferences blob
     * stays untouched. Lazy because fresh installs that never open the
     * Lovelace dashboards screen don't need to allocate it.
     */
    val lovelaceOverrideStore: com.github.itskenny0.r1ha.core.lovelace.LovelaceOverrideStore by lazy {
        com.github.itskenny0.r1ha.core.lovelace.LovelaceOverrideStore(appContext)
    }

    /**
     * Multi-device settings sync. Mirrors the user's preferences to/from
     * HA's per-user JSON storage; idle (no WS traffic) until the user opts
     * in via Settings → Integrations → "Sync with Home Assistant". Started
     * from [com.github.itskenny0.r1ha.App.onCreate] on the same scope that
     * owns the HA repository, so a stop()/start() cycle (e.g. sign-out)
     * cancels its observers cleanly.
     */
    val haSettingsSync: com.github.itskenny0.r1ha.core.sync.HaSettingsSync by lazy {
        com.github.itskenny0.r1ha.core.sync.HaSettingsSync(
            settings = settings,
            haRepository = haRepository,
            scope = appScope,
        )
    }

    /** Process-scope coroutine scope for long-lived collectors that should
     *  outlive any single activity. Used by [haSettingsSync]. */
    private val appScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default,
    )

    /**
     * Shared frame bus for IoT Camera Mode. Lives on the graph so both the
     * service (publisher) and the settings preview tile (subscriber) read
     * from the same instance — without this they'd be two parallel buses
     * and the settings screen would never see frames. Lazy so devices that
     * never enable the feature don't allocate the SharedFlow.
     */
    val iotCameraFrameBus: com.github.itskenny0.r1ha.core.iotcamera.FrameBus by lazy {
        com.github.itskenny0.r1ha.core.iotcamera.FrameBus()
    }

    /**
     * Live status of each IoT Camera Mode sink + the MQTT discovery
     * publish. Service writes; settings screen reads via the contained
     * StateFlow. Lazy so a fresh install that never enables the feature
     * doesn't allocate a SharedFlow that no-one observes.
     */
    val iotCameraStatus: com.github.itskenny0.r1ha.core.iotcamera.IotCameraStatus by lazy {
        com.github.itskenny0.r1ha.core.iotcamera.IotCameraStatus()
    }

    /**
     * Latest user [KeyBindings], kept up to date by a collector in [App.onCreate]. Read
     * from `MainActivity.dispatchKeyEvent`, which runs on the main thread and can't await a
     * suspend operation — so this volatile cache is the synchronous source of truth. Falls
     * back to [KeyBindings.DEFAULT] until the first settings emission lands.
     */
    @Volatile
    var latestBindings: KeyBindings = KeyBindings.DEFAULT

    /**
     * Current top-level navigation route, updated by an
     * `OnDestinationChangedListener` installed in `MainActivity.setContent`.
     * Read from `MainActivity.dispatchKeyEvent` to gate key-binding fire on
     * the screens where bindings make sense (card stack + dashboard) and
     * silence them everywhere else so a binding on "6" doesn't swallow the
     * keystroke when the user is typing into a text field on a Settings
     * or Onboarding screen. Null until the first navigation tick lands.
     */
    @Volatile
    var currentNavRoute: String? = null
}
