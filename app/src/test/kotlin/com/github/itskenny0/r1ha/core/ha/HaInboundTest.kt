package com.github.itskenny0.r1ha.core.ha

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class HaInboundTest {
    @Test fun `auth_required`() {
        val m = HaJson.decodeFromString<HaInbound>("""{"type":"auth_required","ha_version":"2026.5.0"}""")
        assertThat(m).isInstanceOf(HaInbound.AuthRequired::class.java)
        assertThat((m as HaInbound.AuthRequired).haVersion).isEqualTo("2026.5.0")
    }
    @Test fun `auth_ok`() {
        val m = HaJson.decodeFromString<HaInbound>("""{"type":"auth_ok","ha_version":"2026.5.0"}""")
        assertThat(m).isInstanceOf(HaInbound.AuthOk::class.java)
    }
    @Test fun `auth_invalid`() {
        val m = HaJson.decodeFromString<HaInbound>("""{"type":"auth_invalid","message":"Invalid access token or password"}""")
        assertThat(m).isInstanceOf(HaInbound.AuthInvalid::class.java)
    }
    @Test fun `result success`() {
        val m = HaJson.decodeFromString<HaInbound>("""{"id":7,"type":"result","success":true,"result":null}""")
        assertThat(m).isInstanceOf(HaInbound.Result::class.java)
        val r = m as HaInbound.Result
        assertThat(r.id).isEqualTo(7); assertThat(r.success).isTrue()
    }
    @Test fun `result error`() {
        val m = HaJson.decodeFromString<HaInbound>("""{"id":7,"type":"result","success":false,"error":{"code":"not_found","message":"nope"}}""")
        val r = m as HaInbound.Result
        assertThat(r.success).isFalse()
        assertThat(r.error?.codeString).isEqualTo("not_found")
        assertThat(r.error?.message).isEqualTo("nope")
    }
    @Test fun `event trigger with state_changed`() {
        val raw = """
            {"id":5,"type":"event","event":{"variables":{"trigger":{"platform":"state","entity_id":"light.k",
            "from_state":{"state":"off","attributes":{}},
            "to_state":{"entity_id":"light.k","state":"on","attributes":{"brightness":128,"friendly_name":"Kitchen"},"last_changed":"2026-05-11T10:00:00+00:00"}}}}}
        """.trimIndent()
        val m = HaJson.decodeFromString<HaInbound>(raw)
        val ev = m as HaInbound.Event
        assertThat(ev.id).isEqualTo(5)
        val toState = ev.event.variables.trigger.toState!!
        assertThat(toState.entityId).isEqualTo("light.k")
        assertThat(toState.state).isEqualTo("on")
        assertThat(toState.attributes["brightness"]?.toString()).isEqualTo("128")
    }
    @Test fun `event tolerates missing to_state state field`() {
        val raw = """
            {"id":6,"type":"event","event":{"variables":{"trigger":{"platform":"state","entity_id":"light.k",
            "to_state":{"entity_id":"light.k","attributes":{}}}}}}
        """.trimIndent()
        val m = HaJson.decodeFromString<HaInbound>(raw)
        val ev = m as HaInbound.Event
        assertThat(ev.event.variables.trigger.toState?.state).isNull()
    }
    @Test fun `result error code tolerates integer code`() {
        val m = HaJson.decodeFromString<HaInbound>(
            """{"id":7,"type":"result","success":false,"error":{"code":15,"message":"nope"}}"""
        )
        val r = m as HaInbound.Result
        assertThat(r.error?.codeString).isEqualTo("15")
    }
    @Test fun `result error with code but no message exposes code for surfacing`() {
        // Some HA error frames / integrations send only `code`, no `message`. The repository's
        // failure path falls back to codeString so the toast/log carries the real reason
        // ("not_found") instead of the opaque "ha_error" sentinel. Lock that the model keeps
        // the code available and reports a null message in this shape.
        val m = HaJson.decodeFromString<HaInbound>(
            """{"id":9,"type":"result","success":false,"error":{"code":"not_found"}}"""
        )
        val r = m as HaInbound.Result
        assertThat(r.success).isFalse()
        assertThat(r.error?.message).isNull()
        assertThat(r.error?.codeString).isEqualTo("not_found")
    }
    @Test fun `pong`() {
        val m = HaJson.decodeFromString<HaInbound>("""{"id":1,"type":"pong"}""")
        assertThat(m).isInstanceOf(HaInbound.Pong::class.java)
    }
}
