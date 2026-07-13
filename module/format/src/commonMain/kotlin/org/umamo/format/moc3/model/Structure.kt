package org.umamo.format.moc3.model

/**
 * A part (visibility/draw-order group). [drawOrderKeyforms] holds the part's draw order per keyform
 * of [keyformBindingIndex]'s grid (a single value when the part is static).
 */
public data class Part(
	val id: String,
	val parentPartIndex: Int,
	val keyformBindingIndex: Int,
	val drawOrderKeyforms: FloatArray,
) {
	override fun equals(other: Any?): Boolean =
		this === other ||
			(
				other is Part &&
					id == other.id &&
					parentPartIndex == other.parentPartIndex &&
					keyformBindingIndex == other.keyformBindingIndex &&
					drawOrderKeyforms.contentEquals(other.drawOrderKeyforms)
			)

	override fun hashCode(): Int = id.hashCode()
}

/** A glued vertex pair: a vertex in mesh A welded to one in mesh B with per-side weights. */
public data class GlueVertexPair(
	val vertexA: Int,
	val vertexB: Int,
	val weightA: Float,
	val weightB: Float,
)

/**
 * A glue: seam-welds two art meshes' shared vertices. [intensityKeyforms] modulate the weld strength
 * over [keyformBindingIndex]'s grid (a single value when static).
 */
public data class Glue(
	val meshAIndex: Int,
	val meshBIndex: Int,
	val keyformBindingIndex: Int,
	val pairs: List<GlueVertexPair>,
	val intensityKeyforms: FloatArray,
) {
	override fun equals(other: Any?): Boolean =
		this === other ||
			(
				other is Glue &&
					meshAIndex == other.meshAIndex &&
					meshBIndex == other.meshBIndex &&
					keyformBindingIndex == other.keyformBindingIndex &&
					pairs == other.pairs &&
					intensityKeyforms.contentEquals(other.intensityKeyforms)
			)

	override fun hashCode(): Int = 31 * (31 * meshAIndex + meshBIndex) + keyformBindingIndex
}

/** A child of a [RenderOrderGroup]: a drawable (kind 0) or a nested group (kind 1). */
public data class RenderOrderChild(
	val kind: Int,
	/** Drawable index when [kind] is 0, else the part index used to look up the sub-group's order. */
	val index: Int,
	/** The nested group's record index when [kind] is 1 (else unused). */
	val groupIndex: Int,
)

/** A render-order group: an ordered list of drawable/sub-group children (the draw-order tree). */
public data class RenderOrderGroup(val children: List<RenderOrderChild>)
