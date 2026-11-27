package org.umamo.ui.workspace.spaces.parameters

import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.ui.model.LiveParamsHandle

/**
 * The panel's writes to the pose: a preview that records no undo step, the commit that ends a gesture as
 * one step, and the discrete edits built from the two.
 *
 * Parameters are LOCKED while in Edit mode: Edit mode edits the neutral state of the base mesh and is
 * pinned to the neutral pose (the viewport shows rest via a display-only override), so no scrub may move
 * the session pose out from under it.  The pose seam refuses such a write from anyone; the lock here is
 * the panel's own, and what it adds is that a refused write never reaches the values the panel holds.
 * The sliders show the rest pose meanwhile, as the viewport does, and the Object-mode pose they hold
 * shows again on exit.
 * Range, link, rename, create, and delete are document edits rather than pose writes, so none of them
 * comes through this class.
 *
 * The lock is a constructor value, not a read at write time.  A writer stands for one state of the lock
 * and is replaced when the mode changes, so a callback holding a writer can never act on a lock that has
 * since flipped.
 *
 * @param LiveParamsHandle? liveParams The pose seam the writes go through, or null with no posable document.
 * @param Boolean locked Whether every write is refused.
 * @param Function recordLocalValue Called with each previewed (id, value) ahead of the seam, so a panel
 *   showing the value moves with its own write.  Does nothing by default.
 */
internal class ParameterPoseWriter(
	private val liveParams: LiveParamsHandle?,
	private val locked: Boolean,
	private val recordLocalValue: (ParameterId, Float) -> Unit = { _, _ -> },
) {
	/**
	 * Previews a value: records it locally and streams it to the renderer, recording no undo step.
	 *
	 * @param ParameterId id The parameter to move.
	 * @param Float newValue The value to show.
	 */
	fun preview(id: ParameterId, newValue: Float) {
		if (locked) {
			return
		}

		recordLocalValue(id, newValue)
		liveParams?.preview(id, newValue)
	}

	/**
	 * Commits the current pose as one undo step, ending a scrub gesture.
	 *
	 * @param Set<ParameterId> ids The parameters the gesture moved.
	 */
	fun commitGesture(ids: Set<ParameterId>) {
		if (locked) {
			return
		}

		liveParams?.commit(ids)
	}

	/**
	 * A discrete value edit (a typed field, a reset): previews it, then commits it as one step.
	 *
	 * @param ParameterId id The parameter to set.
	 * @param Float newValue The value to set it to.
	 */
	fun commitValue(id: ParameterId, newValue: Float) {
		preview(id, newValue)
		commitGesture(setOf(id))
	}

	/**
	 * Returns every parameter to its default in one undo step: a preview of each default, then one commit
	 * over all of them.
	 *
	 * @param List<Parameter> parameters The document's parameters.
	 */
	fun resetAll(parameters: List<Parameter>) {
		parameters.forEach { parameter -> preview(parameter.id, parameter.default) }
		commitGesture(parameters.map { parameter -> parameter.id }.toSet())
	}
}

/**
 * Brings the panel's displayed values up to a pose, writing only the entries that differ.
 *
 * The guard is what lets the panel follow its own writes for free: a commit publishes the pose the panel
 * is already showing, and an entry that already matches is not written, so nothing that reads it is
 * invalidated.  A parameter the pose does not name keeps the value it has.
 *
 * @param MutableMap<ParameterId, Float> values The displayed values, written in place.
 * @param Map<ParameterId, Float> pose The pose to follow.
 */
internal fun followPose(values: MutableMap<ParameterId, Float>, pose: Map<ParameterId, Float>) {
	pose.forEach { (id, value) ->
		if (values[id] != value) {
			values[id] = value
		}
	}
}