package com.github.itskenny0.r1ha.core.ha

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.jupiter.api.Test

/**
 * The refresh-failure classifier decides whether the user hears a "session
 * expired, sign out" toast. A 38-minute DNS outage on the device fired 34
 * failed refreshes, each with that toast; only a genuine grant rejection
 * should ever surface it.
 */
class RefreshFailureClassificationTest {

    @Test fun `network failures are transient`() {
        assertThat(classifyRefreshFailure(UnknownHostException("ha.n8.gs")))
            .isEqualTo(RefreshFailureKind.TRANSIENT)
        assertThat(classifyRefreshFailure(SocketTimeoutException("timeout")))
            .isEqualTo(RefreshFailureKind.TRANSIENT)
        assertThat(classifyRefreshFailure(IOException("connection reset")))
            .isEqualTo(RefreshFailureKind.TRANSIENT)
    }

    @Test fun `a generic error (empty body, parse, server 5xx) is transient`() {
        assertThat(classifyRefreshFailure(IllegalStateException("HTTP 503: down")))
            .isEqualTo(RefreshFailureKind.TRANSIENT)
        assertThat(classifyRefreshFailure(IllegalStateException("Empty refresh response")))
            .isEqualTo(RefreshFailureKind.TRANSIENT)
    }

    @Test fun `only an auth rejection is an auth failure`() {
        assertThat(classifyRefreshFailure(AuthRejectedException(400, "invalid_grant")))
            .isEqualTo(RefreshFailureKind.AUTH)
        assertThat(classifyRefreshFailure(AuthRejectedException(401, "unauthorized")))
            .isEqualTo(RefreshFailureKind.AUTH)
    }

    @Test fun `4xx codes are auth rejections, 5xx and others are not`() {
        assertThat(isAuthRejectionCode(400)).isTrue()
        assertThat(isAuthRejectionCode(401)).isTrue()
        assertThat(isAuthRejectionCode(403)).isTrue()
        assertThat(isAuthRejectionCode(404)).isTrue()
        assertThat(isAuthRejectionCode(500)).isFalse()
        assertThat(isAuthRejectionCode(502)).isFalse()
        assertThat(isAuthRejectionCode(503)).isFalse()
        assertThat(isAuthRejectionCode(200)).isFalse()
    }
}
