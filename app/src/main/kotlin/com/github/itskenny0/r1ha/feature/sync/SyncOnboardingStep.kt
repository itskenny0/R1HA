package com.github.itskenny0.r1ha.feature.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.sync.SyncCategory
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1Switch
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * Final step of the OAuth / LLAT onboarding flow: ask the user whether
 * to mirror preferences via HA. Three paths:
 *
 *   YES, SYNC               enable with wheel + input excluded (the
 *                           per-device safe-default); a short toast
 *                           explains where to refine later.
 *   PICK WHAT TO SYNC       expand the per-category switches inline; the
 *                           user confirms and we enable with whatever
 *                           they chose.
 *   NOT NOW                 leave sync off; the user can flip it on
 *                           later from Settings, Sync.
 *
 * All three flip `haSyncPromptSeen = true` so the post-launch
 * [HaSyncOnboardingPrompt] doesn't re-fire on first card-stack render.
 *
 * Rendered as a full-screen step (matching 01 LINK and 02 AUTHORISE) so
 * the onboarding feels like one continuous flow rather than a modal that
 * pops on top of a finished screen.
 */
@Composable
fun SyncOnboardingStep(
    onAcceptAll: () -> Unit,
    onAcceptWithExclusions: (excludedCategories: Set<String>) -> Unit,
    onDecline: () -> Unit,
) {
    var customising by remember { mutableStateOf(false) }
    // Wheel + input excluded by default: wheel step/curve and key
    // bindings tend to be per-device preferences (R1 hardware wheel vs
    // phone touch; different button layouts), so the safe default is
    // "don't override wheel feel across devices". The user can flip it
    // back on if they want to share these too.
    var excluded by remember { mutableStateOf<Set<String>>(setOf(SyncCategory.WHEEL_INPUT.name)) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 28.dp),
        ) {
            // Step callout matching 01 LINK / 02 AUTHORISE so this reads as
            // part of the same sequence the user just walked through.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "03", style = R1.labelMicro, color = R1.AccentWarm)
                Spacer(Modifier.size(6.dp))
                Box(modifier = Modifier.size(width = 14.dp, height = 1.dp).background(R1.AccentWarm))
                Spacer(Modifier.size(6.dp))
                Text(text = "SYNC", style = R1.labelMicro, color = R1.AccentWarm)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (customising) "Pick what to sync." else "Mirror settings\nvia Home Assistant?",
                style = R1.screenTitle,
                color = R1.Ink,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Other R1 / phone installs signed into the same HA " +
                    "user will mirror your theme, pages, favourites, and " +
                    "card overrides. Server URL, iBeacon, webhook, and MQTT " +
                    "always stay device-local.",
                style = R1.body,
                color = R1.InkMuted,
            )

            if (customising) {
                Spacer(Modifier.height(20.dp))
                Text(text = "INCLUDE", style = R1.labelMicro, color = R1.InkSoft)
                Spacer(Modifier.height(4.dp))
                SyncCategory.entries.forEach { category ->
                    CategoryRow(
                        label = category.displayLabel,
                        description = category.description,
                        included = !excluded.contains(category.name),
                        onIncludedChange = { v ->
                            excluded = excluded.toMutableSet().apply {
                                if (v) remove(category.name) else add(category.name)
                            }
                        },
                    )
                }
                Spacer(Modifier.height(20.dp))
                StepButton(
                    text = "CONFIRM",
                    tint = R1.AccentGreen,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onAcceptWithExclusions(excluded) },
                )
                Spacer(Modifier.height(8.dp))
                StepButton(
                    text = "BACK",
                    tint = R1.InkMuted,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { customising = false },
                )
            } else {
                Spacer(Modifier.height(24.dp))
                // Primary recommendation. The label spells out the
                // exclusion so the user knows what they're agreeing to
                // without expanding the picker.
                StepButton(
                    text = "YES, SYNC (WHEEL + INPUT STAY LOCAL)",
                    tint = R1.AccentGreen,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onAcceptAll,
                )
                Spacer(Modifier.height(8.dp))
                StepButton(
                    text = "PICK WHAT TO SYNC",
                    tint = R1.AccentWarm,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { customising = true },
                )
                Spacer(Modifier.height(8.dp))
                StepButton(
                    text = "NOT NOW",
                    tint = R1.InkMuted,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onDecline,
                )
            }
            // Trailing spacer so the last button clears the navigation
            // bar on portrait phones / R1's short display.
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Per-category row: label + one-line description + R1Switch toggle. */
@Composable
private fun CategoryRow(
    label: String,
    description: String,
    included: Boolean,
    onIncludedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = R1.bodyEmph,
                color = R1.Ink,
            )
            Text(
                text = description,
                style = R1.body,
                color = R1.InkMuted,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        Spacer(Modifier.size(12.dp))
        R1Switch(
            checked = included,
            onCheckedChange = onIncludedChange,
        )
    }
}

@Composable
private fun StepButton(
    text: String,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = R1.labelMicro, color = tint)
    }
}

