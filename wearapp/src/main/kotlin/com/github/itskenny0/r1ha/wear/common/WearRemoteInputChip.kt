package com.github.itskenny0.r1ha.wear.common

import android.app.Activity
import android.app.RemoteInput
import android.os.Bundle
import android.text.InputType
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.input.RemoteInputIntentHelper

/**
 * A Wear OS chip that opens the watch's native RemoteInput keyboard dialog on tap.
 *
 * Unlike `material3.TextField` / `BasicTextField`, which suffer from the Samsung
 * Galaxy Watch keyboard bug (each character sent as a full replacement via a
 * separate Activity → only the last char survives), RemoteInput is the official
 * Wear OS text-input mechanism. The watch's keyboard (or voice / emoji) opens as
 * a dedicated Activity, and the complete text is returned in one shot via
 * [ActivityResult].
 *
 * @param label       Short field label shown above the value in the chip body.
 * @param value       Current field value; shown as the chip's secondary text.
 * @param placeholder Hint shown when [value] is blank.
 * @param inputKey    Unique key used to extract the text from the RemoteInput result bundle.
 * @param onValueChange Called with the full typed string when the user confirms input.
 * @param maskValue   If true, the display string is replaced with bullet characters.
 * @param inputType   Android `InputType` constant sent as a hint to the keyboard.
 *                    Defaults to plain text; use [InputType.TYPE_TEXT_VARIATION_URI] for
 *                    URL fields or [InputType.TYPE_TEXT_VARIATION_PASSWORD] for passwords.
 */
@Composable
fun WearRemoteInputChip(
    label: String,
    value: String,
    placeholder: String,
    inputKey: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    maskValue: Boolean = false,
    inputType: Int = InputType.TYPE_CLASS_TEXT,
) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val bundle = RemoteInput.getResultsFromIntent(
                result.data ?: return@rememberLauncherForActivityResult
            )
            val text = bundle?.getCharSequence(inputKey)?.toString()
            if (text != null) onValueChange(text)
        }
    }

    Chip(
        onClick = {
            val extras = Bundle().apply {
                putInt("android.view.inputmethod.InputType", inputType)
            }
            val remoteInput = RemoteInput.Builder(inputKey)
                .setLabel(label)
                .addExtras(extras)
                .build()
            val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
            RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(remoteInput))
            intent.putExtra("android.view.inputmethod.InputType", inputType)
            launcher.launch(intent)
        },
        label = {
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                    fontSize = 9.sp,
                )
                val display = when {
                    value.isBlank() -> placeholder
                    maskValue -> "•".repeat(minOf(value.length, 12))
                    else -> value
                }
                Text(
                    text = display,
                    style = MaterialTheme.typography.body2,
                    color = if (value.isBlank())
                        MaterialTheme.colors.onSurface.copy(alpha = 0.4f)
                    else
                        MaterialTheme.colors.onSurface,
                    maxLines = 1,
                )
            }
        },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = modifier,
    )
}
