package org.umamo.edit.mesh

import org.umamo.edit.DeformerChange
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshSelectionOps
import org.umamo.edit.NoticePlacement
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.ElementOwner
import org.umamo.runtime.model.PuppetModel

/*
 * The commit side of rewriting a lattice at rest: reshaping it in Edit mode, resizing or subdividing it, and toggling
 * its Quad transform.  The rewrite itself - resampling or inverting the lattice and keeping its children in place -
 * needs the evaluator, which :edit cannot see, so the caller computes the rewritten model and this records it.
 */

/**
 * Commits a reshape of one or more warps' rest lattices as ONE undo step, and reports the children it could not keep
 * in place: the reshape stands, Ctrl+Z undoes it, and the notice says how many children's art moved.  A reshape that
 * changed nothing records nothing and reports nothing.
 *
 * @param DeformerChange.ReshapeLattice change The edit descriptor.
 * @param PuppetModel reshaped The model with the lattices reshaped and their children kept in place.
 * @param Int unconvertedCount How many direct children the reshape could not keep in place.
 */
fun EditorSession.commitLatticeReshape(change: DeformerChange.ReshapeLattice, reshaped: PuppetModel, unconvertedCount: Int) {
	val before = model.value
	mutate(change) { reshaped }

	if (model.value !== before) {
		reportLatticeChildrenMoved(unconvertedCount)
	}
}

/**
 * Commits a rewrite of one or more warps' lattices that kept their children in place - a resize, a subdivision, a
 * Quad toggle - as ONE undo step, and reports what it could not keep exact.  Warps whose points it renumbered lose
 * their element selection and their remembered elements in the same step, since the old indices now name other
 * points.  A rewrite that changed nothing records nothing.
 *
 * @param DeformerChange change The edit descriptor.
 * @param PuppetModel rewritten The model with the lattices rewritten and their children kept in place.
 * @param Collection<DeformerId> renumbered The warps whose lattice points the rewrite renumbered.
 * @param Int unconvertedCount How many direct children could not be kept in place.
 * @param Boolean approximate Whether a resample only approximated a warp's old map.
 * @return Boolean True when a step was recorded.
 */
fun EditorSession.commitLatticeRewrite(
	change: DeformerChange,
	rewritten: PuppetModel,
	renumbered: Collection<DeformerId>,
	unconvertedCount: Int,
	approximate: Boolean,
): Boolean {
	if (rewritten === model.value) {
		return false
	}

	val owners = renumbered.mapTo(HashSet()) { deformerId -> ElementOwner.Deformer(deformerId) }
	commitStep(change, model = rewritten, meshSelection = MeshSelectionOps.clearElementsOf(meshSelection.value, owners))
	elementMemory.forget(owners)
	reportLatticeRewrite(unconvertedCount, approximate)

	return true
}

/**
 * Tells the rigger, near the cursor, what a lattice rewrite could not keep exact: that a resample only approximated
 * the old map, with how many children it could not keep in place when there are any, or those children alone.  Says
 * nothing when it kept everything.
 *
 * @param Int unconvertedCount How many children could not be kept in place.
 * @param Boolean approximate Whether a resample only approximated a warp's old map.
 */
fun EditorSession.reportLatticeRewrite(unconvertedCount: Int, approximate: Boolean) {
	when {
		approximate && unconvertedCount > 0 -> {
			emitNotice("notice.lattice.resampledChildrenMoved", NoticePlacement.NearCursor, listOf(unconvertedCount.toString()))
		}

		approximate -> {
			emitNotice("notice.lattice.resampled", NoticePlacement.NearCursor)
		}

		else -> {
			reportLatticeChildrenMoved(unconvertedCount)
		}
	}
}

/**
 * Tells the rigger, near the cursor, how many children a lattice reshape could not keep in place - a child the
 * reshaped lattice folds over or collapses at keeps its numbers, so its art moves.  Says nothing for none.
 *
 * @param Int unconvertedCount How many children could not be kept in place.
 */
fun EditorSession.reportLatticeChildrenMoved(unconvertedCount: Int) {
	if (unconvertedCount <= 0) {
		return
	}

	emitNotice("notice.edit.latticeChildrenMoved", NoticePlacement.NearCursor, listOf(unconvertedCount.toString()))
}