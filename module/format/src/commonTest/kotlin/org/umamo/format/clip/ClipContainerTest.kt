package org.umamo.format.clip

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the CSFCHUNK container walk on hand-assembled bytes - the one CLIP layer that needs no
 * SQLite, and therefore the coverage that survives a corpus-less run.  Every failure path of
 * extractSqliteDatabase is reachable synthetically.
 */
class ClipContainerTest {
	private val databaseSentinel = byteArrayOf(0x53, 0x51, 0x4C, 0x69, 0x74, 0x65, 0x21)

	/** A well-formed container: header chunk, the database, and the footer terminator. */
	private fun wellFormedContainer(): ByteArray =
		syntheticClipContainer(
			listOf(
				"CHNKHead" to byteArrayOf(1, 2, 3, 4),
				"CHNKSQLi" to databaseSentinel,
				"CHNKFoot" to byteArrayOf(),
			),
		)

	/** The magic sniff accepts a synthetic container and rejects anything else. */
	@Test
	fun isClipMatchesOnlyTheContainerMagic() {
		assertTrue(ClipContainer.isClip(wellFormedContainer()))
		assertFalse(ClipContainer.isClip("NOTACLIP".encodeToByteArray()))
		assertFalse(ClipContainer.isClip(byteArrayOf(0x43)), "a short buffer never matches")
	}

	/** The extraction returns the CHNKSQLi payload as a fresh copy, not a view into the container. */
	@Test
	fun extractReturnsTheSqlitePayloadAsACopy() {
		val container = wellFormedContainer()
		val extracted = ClipContainer.extractSqliteDatabase(container)
		assertContentEquals(databaseSentinel, extracted)
		extracted[0] = 0
		assertContentEquals(databaseSentinel, ClipContainer.extractSqliteDatabase(container), "mutating the result must not alias the container")
	}

	/** A missing magic is rejected before any chunk walk. */
	@Test
	fun extractFailsOnMissingMagic() {
		assertFailsWith<IllegalArgumentException> {
			ClipContainer.extractSqliteDatabase("NOTACLIPatall".encodeToByteArray())
		}
	}

	/** A database chunk whose declared size runs past EOF is rejected rather than truncated. */
	@Test
	fun extractFailsWhenTheSqlitePayloadRunsPastEof() {
		val container = wellFormedContainer()
		// CLIP: chunk size u64 immediately after the 8-byte id; the CHNKSQLi header sits after the
		// 24-byte preamble and the 20-byte CHNKHead chunk, so its size field starts at 24 + 20 + 8.
		val sqliteSizeOffset = 24 + (16 + 4) + 8
		container[sqliteSizeOffset + 7] = 0x7F
		assertFailsWith<IllegalArgumentException> { ClipContainer.extractSqliteDatabase(container) }
	}

	/** A container that ends (or hits the footer) without a database chunk is an error. */
	@Test
	fun extractFailsWithoutASqliteChunk() {
		val footerOnly = syntheticClipContainer(listOf("CHNKHead" to byteArrayOf(1), "CHNKFoot" to byteArrayOf()))
		assertFailsWith<IllegalArgumentException> { ClipContainer.extractSqliteDatabase(footerOnly) }
		val sqliteAfterFooter = syntheticClipContainer(listOf("CHNKFoot" to byteArrayOf(), "CHNKSQLi" to databaseSentinel))
		assertFailsWith<IllegalArgumentException> { ClipContainer.extractSqliteDatabase(sqliteAfterFooter) }
	}

	/** A head offset outside the addressable range is rejected rather than wrapped. */
	@Test
	fun extractFailsOnHeadOffsetOverflow() {
		val container = syntheticClipContainer(listOf("CHNKSQLi" to databaseSentinel), headOffset = Long.MAX_VALUE)
		assertFailsWith<IllegalArgumentException> { ClipContainer.extractSqliteDatabase(container) }
	}
}
