package org.umamo.ui.viewport.uv

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntSize
import org.umamo.format.art.AlphaContour
import org.umamo.format.art.LayerBounds
import org.umamo.render.ViewportCamera
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.ui.viewport.gizmo.worldToScreen

/**
 * Draws the placement drag's preview under the islands: each mover's original spot dimmed (the art is
 * leaving it) and its crop drawn where the placement now puts it, then a committed move's ghost crops while
 * the resolver composes the real pixels.
 *
 * @param PlacementGesture? capture The in-flight placement gesture, or null.
 * @param PlacementDragResult? result Its latest evaluation, or null before the first drive.
 * @param PlacementGhost? ghost The committed move's crops to draw, or null.
 * @param Color scrimColor The dimming over a mover's original spot.
 * @param ViewportCamera camera The frame camera.
 * @param IntSize size The area size in pixels.
 */
internal fun DrawScope.drawPlacementPreview(
	capture: PlacementGesture?,
	result: PlacementDragResult?,
	ghost: PlacementGhost?,
	scrimColor: Color,
	camera: ViewportCamera,
	size: IntSize,
) {
	if (capture != null && result != null) {
		for (mover in capture.movers) {
			drawTileQuad(mover.trim, mover.placement, capture.pageHeight, camera, size) { quad ->
				drawPath(quad, scrimColor)
			}
		}
		for (mover in capture.movers) {
			val crop = mover.crop ?: continue
			val placement = result.placementByTile[mover.tileId] ?: continue
			drawTileCrop(crop, mover.trim, placement, capture.pageHeight, camera, size)
		}
	}
	ghost?.let { pending ->
		for (ghostCrop in pending.crops) {
			drawTileCrop(ghostCrop.crop, ghostCrop.trim, ghostCrop.placement, pending.pageHeight, camera, size)
		}
	}
}

/**
 * Draws the painter's side of a collision on top of the islands: a mover whose paint lies under another
 * tile's triangles outlines its opaque region with the art's own contour (never a box), and a spill outlines
 * the trim that leaves the page.  A bystander painter has no contour without a decode; its tinted islands
 * stand for it.
 *
 * @param PlacementGesture? capture The in-flight placement gesture, or null.
 * @param PlacementDragResult? result Its latest evaluation, or null before the first drive.
 * @param Color warningColor The collision outline color.
 * @param ViewportCamera camera The frame camera.
 * @param IntSize size The area size in pixels.
 */
internal fun DrawScope.drawPlacementCollisions(
	capture: PlacementGesture?,
	result: PlacementDragResult?,
	warningColor: Color,
	camera: ViewportCamera,
	size: IntSize,
) {
	if (capture == null || result == null) {
		return
	}
	val warningStroke = Stroke(width = 2f)
	for (mover in capture.movers) {
		val placement = result.placementByTile[mover.tileId] ?: continue
		if (mover.tileId in result.paintingTileIds) {
			drawContours(mover.contours, placement, capture.pageHeight, camera, size, warningColor, warningStroke)
		}
		if (mover.tileId in result.offPageTileIds) {
			drawTileQuad(mover.trim, placement, capture.pageHeight, camera, size) { quad ->
				drawPath(quad, warningColor, style = warningStroke)
			}
		}
	}
}

/**
 * Where a tile-local point lands on screen under [placement]: through the placement into page pixels,
 * through the display flip, and through the area camera.
 *
 * @param Float tileX The tile-local x.
 * @param Float tileY The tile-local y.
 * @param FloatArray tileToDisplay The tile-to-display affine (the placement's, flipped).
 * @param ViewportCamera camera The area camera.
 * @param IntSize size The area size in pixels.
 * @return Offset The screen position.
 */
private fun tileToScreen(tileX: Float, tileY: Float, tileToDisplay: FloatArray, camera: ViewportCamera, size: IntSize): Offset {
	val displayX = tileToDisplay[0] * tileX + tileToDisplay[1] * tileY + tileToDisplay[2]
	val displayY = tileToDisplay[3] * tileX + tileToDisplay[4] * tileY + tileToDisplay[5]
	return worldToScreen(displayX, displayY, camera, size)
}

/**
 * Draws a tile's trim crop where [placement] puts it: the crop's pixel grid is carried by a matrix
 * fitted to three transformed trim corners, so rotation and scale come along, bilinear-filtered.
 *
 * @param ImageBitmap crop The trim's pixels.
 * @param LayerBounds trim The trim the crop covers, raster-local.
 * @param AtlasPlacement placement Where the tile sits.
 * @param Int pageHeight The page height, for the display flip.
 * @param ViewportCamera camera The area camera.
 * @param IntSize size The area size in pixels.
 */
private fun DrawScope.drawTileCrop(
	crop: ImageBitmap,
	trim: LayerBounds,
	placement: AtlasPlacement,
	pageHeight: Int,
	camera: ViewportCamera,
	size: IntSize,
) {
	val tileToDisplay = tileToDisplayAffine(placement, pageHeight)
	val origin = tileToScreen(trim.left.toFloat(), trim.top.toFloat(), tileToDisplay, camera, size)
	val xAxis = tileToScreen(trim.left + 1f, trim.top.toFloat(), tileToDisplay, camera, size) - origin
	val yAxis = tileToScreen(trim.left.toFloat(), trim.top + 1f, tileToDisplay, camera, size) - origin
	val matrix = Matrix()
	matrix.values[Matrix.ScaleX] = xAxis.x
	matrix.values[Matrix.SkewY] = xAxis.y
	matrix.values[Matrix.SkewX] = yAxis.x
	matrix.values[Matrix.ScaleY] = yAxis.y
	matrix.values[Matrix.TranslateX] = origin.x
	matrix.values[Matrix.TranslateY] = origin.y
	withTransform({ transform(matrix) }) {
		drawImage(image = crop, dstSize = IntSize(crop.width, crop.height), filterQuality = FilterQuality.Low)
	}
}

/**
 * Hands [draw] the screen-space quad of a tile's trim under [placement].
 *
 * @param LayerBounds trim The trim, raster-local.
 * @param AtlasPlacement placement Where the tile sits.
 * @param Int pageHeight The page height, for the display flip.
 * @param ViewportCamera camera The area camera.
 * @param IntSize size The area size in pixels.
 * @param Function draw Draws the closed quad.
 */
private fun DrawScope.drawTileQuad(
	trim: LayerBounds,
	placement: AtlasPlacement,
	pageHeight: Int,
	camera: ViewportCamera,
	size: IntSize,
	draw: DrawScope.(Path) -> Unit,
) {
	val tileToDisplay = tileToDisplayAffine(placement, pageHeight)
	val left = trim.left.toFloat()
	val top = trim.top.toFloat()
	val right = (trim.left + trim.width).toFloat()
	val bottom = (trim.top + trim.height).toFloat()
	val quad = Path()
	val first = tileToScreen(left, top, tileToDisplay, camera, size)
	quad.moveTo(first.x, first.y)
	for ((cornerX, cornerY) in listOf(right to top, right to bottom, left to bottom)) {
		val corner = tileToScreen(cornerX, cornerY, tileToDisplay, camera, size)
		quad.lineTo(corner.x, corner.y)
	}
	quad.close()
	draw(quad)
}

/**
 * Strokes a tile's opaque contours on screen under [placement]: each loop's lattice corners carried
 * through the placement and the camera, holes included, so the outline follows the art.
 *
 * @param List<AlphaContour> contours The loops, raster-local.
 * @param AtlasPlacement placement Where the tile sits.
 * @param Int pageHeight The page height, for the display flip.
 * @param ViewportCamera camera The area camera.
 * @param IntSize size The area size in pixels.
 * @param Color color The stroke color.
 * @param Stroke stroke The stroke style.
 */
private fun DrawScope.drawContours(
	contours: List<AlphaContour>,
	placement: AtlasPlacement,
	pageHeight: Int,
	camera: ViewportCamera,
	size: IntSize,
	color: Color,
	stroke: Stroke,
) {
	val tileToDisplay = tileToDisplayAffine(placement, pageHeight)
	for (contour in contours) {
		if (contour.points.size < 6) {
			continue
		}
		val outline = Path()
		var pointIndex = 0
		while (pointIndex + 1 < contour.points.size) {
			val screen = tileToScreen(contour.points[pointIndex].toFloat(), contour.points[pointIndex + 1].toFloat(), tileToDisplay, camera, size)
			if (pointIndex == 0) {
				outline.moveTo(screen.x, screen.y)
			} else {
				outline.lineTo(screen.x, screen.y)
			}
			pointIndex += 2
		}
		outline.close()
		drawPath(outline, color, style = stroke)
	}
}