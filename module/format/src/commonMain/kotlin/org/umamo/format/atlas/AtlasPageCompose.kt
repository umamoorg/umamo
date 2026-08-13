package org.umamo.format.atlas

import org.umamo.format.art.LayerBounds

/*
 * Pixel composition for the atlas packer: writing one trimmed tile into a page buffer, optionally
 * quarter-turned, and extruding its edge outward into the surrounding gutter.
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
 * Bilinear sampling at a tile's border reads half a texel past it; without this the neighbouring
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
 * @param Int       extrude    How many pixels of edge colour to replicate on every side.
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