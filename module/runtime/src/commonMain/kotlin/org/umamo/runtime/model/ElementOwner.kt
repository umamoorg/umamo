package org.umamo.runtime.model

/**
 * An entity whose interior the editor draws and edits as a mesh: a drawable's triangle mesh, or a warp
 * deformer's lattice of control points (WarpLatticeLayout).  The one key every per-mesh structure takes - an
 * overlay mesh, an element selection, a transform capture - so a lattice can stand anywhere a drawable's mesh
 * does without a parallel copy of each.
 *
 * Here rather than in the editing module because the renderer's overlay is keyed by it too, and the renderer
 * sees the runtime but not the editor.
 */
sealed interface ElementOwner {
	/**
	 * A drawable's triangle mesh.
	 *
	 * @property DrawableId id The drawable.
	 */
	data class Drawable(val id: DrawableId) : ElementOwner

	/**
	 * A deformer's lattice.
	 *
	 * @property DeformerId id The deformer.
	 */
	data class Deformer(val id: DeformerId) : ElementOwner
}

/** The drawable this owner names, or null for a lattice. */
val ElementOwner.drawableId: DrawableId? get() = (this as? ElementOwner.Drawable)?.id

/** The deformer this owner names, or null for a drawable's mesh. */
val ElementOwner.deformerId: DeformerId? get() = (this as? ElementOwner.Deformer)?.id