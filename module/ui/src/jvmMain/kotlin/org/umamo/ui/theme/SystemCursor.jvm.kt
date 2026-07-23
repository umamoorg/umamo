package org.umamo.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.awt.Dimension
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The fallback cursor box used when the toolkit reports no preferred size.  32x32 is the near-universal
 * native cursor size, so this is only a guard against a toolkit that answers 0.
 */
private const val FALLBACK_CURSOR_SIZE = 32

/**
 * The fraction of the cursor box the art spans.  Kept under 1 so a hotspot that sits on the art's edge
 * still has a pixel of margin inside the bitmap and the outline is not clipped by the box.
 */
private const val CURSOR_ART_FRACTION = 0.9f

/**
 * Baked cursors, keyed by the cursor definition.  Without this every hover frame would rasterize a fresh
 * bitmap and ask the toolkit for a new native cursor, since the icon is resolved during composition.  The
 * bundled cursors are a single top-level value, so the same key instance comes back each time.  Concurrent
 * because composition is not pledged to one thread, and a torn HashMap would be a genuine crash.
 */
private val bakedCursors = ConcurrentHashMap<UmamoCursor, PointerIcon>()

/**
 * A single cached transparent AWT cursor; falls back to the default pointer if the toolkit cannot build a
 * custom cursor (a locked-down or headless environment).
 */
private val hiddenCursorIcon: PointerIcon by lazy {
	try {
		val transparent = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
		val cursor = Toolkit.getDefaultToolkit().createCustomCursor(transparent, Point(0, 0), "umamo-hidden")
		PointerIcon(cursor)
	} catch (error: Exception) {
		// HeadlessException, IndexOutOfBoundsException (bad hotspot), or a toolkit failure - fall back.
		PointerIcon.Default
	}
}

/**
 * Rasterizes [cursor] into an AWT custom cursor at the toolkit's preferred cursor size.
 *
 * The art is scaled to [CURSOR_ART_FRACTION] of the box and centered, then the cursor's own hotspot
 * fraction is resolved against the PAINTED bounds (not the authored viewport) to a pixel, mirroring what
 * drawCursor does on the overlay path - so the same art tracks the pointer identically whether the OS or
 * the viewport overlay is drawing it.
 *
 * @param UmamoCursor cursor The cursor definition to bake.
 * @return PointerIcon The AWT-backed cursor, or the default if the toolkit refuses one.
 */
private fun bakeCursor(cursor: UmamoCursor): PointerIcon =
	try {
		val toolkit = Toolkit.getDefaultToolkit()
		val preferred: Dimension? = toolkit.getBestCursorSize(FALLBACK_CURSOR_SIZE, FALLBACK_CURSOR_SIZE)
		val boxSize = max(preferred?.width ?: 0, preferred?.height ?: 0).takeIf { it > 0 } ?: FALLBACK_CURSOR_SIZE
		val artSpan = boxSize * CURSOR_ART_FRACTION

		// Match drawCursor: scale so the art's LONGER side spans artSpan, keeping the authored aspect.
		val bounds = cursor.bounds
		val drawScale = artSpan / max(bounds.width, bounds.height)
		val artWidth = bounds.width * drawScale
		val artHeight = bounds.height * drawScale
		// Center the art in the box, then resolve the hotspot inside it.
		val hotspotX = (boxSize - artWidth) / 2f + cursor.hotspot.fractionX * artWidth
		val hotspotY = (boxSize - artHeight) / 2f + cursor.hotspot.fractionY * artHeight

		val bitmap = ImageBitmap(boxSize, boxSize)
		CanvasDrawScope().draw(
			density = Density(1f),
			layoutDirection = LayoutDirection.Ltr,
			canvas = Canvas(bitmap),
			size = Size(boxSize.toFloat(), boxSize.toFloat()),
		) {
			// drawCursor places the hotspot exactly on the anchor, so anchoring at the resolved hotspot
			// lands the art where the centering math intends.
			drawCursor(cursor, Offset(hotspotX, hotspotY), pixelSize = artSpan)
		}

		val awtHotspot =
			Point(
				hotspotX.roundToInt().coerceIn(0, boxSize - 1),
				hotspotY.roundToInt().coerceIn(0, boxSize - 1),
			)
		PointerIcon(toolkit.createCustomCursor(bitmap.toAwtImage(), awtHotspot, "umamo-cursor"))
	} catch (error: Exception) {
		// HeadlessException, an unsupported cursor size, or a raster failure - a stock pointer still works.
		PointerIcon.Default
	}

/**
 * Desktop actual: bakes the vector cursor into an AWT custom cursor once and reuses it thereafter.
 *
 * デスクトップ実装：ベクターカーソルを AWT カーソルに一度だけ焼き込み、以降は再利用する。
 *
 * @param UmamoCursor cursor The cursor definition to rasterize.
 * @return PointerIcon The AWT-backed cursor showing that art.
 */
actual fun umamoPointerIcon(cursor: UmamoCursor): PointerIcon = bakedCursors.getOrPut(cursor) { bakeCursor(cursor) }

/**
 * Desktop actual: an invisible cursor built from a fully transparent 16x16 image.
 *
 * デスクトップ実装：透明画像から作った不可視カーソルを返す。
 *
 * @return PointerIcon The transparent cursor, or the default where none can be built.
 */
actual fun hiddenPointerIcon(): PointerIcon = hiddenCursorIcon
