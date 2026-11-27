package org.umamo.interop.art.mesh

/**
 * Something the art mesher wants its caller to know: a request it adjusted, or why it produced no
 * mesh.  Presentation-free, like the import notices; the UI words them.
 */
public sealed interface ArtMeshNotice {
	/** No pixel met the alpha threshold, so there is nothing to mesh. */
	public data object NothingOpaque : ArtMeshNotice

	/**
	 * The layer was too large for the working grid at the cell size the margins asked for, so the
	 * grid used a coarser cell (the outline then sits a little further out to keep its guarantee).
	 *
	 * @property Int requestedCellSize The cell size the margins asked for, pixels.
	 * @property Int cellSize          The cell size used, pixels.
	 */
	public data class WorkingGridCoarsened(val requestedCellSize: Int, val cellSize: Int) : ArtMeshNotice

	/**
	 * The mesh would have exceeded the vertex budget, so it was coarsened: the interior thinned first,
	 * and only once the outline and pins filled the budget themselves was the interior left to
	 * refinement and the outline widened.
	 *
	 * @property Double  outlineSpacing  The outline spacing used, pixels.
	 * @property Double? interiorSpacing The interior spacing used, pixels, or null when the interior was left to refinement.
	 */
	public data class SpacingCoarsened(val outlineSpacing: Double, val interiorSpacing: Double?) : ArtMeshNotice

	/**
	 * No mesh within the vertex budget exists at these settings - typically a layer of many separate
	 * specks, each needing its own outline ring of the minimum size.
	 *
	 * @property Int vertexCount The fewest vertices the mesher could reach.
	 * @property Int budget      The budget.
	 */
	public data class OverBudget(val vertexCount: Int, val budget: Int) : ArtMeshNotice

	/**
	 * Two or more pins sit at the same position, so they share one vertex.
	 *
	 * @property Int pinCount The number of pins beyond the first on each shared vertex.
	 */
	public data class PinsShareVertex(val pinCount: Int) : ArtMeshNotice

	/**
	 * Some inner-ring edges crossed the outline and were left out; the inner ring only shapes the
	 * interior, so coverage is unaffected.
	 *
	 * @property Int edgeCount The number of inner-ring edges dropped.
	 */
	public data class InnerRingEdgesDropped(val edgeCount: Int) : ArtMeshNotice

	/**
	 * Outline rings crossed one another after simplification, so some outline stretches were kept at
	 * their full traced density instead.
	 *
	 * @property Int chordCount The number of outline chords replaced by their traced points.
	 */
	public data class OutlineDensified(val chordCount: Int) : ArtMeshNotice

	/**
	 * Refinement reached the vertex budget - with a rig to follow, its part of the budget, the rest
	 * held for the old vertices that keep the rig, used or not - before every triangle reached the
	 * minimum angle; the mesh is valid, only some triangles stay narrower than asked.
	 *
	 * @property Int underTargetTriangleCount The triangles left below the minimum angle.
	 */
	public data class RefinementBudgetReached(val underTargetTriangleCount: Int) : ArtMeshNotice

	/**
	 * The vertex budget ran out before the new mesh followed the rig it replaces to within the
	 * deformation tolerance; the mesh is valid, only some art moves a little more than asked.
	 *
	 * @property Double largestError The largest distance left, in art pixels.
	 */
	public data class DeformationToleranceUnmet(val largestError: Double) : ArtMeshNotice

	/**
	 * The mesh failed a final structural check and was withheld rather than risk uncovered art; the
	 * outline could not be repaired.
	 *
	 * @property String reason What failed.
	 */
	public data class StructureCheckFailed(val reason: String) : ArtMeshNotice
}