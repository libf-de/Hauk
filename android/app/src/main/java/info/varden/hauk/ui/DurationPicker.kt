package info.varden.hauk.ui

import android.icu.text.MeasureFormat
import android.icu.text.NumberFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import info.varden.hauk.R
import info.varden.hauk.dialog.HaukDialog
import info.varden.hauk.ui.theme.HaukIcons
import info.varden.hauk.utils.TimeUtils
import java.util.Locale

/**
 * A read-only field that displays the share duration, such as "30 minutes", and opens a
 * [DurationPickerDialog] when tapped.
 *
 * @param duration The duration, in units of [unit].
 * @param unit     The duration unit, as one of the Constants.DURATION_UNIT_* values.
 * @param onChange Called with the new duration and unit when the user confirms the dialog.
 */
@Composable
internal fun DurationField(
    duration: String,
    unit: Int,
    onChange: (duration: String, unit: Int) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var picking by remember { mutableStateOf(false) }
    val label = stringResource(R.string.label_duration).asLabel()
    val locale = LocalConfiguration.current.locales[0]
    val value = duration.toIntOrNull()?.let { formatDuration(it, unit, locale) } ?: duration

    Box(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            label = { Text(label) },
            leadingIcon = { Icon(HaukIcons.Timer, contentDescription = null) },
            trailingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics {},
        )
        // Text fields consume taps to place the cursor, so an overlay handles them instead. The top
        // padding leaves out the space reserved for the label above the outline.
        Box(
            Modifier
                .matchParentSize()
                .padding(top = 8.dp)
                .clip(OutlinedTextFieldDefaults.shape)
                .clickable(enabled = enabled, role = Role.Button) { picking = true }
                .semantics { contentDescription = "$label: $value" },
        )
    }

    if (picking) {
        DurationPickerDialog(
            initialDuration = duration,
            initialUnit = unit,
            onConfirm = { newDuration, newUnit ->
                picking = false
                onChange(newDuration, newUnit)
            },
            onDismiss = { picking = false },
        )
    }
}

/**
 * Dialog for choosing the share duration. The amount can be typed in or adjusted with stepper
 * buttons, and the unit is chosen from a connected button group.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DurationPickerDialog(
    initialDuration: String,
    initialUnit: Int,
    onConfirm: (duration: String, unit: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialDuration) }
    var unit by remember { mutableIntStateOf(initialUnit) }
    val amount = text.toIntOrNull()
    val valid = amount != null && amount > 0 && runCatching { TimeUtils.timeUnitsToSeconds(amount, unit) }.isSuccess
    val locale = LocalConfiguration.current.locales[0]

    HaukDialog(
        title = stringResource(R.string.label_duration).asLabel(),
        icon = HaukIcons.Timer,
        dismissible = true,
        onDismissRequest = onDismiss,
        buttons = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
            TextButton(onClick = { onConfirm(amount.toString(), unit) }, enabled = valid) {
                Text(stringResource(R.string.btn_ok))
            }
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilledTonalIconButton(
                onClick = { text = ((amount ?: MIN_AMOUNT) - 1).coerceAtLeast(MIN_AMOUNT).toString() },
                shapes = IconButtonDefaults.shapes(),
                enabled = amount == null || amount > MIN_AMOUNT,
                modifier = Modifier.size(IconButtonDefaults.mediumContainerSize()),
            ) {
                Icon(HaukIcons.Remove, contentDescription = "−1")
            }
            OutlinedTextField(
                value = text,
                onValueChange = { value -> text = value.filter(Char::isDigit).take(MAX_DIGITS) },
                isError = !valid,
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineMedium.copy(textAlign = TextAlign.Center),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1F),
            )
            FilledTonalIconButton(
                onClick = { text = ((amount ?: 0) + 1).coerceAtMost(MAX_AMOUNT).toString() },
                shapes = IconButtonDefaults.shapes(),
                enabled = amount == null || amount < MAX_AMOUNT,
                modifier = Modifier.size(IconButtonDefaults.mediumContainerSize()),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "+1")
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            DURATION_UNITS.indices.forEach { index ->
                ToggleButton(
                    checked = unit == index,
                    onCheckedChange = { unit = index },
                    shapes = when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        DURATION_UNITS.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1F).semantics { role = Role.RadioButton },
                ) {
                    // Name the unit in the grammatical form that matches the entered amount.
                    Text(unitName(amount ?: 0, index, locale), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * Formats a duration, such as "30 minutes", in the given locale with the correct plural form.
 *
 * @param unit The duration unit, as one of the Constants.DURATION_UNIT_* values.
 */
internal fun formatDuration(amount: Int, unit: Int, locale: Locale): String =
    MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.WIDE).format(Measure(amount, DURATION_UNITS[unit]))

/**
 * Returns the name of a duration unit in the form used after the given amount, such as "minute"
 * for 1 and "minutes" for 30.
 *
 * @param unit The duration unit, as one of the Constants.DURATION_UNIT_* values.
 */
private fun unitName(amount: Int, unit: Int, locale: Locale): String {
    val number = NumberFormat.getInstance(locale).format(amount)
    return formatDuration(amount, unit, locale).replace(number, "").trim()
}

/**
 * ICU measure units, indexed by the Constants.DURATION_UNIT_* values.
 */
private val DURATION_UNITS = arrayOf(MeasureUnit.MINUTE, MeasureUnit.HOUR, MeasureUnit.DAY)

private const val MIN_AMOUNT = 1
private const val MAX_DIGITS = 6
private const val MAX_AMOUNT = 999_999
