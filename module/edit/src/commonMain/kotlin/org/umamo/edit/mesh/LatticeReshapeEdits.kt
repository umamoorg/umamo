package org.umamo.edit.mesh

import org.umamo.edit.DeformerChange
import org.umamo.edit.EditorSession
import org.umamo.edit.NoticePlacement
import org.umamo.runtime.model.PuppetModel

/*
 * The commit side of reshaping a lattice in Edit mode.  The reshape itself - inverting the dragged points through
 * each warp's parent and keeping its children in place - needs the evaluator, which :edit cannot see, so the caller
 * computes the reshaped model and this records it.
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