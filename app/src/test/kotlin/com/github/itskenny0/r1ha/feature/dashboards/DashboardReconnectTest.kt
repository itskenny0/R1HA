package com.github.itskenny0.r1ha.feature.dashboards

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DashboardReconnectTest {

    @Test fun `disconnected to connected triggers a refetch`() {
        assertThat(
            DashboardsViewModel.shouldRefetchOnReconnect(wasConnected = false, nowConnected = true),
        ).isTrue()
    }

    @Test fun `staying connected does not refetch`() {
        assertThat(
            DashboardsViewModel.shouldRefetchOnReconnect(wasConnected = true, nowConnected = true),
        ).isFalse()
    }

    @Test fun `dropping the connection does not refetch`() {
        assertThat(
            DashboardsViewModel.shouldRefetchOnReconnect(wasConnected = true, nowConnected = false),
        ).isFalse()
    }

    @Test fun `staying disconnected does not refetch`() {
        assertThat(
            DashboardsViewModel.shouldRefetchOnReconnect(wasConnected = false, nowConnected = false),
        ).isFalse()
    }
}
