package org.umamo.ui.viewport

import org.umamo.edit.EditorSession
import org.umamo.edit.ParameterChange
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.model.LiveParamsHandle
import kotlin.concurrent.Volatile

/**
 * Thread-safe hand-off of parameter values from the Compose UI thread to the render thread. The
 * value is an immutable map swapped wholesale, so a volatile reference is a safe publish; the render
 * thread compares identity to detect a change.
 *
 * Compose UI スレッドからレンダースレッドへのパラメータ値のスレッドセーフな受け渡し。
 */
class LiveParams(
	@Volatile var values: Map<ParameterId, Float>,
)

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
 * LiveParams を共通の LiveParamsHandle に適合させる。preview は volatile を直接書き（取り消し
 * しない）、commit はセッション経由で1つの取り消し段にする。
 *
 * @property LiveParams liveParams The underlying render-thread hand-off (the live pose mirror).
 * @property EditorSession session The session that records the committed pose as an undo step.
 */
class LiveParamsAdapter(private val liveParams: LiveParams, private val session: EditorSession) : LiveParamsHandle {
	override val values: Map<ParameterId, Float> get() = liveParams.values

	/**
	 * Previews one parameter's value toward the render thread without recording an undo step (the fast
	 * per-frame scrub path).
	 *
	 * @param ParameterId id The parameter to set.
	 * @param Float value The new value.
	 */
	override fun preview(id: ParameterId, value: Float) {
		liveParams.values = liveParams.values + (id to value)
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
