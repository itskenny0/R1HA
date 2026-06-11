package com.github.itskenny0.r1ha.feature.dashboards.cards.energy

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.itskenny0.r1ha.core.lovelace.EnergyCardKind
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.LocalUiOptions
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.feature.dashboards.cards.CardSurface

/**
 * Single entry point for the energy card family. Every `energy-*` / `*-sankey`
 * card resolves its shared collection (the period + fetched statistics) then
 * dispatches to the kind-specific body. The graph/table/gauge bodies are pure
 * Compose over the [EnergyMath] aggregation; the only IO is the shared
 * collection fetch in [rememberEnergyCollection].
 *
 * UNVERIFIED OFFLINE: the rendered output depends on live HA energy statistics;
 * only the aggregation math underneath is fixture-tested.
 */
@Composable
fun EnergyCard(
    card: LovelaceCard.Energy,
    modifier: Modifier = Modifier,
) {
    val collection = rememberEnergyCollection(card.collectionKey)
    val data by collection.data.collectAsStateWithLifecycle()

    CardSurface(modifier = modifier, title = card.title?.takeUnless { it.isBlank() }) {
        Column(Modifier.padding(horizontal = 14.dp)) {
            when {
                data.error != null -> EnergyMessage(data.error!!)
                !data.loaded -> EnergyMessage("Loading energy...")
                else -> EnergyBody(card.kind, data)
            }
        }
    }
}

@Composable
private fun EnergyBody(kind: EnergyCardKind, data: EnergyCollectionData) {
    when (kind) {
        EnergyCardKind.USAGE_GRAPH -> EnergyUsageGraph(data)
        EnergyCardKind.DISTRIBUTION -> EnergyDistribution(data)
        EnergyCardKind.DEVICES_GRAPH,
        EnergyCardKind.DEVICES_DETAIL_GRAPH,
        -> EnergyDevicesGraph(data)
        EnergyCardKind.SOURCES_TABLE -> EnergySourcesTable(data)
        EnergyCardKind.SOLAR_GRAPH -> EnergySourceGraph(data, "solar", "Solar production")
        EnergyCardKind.GAS_GRAPH -> EnergySourceGraph(data, "gas", "Gas consumption")
        EnergyCardKind.WATER_GRAPH -> EnergySourceGraph(data, "water", "Water consumption")
        EnergyCardKind.SOLAR_CONSUMED_GAUGE -> EnergyGauge(
            value = solarConsumedGauge(data.summed.hasBattery, data.summed),
            label = "Self consumed", min = 0.0, max = 100.0, suffix = "%",
        )
        EnergyCardKind.SELF_SUFFICIENCY_GAUGE -> EnergyGauge(
            value = selfSufficiencyGauge(data.summed),
            label = "Self sufficiency", min = 0.0, max = 100.0, suffix = "%",
        )
        EnergyCardKind.GRID_NEUTRALITY_GAUGE -> EnergyGauge(
            // Grid neutrality is -1..1; render as a centred percentage.
            value = gridNeutralityGauge(data.summed)?.times(100.0),
            label = "Grid neutrality", min = -100.0, max = 100.0, suffix = "%",
        )
        EnergyCardKind.CARBON_CONSUMED_GAUGE -> EnergyGauge(
            // High-carbon (fossil) grid energy from energy/fossil_energy_consumption,
            // summed per HA's carbon-consumed gauge. Null when no CO2 signal source
            // is configured, which keeps the needs-source note.
            value = carbonConsumedGauge(
                data.summed,
                highCarbonEnergy = data.fossilEnergyConsumption
                    ?.let { sumFossilEnergyConsumption(it) },
            ),
            label = "Low-carbon", min = 0.0, max = 100.0, suffix = "%",
            unavailableNote = "Needs a CO2 signal source",
        )
        EnergyCardKind.COMPARE -> EnergyCompare(data)
        EnergyCardKind.GRID_BALANCE -> EnergyGridBalance(data)
        EnergyCardKind.SANKEY,
        EnergyCardKind.POWER_SANKEY,
        EnergyCardKind.WATER_SANKEY,
        EnergyCardKind.WATER_FLOW_SANKEY,
        -> EnergyFlowList(data)
        EnergyCardKind.POWER_SOURCES_GRAPH -> EnergyUsageGraph(data)
    }
}

// ---- shared small pieces ----------------------------------------------------

@Composable
private fun EnergyMessage(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().height(72.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = R1.labelMicro, color = R1.InkMuted)
    }
}

private val ROUTE_COLORS: Map<String, Color>
    @Composable get() = mapOf(
        "solar" to R1.AccentWarm,
        "grid" to R1.AccentCool,
        "battery" to R1.AccentGreen,
        "home" to R1.InkSoft,
        "gas" to R1.StatusAmber,
        "water" to R1.AccentCool,
    )

@Composable
private fun routeColor(route: String): Color =
    ROUTE_COLORS[route.lowercase()] ?: R1.AccentNeutral

@Composable
private fun LegendDot(color: Color, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text("$label $value", style = R1.labelMicro, color = R1.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---- usage graph (stacked consumption bars) ---------------------------------

@Composable
private fun EnergyUsageGraph(data: EnergyCollectionData) {
    val summed = data.summed
    val timestamps = summed.timestamps
    if (timestamps.isEmpty()) {
        EnergyMessage("No usage in this period")
        return
    }
    // Per-bucket stacked consumption: solar, battery, grid (the home-supply mix).
    val solar = routeColor("solar")
    val battery = routeColor("battery")
    val grid = routeColor("grid")
    val bars = timestamps.map { t ->
        val s = computeConsumptionSingle(
            fromGrid = summed.fromGrid[t], toGrid = summed.toGrid[t], solar = summed.solar[t],
            toBattery = summed.toBattery[t], fromBattery = summed.fromBattery[t],
        )
        floatArrayOf(s.usedSolar.toFloat(), s.usedBattery.toFloat(), s.usedGrid.toFloat())
    }
    val maxBar = bars.maxOf { it.sum() }.coerceAtLeast(1e-6f)
    Canvas(
        modifier = Modifier.fillMaxWidth().height(100.dp).clip(RoundedCornerShape(2.dp))
            .background(R1.Surface).padding(vertical = 4.dp),
    ) {
        val n = bars.size
        val slotW = size.width / n
        val barW = (slotW * 0.7f).coerceAtLeast(1f)
        bars.forEachIndexed { i, parts ->
            var y = size.height
            val x = i * slotW + (slotW - barW) / 2f
            listOf(parts[0] to solar, parts[1] to battery, parts[2] to grid).forEach { (v, c) ->
                if (v <= 0f) return@forEach
                val h = (v / maxBar) * size.height
                y -= h
                drawRect(color = c, topLeft = Offset(x, y), size = Size(barW, h))
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    val totals = computeConsumptionData(summed)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (totals.usedSolar > 0) LegendDot(solar, "Solar", formatEnergyKwh(totals.usedSolar))
        if (totals.usedBattery > 0) LegendDot(battery, "Battery", formatEnergyKwh(totals.usedBattery))
        if (totals.usedGrid > 0) LegendDot(grid, "Grid", formatEnergyKwh(totals.usedGrid))
    }
}

// ---- distribution (circles view) --------------------------------------------

@Composable
private fun EnergyDistribution(data: EnergyCollectionData) {
    val summed = data.summed
    val totals = computeConsumptionData(summed)
    val reduceMotion = LocalUiOptions.current.reduceMotion
    val nodes = buildList {
        if (summed.hasSolar) add(Triple("Solar", summed.total.solar, routeColor("solar")))
        if (summed.hasGrid) add(Triple("Grid", summed.total.fromGrid, routeColor("grid")))
        if (summed.hasBattery) add(Triple("Battery", totals.usedBattery, routeColor("battery")))
        add(Triple("Home", totals.usedTotal.coerceAtLeast(0.0), routeColor("home")))
    }
    if (nodes.all { it.second <= 0.0 }) {
        EnergyMessage("No distribution data")
        return
    }
    // A compact node row with a moving-dash flow band beneath, honouring
    // reduced-motion (then a static band).
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        nodes.forEach { (label, value, color) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier.size(44.dp).clip(CircleShape)
                        .border(2.dp, color, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        formatEnergyKwh(value).substringBefore(" "),
                        style = R1.numeralS, color = R1.Ink, maxLines = 1,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(label, style = R1.labelMicro, color = R1.InkSoft)
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    FlowBand(animate = !reduceMotion, color = routeColor("home"))
}

@Composable
private fun FlowBand(animate: Boolean, color: Color) {
    val phase = if (animate) {
        val t = rememberInfiniteTransition(label = "flow")
        val p by t.animateFloat(
            initialValue = 0f, targetValue = 24f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = LinearEasing), repeatMode = RepeatMode.Restart,
            ),
            label = "dash",
        )
        p
    } else {
        0f
    }
    Canvas(Modifier.fillMaxWidth().height(6.dp)) {
        drawLine(
            color = color,
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = 4f,
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 12f), phase),
        )
    }
}

// ---- devices graph (sorted horizontal bars) ---------------------------------

@Composable
private fun EnergyDevicesGraph(data: EnergyCollectionData) {
    val prefs = data.prefs ?: return EnergyMessage("No devices")
    val totals = deviceTotals(prefs, data.stats).filter { it.kwh > 0.0 }
    if (totals.isEmpty()) {
        EnergyMessage("No device usage in this period")
        return
    }
    val max = totals.maxOf { it.kwh }.coerceAtLeast(1e-6)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        totals.take(8).forEach { dev ->
            Column {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        dev.name ?: dev.statId.substringAfterLast('.').replace('_', ' '),
                        style = R1.labelMicro, color = R1.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(formatEnergyKwh(dev.kwh), style = R1.labelMicro, color = R1.InkSoft)
                }
                Spacer(Modifier.height(2.dp))
                Box(
                    Modifier.fillMaxWidth().height(6.dp).clip(R1.ShapeRound).background(R1.SurfaceMuted),
                ) {
                    Box(
                        Modifier.fillMaxWidth((dev.kwh / max).toFloat()).height(6.dp)
                            .clip(R1.ShapeRound).background(R1.AccentCool),
                    )
                }
            }
        }
    }
}

// ---- sources table ----------------------------------------------------------

@Composable
private fun EnergySourcesTable(data: EnergyCollectionData) {
    val prefs = data.prefs ?: return EnergyMessage("No sources")
    val rows = sourceTableRows(prefs, data.stats, data.info.costSensors)
    if (rows.isEmpty()) {
        EnergyMessage("No sources configured")
        return
    }
    val hasCost = rows.any { it.cost != null }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(routeColor(row.type)))
                Spacer(Modifier.width(6.dp))
                Text(
                    row.label, style = R1.labelMicro, color = R1.Ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Text(formatEnergyKwh(row.energyKwh), style = R1.labelMicro, color = R1.InkSoft)
                if (hasCost) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        row.cost?.let { formatCost(it) } ?: "-",
                        style = R1.labelMicro, color = R1.InkSoft,
                        modifier = Modifier.width(56.dp),
                    )
                }
            }
        }
    }
}

private fun formatCost(value: Double): String =
    String.format(java.util.Locale.US, "%.2f", value)

// ---- single-source graph (solar / gas / water) ------------------------------

@Composable
private fun EnergySourceGraph(data: EnergyCollectionData, type: String, emptyLabel: String) {
    val prefs = data.prefs ?: return EnergyMessage(emptyLabel)
    val ids = prefs.sources.filter { it.type == type }.mapNotNull { it.statEnergyFrom }
    val series = ids.flatMap { changeSeriesOf(data.stats[it]).entries.map { e -> e.key to e.value } }
        .groupBy({ it.first }, { it.second })
        .mapValues { it.value.sum() }
        .toSortedMap()
    if (series.isEmpty() || series.values.all { it <= 0.0 }) {
        EnergyMessage("No $type data in this period")
        return
    }
    val color = routeColor(type)
    val max = series.values.maxOf { it }.coerceAtLeast(1e-6)
    Canvas(
        Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(2.dp))
            .background(R1.Surface).padding(vertical = 4.dp),
    ) {
        val vals = series.values.toList()
        val slotW = size.width / vals.size
        val barW = (slotW * 0.7f).coerceAtLeast(1f)
        vals.forEachIndexed { i, v ->
            val h = (v / max).toFloat() * size.height
            drawRect(
                color = color,
                topLeft = Offset(i * slotW + (slotW - barW) / 2f, size.height - h),
                size = Size(barW, h),
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    LegendDot(color, type.replaceFirstChar(Char::uppercase), formatEnergyKwh(series.values.sum()))
}

// ---- gauges -----------------------------------------------------------------

@Composable
private fun EnergyGauge(
    value: Double?,
    label: String,
    min: Double,
    max: Double,
    suffix: String,
    unavailableNote: String? = null,
) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
            EnergyGaugeArc(fraction = gaugeFraction(value, min, max), accent = R1.AccentCool)
            Text(
                text = value?.let { String.format(java.util.Locale.US, "%.0f%s", it, suffix) } ?: "-",
                style = R1.numeralM, color = R1.Ink,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = R1.bodyEmph, color = R1.Ink)
        if (value == null && unavailableNote != null) {
            Text(unavailableNote, style = R1.labelMicro, color = R1.InkMuted)
        }
    }
}

private fun gaugeFraction(value: Double?, min: Double, max: Double): Float {
    if (value == null || max <= min) return 0f
    return ((value - min) / (max - min)).coerceIn(0.0, 1.0).toFloat()
}

@Composable
private fun EnergyGaugeArc(fraction: Float, accent: Color) {
    Canvas(Modifier.size(180.dp, 90.dp)) {
        val sw = 12.dp.toPx()
        val arcSize = Size(size.width - sw, size.width - sw)
        val off = Offset(sw / 2f, sw / 2f)
        drawArc(
            color = R1.SurfaceMuted, startAngle = 180f, sweepAngle = 180f, useCenter = false,
            topLeft = off, size = arcSize, style = Stroke(width = sw, cap = StrokeCap.Round),
        )
        drawArc(
            color = accent, startAngle = 180f, sweepAngle = 180f * fraction.coerceIn(0f, 1f),
            useCenter = false, topLeft = off, size = arcSize,
            style = Stroke(width = sw, cap = StrokeCap.Round),
        )
    }
}

// ---- compare banner ---------------------------------------------------------

@Composable
private fun EnergyCompare(data: EnergyCollectionData) {
    val cur = computeConsumptionData(data.summed)
    val prev = data.summedCompare?.let { computeConsumptionData(it) }
    if (prev == null) {
        EnergyMessage("Enable compare in the date selector")
        return
    }
    val delta = cur.usedTotal - prev.usedTotal
    val pct = if (prev.usedTotal > 0.0) delta / prev.usedTotal * 100.0 else null
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column {
            Text("This period", style = R1.labelMicro, color = R1.InkMuted)
            Text(formatEnergyKwh(cur.usedTotal), style = R1.numeralM, color = R1.Ink)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val up = delta >= 0.0
            Text(
                (if (up) "+" else "") + formatEnergyKwh(delta),
                style = R1.bodyEmph, color = if (up) R1.StatusRed else R1.AccentGreen,
            )
            pct?.let {
                Text(String.format(java.util.Locale.US, "%+.0f%%", it), style = R1.labelMicro, color = R1.InkSoft)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("Previous", style = R1.labelMicro, color = R1.InkMuted)
            Text(formatEnergyKwh(prev.usedTotal), style = R1.numeralM, color = R1.InkSoft)
        }
    }
}

// ---- grid balance -----------------------------------------------------------

@Composable
private fun EnergyGridBalance(data: EnergyCollectionData) {
    val summed = data.summed
    if (!summed.hasGrid) {
        EnergyMessage("No grid source")
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Imported", style = R1.labelMicro, color = R1.InkMuted)
            Text(formatEnergyKwh(summed.total.fromGrid), style = R1.numeralM, color = routeColor("grid"))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Exported", style = R1.labelMicro, color = R1.InkMuted)
            Text(formatEnergyKwh(summed.total.toGrid), style = R1.numeralM, color = routeColor("solar"))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val net = summed.total.fromGrid - summed.total.toGrid
            Text("Net", style = R1.labelMicro, color = R1.InkMuted)
            Text(
                (if (net >= 0) "+" else "") + formatEnergyKwh(net),
                style = R1.numeralM, color = if (net >= 0) R1.StatusRed else R1.AccentGreen,
            )
        }
    }
}

// ---- flow list (the shared sankey adaptation) -------------------------------

@Composable
private fun EnergyFlowList(data: EnergyCollectionData) {
    val rows = energyFlowRows(data.summed)
    if (rows.isEmpty()) {
        EnergyMessage("No energy flow in this period")
        return
    }
    // A full sankey is illegible at 640x480, so every sankey card variant
    // renders this proportional "source -> bar -> sink" list instead.
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${row.source} → ${row.sink}",
                    style = R1.labelMicro, color = R1.Ink, modifier = Modifier.width(120.dp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Box(
                    Modifier.weight(1f).height(8.dp).clip(R1.ShapeRound).background(R1.SurfaceMuted),
                ) {
                    Box(
                        Modifier.fillMaxWidth(row.fraction.toFloat()).fillMaxSize()
                            .clip(R1.ShapeRound).background(routeColor(row.source)),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(formatEnergyKwh(row.value), style = R1.labelMicro, color = R1.InkSoft)
            }
        }
    }
}
