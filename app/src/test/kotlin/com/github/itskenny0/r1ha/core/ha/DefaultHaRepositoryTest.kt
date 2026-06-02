package com.github.itskenny0.r1ha.core.ha

import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.github.itskenny0.r1ha.core.ha.testing.ServerRecorder
import com.github.itskenny0.r1ha.core.prefs.ServerConfig
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.SoftwareKeyProvider
import com.github.itskenny0.r1ha.core.prefs.TokenStore
import com.github.itskenny0.r1ha.core.prefs.Tokens
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DefaultHaRepositoryTest {
    private val server = MockWebServer()
    private val recorder = ServerRecorder()

    /**
     * Full integration: WS connects, auth handshake, state_changed event arrives,
     * cache is updated, observe() emits the filtered entry.
     *
     * The test connects the WS client directly (bypassing DataStore for the connect step),
     * then starts the repo so its event listeners are active. DataStore is pre-populated
     * so resubscribe() can read favorites.
     */
    @Test fun `event populates cache then observe emits filtered`() = runTest {
        server.enqueue(MockResponse().withWebSocketUpgrade(recorder))
        server.start()

        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = SettingsRepository.forTesting(ctx, datastoreName = "t_${System.nanoTime()}")
        val tokens = TokenStore(
            ctx,
            datastoreName = "tk_${System.nanoTime()}",
            keyAlias = "ta_${System.nanoTime()}",
            keystoreProvider = SoftwareKeyProvider(),
        )
        val baseUrl = server.url("/").toString().trimEnd('/')
        prefs.update { it.copy(server = ServerConfig(url = baseUrl), favorites = listOf("light.kitchen")) }
        tokens.save(Tokens("TOK", "REFRESH", Long.MAX_VALUE))

        val http = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        // Use a real scope for the WS client so OkHttp callbacks fire on its threads
        val wsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ws = HaWebSocketClient(http = http, scope = wsScope)

        // Connect the WS directly — bypasses DataStore so no main-looper deadlock
        val wsUrl = baseUrl.replace("http://", "ws://") + "/api/websocket"
        ws.connect(wsUrl, "TOK")

        // Complete auth handshake on the server side
        val opened = recorder.awaitOpen(5_000)
        opened.send("""{"type":"auth_required","ha_version":"2026.5.0"}""")
        val authFrame = recorder.awaitTextMessage(5_000)
        assertThat(authFrame).contains("\"type\":\"auth\"")
        assertThat(authFrame).contains("TOK")
        opened.send("""{"type":"auth_ok","ha_version":"2026.5.0"}""")

        // Give WS time to transition to Connected state
        @Suppress("BlockingMethodInNonBlockingContext")
        Thread.sleep(200)
        // Also advance Robolectric's main looper for any pending Android callbacks
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        // Now start the repo — WS is already Connected so connectFromSettings() is a no-op
        val repo = DefaultHaRepository(ws, http, prefs, tokens,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))
        repo.start()

        // repo's state listener sees Connected → resubscribe() fires; DataStore is pre-populated
        @Suppress("BlockingMethodInNonBlockingContext")
        Thread.sleep(300)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        // Server receives the subscribe_trigger
        val subFrame = recorder.awaitTextMessage(5_000)
        assertThat(subFrame).contains("subscribe_trigger")

        // Push a state_changed event for light.kitchen
        opened.send(
            """{"id":1,"type":"event","event":{"variables":{"trigger":{"platform":"state","entity_id":"light.kitchen",""" +
            """"to_state":{"entity_id":"light.kitchen","state":"on","attributes":{"brightness":255,"friendly_name":"Kitchen"},""" +
            """"last_changed":"2026-05-11T10:00:00+00:00"}}}}}"""
        )
        @Suppress("BlockingMethodInNonBlockingContext")
        Thread.sleep(200)

        // observe() should emit an entry for light.kitchen
        repo.observe(setOf(EntityId("light.kitchen"))).test {
            var entry: Map<EntityId, EntityState> = awaitItem()
            while (entry.isEmpty()) entry = awaitItem()
            val s = checkNotNull(entry[EntityId("light.kitchen")])
            assertThat(s.isOn).isTrue()
            assertThat(s.percent).isEqualTo(100)
            assertThat(s.friendlyName).isEqualTo("Kitchen")
            cancelAndConsumeRemainingEvents()
        }

        repo.stop()
        server.shutdown()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    /**
     * Regression: a state_changed event carrying a malformed entity_id (supported domain
     * prefix but empty object_id, e.g. "light.") must not crash the inbound message flow.
     * EntityId's init throws on such ids; before the guard in applyEvent that throw escaped
     * the inbound onEach and cancelled the whole message-processing flow, silently freezing
     * every subsequent live update. Here we push the malformed event first, then a valid one,
     * and assert the valid one still lands in the cache.
     */
    @Test fun `malformed entity_id event does not kill the inbound flow`() = runTest {
        server.enqueue(MockResponse().withWebSocketUpgrade(recorder))
        server.start()

        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = SettingsRepository.forTesting(ctx, datastoreName = "t_${System.nanoTime()}")
        val tokens = TokenStore(
            ctx,
            datastoreName = "tk_${System.nanoTime()}",
            keyAlias = "ta_${System.nanoTime()}",
            keystoreProvider = SoftwareKeyProvider(),
        )
        val baseUrl = server.url("/").toString().trimEnd('/')
        prefs.update { it.copy(server = ServerConfig(url = baseUrl), favorites = listOf("light.kitchen")) }
        tokens.save(Tokens("TOK", "REFRESH", Long.MAX_VALUE))

        val http = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val wsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ws = HaWebSocketClient(http = http, scope = wsScope)

        val wsUrl = baseUrl.replace("http://", "ws://") + "/api/websocket"
        ws.connect(wsUrl, "TOK")

        val opened = recorder.awaitOpen(5_000)
        opened.send("""{"type":"auth_required","ha_version":"2026.5.0"}""")
        recorder.awaitTextMessage(5_000) // auth frame
        opened.send("""{"type":"auth_ok","ha_version":"2026.5.0"}""")

        @Suppress("BlockingMethodInNonBlockingContext")
        Thread.sleep(200)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val repo = DefaultHaRepository(ws, http, prefs, tokens,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))
        repo.start()

        @Suppress("BlockingMethodInNonBlockingContext")
        Thread.sleep(300)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val subFrame = recorder.awaitTextMessage(5_000)
        assertThat(subFrame).contains("subscribe_trigger")

        // Malformed entity_id: supported "light" prefix but empty object_id. EntityId(init)
        // would throw on this; the guard must drop the event instead of cancelling the flow.
        opened.send(
            """{"id":1,"type":"event","event":{"variables":{"trigger":{"platform":"state","entity_id":"light.",""" +
            """"to_state":{"entity_id":"light.","state":"on","attributes":{}}}}}}"""
        )
        @Suppress("BlockingMethodInNonBlockingContext")
        Thread.sleep(150)

        // A well-formed event after the malformed one must still be processed.
        opened.send(
            """{"id":1,"type":"event","event":{"variables":{"trigger":{"platform":"state","entity_id":"light.kitchen",""" +
            """"to_state":{"entity_id":"light.kitchen","state":"on","attributes":{"brightness":255,"friendly_name":"Kitchen"},""" +
            """"last_changed":"2026-05-11T10:00:00+00:00"}}}}}"""
        )
        @Suppress("BlockingMethodInNonBlockingContext")
        Thread.sleep(200)

        repo.observe(setOf(EntityId("light.kitchen"))).test {
            var entry: Map<EntityId, EntityState> = awaitItem()
            while (entry.isEmpty()) entry = awaitItem()
            val s = checkNotNull(entry[EntityId("light.kitchen")])
            assertThat(s.isOn).isTrue()
            assertThat(s.friendlyName).isEqualTo("Kitchen")
            cancelAndConsumeRemainingEvents()
        }

        repo.stop()
        server.shutdown()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    /**
     * observe() is distinctUntilChanged: HA re-emits state_changed events whose to_state
     * is byte-identical to the previous one (a sensor reporting the same reading on a fixed
     * poll interval is the common case). EntityState has value equality, so a second
     * identical event must NOT produce a downstream emission — otherwise every no-op churn
     * would re-run the whole card-stack materialize + recomposition.
     */
    @Test fun `observe dedups an identical repeated state event`() = runTest {
        server.enqueue(MockResponse().withWebSocketUpgrade(recorder))
        server.start()

        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = SettingsRepository.forTesting(ctx, datastoreName = "t_${System.nanoTime()}")
        val tokens = TokenStore(
            ctx,
            datastoreName = "tk_${System.nanoTime()}",
            keyAlias = "ta_${System.nanoTime()}",
            keystoreProvider = SoftwareKeyProvider(),
        )
        val baseUrl = server.url("/").toString().trimEnd('/')
        prefs.update { it.copy(server = ServerConfig(url = baseUrl), favorites = listOf("light.kitchen")) }
        tokens.save(Tokens("TOK", "REFRESH", Long.MAX_VALUE))

        val http = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val wsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ws = HaWebSocketClient(http = http, scope = wsScope)

        val wsUrl = baseUrl.replace("http://", "ws://") + "/api/websocket"
        ws.connect(wsUrl, "TOK")

        val opened = recorder.awaitOpen(5_000)
        opened.send("""{"type":"auth_required","ha_version":"2026.5.0"}""")
        recorder.awaitTextMessage(5_000) // auth frame
        opened.send("""{"type":"auth_ok","ha_version":"2026.5.0"}""")

        @Suppress("BlockingMethodInNonBlockingContext")
        Thread.sleep(200)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val repo = DefaultHaRepository(ws, http, prefs, tokens,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))
        repo.start()

        @Suppress("BlockingMethodInNonBlockingContext")
        Thread.sleep(300)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val subFrame = recorder.awaitTextMessage(5_000)
        assertThat(subFrame).contains("subscribe_trigger")

        // A fixed event payload (same last_changed so the parsed EntityState is identical).
        val event =
            """{"id":1,"type":"event","event":{"variables":{"trigger":{"platform":"state","entity_id":"light.kitchen",""" +
            """"to_state":{"entity_id":"light.kitchen","state":"on","attributes":{"brightness":128,"friendly_name":"Kitchen"},""" +
            """"last_changed":"2026-05-11T10:00:00+00:00"}}}}}"""

        opened.send(event)
        @Suppress("BlockingMethodInNonBlockingContext")
        Thread.sleep(200)

        repo.observe(setOf(EntityId("light.kitchen"))).test {
            var entry: Map<EntityId, EntityState> = awaitItem()
            while (entry.isEmpty()) entry = awaitItem()
            val first = checkNotNull(entry[EntityId("light.kitchen")])
            assertThat(first.percent).isEqualTo(50)

            // Push the byte-identical event again — the cache.update writes an equal
            // EntityState, so the filtered subset compares equal and distinctUntilChanged
            // must swallow it: no further downstream emission.
            opened.send(event)
            @Suppress("BlockingMethodInNonBlockingContext")
            Thread.sleep(250)
            expectNoEvents()
            cancelAndConsumeRemainingEvents()
        }

        repo.stop()
        server.shutdown()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    /**
     * fetchLogbook parses HA's /api/logbook rows, including the "triggered by"
     * context block. The endpoint is plain REST (no WS), so this test drives the
     * repo's HTTP path directly: enqueue a two-row logbook body, call
     * fetchLogbook, and assert the context fields land on [LogbookEntry]. One row
     * carries a full context block (user id + triggering entity + resolved name),
     * the other carries none so the defaults-to-null path is covered too.
     */
    @Test fun `fetchLogbook maps context fields and tolerates rows without them`() = runTest {
        server.start()

        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = SettingsRepository.forTesting(ctx, datastoreName = "t_${System.nanoTime()}")
        val tokens = TokenStore(
            ctx,
            datastoreName = "tk_${System.nanoTime()}",
            keyAlias = "ta_${System.nanoTime()}",
            keystoreProvider = SoftwareKeyProvider(),
        )
        val baseUrl = server.url("/").toString().trimEnd('/')
        prefs.update { it.copy(server = ServerConfig(url = baseUrl)) }
        tokens.save(Tokens("TOK", "REFRESH", Long.MAX_VALUE))

        val http = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val ws = HaWebSocketClient(http = http, scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))
        // refresher = null (default) so fetchLogbook skips token refresh and just GETs.
        val repo = DefaultHaRepository(ws, http, prefs, tokens,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))

        // Row 1: full context block. Row 2: no context at all.
        val bodyJson = """
            [
              {
                "when":"2026-05-29T10:00:00+00:00",
                "name":"Kitchen Light",
                "entity_id":"light.kitchen",
                "domain":"light",
                "state":"on",
                "message":"turned on",
                "context_user_id":"abcdef0123456789",
                "context_entity_id":"binary_sensor.front_door",
                "context_entity_id_name":"Front Door Motion"
              },
              {
                "when":"2026-05-29T09:59:00+00:00",
                "name":"Hallway",
                "entity_id":"light.hallway",
                "domain":"light",
                "state":"off",
                "message":"turned off"
              }
            ]
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(bodyJson))

        val result = repo.fetchLogbook(hours = 24)
        assertThat(result.isSuccess).isTrue()
        val rows = result.getOrThrow()
        assertThat(rows).hasSize(2)

        // Newest-first: the 10:00 row sorts ahead of the 09:59 row.
        val withCtx = rows.first { it.entityId?.value == "light.kitchen" }
        assertThat(withCtx.contextUserId).isEqualTo("abcdef0123456789")
        assertThat(withCtx.contextEntityId).isEqualTo("binary_sensor.front_door")
        assertThat(withCtx.contextName).isEqualTo("Front Door Motion")

        val noCtx = rows.first { it.entityId?.value == "light.hallway" }
        assertThat(noCtx.contextUserId).isNull()
        assertThat(noCtx.contextEntityId).isNull()
        assertThat(noCtx.contextName).isNull()

        server.shutdown()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    /**
     * Regression: when the auth breaker is tripped it short-circuits /api/history with a
     * synthetic 503, and the SensorCard / HistoryGraphCard fetch is a one-shot — so a single
     * transient failure used to leave the chart permanently blank. fetchHistory now retries
     * with backoff, so a couple of failures followed by a good response still yields the data.
     */
    @Test fun `fetchHistory retries past transient failures and succeeds`() = runTest {
        server.start()

        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = SettingsRepository.forTesting(ctx, datastoreName = "t_${System.nanoTime()}")
        val tokens = TokenStore(
            ctx,
            datastoreName = "tk_${System.nanoTime()}",
            keyAlias = "ta_${System.nanoTime()}",
            keystoreProvider = SoftwareKeyProvider(),
        )
        val baseUrl = server.url("/").toString().trimEnd('/')
        prefs.update { it.copy(server = ServerConfig(url = baseUrl)) }
        tokens.save(Tokens("TOK", "REFRESH", Long.MAX_VALUE))

        val http = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val ws = HaWebSocketClient(http = http, scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))
        // Tiny backoff so the retry path runs without real-time waits.
        val repo = DefaultHaRepository(ws, http, prefs, tokens,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            historyRetryBackoffMillis = 1L)

        // Two short-circuit-style 503s, then a real history payload with two numeric samples.
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[[{"state":"21.5","last_changed":"2026-05-29T10:00:00+00:00"},""" +
                    """{"state":"22.0","last_changed":"2026-05-29T10:05:00+00:00"}]]"""
            )
        )

        val result = repo.fetchHistory(EntityId("sensor.temp"), hours = 24)
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow()).hasSize(2)
        // Two failed attempts + the successful third == three requests reached the server.
        assertThat(server.requestCount).isEqualTo(3)

        server.shutdown()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    /**
     * A genuinely empty history (HA simply has no samples in the window) is a success, not a
     * failure, so it must return immediately on the first try and never burn the retry budget.
     */
    @Test fun `fetchHistory does not retry a successful empty history`() = runTest {
        server.start()

        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = SettingsRepository.forTesting(ctx, datastoreName = "t_${System.nanoTime()}")
        val tokens = TokenStore(
            ctx,
            datastoreName = "tk_${System.nanoTime()}",
            keyAlias = "ta_${System.nanoTime()}",
            keystoreProvider = SoftwareKeyProvider(),
        )
        val baseUrl = server.url("/").toString().trimEnd('/')
        prefs.update { it.copy(server = ServerConfig(url = baseUrl)) }
        tokens.save(Tokens("TOK", "REFRESH", Long.MAX_VALUE))

        val http = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val ws = HaWebSocketClient(http = http, scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))
        val repo = DefaultHaRepository(ws, http, prefs, tokens,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            historyRetryBackoffMillis = 1L)

        // HA returns one entity entry with no samples: the outer array holds one empty array.
        server.enqueue(MockResponse().setResponseCode(200).setBody("[[]]"))

        val result = repo.fetchHistory(EntityId("sensor.temp"), hours = 24)
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow()).isEmpty()
        assertThat(server.requestCount).isEqualTo(1)

        server.shutdown()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    /**
     * The human label can arrive under `context_name` instead of
     * `context_entity_id_name` (HA uses the former for user-initiated actions).
     * The mapper prefers the entity-scoped label but must accept either; here
     * only `context_name` is present and must populate [LogbookEntry.contextName].
     */
    @Test fun `fetchLogbook accepts context_name as the human label`() = runTest {
        server.start()

        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = SettingsRepository.forTesting(ctx, datastoreName = "t_${System.nanoTime()}")
        val tokens = TokenStore(
            ctx,
            datastoreName = "tk_${System.nanoTime()}",
            keyAlias = "ta_${System.nanoTime()}",
            keystoreProvider = SoftwareKeyProvider(),
        )
        val baseUrl = server.url("/").toString().trimEnd('/')
        prefs.update { it.copy(server = ServerConfig(url = baseUrl)) }
        tokens.save(Tokens("TOK", "REFRESH", Long.MAX_VALUE))

        val http = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val ws = HaWebSocketClient(http = http, scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))
        val repo = DefaultHaRepository(ws, http, prefs, tokens,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))

        val bodyJson = """
            [
              {
                "when":"2026-05-29T10:00:00+00:00",
                "name":"Kitchen Light",
                "entity_id":"light.kitchen",
                "domain":"light",
                "state":"on",
                "message":"turned on",
                "context_user_id":"abcdef0123456789",
                "context_name":"Alice"
              }
            ]
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(bodyJson))

        val rows = repo.fetchLogbook(hours = 24).getOrThrow()
        assertThat(rows).hasSize(1)
        assertThat(rows.single().contextName).isEqualTo("Alice")

        server.shutdown()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    /**
     * renameArea must emit a `config/area_registry/update` WS command carrying the
     * stable area_id plus the new name, and resolve to Result.success once HA replies
     * with a success result. Asserts the request shape on the wire and the return value.
     */
    @Test fun `renameArea sends area_registry update with id and name`() = runTest {
        server.enqueue(MockResponse().withWebSocketUpgrade(recorder))
        server.start()

        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = SettingsRepository.forTesting(ctx, datastoreName = "t_${System.nanoTime()}")
        val tokens = TokenStore(
            ctx,
            datastoreName = "tk_${System.nanoTime()}",
            keyAlias = "ta_${System.nanoTime()}",
            keystoreProvider = SoftwareKeyProvider(),
        )
        val baseUrl = server.url("/").toString().trimEnd('/')
        prefs.update { it.copy(server = ServerConfig(url = baseUrl), favorites = listOf("light.kitchen")) }
        tokens.save(Tokens("TOK", "REFRESH", Long.MAX_VALUE))

        val http = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val wsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ws = HaWebSocketClient(http = http, scope = wsScope)

        val wsUrl = baseUrl.replace("http://", "ws://") + "/api/websocket"
        ws.connect(wsUrl, "TOK")

        val opened = recorder.awaitOpen(5_000)
        opened.send("""{"type":"auth_required","ha_version":"2026.5.0"}""")
        recorder.awaitTextMessage(5_000) // auth frame
        opened.send("""{"type":"auth_ok","ha_version":"2026.5.0"}""")

        @Suppress("BlockingMethodInNonBlockingContext")
        Thread.sleep(200)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val repo = DefaultHaRepository(ws, http, prefs, tokens,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))
        repo.start()

        @Suppress("BlockingMethodInNonBlockingContext")
        Thread.sleep(300)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val subFrame = recorder.awaitTextMessage(5_000)
        assertThat(subFrame).contains("subscribe_trigger")

        // renameArea suspends until HA replies, so issue it on a background coroutine,
        // capture the outbound frame, reply success keyed to its id, then await the Result.
        val callScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val deferred = callScope.async { repo.renameArea("kitchen", "Cocina") }

        // The repo may emit additional resubscribe frames; skip past anything
        // that isn't the area-registry update command we're asserting on.
        var frame = recorder.awaitTextMessage(5_000)
        while (!frame.contains("config/area_registry/update")) {
            frame = recorder.awaitTextMessage(5_000)
        }
        assertThat(frame).contains("\"type\":\"config/area_registry/update\"")
        assertThat(frame).contains("\"area_id\":\"kitchen\"")
        assertThat(frame).contains("\"name\":\"Cocina\"")

        // Echo HA's success result back, keyed to the request id from the captured frame.
        val id = Regex("\"id\":(\\d+)").find(frame)!!.groupValues[1]
        opened.send("""{"id":$id,"type":"result","success":true,"result":null}""")

        val result = deferred.await()
        assertThat(result.isSuccess).isTrue()

        repo.stop()
        server.shutdown()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    /**
     * getWeatherForecasts must POST to /api/services/weather/get_forecasts with
     * the ?return_response=true query param (HA rejects the response-only service
     * with HTTP 400 without it), forward the entity_id + type in the body, and
     * return the per-entity service-response object ({"forecast":[...]}) verbatim.
     */
    @Test fun `getWeatherForecasts posts return_response and returns per-entity object`() = runTest {
        server.start()

        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = SettingsRepository.forTesting(ctx, datastoreName = "t_${System.nanoTime()}")
        val tokens = TokenStore(
            ctx,
            datastoreName = "tk_${System.nanoTime()}",
            keyAlias = "ta_${System.nanoTime()}",
            keystoreProvider = SoftwareKeyProvider(),
        )
        val baseUrl = server.url("/").toString().trimEnd('/')
        prefs.update { it.copy(server = ServerConfig(url = baseUrl)) }
        tokens.save(Tokens("TOK", "REFRESH", Long.MAX_VALUE))

        val http = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val ws = HaWebSocketClient(http = http, scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))
        val repo = DefaultHaRepository(ws, http, prefs, tokens,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))

        // HA wraps the response data under service_response, keyed by entity_id.
        val bodyJson = """
            {
              "changed_states": [],
              "service_response": {
                "weather.home": {
                  "forecast": [
                    {"datetime":"2026-05-29T12:00:00+00:00","condition":"sunny","temperature":21.5},
                    {"datetime":"2026-05-29T13:00:00+00:00","condition":"cloudy","temperature":20.0}
                  ]
                }
              }
            }
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(bodyJson))

        val result = repo.getWeatherForecasts("weather.home", "hourly")
        assertThat(result.isSuccess).isTrue()

        // The returned element is the per-entity object, not the whole envelope.
        val obj = result.getOrThrow() as kotlinx.serialization.json.JsonObject
        val forecast = obj["forecast"] as kotlinx.serialization.json.JsonArray
        assertThat(forecast).hasSize(2)

        val req = server.takeRequest()
        assertThat(req.method).isEqualTo("POST")
        assertThat(req.path).contains("/api/services/weather/get_forecasts")
        assertThat(req.path).contains("return_response=true")
        val sentBody = req.body.readUtf8()
        assertThat(sentBody).contains("\"entity_id\":\"weather.home\"")
        assertThat(sentBody).contains("\"type\":\"hourly\"")

        server.shutdown()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    /**
     * listDevices must parse HA's `identifiers` and `connections` arrays (each a
     * JSON array of [domain, id] 2-tuples) into the DeviceInfo pair lists, while
     * tolerating rows that carry neither field and entries that aren't well-formed
     * 2-element tuples (dropped silently, leaving an empty list).
     */
    @Test fun `listDevices parses identifiers and connections and tolerates malformed`() = runTest {
        server.enqueue(MockResponse().withWebSocketUpgrade(recorder))
        server.start()

        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = SettingsRepository.forTesting(ctx, datastoreName = "t_${System.nanoTime()}")
        val tokens = TokenStore(
            ctx,
            datastoreName = "tk_${System.nanoTime()}",
            keyAlias = "ta_${System.nanoTime()}",
            keystoreProvider = SoftwareKeyProvider(),
        )
        val baseUrl = server.url("/").toString().trimEnd('/')
        prefs.update { it.copy(server = ServerConfig(url = baseUrl), favorites = listOf("light.kitchen")) }
        tokens.save(Tokens("TOK", "REFRESH", Long.MAX_VALUE))

        val http = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val wsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ws = HaWebSocketClient(http = http, scope = wsScope)

        val wsUrl = baseUrl.replace("http://", "ws://") + "/api/websocket"
        ws.connect(wsUrl, "TOK")

        val opened = recorder.awaitOpen(5_000)
        opened.send("""{"type":"auth_required","ha_version":"2026.5.0"}""")
        recorder.awaitTextMessage(5_000) // auth frame
        opened.send("""{"type":"auth_ok","ha_version":"2026.5.0"}""")

        @Suppress("BlockingMethodInNonBlockingContext")
        Thread.sleep(200)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val repo = DefaultHaRepository(ws, http, prefs, tokens,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))
        repo.start()

        @Suppress("BlockingMethodInNonBlockingContext")
        Thread.sleep(300)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val subFrame = recorder.awaitTextMessage(5_000)
        assertThat(subFrame).contains("subscribe_trigger")

        val callScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val deferred = callScope.async { repo.listDevices() }

        var frame = recorder.awaitTextMessage(5_000)
        while (!frame.contains("config/device_registry/list")) {
            frame = recorder.awaitTextMessage(5_000)
        }
        val id = Regex("\"id\":(\\d+)").find(frame)!!.groupValues[1]

        // dev1: well-formed identifiers + connections.
        // dev2: neither field present.
        // dev3: malformed entries (1-element, 3-element, non-array, non-primitive
        //       members) must all be dropped, yielding empty lists.
        opened.send(
            """{"id":$id,"type":"result","success":true,"result":[""" +
            """{"id":"dev1","identifiers":[["zha","00:11:22"],["mqtt","abc"]],""" +
            """"connections":[["mac","aa:bb:cc:dd:ee:ff"]]},""" +
            """{"id":"dev2"},""" +
            """{"id":"dev3","identifiers":[["only_one"],["a","b","c"],"flat",[{"x":1},"y"]],""" +
            """"connections":"not_an_array"}""" +
            """]}"""
        )

        val result = deferred.await()
        assertThat(result.isSuccess).isTrue()
        val devices = result.getOrThrow().associateBy { it.id }

        val dev1 = checkNotNull(devices["dev1"])
        assertThat(dev1.identifiers).containsExactly("zha" to "00:11:22", "mqtt" to "abc").inOrder()
        assertThat(dev1.connections).containsExactly("mac" to "aa:bb:cc:dd:ee:ff")

        val dev2 = checkNotNull(devices["dev2"])
        assertThat(dev2.identifiers).isEmpty()
        assertThat(dev2.connections).isEmpty()

        val dev3 = checkNotNull(devices["dev3"])
        assertThat(dev3.identifiers).isEmpty()
        assertThat(dev3.connections).isEmpty()

        repo.stop()
        server.shutdown()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }
}
