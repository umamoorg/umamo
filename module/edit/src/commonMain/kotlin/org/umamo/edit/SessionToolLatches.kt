package org.umamo.edit

import kotlinx.coroutines.flow.StateFlow
import org.umamo.runtime.model.DrawableId

/**
 * The session's transient tool state and its controls: the modal operator latches, the armed select tool,
 * the zoom region, the axis constraint, the viewport-gesture flag, the two stroke previews, the pie menu,
 * and the parked parameter choice.  Everything here coordinates the viewport overlays without ever being
 * snapshotted or entering the change bus.
 *
 * [EditorSession] exposes this surface by delegation to its tool latches, so a call site reads
 * `session.activeMeshOperator` and never sees the collaborator.  The mode / selection / pose GUARDS that
 * decide whether a latch may be set at all - the begin and arm entry points - are extensions on the
 * session, not members here: a latch is set only after the session has agreed.
 */
interface SessionToolLatches {
	/**
	 * The modal mesh operator currently running (Grab / Scale / Rotate) with its initiating viewport area,
	 * or null. Transient UI coordination (not snapshotted, not on the bus): a registry command latches it,
	 * the initiating area's gizmo overlay observes it to drive the gesture (bystander viewports stay
	 * inert), and clears it on confirm / cancel.
	 */
	val activeMeshOperator: StateFlow<ActiveOperator?>

	/**
	 * The modal OBJECT operator currently running (Grab / Scale / Rotate over the selected drawables' whole
	 * geometry) with its initiating viewport area, or null. The Object-mode sibling of [activeMeshOperator]:
	 * a separate latch because the object overlay captures N drawables where the mesh overlay captures one,
	 * so the two must be distinguishable. Transient UI coordination like [activeMeshOperator] - not
	 * snapshotted, not on the bus - latched by a registry command, observed by the initiating area's object
	 * gizmo overlay, cleared on confirm / cancel / leaving Object mode.
	 */
	val activeObjectOperator: StateFlow<ActiveOperator?>

	/**
	 * The modal UV operator currently running (Grab / Scale / Rotate over the selected vertices' texture
	 * coordinates) with its initiating UV-editor area, or null. The UV-editor sibling of
	 * [activeMeshOperator]: a separate latch so the puppet viewport's gizmo overlays and the UV editor's
	 * overlay can never cross-capture one gesture (each overlay's capture effect keys on its own latch).
	 * Transient UI coordination like the others - not snapshotted, not on the bus - latched by a registry
	 * command, observed by the initiating area's UV overlay, cleared on confirm / cancel / leaving Edit mode.
	 */
	val activeUvOperator: StateFlow<ActiveOperator?>

	/**
	 * The one modal transform operator running, from whichever of the three families latched it, or null.
	 *
	 * The families are mutually exclusive, so callers that only need to know whether SOME transform is in
	 * flight - the shell's modal key ladder, deciding who owns Escape / Enter / the axis keys - ask this
	 * instead of testing all three.  An instantaneous read, not a flow: the callers that want it read it at an
	 * instant and never observe it, so a combined flow would advertise a reactivity nobody consumes.
	 */
	val activeOperator: ActiveOperator?

	/**
	 * Whether nothing is in flight that a model change would land under: no modal transform operator,
	 * no viewport gesture, no circle-select stroke, no armed select tool, and no open pie menu.  The gate
	 * a watched-file reload waits behind, so the art never changes under a hand that is mid-drag.  An
	 * instantaneous read, like [activeOperator].
	 */
	val isQuiescent: Boolean

	/**
	 * Cancels whichever modal transform operator is running, if any - the family-agnostic counterpart to
	 * [clearMeshOperator] / [clearObjectOperator] / [clearUvOperator].
	 */
	fun clearActiveOperator()

	/**
	 * The transient preview of which drawables an in-flight Object-mode circle stroke is painting, or null when
	 * no stroke is live. The GPU-tint bridge overlays this on top of the committed [EditorSession.selection] so painted
	 * drawables light up immediately without committing each frame (which would spam undo). Not snapshotted,
	 * not on the bus (transient UI coordination like [activeSelectTool]); the stroke commits once on release
	 * via [EditorSession.setSelection] and clears this back to null.
	 */
	val previewSelection: StateFlow<Set<DrawableId>?>

	/**
	 * Publishes the transient circle-stroke preview selection (see [previewSelection]); pass null to clear it.
	 *
	 * @param Set<DrawableId>? drawableIds The drawables currently painted by the stroke, or null to clear.
	 */
	fun setPreviewSelection(drawableIds: Set<DrawableId>?)

	/**
	 * The transient preview of what an in-flight Edit-mode circle stroke has painted so far, or null when no
	 * stroke is live: the whole selection the stroke would commit, in [EditorSession.meshSelection]'s shape.  The renderer's
	 * mesh overlay shows it in place of the committed [EditorSession.meshSelection], so painted elements light up under
	 * the brush without committing each stamp (which would spam undo).  Not snapshotted, not on the bus
	 * (transient UI coordination like [previewSelection]); the stroke commits once on release via
	 * [EditorSession.setMeshSelection] and clears this back to null.
	 */
	val meshPreviewSelection: StateFlow<MeshSelection?>

	/**
	 * Publishes the transient Edit-mode circle-stroke preview (see [meshPreviewSelection]); pass null to
	 * clear it.
	 *
	 * @param MeshSelection? selection The selection the stroke has painted so far, or null to clear.
	 */
	fun setMeshPreviewSelection(selection: MeshSelection?)

	/**
	 * True while a select drag is held in a viewport or UV area - a box (armed or not) or a circle stroke,
	 * in either mode - so navigation does not also pan, the shell routes Escape to a gesture cancel instead
	 * of its next Escape behavior (clearing the object selection), and undo / redo wait for the release.
	 * Transient UI coordination like [previewSelection] - not snapshotted, not on the bus; the overlay sets
	 * it at press and clears it on release, cancel, or when it leaves composition.
	 */
	val viewportGestureActive: StateFlow<Boolean>

	/**
	 * Publishes whether a select drag is held (see [viewportGestureActive]).
	 *
	 * @param Boolean active True while the overlay's select drag owns the pointer.
	 */
	fun setViewportGestureActive(active: Boolean)

	/**
	 * The Edit-mode selection tool currently armed (Box or Circle), or null. Transient UI coordination like
	 * [activeMeshOperator] (not snapshotted, not on the bus): a registry command latches it, the gizmo overlay
	 * observes it to reinterpret pointer input, and it clears on completion / cancel / leaving Edit mode.
	 */
	val activeSelectTool: StateFlow<ActiveSelectTool?>

	/**
	 * The viewport area id whose Zoom Region gesture is armed (Blender's Shift+B), or null. Mode-agnostic -
	 * Zoom Region works in Object and Edit mode alike - so it is keyed by area rather than gated on the mode,
	 * and the top-level region overlay for that area reads it to capture the drag. Transient, not snapshotted.
	 */
	val zoomRegionArmedArea: StateFlow<String?>

	/**
	 * True while the active mesh operator was latched with proportional editing suppressed - the
	 * duplicate / rip auto-grabs, which place fresh copies and must never drag bystander vertices.
	 * Transient latch state (never snapshotted), reset whenever the operator latches or clears.
	 */
	val activeMeshOperatorSuppressesProportional: Boolean

	/**
	 * Whether a mesh operator of [kind], latched as the active one, weights the unselected vertices near
	 * the selection by proportional editing: every operator but Vertex Slide (positions-only, one vertex
	 * along one edge), unless its latch suppressed proportional editing (the duplicate / rip auto-grab).
	 * The one rule every proportional gate asks - the capture, the wheel, a mid-gesture change, the strip's
	 * rows, the ring, and the status badge.  It takes the kind rather than reading the latch because each
	 * gate already holds the kind it is deciding for.
	 *
	 * @param MeshOperatorKind kind The latched operator's kind.
	 * @return Boolean True when the gesture takes proportional weights.
	 */
	fun meshOperatorTakesProportional(kind: MeshOperatorKind): Boolean

	/** Clears the active modal mesh operator (the overlay calls this on confirm or cancel). */
	fun clearMeshOperator()

	/** Clears the active modal object operator (the overlay calls this on confirm or cancel). */
	fun clearObjectOperator()

	/** Clears the active modal UV operator (the overlay calls this on confirm or cancel). */
	fun clearUvOperator()

	/**
	 * Sets the Circle-select brush radius (clamped), remembering it for the next arm.  When a Circle tool is
	 * live its radius updates in place so the overlay redraws; otherwise only the remembered value moves.
	 *
	 * @param Float radiusPx The requested radius in viewport pixels.
	 */
	fun setCircleRadius(radiusPx: Float)

	/** Grows the Circle-select radius by one step (numpad +); a no-op unless a Circle tool is live. */
	fun growCircleRadius()

	/** Shrinks the Circle-select radius by one step (numpad -); a no-op unless a Circle tool is live. */
	fun shrinkCircleRadius()

	/** Clears any armed Box / Circle select tool (the overlay calls this on completion, Esc, or RMB). */
	fun clearSelectTool()

	/**
	 * Arms the Zoom Region gesture (Blender's Shift+B) for [areaId].  Mode-agnostic - valid in Object and
	 * Edit mode.  Clears any latched Edit-mode tool so two overlays never capture at once.
	 *
	 * @param String areaId The viewport area the gesture will run in (the pointer's active area).
	 */
	fun armZoomRegion(areaId: String)

	/** Disarms the Zoom Region gesture (the overlay calls this on completion, Esc, or RMB). */
	fun disarmZoomRegion()

	/**
	 * Releases every latch [areaId] holds: the modal operator it initiated (whichever family), the select
	 * tool it armed, and the Zoom Region armed for it; latches other areas hold are untouched.  The
	 * area-death guard: a leaf can leave composition mid-gesture (a corner-join, a space switch via the
	 * header dropdown, a workspace tab switch), which cancels the overlay's latch effect WITHOUT running its
	 * teardown - and a latch naming a dead area would never be driven, confirmed, or resolved.
	 *
	 * @param String areaId The area leaving composition.
	 * @return Boolean True when a modal operator the area initiated was released - the renderer may then be
	 *   showing that gesture's uncommitted preview, and the caller resyncs it to the committed model.
	 */
	fun releaseArea(areaId: String): Boolean

	/**
	 * The axis the in-flight modal Grab / Scale is locked to, or null when unconstrained.  Set by the
	 * shell's key ladder (X / Z during a modal gesture - the keymap cannot see those keys, the operator
	 * swallows them), read by the gizmo overlays' drive loops, cleared whenever an operator latches or
	 * clears.  Transient coordination like [activeMeshOperator].
	 */
	val axisConstraint: StateFlow<TransformAxisConstraint?>

	/**
	 * Toggles the modal axis constraint (pressing a lock's own key again releases it; pressing the other
	 * axis switches).  A no-op unless a Grab or Scale operator is in flight - Rotate has no axis to lock
	 * and idle keys must not arm a stale constraint.
	 *
	 * @param TransformAxisConstraint axis The axis whose lock to toggle.
	 */
	fun toggleAxisConstraint(axis: TransformAxisConstraint)

	/**
	 * The radial pie menu currently open over the viewport, or null.  Transient UI coordination: a
	 * command opens it (Period / Shift+S / the merge menu), the pie host composable renders it at the
	 * pointer, and picking an entry or Escape closes it.
	 */
	val activePieMenu: StateFlow<PieMenuKind?>

	/**
	 * Opens a pie menu over the viewport (closing any other transient latch is not needed - a pie is
	 * display-only and the key ladder swallows input while one is open).
	 *
	 * @param PieMenuKind kind The pie to open.
	 */
	fun openPieMenu(kind: PieMenuKind)

	/** Closes the open pie menu (entry picked, Escape, or a click outside). */
	fun closePieMenu()

	/**
	 * The keyform edit waiting on an axis, or null.  Transient UI coordination exactly like
	 * [activePieMenu]: an ambiguous `I` / `Alt+I` parks here, the shell lists the candidate parameters at
	 * the pointer, and picking one replays the edit through [EditorSession.resolveParameterChoice].
	 */
	val pendingParameterChoice: StateFlow<ParameterChoiceRequest?>

	/**
	 * Parks a keyform edit until the user picks the axis it writes on.
	 *
	 * @param ParameterChoiceRequest request The parked edit and the axes to choose between.
	 */
	fun requestParameterChoice(request: ParameterChoiceRequest)

	/** Abandons the parked keyform edit (Escape, or a click outside the prompt). */
	fun cancelParameterChoice()
}