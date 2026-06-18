package com.github.itskenny0.r1ha.nav

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure-logic coverage for [LegacyFeatures] — the keep / drop route partition that
 * defines the slim "legacy" build. The invariants here are the safety contract: a
 * route an affordance navigates to must be either KEPT (real screen) or in
 * PLACEHOLDER_ROUTES (resolves to the "not in this build" stub), never neither, or
 * the legacy variant would crash on navigate().
 */
class LegacyFeaturesTest {

    @Test fun kept_and_placeholder_sets_are_disjoint() {
        val overlap = LegacyFeatures.KEPT_ROUTES.intersect(LegacyFeatures.PLACEHOLDER_ROUTES.toSet())
        assertThat(overlap).isEmpty()
    }

    @Test fun more_info_drill_ins_are_kept() {
        // more-info sheets navigate only to history / logbook / media-browse; if any of
        // these were dropped the card stack's own drill-ins would dangle.
        assertThat(LegacyFeatures.KEPT_ROUTES).containsAtLeast(
            Routes.HISTORY,
            Routes.LOGBOOK,
            Routes.LOGBOOK_FOR,
            Routes.MEDIA_BROWSE,
            Routes.MEDIA_BROWSE_FOR,
        )
    }

    @Test fun core_shell_routes_are_kept() {
        assertThat(LegacyFeatures.KEPT_ROUTES).containsAtLeast(
            Routes.ONBOARDING,
            Routes.CARD_STACK,
            Routes.CARD_STACK_FOCUS,
            Routes.FAVORITES_PICKER,
            Routes.SETTINGS,
        )
    }

    @Test fun isAvailable_tracks_the_kept_set() {
        assertThat(LegacyFeatures.isAvailable(Routes.CARD_STACK)).isTrue()
        assertThat(LegacyFeatures.isAvailable(Routes.ENERGY)).isFalse()
        assertThat(LegacyFeatures.isAvailable(null)).isFalse()
    }

    @Test fun every_pinnable_surface_is_registered_in_legacy() {
        // The sidebar filters to KEPT surfaces, but belt-and-suspenders: every pinnable
        // route must still resolve (kept or placeholder) so a stale persisted pin can't
        // navigate to an unregistered destination.
        val registered = LegacyFeatures.KEPT_ROUTES + LegacyFeatures.PLACEHOLDER_ROUTES
        PinnableSurfaces.ALL.forEach { surface ->
            assertThat(registered).contains(surface.route)
        }
    }

    @Test fun placeholder_routes_carry_no_path_arguments() {
        // Placeholders are registered without navArgument specs, so a dropped route that
        // embeds a `{arg}` segment can't be placeheld this way. Such routes are only ever
        // reached from another dropped (placeholder) screen, so they're never navigated.
        LegacyFeatures.PLACEHOLDER_ROUTES.forEach { route ->
            assertThat(route).doesNotContain("{")
        }
    }
}
