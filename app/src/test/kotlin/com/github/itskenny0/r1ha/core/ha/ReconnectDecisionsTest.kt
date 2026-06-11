package com.github.itskenny0.r1ha.core.ha

import com.github.itskenny0.r1ha.core.ha.ReconnectDecisions.ResumeAction
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ReconnectDecisionsTest {

    // --- isHandshakeStale: wedge detection + clock-anomaly safety ---

    @Test fun `handshake younger than threshold is not stale`() {
        assertThat(
            ReconnectDecisions.isHandshakeStale(
                connectingSinceMillis = 1_000,
                nowMillis = 1_000 + 14_999,
                thresholdMillis = 15_000,
            ),
        ).isFalse()
    }

    @Test fun `handshake at exactly the threshold is stale`() {
        assertThat(
            ReconnectDecisions.isHandshakeStale(
                connectingSinceMillis = 1_000,
                nowMillis = 1_000 + 15_000,
                thresholdMillis = 15_000,
            ),
        ).isTrue()
    }

    @Test fun `handshake well past the threshold is stale`() {
        assertThat(
            ReconnectDecisions.isHandshakeStale(
                connectingSinceMillis = 0,
                nowMillis = 60_000,
                thresholdMillis = 15_000,
            ),
        ).isTrue()
    }

    @Test fun `a clock that went backwards never reports stale`() {
        // now < since: a wall-clock correction during the handshake would make the age negative.
        // We must NOT treat that as "stuck forever"; clamp to not-stale so the watchdog keeps
        // waiting rather than tearing down a possibly-healthy connect.
        assertThat(
            ReconnectDecisions.isHandshakeStale(
                connectingSinceMillis = 50_000,
                nowMillis = 10_000,
                thresholdMillis = 15_000,
            ),
        ).isFalse()
    }

    @Test fun `a zero threshold makes any handshake immediately stale`() {
        assertThat(
            ReconnectDecisions.isHandshakeStale(
                connectingSinceMillis = 1_000,
                nowMillis = 1_000,
                thresholdMillis = 0,
            ),
        ).isTrue()
    }

    @Test fun `large monotonic timestamps do not overflow`() {
        // elapsedRealtime on a long-uptime device is a large positive long; the subtraction
        // must stay correct rather than wrapping.
        val since = Long.MAX_VALUE - 20_000
        assertThat(
            ReconnectDecisions.isHandshakeStale(since, since + 15_000, 15_000),
        ).isTrue()
        assertThat(
            ReconnectDecisions.isHandshakeStale(since, since + 14_000, 15_000),
        ).isFalse()
    }

    // --- resumeNudgeAction: the state x age -> action matrix ---

    @Test fun `resume on Connected does nothing`() {
        assertThat(
            ReconnectDecisions.resumeNudgeAction(ConnectionState.Connected("2026.5"), 0),
        ).isEqualTo(ResumeAction.NONE)
    }

    @Test fun `resume on AuthLost does nothing (repository owns its refresh loop)`() {
        assertThat(
            ReconnectDecisions.resumeNudgeAction(ConnectionState.AuthLost("expired"), 0),
        ).isEqualTo(ResumeAction.NONE)
    }

    @Test fun `resume on Idle kicks a reconnect`() {
        assertThat(
            ReconnectDecisions.resumeNudgeAction(ConnectionState.Idle, 0),
        ).isEqualTo(ResumeAction.KICK)
    }

    @Test fun `resume on Disconnected kicks a reconnect`() {
        val st = ConnectionState.Disconnected(ConnectionState.Cause.ServerClosed, attempt = 0)
        assertThat(ReconnectDecisions.resumeNudgeAction(st, 0)).isEqualTo(ResumeAction.KICK)
    }

    @Test fun `resume on a fresh Connecting leaves the healthy handshake alone`() {
        assertThat(
            ReconnectDecisions.resumeNudgeAction(
                ConnectionState.Connecting,
                connectingAgeMillis = 2_000,
                staleThresholdMillis = ReconnectDecisions.RESUME_STALE_CONNECTING_MS,
            ),
        ).isEqualTo(ResumeAction.NONE)
    }

    @Test fun `resume on a stale Connecting rescues the wedge`() {
        assertThat(
            ReconnectDecisions.resumeNudgeAction(
                ConnectionState.Connecting,
                connectingAgeMillis = ReconnectDecisions.RESUME_STALE_CONNECTING_MS + 1,
            ),
        ).isEqualTo(ResumeAction.RESCUE_STALE)
    }

    @Test fun `resume on a fresh Authenticating leaves it alone`() {
        assertThat(
            ReconnectDecisions.resumeNudgeAction(
                ConnectionState.Authenticating,
                connectingAgeMillis = 1_000,
            ),
        ).isEqualTo(ResumeAction.NONE)
    }

    @Test fun `resume on a stale Authenticating rescues the wedge`() {
        // This is the exact bug shape: socket opened, HA never sent auth_ok, state pinned on
        // Authenticating. A foreground resume must break it.
        assertThat(
            ReconnectDecisions.resumeNudgeAction(
                ConnectionState.Authenticating,
                connectingAgeMillis = 30_000,
            ),
        ).isEqualTo(ResumeAction.RESCUE_STALE)
    }

    @Test fun `the resume staleness threshold is below the WS watchdog budget`() {
        // The resume path must be able to rescue a wedge before the in-WS watchdog's own deadline,
        // for the case where the watchdog coroutine was starved while backgrounded.
        assertThat(ReconnectDecisions.RESUME_STALE_CONNECTING_MS)
            .isLessThan(ReconnectDecisions.HANDSHAKE_WATCHDOG_MS)
    }
}
