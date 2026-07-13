package org.umamo.format.moc3

import org.umamo.format.moc3.moc.MocVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The `.moc3` version probe: a synthetic 64-byte header (the same fixture style as
 * FormatRegistryTest) carrying the magic plus a version byte at +0x04 is all `getVersion` reads.
 */
class Moc3GetVersionTest {
	/** A 64-byte buffer with the MOC3 magic and [versionByte] at file offset 0x04. */
	private fun mocHeaderBytes(versionByte: Int): ByteArray =
		ByteArray(64).also { bytes ->
			"MOC3".encodeToByteArray().copyInto(bytes)
			bytes[4] = versionByte.toByte()
		}

	@Test
	fun probesEveryKnownVersionByte() {
		for (version in MocVersion.entries) {
			assertEquals(
				version,
				Moc3.getVersion(mocHeaderBytes(version.byteValue)),
				"version byte ${version.byteValue}",
			)
		}
	}

	@Test
	fun unknownVersionByteProbesNull() {
		assertNull(Moc3.getVersion(mocHeaderBytes(99)), "probe never throws on an unknown byte")
		assertNull(Moc3.getVersion(mocHeaderBytes(0)), "zero is below the valid 1..6 range")
	}

	@Test
	fun nonMocBytesProbeNull() {
		assertNull(Moc3.getVersion("NOPE".encodeToByteArray() + ByteArray(60)), "wrong magic")
		assertNull(Moc3.getVersion(ByteArray(4)), "shorter than a header")
	}
}
