package com.github.itskenny0.r1ha.feature.themepicker

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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.itskenny0.r1ha.core.prefs.AppSettings
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.CardRenderModel
import com.github.itskenny0.r1ha.core.theme.ColorfulCardsTheme
import com.github.itskenny0.r1ha.core.theme.MinimalDarkTheme
import com.github.itskenny0.r1ha.core.theme.PragmaticHybridTheme
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.theme.R1Theme
import com.github.itskenny0.r1ha.core.theme.R1ThemeHost
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.layout.AdaptiveContent
import kotlinx.coroutines.launch

private val SAMPLE_CARD = CardRenderModel(
    entityIdText = "light.living_room",
    friendlyName = "Living Room",
    area = "Lounge",
    percent = 72,
    isOn = true,
    domainGlyph = CardRenderModel.Glyph.LIGHT,
    accent = CardRenderModel.AccentRole.WARM,
    isAvailable = true,
)

private val ALL_THEMES: List<R1Theme> = listOf(
    PragmaticHybridTheme,
    MinimalDarkTheme,
    ColorfulCardsTheme,
)

/**
 * Curated accent palette for the per-theme override. Limited to a small set
 * of hand-picked colours that read well on every theme's background; lets
 * the user re-skin without dropping into a full RGB picker. "Reset" (null)
 * clears the override and falls back to the theme's native accent.
 */
private val ACCENT_PALETTE: List<Pair<String, androidx.compose.ui.graphics.Color?>> = listOf(
    "ORANGE" to androidx.compose.ui.graphics.Color(0xFFF36F21),
    "AMBER" to androidx.compose.ui.graphics.Color(0xFFFFC107),
    "TEAL" to androidx.compose.ui.graphics.Color(0xFF26C6DA),
    "BLUE" to androidx.compose.ui.graphics.Color(0xFF41BDF5),
    "INDIGO" to androidx.compose.ui.graphics.Color(0xFF7986CB),
    "VIOLET" to androidx.compose.ui.graphics.Color(0xFFAB47BC),
    "MAGENTA" to androidx.compose.ui.graphics.Color(0xFFE91E63),
    "RED" to androidx.compose.ui.graphics.Color(0xFFEF5350),
    "GREEN" to androidx.compose.ui.graphics.Color(0xFF52C77F),
    "LIME" to androidx.compose.ui.graphics.Color(0xFFC0CA33),
    "WHITE" to androidx.compose.ui.graphics.Color(0xFFEDEDED),
    "RESET" to null,
)

@Composable
fun ThemePickerScreen(
    settings: SettingsRepository,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val appSettings by settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val currentThemeId = appSettings.theme

    // Surface the chosen accent override here so the preview cards rendered
    // below pick it up live: R1ThemeHost inside ThemeRow doesn't reset
    // LocalThemeAccentOverride, so the override propagates from this scope
    // into each preview. Without this, a user would have to navigate back
    // to a real card screen to see the effect of an accent change.
    androidx.compose.runtime.CompositionLocalProvider(
        com.github.itskenny0.r1ha.core.theme.LocalThemeAccentOverride provides appSettings.themeAccentArgb
            ?.let { androidx.compose.ui.graphics.Color(it) },
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        R1TopBar(title = "THEME", onBack = onBack)

        AdaptiveContent(modifier = Modifier.weight(1f)) {
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.fillMaxSize().padding(top = 6.dp),
            ) {
                items(ALL_THEMES) { theme ->
                    ThemeRow(
                        theme = theme,
                        isSelected = theme.id == currentThemeId,
                        onClick = {
                            scope.launch {
                                settings.update { it.copy(theme = theme.id) }
                            }
                        },
                    )
                }
                item {
                    AccentPickerSection(
                        currentArgb = appSettings.themeAccentArgb,
                        onPick = { argb ->
                            scope.launch {
                                settings.update { it.copy(themeAccentArgb = argb) }
                            }
                        },
                    )
                }
            }
        } // AdaptiveContent
    }
    } // CompositionLocalProvider
}

@Composable
private fun ThemeRow(
    theme: R1Theme,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .r1Pressable(onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Selection indicator — accent fill when active, otherwise a hairline-outlined hollow
        // square so the unselected state is *visible* on the near-black background instead of
        // a hairline-coloured 14dp square that just disappeared into the bg.
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(R1.ShapeS)
                .background(if (isSelected) R1.AccentWarm else R1.Bg)
                .then(
                    if (isSelected) Modifier
                    else Modifier.border(1.dp, R1.InkMuted, R1.ShapeS),
                ),
        )

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = theme.displayName.uppercase(),
                style = R1.labelMicro,
                color = if (isSelected) R1.AccentWarm else R1.InkSoft,
            )
            Spacer(Modifier.height(2.dp))
            // Short description so the user knows what the theme actually does
            // without having to switch and see. The body line used to just
            // restate the title in a different case, which was redundant once
            // the title sat directly above it.
            Text(
                text = when (theme.id) {
                    com.github.itskenny0.r1ha.core.prefs.ThemeId.PRAGMATIC_HYBRID ->
                        "Default: feature-complete, dark grey ground"
                    com.github.itskenny0.r1ha.core.prefs.ThemeId.MINIMAL_DARK ->
                        "Black ground, monochrome accent"
                    com.github.itskenny0.r1ha.core.prefs.ThemeId.COLORFUL_CARDS ->
                        "Per-entity gradient sky behind each card"
                },
                style = R1.body,
                color = R1.Ink,
            )
        }

        Spacer(Modifier.width(14.dp))

        // Miniature preview — render the actual theme.Card with a sample model.
        Box(
            modifier = Modifier
                .size(width = 92.dp, height = 112.dp)
                .clip(R1.ShapeS)
                .border(
                    width = 1.dp,
                    color = if (isSelected) R1.AccentWarm else R1.Hairline,
                    shape = R1.ShapeS,
                ),
        ) {
            R1ThemeHost(themeId = theme.id) {
                theme.Card(
                    model = SAMPLE_CARD,
                    modifier = Modifier.fillMaxSize(),
                    onTapToggle = {},
                )
            }
        }
    }
}

/**
 * Curated palette of global-accent swatches rendered as 36 dp circles.
 * Tapping a swatch persists [SettingsRepository] with the chosen ARGB; the
 * "RESET" tile clears the override (passes null) and the theme reverts to
 * its native accent palette.
 */
@Composable
private fun AccentPickerSection(
    currentArgb: Int?,
    onPick: (Int?) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 14.dp),
    ) {
        Text(
            text = "ACCENT",
            style = R1.labelMicro,
            color = R1.InkSoft,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "Override every theme's accent colour. Reset = use the theme's own palette.",
            style = R1.body,
            color = R1.InkMuted,
        )
        Spacer(Modifier.height(12.dp))
        // Flow-style wrap with 6 columns, hand-rolled because androidx.compose.foundation.layout.FlowRow
        // is still experimental and we avoid that elsewhere in the codebase.
        val columns = 6
        ACCENT_PALETTE.chunked(columns).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { (label, color) ->
                    val isSelected = color?.toArgb() == currentArgb
                    AccentSwatch(
                        label = label,
                        color = color,
                        isSelected = isSelected,
                        onClick = { onPick(color?.toArgb()) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Pad shorter trailing rows so cells stay the same width.
                repeat(columns - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AccentSwatch(
    label: String,
    color: androidx.compose.ui.graphics.Color?,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .r1Pressable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(R1.ShapeRound)
                .background(color ?: androidx.compose.ui.graphics.Color.Transparent)
                .then(
                    if (color == null) {
                        Modifier.border(1.dp, R1.InkMuted, R1.ShapeRound)
                    } else {
                        Modifier
                    },
                )
                .then(
                    if (isSelected) {
                        Modifier.border(2.dp, R1.Ink, R1.ShapeRound)
                    } else {
                        Modifier
                    },
                ),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = R1.labelMicro,
            color = if (isSelected) R1.Ink else R1.InkMuted,
        )
    }
}
