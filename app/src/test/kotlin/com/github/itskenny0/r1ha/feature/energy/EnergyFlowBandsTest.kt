package com.github.itskenny0.r1ha.feature.energy

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Covers the pure layout-math helpers:
 *   - [energyFlowBands]: Sankey-style band computation from instantaneous W figures.
 *   - [consumerDistributionSegments]: proportional share computation for the
 *     consumer distribution bar.
 *
 * No Android APIs, no Compose, no coroutines.
 */
class EnergyFlowBandsTest {

    // ---- helpers -----------------------------------------------------------------

    private fun consumer(name: String, watts: Double) =
        EnergyViewModel.Consumer(entityId = "sensor.${name.lowercase()}", name = name, watts = watts)

    // ======================= energyFlowBands =====================================

    @Test fun `empty when both productionW and drawW are null`() {
        assertThat(energyFlowBands(null, null, emptyList())).isEmpty()
    }

    @Test fun `empty when both sources resolve to zero`() {
        assertThat(energyFlowBands(0.0, 0.0, emptyList())).isEmpty()
    }

    @Test fun `empty when productionW is zero and drawW is zero`() {
        assertThat(energyFlowBands(0.0, 0.0, listOf(consumer("Fridge", 120.0)))).isEmpty()
    }

    @Test fun `negative drawW clamped - only solar band produced`() {
        // drawW < 0 is unusual but must not crash; grid = max(0, draw-solar) = 0
        val bands = energyFlowBands(productionW = 500.0, drawW = -100.0, consumers = emptyList())
        assertThat(bands).hasSize(1)
        assertThat(bands[0].sourceLabel).isEqualTo("SOLAR")
        assertThat(bands[0].frac).isWithin(1e-9).of(1.0)
    }

    @Test fun `negative productionW clamped - only grid band produced`() {
        val bands = energyFlowBands(productionW = -200.0, drawW = 300.0, consumers = emptyList())
        // solar clamped to 0, grid = 300
        assertThat(bands).hasSize(1)
        assertThat(bands[0].sourceLabel).isEqualTo("GRID")
        assertThat(bands[0].frac).isWithin(1e-9).of(1.0)
    }

    @Test fun `pure grid install - one source band, no solar`() {
        val bands = energyFlowBands(productionW = 0.0, drawW = 800.0, consumers = emptyList())
        assertThat(bands).hasSize(1)
        assertThat(bands[0].sourceLabel).isEqualTo("GRID")
        assertThat(bands[0].destLabel).isEqualTo("HOME")
        assertThat(bands[0].watts).isEqualTo(800.0)
        assertThat(bands[0].frac).isWithin(1e-9).of(1.0)
    }

    @Test fun `pure solar export install - only solar band (grid import is zero)`() {
        // drawW = 200, productionW = 500 -> gridImport = max(0, 200-500) = 0
        val bands = energyFlowBands(productionW = 500.0, drawW = 200.0, consumers = emptyList())
        assertThat(bands).hasSize(1)
        assertThat(bands[0].sourceLabel).isEqualTo("SOLAR")
        assertThat(bands[0].frac).isWithin(1e-9).of(1.0)
    }

    @Test fun `mixed install - solar and grid fracs sum to 1`() {
        // solar=300, draw=700 -> gridImport=400, total=700
        val bands = energyFlowBands(productionW = 300.0, drawW = 700.0, consumers = emptyList())
        val sourceBands = bands.filter { it.destLabel == "HOME" }
        assertThat(sourceBands).hasSize(2)
        val fracSum = sourceBands.sumOf { it.frac }
        assertThat(fracSum).isWithin(1e-9).of(1.0)
        // Solar fraction = 300/700 ~= 0.4286
        val solarFrac = sourceBands.first { it.sourceLabel == "SOLAR" }.frac
        assertThat(solarFrac).isWithin(1e-6).of(300.0 / 700.0)
    }

    @Test fun `consumers appear as HOME-to-device bands`() {
        val bands = energyFlowBands(
            productionW = null,
            drawW = 1000.0,
            consumers = listOf(consumer("Fridge", 200.0), consumer("Oven", 500.0)),
        )
        val consumerBands = bands.filter { it.sourceLabel == "HOME" }
        assertThat(consumerBands).hasSize(2)
        assertThat(consumerBands.map { it.destLabel }).containsExactly("Fridge", "Oven").inOrder()
    }

    @Test fun `consumer fracs are relative to total source watts not consumer total`() {
        // draw=1000, solar=null -> gridImport=1000; fridge=200 W -> frac = 200/1000
        val bands = energyFlowBands(
            productionW = null,
            drawW = 1000.0,
            consumers = listOf(consumer("Fridge", 200.0)),
        )
        val fridgeBand = bands.first { it.destLabel == "Fridge" }
        assertThat(fridgeBand.frac).isWithin(1e-9).of(0.2)
    }

    @Test fun `sub-1-W consumers are excluded from bands`() {
        val bands = energyFlowBands(
            productionW = null,
            drawW = 500.0,
            consumers = listOf(consumer("Standby", 0.5)),
        )
        assertThat(bands.none { it.sourceLabel == "HOME" }).isTrue()
    }

    @Test fun `zero-watt consumer excluded`() {
        val bands = energyFlowBands(
            productionW = null,
            drawW = 400.0,
            consumers = listOf(consumer("Off", 0.0), consumer("TV", 100.0)),
        )
        val consumerBands = bands.filter { it.sourceLabel == "HOME" }
        assertThat(consumerBands).hasSize(1)
        assertThat(consumerBands[0].destLabel).isEqualTo("TV")
    }

    @Test fun `consumer frac capped at 1 when consumer wattage exceeds measured source`() {
        // Sensor overcount: consumer reports 1500 W but total source is only 1000 W.
        val bands = energyFlowBands(
            productionW = null,
            drawW = 1000.0,
            consumers = listOf(consumer("AC", 1500.0)),
        )
        val acBand = bands.first { it.destLabel == "AC" }
        assertThat(acBand.frac).isAtMost(1.0)
    }

    @Test fun `colorIndex is stable and increments across bands`() {
        val bands = energyFlowBands(
            productionW = 300.0,
            drawW = 700.0,
            consumers = listOf(consumer("Fridge", 100.0), consumer("Oven", 200.0)),
        )
        val indices = bands.map { it.colorIndex }
        // Indices should be distinct and start at 0.
        assertThat(indices).containsNoDuplicates()
        assertThat(indices.min()).isEqualTo(0)
    }

    // ======================= consumerDistributionSegments ========================

    @Test fun `empty when consumer list is empty`() {
        assertThat(consumerDistributionSegments(emptyList())).isEmpty()
    }

    @Test fun `empty when all consumers have zero watts`() {
        assertThat(
            consumerDistributionSegments(listOf(consumer("Off", 0.0), consumer("Standby", 0.0))),
        ).isEmpty()
    }

    @Test fun `single consumer gets share 1`() {
        val segs = consumerDistributionSegments(listOf(consumer("Fridge", 120.0)))
        assertThat(segs).hasSize(1)
        assertThat(segs[0].share).isWithin(1e-9).of(1.0)
    }

    @Test fun `shares sum to 1 for multiple consumers`() {
        val consumers = listOf(
            consumer("Fridge", 120.0),
            consumer("TV", 80.0),
            consumer("Oven", 200.0),
        )
        val segs = consumerDistributionSegments(consumers)
        val sum = segs.sumOf { it.share }
        assertThat(sum).isWithin(1e-9).of(1.0)
    }

    @Test fun `proportions correct for two consumers`() {
        val segs = consumerDistributionSegments(
            listOf(consumer("A", 300.0), consumer("B", 100.0)),
        )
        assertThat(segs[0].share).isWithin(1e-9).of(0.75)
        assertThat(segs[1].share).isWithin(1e-9).of(0.25)
    }

    @Test fun `negative-watt consumers filtered out before share computation`() {
        val segs = consumerDistributionSegments(
            listOf(consumer("Good", 100.0), consumer("Bad", -50.0)),
        )
        assertThat(segs).hasSize(1)
        assertThat(segs[0].name).isEqualTo("Good")
        assertThat(segs[0].share).isWithin(1e-9).of(1.0)
    }

    @Test fun `order of segments matches input order`() {
        val consumers = listOf(
            consumer("Fridge", 120.0),
            consumer("TV", 80.0),
            consumer("Oven", 200.0),
        )
        val segs = consumerDistributionSegments(consumers)
        assertThat(segs.map { it.name }).containsExactly("Fridge", "TV", "Oven").inOrder()
    }

    @Test fun `colorIndex is 0-based sequential`() {
        val consumers = listOf(consumer("A", 100.0), consumer("B", 100.0), consumer("C", 100.0))
        val segs = consumerDistributionSegments(consumers)
        assertThat(segs.map { it.colorIndex }).containsExactly(0, 1, 2).inOrder()
    }

    @Test fun `share clamped to 0-1 range`() {
        val segs = consumerDistributionSegments(listOf(consumer("A", 100.0), consumer("B", 100.0)))
        segs.forEach { seg ->
            assertThat(seg.share).isAtLeast(0.0)
            assertThat(seg.share).isAtMost(1.0)
        }
    }
}
