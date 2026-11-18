package org.umamo.ui.theme

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Verifies that an icon paints into a bitmap, and that the cached form is the same drawing: an icon drawn
 * twice must not change, or the cache would be showing a stale picture.
 */
class IconRenderingTest {
	/**
	 * Renders an icon into a bitmap of its own size.
	 *
	 * @param UmamoIcon icon The icon to render.
	 * @param Color color The tint.
	 * @param Int side The pixel size.
	 * @return ImageBitmap The rendered icon.
	 */
	private fun render(icon: UmamoIcon, color: Color, side: Int): ImageBitmap {
		val bitmap = ImageBitmap(side, side)

		CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(side.toFloat(), side.toFloat())) {
			drawIcon(icon, color)
		}

		return bitmap
	}

	/**
	 * An icon paints something: the bitmap is not empty.
	 */
	@Test
	fun anIconPaintsItsPath() {
		val bitmap = render(LocalUmamoIcons.reset, Color.White, 32)
		var painted = 0

		for (x in 0 until bitmap.width) {
			for (y in 0 until bitmap.height) {
				if (bitmap.toPixelMap().get(x, y).alpha > 0) {
					painted++
				}
			}
		}

		assertTrue(painted > 0, "the reset icon paints something; painted=$painted")
	}

	/**
	 * Drawing the same icon twice gives the same picture: the cache does not hand out a stale bitmap, and
	 * the second draw is the one that came out of the cache.
	 */
	@Test
	fun drawingAnIconTwiceGivesTheSamePicture() {
		val first = render(LocalUmamoIcons.reset, Color.White, 32)
		val second = render(LocalUmamoIcons.reset, Color.White, 32)

		for (x in 0 until first.width) {
			for (y in 0 until first.height) {
				assertTrue(
					first.toPixelMap().get(x, y) == second.toPixelMap().get(x, y),
					"the cached icon is the same drawing at ($x, $y)",
				)
			}
		}
	}

	/**
	 * Two different icons are two different pictures: the cache keys on the icon itself, not only its size.
	 */
	@Test
	fun differentIconsDrawDifferently() {
		val reset = render(LocalUmamoIcons.reset, Color.White, 32)
		val search = render(LocalUmamoIcons.search, Color.White, 32)
		var differing = 0

		for (x in 0 until reset.width) {
			for (y in 0 until reset.height) {
				if (reset.toPixelMap().get(x, y) != search.toPixelMap().get(x, y)) {
					differing++
				}
			}
		}

		assertTrue(differing > 0, "the reset and the search icon are not the same drawing; differing=$differing")
	}
}