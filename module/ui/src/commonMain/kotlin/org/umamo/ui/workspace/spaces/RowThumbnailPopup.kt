package org.umamo.ui.workspace.spaces

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import org.umamo.ui.kit.POINTER_ARROW_CLEARANCE
import org.umamo.ui.kit.Surface
import org.umamo.ui.kit.Text
import org.umamo.ui.kit.ThumbnailSlot
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.LocalUmamoTypography
import kotlin.math.max
import kotlin.math.roundToInt

/** The square edge of a row's hover preview - bigger than the picker slot so the art is easy to read. */
private val ROW_PREVIEW_SIZE = 120.dp

/**
 * Where a row's hover preview goes, as its top-left in window pixels: beside the row when there is room,
 * else anchored to the pointer.  It is never clamped over the row, because a preview under the pointer
 * takes the row's hover, which withdraws the preview, which returns the hover, in a loop.
 *
 * @param Rect     anchorRect   The hovered row's visible bounds, in window pixels.
 * @param Function pointer      The pointer in window pixels, or null when it is not known.
 * @param Int      gapPx        The gap between the row and a preview beside it, in pixels.
 * @param Int      pointerGapPx The gap between the pointer and a preview anchored to it, in pixels.
 * @param IntSize  windowSize   The host window size.
 * @param IntSize  popupSize    The measured preview size.
 * @return IntOffset The preview's top-left.
 */
internal fun rowPreviewPosition(
	anchorRect: Rect,
	pointer: () -> Offset?,
	gapPx: Int,
	pointerGapPx: Int,
	windowSize: IntSize,
	popupSize: IntSize,
): IntOffset {
	val farthestX = max(0, windowSize.width - popupSize.width)
	val farthestY = max(0, windowSize.height - popupSize.height)
	val rightOfRow = anchorRect.right.roundToInt() + gapPx
	val leftOfRow = anchorRect.left.roundToInt() - gapPx - popupSize.width
	val levelWithRow = anchorRect.top.roundToInt().coerceIn(0, farthestY)

	if (rightOfRow + popupSize.width <= windowSize.width) {
		return IntOffset(rightOfRow, levelWithRow)
	}

	if (leftOfRow >= 0) {
		return IntOffset(leftOfRow, levelWithRow)
	}

	val pointerPosition = pointer() ?: return IntOffset(leftOfRow.coerceIn(0, farthestX), levelWithRow)
	val pointerX = pointerPosition.x.roundToInt()
	val pointerY = pointerPosition.y.roundToInt()
	val rightOfPointer = pointerX + pointerGapPx
	val belowPointer = pointerY + pointerGapPx
	val x =
		if (rightOfPointer + popupSize.width <= windowSize.width) {
			rightOfPointer
		} else {
			pointerX - pointerGapPx - popupSize.width
		}
	val y =
		if (belowPointer + popupSize.height <= windowSize.height) {
			belowPointer
		} else {
			pointerY - pointerGapPx - popupSize.height
		}

	return IntOffset(x.coerceIn(0, farthestX), y.coerceIn(0, farthestY))
}

/**
 * Positions a row's hover preview by [rowPreviewPosition].  Unlike the menu providers this ignores the
 * Popup's own anchor layout - the preview is mounted at the space root, so the row's absolute window
 * rectangle [anchorRect] is the real anchor.
 *
 * The pointer is read while the popup is laid out, so a preview anchored to the pointer is laid out
 * again as the pointer moves, with nothing recomposed.
 *
 * @property Rect     anchorRect      The hovered row's bounds, in window pixels.
 * @property Function pointerInWindow The pointer in window pixels, or null when it is not known.
 * @property Int      gapPx           The gap between the row and a preview beside it, in pixels.
 * @property Int      pointerGapPx    The gap between the pointer and a preview anchored to it, in pixels.
 */
private class RowPreviewPositionProvider(
	private val anchorRect: Rect,
	private val pointerInWindow: () -> Offset?,
	private val gapPx: Int,
	private val pointerGapPx: Int,
) : PopupPositionProvider {
	/**
	 * Computes the preview's top-left in window coordinates.
	 *
	 * @param IntRect anchorBounds The Popup's anchor layout bounds (unused - the row rect is the anchor).
	 * @param IntSize windowSize The host window size.
	 * @param LayoutDirection layoutDirection The layout direction (unused; the preview is LTR-neutral).
	 * @param IntSize popupContentSize The measured preview size.
	 * @return IntOffset The preview's top-left.
	 */
	override fun calculatePosition(
		anchorBounds: IntRect,
		windowSize: IntSize,
		layoutDirection: LayoutDirection,
		popupContentSize: IntSize,
	): IntOffset = rowPreviewPosition(anchorRect, pointerInWindow, gapPx, pointerGapPx, windowSize, popupContentSize)
}

/**
 * A passive hover preview for a list row with art: the thumbnail over the themed checker with the row's
 * name beneath, in a small floating card beside the hovered row.  Non-focusable, so it never steals input
 * or the selection; the caller shows and hides it by composing it or not.
 *
 * @param String name The row's display name (document data, not localized).
 * @param ImageBitmap thumbnail The cropped art preview.
 * @param Rect anchorRect The hovered row's bounds, in window pixels, the card is placed beside.
 * @param Function pointerInWindow The pointer in window pixels, or null when it is not known; read only
 *   when the row has no room beside it.
 */
@Composable
fun RowThumbnailPreview(name: String, thumbnail: ImageBitmap, anchorRect: Rect, pointerInWindow: () -> Offset?) {
	val colors = LocalUmamoColors.current
	val shapes = LocalUmamoShapes.current
	val typography = LocalUmamoTypography.current
	val gapPx = with(LocalDensity.current) { 8.dp.roundToPx() }
	// Clear of the cursor, and never under it.
	val pointerGapPx = with(LocalDensity.current) { POINTER_ARROW_CLEARANCE.roundToPx() }
	val positionProvider =
		remember(anchorRect, pointerInWindow, gapPx, pointerGapPx) {
			RowPreviewPositionProvider(anchorRect, pointerInWindow, gapPx, pointerGapPx)
		}
	Popup(
		popupPositionProvider = positionProvider,
		properties = PopupProperties(focusable = false),
	) {
		Surface(
			color = colors.menuBackground,
			shape = shapes.medium,
			border = BorderStroke(1.dp, colors.panelBorder),
			shadowElevation = 8.dp,
		) {
			Column(modifier = Modifier.padding(6.dp)) {
				ThumbnailSlot(thumbnail = thumbnail, size = ROW_PREVIEW_SIZE)
				Spacer(modifier = Modifier.height(4.dp))
				Text(
					text = name,
					style = typography.bodySmall,
					color = colors.text,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.widthIn(max = ROW_PREVIEW_SIZE),
				)
			}
		}
	}
}