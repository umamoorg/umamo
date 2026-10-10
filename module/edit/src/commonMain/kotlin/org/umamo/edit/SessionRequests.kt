package org.umamo.edit

import kotlinx.coroutines.flow.SharedFlow

/**
 * The session's request buses: fire-and-forget signals from command handlers to the area that executes
 * them - a viewport or UV editor overlay, or the UV editor space itself.  Every one exists for the same
 * reason - the operation needs something only the receiving area has (the pointer position, the projected
 * geometry, the in-flight working positions, the area's own view state), so the session cannot execute it
 * directly; it signals here and the observing collector executes.  [EditorSession] exposes this surface by
 * delegation to its request bus.
 */
interface SessionRequests {
	/**
	 * Fires the geometry-dependent snap operations (Blender's Shift+S) for the active mode's overlay to
	 * execute: the posed world projections and the deformer-chain inverse those snaps need live with the
	 * overlays, not here (the same division as [meshConfirmRequests]).  The purely arithmetical snaps
	 * (cursor to world origin / to grid) never pass through - their command handlers set the cursor
	 * directly.
	 *
	 * The payload carries the dispatch-time resolved area (see [SnapRequest]) purely to elect ONE of the
	 * open viewports; the handlers themselves ignore it.
	 */
	val snapRequests: SharedFlow<SnapRequest>

	/**
	 * Requests a geometry-dependent snap (see [snapRequests]).
	 *
	 * @param SnapKind kind The snap to perform.
	 * @param String? areaId The executing overlay's area, resolved at command dispatch; null no-ops.
	 */
	fun requestSnap(kind: SnapKind, areaId: String?)

	/**
	 * Fires Select Linked (Blender's L / Ctrl+L) for one overlay to execute: a keymap command carries
	 * no pointer position, so the overlay (which tracks the pointer and owns the projected geometry)
	 * picks the seed and floods.  The payload carries the flood variant AND the dispatch-time resolved
	 * area (see [SelectLinkedRequest]), so collectors gate deterministically on their own area id
	 * instead of re-reading a pointer-side volatile at collect time.
	 */
	val selectLinkedRequests: SharedFlow<SelectLinkedRequest>

	/**
	 * Requests a Select Linked (see [selectLinkedRequests]).
	 *
	 * @param Boolean fromSelection True to flood from the whole selection (Ctrl+L), false from the cursor (L).
	 * @param String? areaId The executing overlay's area, resolved at command dispatch; null no-ops.
	 */
	fun requestSelectLinked(fromSelection: Boolean, areaId: String?)

	/**
	 * Fires a UV snap (the UV editor's Shift+S pie) for one UV editor overlay to execute: the shown
	 * surface's dimensions and display geometry live with the overlay, so it performs the snap over
	 * the texture coordinates in Edit mode or the placed art tiles in Object mode (the texture-space
	 * sibling of [snapRequests]).  The payload carries the
	 * operation AND the dispatch-time resolved area (see [UvSnapRequest]), so the collector gates
	 * deterministically on its own area id.
	 */
	val uvSnapRequests: SharedFlow<UvSnapRequest>

	/**
	 * Requests a UV snap (see [uvSnapRequests]).
	 *
	 * @param UvSnapRequest request The snap operation plus the executing overlay's area, resolved at
	 *   command dispatch; a null area (the hovered surface was not a UV editor) no-ops.
	 */
	fun requestUvSnap(request: UvSnapRequest)

	/**
	 * Fires a mirror (the uv.mirrorU / uv.mirrorV commands) for one UV editor overlay to execute: the
	 * axis a mirror reflects about depends on the surface being authored over - an atlas page or a
	 * source layer - and only the overlay knows which it is showing, so it supplies the frame and calls
	 * [EditorSession.mirrorSelectedUvs].  The payload carries the axis AND the dispatch-time resolved area (see
	 * [UvMirrorRequest]), so the collector gates deterministically on its own area id.
	 */
	val uvMirrorRequests: SharedFlow<UvMirrorRequest>

	/**
	 * Requests a mirror (see [uvMirrorRequests]).
	 *
	 * @param UvMirrorRequest request The mirror axis plus the executing overlay's area, resolved at
	 *   command dispatch; a null area (the hovered surface was not a UV editor) no-ops.
	 */
	fun requestUvMirror(request: UvMirrorRequest)

	/**
	 * Fires a page switch (the uv.page.* palette commands) for one UV editor area to execute: the
	 * per-area texture selection (the page pin) lives with the area's view state, not the session, so
	 * the space applies the transition itself.  The payload carries the operation AND the
	 * dispatch-time resolved area (see [UvPageRequest]), so the collector gates deterministically on
	 * its own area id.
	 */
	val uvPageRequests: SharedFlow<UvPageRequest>

	/**
	 * Requests a page switch (see [uvPageRequests]).
	 *
	 * @param UvPageRequest request The page operation plus the executing area, resolved at command
	 *   dispatch; a null area (the hovered surface was not a UV editor) no-ops.
	 */
	fun requestUvPage(request: UvPageRequest)

	/**
	 * Fires "switch the edited mesh to the drawable under the cursor" (Alt+Q) for the Edit overlay to
	 * execute - the pointer position and the pick live there (the same division as
	 * [selectLinkedRequests]).  The payload IS the dispatch-time resolved area, so the collector gates on
	 * its own id rather than re-reading a pointer-side volatile at collect time.
	 */
	val switchObjectRequests: SharedFlow<String?>

	/**
	 * Requests an Alt+Q edited-mesh switch (see [switchObjectRequests]).
	 *
	 * @param String? areaId The executing overlay's area, resolved at command dispatch; null no-ops.
	 */
	fun requestSwitchObjectUnderCursor(areaId: String?)

	/**
	 * Fires a rip (Blender's V) for the Edit overlay to execute: which side of the fan follows the
	 * ripped copies depends on the pointer, which lives with the overlay (the same division as
	 * [selectLinkedRequests]).  The payload IS the dispatch-time resolved area, so the collector gates on
	 * its own id rather than re-reading a pointer-side volatile at collect time.
	 */
	val ripRequests: SharedFlow<String?>

	/**
	 * Requests a rip at the pointer (see [ripRequests]).
	 *
	 * @param String? areaId The executing overlay's area, resolved at command dispatch; null no-ops.
	 */
	fun requestRip(areaId: String?)

	/**
	 * Fires when an in-flight modal mesh gesture should confirm. The working positions live in the desktop
	 * overlay, so the session cannot commit directly - it signals here and the overlay commits. This is the
	 * keyboard path (Enter); a primary click confirms in the overlay's own pointer loop.
	 */
	val meshConfirmRequests: SharedFlow<Unit>

	/** Requests that the gizmo overlay confirm the in-flight modal gesture (bound to Enter, like a click). */
	fun requestMeshConfirm()

	/**
	 * Fires when an in-flight selection gesture should be abandoned (Escape).  The box rubber-band and the
	 * circle stroke live in the overlay's local state, so the session cannot discard them directly - it
	 * signals here and the overlay clears them.  Clearing a latched tool ([SessionToolLatches.clearSelectTool]) already tells
	 * the overlay to abandon its gesture through the tool flow; this is the extra path for a non-armed box
	 * drag, which owns no tool state to change.
	 */
	val meshGestureCancelRequests: SharedFlow<Unit>

	/** Requests that the gizmo overlay abandon any in-flight box / circle selection gesture (bound to Escape). */
	fun requestMeshGestureCancel()
}