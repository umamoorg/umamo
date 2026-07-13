package org.umamo.ui.workspace

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoShapes
import kotlin.math.max
import kotlin.math.min

/**
 * The visual-only highlight for an in-flight corner drag.  Reads the shared [controller]'s drag state
 * and paints one of three previews: a JOIN box spanning the source and target areas (consumed target
 * tinted stronger), a DOCK strip on a workspace edge (source's slot shown vacating), or a SPLIT preview
 * of the source area drawn as the two resulting areas.  It draws nothing for a no-op or when idle.
 *
 * Highlights are rounded to the same large corner radius the areas themselves use, and the split preview
 * leaves the same gap between its halves that a real [Splitter] leaves between areas, so the preview
 * reads as the actual layout it will produce.
 *
 * No pointer input: the gesture itself lives in the corner hotspot, so this layer must never intercept
 * events - it only renders.  It is drawn in the shell content Box, the same content-local space the leaf
 * rectangles are captured in, so the captured rects map straight to draw coordinates.
 *
 * コーナードラッグの進行中ハイライト（描画のみ）。結合・ドック・分割を、実際のエリアと同じ角丸と間隔で示す。
 *
 * @param AreaDragController controller The shared corner-drag state.
 * @param Modifier modifier The layout modifier (fill the content Box).
 */
@Composable
fun AreaDragOverlay(controller: AreaDragController, modifier: Modifier = Modifier) {
	val state = controller.dragState
	if (state == null) {
		return
	}
	val colors = LocalUmamoColors.current
	val accent = colors.accent
	val fillWash = colors.dropZoneFill
	val emphasisWash = colors.dropZoneEmphasis
	val density = LocalDensity.current
	val largeShape = LocalUmamoShapes.current.large
	val corner =
		with(density) {
			val radius = if (largeShape is RoundedCornerShape) largeShape.topStart.toPx(Size.Zero, this) else 0f
			CornerRadius(radius)
		}
	val gapPx = with(density) { SPLITTER_THICKNESS.toPx() }
	val sourceRect = controller.boundsOf(state.sourceId)
	if (state.validJoin && state.targetId != null) {
		val targetRect = controller.boundsOf(state.targetId)
		if (sourceRect != null && targetRect != null) {
			Canvas(modifier = modifier) { drawJoinHighlight(sourceRect, targetRect, accent, fillWash, emphasisWash, corner) }
		}
		return
	}
	val dockEdge = state.dockEdge
	if (state.validDock && dockEdge != null && sourceRect != null) {
		val dockRatio = state.dockRatio
		Canvas(modifier = modifier) { drawDockHighlight(sourceRect, dockEdge, dockRatio, accent, fillWash, emphasisWash, corner) }
		return
	}
	val orientation = state.splitOrientation
	if (state.validSplit && orientation != null && sourceRect != null) {
		val ratio = state.splitRatio
		Canvas(modifier = modifier) { drawSplitHighlight(sourceRect, orientation, ratio, accent, fillWash, emphasisWash, corner, gapPx) }
	}
}

/**
 * Paints the join preview: a faint fill over the union of the two areas, a stronger fill over the
 * consumed target, then a crisp outline around the union (the resulting area).
 *
 * @param Rect source The surviving (dragged) area.
 * @param Rect target The consumed area.
 * @param Color accent The accent color (the outline).
 * @param Color fillWash The faint wash over the whole affected region.
 * @param Color emphasisWash The stronger wash over the part that changes.
 * @param CornerRadius corner The corner rounding (matches the areas).
 */
private fun DrawScope.drawJoinHighlight(
	source: Rect,
	target: Rect,
	accent: Color,
	fillWash: Color,
	emphasisWash: Color,
	corner: CornerRadius,
) {
	val unionTopLeft = Offset(min(source.left, target.left), min(source.top, target.top))
	val unionSize =
		Size(
			max(source.right, target.right) - unionTopLeft.x,
			max(source.bottom, target.bottom) - unionTopLeft.y,
		)
	drawRoundRect(color = fillWash, topLeft = unionTopLeft, size = unionSize, cornerRadius = corner)
	drawRoundRect(
		color = emphasisWash,
		topLeft = target.topLeft,
		size = target.size,
		cornerRadius = corner,
	)
	drawRoundRect(
		color = accent,
		topLeft = unionTopLeft,
		size = unionSize,
		cornerRadius = corner,
		style = Stroke(width = 2.dp.toPx()),
	)
}

/**
 * Paints the dock preview: a faint "vacating" wash over the source's current slot, then the destination
 * strip - a full-span band on the chosen workspace [edge] taking [ratio] of the axis - with a stronger
 * fill and a crisp outline.  The strip is measured against the whole content area ([DrawScope.size]).
 *
 * @param Rect source The area being relocated (its slot is vacated).
 * @param DockEdge edge The workspace edge the source docks against.
 * @param Float ratio The docked strip's fraction of the perpendicular axis.
 * @param Color accent The accent color (the outline).
 * @param Color fillWash The faint wash over the vacated slot.
 * @param Color emphasisWash The stronger wash over the destination strip.
 * @param CornerRadius corner The corner rounding (matches the areas).
 */
private fun DrawScope.drawDockHighlight(
	source: Rect,
	edge: DockEdge,
	ratio: Float,
	accent: Color,
	fillWash: Color,
	emphasisWash: Color,
	corner: CornerRadius,
) {
	drawRoundRect(color = fillWash, topLeft = source.topLeft, size = source.size, cornerRadius = corner)
	val width = size.width
	val height = size.height
	val strip =
		when (edge) {
			DockEdge.Top -> Rect(left = 0f, top = 0f, right = width, bottom = height * ratio)
			DockEdge.Bottom -> Rect(left = 0f, top = height * (1f - ratio), right = width, bottom = height)
			DockEdge.Left -> Rect(left = 0f, top = 0f, right = width * ratio, bottom = height)
			DockEdge.Right -> Rect(left = width * (1f - ratio), top = 0f, right = width, bottom = height)
		}
	drawRoundRect(color = emphasisWash, topLeft = strip.topLeft, size = strip.size, cornerRadius = corner)
	drawRoundRect(color = accent, topLeft = strip.topLeft, size = strip.size, cornerRadius = corner, style = Stroke(width = 2.dp.toPx()))
}

/**
 * Paints the split preview as the two areas it produces: the original half (faint) and the new half
 * (stronger), each rounded and outlined, separated by the same [gapPx] gap a real [Splitter] leaves
 * between areas - so the preview reads as the resulting layout rather than a single divided box.
 *
 * @param Rect source The area being split.
 * @param SplitOrientation orientation The split axis.
 * @param Float ratio The divider fraction (original side).
 * @param Color accent The accent color (the outlines).
 * @param Color fillWash The faint wash over the original half.
 * @param Color emphasisWash The stronger wash over the new half.
 * @param CornerRadius corner The corner rounding (matches the areas).
 * @param Float gapPx The gap between the halves (the splitter thickness).
 */
private fun DrawScope.drawSplitHighlight(
	source: Rect,
	orientation: SplitOrientation,
	ratio: Float,
	accent: Color,
	fillWash: Color,
	emphasisWash: Color,
	corner: CornerRadius,
	gapPx: Float,
) {
	val halfGap = gapPx / 2f
	val (originalHalf, newHalf) =
		when (orientation) {
			SplitOrientation.Horizontal -> {
				val dividerX = source.left + ratio * source.width
				Rect(source.left, source.top, dividerX - halfGap, source.bottom) to
					Rect(dividerX + halfGap, source.top, source.right, source.bottom)
			}
			SplitOrientation.Vertical -> {
				val dividerY = source.top + ratio * source.height
				Rect(source.left, source.top, source.right, dividerY - halfGap) to
					Rect(source.left, dividerY + halfGap, source.right, source.bottom)
			}
		}
	val stroke = 2.dp.toPx()
	drawRoundRect(color = fillWash, topLeft = originalHalf.topLeft, size = originalHalf.size, cornerRadius = corner)
	drawRoundRect(color = emphasisWash, topLeft = newHalf.topLeft, size = newHalf.size, cornerRadius = corner)
	drawRoundRect(color = accent, topLeft = originalHalf.topLeft, size = originalHalf.size, cornerRadius = corner, style = Stroke(width = stroke))
	drawRoundRect(color = accent, topLeft = newHalf.topLeft, size = newHalf.size, cornerRadius = corner, style = Stroke(width = stroke))
}
