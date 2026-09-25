package org.umamo.ui.kit

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoTypography

/**
 * Text a user can select and copy, drawn like the kit's [Text]: an alert's message, where a command to paste
 * into a terminal or an error to paste into a bug report is exactly what someone wants out of it.
 *
 * A read-only text field rather than Compose's SelectionContainer, because the field's selection is state this
 * composable holds, which is what lets the kit's own text menu ([TextEditContextMenuArea], read-only: Copy and
 * Select All) act on it instead of Compose's built-in one.  The platform copy chord is the field's own; a caller
 * inside the shell's modal key ladder has to let that chord through, as the alert rungs do.  It never takes
 * part in text entry: no caret, and no [LocalInlineEditController] claim, since nothing here is typed into.
 *
 * @param String    text     The text to show.
 * @param Modifier  modifier Layout modifier.
 * @param TextStyle style    The text style; its colour defaults to the theme's text colour, as [Text]'s does.
 */
@Composable
fun SelectableText(
	text: String,
	modifier: Modifier = Modifier,
	style: TextStyle = LocalUmamoTypography.current.bodyMedium,
) {
	var value by remember(text) { mutableStateOf(TextFieldValue(text)) }
	val resolvedStyle = style.copy(color = style.color.takeOrElse { LocalUmamoColors.current.text })
	// The text never changes here; only the selection does, from a drag, a double-click, or Select All.
	val keepSelection: (TextFieldValue) -> Unit = { edited -> value = value.copy(selection = edited.selection) }
	TextEditContextMenuArea(value = value, onValueChange = keepSelection, modifier = modifier, readOnly = true) { gesture ->
		BasicTextField(
			value = value,
			onValueChange = keepSelection,
			readOnly = true,
			textStyle = resolvedStyle,
			cursorBrush = SolidColor(Color.Transparent),
			modifier = gesture,
		)
	}
}