package com.github.itskenny0.r1ha.feature.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Round-trips the widgetId-to-entityId bindings through the SharedPreferences
 * store: bind / read / rebind / unbind / clearAll, plus the defensive paths
 * (blank entity ids, unknown widget ids, foreign keys in the prefs file).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FavoriteCardWidgetStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun clean() {
        FavoriteCardWidgetStore.clearAll(context)
    }

    @Test fun bindThenReadRoundTrips() {
        FavoriteCardWidgetStore.bind(context, 42, "light.kitchen")
        assertThat(FavoriteCardWidgetStore.entityFor(context, 42)).isEqualTo("light.kitchen")
    }

    @Test fun unknownWidgetIdReadsNull() {
        assertThat(FavoriteCardWidgetStore.entityFor(context, 999)).isNull()
    }

    @Test fun rebindOverwritesPreviousBinding() {
        FavoriteCardWidgetStore.bind(context, 7, "light.kitchen")
        FavoriteCardWidgetStore.bind(context, 7, "switch.fan")
        assertThat(FavoriteCardWidgetStore.entityFor(context, 7)).isEqualTo("switch.fan")
    }

    @Test fun blankEntityIdIsIgnored() {
        FavoriteCardWidgetStore.bind(context, 7, "")
        assertThat(FavoriteCardWidgetStore.entityFor(context, 7)).isNull()
    }

    @Test fun multipleInstancesBindIndependently() {
        FavoriteCardWidgetStore.bind(context, 1, "light.kitchen")
        FavoriteCardWidgetStore.bind(context, 2, "sensor.office_temp")
        assertThat(FavoriteCardWidgetStore.allBindings(context)).containsExactly(
            1, "light.kitchen",
            2, "sensor.office_temp",
        )
    }

    @Test fun unbindRemovesOnlyTheGivenIds() {
        FavoriteCardWidgetStore.bind(context, 1, "light.a")
        FavoriteCardWidgetStore.bind(context, 2, "light.b")
        FavoriteCardWidgetStore.bind(context, 3, "light.c")
        FavoriteCardWidgetStore.unbind(context, intArrayOf(1, 3))
        assertThat(FavoriteCardWidgetStore.entityFor(context, 1)).isNull()
        assertThat(FavoriteCardWidgetStore.entityFor(context, 2)).isEqualTo("light.b")
        assertThat(FavoriteCardWidgetStore.entityFor(context, 3)).isNull()
    }

    @Test fun clearAllEmptiesEveryBinding() {
        FavoriteCardWidgetStore.bind(context, 1, "light.a")
        FavoriteCardWidgetStore.bind(context, 2, "light.b")
        FavoriteCardWidgetStore.clearAll(context)
        assertThat(FavoriteCardWidgetStore.allBindings(context)).isEmpty()
    }
}
