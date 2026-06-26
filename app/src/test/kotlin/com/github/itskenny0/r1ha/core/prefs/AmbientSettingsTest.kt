package com.github.itskenny0.r1ha.core.prefs

import com.github.itskenny0.r1ha.core.sync.SyncCategory
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class AmbientSettingsTest {

    @Test fun `ambient defaults to off and a 60s timeout`() {
        val s = AppSettings()
        assertThat(s.ambient.enabled).isFalse()
        assertThat(s.ambient.idleTimeoutSec).isEqualTo(60)
        assertThat(s.ambient.scope).isEqualTo(AmbientScope.ANYWHERE)
    }

    @Test fun `ambient brightness defaults are 40 percent day and 6 percent night`() {
        val s = AppSettings()
        assertThat(s.ambient.dayBrightnessPct).isEqualTo(40)
        assertThat(s.ambient.nightBrightnessPct).isEqualTo(6)
        assertThat(s.ambient.nightDimEnabled).isTrue()
    }

    @Test fun `AMBIENT sync category copies the ambient block from source`() {
        val local = AppSettings(ambient = AmbientSettings(enabled = true, dayBrightnessPct = 33))
        val remote = AppSettings(ambient = AmbientSettings(enabled = false, dayBrightnessPct = 99))
        // preserve(applied = remote, source = local) is the pull path: local wins.
        val merged = SyncCategory.AMBIENT.preserve(applied = remote, source = local)
        assertThat(merged.ambient).isEqualTo(local.ambient)
    }
}
