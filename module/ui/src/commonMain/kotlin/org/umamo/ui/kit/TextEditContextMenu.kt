package org.umamo.ui.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.text_menu_copy
import org.umamo.ui.resources.text_menu_cut
import org.umamo.ui.resources.text_menu_paste
import org.umamo.ui.resources.text_menu_select_all

/*
 * The context menu for the kit's text fields.
 *
 * Compose's own text fields carry a built-in Cut / Copy / Paste / Select All menu, and because that menu is
 * installed on the innermost node it wins the right-click outright - so a Properties row's Insert / Remove
 * Keyframe menu and a panel header's area menu were both unreachable over any field.  Rather than suppress
 * the built-in menu and lose the clipboard entries, this replaces it with ONE menu drawn in the kit's own
 * chrome: whatever the enclosing ContextMenuArea offers, a rule, then the four clipboard actions.
 *
 * The clipboard actions are re-implemented here rather than delegated back to the built-in menu, which is
 * why the fields that use this hold a TextFieldValue rather than a String - Cut, Copy and Select All are all
 * statements about a selection range, and the String-shaped BasicTextField overload does not expose one.
 */

/**
 * Wraps a text field so a context-menu request opens the kit menu instead of Compose's built-in one.
 *
 * [content] receives the gesture modifier rather than having it applied to a wrapper, and must chain it AHEAD
 * of its own: the caller's modifier carries focus requesters and layout that belong to the FIELD, and a
 * wrapper that swallowed them would silently break things like the command palette's auto-focus.  [modifier]
 * is for the few things that must sit on the wrapper instead - a Row weight, which only a direct child of
 * the row can carry.
 *
 * @param TextFieldValue value The field's current text and selection.
 * @param Function onValueChange Applies a clipboard action's result back to the field.
 * @param Modifier modifier Layout for the wrapping box, where the field itself cannot carry it.
 * @param Function content The text field, given the modifier that opens the menu.
 */
@Composable
internal fun TextEditContextMenuArea(
	value: TextFieldValue,
	onValueChange: (TextFieldValue) -> Unit,
	modifier: Modifier = Modifier,
	content: @Composable (Modifier) -> Unit,
) {
	var open by remember { mutableStateOf(false) }
	var anchorOffset by remember { mutableStateOf(IntOffset.Zero) }
	val gesture =
		Modifier.textEditContextMenuGesture { localOffset ->
			anchorOffset = localOffset
			open = true
		}
	Box(modifier = modifier) {
		// The field must NOT inherit the ambient items a second time through some nested area of its own:
		// they are already the head of this menu, and a doubled list reads as a bug rather than as emphasis.
		CompositionLocalProvider(LocalContextMenuItems provides emptyList()) {
			content(gesture)
		}
		if (open) {
			// Built only while the menu is up.  Every kit text field mounts one of these, the entries depend
			// on the CLIPBOARD (a cross-process read on desktop), and LocalContextMenuItems hands down a list
			// rebuilt by its area every recomposition - so building them unconditionally read the system
			// clipboard once per field per frame of any neighbouring scrub.  Reading the ambient items here
			// is still correct: this sits outside the provider that blanks them for `content`.
			val surroundingItems = LocalContextMenuItems.current
			val clipboardItems = textEditMenuItems(value, onValueChange)
			Menu(
				items =
					if (surroundingItems.isEmpty()) {
						clipboardItems
					} else {
						surroundingItems + MenuItem.Separator + clipboardItems
					},
				onDismissRequest = { open = false },
				positionProvider = AtPointPositionProvider(anchorOffset),
				focusable = true,
			)
		}
	}
}

/**
 * The four clipboard entries, each disabled when it would do nothing.
 *
 * Cut and Copy need a non-collapsed selection, Paste needs something on the clipboard, and Select All needs
 * text to select.  Showing them greyed rather than hiding them keeps the menu's shape stable, so the entry a
 * user is reaching for does not move between right-clicks.
 *
 * @param TextFieldValue value The field's current text and selection.
 * @param Function onValueChange Applies the result back to the field.
 * @return List<MenuItem> The clipboard entries.
 */
@Composable
private fun textEditMenuItems(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit): List<MenuItem> {
	@Suppress("DEPRECATION")
	val clipboard = LocalClipboardManager.current
	val hasSelection = !value.selection.collapsed
	// One clipboard read for both the Paste entry's enablement and its action; the caller only builds this
	// while the menu is opening, so the cross-process query happens once per right-click.
	val hasClipboardText = clipboard.hasText()
	return listOf(
		MenuItem.Action(
			label = stringResource(Res.string.text_menu_cut),
			onSelect = {
				clipboard.setText(AnnotatedString(value.selectedText()))
				onValueChange(value.withSelectionReplaced(""))
			},
			enabled = hasSelection,
		),
		MenuItem.Action(
			label = stringResource(Res.string.text_menu_copy),
			onSelect = { clipboard.setText(AnnotatedString(value.selectedText())) },
			enabled = hasSelection,
		),
		MenuItem.Action(
			label = stringResource(Res.string.text_menu_paste),
			onSelect = { onValueChange(value.withSelectionReplaced(clipboard.getText()?.text.orEmpty())) },
			enabled = hasClipboardText,
		),
		MenuItem.Action(
			label = stringResource(Res.string.text_menu_select_all),
			onSelect = { onValueChange(value.copy(selection = TextRange(0, value.text.length))) },
			enabled = value.text.isNotEmpty(),
		),
	)
}

/** The text covered by this value's selection, or "" when the selection is a bare caret. */
internal fun TextFieldValue.selectedText(): String = text.substring(selection.min, selection.max)

/**
 * This value with its selection replaced by [replacement], the caret left after the inserted text.
 *
 * @param String replacement The text to put in the selection's place.
 * @return TextFieldValue The edited value.
 */
internal fun TextFieldValue.withSelectionReplaced(replacement: String): TextFieldValue {
	val edited = text.replaceRange(selection.min, selection.max, replacement)
	val caret = selection.min + replacement.length
	return copy(text = edited, selection = TextRange(caret))
}