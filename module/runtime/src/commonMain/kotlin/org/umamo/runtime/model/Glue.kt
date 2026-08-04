package org.umamo.runtime.model

/**
 * One glued vertex pair: vertex [indexA] of the glue's mesh A welded to vertex [indexB] of mesh B, each
 * pulled toward the other by its per-side weight. Indices are into the meshes' deformed position arrays,
 * so each importer has to land them in that space: a CMO3 import resolves that format's stable vertex
 * UIDs, while MOC3 (§5.6 s99) already stores them mesh-local.
 */
class GluePair(
	val indexA: Int,
	val indexB: Int,
	val weightA: Float,
	val weightB: Float,
)

/**
 * A glue keyform: the weld intensity (0 = no weld, 1 = full) at one grid cell.
 *
 * Retained as the CMO3 / MOC3 import intermediate; the model itself keys weld strength through
 * [FormChannel.GLUE_INTENSITY] on [Glue.channelGrids].
 */
class GlueForm(val intensity: Float)

/**
 * A glue affecter - seam-welds two art meshes' shared-edge vertices so they move together (a tail's
 * two skinned strips, a sleeve seam, …). It runs after both meshes are deformed: for each [GluePair]
 * each side slides toward the other by `weight · intensity` from the pre-blend positions -
 * `A' = A + (B−A)·wA·i`, `B' = B + (A−B)·wB·i`. Without it the welded strips drift apart into "two
 * copies".
 *
 * Weld strength is the [FormChannel.GLUE_INTENSITY] track when one is keyed, else the static
 * [intensity] - which is 1 (full weld) for a glue that was never animated.
 */
class Glue(
	val meshA: DrawableId,
	val meshB: DrawableId,
	val pairs: List<GluePair>,
	val channelGrids: ChannelGrids = ChannelGrids.Empty,
	val intensity: Float = 1f,
	/**
	 * The source's authored identifier (MOC3 §5.6 s90, e.g. "Glue__ArtMesh48__ArtMesh49"), or null
	 * when the source carried none.
	 *
	 * A plain string rather than a typed id because nothing REFERENCES a glue - it is addressed only by
	 * list position, so there is no lookup for an id type to make safe.  It is carried purely so a
	 * round trip writes back the name the model was authored with instead of a synthesized one.
	 */
	val id: String? = null,
)
