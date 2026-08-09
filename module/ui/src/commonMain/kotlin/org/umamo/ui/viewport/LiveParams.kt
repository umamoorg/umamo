package org.umamo.ui.viewport

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import org.umamo.edit.EditorSession
import org.umamo.edit.ParameterChange
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.KeyableTarget
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
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
}

/**
 * Builds the initial [LiveParams] from each parameter's default. Hosts with a headless-dump flow
 * (the desktop `UMAMO_DUMP_PARAMS` override) rewrite the values afterwards — environment reads are a
 * desktop dev affordance, not common code.
 *
 * @param PuppetModel puppet The rig (for parameter defaults).
 * @return LiveParams The starting parameter values.
 */
fun initialLiveParams(puppet: PuppetModel): LiveParams =
	LiveParams(puppet.parameters.associate { parameter -> parameter.id to parameter.default })

/**
 * Adapts the [LiveParams] volatile hand-off to the platform-neutral [LiveParamsHandle] the common
 * Parameters panel writes through. [preview] publishes a new immutable map (a volatile write) so the
 * render thread's reference compare detects the change and re-poses — the fast per-frame scrub path,
 * which does not touch undo. [commit] routes the gesture's final pose through the [EditorSession] as one
 * undo step, so a whole drag is undoable in a single Ctrl+Z; the session's pose StateFlow is then mirrored
 * back into this same volatile by the host, so an undo / redo re-poses the viewport.
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
	 * Moving the pose RETIRES every pending unkeyed channel edit, on the first frame that actually moves
	 * rather than at the gesture's end.  A pending value was chosen for one pose; carrying it across a scrub
	 * would apply it at every pose on the way, showing a half-typed opacity as a flat override across the
	 * whole range being scrubbed until release finally snapped it back to the track.  The session's own
	 * commit already clears them on a pose move - this is the same rule applied to the preview path, which
	 * does not go through commit.
	 *
	 * @param ParameterId id The parameter to set.
	 * @param Float value The new value.
	 */
	override fun preview(id: ParameterId, value: Float) {
		val current = liveParams.values
		if (current[id] == value) {
			return
		}
		liveParams.values = current + (id to value)
		session.clearPendingChannelEdits()
	}

	/**
	 * Records the current live pose as one undo step, ending a scrub gesture.
	 *
	 * @param Set<ParameterId> changedIds The parameters this gesture moved (for the history-panel label).
	 */
	override fun commit(changedIds: Set<ParameterId>) {
		session.commitPose(ParameterChange.SetValue(changedIds.toList()), liveParams.values)
	}
}