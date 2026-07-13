package org.umamo.runtime.model

/**
 * One glued vertex pair: vertex [indexA] of the glue's mesh A welded to vertex [indexB] of mesh B, each
 * pulled toward the other by its per-side weight. Indices are into the meshes' deformed position arrays
 * (resolved from the CMO3's stable vertex UIDs at import).
 */
class GluePair(
	val indexA: Int,
	val indexB: Int,
	val weightA: Float,
	val weightB: Float,
)

/** A glue keyform: the weld [intensity] (0 = no weld, 1 = full) at one grid cell. */
class GlueForm(val intensity: Float)

/**
 * A glue affecter - seam-welds two art meshes' shared-edge vertices so they move together (a tail's
 * two skinned strips, a sleeve seam, …). It runs after both meshes are deformed: for each [GluePair]
 * each side slides toward the other by `weight · intensity` from the pre-blend positions -
 * `A' = A + (B−A)·wA·i`, `B' = B + (A−B)·wB·i`. Without it the welded strips drift apart into "two
 * copies". [intensity] is blended over its keyform grid (constant 1 when static).
 *
 * グルー：変形後に2メッシュの継ぎ目頂点を溶接する。なければ継ぎ目が分離して二重像になる。
 */
class Glue(
	val meshA: DrawableId,
	val meshB: DrawableId,
	val pairs: List<GluePair>,
	val intensity: KeyformGrid<GlueForm>?,
)
