package org.umamo.ui.kit

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
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
 * A filter box: a [TextField] with a clear affordance pinned inside its trailing edge, shown only while
 * there is text to clear.  The X rides the field's trailing slot rather than being overlaid on it, so
 * the value and the caret can never run underneath it.
 *
 * Every panel that filters a list mounts this one component - the outliner and the properties panel both
 * did their own copy of the field-plus-X, which is exactly the drift the kit exists to prevent.
 *
 * @param String   value                   The current query.
 * @param Function onValueChange           Edit callback; the clear affordance reports an empty string.
 * @param Modifier modifier                The layout modifier.
 * @param Dp       width                   The box's width; [SEARCH_FIELD_WIDTH] unless a caller needs otherwise.
 * @param String   placeholder             The dimmed hint shown while the query is empty.
 * @param String   clearContentDescription The clear affordance's accessible label and hover tooltip.
 */
@Composable
fun SearchField(
	value: String,
	onValueChange: (String) -> Unit,
	modifier: Modifier = Modifier,
	width: Dp = SEARCH_FIELD_WIDTH,
	placeholder: String = stringResource(Res.string.search_hint),
	clearContentDescription: String = stringResource(Res.string.search_clear),
) {
	TextField(
		value = value,
		onValueChange = onValueChange,
		modifier = modifier.width(width),
		placeholder = placeholder,
		trailing =
			if (value.isEmpty()) {
				null
			} else {
				{
					IconButton(
						icon = LocalUmamoIcons.close,
						onClick = { onValueChange("") },
						contentDescription = clearContentDescription,
						size = DpSize(16.dp, 16.dp),
						glyphSize = 16.dp,
					)
				}
			},
	)
}