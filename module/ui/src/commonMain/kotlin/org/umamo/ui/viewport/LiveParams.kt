package org.umamo.ui.viewport

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import org.umamo.edit.EditorSession
import org.umamo.edit.ParameterChange
import org.umamo.edit.shownPending
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.KeyableTarget
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.WarpLatticeForm
import org.umamo.ui.model.LiveParamsHandle
import kotlin.concurrent.Volatile

/**
 * Thread-safe hand-off of parameter values from the Compose UI thread to the render thread. The
 * value is an immutable map swapped wholesale, so a volatile reference is a safe publish; the render
 * thread compares identity to detect a change.
 */
class LiveParams(
	initialValues: Map<ParameterId, Float>,
	initialChannelOverrides: Map<KeyableTarget, ChannelValue> = emptyMap(),
) {
	@Volatile
	private var publishedValues: Map<ParameterId, Float> = initialValues

	/**
	 * A Compose-snapshot mirror of the pose, so UI that DISPLAYS it (the keyform sheet's playhead) sees it
	 * move.  The volatile stays the render thread's read: the renderer must never take a Compose
	 * dependency, and a per-frame snapshot read on the GL thread would be one.
	 */
	private val observedValues: MutableState<Map<ParameterId, Float>> = mutableStateOf(initialValues)

	/** The current pose. Every write also publishes to the snapshot mirror behind [observed]. */
	var values: Map<ParameterId, Float>
		get() = publishedValues
		set(value) {
			publishedValues = value
			observedValues.value = value
		}

	/** The same pose, read as Compose state - a read inside a composition recomposes when it moves. */
	val observed: Map<ParameterId, Float> get() = observedValues.value

	/**
	 * Pending unkeyed channel edits, published the same way as [values].
	 *
	 * These are session state rather than document state, so they cannot ride the model to the renderer -
	 * they travel beside the pose, exactly as the pose itself travels beside the model.
	 */
	@Volatile
	var channelOverrides: Map<KeyableTarget, ChannelValue> = initialChannelOverrides

	/**
	 * The session's pending Pose-mode lattices, each standing in for its warp's grid blend at the pose, published the
	 * same way as [values].  Session state, so it rides beside the pose as [channelOverrides] does - and is suppressed
	 * while the pose is pinned for the same reason; empty for none.
	 */
	@Volatile
	var latticeOverrides: Map<DeformerId, WarpLatticeForm> = emptyMap()

	/**
	 * A gesture's per-frame lattices, published the same way as [values]: what an Object-mode warp drag or a Pose-mode
	 * lattice drag previews, moving the art under the warp without pushing a model.  Laid over [latticeOverrides]
	 * ([posedLattices]); empty for none.
	 */
	@Volatile
	var latticePreview: Map<DeformerId, WarpLatticeForm> = emptyMap()
}

/**
 * Writes the session's pending lattices, as [mode][EditorSession.mode] shows them, to [service] now - what a lattice
 * gesture's end does before it clears its preview, so the frame after already shows what the gesture committed rather
 * than waiting for the viewport binding to catch up.
 *
 * @param EditorSession session The session.
 */
private fun PuppetViewportService.publishPendingLattices(session: EditorSession) {
	setLatticeOverrides(session.mode.value.shownPending(session.pendingLatticeEdits.value))
}

/**
 * Puts the renderer back on what the session committed after an element gesture ends: the model, then the pending
 * lattices, then no lattice preview - in that order, so no frame shows the art back where the gesture started.
 *
 * @param EditorSession session The session.
 */
fun PuppetViewportService.resyncAfterElementGesture(session: EditorSession) {
	setModel(session.model.value)
	publishPendingLattices(session)
	setLatticePreview(emptyMap())
}

/**
 * The lattices a renderer poses with: the pending ones, each replaced where a gesture previews another.
 *
 * @param Map<DeformerId, WarpLatticeForm> pending The session's pending lattices.
 * @param Map<DeformerId, WarpLatticeForm> preview A gesture's preview.
 * @return Map<DeformerId, WarpLatticeForm> The merged lattices; one of the two maps itself when the other is empty.
 */
fun posedLattices(
	pending: Map<DeformerId, WarpLatticeForm>,
	preview: Map<DeformerId, WarpLatticeForm>,
): Map<DeformerId, WarpLatticeForm> =
	when {
		preview.isEmpty() -> pending
		pending.isEmpty() -> preview
		else -> pending + preview
	}

/**
 * Builds the initial [LiveParams] from each parameter's default. Hosts with a headless-dump flow
 * (the desktop `UMAMO_DUMP_PARAMS` override) rewrite the values afterwards — environment reads are a
 * desktop dev affordance, not common code.
 *
 * A pose the document was saved with lays over the defaults (docs/format/UMA.md § 7.4), each value clamped to its
 * parameter's range - a range can have been narrowed since the pose was saved - and an id the rig does not have
 * simply never matches.  The session takes these values as its initial pose, so the panel, the viewport, and the
 * first undo snapshot agree from frame one.
 *
 * @param PuppetModel puppet    The rig (for parameter defaults and ranges).
 * @param Map         savedPose The pose the document was saved with, by parameter id; empty for a plain open.
 * @return LiveParams The starting parameter values.
 */
fun initialLiveParams(puppet: PuppetModel, savedPose: Map<ParameterId, Float> = emptyMap()): LiveParams =
	LiveParams(
		puppet.parameters.associate { parameter ->
			parameter.id to (savedPose[parameter.id]?.coerceIn(minOf(parameter.min, parameter.max), maxOf(parameter.min, parameter.max)) ?: parameter.default)
		},
	)

/**
 * Adapts the [LiveParams] volatile hand-off to the platform-neutral [LiveParamsHandle] the common
 * Parameters panel writes through. [preview] publishes a new immutable map (a volatile write) so the
 * render thread's reference compare detects the change and re-poses — the fast per-frame scrub path,
 * which does not touch undo. [commit] routes the gesture's final pose through the [EditorSession] as one
 * undo step, so a whole drag is undoable in a single Ctrl+Z; the session's pose StateFlow is then mirrored
 * back into this same volatile by the host, so an undo / redo re-poses the viewport.
 *
 * Both are refused while the session's pose is pinned (Edit mode).  This is the one way a pose write
 * reaches the renderer, so refusing here holds for every control that scrubs - the Parameters panel,
 * its header, the keyform sheet, and whatever is built next - with none of them having to remember to.
 *
 * @property LiveParams liveParams The underlying render-thread hand-off (the live pose mirror).
 * @property EditorSession session The session that records the committed pose as an undo step.
 */
class LiveParamsAdapter(private val liveParams: LiveParams, private val session: EditorSession) : LiveParamsHandle {
	override val values: Map<ParameterId, Float> get() = liveParams.values

	override val observedValues: Map<ParameterId, Float> get() = liveParams.observed

	/**
	 * Previews one parameter's value toward the render thread without recording an undo step (the fast
	 * per-frame scrub path).
	 *
	 * Moving the pose RETIRES every pending unkeyed edit - channel values and Pose-mode lattices alike - on the
	 * first frame that actually moves rather than at the gesture's end.  A pending value was chosen for one pose;
	 * carrying it across a scrub would apply it at every pose on the way, showing a half-typed opacity as a flat
	 * override across the whole range being scrubbed until release finally snapped it back to the track.  The
	 * session's own commit already clears them on a pose move - this is the same rule applied to the preview path,
	 * which does not go through commit - and says near the cursor how many shaped lattices went.
	 *
	 * Refused while the pose is pinned: the hand-off then holds the rest pose Edit mode shows, and a
	 * preview written into it would pose the rig under an edit of its neutral state.
	 *
	 * @param ParameterId id The parameter to set.
	 * @param Float value The new value.
	 */
	override fun preview(id: ParameterId, value: Float) {
		if (session.posePinned) {
			return
		}

		val current = liveParams.values

		if (current[id] == value) {
			return
		}

		liveParams.values = current + (id to value)
		session.discardPendingEditsForPose()
	}

	/**
	 * Records the current live pose as one undo step, ending a scrub gesture.
	 *
	 * Refused while the pose is pinned.  The session would refuse it as well; it stops here because what
	 * this hands over is the hand-off's pose, and while pinned that is not the rig's.
	 *
	 * @param Set<ParameterId> changedIds The parameters this gesture moved (for the history-panel label).
	 */
	override fun commit(changedIds: Set<ParameterId>) {
		if (session.posePinned) {
			return
		}

		session.commitPose(ParameterChange.SetValue(changedIds.toList()), liveParams.values)
	}
}