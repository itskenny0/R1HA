package com.github.itskenny0.r1ha.core.theme

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.github.itskenny0.r1ha.core.prefs.ThemeId
import com.github.itskenny0.r1ha.core.prefs.UiOptions

val LocalR1Theme = staticCompositionLocalOf<R1Theme> { PragmaticHybridTheme }

/**
 * UI options surfaced to themes so they can honour user toggles without taking extra
 * params. Uses [compositionLocalOf] (not the static variant) because EntityCard merges
 * per-card overrides into this — when the user changes a card's text size or pill
 * visibility from the customize dialog the reading composables MUST recompose, but the
 * surrounding skippable cards won't if reads aren't tracked. The static variant only
 * invalidates the providing scope, leaving skippable inner composables unchanged.
 */
val LocalUiOptions = compositionLocalOf { UiOptions() }

/**
 * Repository handle injected near the top of each screen that needs it (CardStackScreen,
 * FavoritesPickerScreen) so deep composables — [com.github.itskenny0.r1ha.ui.components.SensorCard]
 * especially — can fetch history without every wrapper threading the repository through
 * its parameter list. Null by default; consumers handle that gracefully (skip the chart,
 * skip the history list). Static is fine here — the repository handle only changes at
 * activity launch, never during normal use.
 */
val LocalHaRepository = staticCompositionLocalOf<com.github.itskenny0.r1ha.core.ha.HaRepository?> { null }

/**
 * HA server URL (e.g. `http://homeassistant.local:8123`) surfaced for components
 * that need to resolve relative HA-side URLs — primarily the album-art AsyncBitmap
 * on media_player cards, which receives `entity_picture` as a relative path. Null
 * when no server is configured (onboarding flow). Updated by CardStackScreen /
 * FavoritesPickerScreen from settings.server?.url.
 */
val LocalHaServerUrl = staticCompositionLocalOf<String?> { null }

/**
 * Current HA access token (Bearer). Used by deep image-fetch composables — primarily
 * the album-art [com.github.itskenny0.r1ha.ui.components.AsyncBitmap] — to pass an
 * `Authorization: Bearer <token>` header alongside their request.
 *
 * Why this is needed: HA's `entity_picture` URLs come in two flavours. Some
 * integrations bake a short-lived `?token=…` query parameter into the URL itself
 * (the official media-player proxy does this) and a Bearer header is redundant.
 * Other integrations — and any plain `/api/...` path — require normal HA auth, and
 * fetching them without a Bearer header gets a 401. Always passing the Bearer is
 * harmless in the first case (HA ignores it when a token query param is present) and
 * fixes the loaded-but-blank album cover in the second.
 *
 * Null when no token is available (cold start before tokens load, or the user is
 * signed out).
 */
val LocalHaBearerToken = staticCompositionLocalOf<String?> { null }

/**
 * Per-entity overrides surfaced to deep card composables so the rename / display /
 * long-press customizations can apply without each theme threading them through. The
 * EntityCard wrapper looks up the override for its entity_id and merges visibility
 * fields into a per-card [LocalUiOptions] before invoking the theme's Card. Empty map
 * by default (the wrapper handles the missing-key case gracefully).
 *
 * Uses [compositionLocalOf] (NOT the static variant) so that when the user saves a
 * customize-dialog edit the EntityCard reading this CompositionLocal recomposes
 * immediately. With the static variant the read isn't tracked — invalidation only fires
 * on the providing scope, and the VerticalPager's per-page EntityCard is skippable so
 * it wouldn't recompose with the new map until something else dirtied it (in practice,
 * an app restart). That regression is exactly what motivated this comment; please don't
 * "optimize" it back to the static variant without first verifying live updates still
 * work end-to-end from the customize dialog.
 */
val LocalEntityOverrides = compositionLocalOf<Map<String, com.github.itskenny0.r1ha.core.prefs.EntityOverride>> { emptyMap() }

/**
 * Callback for the BigReadout's tap-to-cycle gesture on light cards. Themes' BigReadout
 * composables consult this; null disables the gesture (used by previews / non-light
 * paths). Wired by CardStackScreen from CardStackViewModel.cycleLightWheelMode. Kept
 * for back-compat / theme variants that still want the cycle gesture; the primary
 * affordance is now the segmented mode buttons that use [LocalOnSetLightWheelMode].
 */
val LocalOnCycleLightMode = staticCompositionLocalOf<((com.github.itskenny0.r1ha.core.ha.EntityId) -> Unit)?> { null }

/**
 * Direct setter for a light's wheel mode. Backs the segmented BRIGHT / WHITE / COLOUR
 * buttons on light cards — a discoverable replacement for the previous tap-to-cycle
 * gesture. Null = previews / non-light contexts.
 */
val LocalOnSetLightWheelMode = staticCompositionLocalOf<
    ((com.github.itskenny0.r1ha.core.ha.EntityId, com.github.itskenny0.r1ha.core.ha.LightWheelMode) -> Unit)?
> { null }

/** Same idea for the light-effect cycle gesture: tap the effect chip → next effect. */
val LocalOnCycleLightEffect = staticCompositionLocalOf<((com.github.itskenny0.r1ha.core.ha.EntityId) -> Unit)?> { null }

/**
 * Direct setter for a light's active effect by name. Backs the effect picker sheet —
 * tap an effect name to apply it, "None" (null) clears the effect. Wired by
 * CardStackScreen from CardStackViewModel.setLightEffect.
 */
val LocalOnSetLightEffect = staticCompositionLocalOf<
    ((com.github.itskenny0.r1ha.core.ha.EntityId, String?) -> Unit)?
> { null }

/**
 * Open the effect picker overlay for [entityId]. Themes call this from the FX button
 * inside a card; CardStackScreen owns the picker visibility state and renders the
 * actual sheet at the top of its layer stack so it can be truly fullscreen rather
 * than confined to the card's bounds.
 */
val LocalOnOpenEffectPicker = staticCompositionLocalOf<
    ((com.github.itskenny0.r1ha.core.ha.EntityId) -> Unit)?
> { null }

/**
 * Media-player transport callback — used by the media_player card's control row to
 * fire play/pause/next/prev/vol+/vol-/mute. Null = previews / non-card contexts.
 */
val LocalOnMediaTransport = staticCompositionLocalOf<
    ((com.github.itskenny0.r1ha.core.ha.EntityId, com.github.itskenny0.r1ha.core.ha.MediaTransport) -> Unit)?
> { null }

/**
 * Request the screen-level select-option picker overlay for [entityId]. SelectCard
 * calls this from its CHOOSE button; CardStackScreen owns the visibility state and
 * renders the actual sheet at the top of its layer stack so it's truly fullscreen.
 */
val LocalOnOpenSelectPicker = staticCompositionLocalOf<
    ((com.github.itskenny0.r1ha.core.ha.EntityId) -> Unit)?
> { null }

/** Direct setter for a select-entity's current option (by string value). */
val LocalOnSetSelectOption = staticCompositionLocalOf<
    ((com.github.itskenny0.r1ha.core.ha.EntityId, String) -> Unit)?
> { null }

/**
 * Direct setter for a card's scalar percent (0..100) — wired by themes from touch
 * drag / tap interactions on the vertical tape meter. Lets the user adjust brightness
 * / volume / temperature with a finger as quickly as the wheel can, without leaving
 * the card. Null = previews / non-card contexts.
 */
val LocalOnSetEntityPercent = staticCompositionLocalOf<
    ((com.github.itskenny0.r1ha.core.ha.EntityId, Int) -> Unit)?
> { null }

/**
 * Generic service-call dispatch from inside a card panel. Dedicated panels
 * (VacuumPanel, ClimatePanel, LockPanel, ValvePanel, WaterHeaterPanel,
 * LawnMowerPanel, MediaExtrasPanel) build a [com.github.itskenny0.r1ha.core.ha.ServiceCall]
 * and invoke this; the screen layer wires it to the repository so panels stay
 * free of repo references. Null in previews / non-card contexts — panels skip
 * dispatch when not set.
 */
val LocalOnEntityCall = staticCompositionLocalOf<
    ((com.github.itskenny0.r1ha.core.ha.ServiceCall) -> Unit)?
> { null }

/**
 * Optional global accent colour override surfaced from
 * [com.github.itskenny0.r1ha.core.prefs.AppSettings.themeAccentArgb]. When
 * non-null, every theme's domain-derived accent is replaced by this colour
 * for cards that don't carry a per-card override (which still wins). Null =
 * use the theme's native accent palette unchanged.
 */
val LocalThemeAccentOverride = staticCompositionLocalOf<androidx.compose.ui.graphics.Color?> { null }

@Composable
fun R1ThemeHost(themeId: ThemeId, content: @Composable () -> Unit) {
    val theme = when (themeId) {
        ThemeId.MINIMAL_DARK -> MinimalDarkTheme
        ThemeId.PRAGMATIC_HYBRID -> PragmaticHybridTheme
        ThemeId.COLORFUL_CARDS -> ColorfulCardsTheme
    }
    CompositionLocalProvider(LocalR1Theme provides theme) {
        MaterialTheme(colorScheme = theme.baseline) {
            // Wrap in a Surface so LocalContentColor is propagated to all descendants,
            // otherwise Text composables without explicit `color` fall back to Color.Black.
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
                content = content,
            )
        }
    }
}
