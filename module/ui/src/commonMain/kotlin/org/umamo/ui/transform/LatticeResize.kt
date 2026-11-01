package org.umamo.ui.transform

import org.umamo.edit.mesh.LATTICE_DIVISION_MAX
import org.umamo.render.eval.isExactRefinementTo
import org.umamo.render.eval.resampledTo
import org.umamo.render.warpChildrenKeptInPlace
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.deformerChain

/*
 * Rewriting a warp's lattice at rest without moving its art: a new resolution (rows and columns, or a subdivision)
 * resampled from the old map, and the Quad transform toggled between bilinear and triangle-split mapping.  Both
 * re-express the warp's children so they rest where they rested.  Lives in :ui because the resample and the
 * keep-in-place both need :render's evaluator.
 */

/**
 * A lattice resolution in cells.
 *
 * @property Int rows The cell count along v.
 * @property Int columns The cell count along u.
 */
internal data class LatticeSize(
	val rows: Int,
	val columns: Int,
)

/**
 * A lattice rewrite lowered onto the model, with what it could not keep exact.
 *
 * @property PuppetModel model The model with every lattice rewritten and every child it could keep in place kept.
 * @property List<DrawableId> unconvertedDrawables The direct child drawables left as they were; their art moves.
 * @property List<DeformerId> unconvertedDeformers The direct child deformers left as they were.
 * @property Boolean approximate Whether a resample only approximated a warp's old map.
 */
internal class LatticeRewrite(
	val model: PuppetModel,
	val unconvertedDrawables: List<DrawableId>,
	val unconvertedDeformers: List<DeformerId>,
	val approximate: Boolean,
) {
	/** How many children the rewrite could not keep in place. */
	val unconvertedCount: Int get() = unconvertedDrawables.size + unconvertedDeformers.size
}

/**
 * Lowers new lattice resolutions onto [model]: each warp in [sizes] resampled onto its new grid, every grid cell and
 * blend-shape form included ([resampledTo]), with its children resting where they rested.
 *
 * A quad-mode refinement by whole multiples keeps the old map exactly, inside the lattice and in the extrapolation
 * beyond it, so its children keep their coordinates as they are.  Any other size only approximates the map, so the
 * children are re-expressed ([warpChildrenKeptInPlace]).  Warps are taken parents first, each against the model its
 * ancestors' rewrites left, as a reshape takes them.  A size outside 1..[LATTICE_DIVISION_MAX], or the warp's own,
 * leaves that warp alone.
 *
 * @param PuppetModel model The model the lattices are resized in.
 * @param Map<DeformerId, LatticeSize> sizes Each resized warp's new resolution.
 * @return LatticeRewrite The resized model and what it could not keep exact.
 */
internal fun latticeResizeOf(model: PuppetModel, sizes: Map<DeformerId, LatticeSize>): LatticeRewrite =
	latticeRewriteOf(model, sizes.keys) { warp ->
		val size = sizes.getValue(warp.id)
		val divisions = 1..LATTICE_DIVISION_MAX

		if (size.rows !in divisions || size.columns !in divisions) {
			null
		} else {
			warp.resampledTo(size.rows, size.columns).takeIf { resized -> resized !== warp }?.let { resized ->
				resized to !warp.isExactRefinementTo(size.rows, size.columns)
			}
		}
	}

/**
 * Lowers a Quad transform toggle onto [model]: [warpId] switched to bilinear ([quad]) or triangle-split mapping, with
 * its children re-expressed so they rest where they rested.  The two mappings agree only on an affine lattice, so
 * the children are always re-expressed.  The warp's own setting, or a deformer that is not a warp, changes nothing.
 *
 * @param PuppetModel model The model the warp is in.
 * @param DeformerId warpId The warp.
 * @param Boolean quad The new mapping: true for bilinear.
 * @return LatticeRewrite The model with the mapping switched and what it could not keep in place.
 */
internal fun quadToggledOf(model: PuppetModel, warpId: DeformerId, quad: Boolean): LatticeRewrite =
	latticeRewriteOf(model, listOf(warpId)) { warp ->
		if (warp.isQuadTransform == quad) null else warp.copy(isQuadTransform = quad) to true
	}

/**
 * Rewrites each warp of [warpIds], parents first, with [rewrite], keeping its children in place whenever the rewrite
 * says the old map did not survive.
 *
 * @param PuppetModel model The model the warps are in.
 * @param Collection<DeformerId> warpIds The warps to rewrite.
 * @param Function rewrite The rewritten warp and whether its map changed, or null to leave the warp alone.
 * @return LatticeRewrite The rewritten model and what it could not keep exact.
 */
private fun latticeRewriteOf(
	model: PuppetModel,
	warpIds: Collection<DeformerId>,
	rewrite: (Deformer.Warp) -> Pair<Deformer.Warp, Boolean>?,
): LatticeRewrite {
	val unconvertedDrawables = ArrayList<DrawableId>()
	val unconvertedDeformers = ArrayList<DeformerId>()
	var approximate = false
	var current = model

	for (warpId in warpIds.sortedBy { deformerId -> model.deformerChain(deformerId).size }) {
		val warp = current.deformerById[warpId] as? Deformer.Warp ?: continue
		val (rewritten, mapChanged) = rewrite(warp) ?: continue
		val withWarp = current.copy(deformers = current.deformers.map { deformer -> if (deformer.id == warpId) rewritten else deformer })

		if (!mapChanged) {
			current = withWarp

			continue
		}

		val kept = warpChildrenKeptInPlace(current, withWarp, warpId)
		unconvertedDrawables.addAll(kept.unconvertedDrawables)
		unconvertedDeformers.addAll(kept.unconvertedDeformers)
		approximate = approximate || rewritten.rows != warp.rows || rewritten.columns != warp.columns
		current = kept.model
	}

	return LatticeRewrite(current, unconvertedDrawables, unconvertedDeformers, approximate)
}