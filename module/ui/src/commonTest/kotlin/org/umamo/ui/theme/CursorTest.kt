package org.umamo.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies the bundled cursor set parses (accessing [LocalUmamoCursors] forces every path through the SVG
 * path parser) and that each cursor is drawable: a positive viewport, at least one layer, and every layer
 * carrying real geometry that is actually painted.  A second group rasterizes drawCursor to confirm the
 * hotspot lands on the caller's anchor.
 */
class CursorTest {
	private val cursors = LocalUmamoCursors

	/** Every cursor in the set, so the assertions below cover all of them rather than a sample. */
	private val allCursors: List<Pair<String, UmamoCursor>> =
		listOf(
			"blade" to cursors.blade,
			"bothHandles" to cursors.bothHandles,
			"crosshairContrast" to cursors.crosshairContrast,
			"crosshair" to cursors.crosshair,
			"dot" to cursors.dot,
			"eArrow" to cursors.eArrow,
			"eraser" to cursors.eraser,
			"ewScroll" to cursors.ewScroll,
			"eyedropper" to cursors.eyedropper,
			"hSplit" to cursors.hSplit,
			"hand" to cursors.hand,
			"handClosed" to cursors.handClosed,
			"handPoint" to cursors.handPoint,
			"knife" to cursors.knife,
			"leftHandle" to cursors.leftHandle,
			"mute" to cursors.mute,
			"nArrow" to cursors.nArrow,
			"nsScroll" to cursors.nsScroll,
			"nsewScroll" to cursors.nsewScroll,
			"paint" to cursors.paint,
			"pencil" to cursors.pencil,
			"pickArea" to cursors.pickArea,
			"pointer" to cursors.pointer,
			"rightHandle" to cursors.rightHandle,
			"sArrow" to cursors.sArrow,
			"slip" to cursors.slip,
			"stop" to cursors.stop,
			"swapArea" to cursors.swapArea,
			"textEdit" to cursors.textEdit,
			"vSplit" to cursors.vSplit,
			"vertexLoop" to cursors.vertexLoop,
			"wArrow" to cursors.wArrow,
			"wait" to cursors.wait,
			"xMove" to cursors.xMove,
			"yMove" to cursors.yMove,
			"zoomIn" to cursors.zoomIn,
			"zoomOut" to cursors.zoomOut,
		)

	/** The list above must hold the entire set, so no cursor slips past the per-cursor checks. */
	@Test
	fun everyCursorIsCovered() {
		assertTrue(allCursors.size == 37, "expected all 37 cursors, listed ${allCursors.size}")
	}

	/** Every cursor parses to a positive viewport and bounds with at least one non-empty, painted layer. */
	@Test
	fun bundledCursorSetParses() {
		for ((name, cursor) in allCursors) {
			assertTrue(cursor.viewportWidth > 0f && cursor.viewportHeight > 0f, "$name has a positive viewport")
			assertTrue(cursor.bounds.width > 0f && cursor.bounds.height > 0f, "$name has positive art bounds")
			assertTrue(cursor.layers.isNotEmpty(), "$name has at least one layer")
			assertTrue(cursor.layers.all { layer -> !layer.path.isEmpty }, "$name layers carry geometry")
			assertTrue(
				cursor.layers.all { layer -> layer.fill != null || layer.stroke != null },
				"$name layers are all painted (a fill, a stroke, or both)",
			)
		}
	}

	/** A few hotspots are pinned so a bad edit to the map is caught. */
	@Test
	fun keyHotspotsAreLocked() {
		assertEquals(CursorHotspot.TopLeft, cursors.pointer.hotspot, "pointer tip is top-left")
		assertEquals(CursorHotspot.BottomLeft, cursors.eyedropper.hotspot, "eyedropper nib is bottom-left")
		assertEquals(CursorHotspot.TopRight, cursors.eraser.hotspot, "eraser head is top-right")
		assertEquals(CursorHotspot.TopCenter, cursors.handPoint.hotspot, "pointing fingertip is top-center")
		assertEquals(CursorHotspot.MiddleCenter, cursors.crosshair.hotspot, "crosshair is centered")
		// pick_area is a lone +; its hotspot is the center of its own bounds, i.e. the default.
		assertEquals(CursorHotspot.MiddleCenter, cursors.pickArea.hotspot, "pick_area + is centered")
	}

	/** Shifting the anchor shifts the painted art by the same vector (stroke expansion cancels out). */
	@Test
	fun drawCursorTracksTheAnchor() {
		val first = paintedBounds(cursors.pointer, Offset(60f, 55f), 100f)
		val second = paintedBounds(cursors.pointer, Offset(100f, 80f), 100f)
		assertNotNull(first, "pointer rendered at the first anchor")
		assertNotNull(second, "pointer rendered at the second anchor")
		// Anchor moved by (40, 25); every edge of the painted box must move with it.
		assertTrue(abs((second.left - first.left) - 40) <= 1, "minX tracks anchor dx")
		assertTrue(abs((second.top - first.top) - 25) <= 1, "minY tracks anchor dy")
		assertTrue(abs((second.right - first.right) - 40) <= 1, "maxX tracks anchor dx")
		assertTrue(abs((second.bottom - first.bottom) - 25) <= 1, "maxY tracks anchor dy")
	}

	/** A MiddleCenter hotspot centers the painted art on the anchor; a corner hotspot offsets it. */
	@Test
	fun hotspotPlacesArtRelativeToAnchor() {
		val anchor = Offset(110f, 100f)
		// crosshair is centered: the painted box straddles the anchor evenly (symmetric stroke keeps the
		// center exact even though it widens the box).
		val centered = paintedBounds(cursors.crosshair, anchor, 80f)
		assertNotNull(centered, "crosshair rendered")
		assertTrue(abs((centered.left + centered.right) / 2f - anchor.x) <= 2f, "crosshair center x on anchor")
		assertTrue(abs((centered.top + centered.bottom) / 2f - anchor.y) <= 2f, "crosshair center y on anchor")

		// pointer is TopLeft: the arrow tip sits on the anchor and the body lies down-right of it.
		val corner = paintedBounds(cursors.pointer, anchor, 100f)
		assertNotNull(corner, "pointer rendered")
		assertTrue(abs(corner.left - anchor.x) <= 8f, "pointer tip x near anchor (within stroke half-width)")
		assertTrue(abs(corner.top - anchor.y) <= 8f, "pointer tip y near anchor (within stroke half-width)")
		assertTrue((corner.left + corner.right) / 2f > anchor.x + 10f, "pointer body is right of the anchor")
		assertTrue((corner.top + corner.bottom) / 2f > anchor.y + 10f, "pointer body is below the anchor")
	}

	/** Bounding box, in pixels, of everything drawCursor painted over a sentinel background. */
	private data class PaintedBox(val left: Int, val top: Int, val right: Int, val bottom: Int)

	/**
	 * Rasterizes [cursor] at [anchor]/[pixelSize] onto a sentinel-filled bitmap and returns the tight box
	 * of the painted (non-sentinel) pixels, or null if nothing was drawn.
	 */
	private fun paintedBounds(cursor: UmamoCursor, anchor: Offset, pixelSize: Float, dimension: Int = 220): PaintedBox? {
		// Magenta is used by none of the cursors (white / black / blue / green), so it reads as "unpainted".
		val sentinel = Color(1f, 0f, 1f, 1f)
		val bitmap = ImageBitmap(dimension, dimension)
		CanvasDrawScope().draw(
			Density(1f),
			LayoutDirection.Ltr,
			Canvas(bitmap),
			Size(dimension.toFloat(), dimension.toFloat()),
		) {
			drawRect(color = sentinel)
			drawCursor(cursor, anchor, pixelSize)
		}
		val pixels = bitmap.toPixelMap()
		var minX = dimension
		var minY = dimension
		var maxX = -1
		var maxY = -1
		for (y in 0 until dimension) {
			for (x in 0 until dimension) {
				val pixel = pixels[x, y]
				val delta = abs(pixel.red - sentinel.red) + abs(pixel.green - sentinel.green) + abs(pixel.blue - sentinel.blue)
				if (delta > 0.2f) {
					minX = min(minX, x)
					minY = min(minY, y)
					maxX = max(maxX, x)
					maxY = max(maxY, y)
				}
			}
		}
		if (maxX < 0) {
			return null
		}
		return PaintedBox(minX, minY, maxX, maxY)
	}
}
