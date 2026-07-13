package org.umamo.runtime.model

/**
 * A deformer in the rig hierarchy.
 *
 * Cubism deformation is parameter-driven morph blending, not skeletal - there are exactly two
 * deformer kinds, so a `sealed interface` captures the closed taxonomy and forces `when`s over it to
 * be exhaustive (adding a third kind becomes a compile error until handled everywhere). Deformers nest
 * via [parent] (the transform hierarchy a drawable inherits); each also belongs to a part via [partId]
 * (the organisational tree) - two independent hierarchies, mirroring CMO3's `targetDeformerGuid` vs
 * `parentGuid`.
 *
 * リグ階層の変形器。Cubism は2種(ワープ／回転)のみ。変形系統([parent])と組織系統([partId])は別。
 */
sealed interface Deformer {
	val id: DeformerId

	/** The user-facing display name (CMO3 localName), e.g. "Warp Deformer of Bag front12"; the id when unnamed. */
	val name: String

	/** Parent deformer in the nesting (transform) hierarchy, or null at the root. */
	val parent: DeformerId?

	/** The part this deformer belongs to (organisational tree), or null at the root. */
	val partId: PartId?

	/**
	 * Blender-style selectable toggle: an unselectable deformer cannot be picked in the viewport.
	 * Maps inverted to CMO3's isLocked (Cubism lock = not selectable), so a future writer must
	 * emit isLocked = !isSelectable.
	 */
	val isSelectable: Boolean

	/**
	 * Warp deformer - a free-form-deformation (FFD) lattice: a [rows] × [columns] grid of control
	 * points whose displacement bends the bound geometry. The control-point positions are animated
	 * per keyform; only the lattice dimensions are static.
	 */
	data class Warp(
		override val id: DeformerId,
		override val name: String,
		override val parent: DeformerId?,
		override val partId: PartId?,
		val rows: Int,
		val columns: Int,
		/** FFD interpolation mode: true = bilinear (quad), false = triangle split (the Umamo C++ Runtime's warp mode). */
		val isQuadTransform: Boolean,
		/** Per-parameter lattice control-point forms, or null if unkeyed. */
		val keyforms: KeyformGrid<WarpForm>?,
		override val isSelectable: Boolean = true,
	) : Deformer

	/**
	 * Rotation deformer - a nesting pivot transform. Its origin/angle/scale are animated per keyform
	 * [baseAngle] is the static editor reference angle.
	 */
	data class Rotation(
		override val id: DeformerId,
		override val name: String,
		override val parent: DeformerId?,
		override val partId: PartId?,
		val baseAngle: Float,
		/** Per-parameter pivot-transform forms, or null if unkeyed. */
		val keyforms: KeyformGrid<RotationForm>?,
		override val isSelectable: Boolean = true,
	) : Deformer
}
