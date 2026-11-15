package org.umamo.ui.kit

import androidx.compose.foundation.Indication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * What the last press on a popup's anchor landed on.  A plain holder rather than state: only the anchor's
 * own click reads it, and nothing composes from it.
 */
private class AnchorPress {
	/** Whether the press landed while the anchor's popup was showing. */
	var landedOnOpenPopup = false
}

/**
 * The click on a popup's anchor (a chip face, a menu-bar label, a swatch), used in place of a plain
 * clickable so the anchor toggles its popup.  A press on the anchor of an open popup closes it, and only
 * a press on the anchor of a closed one runs [onClick].
 *
 * @param Boolean expanded Whether the anchor's popup is showing.
 * @param MutableInteractionSource? interactionSource The hover and press source the anchor's face reads, or
 *   null for none.
 * @param Indication? indication The press and hover indication, or null for none.
 * @param Boolean enabled When false, clicks are inert.
 * @param Function onClick Called on a click whose press landed while the popup was closed.
 * @return Modifier The modifier with the anchor's click attached.
 * @pre The popup dismisses itself on a press outside its bounds, as a Compose Popup does by default.
 */
@Composable
fun Modifier.popupAnchorClickable(
	expanded: Boolean,
	interactionSource: MutableInteractionSource?,
	indication: Indication?,
	enabled: Boolean = true,
	onClick: () -> Unit,
): Modifier {
	// The open state as last composed, which is what the press landed on.
	val shownExpanded by rememberUpdatedState(expanded)
	val press = remember { AnchorPress() }

	return this
		.pointerInput(Unit) {
			awaitEachGesture {
				awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
				press.landedOnOpenPopup = shownExpanded
			}
		}
		.clickable(interactionSource = interactionSource, indication = indication, enabled = enabled) {
			// Cleared on use, so a click that arrives with no press of its own (an accessibility action)
			// is never read against a press long since handled.
			if (press.landedOnOpenPopup) {
				press.landedOnOpenPopup = false
			} else {
				onClick()
			}
		}
}