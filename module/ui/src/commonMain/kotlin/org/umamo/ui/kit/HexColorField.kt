package org.umamo.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import org.umamo.ui.graphics.formatHexColor
import org.umamo.ui.graphics.parseHexColor
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.LocalUmamoTypography

/**
 * A compact hex color entry: a live swatch beside a text field accepting "#AARRGGBB" (or "#RRGGBB",
 * treated as opaque).  Every keystroke that parses is committed immediately through [onValueChange] in
 * canonical form - the auto-save model the settings window uses, so the swatch and any live consumer (the
 * viewport gizmo) follow the typing with no confirm step.  Text that does not parse stays local to the
 * field (the swatch holds the last good color) and is discarded on focus loss, which re-seeds the field
 * from the persisted [value].  Enter confirms by clearing focus.
 *
 * Like [NumberField], it lends its cancel hook to [LocalInlineEditController] while focused, so the editor
 * shell's root key handler yields keystrokes to the field (hex digits are also bare-digit shortcuts) and
 * routes Escape here (clearing focus) instead.
 *
 * 16進カラー入力欄。スウォッチ付き。解析できた入力は即時保存し、無効な入力はフォーカス喪失で破棄する。
 *
 * @param String   value         The persisted color as canonical "#AARRGGBB" text.
 * @param Function onValueChange Called with the canonical hex of each parseable edit.
 * @param Modifier modifier      Layout modifier (the caller supplies the width).
 */
@Composable
fun HexColorField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
	val colors = LocalUmamoColors.current
	val shapes = LocalUmamoShapes.current
	val controller = LocalInlineEditController.current
	val focusManager = LocalFocusManager.current
	var focused by remember { mutableStateOf(false) }
	var text by remember { mutableStateOf(value) }

	// While not editing, mirror the external value (another control or a re-read may change it); on focus
	// loss this re-seeds the field from the last persisted value, discarding any invalid leftover text.
	LaunchedEffect(value, focused) {
		if (!focused) {
			text = value
		}
	}

	// Drop the lent cancel hook if the field disposes while focused (a section switch mid-edit).
	DisposableEffect(Unit) {
		onDispose {
			if (focused) {
				controller.cancel = null
			}
		}
	}

	Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
		// The live swatch: the color being typed when parseable, else the last persisted one.
		val swatchColor = parseHexColor(text) ?: parseHexColor(value) ?: Color.Transparent
		Box(
			modifier =
				Modifier
					.size(18.dp)
					.clip(shapes.small)
					.background(swatchColor)
					.border(1.dp, colors.controlBorder, shapes.small),
		)
		Spacer(modifier = Modifier.width(6.dp))
		BasicTextField(
			value = text,
			onValueChange = { newText ->
				text = newText
				val parsed = parseHexColor(newText)
				if (parsed != null) {
					onValueChange(formatHexColor(parsed))
				}
			},
			textStyle = LocalUmamoTypography.current.bodySmall.copy(color = colors.text),
			singleLine = true,
			cursorBrush = SolidColor(colors.text),
			modifier =
				Modifier
					.weight(1f)
					.clip(shapes.small)
					.background(colors.controlBackground)
					.border(1.dp, colors.controlBorder, shapes.small)
					.padding(horizontal = 6.dp, vertical = 4.dp)
					.onFocusChanged { focusState ->
						// hasFocus (not isFocused): BasicTextField focuses an internal child, so this node only
						// ever sees its subtree's focus.
						if (focusState.hasFocus) {
							if (!focused) {
								focused = true
								// Escape routes here via the shell: end the edit by clearing focus (the mirror
								// effect above then re-seeds the text from the persisted value).
								controller.cancel = { focusManager.clearFocus() }
							}
						} else if (focused) {
							focused = false
							controller.cancel = null
						}
					}
					.onKeyEvent { event ->
						// Hardware Enter confirms by clearing focus (edits are already persisted live).
						if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
							focusManager.clearFocus()
							true
						} else {
							false
						}
					},
		)
	}
}
