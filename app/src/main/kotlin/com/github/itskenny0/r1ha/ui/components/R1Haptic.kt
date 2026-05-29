package com.github.itskenny0.r1ha.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.github.itskenny0.r1ha.core.util.R1Log

/**
 * Haptic helper that routes through whatever path the host device
 * actually honours. Different Android ROMs gate different APIs:
 *
 *  - **R1 stock LineageOS / CipherOS** — both `Vibrator` and
 *    `performHapticFeedback` work; latter was the original path.
 *  - **Xiaomi MIUI** — `performHapticFeedback` is silenced unless the
 *    user manually flips "Haptic feedback when typing" on; Vibrator
 *    is the only reliable route.
 *  - **Other LineageOS / vendor ROMs** — sometimes the inverse:
 *    Vibrator one-shots get filtered out unless they carry a
 *    VibrationAttributes with `USAGE_TOUCH`, while
 *    `performHapticFeedback` is unaffected.
 *
 * So we fire *both* paths and accept that a few well-tuned devices
 * will perceive each tap as a very slightly punchier click — that's
 * a much better failure mode than "nothing happens" on a $300 phone
 * because the ROM blessed the wrong API. On API 33+ the Vibrator call
 * carries an explicit USAGE_TOUCH attribute so it honours the system
 * Touch-feedback toggle exactly the way the LineageOS launcher does.
 * On API 30-32 the attribute isn't available so we call vibrate()
 * directly; on API 30 VibratorManager doesn't exist yet so we fall
 * back to Context.VIBRATOR_SERVICE (deprecated from 31 but reliable).
 *
 * The VibratorManager and VibrationAttributes references live in
 * @RequiresApi-annotated private functions outside this class body
 * so ART's verifier only resolves those classes when it's actually
 * reachable (i.e. on the API version that provides them).
 */
class R1Haptic internal constructor(
    private val vibrator: Vibrator?,
) {

    /** Short "click" feedback — wheel detents, button taps, scroll pips. */
    fun tick(view: View) = fire(view, tick = true)

    /** Heavier "you held that down" feedback — long-press menus and
     *  destructive-action confirmations. */
    fun longPress(view: View) = fire(view, tick = false)

    private fun fire(view: View, tick: Boolean) {
        // 1) Vibrator path.
        runCatching {
            val v = vibrator ?: return@runCatching
            if (!v.hasVibrator()) return@runCatching
            // Predefined effects (EFFECT_TICK / EFFECT_CLICK) plus the
            // areEffectsSupported() query that backs them land in API 29/30.
            // On 26-29 we skip straight to the createOneShot fallback, which
            // has been available since API 26 and produces a comparable
            // short buzz. Gated at 30 so the single guard covers both the
            // API-29 createPredefined and the API-30 areEffectsSupported call.
            val effect = if (Build.VERSION.SDK_INT >= 30 && predefinedSupported(v, tick)) {
                createPredefinedEffect(tick)
            } else if (tick) {
                VibrationEffect.createOneShot(20L, VibrationEffect.DEFAULT_AMPLITUDE)
            } else {
                VibrationEffect.createOneShot(35L, VibrationEffect.DEFAULT_AMPLITUDE)
            }
            // VibrationAttributes is API 33; VibrationAttributes-less vibrate is
            // deprecated from 26 but works on 30-32. Both branches reference classes
            // in @RequiresApi helpers so ART only loads what's actually reachable.
            if (Build.VERSION.SDK_INT >= 33) {
                vibrateWithAttrs(v, effect)
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(effect)
            }
        }.onFailure {
            R1Log.w("R1Haptic", "vibrator path failed: ${it.message}")
        }

        // 2) View.performHapticFeedback path. Cheap; on a ROM that routes it
        //    to the same motor the Vibrator just hit the system deduplicates.
        //    On a ROM that silently drops Vibrator calls this is the backup.
        runCatching {
            @Suppress("DEPRECATION")
            val constant = if (tick) HapticFeedbackConstants.CLOCK_TICK
            else HapticFeedbackConstants.LONG_PRESS
            view.performHapticFeedback(constant)
        }.onFailure {
            R1Log.d("R1Haptic", "performHapticFeedback failed: ${it.message}")
        }
    }

    companion object {
        fun from(context: Context): R1Haptic {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
                vibratorFromManager(context)
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            return R1Haptic(vibrator)
        }
    }
}

// Separated into @RequiresApi top-level functions so ART's class verifier
// only loads android.os.VibratorManager / android.os.VibrationAttributes
// on devices that actually have them. Placing them inside the class body
// would include them in the class verification pass regardless of the
// SDK_INT guard above.

@RequiresApi(31)
private fun vibratorFromManager(context: Context): Vibrator? {
    val mgr = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
        as? android.os.VibratorManager
    return mgr?.defaultVibrator
}

// Vibrator.areEffectsSupported is API 30. Isolated in a @RequiresApi helper
// (called only behind the SDK_INT >= 30 guard in fire()) so the verifier
// doesn't touch it on 26-29.
@RequiresApi(30)
private fun predefinedSupported(v: Vibrator, tick: Boolean): Boolean {
    val predefined = if (tick) VibrationEffect.EFFECT_TICK else VibrationEffect.EFFECT_CLICK
    return v.areEffectsSupported(predefined).firstOrNull() ==
        Vibrator.VIBRATION_EFFECT_SUPPORT_YES
}

// VibrationEffect.createPredefined is API 29; reachable only behind the
// SDK_INT >= 30 guard, so an API-29 minimum here is comfortably satisfied.
@RequiresApi(29)
private fun createPredefinedEffect(tick: Boolean): VibrationEffect {
    val predefined = if (tick) VibrationEffect.EFFECT_TICK else VibrationEffect.EFFECT_CLICK
    return VibrationEffect.createPredefined(predefined)
}

@RequiresApi(33)
private fun vibrateWithAttrs(v: Vibrator, effect: VibrationEffect) {
    val attrs = android.os.VibrationAttributes.createForUsage(
        android.os.VibrationAttributes.USAGE_TOUCH,
    )
    v.vibrate(effect, attrs)
}

/** Composable accessor — caches an [R1Haptic] for the lifetime of the
 *  current composition. ReadOnlyComposable form for sites that only
 *  read the haptic once. */
@Composable
@ReadOnlyComposable
fun rememberHaptic(): R1Haptic = R1Haptic.from(LocalContext.current)

/** Stateful variant — use from regular composables. Caches the haptic
 *  so we don't re-fetch the VibratorManager on every recomposition. */
@Composable
fun rememberR1Haptic(): R1Haptic {
    val context = LocalContext.current
    return remember(context) { R1Haptic.from(context) }
}

/** Convenience — combines [rememberR1Haptic] with [LocalView] so a call
 *  site only needs one helper invocation. Returns a lambda that fires a
 *  tick when invoked. */
@Composable
fun rememberTickHaptic(): () -> Unit {
    val haptic = rememberR1Haptic()
    val view = LocalView.current
    return remember(haptic, view) { { haptic.tick(view) } }
}

/** Heavier long-press equivalent of [rememberTickHaptic]. */
@Composable
fun rememberLongPressHaptic(): () -> Unit {
    val haptic = rememberR1Haptic()
    val view = LocalView.current
    return remember(haptic, view) { { haptic.longPress(view) } }
}
