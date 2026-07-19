package org.umamo.format.moc3.encode

import org.umamo.format.moc3.MocDocument
import org.umamo.format.moc3.io.LittleEndianWriter
import org.umamo.format.moc3.moc.MocModel
import org.umamo.format.moc3.moc.MocVersion

/**
 * Frames an ordered set of section byte-arrays into a valid `.moc3` blob (header + offset table +
 * sections), the serializer half of the bake.
 *
 * EN: Section k is placed at `table[k]`; sections are 16-byte aligned and the header+table region
 *     is 64-byte aligned, matching the runtime's expectations. This does not reproduce the editor's
 *     exact padding/region size (that is not required for a runtime-valid file); it produces a
 *     compact, well-formed blob the SDK loads. The semantic lowering (model → section arrays) is the
 *     other half.
 * JA: セクション群を有効な .moc3 にフレーミングする。
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §4, §7</a>
 */
public object MocEncoder {
	private const val HEADER_SIZE: Int = 64
	private val MAGIC: ByteArray = byteArrayOf(0x4D, 0x4F, 0x43, 0x33) // "MOC3"
	private const val SECTION_ALIGN: Int = 64
	private const val BUFFER_ALIGN: Int = 64

	/**
	 * Rounds [value] up to the next multiple of [alignment].
	 *
	 * @param Int value     The value to align.
	 * @param Int alignment The alignment boundary (e.g. 64).
	 * @return Int The smallest multiple of [alignment] that is `>= value`.
	 */
	private fun align(value: Int, alignment: Int): Int = (value + alignment - 1) / alignment * alignment

	/**
	 * Frames [sections] (in table-index order) into a `.moc3` for the given [versionByte].
	 *
	 * @param Int versionByte The moc version byte (1..6).
	 * @param Boolean isBigEndian The endian flag (false for every shipped file).
	 * @param List sections Section payloads, in table order; section k lands at `table[k]`.
	 * @return ByteArray The complete `.moc3` bytes.
	 */
	public fun encode(versionByte: Int, isBigEndian: Boolean, sections: List<ByteArray>): ByteArray {
		val sectionTotal = sections.size
		// The runtime reads a fixed-size offset table; reserve the editor's region for the version so
		// it never reads past the live offsets into section data (0x7C0 for moc <=5, 0x16C0 for moc 6).
		val headerRegion = if (versionByte >= 6) 0x16C0 else 0x7C0
		require(HEADER_SIZE + sectionTotal * 4 <= headerRegion) {
			"offset table ($sectionTotal entries) overruns the $headerRegion-byte header region"
		}
		val firstOffset = headerRegion
		val offsets = IntArray(sectionTotal)
		var cursor = firstOffset
		for (sectionIndex in 0 until sectionTotal) {
			// Every section (even an empty one) takes the running cursor as its offset; the runtime
			// indexes by table slot and tolerates equal offsets for zero-length sections.
			offsets[sectionIndex] = cursor
			cursor += align(sections[sectionIndex].size, SECTION_ALIGN)
		}
		val writer = LittleEndianWriter(align(cursor, BUFFER_ALIGN))
		writer.writeBytes(MAGIC) // MOC3 header: magic "MOC3" @ +0x00
		writer.writeU8(versionByte) // MOC3 header: version @ +0x04
		writer.writeU8(if (isBigEndian) 1 else 0) // MOC3 header: endian flag @ +0x05
		writer.zeroPad(HEADER_SIZE - writer.position) // reserved @ +0x06
		for (offset in offsets) writer.writeInt32(offset) // MOC3 offset table @ +0x40
		writer.zeroPad(firstOffset - writer.position) // pad the table region to the data region
		for (section in sections) {
			writer.writeBytes(section)
			writer.alignTo(SECTION_ALIGN)
		}
		writer.alignTo(BUFFER_ALIGN)
		return writer.toByteArray()
	}

	/**
	 * Re-frames an existing [MocModel]'s sections into a compact valid `.moc3` (a "repack"). Useful to
	 * validate the serializer independent of the semantic lowering: the runtime accepts it and reads
	 * the same model, though the byte layout differs from the original (use
	 * [org.umamo.format.moc3.moc.MocCodec.write] for a byte-identical re-emit).
	 *
	 * @param MocModel model A parsed container.
	 * @return ByteArray The repacked `.moc3` bytes.
	 */
	public fun repack(model: MocModel): ByteArray =
		encode(model.versionByte, model.isBigEndian, model.sectionBytesInOrder())

	/**
	 * Bakes a (possibly-edited) [doc] to `.moc3` bytes, synthesizing every section [MocLowering] can
	 * produce from the object model and carrying the rest (CountInfo, runtime-filled pointer arrays,
	 * blend-shape / offscreen value tables) from [reference]. The result is runtime-valid; for an
	 * unedited document the synthesized sections are byte-exact, so it matches the original data.
	 *
	 * @param MocModel reference The decoded source providing the carried sections + version.
	 * @param MocDocument doc The (editable) semantic model to bake.
	 * @return ByteArray The `.moc3` bytes.
	 */
	public fun bake(reference: MocModel, doc: MocDocument): ByteArray {
		val synthesized =
			MocLowering.structuralSections(doc) + MocLowering.valueTableSections(doc) +
				MocLowering.auxiliarySections(doc) + MocLowering.keyformGridSections(doc) +
				MocLowering.blendShapeSections(doc)
		val sections =
			List(reference.sectionCount) { index ->
				synthesized[index] ?: (reference.section(index) ?: ByteArray(0))
			}
		return encode(reference.versionByte, reference.isBigEndian, sections)
	}

	/**
	 * The number of section-table entries the editor emits for a moc [version]. The runtime indexes the
	 * table by fixed section index and never reads its length, so this only needs to be ≥ the highest
	 * index used; matching the editor keeps the file shape conventional.
	 *
	 * EN: Confirmed against samples for v3 (102), v4 (137), v6 (167); v1/v2/v5 follow the editor's
	 *     version gates (+1 at 3.3, +35 at 4.2, +15 at 5.0) and are unsampled.
	 *
	 * @param MocVersion version The moc version.
	 * @return Int The section count.
	 */
	public fun sectionCount(version: MocVersion): Int =
		when (version.byteValue) {
			1 -> 101
			2, 3 -> 102
			4 -> 137
			5 -> 152
			else -> 167 // v6
		}
}

/**
 * The section payloads in table-index order (internal accessor for the encoder). Absent sections
 * become empty arrays so the list is dense and index-aligned with the offset table.
 *
 * @return List<ByteArray> Each section's raw bytes, in table order.
 */
internal fun MocModel.sectionBytesInOrder(): List<ByteArray> = List(sectionCount) { section(it) ?: ByteArray(0) }
