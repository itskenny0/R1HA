package com.github.itskenny0.r1ha.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.TokenStore
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.layout.AdaptiveContent

/**
 * Tunes [AppSettings.UiOptions.valueBarTapTargetDp], the WIDTH of the card value
 * bar's invisible touch hit area. The visible slider (track, fill, thumb) keeps its
 * drawn width regardless; this only changes how wide a band along the edge accepts a
 * finger, so a press that lands short of the thin hairline still scrubs.
 *
 * The page draws an example card mirroring the real card-stack layout (body on the
 * left, value bar flush right) and overlays a translucent amber band the exact width
 * of the configured tap target, pinned over the narrow visible bar. The user drags
 * the slider and watches the band grow against the unchanging slider, so the size
 * trade-off (easier to grab vs. crowding the card body) is visible before they commit.
 */
@Composable
fun ValueBarTuningScreen(
    settings: SettingsRepository,
    tokens: TokenStore,
    onBack: () -> Unit,
) {
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(settings, tokens))
    val s by vm.state.collectAsStateWithLifecycle()
    val tapTarget = s.ui.valueBarTapTargetDp.coerceIn(24, 72)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        R1TopBar(title = "VALUE BAR TAP TARGET", onBack = onBack)
        val dimens = com.github.itskenny0.r1ha.core.theme.rememberResponsiveDimens()
        AdaptiveContent(modifier = Modifier.weight(1f)) {
            val bodyModifier = if (dimens.capsContentWidth) {
                Modifier
                    .fillMaxSize()
                    .widthIn(max = dimens.maxContentWidth)
                    .align(Alignment.CenterHorizontally)
            } else {
                Modifier.fillMaxSize()
            }
            Column(
                modifier = bodyModifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = R1.space.xl, vertical = R1.space.m),
            ) {
                Text(
                    text = "How wide a band along the bar's edge accepts a press. " +
                        "The visible slider keeps its size; only the invisible touch " +
                        "zone grows, so a finger that lands near the bar still grabs it.",
                    style = R1.body,
                    color = R1.InkMuted,
                )
                Spacer(Modifier.height(R1.space.l))

                // Live example card. The amber band shows the tap target; the bright
                // hairline + thumb to its right are the visible slider, drawn at their
                // real fixed widths so the comparison is honest.
                ExampleCard(tapTargetDp = tapTarget)

                Spacer(Modifier.height(R1.space.l))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Tap target width",
                        style = R1.bodyEmph,
                        color = R1.Ink,
                        modifier = Modifier.weight(1f),
                    )
                    Text("$tapTarget dp", style = R1.bodyEmph, color = R1.AccentWarm)
                }
                Spacer(Modifier.height(R1.space.s))
                Slider(
                    value = tapTarget.toFloat(),
                    onValueChange = { vm.setValueBarTapTargetDp(it.toInt()) },
                    valueRange = 24f..72f,
                    colors = SliderDefaults.colors(
                        thumbColor = R1.AccentWarm,
                        activeTrackColor = R1.AccentWarm,
                        inactiveTrackColor = R1.Hairline,
                    ),
                )
                Spacer(Modifier.height(R1.space.xxs))
                Text(
                    text = "24 dp is the original size. Larger is easier to grab on a " +
                        "touch screen but leaves a little less room for the card body.",
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                )
            }
        }
    }
}

/**
 * A stripped-down stand-in for a card-stack card: a rounded surface with a label /
 * value on the left and the value bar flush against the right edge. The bar area
 * renders the translucent tap-target band UNDER the visible slider so their relative
 * widths read at a glance. No real entity, no gestures: it is a static diagram.
 */
@Composable
private fun ExampleCard(tapTargetDp: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeM)
            .background(R1.Surface)
            .padding(R1.space.l)
            .height(180.dp),
    ) {
        Text("EXAMPLE LIGHT", style = R1.labelMicro, color = R1.InkMuted)
        Spacer(Modifier.height(R1.space.xs))
        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            // Card body — a representative numeric readout where the entity card
            // would draw its content.
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.Center,
            ) {
                Text("65", style = R1.numeralXl, color = R1.AccentWarm)
                Text("PERCENT", style = R1.labelMicro, color = R1.InkMuted)
            }
            Spacer(Modifier.width(20.dp))
            ValueBarDiagram(tapTargetDp = tapTargetDp, fraction = 0.65f)
        }
    }
}

/**
 * The bar half of the example. Draws the touch band ([tapTargetDp] wide, translucent
 * amber, dashed outline) with the visible slider (2 dp hairline track + accent fill +
 * a small thumb) pinned to its right edge, exactly where [VerticalTapeMeter] puts
 * them. The band is what grows with the slider; the slider geometry never changes.
 */
@Composable
private fun ValueBarDiagram(tapTargetDp: Int, fraction: Float) {
    Box(
        modifier = Modifier
            .width(tapTargetDp.dp)
            .fillMaxHeight(),
    ) {
        // Translucent tap-target band — fills the whole hit-area width so the user
        // sees its true extent. Bordered so its edge is legible even when narrow.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(6.dp))
                .background(R1.AccentWarm.copy(alpha = 0.16f))
                .border(1.dp, R1.AccentWarm.copy(alpha = 0.5f), RoundedCornerShape(6.dp)),
        )
        // Visible hairline track, flush right.
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(2.dp)
                .align(Alignment.CenterEnd)
                .background(R1.SurfaceMuted),
        )
        // Accent fill, grows from the bottom, flush right.
        Box(
            modifier = Modifier
                .fillMaxHeight(fraction)
                .width(4.dp)
                .align(Alignment.BottomEnd)
                .clip(RoundedCornerShape(2.dp))
                .background(R1.AccentWarm),
        )
        // Thumb capsule at the fill height, flush right.
        Box(
            modifier = Modifier
                .fillMaxHeight(fraction)
                .align(Alignment.BottomEnd),
            contentAlignment = Alignment.TopEnd,
        ) {
            Box(
                modifier = Modifier
                    .width(14.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(R1.AccentWarm),
            )
        }
    }
}
