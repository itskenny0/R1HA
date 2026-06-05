package com.github.itskenny0.r1ha.feature.notifications

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks in [NotificationsViewModel.dismissAllDescription]: the bulk-dismiss button's
 * spoken label pluralises the noun and switches verb on the confirm (armed) state.
 */
class NotificationsDismissAllDescriptionTest {
    @Test fun `single notification is singular`() {
        assertThat(NotificationsViewModel.dismissAllDescription(1, armed = false))
            .isEqualTo("Dismiss all 1 notification")
    }

    @Test fun `multiple notifications are plural`() {
        assertThat(NotificationsViewModel.dismissAllDescription(4, armed = false))
            .isEqualTo("Dismiss all 4 notifications")
    }

    @Test fun `armed state switches the verb`() {
        assertThat(NotificationsViewModel.dismissAllDescription(1, armed = true))
            .isEqualTo("Confirm dismiss all 1 notification")
        assertThat(NotificationsViewModel.dismissAllDescription(3, armed = true))
            .isEqualTo("Confirm dismiss all 3 notifications")
    }
}
