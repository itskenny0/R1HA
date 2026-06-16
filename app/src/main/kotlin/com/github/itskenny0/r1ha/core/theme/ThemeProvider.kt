package com.github.itskenny0.r1ha.core.theme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.github.itskenny0.r1ha.core.prefs.ThemeId
import com.github.itskenny0.r1ha.core.prefs.UiOptions

val LocalR1Theme = staticCompositionLocalOf<R1Theme> { PragmaticHybridTheme }

/**
 * Active HA theme overlay for the current dashboard context (view, section, or
 * card scope). Composed from the fetched HA theme catalogue and the per-view /
 * per-card `theme:` key, falling back through card -> section -> view -> global.
 *
 * Only card surfaces consult this; the app's own chrome (top bar, nav, settings)
 * ignores it and keeps the R1 design system unchanged. The default is
 * [com.github.itskenny0.r1ha.core.lovelace.HaThemeOverlay.NONE] so cards with
 * no active HA theme render identically to before.
 *
 * Uses [compositionLocalOf] (not the static variant) because different cards
 * within the same view can carry different per-card `theme:` keys; each card
 * provides its own narrowed overlay so Compose tracks the read per-composable
 * rather than invalidating the whole subtree on a coarse change.
 */
val LocalDashboardThemeOverlay = compositionLocalOf {
    com.github.itskenny0.r1ha.core.lovelace.HaThemeOverlay.NONE
}

/**
 * Resolver function for HA theme names: given a theme name returns the derived
 * [com.github.itskenny0.r1ha.core.lovelace.HaThemeOverlay], or null when the
 * name is unknown / absent from the catalogue. Provided by the dashboard screen
 * once the catalogue is fetched; deep composables (per-card renderers) call this
 * to apply a `theme:` key without the screen threading the catalogue through
 * every parameter list.
 *
 * Static is fine: the catalogue only changes on reconnect or a `themes_updated`
 * event, both of which rebuild the providing scope.
 */
val LocalHaThemeLookup = staticCompositionLocalOf<(String?) -> com.github.itskenny0.r1ha.core.lovelace.HaThemeOverlay?> {
    { _ -> null }
}

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
 * Ink palette for the aux card variants (sensor / select / action / switch). The
 * EntityCard wrapper provides the active theme's [AuxCardStyle.ink] here when
 * [R1Theme.auxCardStyle] returns one; the default mirrors the R1 ink tokens exactly so
 * every theme without the hook keeps today's rendering. Static is fine — the value only
 * changes when the theme (or the card's entity) changes, both of which rebuild the
 * providing scope anyway.
 */
val LocalCardInk = staticCompositionLocalOf { DefaultCardInk }

/**
 * Fill colour for the small opaque "inner panels" a card body draws on top of its own
 * backdrop — the sensor history chart's plot box and the action/IR card's fire-actuator
 * disc. These used to hardcode [R1.Surface] (a near-black slab), which on the Colourful
 * Cards theme punched a black hole through the per-entity gradient sky. Routing them
 * through this CompositionLocal lets each theme decide:
 *  - Pragmatic / Minimal: their existing near-black [R1.Surface] (the default below), so
 *    those themes render byte-for-byte unchanged.
 *  - Colourful Cards: a TRANSLUCENT dark tint so the panel reads as sitting IN the
 *    gradient (the sky shows through, just dimmed for legibility) rather than as a stamped
 *    black rectangle.
 *
 * Static is fine: the value only changes when the active theme changes, which rebuilds the
 * providing [R1ThemeHost] scope anyway. The default matches the historical hardcoded fill
 * so any composable that reads it outside a theme that overrides it is unaffected.
 */
val LocalCardPanelColor = staticCompositionLocalOf { R1.Surface }

/**
 * True when a WRAPPER has already painted this card's backdrop (a theme whose card
 * identity IS a gradient, e.g. Colourful Cards, painted via a deck slot surface), so a
 * card FACE that would otherwise stamp its own opaque [R1.Surface] plate should paint a
 * TRANSPARENT face instead and let the wrapper's backdrop + scrim show through. Lets a
 * pinned Lovelace button / IR card read as the same colourful tile as the entity action
 * cards rather than a flat near-black plate sitting on top of the gradient.
 *
 * Default false: every surface that doesn't sit under such a wrapper (the dark themes, a
 * Lovelace card rendered in a full dashboard view rather than a deck slot) keeps painting
 * its opaque plate, byte-identical to today. Static is fine — it only flips when the deck
 * slot wrapper provides it.
 */
val LocalCardBackdropPainted = staticCompositionLocalOf { false }

/**
 * The user's "Colourful Cards" palette-set + background-design choice, surfaced to
 * [ColorfulCardsTheme] so it can pick which six-palette set to hash entities onto and how
 * to paint the per-entity gradient. Provided from the screens that own [AppSettings]
 * (CardStackScreen, FavoritesPickerScreen, the theme picker preview); the default
 * reproduces the shipped look (VIVID set, GRADIENT design) so any surface that doesn't
 * provide it renders the original Colourful Cards.
 *
 * Plain holder (not the live settings object) so the theme — including its non-composable
 * [R1Theme.auxCardStyle], which EntityCard reads this local and forwards into — depends only
 * on the two enums, not on the whole prefs module. Static is fine: it only changes when the
 * user edits the choice, which recomposes the providing scope.
 */
@androidx.compose.runtime.Immutable
data class ColorfulCardsConfig(
    val paletteSet: com.github.itskenny0.r1ha.core.prefs.ColorfulPaletteSet =
        com.github.itskenny0.r1ha.core.prefs.ColorfulPaletteSet.VIVID,
    val backgroundDesign: com.github.itskenny0.r1ha.core.prefs.ColorfulBackgroundDesign =
        com.github.itskenny0.r1ha.core.prefs.ColorfulBackgroundDesign.GRADIENT,
)

val LocalColorfulCardsConfig = staticCompositionLocalOf { ColorfulCardsConfig() }

/**
 * Whether the entity card fills its slot's full height (true, the historical
 * full-screen control surface every pre-existing caller gets) or wraps to its
 * content height (false, the DYNAMIC deck's flowing layout). Provided by the
 * EntityCard wrapper from its fillSlot parameter so the theme card bodies, the
 * value-bar scaffold and the aux variants (sensor / select / action / switch)
 * all agree on the mode without threading a parameter through every layer.
 *
 * In wrap mode every previously fill-height element must resolve to a natural
 * or concrete height: intrinsic measurement is NOT an option (the tape meters
 * are BoxWithConstraints/SubcomposeLayout-backed, which throws on intrinsics),
 * so the vertical meter takes the fixed
 * [com.github.itskenny0.r1ha.feature.cardstack.DYNAMIC_VALUE_BAR_HEIGHT_DP]
 * band and the card's total height becomes the plain sum of its children.
 * Static is fine — the value is constant per deck layout and only changes when
 * the providing call site itself recomposes with a different deck.
 */
val LocalCardFillSlot = staticCompositionLocalOf { true }

/**
 * Optional "make this card the focused/targeted one" action, wired by the DYNAMIC
 * deck so a tap on the card's TITLE selects that card (scrolls it to its snap line
 * and routes the wheel to it) without firing the card. The deck items can be tall
 * (a media card fills most of the screen), so a touch-drag is swallowed by the
 * card's own value bar / internal scroll and the user cannot reach a later card by
 * scrolling: the title tap is the manual target affordance that fixes that.
 *
 * Null by default (the fullscreen pager and any non-deck host do not provide it),
 * so a theme's title is a plain label there. [compositionLocalOf] (not static)
 * because the lambda is provided fresh per card (it closes over that card's index)
 * and the title needs to recompose when it changes.
 */
val LocalOnCardTarget = compositionLocalOf<(() -> Unit)?> { null }

/**
 * Makes a card's TITLE a tap target for [LocalOnCardTarget]: when the deck has
 * provided a target action, a tap selects this card. No-op (returns the modifier
 * unchanged) when no action is provided, so the title stays an inert label
 * outside the dynamic deck. Indication is suppressed (the card scrolling to its
 * snap line is the feedback) and the press uses its own interaction source so it
 * never fights the card body's tap-to-actuate. Shared so all three themes wire
 * the title identically.
 */
@Composable
fun Modifier.cardTitleTarget(): Modifier {
    val onTarget = LocalOnCardTarget.current ?: return this
    val interaction = remember { MutableInteractionSource() }
    return clickable(interactionSource = interaction, indication = null, onClick = onTarget)
}

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
 * Name resolver for Lovelace cards that carry a `name_type` field (HA 2025.11).
 * Backed by the entity, device, and area registries loaded once per dashboard
 * view. The default is an empty resolver that returns null for every lookup,
 * so callers fall back to the entity's friendly_name when the registries are
 * unavailable or still loading.
 *
 * Static is appropriate here: the resolver reference only changes when the
 * registries finish loading (a coarse event), never during normal interactive use.
 */
val LocalNameResolver = staticCompositionLocalOf<com.github.itskenny0.r1ha.feature.dashboards.cards.DashboardNameResolver> {
    com.github.itskenny0.r1ha.feature.dashboards.cards.DashboardNameResolver.EMPTY
}

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
 * Open the colour-wheel overlay for [entityId]. The HUE mode button on light cards
 * calls this in addition to setting the wheel mode, so colour picking works by touch
 * on wheel-less phones while the R1's physical wheel keeps cycling hue underneath.
 * CardStackScreen owns the visibility state and renders the wheel at the top of its
 * layer stack, same hosting pattern as [LocalOnOpenEffectPicker].
 */
val LocalOnOpenColorWheel = staticCompositionLocalOf<
    ((com.github.itskenny0.r1ha.core.ha.EntityId) -> Unit)?
> { null }

/**
 * Open the fan-preset picker overlay for [entityId]. FanPanel calls this so the
 * preset chips aren't a horizontally-scrolling row that competes with the card
 * stack's left/right tab-swipe gesture. CardStackScreen owns the visibility
 * state and renders the actual sheet at the top of its layer stack.
 */
val LocalOnOpenFanPresetPicker = staticCompositionLocalOf<
    ((com.github.itskenny0.r1ha.core.ha.EntityId) -> Unit)?
> { null }

/**
 * Fire a user-defined custom service-call attached to a card. The custom
 * action's domain can differ from the card's entity domain (vendor-specific
 * services like xiaomi_miio_fan.* on a fan.* entity), so this can't piggyback
 * on LocalOnEntityCall which derives the haDomain from the entity. Wired by
 * CardStackScreen to CardStackViewModel.callRawService.
 */
val LocalOnCustomServiceCall = staticCompositionLocalOf<
    ((domain: String, service: String, data: kotlinx.serialization.json.JsonObject) -> Unit)?
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
 * Open the more-info / ultra-detail sheet for a card, invoked by the on-card
 * "..." detail affordance the scaffold draws in the card body's bottom-right.
 * The screen layer provides it (wired to the more-info gate) only when the
 * DETAIL chrome button is enabled and only for the active card; peek neighbours
 * and previews leave it null so no button is drawn.
 */
val LocalOnCardMoreInfo = staticCompositionLocalOf<
    ((com.github.itskenny0.r1ha.core.ha.EntityId) -> Unit)?
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
    CompositionLocalProvider(
        LocalR1Theme provides theme,
        // The active theme's inner-panel fill (near-black for the dark themes, a translucent
        // tint for Colourful Cards) so the chart box / actuator disc read correctly on every
        // theme's backdrop. Provided once here at the theme root rather than per-card.
        LocalCardPanelColor provides theme.cardPanelColor,
    ) {
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
