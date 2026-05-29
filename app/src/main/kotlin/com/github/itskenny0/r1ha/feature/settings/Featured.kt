package com.github.itskenny0.r1ha.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * One curated entry in the Settings "Featured" spotlight: a glyph, a title, and a
 * one-line blurb, paired with the deep-link action that opens the feature. The
 * pool is built only from features that have a real `onOpen*` callback wired into
 * [SettingsScreen], so a tap always lands somewhere.
 */
data class FeaturedItem(
    /** Single-glyph emblem rendered large on the lead card / inline on the tail cards. */
    val glyph: String,
    /** Short, sentence-case feature name. */
    val title: String,
    /** One-line "why you'd care" blurb. */
    val blurb: String,
    /** Deep-link: invoked on tap; one of the host's `onOpen*` callbacks. */
    val onOpen: () -> Unit,
)

/**
 * Deterministic selection window over a curated [pool]. Given a rotation [index]
 * that advances by one each cold start, returns [count] consecutive items
 * starting at `index % pool.size`, wrapping around the end. The window is:
 *  - deterministic: same (pool, index) always yields the same trio,
 *  - cycling: as [index] grows the start walks the whole pool before repeating,
 *  - duplicate-free within the trio whenever the pool is at least [count] long
 *    (we never read the same slot twice in one window),
 *  - graceful for a short pool: a pool smaller than [count] simply returns every
 *    item once (no padding, no repeats), and an empty pool returns empty.
 *
 * Pure and Compose-free so it unit-tests without a runtime.
 */
fun featuredFor(
    pool: List<FeaturedItem>,
    index: Int,
    count: Int = 3,
): List<FeaturedItem> {
    if (pool.isEmpty() || count <= 0) return emptyList()
    val size = pool.size
    val take = if (count < size) count else size
    // Normalise the index into [0, size) without letting a negative value (e.g. a
    // wrapped Int) produce a negative modulus.
    val start = ((index % size) + size) % size
    return (0 until take).map { offset -> pool[(start + offset) % size] }
}

/**
 * Per-process rotation seed for the Featured spotlight. The cleanest place for a
 * "cold starts so far" counter is the prefs DataStore, but that lives outside this
 * slice; until a real `featuredRotationIndex` is added there (see the agent
 * report), this captures a seed ONCE per process from the launch wall-clock and
 * caches it for the whole session. That keeps the trio:
 *  - stable within a session (the value is read on first access and never changes,
 *    so recomposition / navigation / back-stack churn never reshuffles it), and
 *  - varied across cold starts (a fresh process samples a fresh millisecond).
 *
 * It does NOT cycle the pool in strict order the way a persisted +1 counter would;
 * that ordering guarantee is what the prefs field unlocks. The math is funneled
 * through [featuredFor] either way, so swapping the source to a persisted index is
 * a one-line change at the call site.
 */
object FeaturedRotation {
    /** Sampled lazily on first read, then frozen for the life of the process. */
    val sessionIndex: Int by lazy {
        // Fold the launch time down to a small non-negative Int. The absolute value
        // is irrelevant; only that it differs run-to-run and is constant per run.
        (System.currentTimeMillis() / 1000L).toInt() and 0x7FFFFFFF
    }
}

// ── Render ──────────────────────────────────────────────────────────────────

/**
 * Emit the Featured spotlight at the very top of the Settings ROOT list: a header
 * label, the lead (tier-1) card full width, then a row of two secondary cards.
 * [items] is the already-selected trio (see [featuredFor]); shorter lists degrade
 * gracefully (lead-only, or nothing). Lives as a [LazyListScope] extension so it
 * slots in as the first items of the ROOT body alongside the category rows.
 */
internal fun LazyListScope.featuredSection(items: List<FeaturedItem>) {
    if (items.isEmpty()) return
    item("__featured_header") {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = R1.space.l)) {
            Spacer(Modifier.height(R1.space.s))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("FEATURED", style = R1.sectionHeader, color = R1.AccentWarm)
                Spacer(Modifier.width(R1.space.m))
                Box(modifier = Modifier.height(1.dp).weight(1f).background(R1.Hairline))
            }
            Spacer(Modifier.height(R1.space.xxs))
            Text(
                text = "Spotlight rotates each launch",
                style = R1.labelMicro,
                color = R1.InkMuted,
            )
        }
    }
    item("__featured_lead") {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = R1.space.l)) {
            Spacer(Modifier.height(R1.space.s))
            FeaturedLeadCard(item = items[0])
            if (items.size > 1) {
                Spacer(Modifier.height(R1.space.s))
                Row(horizontalArrangement = Arrangement.spacedBy(R1.space.s)) {
                    items.drop(1).take(2).forEach { tail ->
                        Box(modifier = Modifier.weight(1f)) { FeaturedTailCard(item = tail) }
                    }
                    // Pad a lone second card so it doesn't stretch full width and
                    // visually masquerade as a second lead.
                    if (items.size == 2) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** Tier-1: large emblem + title + blurb, accent-bordered to read as the headliner. */
@Composable
private fun FeaturedLeadCard(item: FeaturedItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeM)
            .background(R1.Surface)
            .border(1.dp, R1.AccentWarm, R1.ShapeM)
            .r1Pressable(onClick = item.onOpen, contentDescription = "Open ${item.title}")
            .heightIn(min = R1.MinTarget)
            .padding(horizontal = R1.space.l, vertical = R1.space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(R1.ShapeS)
                .background(R1.SurfaceMuted)
                .border(1.dp, R1.Hairline, R1.ShapeS),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = item.glyph, style = R1.numeralM, color = R1.AccentWarm)
        }
        Spacer(Modifier.width(R1.space.l))
        Column(verticalArrangement = Arrangement.spacedBy(R1.space.xxs)) {
            Text(text = item.title, style = R1.titleCard, color = R1.Ink, maxLines = 1)
            Text(text = item.blurb, style = R1.labelMicro, color = R1.InkSoft, maxLines = 2)
        }
    }
}

/** Tier-2/3: compact stacked card; quieter (hairline border, neutral glyph). */
@Composable
private fun FeaturedTailCard(item: FeaturedItem) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeM)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeM)
            .r1Pressable(onClick = item.onOpen, contentDescription = "Open ${item.title}")
            .heightIn(min = 72.dp)
            .padding(horizontal = R1.space.m, vertical = R1.space.s),
        verticalArrangement = Arrangement.spacedBy(R1.space.xxs),
    ) {
        Text(text = item.glyph, style = R1.numeralM, color = R1.InkSoft)
        Spacer(Modifier.height(R1.space.xxs))
        Text(text = item.title, style = R1.bodyEmph, color = R1.Ink, maxLines = 1)
        Text(
            text = item.blurb,
            style = R1.labelMicro,
            color = R1.InkMuted,
            maxLines = 2,
            textAlign = TextAlign.Start,
        )
    }
}
