package org.umamo.ui.workspace.rowdrag

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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

/** Places a popup at a fixed window point (the drag cursor), nudged down-right so it clears the pointer. */
private class CursorPopupPositionProvider(private val cursorX: Float, private val cursorY: Float) : PopupPositionProvider {
	override fun calculatePosition(
		anchorBounds: IntRect,
		windowSize: IntSize,
		layoutDirection: LayoutDirection,
		popupContentSize: IntSize,
	): IntOffset = IntOffset((cursorX + 14f).roundToInt(), (cursorY + 8f).roundToInt())
}

/**
 * The little name chip that follows the cursor while a row is being dragged, so there is something
 * obviously "in hand" beyond the faded source row.  Non-focusable and mounted at the space root,
 * positioned in window coordinates at the drag pointer - the one chip every row-dragging space shows.
 *
 * @param String label   The dragged row's display name.
 * @param Float  cursorX The drag pointer X, in window pixels.
 * @param Float  cursorY The drag pointer Y, in window pixels.
 */
@Composable
fun RowDragLabel(label: String, cursorX: Float, cursorY: Float) {
	val colors = LocalUmamoColors.current
	val shapes = LocalUmamoShapes.current
	val typography = LocalUmamoTypography.current
	Popup(
		popupPositionProvider = CursorPopupPositionProvider(cursorX, cursorY),
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