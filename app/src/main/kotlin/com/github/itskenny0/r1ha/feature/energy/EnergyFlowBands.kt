package com.github.itskenny0.r1ha.feature.energy

/**
 * Pure layout-math helpers for the energy-flow Sankey-style diagram and the
 * consumer-distribution bar. Both helpers are side-effect-free so they are
 * unit-testable without any Android / Compose dependency.
 *
 * UNVERIFIED OFFLINE: the data these helpers receive at runtime (productionW,
 * drawW, consumers) comes from EnergyViewModel which talks to a live Home
 * Assistant. The math itself is offline-testable; the end-to-end rendering
 * on a real device with real HA data has not been verified.
 */

/**
 * One proportional band in the flow diagram. Carries the fractional
 * thickness (0..1 relative to the maximum band in the diagram) and a
 * human-readable label for the source or destination node.
 *
 * [fromFrac] is the normalised thickness on the source side;
 * [toFrac] on the destination side (they are equal for straight bands;
 * diverging would model fan-out, but we keep them the same for simplicity).
 * [color] is a stable index (0-based) into the palette so the Canvas
 * composable can pick a color without needing the Color type here.
 */
data class FlowBand(
    /** Human-readable source node label. */
    val sourceLabel: String,
    /** Human-readable destination node label. */
    val destLabel: String,
    /** Watts carried by this band (always >= 0). */
    val watts: Double,
    /** Normalised band thickness, in 0..1 relative to the total flow. */
    val frac: Double,
    /** Stable 0-based palette index for color assignment. */
    val colorIndex: Int,
)

/**
 * Compute the list of flow bands for the energy-flow diagram from the
 * instantaneous power figures already present in EnergyViewModel.UiState.
 *
 * Sources (left side):
 *   - SOLAR: [productionW] (clamped to >= 0)
 *   - GRID:  net grid import = max(0, [drawW] - [productionW])
 *     (negative = exporting, so clamp to zero; a full-export situation renders
 *      only the SOLAR -> HOME band)
 *
 * HOME node (centre): the junction through which all power passes.
 *
 * Consumers (right side): each entry in [consumers], clamped to >= 0.
 *   The consumer list may not sum to [drawW] (sensors missing, unmeasured
 *   loads); no correction is applied - we render what we have.
 *
 * Returns an empty list when [drawW] and [productionW] are both null/zero
 * (nothing to draw). Negative inputs are clamped to zero.
 *
 * The [frac] of every band is relative to the total measured flow
 * (sum of source watts entering HOME), so all fracs lie in [0, 1].
 *
 * @param productionW  Solar / battery production in watts. Null = no solar.
 * @param drawW        Net grid-side draw (whole-home) in watts. Null = unknown.
 * @param consumers    Ordered list of top consumers with watts >= 0.
 */
fun energyFlowBands(
    productionW: Double?,
    drawW: Double?,
    consumers: List<EnergyViewModel.Consumer>,
): List<FlowBand> {
    val solar = (productionW ?: 0.0).coerceAtLeast(0.0)
    val draw = (drawW ?: 0.0).coerceAtLeast(0.0)
    // Grid import = what the home draws minus what solar produces.
    // Clamped to 0: a negative value means we are net-exporting, so the
    // grid is not a source in the flow diagram.
    val gridImport = (draw - solar).coerceAtLeast(0.0)
    val totalSourceWatts = solar + gridImport

    // Nothing to draw when both sources are zero.
    if (totalSourceWatts < 1.0) return emptyList()

    val bands = mutableListOf<FlowBand>()
    var colorIdx = 0

    // Source -> HOME bands.
    if (solar > 0.0) {
        bands += FlowBand(
            sourceLabel = "SOLAR",
            destLabel = "HOME",
            watts = solar,
            frac = solar / totalSourceWatts,
            colorIndex = colorIdx++,
        )
    }
    if (gridImport > 0.0) {
        bands += FlowBand(
            sourceLabel = "GRID",
            destLabel = "HOME",
            watts = gridImport,
            frac = gridImport / totalSourceWatts,
            colorIndex = colorIdx++,
        )
    }

    // HOME -> consumer bands.
    for (c in consumers) {
        val w = c.watts.coerceAtLeast(0.0)
        if (w < 1.0) continue // Sub-1 W consumers add visual noise; skip.
        bands += FlowBand(
            sourceLabel = "HOME",
            destLabel = c.name,
            watts = w,
            frac = (w / totalSourceWatts).coerceAtMost(1.0),
            colorIndex = colorIdx++,
        )
    }

    return bands
}

/**
 * One segment of the consumer-distribution bar.
 *
 * [share] is the fraction [0..1] of this consumer's watts out of the
 * total measured consumer watts. [colorIndex] maps to the same palette
 * as [FlowBand.colorIndex] for visual consistency.
 */
data class ConsumerSegment(
    val name: String,
    val watts: Double,
    /** Share of total: 0.0 .. 1.0 */
    val share: Double,
    val colorIndex: Int,
)

/**
 * Compute the proportional segments for the consumer-distribution bar.
 *
 * Each consumer's [share] = watts / sum(all consumer watts), so the shares
 * sum to 1.0. Returns an empty list when [consumers] is empty or their
 * total wattage is zero.
 *
 * Consumer entries with watts <= 0 are filtered out before computing shares.
 */
fun consumerDistributionSegments(
    consumers: List<EnergyViewModel.Consumer>,
): List<ConsumerSegment> {
    val positive = consumers.filter { it.watts > 0.0 }
    if (positive.isEmpty()) return emptyList()
    val total = positive.sumOf { it.watts }
    if (total < 1e-9) return emptyList()
    return positive.mapIndexed { idx, c ->
        ConsumerSegment(
            name = c.name,
            watts = c.watts,
            share = (c.watts / total).coerceIn(0.0, 1.0),
            colorIndex = idx,
        )
    }
}
