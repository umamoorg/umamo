package org.umamo.ui.viewport

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import org.umamo.ui.theme.UmamoColors

// The on/off dash length (equal halves, so offsetting the second pass by one length makes its dashes fall in
// the first pass's gaps - the alternating "marching ants" look).
private val ANTS_DASH = 5.dp

// The affordance line width, shared so the crosshair, box, and circle read identically.
private val ANTS_STROKE = 1.dp

// The selection region's fill alpha: mostly transparent, just enough to read the enclosed area over busy art.
private const val SELECTION_FILL_ALPHA = 0.12f

/**
 * The two-tone "marching ants" palette for the viewport selection affordances (crosshair guides, box, and
 * circle), read once from the theme so the three share one look.  The two dash tones are opposite in
 * luminance (light plus dark), so the alternating outline stays legible over any viewport background -
 * grid, bright art, or dark art - where a single-color dash washes out.
 *
 * @property Color primary The first dash tone (the theme marquee).
 * @property Color secondary The contrasting dash tone that fills the primary's gaps.
 * @property Color fill The mostly-transparent region fill for the box and circle (the guides use no fill).
 */
class SelectionOverlayStyle(val primary: Color, val secondary: Color, val fill: Color)

/**
 * Builds the [SelectionOverlayStyle] from the active theme colors: the marquee and its contrast tone for the
 * two-tone dash, and a low-alpha marquee wash for the region fill.
 *
 * @param UmamoColors colors The active theme palette.
 * @return SelectionOverlayStyle The shared affordance style.
 */
fun selectionOverlayStyle(colors: UmamoColors): SelectionOverlayStyle =
	SelectionOverlayStyle(
		primary = colors.viewportMarquee,
		secondary = colors.viewportMarqueeContrast,
		fill = colors.viewportMarquee.copy(alpha = SELECTION_FILL_ALPHA),
	)

/**
 * Strokes a shape twice with the marching-ants dash - once in each tone, the second offset by one dash so
 * the tones alternate along the outline.  The single primitive behind every selection affordance, so the
 * crosshair, box, and circle cannot drift apart.
 *
 * @param SelectionOverlayStyle style The two-tone palette.
 * @param Function drawStroke Strokes the shape with the given color and dash path effect; called twice.
 */
private inline fun strokeMarchingAnts(style: SelectionOverlayStyle, dashPx: Float, drawStroke: (Color, PathEffect) -> Unit) {
	val intervals = floatArrayOf(dashPx, dashPx)
	drawStroke(style.primary, PathEffect.dashPathEffect(intervals, 0f))
	drawStroke(style.secondary, PathEffect.dashPathEffect(intervals, dashPx))
}

/**
 * Draws Blender's full-viewport crosshair guides for the armed Box-select and Zoom-Region gestures: a
 * horizontal and a vertical marching-ants line spanning the area through [cursor].  The caller clips to the
 * area bounds, so the lines stop at the area edge rather than bleeding across panels.
 *
 * @param Offset cursor The pointer position in area-local pixels.
 * @param Size size The area size in pixels.
 * @param SelectionOverlayStyle style The shared two-tone style.
 */
fun DrawScope.drawCrosshairGuides(cursor: Offset, size: Size, style: SelectionOverlayStyle) {
	val strokeWidth = ANTS_STROKE.toPx()
	strokeMarchingAnts(style, ANTS_DASH.toPx()) { color, dash ->
		drawLine(color = color, start = Offset(0f, cursor.y), end = Offset(size.width, cursor.y), strokeWidth = strokeWidth, pathEffect = dash)
		drawLine(color = color, start = Offset(cursor.x, 0f), end = Offset(cursor.x, size.height), strokeWidth = strokeWidth, pathEffect = dash)
	}
}

/**
 * Draws a rubber-band selection / zoom box: a mostly-transparent fill plus a two-tone marching-ants border.
 * The single shared style for the non-armed and armed Box-select and for the Zoom Region gesture.
 *
 * @param Offset topLeft The box top-left in area-local pixels.
 * @param Size size The box size in pixels.
 * @param SelectionOverlayStyle style The shared two-tone style.
 */
fun DrawScope.drawSelectionBox(topLeft: Offset, size: Size, style: SelectionOverlayStyle) {
	drawRect(color = style.fill, topLeft = topLeft, size = size)
	val strokeWidth = ANTS_STROKE.toPx()
	strokeMarchingAnts(style, ANTS_DASH.toPx()) { color, dash ->
		drawRect(color = color, topLeft = topLeft, size = size, style = Stroke(width = strokeWidth, pathEffect = dash))
	}
}

/**
 * Draws the Circle-select brush: a mostly-transparent fill disc plus a two-tone marching-ants outline,
 * matching the box and crosshair style.
 *
 * @param Offset center The brush centre in area-local pixels.
 * @param Float radius The brush radius in pixels.
 * @param SelectionOverlayStyle style The shared two-tone style.
 */
fun DrawScope.drawSelectionCircle(center: Offset, radius: Float, style: SelectionOverlayStyle) {
	drawCircle(color = style.fill, radius = radius, center = center)
	val strokeWidth = ANTS_STROKE.toPx()
	strokeMarchingAnts(style, ANTS_DASH.toPx()) { color, dash ->
		drawCircle(color = color, radius = radius, center = center, style = Stroke(width = strokeWidth, pathEffect = dash))
	}
}
