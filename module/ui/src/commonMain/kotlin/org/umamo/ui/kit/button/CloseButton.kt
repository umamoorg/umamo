package org.umamo.ui.kit.button

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.LocalUmamoShapes

/**
 * A modal-card header close button, the way out on a keyboardless tablet where Escape is unavailable.  A
 * preset of [IconButton] that, sitting in stable dialog chrome, keeps keyboard focus.
 *
 * @param Function onClick            Click callback (closes the window).
 * @param String   contentDescription The accessible label (the face is only a glyph).
 */
@Composable
internal fun CloseButton(onClick: () -> Unit, contentDescription: String) {
	IconButton(
		icon = LocalUmamoIcons.close,
		onClick = onClick,
		contentDescription = contentDescription,
		size = DpSize(20.dp, 20.dp),
		glyphSize = 20.dp,
		suppressFocus = false,
		appearance = IconButtonAppearance.Filled(LocalUmamoShapes.current.small),
	)
}