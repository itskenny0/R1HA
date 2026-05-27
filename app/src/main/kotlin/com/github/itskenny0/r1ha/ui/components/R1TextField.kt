package com.github.itskenny0.r1ha.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.theme.R1

/**
 * Single-line text input in the R1 idiom. Built on [BasicTextField] (not Material's
 * `OutlinedTextField`) so we can drive every visual atom from the design tokens:
 *
 *  * Sharp 4dp slot — no Material 12dp+ rounding.
 *  * Hairline 1dp border in [R1.Hairline] at rest, [R1.AccentWarm] when focused,
 *    [R1.StatusRed] when [isError] — animated so the focus transition reads as deliberate
 *    rather than a Material binary flip.
 *  * Monospace text by default (the field is almost always pointed at a URL or a token, so
 *    fixed-width digits help legibility on the R1's tiny display).
 *  * Cursor in the accent colour, not Material's primary.
 *
 * No floating label — the screen's section header serves that role; a Material label on top
 * would duplicate it. A static [placeholder] is shown when [value] is empty.
 */
@Composable
fun R1TextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    monospace: Boolean = true,
    /** Optional [androidx.compose.ui.focus.FocusRequester] — callers pass one
     *  when they want to auto-focus the field on dialog open (so the keyboard
     *  appears without a stray tap). The component attaches it via
     *  Modifier.focusRequester on the inner BasicTextField; callers drive
     *  .requestFocus() from a LaunchedEffect on their side. Null = no
     *  attachment, preserving the original behaviour for every existing
     *  caller. */
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
    /**
     * Set false for fields that should wrap rather than scroll horizontally. The Service
     * Caller's JSON DATA field is the canonical case: a long object body would otherwise
     * trail off the right of the screen with no way to see the trailing characters.
     */
    singleLine: Boolean = true,
    /** Optional minimum height for multi-line fields; ignored when [singleLine] is true. */
    minLines: Int = 1,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    // Internal TextFieldValue so we own cursor + selection state.
    //
    // Background — the String-based BasicTextField overload re-anchors
    // selection on every recomposition because Compose can't tell whether
    // the incoming String is the same value the user just typed (about
    // to be echoed back through the async settings flow) or a fresh
    // programmatic edit. With our SettingsRepository round-tripping every
    // keystroke through DataStore + a Flow emission, that recomposition
    // happens AFTER the user's next keystroke.
    //
    // Two earlier attempts both miscarried:
    //   - [lastSeenExternal]: treated every fresh [value] as external
    //     and overwrote local — dropping characters typed faster than the
    //     round-trip latency.
    //   - [lastPushedOut]: tracked the most recent push, but multiple
    //     pushes can be in flight at once. The first echo (e.g. "1")
    //     arrives while lastPushedOut already says "192", so the guard
    //     fires and rolls local back to "1". Same dropped-character bug.
    //
    // Focus-gated approach: while the field is focused (user is actively
    // typing), [value] from upstream is ignored entirely — local text is
    // the only source of truth. When the field loses focus, any pending
    // external value flows in. That covers both the round-trip echo
    // problem (always present during typing) and the programmatic-edit
    // case (clear button, password regen, restored backup), since those
    // edits happen when the field isn't focused. Edge case: a
    // programmatic edit while the user is still typing won't apply until
    // they tap away — acceptable for the settings surfaces this field
    // backs, where simultaneous human typing + programmatic edits don't
    // happen.
    var localTfv by remember { mutableStateOf(TextFieldValue(text = value)) }
    if (!focused && value != localTfv.text) {
        localTfv = TextFieldValue(text = value, selection = TextRange(value.length))
    }

    val borderTarget = when {
        isError -> R1.StatusRed
        focused -> R1.AccentWarm
        !enabled -> R1.Hairline
        else -> R1.Hairline
    }
    val borderColor by animateColorAsState(targetValue = borderTarget, label = "r1-tf-border")

    val baseStyle = R1.body.copy(
        color = if (enabled) R1.Ink else R1.InkMuted,
        fontFamily = if (monospace) FontFamily.Monospace else R1.body.fontFamily,
    )
    val placeholderStyle: TextStyle = baseStyle.copy(color = R1.InkMuted)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.Bg)
            .border(1.dp, borderColor, R1.ShapeS)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = localTfv,
            onValueChange = { next ->
                val textChanged = next.text != localTfv.text
                localTfv = next
                if (textChanged) {
                    // Only escalate to the caller when the text actually
                    // changed. Pure-selection moves (caret nav, focus
                    // tap) are local to the field and shouldn't fire
                    // spurious settings writes.
                    onValueChange(next.text)
                }
            },
            enabled = enabled,
            singleLine = singleLine,
            minLines = if (singleLine) 1 else minLines.coerceAtLeast(1),
            textStyle = baseStyle,
            cursorBrush = SolidColor(R1.AccentWarm),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            interactionSource = interactionSource,
            modifier = Modifier
                .fillMaxWidth()
                .let { m ->
                    if (focusRequester != null) m.focusRequester(focusRequester) else m
                },
            decorationBox = { innerTextField ->
                if (localTfv.text.isEmpty() && placeholder != null) {
                    Text(text = placeholder, style = placeholderStyle)
                }
                innerTextField()
            },
        )
    }
}
