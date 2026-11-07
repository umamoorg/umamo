package org.umamo.format.clip

import org.umamo.format.binary.deflateZlib
import org.umamo.format.binary.encodeUtf16Be
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Layer-mask decoding against hand-assembled tiled block data: a two-tile opaque layer whose mask
 * stores a tile over column 0 and nothing over column 1, so the empty mask tile's value comes from
 * the mask Offscreen.Attribute's InitColor alone.
 */
class ClipRasterMaskTest {
	private val tileSide = 256
	private val tilePixels = tileSide * tileSide

	// The one stored mask tile's uniform value: half revealed.
	private val storedMaskValue = 128

	/**
	 * Encodes a big-endian u32.
	 *
	 * @param Int value The value to encode.
	 * @return ByteArray The four bytes, most significant first.
	 */
	private fun uint32BigEndian(value: Int): ByteArray =
		byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

	/**
	 * Encodes a little-endian u32 (the tile body's zlib-length prefix).
	 *
	 * @param Int value The value to encode.
	 * @return ByteArray The four bytes, least significant first.
	 */
	private fun uint32LittleEndian(value: Int): ByteArray =
		byteArrayOf(value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte(), (value ushr 24).toByte())

	/**
	 * Encodes a length-prefixed UTF-16BE property or block-data name.
	 *
	 * @param String name The name.
	 * @return ByteArray The u32 code-unit count, then the UTF-16BE name.
	 */
	private fun namedEntry(name: String): ByteArray = uint32BigEndian(name.length) + encodeUtf16Be(name)

	/**
	 * Builds an Offscreen.Attribute carrying the tile grid and, optionally, an InitColor record.
	 *
	 * @param Int columns          The tile-grid columns.
	 * @param Int rows             The tile-grid rows.
	 * @param Int? initEnabled     The InitColor enabled word, or null to omit the record.
	 * @param Int initColor        The InitColor color word.
	 * @return ByteArray The attribute bytes.
	 */
	private fun attribute(columns: Int, rows: Int, initEnabled: Int?, initColor: Int = 0): ByteArray {
		// CLIP: Offscreen.Attribute "Parameter" - padded width, padded height, cols, rows (u32 BE).
		var bytes =
			namedEntry("Parameter") +
				uint32BigEndian(columns * tileSide) +
				uint32BigEndian(rows * tileSide) +
				uint32BigEndian(columns) +
				uint32BigEndian(rows)

		if (initEnabled != null) {
			// CLIP: Offscreen.Attribute "InitColor" - u32 size (20), u32 enabled, u32 color, two more u32s.
			bytes += namedEntry("InitColor") +
				uint32BigEndian(20) +
				uint32BigEndian(initEnabled) +
				uint32BigEndian(initColor) +
				uint32BigEndian(0) +
				uint32BigEndian(4)
		}

		return bytes
	}

	/**
	 * Builds one tagged block-data record header: a size word, then the length-prefixed tag.
	 *
	 * @param String tag The block-data tag.
	 * @return ByteArray The record header bytes.
	 */
	private fun blockTag(tag: String): ByteArray = uint32BigEndian(0) + namedEntry(tag)

	/**
	 * Builds a BlockDataBeginChunk record (plus its end record) for one tile.
	 *
	 * @param Int blockIndex     The tile's grid index.
	 * @param ByteArray? pixels  The inflated tile bytes, or null for a tile flagged empty.
	 * @return ByteArray The tile's records.
	 */
	private fun tileRecord(blockIndex: Int, pixels: ByteArray?): ByteArray {
		// CLIP: BlockDataBeginChunk - block index, 12 unknown bytes, has-data flag, then the tile.
		var bytes = blockTag("BlockDataBeginChunk") + uint32BigEndian(blockIndex) + ByteArray(12)

		if (pixels == null) {
			bytes += uint32BigEndian(0)
		} else {
			val stream = deflateZlib(pixels)
			// CLIP: tile body - [u32 LE zlib length][zlib stream].
			val body = uint32LittleEndian(stream.size) + stream
			bytes += uint32BigEndian(1) + uint32BigEndian(body.size) + body
		}

		return bytes + blockTag("BlockDataEndChunk")
	}

	/**
	 * Wraps tile records in a CHNKExta chunk.
	 *
	 * @param List records The tiles' block-data records, in grid order.
	 * @return ByteArray The chunk bytes.
	 */
	private fun externalChunk(records: List<ByteArray>): ByteArray {
		val blockData = records.fold(ByteArray(0)) { accumulated, record -> accumulated + record }
		// CLIP: CHNKExta - 8-byte id, u64 size, then a 56-byte external header before the block data.
		val payload = ByteArray(56) + blockData

		return "CHNKExta".encodeToByteArray() + ByteArray(4) + uint32BigEndian(payload.size) + payload
	}

	/**
	 * An opaque RGBA tile: the alpha plane, then interleaved B, G, R, unused.
	 *
	 * @return ByteArray The inflated tile bytes.
	 */
	private fun opaqueColorTile(): ByteArray {
		val tile = ByteArray(tilePixels * 5)
		tile.fill(0xFF.toByte(), 0, tilePixels)

		for (pixelIndex in 0 until tilePixels) {
			val colorBase = tilePixels + pixelIndex * 4
			tile[colorBase] = 10
			tile[colorBase + 1] = 20
			tile[colorBase + 2] = 30
		}

		return tile
	}

	/**
	 * Decodes the two-tile layer, its mask (if any) storing only column 0.
	 *
	 * @param ByteArray? maskAttribute The mask Offscreen.Attribute, or null for a layer with no mask.
	 * @return ClipDecodedRaster The decoded raster.
	 */
	private fun decodeTwoTileLayer(maskAttribute: ByteArray?): ClipDecodedRaster {
		val colorChunk = externalChunk(listOf(tileRecord(0, opaqueColorTile()), tileRecord(1, opaqueColorTile())))
		val maskChunk =
			externalChunk(listOf(tileRecord(0, ByteArray(tilePixels) { storedMaskValue.toByte() }), tileRecord(1, null)))
		val decoded =
			ClipRaster.decodeLayer(
				fileBytes = colorChunk + maskChunk,
				colorChunkOffset = 0,
				colorAttribute = attribute(columns = 2, rows = 1, initEnabled = 0),
				maskChunkOffset = if (maskAttribute != null) colorChunk.size else -1,
				maskAttribute = maskAttribute,
				anchorX = 0,
				anchorY = 0,
			)

		return assertNotNull(decoded, "the two opaque tiles decode")
	}

	/**
	 * Reads one decoded pixel's alpha.
	 *
	 * @param ClipDecodedRaster decoded The decoded raster.
	 * @param Int x                     The pixel column.
	 * @param Int y                     The pixel row.
	 * @return Int The 0..255 alpha.
	 */
	private fun alphaAt(decoded: ClipDecodedRaster, x: Int, y: Int): Int =
		decoded.raster.rgba[(y * decoded.raster.width + x) * 4 + 3].toInt() and 0xFF

	/** A mask whose InitColor is disabled hides the tiles it never stored. */
	@Test
	fun disabledInitColorHidesEmptyMaskTiles() {
		val decoded = decodeTwoTileLayer(attribute(columns = 2, rows = 1, initEnabled = 0, initColor = 0))

		assertEquals(512, decoded.raster.width)
		assertEquals(storedMaskValue, alphaAt(decoded, 10, 10), "the stored mask tile scales alpha")
		assertEquals(0, alphaAt(decoded, 300, 10), "the empty mask tile hides")
		assertEquals(30, decoded.raster.rgba[(10 * 512 + 300) * 4].toInt() and 0xFF, "color survives a hidden pixel")
	}

	/** A mask whose InitColor is enabled and all-bits-set reveals the tiles it never stored. */
	@Test
	fun enabledInitColorRevealsEmptyMaskTiles() {
		val decoded = decodeTwoTileLayer(attribute(columns = 2, rows = 1, initEnabled = 1, initColor = -1))

		assertEquals(storedMaskValue, alphaAt(decoded, 10, 10))
		assertEquals(255, alphaAt(decoded, 300, 10), "the empty mask tile reveals")
	}

	/** A mask Attribute without a readable InitColor reveals rather than hiding art. */
	@Test
	fun missingInitColorRevealsEmptyMaskTiles() {
		val decoded = decodeTwoTileLayer(attribute(columns = 2, rows = 1, initEnabled = null))

		assertEquals(storedMaskValue, alphaAt(decoded, 10, 10))
		assertEquals(255, alphaAt(decoded, 300, 10))
	}

	/** A layer with no mask keeps its own alpha everywhere. */
	@Test
	fun unmaskedLayerStaysOpaque() {
		val decoded = decodeTwoTileLayer(maskAttribute = null)

		assertEquals(255, alphaAt(decoded, 10, 10))
		assertEquals(255, alphaAt(decoded, 300, 10))
	}
}