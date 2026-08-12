package org.umamo.format.atlas

import org.umamo.format.raster.RasterImage
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * Shared fixtures and assertions for the atlas packer suites.
 *
 * Deliberately not reusing the alpha-analysis support: its rasters fill every pixel with the same
 * RGB, which is right for coverage tests and useless here - a transposed or mirrored blit would
 * compare equal against itself.  These fixtures make every pixel distinguishable instead.
 */

/**
 * Builds a pack item from rows of character art: '#' is alpha 255, '.' is alpha 0, and a decimal
 * digit is that literal alpha value.
 *
 * RGB carries the pixel's own coordinates plus a per-item tint, so a blit that transposes, mirrors,
 * or reads the wrong row stride produces mismatching bytes rather than accidentally-equal ones.
 *
 * @param String key  The item key; its first character tints the blue channel.
 * @param String rows One string per pixel row, top row first, all the same length.
 * @return AtlasPackItem The synthetic tile.
 */
internal fun packItemOfRows(key: String, vararg rows: String): AtlasPackItem {
	val height = rows.size
	val width = if (height == 0) 0 else rows[0].length
	val rgba = ByteArray(width * height * 4)
	for (rowIndex in 0 until height) {
		require(rows[rowIndex].length == width) { "all rows must share one width" }
		for (columnIndex in 0 until width) {
			val cell = rows[rowIndex][columnIndex]
			val alpha =
				when (cell) {
					'#' -> 255
					'.' -> 0
					in '0'..'9' -> cell - '0'
					else -> throw IllegalArgumentException("unknown cell '$cell'")
				}
			val byteIndex = (rowIndex * width + columnIndex) * 4
			rgba[byteIndex] = (columnIndex + 1).toByte()
			rgba[byteIndex + 1] = (rowIndex + 1).toByte()
			rgba[byteIndex + 2] = key[0].code.toByte()
			rgba[byteIndex + 3] = alpha.toByte()
		}
	}
	return AtlasPackItem(key, width, height, rgba)
}

/**
 * Builds a fully opaque pack item of the given size, with the same per-pixel RGB scheme.
 *
 * @param String key    The item key.
 * @param Int width     The tile width.
 * @param Int height    The tile height.
 * @return AtlasPackItem The synthetic tile.
 */
internal fun opaquePackItem(key: String, width: Int, height: Int): AtlasPackItem =
	packItemOfRows(key, *Array(height) { "#".repeat(width) })

/**
 * Reads one page pixel as a packed 0xRRGGBBAA integer.
 *
 * @param RasterImage page The page to read.
 * @param Int x            The pixel column.
 * @param Int y            The pixel row.
 * @return Int The packed RGBA value.
 */
internal fun pagePixel(page: RasterImage, x: Int, y: Int): Int {
	val byteIndex = (y * page.width + x) * 4
	return ((page.rgba[byteIndex].toInt() and 0xFF) shl 24) or
		((page.rgba[byteIndex + 1].toInt() and 0xFF) shl 16) or
		((page.rgba[byteIndex + 2].toInt() and 0xFF) shl 8) or
		(page.rgba[byteIndex + 3].toInt() and 0xFF)
}

/**
 * Reads one source-tile pixel as a packed 0xRRGGBBAA integer.
 *
 * @param AtlasPackItem item The source tile.
 * @param Int x              The pixel column.
 * @param Int y              The pixel row.
 * @return Int The packed RGBA value.
 */
internal fun itemPixel(item: AtlasPackItem, x: Int, y: Int): Int {
	val byteIndex = (y * item.width + x) * 4
	return ((item.rgba[byteIndex].toInt() and 0xFF) shl 24) or
		((item.rgba[byteIndex + 1].toInt() and 0xFF) shl 16) or
		((item.rgba[byteIndex + 2].toInt() and 0xFF) shl 8) or
		(item.rgba[byteIndex + 3].toInt() and 0xFF)
}

/**
 * Where a tile pixel lands on its page, applying the placement's quarter turn.
 *
 * This is the reader half of the packer's own blit, written independently so a wrong rotation
 * convention cannot agree with itself.
 *
 * @param AtlasPackPlacement placement The placement to read through.
 * @param Int tileX  The pixel's column within the trimmed tile.
 * @param Int tileY  The pixel's row within the trimmed tile.
 * @return Pair The page column and row.
 */
internal fun pageCoordinateOf(placement: AtlasPackPlacement, tileX: Int, tileY: Int): Pair<Int, Int> =
	if (placement.quarterTurns == 0) {
		(placement.pageX + tileX) to (placement.pageY + tileY)
	} else {
		(placement.pageX + tileY) to (placement.pageY + placement.trimWidth - 1 - tileX)
	}

/**
 * Asserts every packed tile reads back out of its page byte-for-byte against its source pixels.
 *
 * This is the packer's central correctness claim: the atlas is a repackable indirection over the
 * source art, so every opaque source pixel must survive the trip into the page unchanged.
 *
 * @param List items             The tiles that were packed, by key.
 * @param AtlasPackResult result The packing outcome to verify.
 */
internal fun assertTilesRoundTripByteExact(items: List<AtlasPackItem>, result: AtlasPackResult) {
	val itemsByKey = items.associateBy { item -> item.key }
	for (placement in result.placements) {
		val item = itemsByKey.getValue(placement.key)
		val page = result.pages[placement.pageIndex]
		for (tileY in 0 until placement.trimHeight) {
			for (tileX in 0 until placement.trimWidth) {
				val (pageX, pageY) = pageCoordinateOf(placement, tileX, tileY)
				assertEquals(
					itemPixel(item, placement.trimLeft + tileX, placement.trimTop + tileY),
					pagePixel(page, pageX, pageY),
					"'${placement.key}' tile pixel ($tileX, $tileY) at page ($pageX, $pageY)",
				)
			}
		}
	}
}

/**
 * Asserts no two tiles come within the gutter of each other, and every gutter footprint fits its page.
 *
 * Expanding each tile by the gutter reconstructs the footprint the packer reserved, so overlap here
 * is exactly the failure that lets one tile's extrusion write over another's artwork.
 *
 * @param AtlasPackResult result The packing outcome to verify.
 * @param Int gutter             The gutter the pack ran with.
 */
internal fun assertNoTileOverlap(result: AtlasPackResult, gutter: Int) {
	for (placement in result.placements) {
		val page = result.pages[placement.pageIndex]
		assertTrue(
			placement.pageX - gutter >= 0 && placement.pageY - gutter >= 0,
			"'${placement.key}' footprint starts outside its page at (${placement.pageX}, ${placement.pageY})",
		)
		assertTrue(
			placement.pageX + placement.pageWidth + gutter <= page.width &&
				placement.pageY + placement.pageHeight + gutter <= page.height,
			"'${placement.key}' footprint runs past its ${page.width}x${page.height} page",
		)
	}
	for (firstIndex in result.placements.indices) {
		for (secondIndex in firstIndex + 1 until result.placements.size) {
			val first = result.placements[firstIndex]
			val second = result.placements[secondIndex]
			if (first.pageIndex != second.pageIndex) {
				continue
			}
			val separated =
				first.pageX + first.pageWidth + gutter <= second.pageX - gutter ||
					second.pageX + second.pageWidth + gutter <= first.pageX - gutter ||
					first.pageY + first.pageHeight + gutter <= second.pageY - gutter ||
					second.pageY + second.pageHeight + gutter <= first.pageY - gutter
			assertTrue(separated, "'${first.key}' and '${second.key}' overlap within the gutter")
		}
	}
}