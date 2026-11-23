package org.umamo.ui.kit.field

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.kit.button.IconButton
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoIcons

/**
 * The standard width of a header search box - wide enough for a name, narrow enough to leave the rest
 * of the strip room in a tight area.
 */
val SEARCH_FIELD_WIDTH = 160.dp

/**
 * The narrowest a header search box is worth squeezing to: still wide enough to read a few characters and
 * the clear affordance beside them.  Below this it is better off in the overflow dropdown at full width.
 */
val SEARCH_FIELD_MIN_WIDTH = 72.dp

/** The edge length of the icon controls inside a search box: the clear affordance and a caller's trailing action. */
private val SEARCH_FIELD_ICON_SIZE = 16.dp

/**
 * A filter box: a [TextField] with a clear affordance pinned inside its trailing edge, shown only while
 * there is text to clear.  The X rides the field's trailing slot rather than being overlaid on it, so
 * the value and the caret can never run underneath it.  Every panel that filters a list mounts this one
 * component, so the field-plus-X cannot drift between them.
 *
 * A [trailingAction] sits after the X.  It is drawn over the field rather than inside it, with its room reserved in
 * the trailing slot so the text still stops short of it: a control inside the field is a descendant of the text
 * editor, so a key press it takes would bubble on into the editor as typed text, and its focus would read as
 * text entry.
 *
 * @param String   value                   The current query.
 * @param Function onValueChange           Edit callback; the clear affordance reports an empty string.
 * @param Modifier modifier                The layout modifier.
 * @param Dp       width                   The box's width; [SEARCH_FIELD_WIDTH] unless a caller needs otherwise.
 * @param String   placeholder             The dimmed hint shown while the query is empty.
 * @param String   clearContentDescription The clear affordance's accessible label and hover tooltip.
 * @param Function trailingAction          An icon control of the caller's, no larger than the clear affordance,
 *   shown after it whether or not there is text, or null for none.
 */
@Composable
fun SearchField(
	value: String,
	onValueChange: (String) -> Unit,
	modifier: Modifier = Modifier,
	width: Dp = SEARCH_FIELD_WIDTH,
	placeholder: String = stringResource(Res.string.search_hint),
	clearContentDescription: String = stringResource(Res.string.search_clear),
	trailingAction: (@Composable () -> Unit)? = null,
) {
	val showsClear = value.isNotEmpty()
	val clear: @Composable () -> Unit = {
		IconButton(
			icon = LocalUmamoIcons.close,
			onClick = { onValueChange("") },
			contentDescription = clearContentDescription,
			size = DpSize(SEARCH_FIELD_ICON_SIZE, SEARCH_FIELD_ICON_SIZE),
			glyphSize = SEARCH_FIELD_ICON_SIZE,
		)
	}

	if (trailingAction == null) {
		TextField(
			value = value,
			onValueChange = onValueChange,
			modifier = modifier.width(width),
			placeholder = placeholder,
			trailing = if (showsClear) clear else null,
		)

		return
	}

	Box(modifier = modifier.width(width), contentAlignment = Alignment.CenterEnd) {
		TextField(
			value = value,
			onValueChange = onValueChange,
			modifier = Modifier.fillMaxWidth(),
			placeholder = placeholder,
			trailing = {
				Row(verticalAlignment = Alignment.CenterVertically) {
					if (showsClear) {
						clear()
					}

					// The action's room: it is drawn over this gap by the Box, outside the text editor.
					Spacer(modifier = Modifier.size(SEARCH_FIELD_ICON_SIZE))
				}
			},
		)
		Box(modifier = Modifier.padding(end = TEXT_FIELD_HORIZONTAL_PADDING).size(SEARCH_FIELD_ICON_SIZE), contentAlignment = Alignment.Center) {
			trailingAction()
		}
	}
}