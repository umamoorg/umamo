package org.umamo.edit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.umamo.runtime.model.DrawableId

/**
 * The session's transient tool state: the modal operator latches, the armed select tool, the zoom
 * region, the axis constraint, the viewport-gesture flag, the Object- and Edit-mode stroke previews, the pie
 * menu, and the parked parameter choice - everything that coordinates the viewport overlays without ever
 * being snapshotted, saved, or entering the change bus.  The mutual-exclusion story (a transform operator
 * owns the pointer, so arming anything drops the others) lives in [clearTransient].  The settings a saved
 * document carries - cursors, pivot, grid, proportional editing - are [ToolSettings], not here.
 *
 * The public face is [SessionToolLatches]; [EditorSession] delegates it to this one instance and keeps the
 * mode / selection / pose guards for itself: the latch and arm entry points below are the session's to
 * call, never a caller's.
 */
internal class ToolLatches : SessionToolLatches {
	private val mutableActiveMeshOperator = MutableStateFlow<ActiveOperator?>(null)

	/** The modal mesh operator currently running, or null (see [SessionToolLatches.activeMeshOperator]). */
	override val activeMeshOperator: StateFlow<ActiveOperator?> = mutableActiveMeshOperator.asStateFlow()

	private val mutableActiveObjectOperator = MutableStateFlow<ActiveOperator?>(null)

	/** The modal object operator currently running, or null (see [SessionToolLatches.activeObjectOperator]). */
	override val activeObjectOperator: StateFlow<ActiveOperator?> = mutableActiveObjectOperator.asStateFlow()

	private val mutableActiveUvOperator = MutableStateFlow<ActiveOperator?>(null)

	/** The modal UV operator currently running, or null (see [SessionToolLatches.activeUvOperator]). */
	override val activeUvOperator: StateFlow<ActiveOperator?> = mutableActiveUvOperator.asStateFlow()

	private val mutableActiveSelectTool = MutableStateFlow<ActiveSelectTool?>(null)

	/** The armed Box / Circle select tool, or null (see [SessionToolLatches.activeSelectTool]). */
	override val activeSelectTool: StateFlow<ActiveSelectTool?> = mutableActiveSelectTool.asStateFlow()

	private val mutableZoomRegionArmedArea = MutableStateFlow<String?>(null)

	/** The area id whose Zoom Region gesture is armed, or null (see [SessionToolLatches.zoomRegionArmedArea]). */
	override val zoomRegionArmedArea: StateFlow<String?> = mutableZoomRegionArmedArea.asStateFlow()

	private val mutableAxisConstraint = MutableStateFlow<TransformAxisConstraint?>(null)

	/** The axis the in-flight modal transform is locked to, or null (see [SessionToolLatches.axisConstraint]). */
	override val axisConstraint: StateFlow<TransformAxisConstraint?> = mutableAxisConstraint.asStateFlow()

	private val mutableViewportGestureActive = MutableStateFlow(false)

	/** True while a select drag is held (see [SessionToolLatches.viewportGestureActive]). */
	override val viewportGestureActive: StateFlow<Boolean> = mutableViewportGestureActive.asStateFlow()

	private val mutablePreviewSelection = MutableStateFlow<Set<DrawableId>?>(null)

	/** The transient circle-stroke preview selection, or null (see [SessionToolLatches.previewSelection]). */
	override val previewSelection: StateFlow<Set<DrawableId>?> = mutablePreviewSelection.asStateFlow()

	private val mutableMeshPreviewSelection = MutableStateFlow<MeshSelection?>(null)

	/** The transient Edit-mode circle-stroke preview, or null (see [SessionToolLatches.meshPreviewSelection]). */
	override val meshPreviewSelection: StateFlow<MeshSelection?> = mutableMeshPreviewSelection.asStateFlow()

	private val mutableActivePieMenu = MutableStateFlow<PieMenuKind?>(null)

	/** The radial pie menu currently open, or null (see [SessionToolLatches.activePieMenu]). */
	override val activePieMenu: StateFlow<PieMenuKind?> = mutableActivePieMenu.asStateFlow()

	private val mutablePendingParameterChoice = MutableStateFlow<ParameterChoiceRequest?>(null)

	/** The keyform edit waiting on an axis, or null (see [SessionToolLatches.pendingParameterChoice]). */
	override val pendingParameterChoice: StateFlow<ParameterChoiceRequest?> = mutablePendingParameterChoice.asStateFlow()

	// The Circle-select brush radius carried across re-entry (Blender remembers it). Deliberately NOT
	// part of EditorSnapshot - re-arming the tool restores the last size.
	private var lastCircleRadiusPx: Float = DEFAULT_CIRCLE_RADIUS_PX

	/**
	 * True while the active mesh operator was latched with proportional editing suppressed - the
	 * duplicate / rip auto-grabs, which place fresh copies and must never drag bystander vertices.
	 * Reset whenever the operator latches or clears.
	 */
	override var activeMeshOperatorSuppressesProportional: Boolean = false
		private set

	/**
	 * Clears the mutually-exclusive transient tool latches - the modal mesh / object / UV operators and
	 * the armed select tool - plus the optional extras the caller names.  Every site that latches a tool,
	 * switches mode, or restores a snapshot funnels through here so the mutual-exclusion story lives in
	 * one place; a caller about to latch one of the four simply overwrites its own slot right after.
	 *
	 * @param Boolean clearZoomRegion True to also disarm the zoom region (the tool-latch sites; a mode
	 *   switch or restore leaves it armed - the gesture is mode-agnostic).
	 * @param Boolean clearAxisConstraint True to also drop the axis lock (the operator begins and
	 *   restore; an axis lock is meaningless without a live operator).
	 * @param Boolean clearViewportGesture True to also end the viewport-gesture flag (mode switches and
	 *   restore, which tear down any in-flight gesture).
	 */
	fun clearTransient(
		clearZoomRegion: Boolean = false,
		clearAxisConstraint: Boolean = false,
		clearViewportGesture: Boolean = false,
	) {
		mutableActiveMeshOperator.value = null
		mutableActiveObjectOperator.value = null
		mutableActiveUvOperator.value = null
		mutableActiveSelectTool.value = null
		if (clearZoomRegion) {
			mutableZoomRegionArmedArea.value = null
		}
		if (clearAxisConstraint) {
			mutableAxisConstraint.value = null
		}
		if (clearViewportGesture) {
			mutableViewportGestureActive.value = false
		}
	}

	/**
	 * Latches a modal mesh operator (the caller has already checked the Edit-mode preconditions).
	 * Drops any armed select tool / zoom region first (mutual exclusion), and publishes the
	 * suppression BEFORE the operator itself: the overlay's latch effect keys on the operator flow and
	 * must read a consistent flag when it fires.
	 *
	 * @param MeshOperatorKind kind The operator to begin (Grab / Scale / Rotate).
	 * @param String areaId The initiating viewport's area id (only its overlay drives the gesture).
	 * @param Boolean suppressProportional True to ignore proportional editing for this gesture.
	 */
	fun latchMeshOperator(kind: MeshOperatorKind, areaId: String, suppressProportional: Boolean) {
		clearTransient(clearZoomRegion = true, clearAxisConstraint = true)
		activeMeshOperatorSuppressesProportional = suppressProportional
		mutableActiveMeshOperator.value = ActiveOperator(kind, areaId)
	}

	/** Clears the active modal mesh operator (confirm or cancel), dropping the axis lock with it. */
	override fun clearMeshOperator() {
		mutableActiveMeshOperator.value = null
		mutableAxisConstraint.value = null
		activeMeshOperatorSuppressesProportional = false
	}

	/**
	 * Latches a modal object operator (the caller has already checked the Object-mode preconditions),
	 * dropping any other latched tool first (mutual exclusion).
	 *
	 * @param MeshOperatorKind kind The operator to begin (Grab / Scale / Rotate).
	 * @param String areaId The initiating viewport's area id (only its overlay drives the gesture).
	 */
	fun latchObjectOperator(kind: MeshOperatorKind, areaId: String) {
		clearTransient(clearZoomRegion = true, clearAxisConstraint = true)
		mutableActiveObjectOperator.value = ActiveOperator(kind, areaId)
	}

	/** Clears the active modal object operator (confirm or cancel), dropping the axis lock with it. */
	override fun clearObjectOperator() {
		mutableActiveObjectOperator.value = null
		mutableAxisConstraint.value = null
	}

	/**
	 * Latches a modal UV operator (the caller has already checked the Edit-mode and UV-validity
	 * preconditions), dropping any other latched tool first (mutual exclusion).
	 *
	 * @param MeshOperatorKind kind The operator to begin (Grab / Scale / Rotate).
	 * @param String areaId The initiating UV editor's area id (only its overlay drives the gesture).
	 */
	fun latchUvOperator(kind: MeshOperatorKind, areaId: String) {
		clearTransient(clearZoomRegion = true, clearAxisConstraint = true)
		mutableActiveUvOperator.value = ActiveOperator(kind, areaId)
	}

	/** Clears the active modal UV operator (confirm or cancel), dropping the axis lock with it. */
	override fun clearUvOperator() {
		mutableActiveUvOperator.value = null
		mutableAxisConstraint.value = null
	}

	/**
	 * The one modal transform operator currently running, whichever family latched it, or null.
	 *
	 * Safe to collapse the three into one answer because [clearTransient] runs at every latch site, so at
	 * most one is ever non-null.  A plain getter rather than a combined StateFlow: the callers that want
	 * "is any transform running" read it at an instant (the shell's key ladder) and never observe it, so a
	 * combine would advertise a reactivity nobody consumes.
	 */
	override val activeOperator: ActiveOperator?
		get() = activeMeshOperator.value ?: activeObjectOperator.value ?: activeUvOperator.value

	/** See [SessionToolLatches.isQuiescent]: no operator, gesture, stroke preview, armed tool, or open pie. */
	override val isQuiescent: Boolean
		get() =
			activeOperator == null &&
				!mutableViewportGestureActive.value &&
				mutablePreviewSelection.value == null &&
				mutableMeshPreviewSelection.value == null &&
				mutableActiveSelectTool.value == null &&
				mutableActivePieMenu.value == null

	/**
	 * See [SessionToolLatches.meshOperatorTakesProportional]: every operator but Vertex Slide, unless the
	 * latch suppressed proportional editing.
	 *
	 * @param MeshOperatorKind kind The latched operator's kind.
	 * @return Boolean True when the gesture takes proportional weights.
	 */
	override fun meshOperatorTakesProportional(kind: MeshOperatorKind): Boolean =
		kind != MeshOperatorKind.VertexSlide && !activeMeshOperatorSuppressesProportional

	/**
	 * Clears whichever operator family is running, if any.
	 *
	 * Dispatches to that family's own clear rather than blanking all three: only [clearMeshOperator]
	 * resets the proportional-suppression flag, so a three-way blank would quietly change the duplicate
	 * and rip auto-grab behavior.
	 */
	override fun clearActiveOperator() {
		when {
			mutableActiveMeshOperator.value != null -> clearMeshOperator()
			mutableActiveObjectOperator.value != null -> clearObjectOperator()
			mutableActiveUvOperator.value != null -> clearUvOperator()
		}
	}

	/**
	 * Arms the Box-select tool (the caller has already checked the mode preconditions).
	 *
	 * @param String areaId The arming viewport's area id (only its overlay drives the drag).
	 */
	fun armBoxSelect(areaId: String) {
		clearTransient(clearZoomRegion = true)
		mutableActiveSelectTool.value = ActiveSelectTool.BoxArmed(areaId)
	}

	/**
	 * Arms the Circle-select tool at the remembered radius (preconditions checked by the caller).
	 *
	 * @param String areaId The arming viewport's area id (only its overlay drives the brush).
	 */
	fun armCircleSelect(areaId: String) {
		clearTransient(clearZoomRegion = true)
		mutableActiveSelectTool.value = ActiveSelectTool.Circle(lastCircleRadiusPx, areaId)
	}

	/**
	 * Sets the Circle-select brush radius (clamped), remembering it for the next arm.  When a Circle
	 * tool is live its radius updates in place so the overlay redraws; otherwise only the remembered
	 * value moves.
	 *
	 * @param Float radiusPx The requested radius in viewport pixels.
	 */
	override fun setCircleRadius(radiusPx: Float) {
		val clamped = radiusPx.coerceIn(MIN_CIRCLE_RADIUS_PX, MAX_CIRCLE_RADIUS_PX)
		lastCircleRadiusPx = clamped
		val current = mutableActiveSelectTool.value
		if (current is ActiveSelectTool.Circle) {
			// A resize keeps the brush in its arming area - only re-arming moves the tool.
			mutableActiveSelectTool.value = ActiveSelectTool.Circle(clamped, current.areaId)
		}
	}

	/** Grows the Circle-select radius by one step; a no-op unless a Circle tool is live. */
	override fun growCircleRadius() {
		val current = mutableActiveSelectTool.value
		if (current is ActiveSelectTool.Circle) {
			setCircleRadius(current.radiusPx + CIRCLE_RADIUS_STEP_PX)
		}
	}

	/** Shrinks the Circle-select radius by one step; a no-op unless a Circle tool is live. */
	override fun shrinkCircleRadius() {
		val current = mutableActiveSelectTool.value
		if (current is ActiveSelectTool.Circle) {
			setCircleRadius(current.radiusPx - CIRCLE_RADIUS_STEP_PX)
		}
	}

	/** Clears any armed Box / Circle select tool. */
	override fun clearSelectTool() {
		mutableActiveSelectTool.value = null
	}

	/**
	 * Arms the Zoom Region gesture for [areaId], clearing any latched tool so two overlays never
	 * capture at once.
	 *
	 * @param String areaId The viewport area the gesture will run in.
	 */
	override fun armZoomRegion(areaId: String) {
		clearTransient()
		mutableZoomRegionArmedArea.value = areaId
	}

	/** Disarms the Zoom Region gesture. */
	override fun disarmZoomRegion() {
		mutableZoomRegionArmedArea.value = null
	}

	/**
	 * See [SessionToolLatches.releaseArea]: each family's own clear, so the proportional-suppression flag
	 * and the axis lock go with the operator exactly as a confirm or cancel would take them.
	 *
	 * @param String areaId The area leaving composition.
	 * @return Boolean True when a modal operator the area initiated was released.
	 */
	override fun releaseArea(areaId: String): Boolean {
		var releasedOperator = false
		if (mutableActiveMeshOperator.value?.areaId == areaId) {
			clearMeshOperator()
			releasedOperator = true
		}
		if (mutableActiveObjectOperator.value?.areaId == areaId) {
			clearObjectOperator()
			releasedOperator = true
		}
		if (mutableActiveUvOperator.value?.areaId == areaId) {
			clearUvOperator()
			releasedOperator = true
		}
		if (mutableActiveSelectTool.value?.areaId == areaId) {
			clearSelectTool()
		}
		if (mutableZoomRegionArmedArea.value == areaId) {
			disarmZoomRegion()
		}
		return releasedOperator
	}

	/**
	 * Toggles the modal axis constraint (pressing a lock's own key again releases it; pressing the
	 * other axis switches).  A no-op unless a Grab or Scale operator is in flight - Rotate has no axis
	 * to lock and idle keys must not arm a stale constraint.
	 *
	 * @param TransformAxisConstraint axis The axis whose lock to toggle.
	 */
	override fun toggleAxisConstraint(axis: TransformAxisConstraint) {
		val operator = mutableActiveMeshOperator.value ?: mutableActiveObjectOperator.value ?: mutableActiveUvOperator.value ?: return
		if (operator.kind == MeshOperatorKind.Rotate) {
			return
		}
		mutableAxisConstraint.value = if (mutableAxisConstraint.value == axis) null else axis
	}

	/**
	 * Publishes whether a non-armed viewport gesture is in flight.
	 *
	 * @param Boolean active True while the overlay's gesture owns the pointer.
	 */
	override fun setViewportGestureActive(active: Boolean) {
		mutableViewportGestureActive.value = active
	}

	/**
	 * Publishes the transient circle-stroke preview selection; pass null to clear it.
	 *
	 * @param Set<DrawableId>? drawableIds The drawables currently painted by the stroke, or null.
	 */
	override fun setPreviewSelection(drawableIds: Set<DrawableId>?) {
		mutablePreviewSelection.value = drawableIds
	}

	/**
	 * Publishes the transient Edit-mode circle-stroke preview; pass null to clear it.
	 *
	 * @param MeshSelection? selection The selection the stroke has painted so far, or null.
	 */
	override fun setMeshPreviewSelection(selection: MeshSelection?) {
		mutableMeshPreviewSelection.value = selection
	}

	/**
	 * Opens a pie menu over the viewport.
	 *
	 * @param PieMenuKind kind The pie to open.
	 */
	override fun openPieMenu(kind: PieMenuKind) {
		mutableActivePieMenu.value = kind
	}

	/** Closes the open pie menu. */
	override fun closePieMenu() {
		mutableActivePieMenu.value = null
	}

	/**
	 * Parks a keyform edit until the user picks the axis it writes on.
	 *
	 * @param ParameterChoiceRequest request The parked edit and the axes to choose between.
	 */
	override fun requestParameterChoice(request: ParameterChoiceRequest) {
		mutablePendingParameterChoice.value = request
	}

	/** Abandons the parked keyform edit (an axis was picked, Escape, or a click outside the prompt). */
	override fun cancelParameterChoice() {
		mutablePendingParameterChoice.value = null
	}
}