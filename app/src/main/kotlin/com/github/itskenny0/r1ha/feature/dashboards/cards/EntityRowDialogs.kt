package com.github.itskenny0.r1ha.feature.dashboards.cards

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Lock code prompt. HA's `code_format` is a regex; a `number`-style format (or
 * a digit-only pattern) gets the keypad, everything else a free-text field. The
 * entered code is validated against the pattern before SUBMIT enables.
 */
@Composable
internal fun LockCodeDialog(
    codeFormat: String?,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    val numeric = codeFormat == "number" || codeFormat?.let {
        // A pattern that only ever accepts digits (no letters in its char classes).
        Regex("[a-zA-Z]").containsMatchIn(it).not()
    } ?: false
    val pattern = remember(codeFormat) {
        runCatching { codeFormat?.takeUnless { it == "number" || it == "text" }?.let { Regex(it) } }.getOrNull()
    }
    if (numeric) {
        KeypadDialog(title = "Enter code", pattern = pattern, onDismiss = onDismiss, onConfirm = onSubmit)
    } else {
        TextCodeDialog(pattern = pattern, onDismiss = onDismiss, onConfirm = onSubmit)
    }
}

@Composable
private fun KeypadDialog(
    title: String,
    pattern: Regex?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var entered by remember { mutableStateOf("") }
    val accent = R1.AccentWarm
    val valid = entered.isNotEmpty() && (pattern?.matches(entered) ?: true)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .background(R1.Bg)
                .border(1.dp, accent, R1.ShapeM)
                .padding(20.dp)
                .width(260.dp),
        ) {
            Text(text = title, style = R1.bodyEmph, color = accent)
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(R1.ShapeS)
                    .background(R1.SurfaceMuted),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (entered.isEmpty()) "·  ·  ·  ·" else "*".repeat(entered.length),
                    style = R1.numeralM,
                    color = R1.Ink,
                )
            }
            Spacer(Modifier.height(10.dp))
            val keys = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("⌫", "0", "OK"),
            )
            keys.forEach { keyRow ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    keyRow.forEach { key ->
                        val isOk = key == "OK"
                        val isBack = key == "⌫"
                        Box(
                            modifier = Modifier
                                .height(44.dp)
                                .weight(1f)
                                .clip(R1.ShapeS)
                                .background(if (isOk && valid) accent else R1.SurfaceMuted)
                                .r1Pressable(onClick = {
                                    when {
                                        isBack -> entered = entered.dropLast(1)
                                        isOk -> if (valid) onConfirm(entered)
                                        entered.length < 12 -> entered += key
                                    }
                                }),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = key,
                                style = R1.numeralM,
                                color = when {
                                    isOk && valid -> R1.Bg
                                    isOk -> R1.InkMuted
                                    else -> R1.Ink
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TextCodeDialog(
    pattern: Regex?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    val accent = R1.AccentWarm
    val valid = code.isNotEmpty() && (pattern?.matches(code) ?: true)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .background(R1.Bg)
                .border(1.dp, accent, R1.ShapeM)
                .padding(20.dp)
                .width(280.dp),
        ) {
            Text(text = "Enter code", style = R1.bodyEmph, color = accent)
            Spacer(Modifier.height(12.dp))
            R1TextField(value = code, onValueChange = { code = it }, monospace = false)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RowActionButton(label = "CANCEL", accent = R1.InkSoft, enabled = true) { onDismiss() }
                RowActionButton(label = "SUBMIT", accent = accent, enabled = valid) { if (valid) onConfirm(code) }
            }
        }
    }
}

/** Scrollable single-select option list for select / input_select rows. */
@Composable
internal fun OptionPickerDialog(
    title: String,
    options: List<String>,
    current: String?,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val accent = R1.AccentWarm
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .background(R1.Bg)
                .border(1.dp, accent, R1.ShapeM)
                .padding(16.dp)
                .width(300.dp),
        ) {
            Text(text = title, style = R1.bodyEmph, color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                options.forEach { option ->
                    val selected = option.equals(current, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(R1.ShapeM)
                            .background(if (selected) accent.copy(alpha = 0.2f) else R1.SurfaceMuted)
                            .border(1.dp, if (selected) accent else R1.Hairline, R1.ShapeM)
                            .r1Pressable(onClick = { onPick(option) })
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = option,
                            style = R1.body,
                            color = if (selected) accent else R1.Ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private val ISO_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
private val ISO_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)

/** Native Android date picker, seeded from [current] ("YYYY-MM-DD"); returns the
 *  picked date as the same string. */
internal fun showDatePicker(context: Context, current: String, onPicked: (String) -> Unit) {
    val seed = runCatching { LocalDate.parse(current, ISO_DATE) }.getOrDefault(LocalDate.now())
    DatePickerDialog(
        context,
        { _, year, month, day -> onPicked(LocalDate.of(year, month + 1, day).format(ISO_DATE)) },
        seed.year,
        seed.monthValue - 1,
        seed.dayOfMonth,
    ).show()
}

/** Native Android time picker, seeded from [current] ("HH:MM:SS"); returns the
 *  picked time as "HH:MM:SS". */
internal fun showTimePicker(context: Context, current: String, onPicked: (String) -> Unit) {
    val seed = runCatching {
        val parts = current.split(":")
        LocalTime.of(parts[0].toInt(), parts.getOrNull(1)?.toIntOrNull() ?: 0)
    }.getOrDefault(LocalTime.now())
    TimePickerDialog(
        context,
        { _, hour, minute -> onPicked(LocalTime.of(hour, minute).format(ISO_TIME)) },
        seed.hour,
        seed.minute,
        true,
    ).show()
}
