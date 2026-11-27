package org.umamo.ui.workspace.rowdrag

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import org.umamo.ui.kit.Surface
import org.umamo.ui.kit.Text
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.LocalUmamoTypography
import kotlin.math.roundToInt

/**
 * Places a popup at the drag pointer, nudged down-right so it clears the pointer.
 *
 * The pointer is read while the popup is laid out, so the chip is laid out again as the pointer moves,
 * with nothing recomposed.
 *
 * @property Function pointerInWindow The drag pointer, in window pixels.
 */
private class CursorPopupPositionProvider(private val pointerInWindow: () -> Offset) : PopupPositionProvider {
	/**
	 * Computes the chip's top-left in window coordinates.
	 *
	 * @param IntRect anchorBounds The Popup's anchor layout bounds (unused - the pointer is the anchor).
	 * @param IntSize windowSize The host window size (unused - the chip may run off the window's edge).
	 * @param LayoutDirection layoutDirection The layout direction (unused; the nudge is the pointer's).
	 * @param IntSize popupContentSize The measured chip size (unused).
	 * @return IntOffset The chip's top-left.
	 */
	override fun calculatePosition(
		anchorBounds: IntRect,
		windowSize: IntSize,
		layoutDirection: LayoutDirection,
		popupContentSize: IntSize,
	): IntOffset {
		val pointer = pointerInWindow()

		return IntOffset((pointer.x + 14f).roundToInt(), (pointer.y + 8f).roundToInt())
	}
}

/**
 * The little name chip that follows the cursor while a row is being dragged, so there is something
 * obviously "in hand" beyond the faded source row.  Non-focusable and mounted at the space root,
 * positioned in window coordinates at the drag pointer - the one chip every row-dragging space shows.
 *
 * The pointer is handed over as something to ask, not as a value: the space that mounts the chip then
 * reads which row is in hand and nothing that moves, and only the chip follows the pointer.
 *
 * @param String   label           The dragged row's display name.
 * @param Function pointerInWindow The drag pointer, in window pixels; asked while the chip is laid out.
 */
@Composable
fun RowDragLabel(label: String, pointerInWindow: () -> Offset) {
	val colors = LocalUmamoColors.current
	val shapes = LocalUmamoShapes.current
	val typography = LocalUmamoTypography.current
	val positionProvider = remember(pointerInWindow) { CursorPopupPositionProvider(pointerInWindow) }
	Popup(
		popupPositionProvider = positionProvider,
		properties = PopupProperties(focusable = false, clippingEnabled = false),
	) {
		Surface(
			color = colors.menuBackground,
			shape = shapes.small,
			border = BorderStroke(1.dp, colors.panelBorder),
			shadowElevation = 6.dp,
		) {
			Text(
				text = label,
				style = typography.labelSmall,
				color = colors.text,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.widthIn(max = 220.dp).padding(horizontal = 8.dp, vertical = 3.dp),
			)
		}
	}
}