package org.umamo.ui.kit.field

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import org.umamo.ui.kit.Text
import org.umamo.ui.kit.menu.TextEditContextMenuArea
import org.umamo.ui.kit.textentry.LocalInlineEditController
import org.umamo.ui.kit.textentry.textEntrySurface
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.LocalUmamoTypography

/**
 * A bordered text field for one paragraph of free text that commits once, when the edit ends: Enter or a
 * click away commits, and Escape discards.  A long text wraps onto more lines, and a line break typed or
 * pasted arrives as a space.  Like [NumberField], it lends its cancel hook to [LocalInlineEditController]
 * while focused, so the editor shell routes Escape here and yields every other key to the field.
 *
 * @param String value The committed text, which the field shows while it is not being edited.
 * @param Function onCommit Called with the edited text when an edit ends with a change.
 * @param Modifier modifier Layout modifier (the caller supplies the width).
 * @param String? placeholder Optional dimmed hint shown while the field is empty.
 */
@Composable
fun CommitTextField(
	value: String,
	onCommit: (String) -> Unit,
	modifier: Modifier = Modifier,
	placeholder: String? = null,
) {
	val colors = LocalUmamoColors.current
	val shapes = LocalUmamoShapes.current
	val controller = LocalInlineEditController.current
	val focusManager = LocalFocusManager.current
	val currentValue by rememberUpdatedState(value)
	val currentOnCommit by rememberUpdatedState(onCommit)
	var focused by remember { mutableStateOf(false) }
	// A TextFieldValue rather than a String so the context menu can act on a selection.
	var draft by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
	// True between an Escape (which clears focus to discard) and the resulting focus loss, so that path
	// discards instead of committing.
	var discarding by remember { mutableStateOf(false) }

	/** Hands the draft to the owner when it differs from the committed text. */
	fun commitDraft() {
		if (draft.text != currentValue) {
			currentOnCommit(draft.text)
		}
	}

	// A field disposed while it holds focus (its host closed under it) may get no focus-loss callback, so
	// the hook it parked would keep the shell yielding the keyboard to a field that no longer exists.  The
	// draft is the rigger's own writing, so it is committed rather than lost.
	DisposableEffect(Unit) {
		onDispose {
			if (focused) {
				focused = false
				controller.cancel = null

				if (!discarding) {
					commitDraft()
				}
			}
		}
	}
	// While not editing, mirror the committed text, which an undo or the owner's own normalizing changes.
	LaunchedEffect(value, focused) {
		if (!focused) {
			draft = TextFieldValue(value, TextRange(value.length))
		}
	}
	val applyEdit: (TextFieldValue) -> Unit = { edited ->
		draft = edited.withLineBreaksAsSpaces()

		// An edit that arrives while the field is not being typed in, the context menu's Paste say, has no Enter or
		// click-away to follow it, so it commits at once.
		if (!focused) {
			commitDraft()
		}
	}
	TextEditContextMenuArea(value = draft, onValueChange = applyEdit) { gesture ->
		BasicTextField(
			value = draft,
			onValueChange = applyEdit,
			textStyle = LocalUmamoTypography.current.bodySmall.copy(color = colors.text),
			cursorBrush = SolidColor(colors.text),
			keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
			// Soft-keyboard Done: clear focus so the single commit happens on the focus-loss path below.
			keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
			modifier =
				gesture
					.then(modifier)
					.textEntrySurface(controller)
					.clip(shapes.small)
					.background(colors.controlBackground)
					.border(1.dp, colors.controlBorder, shapes.small)
					.padding(horizontal = TEXT_FIELD_HORIZONTAL_PADDING, vertical = 4.dp)
					.onFocusChanged { focusState ->
						// hasFocus (not isFocused): BasicTextField focuses an internal child, so this node only ever
						// sees its subtree's focus.
						if (focusState.hasFocus) {
							if (!focused) {
								focused = true
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
								commitDraft()
							}
						}
					}
					// Preview, not onKeyEvent: the field wraps, so the text field's own handler would take Enter as a
					// line break before a bubbling handler saw it.
					.onPreviewKeyEvent { event ->
						if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
							focusManager.clearFocus()
							true
						} else if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
							// In the main window the shell's key ladder previews Escape first and routes it to the
							// cancel hook, so this runs only inside a popup, whose own dismiss would otherwise take
							// the key and let the closing popup's focus loss commit the draft.
							discarding = true
							focusManager.clearFocus()
							true
						} else {
							false
						}
					},
			decorationBox = { innerTextField ->
				Box {
					// Behind the field's own text, so it disappears as soon as the rigger types.
					if (placeholder != null && draft.text.isEmpty()) {
						Text(text = placeholder, style = LocalUmamoTypography.current.bodySmall, color = colors.textMuted)
					}

					innerTextField()
				}
			},
		)
	}
}

/**
 * This value with each line break (CRLF, CR, or LF) replaced by one space, its selection and composition
 * carried to the same places in the new text.
 *
 * @return TextFieldValue The value as one paragraph, or this same value when it holds no line break.
 */
internal fun TextFieldValue.withLineBreaksAsSpaces(): TextFieldValue {
	if ('\n' !in text && '\r' !in text) {
		return this
	}

	val joined = StringBuilder(text.length)
	// Where each offset of the old text lands in the new one, the end included.
	val movedOffsets = IntArray(text.length + 1)
	var index = 0

	while (index < text.length) {
		movedOffsets[index] = joined.length
		val character = text[index]

		if (character == '\r' && text.getOrNull(index + 1) == '\n') {
			joined.append(' ')
			movedOffsets[index + 1] = joined.length
			index += 2
		} else if (character == '\r' || character == '\n') {
			joined.append(' ')
			index += 1
		} else {
			joined.append(character)
			index += 1
		}
	}

	movedOffsets[text.length] = joined.length

	return TextFieldValue(
		text = joined.toString(),
		selection = TextRange(movedOffsets[selection.start], movedOffsets[selection.end]),
		composition = composition?.let { range -> TextRange(movedOffsets[range.start], movedOffsets[range.end]) },
	)
}