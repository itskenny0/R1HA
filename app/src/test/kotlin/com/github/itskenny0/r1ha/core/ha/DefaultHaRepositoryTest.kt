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
}
