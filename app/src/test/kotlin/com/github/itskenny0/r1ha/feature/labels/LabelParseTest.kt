package com.github.itskenny0.r1ha.feature.labels

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit tests for the pure template-payload parser in
 * [LabelsViewModel.parse]. Covers the happy path plus the resilience the
 * renderer needs: missing color/icon, missing member maps, and a blank name
 * falling back to the id.
 */
class LabelParseTest {

    @Test
    fun parse_reads_full_label_with_members() {
        val json = """
            [
              {
                "id": "lbl_1",
                "name": "Daily Routine",
                "color": "deep-purple",
                "icon": "mdi:calendar",
                "entities": {"light.kitchen": "Kitchen Light"},
                "devices": {"dev1": "Hue Hub"},
                "areas": {"kitchen": "Kitchen"}
              }
            ]
        """.trimIndent()
        val labels = LabelsViewModel.parse(json)
        assertThat(labels).hasSize(1)
        val l = labels.single()
        assertThat(l.id).isEqualTo("lbl_1")
        assertThat(l.name).isEqualTo("Daily Routine")
        assertThat(l.color).isEqualTo("deep-purple")
        assertThat(l.icon).isEqualTo("mdi:calendar")
        assertThat(l.entities).containsExactly("light.kitchen", "Kitchen Light")
        assertThat(l.devices).containsExactly("dev1", "Hue Hub")
        assertThat(l.areas).containsExactly("kitchen", "Kitchen")
        assertThat(l.memberCount).isEqualTo(3)
    }

    @Test
    fun parse_tolerates_missing_optional_fields() {
        val json = """[{"id": "lbl_2", "name": ""}]"""
        val l = LabelsViewModel.parse(json).single()
        assertThat(l.name).isEqualTo("lbl_2")
        assertThat(l.color).isNull()
        assertThat(l.icon).isNull()
        assertThat(l.entities).isEmpty()
        assertThat(l.devices).isEmpty()
        assertThat(l.areas).isEmpty()
        assertThat(l.memberCount).isEqualTo(0)
    }

    @Test
    fun parse_drops_entries_without_an_id() {
        val json = """[{"name": "Orphan"}, {"id": "ok", "name": "OK"}]"""
        val labels = LabelsViewModel.parse(json)
        assertThat(labels.map { it.id }).containsExactly("ok")
    }
}
