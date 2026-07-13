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
 * A modal-card header close button: a flat "✕" glyph that brightens from muted to full text color on
 * hover, matching the kit's other icon controls.  Shared by the settings window and the Help dialogs;
 * needed for keyboardless tablets where Escape is unavailable.  A thin preset of [IconButton] - it fixes
 * the close glyph at a larger 20dp and, sitting in stable dialog chrome, keeps keyboard focus.
 *
 * モーダルヘッダの閉じるボタン。ホバーで明るくなる「✕」。Esc が使えないタブレット向け。
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
