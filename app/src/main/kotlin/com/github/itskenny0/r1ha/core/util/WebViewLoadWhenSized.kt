package com.github.itskenny0.r1ha.core.util

import android.view.View
import android.webkit.WebView

/**
 * Defer [WebView.loadUrl] until the view has non-zero dimensions.
 *
 * Loading from a Compose `remember` factory starts the page in a 0x0 view; the
 * resize to the real size then lands in the middle of the page's bootstrap.
 * HA's frontend recomputes its `narrow` flag from the viewport on every
 * resize, and components like ha-menu-button dereference `this.hass` on a
 * narrow flip without guarding against the property not being assigned yet —
 * an uncaught TypeError that wedges the component's lit update cycle for good.
 * On a desktop browser the viewport never changes mid-load so the upstream bug
 * is invisible; in an embedded WebView the 0x0-to-real resize made it fire on
 * every cold open. Waiting for real dimensions keeps the viewport constant
 * through bootstrap.
 */
fun WebView.loadWhenSized(url: String) {
    if (width > 0 && height > 0) {
        loadUrl(url)
        return
    }
    addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
        override fun onLayoutChange(
            v: View,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
            oldLeft: Int,
            oldTop: Int,
            oldRight: Int,
            oldBottom: Int,
        ) {
            if (right - left > 0 && bottom - top > 0) {
                v.removeOnLayoutChangeListener(this)
                (v as WebView).loadUrl(url)
            }
        }
    })
}
