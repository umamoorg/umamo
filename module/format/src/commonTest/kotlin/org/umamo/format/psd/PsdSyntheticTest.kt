package org.umamo.format.psd

import org.umamo.format.raster.ByteBuilder
import org.umamo.format.raster.deflateZlib
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Round-trips synthetic, hand-built PSD files through [PsdReader] to verify the channel decoder
 * exactly, without needing a committed corpus or a reference reader for comparison.
 *
 * Each test constructs a single-layer RGBA PSD with known per-channel planes, encodes the channels
 * with one of the three compressions a real `.psd` uses (raw, PackBits RLE, ZIP), and asserts the
 * decoded RGBA is bit-exact and the geometry is correct.  Together they cover the riskiest parts of
 * the port: the channel-data offset math, every decompressor, the RGB+alpha assembly, and the
 * lyid-or-name+order stable identity.
 */
class PsdSyntheticTest {
	private val compressionRaw = 0
	private val compressionRle = 1
	private val compressionZip = 2

	/**
	 * Compresses [data] to a zlib stream, matching the ZIP channel framing the reader inflates.
	 *
	 * @param ByteArray data The bytes to compress.
	 * @return ByteArray The zlib-wrapped deflate stream.
	 */
	private fun deflate(data: ByteArray): ByteArray = deflateZlib(data)

	/**
	 * PackBits-encodes one row as a single literal run (valid for rows up to 128 bytes).
	 *
	 * @param ByteArray row The raw row bytes.
	 * @return ByteArray The PackBits-encoded row (a 1-byte control of size-1, then the bytes).
	 */
	private fun packBitsRow(row: ByteArray): ByteArray {
		val out = ByteBuilder()
		out.writeByte(row.size - 1) // control n: copy the next n+1 literal bytes
		out.writeBytes(row)
		return out.toByteArray()
	}

	/**
	 * Encodes one channel's plane into its on-disk block: a 2-byte compression code then the
	 * (optionally compressed) sample bytes, with the per-row byte-count table for RLE.
	 *
	 * @param ByteArray plane     The width*height raw 8-bit samples.
	 * @param Int width           Row width in pixels.
	 * @param Int height          Row count.
	 * @param Int compression     The PSD compression code to apply.
	 * @return ByteArray The complete channel block.
	 */
	private fun encodeChannel(plane: ByteArray, width: Int, height: Int, compression: Int): ByteArray {
		val out = BigEndianWriter()
		out.writeShort(compression)
		when (compression) {
			compressionRaw -> out.writeBytes(plane)
			compressionRle -> {
				val encodedRows = (0 until height).map { rowIndex -> packBitsRow(plane.copyOfRange(rowIndex * width, rowIndex * width + width)) }
				for (encoded in encodedRows) {
					out.writeShort(encoded.size) // byte-count table entry
				}
				for (encoded in encodedRows) {
					out.writeBytes(encoded)
				}
			}

			compressionZip -> out.writeBytes(deflate(plane))
		}
		return out.toByteArray()
	}

	/**
	 * Builds an additional-layer-info `lyid` block (8BIM signature, lyid key, u32 length 4, the id).
	 *
	 * @param Int layerId The Photoshop layer id to embed.
	 * @return ByteArray The 16-byte lyid block.
	 */
	private fun lyidBlock(layerId: Int): ByteArray {
		val out = BigEndianWriter()
		out.writeAscii("8BIM")
		out.writeAscii("lyid")
		out.writeInt(4)
		out.writeInt(layerId)
		return out.toByteArray()
	}

	/**
	 * Builds a minimal valid version-1 RGB PSD with a single layer carrying the given channel planes,
	 * optionally embedding a `lyid` block so the stable-id path can be exercised.
	 *
	 * @param Int width           Canvas/layer width.
	 * @param Int height          Canvas/layer height.
	 * @param String name         Layer name.
	 * @param ByteArray red       Red plane (width*height).
	 * @param ByteArray green     Green plane.
	 * @param ByteArray blue      Blue plane.
	 * @param ByteArray alpha     Alpha plane.
	 * @param Int compression     The compression to apply to every channel.
	 * @param Int? layerId        The lyid to embed, or null to omit the block.
	 * @return ByteArray The complete `.psd` bytes.
	 */
	private fun buildRgbPsd(
		width: Int,
		height: Int,
		name: String,
		red: ByteArray,
		green: ByteArray,
		blue: ByteArray,
		alpha: ByteArray,
		compression: Int,
		layerId: Int? = null,
	): ByteArray {
		val channels = listOf(0 to red, 1 to green, 2 to blue, -1 to alpha)
		val blocks = channels.map { (id, plane) -> id to encodeChannel(plane, width, height, compression) }

		val out = BigEndianWriter()

		// File Header
		out.writeAscii("8BPS")
		out.writeShort(1) // version 1 (PSD)
		out.writeBytes(ByteArray(6)) // reserved
		out.writeShort(3) // composite channel count
		out.writeInt(height)
		out.writeInt(width)
		out.writeShort(8) // depth
		out.writeShort(3) // mode = RGB

		out.writeInt(0) // Color Mode Data length
		out.writeInt(0) // Image Resources length
		out.writeInt(0) // Layer and Mask Information length (ignored by the reader)
		out.writeInt(0) // Layer Info length (ignored by the reader)
		out.writeShort(1) // layer count

		// Layer record
		out.writeInt(0) // top
		out.writeInt(0) // left
		out.writeInt(height) // bottom
		out.writeInt(width) // right
		out.writeShort(channels.size)
		for ((id, block) in blocks) {
			out.writeShort(id)
			out.writeInt(block.size)
		}
		out.writeAscii("8BIM")
		out.writeAscii("norm")
		out.writeByte(255) // opacity
		out.writeByte(0) // clipping
		out.writeByte(0) // flags (visible)
		out.writeByte(0) // filler
		// UTF-8, which is what Photoshop writes into the legacy Pascal-string name and what the reader
		// decodes.  (Also keeps this out of kotlin.text.Charsets, which is JVM-only.)
		val nameBytes = name.encodeToByteArray()
		val additional = if (layerId != null) lyidBlock(layerId) else ByteArray(0)
		out.writeInt(4 + 4 + 1 + nameBytes.size + additional.size) // extra data: mask + ranges + name + additional info
		out.writeInt(0) // layer mask data length
		out.writeInt(0) // blending ranges length
		out.writeByte(nameBytes.size)
		out.writeBytes(nameBytes)
		out.writeBytes(additional)

		// Channel image data, in channel-info order.
		for ((_, block) in blocks) {
			out.writeBytes(block)
		}
		return out.toByteArray()
	}

	private val width = 2
	private val height = 2

	// Distinct per-channel values (all <= 127 so they are valid Byte literals), row-major from top.
	private val red = byteArrayOf(10, 50, 90, 120)
	private val green = byteArrayOf(20, 60, 100, 121)
	private val blue = byteArrayOf(30, 70, 110, 122)
	private val alpha = byteArrayOf(40, 80, 115, 123)
	private val expectedRgba =
		byteArrayOf(
			10,
			20,
			30,
			40,
			50,
			60,
			70,
			80,
			90,
			100,
			110,
			115,
			120,
			121,
			122,
			123,
		)

	/**
	 * Decodes a synthetic PSD and asserts the single layer's geometry, pixels, and stable id.
	 *
	 * @param Int compression The compression the channels were encoded with.
	 * @param Int? layerId     The lyid embedded in the file, or null for the name+order fallback.
	 */
	private fun assertDecodes(compression: Int, layerId: Int? = null) {
		val bytes = buildRgbPsd(width, height, "Layer", red, green, blue, alpha, compression, layerId)
		val art = PsdReader.read(bytes)

		assertEquals(width, art.widthPx, "canvas width")
		assertEquals(height, art.heightPx, "canvas height")
		assertEquals(1, art.layers.size, "one layer")

		val layer = art.layers.single()
		assertEquals(width, layer.bounds.width, "layer width")
		assertEquals(height, layer.bounds.height, "layer height")
		assertEquals(width, layer.raster.width, "raster width")
		assertEquals(height, layer.raster.height, "raster height")
		assertContentEquals(expectedRgba, layer.raster.rgba, "decoded RGBA (compression $compression)")

		val expectedId = layerId?.let { "lyid:$it" } ?: "Layer#0"
		assertEquals(expectedId, layer.id.raw, "stable layer id")
	}

	@Test
	fun decodesRawRgbaLayer() {
		assertDecodes(compressionRaw)
	}

	@Test
	fun decodesRleRgbaLayer() {
		assertDecodes(compressionRle)
	}

	@Test
	fun decodesZipRgbaLayer() {
		assertDecodes(compressionZip)
	}

	@Test
	fun usesLyidAsStableIdWhenPresent() {
		assertDecodes(compressionRaw, layerId = 42)
	}

	/**
	 * A non-ASCII layer name survives the round trip.
	 *
	 * The audience is heavily Japanese, so this is the common case, not an exotic one.  It is a
	 * regression test: the name was briefly decoded byte-per-character (Latin-1), which turned 顔
	 * into three mojibake characters — and because a PSD without a lyid falls back to name+order for
	 * its stable id, that corruption reached the re-import join key too, not just the label.
	 */
	@Test
	fun decodesNonAsciiLayerName() {
		val japaneseName = "顔レイヤー"
		val bytes = buildRgbPsd(width, height, japaneseName, red, green, blue, alpha, compressionRaw)
		val layer = PsdReader.read(bytes).layers.single()

		assertEquals(japaneseName, layer.name, "layer name")
		assertEquals("$japaneseName#0", layer.id.raw, "name+order stable id")
	}

	/**
	 * A big-endian byte writer standing in for java.io.DataOutputStream, so this test can live in
	 * commonTest and run on every target.  PSD is big-endian throughout, so no byte order is exposed.
	 */
	private class BigEndianWriter {
		private val buffer = ByteBuilder()

		/**
		 * Appends the low 8 bits of [value].
		 *
		 * @param Int value The byte to append.
		 */
		fun writeByte(value: Int) = buffer.writeByte(value)

		/**
		 * Appends [value] as a big-endian 16-bit field.
		 *
		 * @param Int value The value to append.
		 */
		fun writeShort(value: Int) {
			buffer.writeByte((value ushr 8) and 0xFF)
			buffer.writeByte(value and 0xFF)
		}

		/**
		 * Appends [value] as a big-endian 32-bit field.
		 *
		 * @param Int value The value to append.
		 */
		fun writeInt(value: Int) {
			buffer.writeByte((value ushr 24) and 0xFF)
			buffer.writeByte((value ushr 16) and 0xFF)
			buffer.writeByte((value ushr 8) and 0xFF)
			buffer.writeByte(value and 0xFF)
		}

		/**
		 * Appends every byte of [bytes].
		 *
		 * @param ByteArray bytes The bytes to append.
		 */
		fun writeBytes(bytes: ByteArray) = buffer.writeBytes(bytes)

		/**
		 * Appends [text] as one byte per character, matching DataOutputStream.writeBytes (each
		 * character's high 8 bits are discarded).  Used for PSD's ASCII signatures and keys.
		 *
		 * @param String text The text to append.
		 */
		fun writeAscii(text: String) {
			for (character in text) {
				buffer.writeByte(character.code and 0xFF)
			}
		}

		/**
		 * Copies out the bytes written so far.
		 *
		 * @return ByteArray The assembled bytes.
		 */
		fun toByteArray(): ByteArray = buffer.toByteArray()
	}
}
