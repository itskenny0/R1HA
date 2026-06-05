package com.github.itskenny0.r1ha.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.theme.R1

/**
 * The one way to title a group of rows. Consolidates the divergent section-header
 * treatments that had appeared across screens: the Settings "title + hairline rule + count
 * pill", the Devices "label + count", and the bare uppercase category labels in Modified
 * Settings. Renders a consistent header (uppercase [R1.sectionHeader] in the accent colour,
 * an optional trailing hairline rule, an optional count pill, an optional description line)
 * followed by the grouped [content] with consistent vertical rhythm.
 *
 * Anatomy:
 *   TITLE ──────────────── [count]   <- header row (rule fills remaining width)
 *   optional description line
 *   content()                        <- the rows, spaced by [R1.space.xs]
 *
 * [rule] draws the hairline that fills the space between title and trailing elements; turn
 * it off for a tighter sub-group heading. [trailing] is an optional slot at the far right
 * (e.g. a RESET [R1Chip]); it sits after the count pill.
 *
 * Spacing: the section reserves [R1.space.xl] of top breathing room by default so stacked
 * sections separate clearly without an explicit divider; pass [topSpace] = [R1.space.s] for
 * the first section on a screen if the top bar already provides the gap.
 */
@Composable
fun R1Section(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    count: Int? = null,
    rule: Boolean = true,
    topSpace: androidx.compose.ui.unit.Dp = R1.space.xl,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Spacer(Modifier.height(topSpace))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = R1.space.l),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // US-pinned so a section title with an 'i' upper-cases to "I", not a dotted
            // "İ", on Turkish / Azeri locales. One chokepoint for every screen's headers.
            Text(title.uppercase(java.util.Locale.US), style = R1.sectionHeader, color = R1.AccentWarm)
            if (count != null) {
                Spacer(Modifier.width(R1.space.s))
                R1Chip(text = count.toString(), variant = R1ChipVariant.Pill)
            }
            if (rule) {
                Spacer(Modifier.width(R1.space.m))
                Box(
                    modifier = Modifier
                        .height(1.dp)
                        .weight(1f)
                        .background(R1.Hairline),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (trailing != null) {
                Spacer(Modifier.width(R1.space.s))
                trailing()
            }
        }
        if (description != null) {
            Spacer(Modifier.height(R1.space.xs))
            Text(
                text = description,
                style = R1.labelMicro,
                color = R1.InkMuted,
                modifier = Modifier.padding(horizontal = R1.space.l),
            )
        }
        Spacer(Modifier.height(R1.space.s))
        Column(verticalArrangement = Arrangement.spacedBy(R1.space.xs)) {
            content()
        }
    }
}
