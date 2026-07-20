package org.umamo.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.LocalUmamoTypography

/**
 * A compact checkbox row: a small box (accent-filled with a checkmark when on, neutral when off) plus a
 * label, the whole row toggling [onCheckedChange].
 *
 * @param Boolean  checked         Current state.
 * @param Function onCheckedChange Toggle callback.
 * @param String   label           The row label.
 */
@Composable
fun Checkbox(checked: Boolean, onCheckedChange: (Boolean) -> Unit, label: String) {
	val colors = LocalUmamoColors.current
	val interaction = remember { MutableInteractionSource() }
	val boxFill = if (checked) colors.accent else colors.controlBackground
	val boxBorder = colors.controlBorder
	val checkColor = colors.accentText
	Row(
		modifier =
			Modifier
				.fillMaxWidth()
				.height(22.dp)
				.clickable(interactionSource = interaction, indication = null, onClick = { onCheckedChange(!checked) })
				.padding(horizontal = 4.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Canvas(modifier = Modifier.size(13.dp)) {
			val radius = CornerRadius(2.dp.toPx())
			drawRoundRect(color = boxFill, cornerRadius = radius)
			drawRoundRect(color = boxBorder, cornerRadius = radius, style = Stroke(1.dp.toPx()))
			if (checked) {
				val tick = Path()
				tick.moveTo(size.width * 0.22f, size.height * 0.52f)
				tick.lineTo(size.width * 0.42f, size.height * 0.72f)
				tick.lineTo(size.width * 0.78f, size.height * 0.28f)
				drawPath(tick, color = checkColor, style = Stroke(1.6.dp.toPx()))
			}
		}
		Spacer(modifier = Modifier.width(6.dp))
		Text(text = label, style = LocalUmamoTypography.current.labelMedium)
	}
}

/**
 * A compact bordered text field wrapping Foundation's [BasicTextField] (which already handles cursor,
 * selection, and IME). Single line, tight padding.  When [placeholder] is set it shows as dimmed hint
 * text while the field is empty.
 *
 * Like [NumberField], it lends a cancel hook to [LocalInlineEditController] while focused so the editor
 * shell's root key handler stops claiming keystrokes the field needs - notably Space (which otherwise
 * opens the command palette) and letter shortcuts - and routes Escape here (clearing focus) instead.
 *
 * @param String   value         The current text.
 * @param Function onValueChange Edit callback.
 * @param Modifier modifier      Layout modifier.
 * @param String?  placeholder   Optional dimmed hint shown while the field is empty.
 */
@Composable
fun TextField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String? = null) {
	val colors = LocalUmamoColors.current
	val shapes = LocalUmamoShapes.current
	val controller = LocalInlineEditController.current
	val focusManager = LocalFocusManager.current
	var focused by remember { mutableStateOf(false) }
	// Park the cancel hook only while focused; the shell checks it to yield Space / letters to the field
	// and to route Escape (clear focus) here.  Drop it on focus loss and on dispose-while-focused.
	DisposableEffect(Unit) {
		onDispose {
			if (focused) {
				controller.cancel = null
			}
		}
	}
	BasicTextField(
		value = value,
		onValueChange = onValueChange,
		textStyle = LocalUmamoTypography.current.bodySmall.copy(color = colors.text),
		singleLine = true,
		cursorBrush = SolidColor(colors.text),
		modifier =
			modifier
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
							controller.cancel = { focusManager.clearFocus() }
						}
					} else if (focused) {
						focused = false
						controller.cancel = null
					}
				},
		decorationBox = { innerTextField ->
			// Overlay the placeholder behind the field's own text so it disappears as soon as the user types.
			Box {
				if (placeholder != null && value.isEmpty()) {
					Text(text = placeholder, style = LocalUmamoTypography.current.bodySmall, color = colors.textMuted)
				}
				innerTextField()
			}
		},
	)
}
