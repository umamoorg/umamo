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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.LocalUmamoTypography
import kotlin.math.round

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

/**
 * A compact numeric entry field for a scalar [value], clamped to [range].  Shows the live value as
 * two-decimal text and lets the user type an exact number; while not being edited it tracks the external
 * value (so a slider or pad drag, or a reset, updates the displayed number).  Edits commit on Enter or
 * when focus leaves: the text is parsed and clamped, or - if unparseable - discarded back to the live
 * value.  Escape discards the in-progress edit.
 *
 * 数値入力欄。値を 2 桁表示し、Enter／フォーカス喪失で確定（解析・クランプ）、Escape で取消。
 *
 * @param Float    value         The current value, shown when not being edited.
 * @param Function onValueChange Called with the committed value (already clamped to [range]).
 * @param ClosedFloatingPointRange range The min..max the committed value is clamped to.
 * @param Modifier modifier      Layout modifier (the caller supplies the width).
 */
@Composable
fun NumberField(
	value: Float,
	onValueChange: (Float) -> Unit,
	range: ClosedFloatingPointRange<Float>,
	modifier: Modifier = Modifier,
) {
	NumberEntryField(display = formatNumber(value), modifier = modifier) { typed ->
		typed.trim().toFloatOrNull()?.let { parsed -> onValueChange(parsed.coerceIn(range.start, range.endInclusive)) }
	}
}

/**
 * Integer variant of [NumberField]: shows [value] as a plain whole number (no decimals) and commits the
 * parsed, clamped integer.  A typed decimal or non-number is rejected, reseeding the field to [value] on
 * focus loss - the same discard-to-live behavior as the scalar field.
 *
 * 整数版の数値入力欄。小数なしで表示し、確定時に整数として解析・クランプする。小数や非数は取消。
 *
 * @param Int      value         The current value, shown when not being edited.
 * @param Function onValueChange Called with the committed value (already clamped to [range]).
 * @param IntRange range         The min..max the committed value is clamped to.
 * @param Modifier modifier      Layout modifier (the caller supplies the width).
 */
@Composable
fun NumberField(
	value: Int,
	onValueChange: (Int) -> Unit,
	range: IntRange,
	modifier: Modifier = Modifier,
) {
	NumberEntryField(display = value.toString(), modifier = modifier) { typed ->
		typed.trim().toIntOrNull()?.let { parsed -> onValueChange(parsed.coerceIn(range.first, range.last)) }
	}
}

/**
 * The shared editing chrome behind the [NumberField] overloads: a single-line [BasicTextField] that shows
 * [display] (the formatted external value) while idle and lets the user type a replacement.  On Enter or
 * focus loss it hands the raw typed text to [commit], which parses / clamps / fires the caller's callback
 * (an unparseable entry simply does nothing, and the focus-loss reseed restores [display]).  Escape
 * discards the in-progress edit.
 *
 * Like the inline tab-rename editor, it lends its cancel hook to [LocalInlineEditController] only while
 * focused, because the editor shell's root key handler previews keystrokes (Escape, and shortcut keys
 * such as Space / Digit0 / Minus) before this field would see them; while the hook is set the shell
 * routes Escape here and lets every other key fall through to the field instead of firing a shortcut.
 *
 * 数値入力欄の共有チャンネル。編集中のみシェルへ cancel フックを預け、ショートカットにキー入力を奪われないようにする。
 *
 * @param String   display  The formatted external value, shown when not being edited.
 * @param Modifier modifier Layout modifier (the caller supplies the width).
 * @param Function commit   Parses / clamps / fires the caller callback with the raw typed text.
 */
@Composable
private fun NumberEntryField(
	display: String,
	modifier: Modifier,
	commit: (String) -> Unit,
) {
	val colors = LocalUmamoColors.current
	val shapes = LocalUmamoShapes.current
	val controller = LocalInlineEditController.current
	val focusManager = LocalFocusManager.current
	var focused by remember { mutableStateOf(false) }
	var text by remember { mutableStateOf(display) }
	// True between an Escape (which clears focus to discard) and the resulting focus-loss, so that path
	// discards instead of committing.  Plain commit paths (Enter, click-away) leave it false.
	var discarding by remember { mutableStateOf(false) }

	// While not editing, mirror the external value: a slider/pad drag or a reset should update the number.
	// On focus loss this re-seeds the field to the committed (clamped) or, when discarding, the unchanged value.
	LaunchedEffect(display, focused) {
		if (!focused) {
			text = display
		}
	}

	BasicTextField(
		value = text,
		onValueChange = { newText -> text = newText },
		textStyle = LocalUmamoTypography.current.bodySmall.copy(color = colors.text, textAlign = TextAlign.End),
		singleLine = true,
		cursorBrush = SolidColor(colors.text),
		keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
		// Soft-keyboard Done: clear focus so the single commit happens on the focus-loss path below.
		keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
		modifier =
			modifier
				.clip(shapes.small)
				.background(colors.controlBackground)
				.border(1.dp, colors.controlBorder, shapes.small)
				.padding(horizontal = 6.dp, vertical = 4.dp)
				.onFocusChanged { focusState ->
					// hasFocus (not isFocused): BasicTextField focuses an internal child, so this node only ever
					// sees its subtree's focus.
					if (focusState.hasFocus) {
						if (!focused) {
							focused = true
							// Escape routes here via the shell: discard the edit by clearing focus (the focus-loss
							// path below sees [discarding] and skips the commit).
							controller.cancel = {
								discarding = true
								focusManager.clearFocus()
							}
						}
					} else if (focused) {
						focused = false
						controller.cancel = null
						if (discarding) {
							discarding = false
						} else {
							commit(text)
						}
					}
				}
				.onKeyEvent { event ->
					// Hardware Enter confirms by clearing focus, so the commit runs once on the focus-loss path.
					if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
						focusManager.clearFocus()
						true
					} else {
						false
					}
				},
	)
}

/**
 * Formats a scalar to two decimals for display in a [NumberField] (kept dependency-free, matching the
 * Parameters panel's readout).
 *
 * @param Float value The value to format.
 * @return String The two-decimal text.
 */
private fun formatNumber(value: Float): String {
	val scaled = round(value * 100f) / 100f
	return scaled.toString()
}
