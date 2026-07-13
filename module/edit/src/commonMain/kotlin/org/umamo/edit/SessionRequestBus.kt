package org.umamo.edit

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The session's request buses: fire-and-forget signals from command handlers to the viewport
 * overlays.  Every one exists for the same reason - the operation needs something only an overlay
 * has (the pointer position, the projected geometry, the in-flight working positions), so the
 * session cannot execute it directly; it signals here and the observing overlay executes.  Pure
 * plumbing with no other session state involved, hence a separate collaborator; [EditorSession]
 * exposes each flow and request method unchanged.
 *
 * セッションのリクエストバス。ポインタ位置や投影ジオメトリを持つオーバーレイへ向けた発火信号で、
 * セッション自身は実行できない操作をここで通知する。
 */

internal class SessionRequestBus {
	private val mutableSnapRequests = MutableSharedFlow<SnapKind>(extraBufferCapacity = 4)

	/** The geometry-dependent snap requests (see [EditorSession.snapRequests]). */
	val snapRequests: SharedFlow<SnapKind> = mutableSnapRequests.asSharedFlow()

	private val mutableSelectLinkedRequests = MutableSharedFlow<SelectLinkedRequest>(extraBufferCapacity = 4)

	/** The Select Linked requests (see [EditorSession.selectLinkedRequests]). */
	val selectLinkedRequests: SharedFlow<SelectLinkedRequest> = mutableSelectLinkedRequests.asSharedFlow()

	private val mutableSwitchObjectRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

	/** The Alt+Q edited-mesh switch requests (see [EditorSession.switchObjectRequests]). */
	val switchObjectRequests: SharedFlow<Unit> = mutableSwitchObjectRequests.asSharedFlow()

	private val mutableRipRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

	/** The rip-at-pointer requests (see [EditorSession.ripRequests]). */
	val ripRequests: SharedFlow<Unit> = mutableRipRequests.asSharedFlow()

	private val mutableMeshConfirm = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

	/** The modal-gesture confirm requests (see [EditorSession.meshConfirmRequests]). */
	val meshConfirmRequests: SharedFlow<Unit> = mutableMeshConfirm.asSharedFlow()

	private val mutableMeshGestureCancel = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

	/** The selection-gesture cancel requests (see [EditorSession.meshGestureCancelRequests]). */
	val meshGestureCancelRequests: SharedFlow<Unit> = mutableMeshGestureCancel.asSharedFlow()

	/**
	 * Requests a geometry-dependent snap.
	 *
	 * @param SnapKind kind The snap to perform.
	 */
	fun requestSnap(kind: SnapKind) {
		mutableSnapRequests.tryEmit(kind)
	}

	/**
	 * Requests a Select Linked for one area's overlay to execute.
	 *
	 * @param SelectLinkedRequest request The flood variant plus the dispatch-time resolved area.
	 */
	fun requestSelectLinked(request: SelectLinkedRequest) {
		mutableSelectLinkedRequests.tryEmit(request)
	}

	/** Requests an Alt+Q edited-mesh switch. */
	fun requestSwitchObjectUnderCursor() {
		mutableSwitchObjectRequests.tryEmit(Unit)
	}

	/** Requests a rip at the pointer. */
	fun requestRip() {
		mutableRipRequests.tryEmit(Unit)
	}

	/** Requests that the gizmo overlay confirm the in-flight modal gesture. */
	fun requestMeshConfirm() {
		mutableMeshConfirm.tryEmit(Unit)
	}

	/** Requests that the gizmo overlay abandon any in-flight box / circle selection gesture. */
	fun requestMeshGestureCancel() {
		mutableMeshGestureCancel.tryEmit(Unit)
	}
}

/**
 * One Select Linked request: which flood variant, and which area's overlay executes it.  The area is
 * resolved ONCE at command dispatch (the hovered surface at that instant) and carried in the payload,
 * so two collectors reading a volatile independently can never double- or zero-execute when the
 * pointer crosses areas between resumptions.
 *
 * @property Boolean fromSelection True to flood from the whole selection (Ctrl+L), false from the cursor (L).
 * @property String? areaId The area whose overlay executes, or null when no surface was ever touched
 *   (then no collector matches and the request is a clean no-op).
 */
data class SelectLinkedRequest(val fromSelection: Boolean, val areaId: String?)
