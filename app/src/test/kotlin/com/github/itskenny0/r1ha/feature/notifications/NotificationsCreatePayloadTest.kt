package com.github.itskenny0.r1ha.feature.notifications

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

class NotificationsCreatePayloadTest {

    @Test
    fun `title and message are carried through`() {
        val payload = NotificationsViewModel.buildCreatePayload("Boiler", "Pressure low")
        assertThat(payload).isNotNull()
        assertThat(payload!!["message"]).isEqualTo(JsonPrimitive("Pressure low"))
        assertThat(payload["title"]).isEqualTo(JsonPrimitive("Boiler"))
    }

    @Test
    fun `both fields are trimmed`() {
        val payload = NotificationsViewModel.buildCreatePayload("  Boiler  ", "  Pressure low  ")
        assertThat(payload!!["message"]).isEqualTo(JsonPrimitive("Pressure low"))
        assertThat(payload["title"]).isEqualTo(JsonPrimitive("Boiler"))
    }

    @Test
    fun `blank title is omitted so HA supplies its default`() {
        val payload = NotificationsViewModel.buildCreatePayload("", "Body")
        assertThat(payload).isNotNull()
        assertThat(payload!!.containsKey("title")).isFalse()
        assertThat(payload["message"]).isEqualTo(JsonPrimitive("Body"))
    }

    @Test
    fun `whitespace-only title is omitted`() {
        val payload = NotificationsViewModel.buildCreatePayload("   ", "Body")
        assertThat(payload!!.containsKey("title")).isFalse()
    }

    @Test
    fun `blank message yields null since the service rejects an empty body`() {
        assertThat(NotificationsViewModel.buildCreatePayload("Title", "")).isNull()
        assertThat(NotificationsViewModel.buildCreatePayload("Title", "   ")).isNull()
    }
}
