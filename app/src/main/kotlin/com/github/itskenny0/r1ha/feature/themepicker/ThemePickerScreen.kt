package com.github.itskenny0.r1ha.feature.themepicker

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.itskenny0.r1ha.core.prefs.AppSettings
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.ThemeId
import com.github.itskenny0.r1ha.core.theme.CardRenderModel
import com.github.itskenny0.r1ha.core.theme.ColorfulCardsTheme
import com.github.itskenny0.r1ha.core.theme.MinimalDarkTheme
import com.github.itskenny0.r1ha.core.theme.PragmaticHybridTheme
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.theme.R1Theme
import com.github.itskenny0.r1ha.core.theme.R1ThemeHost
import com.github.itskenny0.r1ha.core.theme.responsiveType
import com.github.itskenny0.r1ha.ui.components.LocalWindowTier
import com.github.itskenny0.r1ha.ui.components.WindowTier
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

/** One-line "what this theme actually looks like" copy, kept beside the theme list so
 *  the picker reads as a guided choice rather than three near-identical rows. */
private fun themeBlurb(id: ThemeId): String = when (id) {
    ThemeId.PRAGMATIC_HYBRID -> "The default. Instrument-panel layout on a dark grey ground, full feature set."
    ThemeId.MINIMAL_DARK -> "Pure black, a single accent, no per-domain colour. The quiet option."
    ThemeId.COLORFUL_CARDS -> "A per-entity gradient sky behind every card, so each tile reads at a glance."
}

/**
 * Curated accent palette for the per-theme override. Limited to a small set
 * of hand-picked colours that read well on every theme's background; lets
 * the user re-skin without dropping into a full RGB picker. "Reset" (null)
 * clears the override and falls back to the theme's native accent.
 */
private val ACCENT_PALETTE: List<Pair<String, Color?>> = listOf(
    "ORANGE" to Color(0xFFF36F21),
    "AMBER" to Color(0xFFFFC107),
    "TEAL" to Color(0xFF26C6DA),
    "BLUE" to Color(0xFF41BDF5),
    "INDIGO" to Color(0xFF7986CB),
    "VIOLET" to Color(0xFFAB47BC),
    "MAGENTA" to Color(0xFFE91E63),
    "RED" to Color(0xFFEF5350),
    "GREEN" to Color(0xFF52C77F),
    "LIME" to Color(0xFFC0CA33),
    "WHITE" to Color(0xFFEDEDED),
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
    // below pick it up live: R1ThemeHost inside ThemeCard doesn't reset
    // LocalThemeAccentOverride, so the override propagates from this scope
    // into each preview. Without this, a user would have to navigate back
    // to a real card screen to see the effect of an accent change.
    androidx.compose.runtime.CompositionLocalProvider(
        com.github.itskenny0.r1ha.core.theme.LocalThemeAccentOverride provides appSettings.themeAccentArgb
            ?.let { Color(it) },
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
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    top = R1.space.s,
                    bottom = R1.space.xl,
                ),
            ) {
                item {
                    PickerEyebrow(
                        label = "APPEARANCE",
                        hint = "Pick a look. The preview is a live card in that theme.",
                    )
                }
                items(ALL_THEMES, key = { it.id }) { theme ->
                    ThemeCard(
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

/**
 * A small uppercase eyebrow + hint above a group. Lighter than [com.github.itskenny0.r1ha.ui.components.R1Section]
 * (no count pill, no hairline rule) because the picker's groups are full-bleed cards /
 * swatch clusters that already separate themselves; the eyebrow just names them.
 */
@Composable
private fun PickerEyebrow(label: String, hint: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = R1.space.l, vertical = R1.space.s),
    ) {
        Text(
            text = label,
            style = responsiveType(R1.sectionHeader),
            color = R1.AccentWarm,
        )
        Spacer(Modifier.height(R1.space.xxs))
        Text(
            text = hint,
            style = responsiveType(R1.body),
            color = R1.InkMuted,
        )
    }
}

/**
 * A full-width theme tile: a live preview card on the left, the theme name + blurb on the
 * right, and a clear selection state (accent border + filled check) wrapping the whole
 * thing. Replaces the old thin radio row — the preview now carries enough of the card to
 * actually convey the theme, and the selected state reads at a glance rather than as a
 * tiny accent square.
 *
 * TalkBack: the whole tile is one selectable node announcing the theme name, its blurb,
 * and the on/off selection state; the decorative preview is dropped from the tree.
 */
@Composable
private fun ThemeCard(
    theme: R1Theme,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    // The preview steps up modestly on roomier panels so it reads at arm's length without
    // ballooning; the aspect ratio is constant so the deck card stays recognisable.
    val previewScale = when (LocalWindowTier.current.tier) {
        WindowTier.R1, WindowTier.COMPACT -> 1.0f
        WindowTier.MEDIUM -> 1.12f
        WindowTier.EXPANDED -> 1.24f
        WindowTier.EXTRA_LARGE -> 1.36f
    }
    val borderColor = if (isSelected) R1.AccentWarm else R1.Hairline
    // Selected tiles lift onto the one-step surface so the chosen theme separates from the
    // unselected ones on the near-black ground without needing a heavy fill.
    val tileBg = if (isSelected) R1.Surface else R1.Bg
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = R1.space.l, vertical = R1.space.xs)
            .clip(R1.ShapeM)
            .background(tileBg)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = borderColor,
                shape = R1.ShapeM,
            )
            .heightIn(min = R1.MinTarget)
            .r1Pressable(onClick)
            .semantics {
                selected = isSelected
                stateDescription = if (isSelected) "Selected" else "Not selected"
            }
            .padding(R1.space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Live preview — the actual theme.Card with a sample model, framed so it reads as a
        // miniature of the deck tile.
        Box(
            modifier = Modifier
                .size(width = 96.dp * previewScale, height = 116.dp * previewScale)
                .clip(R1.ShapeS)
                .clearAndSetSemantics {}
                .border(width = 1.dp, color = R1.Hairline, shape = R1.ShapeS),
        ) {
            R1ThemeHost(themeId = theme.id) {
                theme.Card(
                    model = SAMPLE_CARD,
                    modifier = Modifier.fillMaxSize(),
                    onTapToggle = {},
                )
            }
        }

        Spacer(Modifier.width(R1.space.m))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = theme.displayName.uppercase(),
                style = responsiveType(R1.label),
                color = if (isSelected) R1.AccentWarm else R1.Ink,
            )
            Spacer(Modifier.height(R1.space.xxs))
            Text(
                text = themeBlurb(theme.id),
                style = responsiveType(R1.body),
                color = R1.InkSoft,
            )
        }

        Spacer(Modifier.width(R1.space.s))

        // Selection check — a filled accent disc with a dark tick when chosen, a hollow
        // hairline ring otherwise. Reads as a single glanceable state instead of the old
        // 16dp accent square that vanished into the row.
        SelectionCheck(isSelected = isSelected)
    }
}

/** Filled accent disc + dark tick when selected; a hollow hairline ring otherwise. The
 *  tick is two bars meeting at an elbow, rotated 45° as a unit so it reads as a checkmark
 *  without depending on a particular icon set being on the classpath here. */
@Composable
private fun SelectionCheck(isSelected: Boolean) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(R1.ShapeRound)
            .background(if (isSelected) R1.AccentWarm else Color.Transparent)
            .then(
                if (isSelected) Modifier
                else Modifier.border(1.dp, R1.InkMuted, R1.ShapeRound),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (isSelected) {
            // Short + long bar sharing a bottom edge form an L; rotating the pair 45°
            // turns the L into a checkmark. Drawn in the background ink so it reads on the
            // accent fill across every accent colour.
            Row(
                modifier = Modifier.rotate(45f),
                verticalAlignment = Alignment.Bottom,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 2.dp, height = 5.dp)
                        .clip(R1.ShapeS)
                        .background(R1.Bg),
                )
                Box(
                    modifier = Modifier
                        .size(width = 2.dp, height = 9.dp)
                        .clip(R1.ShapeS)
                        .background(R1.Bg),
                )
            }
        }
    }
}

/**
 * Curated palette of global-accent swatches rendered as circles. Tapping a swatch persists
 * [SettingsRepository] with the chosen ARGB; the "RESET" tile clears the override (passes
 * null) and the theme reverts to its native accent palette. Lives under an [PickerEyebrow]
 * so it reads as a distinct group from the theme tiles above.
 */
@Composable
private fun AccentPickerSection(
    currentArgb: Int?,
    onPick: (Int?) -> Unit,
) {
    Spacer(Modifier.height(R1.space.m))
    PickerEyebrow(
        label = "ACCENT",
        hint = "Tints the whole app: buttons, chips, top bars, and every theme's card " +
            "accents. Reset returns the stock orange and each theme's own palette.",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = R1.space.l, vertical = R1.space.s),
    ) {
        // Six weighted cells suit the R1's narrow panel; on roomier tiers we cap the grid's
        // overall width so the swatches stay a tidy cluster instead of being flung apart into
        // wall-wide cells with a lone circle marooned in each.
        val columns = 6
        val gridModifier = when (LocalWindowTier.current.tier) {
            WindowTier.R1, WindowTier.COMPACT -> Modifier.fillMaxWidth()
            else -> Modifier.fillMaxWidth().widthIn(max = 440.dp)
        }
        ACCENT_PALETTE.chunked(columns).forEach { row ->
            Row(
                modifier = gridModifier,
                horizontalArrangement = Arrangement.spacedBy(R1.space.xs),
            ) {
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
            Spacer(Modifier.height(R1.space.s))
        }
    }
}

@Composable
private fun AccentSwatch(
    label: String,
    color: Color?,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            // Keep each swatch a 48dp min tap target even though the visible
            // circle is only 38dp; the label sits inside the same target.
            .heightIn(min = R1.MinTarget)
            .clip(R1.ShapeS)
            .r1Pressable(
                onClick = onClick,
                contentDescription = if (label == "RESET") {
                    "Reset accent to theme default${if (isSelected) ", selected" else ""}"
                } else {
                    "$label accent${if (isSelected) ", selected" else ""}"
                },
            )
            .semantics { selected = isSelected }
            .padding(vertical = R1.space.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Selected swatches sit inside an accent-ringed well so the chosen colour reads as
        // a deliberate ring rather than a slightly thicker border. The ring is the colour
        // itself for a real swatch (so it glows its own hue) and the ink for RESET.
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(R1.ShapeRound)
                .then(
                    if (isSelected) {
                        Modifier.border(2.dp, color ?: R1.Ink, R1.ShapeRound)
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(if (isSelected) 30.dp else 36.dp)
                    .clip(R1.ShapeRound)
                    .background(color ?: Color.Transparent)
                    .then(
                        if (color == null) {
                            // RESET — a hollow ring with a diagonal feel from the ink border
                            // so it doesn't read as a missing/black swatch.
                            Modifier.border(1.dp, R1.InkMuted, R1.ShapeRound)
                        } else {
                            Modifier
                        },
                    ),
            )
        }
        Spacer(Modifier.height(R1.space.xs))
        Text(
            text = label,
            style = responsiveType(R1.labelMicro),
            color = if (isSelected) R1.Ink else R1.InkMuted,
        )
    }
}
