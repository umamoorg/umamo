package org.umamo.format.moc3.moc

import org.umamo.format.moc3.io.LittleEndianReader

/** Canvas dimensions and origin (section [Sections.CANVAS], `f32[6]`). Pixel units. */
public data class CanvasInfo(
	val pixelsPerUnit: Float,
	val originX: Float,
	val originY: Float,
	val width: Float,
	val height: Float,
)

/** A model parameter (slider). [type] is null on moc versions without `Parameter.Types` (< 4). */
public data class MocParameter(
	val id: String,
	val minimumValue: Float,
	val maximumValue: Float,
	val defaultValue: Float,
	val type: ParameterType?,
)

/** A part (visibility/draw-order group). [parentPartIndex] is -1 when the part is at the root. */
public data class MocPart(
	val id: String,
	val parentPartIndex: Int,
)

/** A drawable (ArtMesh). [constantFlags] is the [ConstantFlag] bitmask. */
public data class MocDrawable(
	val id: String,
	val textureIndex: Int,
	val constantFlags: Int,
	val vertexCount: Int,
	val indexCount: Int,
	val maskCount: Int,
	val parentPartIndex: Int,
)

/**
 * A parsed `.moc3`: the 64-byte header, the section-offset table, and each section's raw bytes.
 *
 * EN: The model retains every section verbatim (the slice `[offset[k], offset[k+1])`, last → EOF,
 *     including padding) so [org.umamo.format.moc3.moc.MocCodec.write] reproduces an unedited file
 *     byte identical.  Structural sections (counts, canvas, IDs, parameters, parts, drawables) are
 *     exposed as typed accessors; the deformer/keyform/glue/ blend-shape/offscreen sections
 *     (per-frame math) are out of scope and read only as raw bytes.
 * JA: 全セクションをそのまま保持し、未編集ファイルはバイト単位で再生成可能。
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md</a>
 */
public class MocModel internal constructor(
	/** The 64-byte file header `[0, 0x40)`, preserved verbatim. */
	public val header: ByteArray,
	/** Absolute file offset of each section, in table order (length == section count). */
	public val offsets: IntArray,
	/** Raw bytes of each section, in table order; section k == original `[offsets[k], offsets[k+1])`. */
	internal val rawSections: Array<ByteArray>,
) {
	/** Typed Layer-1 access to the deformation sections (deformers, keyforms, glue, colors, …). */
	public val sections: MocSections by lazy { MocSections(this) }

	/** The raw version byte at offset 0x04. */
	public val versionByte: Int get() = header[4].toInt() and 0xFF // MOC3 header: version @ +0x04

	/** The typed format version. */
	public val version: MocVersion get() = MocVersion.fromByte(versionByte)

	/** Whether the file declares big-endian byte order (offset 0x05); false for every shipped file. */
	public val isBigEndian: Boolean get() = header[5].toInt() != 0 // MOC3 header: endian flag @ +0x05

	/** Number of sections present in the offset table. */
	public val sectionCount: Int get() = offsets.size

	/**
	 * Returns the raw bytes of section [index] (including its trailing padding), or null if absent.
	 *
	 * @param Int index A section-table index (e.g. a [Sections] constant).
	 * @return ByteArray? The section bytes, or null when the section is not present.
	 */
	public fun section(index: Int): ByteArray? = rawSections.getOrNull(index)

	/** The CountInfo block (section 0) decoded as a `u32[]` (indexable with `Sections.CI_*`). */
	public val countInfo: IntArray by lazy {
		val raw = section(Sections.COUNTINFO) ?: return@lazy IntArray(0)
		val reader = LittleEndianReader(raw)
		IntArray(raw.size / 4) { reader.readInt32() }
	}

	/**
	 * Reads one count from the CountInfo block, defaulting to 0 when the index is absent.
	 *
	 * @param Int index A `Sections.CI_*` field index into [countInfo].
	 * @return Int The count, or 0 when out of range.
	 */
	private fun count(index: Int): Int = countInfo.getOrElse(index) { 0 }

	/** Number of parts. */
	public val partCount: Int get() = count(Sections.CI_PARTS)

	/** Number of drawables (ArtMeshes). */
	public val drawableCount: Int get() = count(Sections.CI_DRAWABLES)

	/** Number of parameters. */
	public val parameterCount: Int get() = count(Sections.CI_PARAMETERS)

	/** Number of deformers (warp + rotation). */
	public val deformerCount: Int get() = count(Sections.CI_DEFORMERS)

	/** Number of glue (affecter) entries. */
	public val glueCount: Int get() = count(Sections.CI_GLUES)

	/** The canvas dimensions/origin, or null if the canvas section is absent. */
	public val canvasInfo: CanvasInfo? by lazy {
		val raw = section(Sections.CANVAS) ?: return@lazy null
		val reader = LittleEndianReader(raw)
		CanvasInfo(
			pixelsPerUnit = reader.readFloat32(),
			originX = reader.readFloat32(),
			originY = reader.readFloat32(),
			width = reader.readFloat32(),
			height = reader.readFloat32(),
		)
	}

	/**
	 * Reads section [index] as a packed `i32[elementCount]`, or a zero-filled array when absent.
	 *
	 * @param Int index        A [Sections] section index.
	 * @param Int elementCount Number of 32-bit integers to read.
	 * @return IntArray The decoded values (length == [elementCount]).
	 */
	private fun ints(index: Int, elementCount: Int): IntArray {
		val raw = section(index) ?: return IntArray(elementCount)
		val reader = LittleEndianReader(raw)
		return IntArray(elementCount) { reader.readInt32() }
	}

	/**
	 * Reads section [index] as a packed `f32[elementCount]`, or a zero-filled array when absent.
	 *
	 * @param Int index        A [Sections] section index.
	 * @param Int elementCount Number of 32-bit floats to read.
	 * @return FloatArray The decoded values (length == [elementCount]).
	 */
	private fun floats(index: Int, elementCount: Int): FloatArray {
		val raw = section(index) ?: return FloatArray(elementCount)
		val reader = LittleEndianReader(raw)
		return FloatArray(elementCount) { reader.readFloat32() }
	}

	/**
	 * Reads section [index] as [elementCount] fixed-width ID records, or blanks when absent.
	 *
	 * @param Int index        A [Sections] section index.
	 * @param Int elementCount Number of ID records to read (each [Sections.ID_STRIDE] bytes).
	 * @return List<String> The decoded IDs (length == [elementCount]).
	 */
	private fun idStrings(index: Int, elementCount: Int): List<String> {
		val raw = section(index) ?: return List(elementCount) { "" }
		val reader = LittleEndianReader(raw)
		return List(elementCount) { reader.readFixedString(Sections.ID_STRIDE) }
	}

	/** The parameters, in file order. */
	public fun parameters(): List<MocParameter> {
		val parameterCount = parameterCount
		val ids = idStrings(Sections.PARAM_ID, parameterCount)
		val maxima = floats(Sections.PARAM_MAX, parameterCount)
		val minima = floats(Sections.PARAM_MIN, parameterCount)
		val defaults = floats(Sections.PARAM_DEFAULT, parameterCount)
		val types = if (section(Sections.PARAM_TYPE) != null) ints(Sections.PARAM_TYPE, parameterCount) else null
		return List(parameterCount) { parameterIndex ->
			MocParameter(
				ids[parameterIndex],
				minima[parameterIndex],
				maxima[parameterIndex],
				defaults[parameterIndex],
				types?.let { ParameterType.fromNumber(it[parameterIndex]) },
			)
		}
	}

	/** The parts, in file order. */
	public fun parts(): List<MocPart> {
		val partCount = partCount
		val ids = idStrings(Sections.PART_ID, partCount)
		val parents = ints(Sections.PART_PARENT, partCount)
		return List(partCount) { partIndex -> MocPart(ids[partIndex], parents[partIndex]) }
	}

	/** The drawables (ArtMeshes), in file order. */
	public fun drawables(): List<MocDrawable> {
		val drawableCount = drawableCount
		val ids = idStrings(Sections.DRAW_ID, drawableCount)
		val textures = ints(Sections.DRAW_TEXTURE, drawableCount)
		val vertexCounts = ints(Sections.DRAW_VERTEX_COUNT, drawableCount)
		val indexCounts = ints(Sections.DRAW_INDEX_COUNT, drawableCount)
		val maskCounts = ints(Sections.DRAW_MASK_COUNT, drawableCount)
		val parents = ints(Sections.DRAW_PARENT, drawableCount)
		val flagsRaw = section(Sections.DRAW_CONSTANT_FLAG)
		return List(drawableCount) { drawableIndex ->
			MocDrawable(
				id = ids[drawableIndex],
				textureIndex = textures[drawableIndex],
				constantFlags = (flagsRaw?.getOrNull(drawableIndex)?.toInt() ?: 0) and 0xFF,
				vertexCount = vertexCounts[drawableIndex],
				indexCount = indexCounts[drawableIndex],
				maskCount = maskCounts[drawableIndex],
				parentPartIndex = parents[drawableIndex],
			)
		}
	}
}
