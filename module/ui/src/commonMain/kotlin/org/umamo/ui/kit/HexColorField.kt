package org.umamo.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import org.umamo.ui.graphics.formatHexColor
import org.umamo.ui.graphics.parseHexColor
import org.umamo.ui.model.KeyedFieldState
import org.umamo.ui.model.backgroundTint
import org.umamo.ui.model.tint
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.LocalUmamoTypography
import kotlin.math.roundToInt

/**
 * A compact hex color entry: a live swatch beside a text field accepting "#AARRGGBB" (or "#RRGGBB",
 * treated as opaque).  Every keystroke that parses is committed immediately through [onValueChange] in
 * canonical form - the auto-save model the settings window uses, so the swatch and any live consumer (the
 * viewport gizmo) follow the typing with no confirm step.  Text that does not parse stays local to the
 * field (the swatch holds the last good color) and is discarded on focus loss, which re-seeds the field
 * from the persisted [value].  Enter confirms by clearing focus.
 *
 * Clicking the swatch opens an RGB-slider popover for graphical picking; a slider drag commits once at its
 * end (one write per gesture, not per frame), while the text field stays the path for exact entry.
 *
 * Like [NumberField], it lends its cancel hook to [LocalInlineEditController] while focused, so the editor
 * shell's root key handler yields keystrokes to the field (hex digits are also bare-digit shortcuts) and
 * routes Escape here (clearing focus) instead.
 *
 * @param String   value         The persisted color as canonical "#AARRGGBB" text.
 * @param Function onValueChange Called with the canonical hex of each parseable edit.
 * @param Modifier modifier      Layout modifier (the caller supplies the width).
 */
@Composable
fun HexColorField(
	value: String,
	onValueChange: (String) -> Unit,
	modifier: Modifier = Modifier,
	keyState: KeyedFieldState = KeyedFieldState.None,
) {
	val colors = LocalUmamoColors.current
	// The SWATCH keeps the outline form of the keyed tint, because its background IS the user's own color and
	// has nothing to spare; the hex field beside it takes the tint as a fill, like every other text control.
	val swatchBorderColor = keyState.tint(colors) ?: colors.controlBorder
	val keyedFill = keyState.backgroundTint(colors) ?: Color.Transparent
	val shapes = LocalUmamoShapes.current
	val controller = LocalInlineEditController.current
	val focusManager = LocalFocusManager.current
	var focused by remember { mutableStateOf(false) }
	// A TextFieldValue rather than a String so the context menu can act on a selection.
	var text by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
	var pickerOpen by remember { mutableStateOf(false) }

	// Mirror the external value - and do it EVEN WHILE FOCUSED, because this field is not the only writer
	// of what it shows.  The swatch's own picker, a parameter scrub moving a keyed channel, and undo all
	// change the value underneath it; skipping the mirror while focused pinned the field to whatever it
	// last held, and since nothing takes focus back off it, a keyed color stayed frozen on its first key
	// forever while the viewport went on blending.
	//
	// The compare is on the PARSED color, not the text, and that is what keeps this from fighting the
	// typist: the user's own keystroke round-trips out through onValueChange and comes back equal, so it
	// re-seeds nothing.  Half-typed text that does not parse is left alone for the same reason.
	LaunchedEffect(value, focused) {
		val typed = parseHexColor(text.text)
		if (!focused || (typed != null && typed != parseHexColor(value))) {
			text = TextFieldValue(value, TextRange(value.length))
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
		// The live swatch: the color being typed when parseable, else the last persisted one.  Clicking it
		// opens the slider popover, anchored to this swatch (the Box wrapper is the popup's anchor bounds).
		val swatchColor = parseHexColor(text.text) ?: parseHexColor(value) ?: Color.Transparent
		Box {
			Box(
				modifier =
					Modifier
						.size(18.dp)
						.clip(shapes.small)
						.background(swatchColor)
						.border(1.dp, swatchBorderColor, shapes.small)
						// Opens only.  It must NOT toggle: an outside-click dismiss fires first and closes the
						// popover, and this click would then immediately reopen it, so the swatch could never
						// be used to close.  Closing is the popup's own job (click-away or Escape).
						.clickable { pickerOpen = true },
			)
			if (pickerOpen) {
				Popup(
					popupPositionProvider = BelowAnchorPositionProvider,
					onDismissRequest = { pickerOpen = false },
					// NOT focusable: a focusable popup pulls focus off the shell root, and nothing restores it
					// on dismiss - every keyboard shortcut stays dead until Tab traversal reclaims it (the
					// hazard IconButton.suppressFocus exists to avoid).  The sliders are pointer-driven, so the
					// popover needs no focus of its own; dismissal still works via the outside-click handler.
					properties = PopupProperties(focusable = false),
				) {
					// The popover edits the persisted color; a slider commit round-trips through onValueChange
					// (canonical hex), which re-seeds the swatch and the text field.
					ColorSliderPopover(
						color = parseHexColor(value) ?: Color.Black,
						onColorChange = { picked -> onValueChange(formatHexColor(picked)) },
					)
				}
			}
		}
		Spacer(modifier = Modifier.width(6.dp))
		TextEditContextMenuArea(
			value = text,
			onValueChange = { edited -> commitHexEdit(edited, text, onValueChange) { updated -> text = updated } },
			modifier = Modifier.weight(1f),
		) { gesture ->
			BasicTextField(
				value = text,
				onValueChange = { edited -> commitHexEdit(edited, text, onValueChange) { updated -> text = updated } },
				textStyle = LocalUmamoTypography.current.bodySmall.copy(color = colors.text),
				singleLine = true,
				cursorBrush = SolidColor(colors.text),
				modifier =
					gesture
						.fillMaxWidth()
						.clip(shapes.small)
						.background(colors.controlBackground)
						.background(keyedFill, shapes.small)
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
}

/**
 * Applies one hex-field edit: shows it, and persists it when the TEXT changed and parses.
 *
 * Shared by the field and its context menu so a pasted color takes effect exactly like a typed one - the
 * menu writing only the local text would have shown a color the document never received.
 *
 * Selection-only changes are shown and not persisted.  BasicTextField's TextFieldValue overload reports a
 * caret or selection move through the same callback as an edit, so persisting unconditionally re-committed
 * the stored value for merely clicking into the field, dragging a selection across it, or picking Select
 * All - and on a keyed channel that is an undo step plus an uncommitted-edit tint for a value that never
 * changed.
 *
 * @param TextFieldValue edited The value the field or menu produced.
 * @param TextFieldValue previous The value it replaces, for telling an edit from a selection move.
 * @param Function onValueChange The caller's persist callback, given canonical hex.
 * @param Function show Writes the edited value back to the field's own state.
 */
private fun commitHexEdit(
	edited: TextFieldValue,
	previous: TextFieldValue,
	onValueChange: (String) -> Unit,
	show: (TextFieldValue) -> Unit,
) {
	show(edited)
	if (edited.text == previous.text) {
		return
	}
	parseHexColor(edited.text)?.let { parsed -> onValueChange(formatHexColor(parsed)) }
}

/**
 * The RGB-slider popover a [HexColorField] swatch opens: a preview strip over three channel sliders.  The
 * sliders drive a local draft so the preview follows every frame, but the outer [onColorChange] fires only
 * when a slider gesture ends - so a whole drag is one commit (and one undo step), not one per frame.  The
 * draft re-seeds whenever the committed [color] changes externally (e.g. the text field is typed while the
 * popover is open).  A slider edits only its channel, so the color's alpha (if any) is preserved.
 *
 * @param Color color The current committed color the sliders start from.
 * @param Function onColorChange Commits a picked color (fired at each slider gesture's end).
 */
@Composable
private fun ColorSliderPopover(color: Color, onColorChange: (Color) -> Unit) {
	val colors = LocalUmamoColors.current
	val shapes = LocalUmamoShapes.current
	var draft by remember { mutableStateOf(color) }
	LaunchedEffect(color) {
		draft = color
	}
	Surface(color = colors.menuBackground, shape = shapes.medium) {
		Column(
			modifier = Modifier.width(184.dp).padding(8.dp),
			verticalArrangement = Arrangement.spacedBy(6.dp),
		) {
			Box(
				modifier =
					Modifier
						.fillMaxWidth()
						.height(22.dp)
						.clip(shapes.small)
						.background(draft)
						.border(1.dp, colors.controlBorder, shapes.small),
			)
			ColorChannelSlider("R", draft.red, { channel -> draft = draft.copy(red = channel) }, { onColorChange(draft) })
			ColorChannelSlider("G", draft.green, { channel -> draft = draft.copy(green = channel) }, { onColorChange(draft) })
			ColorChannelSlider("B", draft.blue, { channel -> draft = draft.copy(blue = channel) }, { onColorChange(draft) })
		}
	}
}

/**
 * One 0..255 channel row inside [ColorSliderPopover]: a letter label, the slider, and a numeric readout.
 * The slider streams its channel value as a 0..1 fraction to [onChannel] for the live preview and calls
 * [onCommit] once when the gesture ends.
 *
 * @param String label The channel letter ("R" / "G" / "B").
 * @param Float channel The channel's current 0..1 value.
 * @param Function onChannel Called with the new 0..1 channel value on each slider frame.
 * @param Function onCommit Called once when the slider gesture ends.
 */
@Composable
private fun ColorChannelSlider(label: String, channel: Float, onChannel: (Float) -> Unit, onCommit: () -> Unit) {
	val colors = LocalUmamoColors.current
	val typography = LocalUmamoTypography.current
	Row(verticalAlignment = Alignment.CenterVertically) {
		Text(text = label, style = typography.labelSmall, color = colors.textMuted, modifier = Modifier.width(12.dp))
		Slider(
			value = channel * 255f,
			onValueChange = { raw -> onChannel((raw / 255f).coerceIn(0f, 1f)) },
			modifier = Modifier.weight(1f),
			onValueChangeFinished = onCommit,
			valueRange = 0f..255f,
		)
		Spacer(modifier = Modifier.width(6.dp))
		Text(
			text = (channel * 255f).roundToInt().toString(),
			style = typography.labelSmall,
			color = colors.text,
			textAlign = TextAlign.End,
			modifier = Modifier.width(28.dp),
		)
	}
}
