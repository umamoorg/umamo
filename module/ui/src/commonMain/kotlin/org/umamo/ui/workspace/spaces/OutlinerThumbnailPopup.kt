package org.umamo.ui.workspace.spaces

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
import org.umamo.ui.kit.Surface
import org.umamo.ui.kit.Text
import org.umamo.ui.kit.ThumbnailSlot
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.LocalUmamoTypography
import kotlin.math.max
import kotlin.math.roundToInt

/** The square edge of the Outliner hover preview - bigger than the picker slot so the art is easy to read. */
private val OUTLINER_PREVIEW_SIZE = 120.dp

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
 * The little name chip that follows the cursor while a row is being dragged, so there is something obviously
 * "in hand" beyond the faded source row. Non-focusable and mounted at the space root, positioned in window
 * coordinates at the drag pointer.
 *
 * ドラッグ中にカーソルに追従する名前チップ。掴んでいる対象を視覚的に示す。
 *
 * @param String label The dragged row's display name.
 * @param Float cursorX The drag pointer X, in window pixels.
 * @param Float cursorY The drag pointer Y, in window pixels.
 */
@Composable
fun OutlinerDragLabel(label: String, cursorX: Float, cursorY: Float) {
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

/**
 * Positions the Outliner hover preview just to the right of the hovered row (anchored by its window
 * bounds), flipping to the left when it would overflow the window's right edge and clamping to the window
 * on both axes. Unlike the menu providers this ignores the Popup's own anchor layout - the preview is
 * mounted at the space root, so the row's absolute window rectangle [anchorRect] is the real anchor.
 *
 * @property Rect anchorRect The hovered row's bounds, in window pixels.
 * @property Int gapPx       The horizontal gap between the row and the preview, in pixels.
 */
private class RowSidePopupPositionProvider(
	private val anchorRect: Rect,
	private val gapPx: Int,
) : PopupPositionProvider {
	/**
	 * Computes the preview's top-left in window coordinates.
	 *
	 * @param IntRect anchorBounds The Popup's anchor layout bounds (unused - the row rect is the anchor).
	 * @param IntSize windowSize The host window size.
	 * @param LayoutDirection layoutDirection The layout direction (unused; the preview is LTR-neutral).
	 * @param IntSize popupContentSize The measured preview size.
	 * @return IntOffset The preview's top-left, clamped into the window.
	 */
	override fun calculatePosition(
		anchorBounds: IntRect,
		windowSize: IntSize,
		layoutDirection: LayoutDirection,
		popupContentSize: IntSize,
	): IntOffset {
		val rightOfRow = anchorRect.right.roundToInt() + gapPx
		val leftOfRow = anchorRect.left.roundToInt() - gapPx - popupContentSize.width
		// Prefer the right of the row; flip to the left when the preview would overflow the window edge.
		val preferredX = if (rightOfRow + popupContentSize.width <= windowSize.width) rightOfRow else leftOfRow
		val x = preferredX.coerceIn(0, max(0, windowSize.width - popupContentSize.width))
		val y = anchorRect.top.roundToInt().coerceIn(0, max(0, windowSize.height - popupContentSize.height))
		return IntOffset(x, y)
	}
}

/**
 * A passive hover preview for an Outliner drawable row: the art-mesh thumbnail over the themed checker
 * (so a transparent layer reads as a silhouette) with the drawable's name beneath, in a small floating
 * card anchored beside the hovered row. Non-focusable - it never steals input or the selection; the caller
 * shows and hides it purely by composing or not composing it (no dismiss handling needed).
 *
 * アウトライナーのドロウアブル行のホバープレビュー。サムネイルと名前を行の横の小さなカードに表示する。
 *
 * @param String name The drawable's display name (document data, not localized).
 * @param ImageBitmap thumbnail The cropped art preview.
 * @param Rect anchorRect The hovered row's bounds, in window pixels, the card is placed beside.
 */
@Composable
fun OutlinerThumbnailPreview(name: String, thumbnail: ImageBitmap, anchorRect: Rect) {
	val colors = LocalUmamoColors.current
	val shapes = LocalUmamoShapes.current
	val typography = LocalUmamoTypography.current
	val gapPx = with(LocalDensity.current) { 8.dp.roundToPx() }
	Popup(
		popupPositionProvider = RowSidePopupPositionProvider(anchorRect, gapPx),
		properties = PopupProperties(focusable = false),
	) {
		Surface(
			color = colors.menuBackground,
			shape = shapes.medium,
			border = BorderStroke(1.dp, colors.panelBorder),
			shadowElevation = 8.dp,
		) {
			Column(modifier = Modifier.padding(6.dp)) {
				ThumbnailSlot(thumbnail = thumbnail, size = OUTLINER_PREVIEW_SIZE)
				Spacer(modifier = Modifier.height(4.dp))
				Text(
					text = name,
					style = typography.bodySmall,
					color = colors.text,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.widthIn(max = OUTLINER_PREVIEW_SIZE),
				)
			}
		}
	}
}
