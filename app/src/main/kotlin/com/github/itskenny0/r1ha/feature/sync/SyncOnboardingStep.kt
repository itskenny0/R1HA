package com.github.itskenny0.r1ha.feature.sync

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
 * they want to mirror their preferences via HA. Three paths:
 *
 *   - YES → enable sync with every category included; the user gets a
 *     short toast saying they can refine in Settings → Sync.
 *   - PICK WHAT TO SYNC → expand the per-category switches inline; the
 *     user confirms and we enable sync with whatever they chose.
 *   - NOT NOW → leave sync off; the user can flip it on later from
 *     Settings → Sync.
 *
 * All three paths flip `haSyncPromptSeen = true` so the post-launch
 * [HaSyncOnboardingPrompt] doesn't also fire (which would be redundant
 * for fresh installs).
 */
@Composable
fun SyncOnboardingStep(
    onAcceptAll: () -> Unit,
    onAcceptWithExclusions: (excludedCategories: Set<String>) -> Unit,
    onDecline: () -> Unit,
) {
    var customising by remember { mutableStateOf(false) }
    // Wheel & input excluded by default — wheel step/curve and key bindings
    // tend to be per-device preferences (R1 hardware wheel vs phone touch,
    // different button layouts), so the safe-default is "don't override the
    // wheel feel across devices". The user can flip it back on if they want
    // to share these too.
    var excluded by remember { mutableStateOf<Set<String>>(setOf(SyncCategory.WHEEL_INPUT.name)) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .systemBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .heightIn(max = 600.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(R1.Surface)
                .border(1.dp, R1.Hairline, RoundedCornerShape(12.dp))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "04 · SYNC", style = R1.labelMicro, color = R1.AccentWarm)
            Text(
                text = "Sync your settings via Home Assistant?",
                style = R1.bodyEmph,
                color = R1.Ink,
            )
            Text(
                text = "Other R1 / phone installs signed into the same HA user " +
                    "will mirror your theme, pages, favourites, key bindings, " +
                    "and overrides. Server URL, iBeacon, webhook, and MQTT stay " +
                    "device-local.",
                style = R1.body,
                color = R1.InkMuted,
            )
            Spacer(Modifier.height(4.dp))
            if (customising) {
                Text(text = "INCLUDE", style = R1.labelMicro, color = R1.InkSoft)
                SyncCategory.entries.forEach { category ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = category.displayLabel,
                            style = R1.body,
                            color = R1.Ink,
                            modifier = Modifier.weight(1f),
                        )
                        val included = !excluded.contains(category.name)
                        R1Switch(
                            checked = included,
                            onCheckedChange = { v ->
                                excluded = excluded.toMutableSet().apply {
                                    if (v) remove(category.name) else add(category.name)
                                }
                            },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                StepButton(
                    text = "CONFIRM",
                    tint = R1.AccentGreen,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onAcceptWithExclusions(excluded) },
                )
                StepButton(
                    text = "BACK",
                    tint = R1.InkMuted,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { customising = false },
                )
            } else {
                StepButton(
                    // Headline reflects the recommended-default behaviour:
                    // everything except wheel + input (which is per-device).
                    text = "YES, SYNC (RECOMMENDED)",
                    tint = R1.AccentGreen,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onAcceptAll,
                )
                StepButton(
                    text = "PICK WHAT TO SYNC",
                    tint = R1.AccentWarm,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { customising = true },
                )
                StepButton(
                    text = "NOT NOW",
                    tint = R1.InkMuted,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onDecline,
                )
            }
        }
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
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = R1.labelMicro, color = tint)
    }
}
