package org.umamo.ui.transform

import org.umamo.edit.AdjustableOperation
import org.umamo.edit.DeformerChange
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.mesh.commitLatticeReshape
import org.umamo.edit.mesh.reportLatticeChildrenMoved
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.WarpLatticeForm

/*
 * Where a finished lattice edit lands, by the stage it was made at (ElementEditStage): a rest reshape of every form
 * with the children kept in place, or pending lattices at the pose.  The one place a confirm, a strip adjustment, a
 * slide, a snap, and a typed median commit or amend a lattice edit, so none of them asks which mode it ran in.
 */

/** A lattice edit lowered for the stage it was made at. */
internal sealed interface LatticeLanding {
	/** The warps the edit shaped. */
	val deformerIds: List<DeformerId>

	/**
	 * Edit mode: the model with every shaped warp's forms reshaped and its children kept in place.
	 *
	 * @property List<DeformerId> deformerIds The reshaped warps.
	 * @property LatticeReshape reshape The reshaped model and the children it could not keep in place.
	 */
	class Reshape(
		override val deformerIds: List<DeformerId>,
		val reshape: LatticeReshape,
	) : LatticeLanding

	/**
	 * Pose mode: each shaped warp's lattice at the pose, held pending.
	 *
	 * @property Map<DeformerId, WarpLatticeForm> forms Each shaped warp's lattice in its parent's space, before blend
	 *   shapes.
	 */
	class Posed(
		val forms: Map<DeformerId, WarpLatticeForm>,
	) : LatticeLanding {
		override val deformerIds: List<DeformerId> get() = forms.keys.toList()
	}
}

/**
 * Commits [landing] as one undo step labelled by [kind]: a rest reshape (with the children it moved reported), or
 * pending lattices at the pose.
 *
 * @param LatticeLanding landing The landing.
 * @param MeshOperatorKind kind The operator the edit ran as.
 * @return Boolean True when a step was recorded; a landing that changes nothing records none, and so has no step for
 *   the operation strip to amend.
 */
internal fun EditorSession.commitLatticeLanding(
	landing: LatticeLanding,
	kind: MeshOperatorKind,
): Boolean =
	when (landing) {
		is LatticeLanding.Reshape -> {
			val before = model.value
			commitLatticeReshape(DeformerChange.ReshapeLattice(landing.deformerIds, kind), landing.reshape.model, landing.reshape.unconvertedCount)
			model.value !== before
		}

		is LatticeLanding.Posed -> {
			val before = pendingLatticeEdits.value
			commitPendingLatticeEdits(DeformerChange.PoseLattice(landing.deformerIds, kind), landing.forms)
			pendingLatticeEdits.value !== before
		}
	}

/**
 * Lands an operation strip adjustment of a lattice edit over its own step: the rest reshape replaces the step's model
 * (with the children it moved reported), the pending lattices replace the ones the step added.
 *
 * @param AdjustableOperation record The record the rerun was given.
 * @param LatticeLanding landing The recomputed landing.
 * @return Boolean True when the step was replaced.
 */
internal fun EditorSession.amendLatticeLanding(
	record: AdjustableOperation,
	landing: LatticeLanding,
): Boolean =
	when (landing) {
		is LatticeLanding.Reshape -> {
			amendLastCommit(record, landing.reshape.model).also { amended ->
				if (amended) {
					reportLatticeChildrenMoved(landing.reshape.unconvertedCount)
				}
			}
		}

		is LatticeLanding.Posed -> {
			amendLastPendingLatticeEdits(record, landing.forms)
		}
	}