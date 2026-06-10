package com.github.itskenny0.r1ha.feature.dashboards.cards

import com.github.itskenny0.r1ha.core.lovelace.ActionConfirmation
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure-logic coverage for the central action layer: the default-action
 * fallback chain (resolveTapAction / resolveCardActions) and the confirmation
 * exemption decision. Gesture timing itself is Compose plumbing and out of
 * scope here.
 */
class ActionDispatcherTest {

    @Test fun `absent tap on a toggleable entity defaults to toggle bound to the entity`() {
        val resolved = resolveTapAction(tapAction = null, cardEntityId = "light.kitchen")
        val builtin = resolved as LovelaceAction.Builtin
        assertThat(builtin.name).isEqualTo("toggle")
        assertThat(builtin.entityId).isEqualTo("light.kitchen")
    }

    @Test fun `absent tap on a button entity defaults to press`() {
        val resolved = resolveTapAction(tapAction = null, cardEntityId = "button.doorbell")
        val call = resolved as LovelaceAction.CallService
        assertThat(call.service).isEqualTo("button.press")
        assertThat(call.entityId).isEqualTo("button.doorbell")
    }

    @Test fun `absent tap on a sensor entity defaults to more-info`() {
        val resolved = resolveTapAction(tapAction = null, cardEntityId = "sensor.temp")
        val builtin = resolved as LovelaceAction.Builtin
        assertThat(builtin.name).isEqualTo("more-info")
        assertThat(builtin.entityId).isEqualTo("sensor.temp")
    }

    @Test fun `absent tap with no entity is inert`() {
        assertThat(resolveTapAction(tapAction = null, cardEntityId = null)).isNull()
    }

    @Test fun `badge default-to-more-info opens detail for a toggleable domain`() {
        // A view badge with no tap_action opens more-info for every domain
        // (defaultToMoreInfo), unlike a card which would toggle a light.
        val resolved = resolveTapAction(
            tapAction = null,
            cardEntityId = "light.kitchen",
            defaultToMoreInfo = true,
        )
        val builtin = resolved as LovelaceAction.Builtin
        assertThat(builtin.name).isEqualTo("more-info")
        assertThat(builtin.entityId).isEqualTo("light.kitchen")
    }

    @Test fun `badge default-to-more-info still honours an explicit tap`() {
        val explicit = LovelaceAction.Navigate("/lovelace/0")
        val resolved = resolveTapAction(
            tapAction = explicit,
            cardEntityId = "light.kitchen",
            defaultToMoreInfo = true,
        )
        assertThat(resolved).isEqualTo(explicit)
    }

    @Test fun `explicit toggle action without an entity is bound to the card entity`() {
        val resolved = resolveTapAction(
            tapAction = LovelaceAction.Builtin("toggle"),
            cardEntityId = "switch.fan",
        )
        assertThat((resolved as LovelaceAction.Builtin).entityId).isEqualTo("switch.fan")
    }

    @Test fun `explicit more-info entity override is not overwritten by card entity binding`() {
        val resolved = resolveTapAction(
            tapAction = LovelaceAction.Builtin("more-info", entityId = "sensor.other"),
            cardEntityId = "light.kitchen",
        )
        assertThat((resolved as LovelaceAction.Builtin).entityId).isEqualTo("sensor.other")
    }

    @Test fun `resolveCardActions binds all three slots and applies tap fallback`() {
        val actions = resolveCardActions(
            tapAction = null,
            holdAction = LovelaceAction.Builtin("more-info"),
            doubleTapAction = LovelaceAction.Builtin("toggle"),
            cardEntityId = "light.kitchen",
        )
        // tap fell back to the domain default for a light.
        assertThat((actions.tap as LovelaceAction.Builtin).name).isEqualTo("toggle")
        assertThat((actions.tap as LovelaceAction.Builtin).entityId).isEqualTo("light.kitchen")
        // hold/double bound to the card entity.
        assertThat((actions.hold as LovelaceAction.Builtin).entityId).isEqualTo("light.kitchen")
        assertThat((actions.doubleTap as LovelaceAction.Builtin).entityId).isEqualTo("light.kitchen")
        assertThat(actions.hasHoldOrDoubleTap).isTrue()
    }

    @Test fun `confirmation is not exempt when current user id is unknown`() {
        val c = ActionConfirmation(exemptions = listOf("user-a"))
        assertThat(isConfirmationExempt(c, currentUserId = null)).isFalse()
    }

    @Test fun `confirmation is exempt for a listed user and not for others`() {
        val c = ActionConfirmation(exemptions = listOf("user-a", "user-b"))
        assertThat(isConfirmationExempt(c, currentUserId = "user-b")).isTrue()
        assertThat(isConfirmationExempt(c, currentUserId = "user-c")).isFalse()
    }

    @Test fun `confirmation with no exemptions is never exempt`() {
        val c = ActionConfirmation()
        assertThat(isConfirmationExempt(c, currentUserId = "user-a")).isFalse()
    }
}
