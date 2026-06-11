package com.github.itskenny0.r1ha.core.ha

import app.cash.turbine.test
import com.github.itskenny0.r1ha.core.ha.testing.ServerRecorder
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class HaWebSocketClientTest {
    private lateinit var server: MockWebServer
    private lateinit var recorder: ServerRecorder
    private lateinit var httpClient: OkHttpClient

    @BeforeEach fun setUp() {
        server = MockWebServer()
        recorder = ServerRecorder()
        httpClient = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    }
    @AfterEach fun tearDown() {
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
        server.shutdown()
    }

    private fun http(): OkHttpClient = httpClient

    @Test fun `connect performs auth and emits Connected`() = runTest {
        server.enqueue(MockResponse().withWebSocketUpgrade(recorder))
        server.start()
        val url = server.url("/api/websocket").toString().replace("http", "ws")

        // Watchdog disabled: this test drives the virtual clock with advanceUntilIdle, which would
        // otherwise fast-forward into the watchdog delay and fail the connection before the real
        // socket finishes its (real-time) auth handshake.
        val client = HaWebSocketClient(
            http = http(),
            scope = TestScope(StandardTestDispatcher(testScheduler)),
            handshakeWatchdogMillis = 0,
        )
        client.state.test {
            assertThat(awaitItem()).isEqualTo(ConnectionState.Idle)
            client.connect(url, accessToken = "TOK")
            awaitState(ConnectionState.Authenticating)

            // Server side: receive auth, then respond auth_ok
            val opened = recorder.awaitOpen()
            opened.send("""{"type":"auth_required","ha_version":"2026.5.0"}""")
            val authFrame = recorder.awaitTextMessage()
            assertThat(authFrame).contains("\"type\":\"auth\"")
            assertThat(authFrame).contains("\"access_token\":\"TOK\"")
            opened.send("""{"type":"auth_ok","ha_version":"2026.5.0"}""")
            advanceUntilIdle()
            val connected = awaitItem()
            assertThat(connected).isInstanceOf(ConnectionState.Connected::class.java)
            assertThat((connected as ConnectionState.Connected).haVersion).isEqualTo("2026.5.0")
            cancelAndConsumeRemainingEvents()
        }
        client.scope.cancel()
    }

    @Test fun `after auth_ok client drains queued sends`() = runTest {
        server.enqueue(MockResponse().withWebSocketUpgrade(recorder))
        server.start()
        val url = server.url("/api/websocket").toString().replace("http", "ws")
        // Watchdog disabled (see the Connected test): advanceUntilIdle below would otherwise
        // fast-forward into the watchdog delay mid-test.
        val client = HaWebSocketClient(
            http = http(),
            scope = TestScope(StandardTestDispatcher(testScheduler)),
            handshakeWatchdogMillis = 0,
        )

        client.connect(url, accessToken = "TOK")
        val opened = recorder.awaitOpen()
        opened.send("""{"type":"auth_required"}""")
        recorder.awaitTextMessage()                              // auth frame
        opened.send("""{"type":"auth_ok","ha_version":"x"}""")

        // Now queue subscribe + call_service and verify they hit the wire
        val subId = client.nextRequestId()
        client.send(HaOutbound.SubscribeStateTrigger(id = subId, entityIds = listOf("light.kitchen")))
        val callId = client.nextRequestId()
        client.send(HaOutbound.CallService(callId, "light", "turn_on", "light.kitchen", null))

        advanceUntilIdle()
        val frame1 = recorder.awaitTextMessage()
        val frame2 = recorder.awaitTextMessage()
        assertThat(frame1).contains("\"type\":\"subscribe_trigger\"")
        assertThat(frame2).contains("\"type\":\"call_service\"")
        client.disconnect(); client.scope.cancel()
    }

    @Test fun `handshake watchdog force-fails a socket that never sends auth_required`() = runTest {
        // The wedge: the WS upgrades at the transport layer (onOpen -> Authenticating) but the HA
        // backend never delivers auth_required, so AuthOk/AuthInvalid/onClosed/onFailure never run
        // and the state would otherwise sit on Authenticating forever. The watchdog must break it.
        server.enqueue(MockResponse().withWebSocketUpgrade(recorder))
        server.start()
        val url = server.url("/api/websocket").toString().replace("http", "ws")
        val client = HaWebSocketClient(
            http = http(),
            scope = TestScope(StandardTestDispatcher(testScheduler)),
            handshakeWatchdogMillis = 5_000,
        )

        client.state.test {
            assertThat(awaitItem()).isEqualTo(ConnectionState.Idle)
            client.connect(url, accessToken = "TOK")
            // Socket upgrades but the server stays mute (no auth_required).
            recorder.awaitOpen()
            awaitState(ConnectionState.Authenticating)

            // Advance past the watchdog budget on the virtual clock; nothing else has settled it.
            advanceTimeBy(5_001)
            advanceUntilIdle()
            val failed = awaitItem()
            assertThat(failed).isInstanceOf(ConnectionState.Disconnected::class.java)
            cancelAndConsumeRemainingEvents()
        }
        client.scope.cancel()
    }

    @Test fun `disconnect disarms the handshake watchdog`() = runTest {
        // A client_disconnect during the connecting window must cancel the armed watchdog so it
        // can't later fire a spurious Disconnected over an already-torn-down (or freshly replaced)
        // connection. Deterministic: the server stays mute, so only the watchdog could move state,
        // and disconnect() runs before we advance the virtual clock past the watchdog budget.
        server.enqueue(MockResponse().withWebSocketUpgrade(recorder))
        server.start()
        val url = server.url("/api/websocket").toString().replace("http", "ws")
        val client = HaWebSocketClient(
            http = http(),
            scope = TestScope(StandardTestDispatcher(testScheduler)),
            handshakeWatchdogMillis = 5_000,
        )

        client.state.test {
            assertThat(awaitItem()).isEqualTo(ConnectionState.Idle)
            client.connect(url, accessToken = "TOK")
            recorder.awaitOpen()
            awaitState(ConnectionState.Authenticating)

            // Client-side disconnect: state goes Idle and the watchdog is cancelled.
            client.disconnect()
            assertThat(awaitItem()).isEqualTo(ConnectionState.Idle)

            // Advancing past the (now-cancelled) watchdog budget must NOT resurrect a Disconnected.
            advanceTimeBy(10_000)
            advanceUntilIdle()
            expectNoEvents()
            cancelAndConsumeRemainingEvents()
        }
        client.scope.cancel()
    }
}

/**
 * Await until the state flow reaches [expected], skipping conflated
 * intermediates. ConnectionState is a conflated StateFlow: rapid transitions
 * (Connecting then Authenticating off OkHttp's real callback thread) can
 * collapse into one observed emission, so asserting every hop with a strict
 * awaitItem() is racy on slow runners (it failed on CI while passing locally).
 * Turbine's own timeout still bounds the wait, so a wrong terminal state fails
 * loudly rather than hanging.
 */
private suspend fun app.cash.turbine.TurbineTestContext<ConnectionState>.awaitState(
    expected: ConnectionState,
) {
    while (true) {
        val item = awaitItem()
        if (item == expected) return
    }
}
