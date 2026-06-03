package com.github.itskenny0.r1ha.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.theme.R1

/**
 * The one list / settings row in the R1 design system. Replaces the many hand-rolled
 * `Row { Column(weight) { Text; Text }; value; chevron }` blocks that had each picked their
 * own padding, min height, and ink colours. Guarantees:
 *  - a [R1.MinTarget] (48dp) minimum height so every row is a comfortable wheel-tap target,
 *  - consistent internal padding off the spacing scale,
 *  - one primary/secondary text hierarchy ([R1.bodyEmph] primary, [R1.labelMicro] secondary),
 *  - optional trailing [value] text and a trailing affordance slot, vertically centred.
 *
 * Layout: [leadingContent]? | (label over optional description) | [value]? | [trailing]?
 *
 * Tap behaviour: pass [onClick] for a navigable/actionable row (gets [r1Pressable] press
 * feedback + a Button role); leave it null for a static row. For a chevron-style "navigates
 * further" hint pass [showChevron] = true; it renders the standard muted "›".
 *
 * This is for *rows in a list/settings flow*. It is deliberately not a card: it has no fill
 * by default so it reads as a row in a column. Pass [boxed] = true to wrap it in the standard
 * muted surface + hairline (the treatment Devices / Logbook / Modified-settings rows use).
 */
@Composable
fun R1Row(
    label: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = false,
    boxed: Boolean = false,
    enabled: Boolean = true,
    leadingContent: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    contentDescription: String? = null,
) {
    val surface = if (boxed) {
        Modifier
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
    } else {
        Modifier
    }
    val pressable = if (onClick != null && enabled) {
        Modifier.r1Pressable(onClick = onClick, contentDescription = contentDescription)
    } else {
        Modifier
    }
    val labelColor = if (enabled) R1.Ink else R1.InkMuted

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(surface)
            .then(pressable)
            .heightIn(min = R1.MinTarget)
            .padding(horizontal = R1.space.m, vertical = R1.space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingContent != null) {
            leadingContent()
            Spacer(Modifier.width(R1.space.m))
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(R1.space.xxs),
        ) {
            Text(
                text = label,
                style = R1.bodyEmph,
                color = labelColor,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (description != null) {
                Text(
                    text = description,
                    style = R1.labelMicro,
                    color = R1.InkSoft,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        if (value != null) {
            Spacer(Modifier.width(R1.space.m))
            Text(
                text = value,
                style = R1.bodyEmph,
                color = R1.AccentWarm,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(R1.space.s))
            trailing()
        }
        if (showChevron) {
            Spacer(Modifier.width(R1.space.s))
            Text(text = "›", style = R1.bodyEmph, color = R1.InkSoft)
        }
    }
}
