package org.umamo.format.moc3

import org.umamo.format.moc3.io.LittleEndianReader
import org.umamo.format.moc3.io.LittleEndianWriter
import org.umamo.format.moc3.moc.ConstantFlag
import org.umamo.format.moc3.moc.MocCodec
import org.umamo.format.moc3.moc.MocVersion
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Platform-independent moc3 tests using synthetic data (no sample/jar needed); mirrors the cmo3
 * module's CaffRoundTripTest so the public suite has coverage without the git-ignored samples.
 */
class MocSanityTest {
	@Test
	fun littleEndianRoundTrips() {
		val writer = LittleEndianWriter()
		writer.writeU8(0xAB)
		writer.writeInt32(-123456)
		writer.writeInt32(0x7FFFFFFF)
		writer.writeFloat32(3.5f)
		writer.writeBytes("ParamAngleX".encodeToByteArray())
		writer.zeroPad(64 - 11) // pad the "ID record" to 64 bytes
		val bytes = writer.toByteArray()

		val reader = LittleEndianReader(bytes)
		assertEquals(0xAB, reader.readU8())
		assertEquals(-123456, reader.readInt32())
		assertEquals(0x7FFFFFFF, reader.readInt32())
		assertEquals(3.5f, reader.readFloat32())
		assertEquals("ParamAngleX", reader.readFixedString(64))
	}

	@Test
	fun versionByteMapping() {
		assertEquals(MocVersion.V40, MocVersion.fromByte(3))
		assertEquals(MocVersion.V42, MocVersion.fromByte(4))
		assertEquals(MocVersion.V53, MocVersion.fromByte(6))
		assertEquals(6, MocVersion.LATEST)
	}

	@Test
	fun constantFlagBits() {
		assertEquals(1, ConstantFlag.BLEND_ADDITIVE)
		assertEquals(2, ConstantFlag.BLEND_MULTIPLICATIVE)
		assertEquals(4, ConstantFlag.IS_DOUBLE_SIDED)
		assertEquals(8, ConstantFlag.IS_INVERTED_MASK)
	}

	@Test
	fun syntheticMocRoundTrips() {
		// Build a minimal valid moc3: 64-byte header, two section offsets, two 16-byte sections.
		val writer = LittleEndianWriter()
		writer.writeBytes(byteArrayOf(0x4D, 0x4F, 0x43, 0x33)) // "MOC3"
		writer.writeU8(3) // version byte (4.0)
		writer.writeU8(0) // little-endian flag
		writer.zeroPad(64 - 6) // reserved up to the offset table at 0x40
		val firstOffset = 80
		writer.writeInt32(firstOffset) // section 0 offset
		writer.writeInt32(firstOffset + 16) // section 1 offset
		writer.zeroPad(firstOffset - writer.position) // pad table region to the data region
		writer.writeBytes(ByteArray(16) { it.toByte() }) // section 0 data
		writer.writeBytes(ByteArray(16) { (it + 100).toByte() }) // section 1 data
		val original = writer.toByteArray()

		val model = MocCodec.read(original)
		assertEquals(MocVersion.V40, model.version)
		assertEquals(2, model.sectionCount)
		assertContentEquals(intArrayOf(firstOffset, firstOffset + 16), model.offsets)
		assertTrue(!model.isBigEndian)

		assertContentEquals(original, MocCodec.write(model), "synthetic moc3 round-trips byte-for-byte")
	}
}
