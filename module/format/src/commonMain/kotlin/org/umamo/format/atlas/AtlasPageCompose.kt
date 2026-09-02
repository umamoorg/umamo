package org.umamo.format.atlas

import org.umamo.format.art.LayerBounds
import org.umamo.format.raster.RasterImage
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/*
 * Pixel composition for the atlas packer: writing one trimmed tile into a page buffer, optionally
 * quarter-turned, and extruding its edge outward into the surrounding gutter - plus the general
 * affine blit a hand-authored placement needs, since a rigger may rotate or scale a tile on the page
 * where the packer never would.
 *
 * Kept apart from the packing geometry so the blit can be read and tested as pixels, which is where
 * a packer's silent bugs live - an orientation flipped, an extrusion off by one, a stride computed
 * from the wrong width.
 */

/**
 * Copies one trimmed tile into a page buffer.
 *
 * A quarter turn is COUNTER-CLOCKWISE in the page's y-down frame, matching how a placement records
 * rotation: source pixel (tileX, tileY) lands at (destinationX + tileY, destinationY + trimWidth - 1
 * - tileX), so the destination footprint is trimHeight wide by trimWidth tall.
 *
 * @param ByteArray   page         The destination page, RGBA8888 row-major from the top.
 * @param Int         pageWidth    The page width in pixels.
 * @param ByteArray   sourceRgba   The source raster, RGBA8888 row-major from the top.
 * @param Int         sourceWidth  The source raster's width in pixels (its row stride).
 * @param LayerBounds trim         The opaque sub-rectangle of the source to copy, raster-local.
 * @param Int         destinationX The tile's left edge on the page.
 * @param Int         destinationY The tile's top edge on the page.
 * @param Int         quarterTurns 0 for upright, 1 for one counter-clockwise quarter turn.
 */
internal fun blitTile(
	page: ByteArray,
	pageWidth: Int,
	sourceRgba: ByteArray,
	sourceWidth: Int,
	trim: LayerBounds,
	destinationX: Int,
	destinationY: Int,
	quarterTurns: Int,
) {
	if (quarterTurns == 0) {
		// The upright case copies whole rows, which is the overwhelmingly common path and worth
		// keeping off the per-pixel loop below.
		for (rowIndex in 0 until trim.height) {
			val sourceOffset = ((trim.top + rowIndex) * sourceWidth + trim.left) * 4
			val destinationOffset = ((destinationY + rowIndex) * pageWidth + destinationX) * 4
			sourceRgba.copyInto(page, destinationOffset, sourceOffset, sourceOffset + trim.width * 4)
		}
		return
	}
	for (tileY in 0 until trim.height) {
		val sourceRowOffset = ((trim.top + tileY) * sourceWidth + trim.left) * 4
		for (tileX in 0 until trim.width) {
			val sourceOffset = sourceRowOffset + tileX * 4
			val destinationOffset =
				((destinationY + trim.width - 1 - tileX) * pageWidth + destinationX + tileY) * 4
			sourceRgba.copyInto(page, destinationOffset, sourceOffset, sourceOffset + 4)
		}
	}
}

/**
 * Replicates a placed tile's edge pixels outward into the surrounding gutter.
 *
 * Bilinear sampling at a tile's border reads half a texel past it; without this the neighboring
 * tile's artwork bleeds in.  The caller guarantees room by reserving a gutter of at least [extrude]
 * on every side, so no clamping against the page edge is needed here - and a silent clamp would hide
 * exactly the arithmetic mistake this is most likely to make.
 *
 * @param ByteArray page       The destination page, RGBA8888 row-major from the top.
 * @param Int       pageWidth  The page width in pixels.
 * @param Int       tileX      The placed tile's left edge on the page.
 * @param Int       tileY      The placed tile's top edge on the page.
 * @param Int       tileWidth  The placed tile's width on the page (post-rotation).
 * @param Int       tileHeight The placed tile's height on the page (post-rotation).
 * @param Int       extrude    How many pixels of edge color to replicate on every side.
 */
internal fun extrudeTileEdges(
	page: ByteArray,
	pageWidth: Int,
	tileX: Int,
	tileY: Int,
	tileWidth: Int,
	tileHeight: Int,
	extrude: Int,
) {
	if (extrude <= 0 || tileWidth <= 0 || tileHeight <= 0) {
		return
	}
	for (rowOffset in -extrude until tileHeight + extrude) {
		val clampedRow = rowOffset.coerceIn(0, tileHeight - 1)
		val insideVertically = rowOffset == clampedRow
		for (columnOffset in -extrude until tileWidth + extrude) {
			val clampedColumn = columnOffset.coerceIn(0, tileWidth - 1)
			if (insideVertically && columnOffset == clampedColumn) {
				continue
			}
			val sourceOffset = ((tileY + clampedRow) * pageWidth + tileX + clampedColumn) * 4
			val destinationOffset = ((tileY + rowOffset) * pageWidth + tileX + columnOffset) * 4
			page.copyInto(page, destinationOffset, sourceOffset, sourceOffset + 4)
		}
	}
}

/**
 * Composes atlas pages from already-decided placements.
 *
 * This is the packer's own composition loop opened to callers that hold placements rather than a
 * pack request: [packAtlas] composes through here, and a repack's undo re-derives an earlier page
 * set through here, so the two can never drift apart.  Placement footprints including their
 * extrusion are pairwise disjoint (every placement reserves at least [extrude] of gutter on every
 * side, which the bounds checks below enforce against the page and the packer enforced between
 * tiles), so the composition is order-independent.
 *
 * @param IntArray pageWidths  Page widths in pixels, indexed by [AtlasPackPlacement.pageIndex].
 * @param IntArray pageHeights Page heights in pixels, index-parallel to [pageWidths].
 * @param List     items       The tiles' pixels; every placement's key must resolve here.
 * @param List     placements  Where each tile goes.
 * @param Int      extrude     How many pixels of each tile's edge color are replicated into the gutter.
 * @return List The composed pages, RGBA8888, in page-index order.
 */
public fun composeAtlasPages(
	pageWidths: IntArray,
	pageHeights: IntArray,
	items: List<AtlasPackItem>,
	placements: List<AtlasPackPlacement>,
	extrude: Int,
): List<RasterImage> {
	require(pageWidths.size == pageHeights.size) {
		"page width and height lists must be index-parallel: ${pageWidths.size} vs ${pageHeights.size}"
	}
	require(extrude >= 0) { "extrude must be non-negative: $extrude" }
	val itemByKey = items.associateBy { item -> item.key }
	require(itemByKey.size == items.size) { "atlas pack item keys must be unique" }
	val pageBuffers = List(pageWidths.size) { pageIndex -> ByteArray(pageWidths[pageIndex] * pageHeights[pageIndex] * 4) }
	for (placement in placements) {
		val item = requireNotNull(itemByKey[placement.key]) { "placement '${placement.key}' has no item" }
		require(placement.quarterTurns in 0..1) {
			"placement '${placement.key}' has an unsupported rotation: ${placement.quarterTurns} quarter turns"
		}
		require(
			placement.trimLeft >= 0 &&
				placement.trimTop >= 0 &&
				placement.trimLeft + placement.trimWidth <= item.width &&
				placement.trimTop + placement.trimHeight <= item.height,
		) {
			"placement '${placement.key}' trims outside its ${item.width}x${item.height} raster"
		}
		val pageWidth = pageWidths.getOrNull(placement.pageIndex)
		val pageHeight = pageHeights.getOrNull(placement.pageIndex)
		require(pageWidth != null && pageHeight != null) {
			"placement '${placement.key}' names page ${placement.pageIndex} of ${pageWidths.size}"
		}
		require(
			placement.pageX - extrude >= 0 &&
				placement.pageY - extrude >= 0 &&
				placement.pageX + placement.pageWidth + extrude <= pageWidth &&
				placement.pageY + placement.pageHeight + extrude <= pageHeight,
		) {
			"placement '${placement.key}' plus its $extrude px extrusion falls outside its ${pageWidth}x$pageHeight page"
		}
		blitTile(
			page = pageBuffers[placement.pageIndex],
			pageWidth = pageWidth,
			sourceRgba = item.rgba,
			sourceWidth = item.width,
			trim = LayerBounds(placement.trimLeft, placement.trimTop, placement.trimWidth, placement.trimHeight),
			destinationX = placement.pageX,
			destinationY = placement.pageY,
			quarterTurns = placement.quarterTurns,
		)
		extrudeTileEdges(
			page = pageBuffers[placement.pageIndex],
			pageWidth = pageWidth,
			tileX = placement.pageX,
			tileY = placement.pageY,
			tileWidth = placement.pageWidth,
			tileHeight = placement.pageHeight,
			extrude = extrude,
		)
	}
	return pageBuffers.mapIndexed { pageIndex, buffer -> RasterImage(pageWidths[pageIndex], pageHeights[pageIndex], buffer) }
}

/**
 * One tile placed on a page by an arbitrary 2x3 affine over tile pixels - the shape a hand-authored
 * placement takes once a rigger has rotated or scaled it, which [AtlasPackPlacement]'s integer rect
 * plus quarter turn cannot hold.
 *
 * @property String      key        The tile's key into the items list.
 * @property Int         pageIndex  The page the tile lands on.
 * @property LayerBounds trim       The opaque sub-rectangle of the tile's raster that is drawn, raster-local.
 * @property FloatArray  tileToPage The affine (m00, m01, m02, m10, m11, m12) mapping tile pixels to
 *   page pixels, both frames y-down with pixel (x, y) spanning [x, x + 1) by [y, y + 1).
 */
public class AtlasTilePlacement(
	public val key: String,
	public val pageIndex: Int,
	public val trim: LayerBounds,
	public val tileToPage: FloatArray,
) {
	init {
		require(tileToPage.size == 6) { "placement '$key' needs a 2x3 affine, got ${tileToPage.size} components" }
	}
}

/**
 * Composes atlas pages from placements given as affines - [composeAtlasPages] for tiles that may sit
 * rotated, scaled, off the pixel grid, or partly off the page.
 *
 * A placement that is an exact integer translation with room for its extrusion takes the packer's own
 * blit and edge extrusion, so a page composed here from a repack's placements is byte-identical to
 * the page the packer composed.  Every other placement is resampled: each page pixel inside the
 * tile's footprint (its trim rectangle through the affine) reads the tile bilinearly at the pixel
 * center's pre-image, and each page pixel within [extrude] pixels outside the footprint reads the
 * nearest edge of the trim - the packer's edge replication, expressed so it holds under rotation.
 * Pixels that fall off the page are dropped rather than refused: the placement is a rigger's authored
 * choice, and clipping is what the page would show.
 *
 * Unlike the packer's placements, hand-authored footprints may overlap; later placements paint over
 * earlier ones, in list order.
 *
 * @param IntArray pageWidths  Page widths in pixels, indexed by [AtlasTilePlacement.pageIndex].
 * @param IntArray pageHeights Page heights in pixels, index-parallel to [pageWidths].
 * @param List     items       The tiles' pixels; every placement's key must resolve here.
 * @param List     placements  Where each tile goes.
 * @param Int      extrude     How many pixels of each tile's edge color are replicated outward.
 * @return List The composed pages, RGBA8888 straight alpha, in page-index order.
 */
public fun composeAtlasPagesAffine(
	pageWidths: IntArray,
	pageHeights: IntArray,
	items: List<AtlasPackItem>,
	placements: List<AtlasTilePlacement>,
	extrude: Int,
): List<RasterImage> {
	require(pageWidths.size == pageHeights.size) {
		"page width and height lists must be index-parallel: ${pageWidths.size} vs ${pageHeights.size}"
	}
	require(extrude >= 0) { "extrude must be non-negative: $extrude" }
	val itemByKey = items.associateBy { item -> item.key }
	require(itemByKey.size == items.size) { "atlas pack item keys must be unique" }
	val pageBuffers = List(pageWidths.size) { pageIndex -> ByteArray(pageWidths[pageIndex] * pageHeights[pageIndex] * 4) }
	for (placement in placements) {
		val item = requireNotNull(itemByKey[placement.key]) { "placement '${placement.key}' has no item" }
		val trim = placement.trim
		require(
			trim.left >= 0 &&
				trim.top >= 0 &&
				trim.left + trim.width <= item.width &&
				trim.top + trim.height <= item.height,
		) {
			"placement '${placement.key}' trims outside its ${item.width}x${item.height} raster"
		}
		val pageWidth = pageWidths.getOrNull(placement.pageIndex)
		val pageHeight = pageHeights.getOrNull(placement.pageIndex)
		require(pageWidth != null && pageHeight != null) {
			"placement '${placement.key}' names page ${placement.pageIndex} of ${pageWidths.size}"
		}
		if (trim.width <= 0 || trim.height <= 0) {
			continue
		}
		val page = pageBuffers[placement.pageIndex]
		val exactOrigin = exactTrimOrigin(placement)
		if (exactOrigin != null &&
			exactOrigin.first - extrude >= 0 &&
			exactOrigin.second - extrude >= 0 &&
			exactOrigin.first + trim.width + extrude <= pageWidth &&
			exactOrigin.second + trim.height + extrude <= pageHeight
		) {
			blitTile(page, pageWidth, item.rgba, item.width, trim, exactOrigin.first, exactOrigin.second, quarterTurns = 0)
			extrudeTileEdges(page, pageWidth, exactOrigin.first, exactOrigin.second, trim.width, trim.height, extrude)
		} else {
			blitTileAffine(page, pageWidth, pageHeight, item.rgba, item.width, trim, placement.tileToPage, extrude)
		}
	}
	return pageBuffers.mapIndexed { pageIndex, buffer -> RasterImage(pageWidths[pageIndex], pageHeights[pageIndex], buffer) }
}

/**
 * Where the trim's top-left pixel lands when [placement] is an exact integer translation - the one
 * case the packer's row-copying blit serves - else null.
 *
 * @param AtlasTilePlacement placement The placement to classify.
 * @return Pair? The page column and row of the trim origin, or null when the affine rotates, scales,
 *   or lands between pixels.
 */
private fun exactTrimOrigin(placement: AtlasTilePlacement): Pair<Int, Int>? {
	val affine = placement.tileToPage
	if (affine[0] != 1f || affine[1] != 0f || affine[3] != 0f || affine[4] != 1f) {
		return null
	}
	val originX = affine[2] + placement.trim.left
	val originY = affine[5] + placement.trim.top
	val pixelX = originX.roundToInt()
	val pixelY = originY.roundToInt()
	if (pixelX.toFloat() != originX || pixelY.toFloat() != originY) {
		return null
	}
	return pixelX to pixelY
}

/**
 * Inverts a 2x3 affine, or null when it is singular.
 *
 * @param FloatArray affine The affine (m00, m01, m02, m10, m11, m12).
 * @return FloatArray? The inverse.
 */
private fun invertAffine2x3(affine: FloatArray): FloatArray? {
	val determinant = affine[0] * affine[4] - affine[1] * affine[3]
	if (determinant == 0f) {
		return null
	}
	val m00 = affine[4] / determinant
	val m01 = -affine[1] / determinant
	val m10 = -affine[3] / determinant
	val m11 = affine[0] / determinant
	return floatArrayOf(
		m00,
		m01,
		-(m00 * affine[2] + m01 * affine[5]),
		m10,
		m11,
		-(m10 * affine[2] + m11 * affine[5]),
	)
}

/**
 * Resamples one trimmed tile onto a page through an affine, clipping to the page.
 *
 * Runs over the footprint's bounding box grown by [extrude] and inverse-maps each page pixel center
 * into the tile.  A center inside the trim samples the tile bilinearly there; a center outside it is
 * clamped to the trim's nearest point and drawn only when that point, mapped back to the page, lies
 * within [extrude] pixels - which paints the packer's edge extrusion as a band of constant width around
 * the footprint whatever its orientation, and leaves everything farther out untouched.
 *
 * @param ByteArray   page        The destination page, RGBA8888 row-major from the top.
 * @param Int         pageWidth   The page width in pixels.
 * @param Int         pageHeight  The page height in pixels.
 * @param ByteArray   sourceRgba  The source raster, RGBA8888 row-major from the top.
 * @param Int         sourceWidth The source raster's width in pixels (its row stride).
 * @param LayerBounds trim        The opaque sub-rectangle of the source to draw, raster-local.
 * @param FloatArray  tileToPage  The affine mapping tile pixels to page pixels.
 * @param Int         extrude     How many pixels of edge color to replicate outward.
 */
internal fun blitTileAffine(
	page: ByteArray,
	pageWidth: Int,
	pageHeight: Int,
	sourceRgba: ByteArray,
	sourceWidth: Int,
	trim: LayerBounds,
	tileToPage: FloatArray,
	extrude: Int,
) {
	val pageToTile = invertAffine2x3(tileToPage) ?: return
	val trimLeft = trim.left.toFloat()
	val trimTop = trim.top.toFloat()
	val trimRight = (trim.left + trim.width).toFloat()
	val trimBottom = (trim.top + trim.height).toFloat()
	// The footprint's page-space bounding box: the four trim corners through the affine.
	var minX = Float.POSITIVE_INFINITY
	var minY = Float.POSITIVE_INFINITY
	var maxX = Float.NEGATIVE_INFINITY
	var maxY = Float.NEGATIVE_INFINITY
	for (cornerIndex in 0 until 4) {
		val cornerX = if (cornerIndex and 1 == 0) trimLeft else trimRight
		val cornerY = if (cornerIndex and 2 == 0) trimTop else trimBottom
		val pageX = tileToPage[0] * cornerX + tileToPage[1] * cornerY + tileToPage[2]
		val pageY = tileToPage[3] * cornerX + tileToPage[4] * cornerY + tileToPage[5]
		minX = minOf(minX, pageX)
		minY = minOf(minY, pageY)
		maxX = maxOf(maxX, pageX)
		maxY = maxOf(maxY, pageY)
	}
	val startColumn = floor(minX - extrude).toInt().coerceAtLeast(0)
	val endColumn = ceil(maxX + extrude).toInt().coerceAtMost(pageWidth)
	val startRow = floor(minY - extrude).toInt().coerceAtLeast(0)
	val endRow = ceil(maxY + extrude).toInt().coerceAtMost(pageHeight)
	if (startColumn >= endColumn || startRow >= endRow) {
		return
	}
	val extrudeSquared = extrude.toFloat() * extrude.toFloat()
	val lastColumn = trim.left + trim.width - 1
	val lastRow = trim.top + trim.height - 1
	for (pageRow in startRow until endRow) {
		val centerY = pageRow + 0.5f
		for (pageColumn in startColumn until endColumn) {
			val centerX = pageColumn + 0.5f
			val tileX = pageToTile[0] * centerX + pageToTile[1] * centerY + pageToTile[2]
			val tileY = pageToTile[3] * centerX + pageToTile[4] * centerY + pageToTile[5]
			val clampedX = tileX.coerceIn(trimLeft, trimRight)
			val clampedY = tileY.coerceIn(trimTop, trimBottom)
			if (clampedX != tileX || clampedY != tileY) {
				if (extrude == 0) {
					continue
				}
				val backX = tileToPage[0] * clampedX + tileToPage[1] * clampedY + tileToPage[2]
				val backY = tileToPage[3] * clampedX + tileToPage[4] * clampedY + tileToPage[5]
				val deltaX = backX - centerX
				val deltaY = backY - centerY
				if (deltaX * deltaX + deltaY * deltaY > extrudeSquared) {
					continue
				}
			}
			sampleBilinearStraight(
				sourceRgba,
				sourceWidth,
				trim.left,
				trim.top,
				lastColumn,
				lastRow,
				clampedX,
				clampedY,
				page,
				(pageRow * pageWidth + pageColumn) * 4,
			)
		}
	}
}

/**
 * Writes the tile's color at a continuous tile-space point into a page pixel, bilinearly over the
 * four surrounding texels with the taps clamped into the trim.
 *
 * Straight-alpha texels interpolate in premultiplied space and convert back: averaging straight RGB
 * across an alpha edge drags the matte color into the visible pixels and darkens the fringe.  A point
 * that sits exactly on a texel center copies that texel verbatim, so an integer-aligned placement
 * (a quarter turn, an exact translation the packer's blit could not take) stays byte-exact.
 *
 * @param ByteArray source            The source raster, RGBA8888 row-major from the top.
 * @param Int       sourceWidth       The source raster's row stride in pixels.
 * @param Int       firstColumn       The trim's first column (taps clamp here).
 * @param Int       firstRow          The trim's first row.
 * @param Int       lastColumn        The trim's last column, inclusive.
 * @param Int       lastRow           The trim's last row, inclusive.
 * @param Float     sampleX           The tile-space x to sample (texel (x, y) spans [x, x + 1)).
 * @param Float     sampleY           The tile-space y to sample.
 * @param ByteArray destination       The page buffer.
 * @param Int       destinationOffset The page pixel's byte offset.
 */
private fun sampleBilinearStraight(
	source: ByteArray,
	sourceWidth: Int,
	firstColumn: Int,
	firstRow: Int,
	lastColumn: Int,
	lastRow: Int,
	sampleX: Float,
	sampleY: Float,
	destination: ByteArray,
	destinationOffset: Int,
) {
	val x = sampleX - 0.5f
	val y = sampleY - 0.5f
	val floorX = floor(x)
	val floorY = floor(y)
	val fractionX = x - floorX
	val fractionY = y - floorY
	val column0 = floorX.toInt().coerceIn(firstColumn, lastColumn)
	val row0 = floorY.toInt().coerceIn(firstRow, lastRow)
	if (fractionX == 0f && fractionY == 0f) {
		val sourceOffset = (row0 * sourceWidth + column0) * 4
		source.copyInto(destination, destinationOffset, sourceOffset, sourceOffset + 4)
		return
	}
	val column1 = (floorX.toInt() + 1).coerceIn(firstColumn, lastColumn)
	val row1 = (floorY.toInt() + 1).coerceIn(firstRow, lastRow)
	val weight00 = (1f - fractionX) * (1f - fractionY)
	val weight10 = fractionX * (1f - fractionY)
	val weight01 = (1f - fractionX) * fractionY
	val weight11 = fractionX * fractionY
	var red = 0f
	var green = 0f
	var blue = 0f
	var alpha = 0f
	var straightRed = 0f
	var straightGreen = 0f
	var straightBlue = 0f
	for (tapIndex in 0 until 4) {
		val column = if (tapIndex and 1 == 0) column0 else column1
		val row = if (tapIndex and 2 == 0) row0 else row1
		val weight =
			when (tapIndex) {
				0 -> weight00
				1 -> weight10
				2 -> weight01
				else -> weight11
			}
		if (weight == 0f) {
			continue
		}
		val offset = (row * sourceWidth + column) * 4
		val tapRed = (source[offset].toInt() and 0xFF).toFloat()
		val tapGreen = (source[offset + 1].toInt() and 0xFF).toFloat()
		val tapBlue = (source[offset + 2].toInt() and 0xFF).toFloat()
		val tapAlpha = (source[offset + 3].toInt() and 0xFF).toFloat()
		red += weight * tapRed * tapAlpha
		green += weight * tapGreen * tapAlpha
		blue += weight * tapBlue * tapAlpha
		alpha += weight * tapAlpha
		straightRed += weight * tapRed
		straightGreen += weight * tapGreen
		straightBlue += weight * tapBlue
	}
	if (alpha > 0f) {
		destination[destinationOffset] = (red / alpha).roundToInt().coerceIn(0, 255).toByte()
		destination[destinationOffset + 1] = (green / alpha).roundToInt().coerceIn(0, 255).toByte()
		destination[destinationOffset + 2] = (blue / alpha).roundToInt().coerceIn(0, 255).toByte()
	} else {
		// Fully transparent: keep the matte color the source carries rather than inventing black.
		destination[destinationOffset] = straightRed.roundToInt().coerceIn(0, 255).toByte()
		destination[destinationOffset + 1] = straightGreen.roundToInt().coerceIn(0, 255).toByte()
		destination[destinationOffset + 2] = straightBlue.roundToInt().coerceIn(0, 255).toByte()
	}
	destination[destinationOffset + 3] = alpha.roundToInt().coerceIn(0, 255).toByte()
}