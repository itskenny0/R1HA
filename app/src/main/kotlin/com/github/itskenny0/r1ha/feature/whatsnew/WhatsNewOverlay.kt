package com.github.itskenny0.r1ha.feature.whatsnew

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.BuildConfig
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1Button

/**
 * One-shot "what changed in this update" overlay. Shown once after an upgrade
 * (gated by [whatsNewAction] against the stamped versionCode) and reopenable
 * from the About screen. Renders above the NavHost so it appears regardless of
 * which start destination the install uses (card stack, dashboard, kiosk).
 *
 * Dismissal is explicit only (GOT IT): the scrim swallows taps so a stray
 * first-launch tap can't throw the panel away unread, but there's exactly one
 * obvious way out.
 */
@Composable
fun WhatsNewOverlay(
    onDismiss: () -> Unit,
    /**
     * "Don't show these again": flips the show-what's-new preference off and
     * closes the panel. Hidden when null (e.g. a context that has no settings
     * write path). Tucked behind the ⋯ toggle so the one-shot upgrade panel
     * keeps a single obvious action.
     */
    onDisable: (() -> Unit)? = null,
) {
    val menuOpen = remember { androidx.compose.runtime.mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg.copy(alpha = 0.92f))
            // Swallow taps so the card stack underneath doesn't react while
            // the panel is up. No ripple, no haptic: the scrim is inert.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .padding(R1.space.xl)
                .clip(R1.ShapeS)
                .background(R1.Surface)
                .border(1.dp, R1.Hairline, R1.ShapeS)
                .padding(R1.space.l),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "WHAT'S NEW",
                        style = R1.sectionHeader,
                        color = R1.AccentWarm,
                    )
                    Spacer(Modifier.height(R1.space.xxs))
                    Text(
                        text = BuildConfig.VERSION_NAME,
                        style = R1.numeralS,
                        color = R1.InkMuted,
                    )
                }
                if (onDisable != null) {
                    Box(
                        modifier = Modifier
                            .clip(R1.ShapeS)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClickLabel = "More options",
                                onClick = { menuOpen.value = !menuOpen.value },
                            )
                            .padding(horizontal = R1.space.s, vertical = R1.space.xxs),
                    ) {
                        Text(text = "⋯", style = R1.numeralM, color = R1.InkMuted)
                    }
                }
            }
            if (menuOpen.value && onDisable != null) {
                Spacer(Modifier.height(R1.space.s))
                Box(
                    modifier = Modifier
                        .clip(R1.ShapeS)
                        .background(R1.SurfaceMuted)
                        .border(1.dp, R1.Hairline, R1.ShapeS)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClickLabel = "Don't show what's new after updates",
                            onClick = onDisable,
                        )
                        .padding(horizontal = R1.space.m, vertical = R1.space.s),
                ) {
                    Text(
                        text = "DON'T SHOW THESE AGAIN",
                        style = R1.labelMicro,
                        color = R1.InkSoft,
                    )
                }
            }
            Spacer(Modifier.height(R1.space.m))
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(R1.space.s),
            ) {
                WHATS_NEW_ENTRIES.forEach { entry ->
                    Row {
                        Text(text = "•", style = R1.body, color = R1.AccentWarm)
                        Spacer(Modifier.width(R1.space.s))
                        Text(text = entry, style = R1.body, color = R1.InkSoft)
                    }
                }
            }
            Spacer(Modifier.height(R1.space.l))
            R1Button(
                text = "GOT IT",
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}
