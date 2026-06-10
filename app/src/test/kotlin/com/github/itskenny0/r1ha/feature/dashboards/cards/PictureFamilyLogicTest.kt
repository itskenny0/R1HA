package com.github.itskenny0.r1ha.feature.dashboards.cards

import com.github.itskenny0.r1ha.core.lovelace.EntityRow
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.PicturePosition
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PictureFamilyLogicTest {

    private fun row(id: String) = EntityRow(entityId = id, name = null, icon = null, secondaryInfo = null)

    // ── Glance grouping (HA _entitiesDialog / _entitiesToggle) ──────────────

    @Test fun `glance grouping puts non-toggle domains in the dialog group`() {
        val groups = glanceGroups(
            listOf(row("sensor.temp"), row("light.living"), row("binary_sensor.door"), row("switch.fan")),
            forceDialog = false,
        )
        assertThat(groups.dialog.map { it.entityId })
            .containsExactly("sensor.temp", "binary_sensor.door").inOrder()
        assertThat(groups.toggle.map { it.entityId })
            .containsExactly("light.living", "switch.fan").inOrder()
    }

    @Test fun `force_dialog routes every entity to the dialog group`() {
        val groups = glanceGroups(
            listOf(row("light.a"), row("switch.b")),
            forceDialog = true,
        )
        assertThat(groups.dialog).hasSize(2)
        assertThat(groups.toggle).isEmpty()
    }

    @Test fun `glance chip default tap is more-info for dialog and toggle for toggle`() {
        val dialog = glanceChipDefaultTap("sensor.x", dialogGroup = true) as LovelaceAction.Builtin
        val toggle = glanceChipDefaultTap("light.x", dialogGroup = false) as LovelaceAction.Builtin
        assertThat(dialog.name).isEqualTo("more-info")
        assertThat(dialog.entityId).isEqualTo("sensor.x")
        assertThat(toggle.name).isEqualTo("toggle")
        assertThat(toggle.entityId).isEqualTo("light.x")
    }

    // ── stateActive (state-icon / glance colouring) ─────────────────────────

    @Test fun `stateActive treats on as active and off as inactive`() {
        assertThat(stateActive("light", "on")).isTrue()
        assertThat(stateActive("light", "off")).isFalse()
    }

    @Test fun `stateActive treats unavailable and unknown as inactive`() {
        assertThat(stateActive("light", "unavailable")).isFalse()
        assertThat(stateActive("sensor", "unknown")).isFalse()
    }

    @Test fun `stateActive applies domain rules for cover lock and vacuum`() {
        assertThat(stateActive("cover", "closed")).isFalse()
        assertThat(stateActive("cover", "open")).isTrue()
        assertThat(stateActive("lock", "locked")).isFalse()
        assertThat(stateActive("lock", "unlocked")).isTrue()
        assertThat(stateActive("vacuum", "docked")).isFalse()
        assertThat(stateActive("vacuum", "cleaning")).isTrue()
    }

    @Test fun `stateActive treats button-like domains active unless unavailable`() {
        assertThat(stateActive("scene", "2025-01-01T00:00:00+00:00")).isTrue()
        assertThat(stateActive("button", "unavailable")).isFalse()
    }

    @Test fun `stateActive keeps alert off as active`() {
        // HA: an "off" alert is acknowledged but still active; "idle" is inactive.
        assertThat(stateActive("alert", "off")).isTrue()
        assertThat(stateActive("alert", "idle")).isFalse()
    }

    // ── Element tap defaults ────────────────────────────────────────────────

    @Test fun `element tap defaults to more-info on its entity`() {
        val action = elementTapAction(null, "light.a") as LovelaceAction.Builtin
        assertThat(action.name).isEqualTo("more-info")
        assertThat(action.entityId).isEqualTo("light.a")
    }

    @Test fun `element with no entity and no action is inert`() {
        assertThat(elementTapAction(null, null)).isNull()
    }

    @Test fun `element none action stays inert`() {
        assertThat(elementTapAction(LovelaceAction.Builtin("none"), "light.a")).isNull()
    }

    @Test fun `explicit element tap action binds the entity`() {
        val action = elementTapAction(LovelaceAction.Builtin("toggle"), "light.a") as LovelaceAction.Builtin
        assertThat(action.name).isEqualTo("toggle")
        assertThat(action.entityId).isEqualTo("light.a")
    }

    // ── Position anchoring ──────────────────────────────────────────────────

    @Test fun `anchorPx scales a percentage against the box`() {
        assertThat(anchorPx(PicturePosition(40.0, isPixel = false), 200f)).isEqualTo(80f)
    }

    @Test fun `anchorPx passes a pixel value through unchanged`() {
        assertThat(anchorPx(PicturePosition(120.0, isPixel = true), 640f)).isEqualTo(120f)
    }

    @Test fun `default and centring transforms centre on the anchor`() {
        assertThat(elementCentersOnAnchor(null)).isTrue()
        assertThat(elementCentersOnAnchor("translate(-50%, -50%)")).isTrue()
    }

    @Test fun `a non-default transform anchors at the raw point`() {
        assertThat(elementCentersOnAnchor("none")).isFalse()
        assertThat(elementCentersOnAnchor("translate(0, 0)")).isFalse()
    }
}
