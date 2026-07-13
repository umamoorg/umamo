package org.umamo.format.moc3.model

/** An offscreen render target (moc 6 / Cubism 5.3): composites its owner part's subtree. */
public data class Offscreen(
	val ownerPartIndex: Int,
	/** [org.umamo.format.moc3.moc.ConstantFlag]-style bitmask (`u8`). */
	val constantFlags: Int,
	/** Packed blend-mode value as stored in the file (the runtime unpacks it into color/alpha modes). */
	val blendMode: Int,
	val maskCount: Int,
)

/** Which kind of object a [BlendShape] deltas. */
public enum class BlendShapeTarget { WARP, ART_MESH, ROTATION }

/**
 * A blend-shape record (moc 4+ for meshes/warps, moc 5+ for rotations): an additive deformation
 * delta driven by one parameter. The deltas themselves live in the shared keyform value tables at
 * [recordBase] (per key), relative to the [neutralKeyIndex] key; this models the record's binding
 * structure (what it targets and what drives it). Vertex/CP/affine delta extraction reuses the same
 * value tables as base keyforms.
 *
 * @property target        Kind of deformed object.
 * @property targetIndex   For [BlendShapeTarget.WARP]/[BlendShapeTarget.ROTATION], the deformer index
 *                         in [org.umamo.format.moc3.MocDocument.deformers]; for
 *                         [BlendShapeTarget.ART_MESH], the drawable index.
 * @property parameterIndex The driving parameter (index into [org.umamo.format.moc3.MocDocument.parameters]).
 * @property keyPositions   Parameter values at which deltas are keyed.
 * @property neutralKeyIndex The key whose delta is zero (the rest shape).
 * @property recordBase      Base index into the keyform value tables for this record's per-key deltas.
 */
public data class BlendShape(
	val target: BlendShapeTarget,
	val targetIndex: Int,
	val parameterIndex: Int,
	val keyPositions: FloatArray,
	val neutralKeyIndex: Int,
	val recordBase: Int,
) {
	override fun equals(other: Any?): Boolean =
		this === other ||
			(
				other is BlendShape &&
					target == other.target &&
					targetIndex == other.targetIndex &&
					parameterIndex == other.parameterIndex &&
					keyPositions.contentEquals(other.keyPositions) &&
					neutralKeyIndex == other.neutralKeyIndex &&
					recordBase == other.recordBase
			)

	override fun hashCode(): Int {
		var hash = target.hashCode()
		hash = 31 * hash + targetIndex
		hash = 31 * hash + parameterIndex
		hash = 31 * hash + keyPositions.contentHashCode()
		hash = 31 * hash + neutralKeyIndex
		hash = 31 * hash + recordBase
		return hash
	}
}
