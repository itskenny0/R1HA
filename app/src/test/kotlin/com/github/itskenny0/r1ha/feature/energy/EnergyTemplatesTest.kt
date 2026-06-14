package com.github.itskenny0.r1ha.feature.energy

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure tests for the exclusion-aware Jinja template builders. The live render
 * against Home Assistant is a device call, so these verify only the SHAPE of
 * the emitted templates: that the safe list builder quotes valid ids, drops
 * junk, collapses an empty set to a no-op `[]`, and that every aggregate splices
 * the rejectattr clause carrying the excluded id.
 */
class EnergyTemplatesTest {

    // ── safe Jinja id-list builder ──────────────────────────────────────────

    @Test fun `empty set yields the no-op empty list literal`() {
        assertThat(EnergyTemplates.jinjaIdList(emptySet())).isEqualTo("[]")
    }

    @Test fun `valid ids are single-quoted and comma-joined`() {
        // LinkedHashSet to pin iteration order for a deterministic assertion.
        val ids = linkedSetOf("sensor.fridge_power", "sensor.tv_power")
        assertThat(EnergyTemplates.jinjaIdList(ids))
            .isEqualTo("['sensor.fridge_power','sensor.tv_power']")
    }

    @Test fun `ids with disallowed characters are dropped entirely`() {
        // Quotes / brackets / braces / uppercase / spaces could break out of the
        // list literal, so the builder must drop them rather than emit them.
        val ids = linkedSetOf(
            "sensor.ok_power",
            "sensor.bad'); }}{{ states",   // injection attempt
            "sensor.Has_Caps",             // uppercase not allowed
            "sensor.has space",            // space not allowed
            "binary_sensor.brackets[0]",   // bracket not allowed
        )
        // Only the clean id survives.
        assertThat(EnergyTemplates.jinjaIdList(ids)).isEqualTo("['sensor.ok_power']")
    }

    @Test fun `a set of only-invalid ids collapses to the empty literal`() {
        assertThat(EnergyTemplates.jinjaIdList(setOf("BAD", "'; drop"))).isEqualTo("[]")
    }

    @Test fun `exclusion clause wraps the list in a rejectattr filter`() {
        assertThat(EnergyTemplates.exclusionClause(setOf("sensor.x_power")))
            .isEqualTo("| rejectattr('entity_id','in',['sensor.x_power']) ")
    }

    @Test fun `exclusion clause for an empty set rejects nothing`() {
        assertThat(EnergyTemplates.exclusionClause(emptySet()))
            .isEqualTo("| rejectattr('entity_id','in',[]) ")
    }

    // ── each aggregate carries the exclusion ────────────────────────────────

    @Test fun `draw template injects the rejectattr clause with the excluded id`() {
        val t = EnergyTemplates.sumPowerDraw(setOf("sensor.total_power"))
        assertThat(t).contains("rejectattr('entity_id','in',['sensor.total_power'])")
        // Still a device_class=power slice (the exclusion sits alongside, not instead).
        assertThat(t).contains("selectattr('attributes.device_class','eq','power')")
    }

    @Test fun `production template injects the exclusion in both passes`() {
        val t = EnergyTemplates.sumProduction(setOf("sensor.total_power"))
        // The generation pass and the grid-export pass each carry the clause, so a
        // count of two membership tests proves both branches honour the exclusion.
        val occurrences = Regex("rejectattr\\('entity_id','in',\\['sensor\\.total_power'\\]\\)")
            .findAll(t).count()
        assertThat(occurrences).isEqualTo(2)
    }

    @Test fun `top consumers template injects the rejectattr clause with the excluded id`() {
        val t = EnergyTemplates.topConsumersJson(setOf("sensor.total_power"))
        assertThat(t).contains("rejectattr('entity_id','in',['sensor.total_power'])")
        assertThat(t).contains("tojson")
    }

    @Test fun `aggregates with no exclusions stay no-ops`() {
        // The empty-set clause appears verbatim in each template, so an unexcluded
        // install renders exactly its historical behaviour.
        val empty = "rejectattr('entity_id','in',[])"
        assertThat(EnergyTemplates.sumPowerDraw(emptySet())).contains(empty)
        assertThat(EnergyTemplates.sumProduction(emptySet())).contains(empty)
        assertThat(EnergyTemplates.topConsumersJson(emptySet())).contains(empty)
    }
}
