package com.github.itskenny0.r1ha.nav

/**
 * Decision for whether a tap on the deck's "add favourites" affordance should
 * push [Routes.FAVORITES_PICKER]. Kept pure and separate from [AppNavGraph] so
 * the open policy is unit-testable without Compose Navigation plumbing.
 *
 * The button only exists on the card deck, so a tap that reaches this code
 * means the user is genuinely looking at the deck: the route is NOT consulted
 * as an allowlist. The earlier route-allowlist version silently dropped every
 * tap whenever [navController]'s currentDestination was anything other than the
 * two deck routes (null during graph setup, mid-transition, a restored back
 * stack, a widget deep link before CARD_STACK_FOCUS was allowlisted), which is
 * the reported "haptic fires but nothing opens".
 *
 * Two cases still block the push:
 *  - the picker is already the current destination (a tap that raced through
 *    after the first push landed), and
 *  - a push fired within [FAVORITES_PICKER_DEBOUNCE_MILLIS] of this one, which
 *    swallows the rapid double-fire (double-tap, or a tap landing while a pager
 *    swipe is still settling) that launchSingleTop alone has not always caught.
 */
const val FAVORITES_PICKER_DEBOUNCE_MILLIS: Long = 500L

/**
 * @param currentRoute [androidx.navigation.NavController.currentDestination]'s
 *   route, or null when the graph has no current destination yet.
 * @param lastOpenedAtMillis the elapsed-time stamp of the previous accepted
 *   push, or a value far enough in the past (e.g. 0) when none has fired.
 * @param nowMillis the current elapsed-time stamp.
 */
fun shouldOpenFavoritesPicker(
    currentRoute: String?,
    lastOpenedAtMillis: Long,
    nowMillis: Long,
): Boolean {
    if (currentRoute == Routes.FAVORITES_PICKER) return false
    if (nowMillis - lastOpenedAtMillis < FAVORITES_PICKER_DEBOUNCE_MILLIS) return false
    return true
}
