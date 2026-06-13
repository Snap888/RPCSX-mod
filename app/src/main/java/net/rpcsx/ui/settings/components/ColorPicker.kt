package net.rpcsx.ui.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import net.rpcsx.ui.settings.components.core.PreferenceSubtitle
import net.rpcsx.ui.settings.components.core.PreferenceTitle
import java.util.Locale

private val PRESETS = listOf(
    0xFF000000.toInt(), // black
    0xFFFFFFFF.toInt(), // white
    0xFFA59DC4.toInt(), // clanker purple
    0xFF1E1F25.toInt(), // dark grey
    0xFFB6C4FF.toInt(), // blue
    0xFF7ED4A6.toInt(), // green
    0xFFE3BADA.toInt(), // pink
    0xFFFFB4AB.toInt(), // coral
    0xFFFFD479.toInt(), // amber
)

private fun Int.toHex(): String =
    String.format(Locale.US, "#%06X", this and 0x00FFFFFF)

private fun parseHex(s: String): Int? {
    val h = s.trim().removePrefix("#")
    if (h.length != 6) return null
    return h.toLongOrNull(16)?.toInt()?.let { 0xFF000000.toInt() or it }
}

/**
 * Color picker dialog: live preview, hex field, R/G/B sliders and preset swatches.
 * Always returns a fully-opaque ARGB color. If [allowOff] is set, an extra button
 * returns 0 (meaning "use the default / no override").
 */
@Composable
fun ColorPickerDialog(
    initial: Int,
    title: String,
    allowOff: Boolean = false,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    val start = if (initial == 0) 0xFFA59DC4.toInt() else initial
    var r by remember { mutableIntStateOf((start shr 16) and 0xFF) }
    var g by remember { mutableIntStateOf((start shr 8) and 0xFF) }
    var b by remember { mutableIntStateOf(start and 0xFF) }
    val current = (0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
    var hexText by remember { mutableStateOf(start.toHex()) }

    fun syncHexFromRgb() { hexText = current.toHex() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Live preview
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(current))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                ) {}

                OutlinedTextField(
                    value = hexText,
                    onValueChange = {
                        hexText = it
                        parseHex(it)?.let { c ->
                            r = (c shr 16) and 0xFF; g = (c shr 8) and 0xFF; b = c and 0xFF
                        }
                    },
                    singleLine = true,
                    label = { Text("Hex (#RRGGBB)") },
                    modifier = Modifier.fillMaxWidth(),
                )

                ColorSlider("R", r) { r = it; syncHexFromRgb() }
                ColorSlider("G", g) { g = it; syncHexFromRgb() }
                ColorSlider("B", b) { b = it; syncHexFromRgb() }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PRESETS.forEach { preset ->
                        Row(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(preset))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                .clickable {
                                    r = (preset shr 16) and 0xFF
                                    g = (preset shr 8) and 0xFF
                                    b = preset and 0xFF
                                    syncHexFromRgb()
                                }
                        ) {}
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(current) }) { Text("Apply") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (allowOff) {
                    TextButton(onClick = { onPick(0) }) { Text("Default") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun ColorSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.size(width = 18.dp, height = 24.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = 0f..255f,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * A settings row showing the current colour as a swatch; tapping opens the picker.
 * Pass [allowOff] when 0 is a valid "use default" value (e.g. the app accent).
 */
@Composable
fun ColorPreference(
    title: String,
    subtitle: String?,
    color: Int,
    allowOff: Boolean = false,
    enabled: Boolean = true,
    onColor: (Int) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { showDialog = true }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            PreferenceTitle(title = title)
            subtitle?.let { PreferenceSubtitle(text = it) }
        }
        Row(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(if (color == 0) MaterialTheme.colorScheme.surfaceVariant else Color(color))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
        ) {}
    }

    if (showDialog) {
        ColorPickerDialog(
            initial = color,
            title = title,
            allowOff = allowOff,
            onDismiss = { showDialog = false },
            onPick = { picked -> onColor(picked); showDialog = false },
        )
    }
}
