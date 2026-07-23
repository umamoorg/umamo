package org.umamo.edit

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.firstEditableDrawableInPanelOrder

/**
 * Where the shell surfaces a [Notice]: the status-bar slot, or a transient label next to the pointer
 * (Blender's near-cursor "can't do this because" style, for feedback about a blocked viewport gesture).
 */
enum class NoticePlacement {
	StatusBar,
	NearCursor,
}

/**
 * A transient user notice: a stable message key plus a monotonic [serial] that distinguishes it from an
 * identical earlier message, so the UI can re-time its dismissal even when the same notice repeats.
 * Carries a key rather than display text so this module stays presentation-free and the UI layer resolves
 * the localized string (the same pattern as [Change.labelKey]).
 *
 * @property String messageKey The stable notice key the UI layer resolves to a localized message.
 * @property Long serial The stamping order (see [EditorSession.emitNotice]); higher is newer.
 * @property NoticePlacement placement Where the shell surfaces this notice.
 */
data class Notice(val messageKey: String, val serial: Long, val placement: NoticePlacement)

/**
 * The single mutable owner of one open document: the live [model], the ephemeral editor state
 * ([selection], [mode]), the undo [History], and the change-event bus. Every edit flows through here so
 * undo, change events, and dirty-tracking stay consistent.
 *
 * Two channels, per the history design: [changes] emits on every mutation (the "everything emits a
 * change event" completeness), while the history stack records only the undoable subset. A document
 * mutation ([mutate]) and a selection gesture ([setSelection]) each push one step; a mode toggle
 * ([setMode]) and future tool/brush switches are transient — they emit but never become steps.
 *
 * Undo is by immutable snapshot, never inverse op: [undo] / [redo] just republish a stored
 * [EditorSnapshot]. Dirty is reference-equality of the model against the last-saved instance — because
 * undo restores the exact prior model instance and selection-only steps reuse the same instance,
 * `model !== savedModel` is correct across undo/redo and is never tripped by a bare selection change.
 *
 * Held on the UI thread (Compose drives it); the render host observes [model] / [selection] as flows.
 * Compose-free by design (its module mandate), so it exposes coroutines flows, not Compose state.
 *
 * 開いているドキュメントの唯一の可変所有者。モデル・選択・モード・履歴・変更イベントを束ねる。
 *
 * @param PuppetModel initialModel The document model at open.
 * @param Pose initialPose The pose at open (the displayed scrub values); defaults to every parameter's
 *   default. The host passes the renderer's starting values so the session, the panel, and the viewport
 *   agree from frame one (e.g. a headless dump's overridden pose is not reset to defaults).
 * @param Int historyLimit The retained-undo-step cap.
 */
class EditorSession(
	initialModel: PuppetModel,
	initialPose: Pose = initialModel.parameters.associate { parameter -> parameter.id to parameter.default },
	historyLimit: Int = DEFAULT_HISTORY_LIMIT,
) {
	// The session's collaborators - the undo machinery (stack, saved baseline, derived flags), the
	// overlay-request buses, and the remembered-selection memory; the members below delegate so the
	// public API is unchanged, and every flow-write ordering stays in this facade.
	private val history = HistoryCore(EditorSnapshot(initialModel, Selection(), initialPose), historyLimit)
	private val requestBus = SessionRequestBus()
	private val elementMemory = MeshElementMemory()
	private val latches = ToolLatches(notify = ::emitNotice)

	private val mutableModel = MutableStateFlow(initialModel)

	/** The live document model; panels read it, the render host observes it. */
	val model: StateFlow<PuppetModel> = mutableModel.asStateFlow()

	private val mutableSelection = MutableStateFlow(Selection())

	/** The live object-mode selection. */
	val selection: StateFlow<Selection> = mutableSelection.asStateFlow()

	private val mutablePose = MutableStateFlow(initialPose)

	/** The live pose (parameter scrub values); the render host mirrors it so undo / redo re-poses. */
	val pose: StateFlow<Pose> = mutablePose.asStateFlow()

	private val mutableMode = MutableStateFlow(EditorMode.Object)

	/**
	 * The live interaction mode. Snapshotted (a mode change is its own undo step), and pose-neutral by
	 * contract: entering or leaving Edit mode NEVER writes the pose — Edit mode's rest view is a
	 * display-only override in the render host, so the Object-mode pose survives an Edit session
	 * untouched with no stashed state to juggle.
	 */
	val mode: StateFlow<EditorMode> = mutableMode.asStateFlow()

	private val mutableMeshSelection = MutableStateFlow(MeshSelection())

	/** The live Edit-mode element selection; snapshotted, so a selection gesture is undoable. */
	val meshSelection: StateFlow<MeshSelection> = mutableMeshSelection.asStateFlow()

	/**
	 * The modal mesh operator currently running (Grab / Scale / Rotate) with its initiating viewport area,
	 * or null. Transient UI coordination (not snapshotted, not on the bus): a registry command latches it,
	 * the initiating area's gizmo overlay observes it to drive the gesture (bystander viewports stay
	 * inert), and clears it on confirm / cancel.
	 */
	val activeMeshOperator: StateFlow<ActiveOperator?> = latches.activeMeshOperator

	/**
	 * The modal OBJECT operator currently running (Grab / Scale / Rotate over the selected drawables' whole
	 * geometry) with its initiating viewport area, or null. The Object-mode sibling of [activeMeshOperator]:
	 * a separate latch because the object overlay captures N drawables where the mesh overlay captures one,
	 * so the two must be distinguishable. Transient UI coordination like [activeMeshOperator] - not
	 * snapshotted, not on the bus - latched by a registry command, observed by the initiating area's object
	 * gizmo overlay, cleared on confirm / cancel / leaving Object mode.
	 */
	val activeObjectOperator: StateFlow<ActiveOperator?> = latches.activeObjectOperator

	/**
	 * The modal UV operator currently running (Grab / Scale / Rotate over the selected vertices' texture
	 * coordinates) with its initiating UV-editor area, or null. The UV-editor sibling of
	 * [activeMeshOperator]: a separate latch so the puppet viewport's gizmo overlays and the UV editor's
	 * overlay can never cross-capture one gesture (each overlay's capture effect keys on its own latch).
	 * Transient UI coordination like the others - not snapshotted, not on the bus - latched by a registry
	 * command, observed by the initiating area's UV overlay, cleared on confirm / cancel / leaving Edit mode.
	 */
	val activeUvOperator: StateFlow<ActiveOperator?> = latches.activeUvOperator

	/**
	 * The transient preview of which drawables an in-flight Object-mode circle stroke is painting, or null when
	 * no stroke is live. The GPU-tint bridge overlays this on top of the committed [selection] so painted
	 * drawables light up immediately without committing each frame (which would spam undo). Not snapshotted,
	 * not on the bus (transient UI coordination like [activeSelectTool]); the stroke commits once on release
	 * via [setSelection] and clears this back to null.
	 */
	val previewSelection: StateFlow<Set<DrawableId>?> = latches.previewSelection

	/**
	 * Publishes the transient circle-stroke preview selection (see [previewSelection]); pass null to clear it.
	 *
	 * @param Set<DrawableId>? drawableIds The drawables currently painted by the stroke, or null to clear.
	 */
	fun setPreviewSelection(drawableIds: Set<DrawableId>?) {
		latches.setPreviewSelection(drawableIds)
	}

	/**
	 * True while a viewport overlay is driving a non-armed pointer gesture (today: the Object-mode
	 * un-armed box drag), so the shell can route Escape to a gesture cancel instead of its next Escape
	 * behavior (clearing the object selection). Transient UI coordination like [previewSelection] - not
	 * snapshotted, not on the bus; the overlay sets it at press and clears it on release or cancel.
	 */
	val viewportGestureActive: StateFlow<Boolean> = latches.viewportGestureActive

	/**
	 * Publishes whether a non-armed viewport gesture is in flight (see [viewportGestureActive]).
	 *
	 * @param Boolean active True while the overlay's gesture owns the pointer.
	 */
	fun setViewportGestureActive(active: Boolean) {
		latches.setViewportGestureActive(active)
	}

	private val mutableNotice = MutableStateFlow<Notice?>(null)

	/**
	 * The current transient user notice, or null when none is showing. A short message the shell surfaces
	 * briefly (near the status bar) to explain why an action did nothing. Deliberately off the undo history and
	 * the change bus - a notice is momentary feedback, never document state. Used today when an Object-mode
	 * transform blocks because the selection holds a part, a deformer, or a mesh-less drawable. A StateFlow (not
	 * a one-shot event) so the shell reads it with the standard collectAsState path and a late subscriber still
	 * sees an in-flight notice; the [Notice.serial] lets the shell time its dismissal and re-trigger on a repeat
	 * of the same text.
	 */
	val notice: StateFlow<Notice?> = mutableNotice.asStateFlow()

	// Monotonic id stamped on each notice so an identical repeated message is still a distinct event the shell
	// can re-time. Not a clock (unavailable here) - just a counter.
	private var noticeSerial: Long = 0L

	/**
	 * Emits a transient user notice (see [notice]); it stays current until dismissed via [clearNotice] or
	 * replaced by a newer one.
	 *
	 * @param String messageKey The stable notice key the UI layer resolves to a localized message.
	 * @param NoticePlacement placement Where the shell surfaces the notice.
	 */
	fun emitNotice(messageKey: String, placement: NoticePlacement = NoticePlacement.StatusBar) {
		noticeSerial += 1
		mutableNotice.value = Notice(messageKey, noticeSerial, placement)
	}

	/**
	 * Dismisses the notice with the given [serial], but only if it is still the current one - so a dismissal
	 * timer for an older notice never clears a newer message that arrived in the meantime.
	 *
	 * @param Long serial The serial of the notice to dismiss (from [Notice.serial]).
	 */
	fun clearNotice(serial: Long) {
		if (mutableNotice.value?.serial == serial) {
			mutableNotice.value = null
		}
	}

	/**
	 * The Edit-mode selection tool currently armed (Box or Circle), or null. Transient UI coordination like
	 * [activeMeshOperator] (not snapshotted, not on the bus): a registry command latches it, the gizmo overlay
	 * observes it to reinterpret pointer input, and it clears on completion / cancel / leaving Edit mode.
	 */
	val activeSelectTool: StateFlow<ActiveSelectTool?> = latches.activeSelectTool

	/**
	 * The viewport area id whose Zoom Region gesture is armed (Blender's Shift+B), or null. Mode-agnostic -
	 * Zoom Region works in Object and Edit mode alike - so it is keyed by area rather than gated on the mode,
	 * and the top-level region overlay for that area reads it to capture the drag. Transient, not snapshotted.
	 */
	val zoomRegionArmedArea: StateFlow<String?> = latches.zoomRegionArmedArea

	private val mutableChanges = MutableSharedFlow<Change>(extraBufferCapacity = 64)

	/** The change-event bus: every mutation emits here, the undoable ones and the transient ones alike. */
	val changes: SharedFlow<Change> = mutableChanges.asSharedFlow()

	/** True when the document model differs from the last-saved state (drives the title/status marker). */
	val dirty: StateFlow<Boolean> = history.dirty

	/** True when there is a step to undo (drives the Edit-menu item's enabled state). */
	val canUndo: StateFlow<Boolean> = history.canUndo

	/** True when there is a step to redo. */
	val canRedo: StateFlow<Boolean> = history.canRedo

	/** The undo stack projected for the history panel; updates on every edit, undo, redo, jump, and save. */
	val historyView: StateFlow<HistoryView> = history.historyView

	/**
	 * Applies a document edit: computes the new model via [transform], records it as one undo step, and
	 * publishes it. The [change] describes the edit for the bus and the history-panel label. A transform
	 * that returns the same model instance (a no-op edit) records nothing, so callers need not pre-check.
	 *
	 * @param Change change The descriptor of this edit (its [Change.undoability] is assumed undoable here).
	 * @param Function transform Produces the new model from the current one.
	 */
	fun mutate(change: Change, transform: (PuppetModel) -> PuppetModel) {
		commit(change, transform(mutableModel.value), mutablePose.value)
	}

	/**
	 * Commits one undo step from an already-computed [model] and [pose], recording it and publishing both.
	 * The single choke point behind [mutate] (model edits), [commitPose] (scrubs), and [setParameterRange]
	 * (both at once). A commit that changes neither the model instance nor the pose records nothing, so
	 * callers need not pre-check. Dirty is measured against the model only, so a pose-only commit (a scrub)
	 * is an undo step without marking the document unsaved, exactly like a selection gesture.
	 *
	 * @param Change change The descriptor of this edit (for the bus and the history-panel label).
	 * @param PuppetModel model The new document model (same instance as now for a pose-only commit).
	 * @param Pose pose The new live pose (same value as now for a model-only commit).
	 */
	private fun commit(change: Change, model: PuppetModel, pose: Pose) {
		if (model === mutableModel.value && pose == mutablePose.value) {
			return
		}
		history.push(EditorSnapshot(model, mutableSelection.value, pose, mutableMeshSelection.value, mutableMode.value), change)
		mutableModel.value = model
		mutablePose.value = pose
		refreshFlags()
		mutableChanges.tryEmit(change)
	}

	/**
	 * Commits a parameter scrub as one undo step: the live [pose] reached a new resting position (a slider
	 * or 2D-pad gesture released, a value typed, a reset). Mid-gesture preview frames bypass this and reach
	 * the renderer directly, so a whole drag is a single step. The model is unchanged, so this does not
	 * mark the document dirty. A commit equal to the current pose records nothing.
	 *
	 * @param Change change The scrub descriptor (a [ParameterChange.SetValue]).
	 * @param Pose pose The pose to commit (the gesture's final parameter values).
	 */
	fun commitPose(change: Change, pose: Pose) {
		commit(change, mutableModel.value, pose)
	}

	/**
	 * Sets parameter [id]'s range and default, and re-clamps its live pose value into the new range — all
	 * as one undo step. A model edit (the range is document content), so it marks the document dirty; the
	 * pose re-clamp rides the same step so undo restores both together. A no-op range records nothing.
	 *
	 * @param ParameterId id The parameter to retarget.
	 * @param Float min The requested minimum.
	 * @param Float default The requested default (clamped into the resulting range).
	 * @param Float max The requested maximum.
	 */
	fun setParameterRange(id: ParameterId, min: Float, default: Float, max: Float) {
		val newModel = mutableModel.value.withParameterRange(id, min, default, max)
		if (newModel === mutableModel.value) {
			return
		}
		// Re-clamp the live value into the resulting (normalized) range so the pose stays valid.
		val parameter = newModel.parameters.firstOrNull { it.id == id }
		val newPose =
			if (parameter != null) {
				val current = mutablePose.value[id]
				val clamped = current?.coerceIn(parameter.min, parameter.max)
				if (clamped != null && clamped != current) {
					mutablePose.value + (id to clamped)
				} else {
					mutablePose.value
				}
			} else {
				mutablePose.value
			}
		val change = ParameterChange.SetRange(id, parameter?.min ?: min, parameter?.default ?: default, parameter?.max ?: max)
		commit(change, newModel, newPose)
	}

	/**
	 * Links parameter [horizontal] with [vertical] (the next parameter below it in panel order) into
	 * one 2D pad, or removes that link, as one undo step. A model edit (the link is document content),
	 * so it marks the document dirty. The pose needs no care here: a link only changes presentation,
	 * both parameters keep their live values by construction. An invalid request returns the same
	 * model instance from [withParameterLink], so the commit short-circuit records nothing.
	 *
	 * @param ParameterId horizontal The X-axis (upper) parameter.
	 * @param ParameterId vertical The Y-axis parameter.
	 * @param Boolean linked True to create the link, false to remove it.
	 */
	fun setParameterLink(horizontal: ParameterId, vertical: ParameterId, linked: Boolean) {
		mutate(ParameterChange.SetLink(horizontal, vertical, linked)) { model ->
			model.withParameterLink(horizontal, vertical, linked)
		}
	}

	/**
	 * Deletes parameter [id] everywhere - the axis list, the panel tree, any link, every object's keyform
	 * grid (its axis collapses to the default slice), and the live pose - as one undo step. A model edit,
	 * so it marks the document dirty; dropping the pose entry rides the same step so undo restores both.
	 * A member (not a mutate extension) because it commits a new model and a new pose together, like
	 * [setParameterRange]. A no-op (no such parameter) records nothing.
	 *
	 * @param ParameterId id The parameter to delete.
	 */
	fun deleteParameter(id: ParameterId) {
		val newModel = mutableModel.value.withParameterDeleted(id)
		if (newModel === mutableModel.value) {
			return
		}
		commit(ParameterChange.Delete(id), newModel, mutablePose.value - id)
	}

	/**
	 * Records a selection gesture as its own undo step (the chosen Blender-faithful granularity), so a
	 * misclick that clears the selection is recoverable. A no-op (selecting the already-current
	 * selection) records nothing.
	 *
	 * @param Selection selection The new selection.
	 */
	fun setSelection(selection: Selection) {
		if (selection == mutableSelection.value) {
			return
		}
		(selection.active as? SelectionTarget.Drawable)?.let { activeDrawable ->
			elementMemory.lastActiveDrawableId = activeDrawable.id
		}
		history.push(
			EditorSnapshot(mutableModel.value, selection, mutablePose.value, mutableMeshSelection.value, mutableMode.value),
			EditorStateChange.SelectionChanged,
		)
		mutableSelection.value = selection
		refreshFlags()
		mutableChanges.tryEmit(EditorStateChange.SelectionChanged)
	}

	/**
	 * Sets the interaction mode as its own undo step, so undo restores the mode (and the mesh selection
	 * it seeds) together - the editor never lands in Edit mode showing a state captured in Object mode.
	 * Entering Edit seeds the session with EVERY selected mesh-carrying drawable (multi-mesh edit; the
	 * object selection's active drawable becomes the session's active mesh), falling back to the last
	 * drawable that was active (Blender's remembered selection) when nothing is selected, and past that
	 * to the topmost editable drawable in Parts-panel order (so a fresh document lands on something to
	 * edit rather than an inert session); the remembered fallback is skipped if that drawable no longer
	 * exists in the model. When the model has nothing editable at all, entering Edit is refused - the
	 * mode stays Object and nothing is recorded - so Edit mode always holds meshes. Leaving Edit stashes the
	 * element selection and clears it; re-entering on the same drawable restores the stash when every
	 * element still fits the mesh (Blender's remembered mesh selection). Leaving also ends any in-flight
	 * operator. The mode is editor state, not document content, so it does not dirty the document. A no-op
	 * (already in [mode]) records nothing.
	 *
	 * @param EditorMode mode The new mode.
	 */
	fun setMode(mode: EditorMode) {
		if (mode == mutableMode.value) {
			return
		}
		val newMeshSelection =
			when (mode) {
				EditorMode.Edit -> {
					val model = mutableModel.value
					// The session spans EVERY selected mesh-carrying drawable (multi-mesh edit, needed for
					// glue work); the object selection's active drawable becomes the session's active mesh.
					val selectedMeshedIds =
						mutableSelection.value.targets
							.filterIsInstance<SelectionTarget.Drawable>()
							.map { target -> target.id }
							.filter { candidateId -> model.drawables.any { drawable -> drawable.id == candidateId && drawable.mesh != null } }
					val activeSelectedId =
						(mutableSelection.value.active as? SelectionTarget.Drawable)?.id
							?.takeIf { activeId -> activeId in selectedMeshedIds }
					val rememberedDrawableId =
						elementMemory.lastActiveDrawableId?.takeIf { remembered ->
							model.drawables.any { drawable -> drawable.id == remembered && drawable.mesh != null }
						}
					val seedDrawableIds =
						when {
							selectedMeshedIds.isNotEmpty() -> selectedMeshedIds
							rememberedDrawableId != null -> listOf(rememberedDrawableId)
							else -> listOfNotNull(model.firstEditableDrawableInPanelOrder())
						}
					if (seedDrawableIds.isEmpty()) {
						// The model has nothing editable, so refuse Edit rather than open an inert session
						// (Blender needs an active object too). Stay in Object; record nothing.
						return
					}
					val seedActiveId = activeSelectedId ?: seedDrawableIds.first()
					if (activeSelectedId == null) {
						// Seeded from the remembered drawable or the topmost fallback: remember it so the next
						// entry is stable. The object selection is left untouched - a soft seed, not a
						// re-selection, matching the remembered-drawable behavior.
						elementMemory.lastActiveDrawableId = seedActiveId
					}
					// Clear the transient tool state on entry too: the select tool is shared with Object mode, so
					// an object tool armed before the switch must not leak in and drive the Edit overlay.
					latches.clearTransient(clearViewportGesture = true)
					// Restore each seeded drawable's remembered elements where they still fit its mesh;
					// the rest start empty (a different mesh set no longer forgets the others' memory).
					elementMemory.restore(MeshSelection.editing(seedDrawableIds, seedActiveId), model)
				}
				EditorMode.Object -> {
					// Stash each mesh's element selection so re-entering Edit mode restores it per mesh.
					elementMemory.stash(mutableMeshSelection.value)
					latches.clearTransient(clearViewportGesture = true)
					MeshSelection()
				}
			}
		history.push(
			EditorSnapshot(mutableModel.value, mutableSelection.value, mutablePose.value, newMeshSelection, mode),
			EditorStateChange.ModeChanged(mode),
		)
		mutableMode.value = mode
		mutableMeshSelection.value = newMeshSelection
		refreshFlags()
		mutableChanges.tryEmit(EditorStateChange.ModeChanged(mode))
	}

	/**
	 * Records an Edit-mode element-selection gesture as its own undo step (the same Blender-faithful
	 * granularity as object selection), so a misclick that loses a selection is recoverable. A no-op
	 * (selecting the already-current selection) records nothing. Not a document edit — leaves dirty
	 * untouched.
	 *
	 * @param MeshSelection meshSelection The new element selection.
	 */
	fun setMeshSelection(meshSelection: MeshSelection) {
		if (meshSelection == mutableMeshSelection.value) {
			return
		}
		history.push(
			EditorSnapshot(mutableModel.value, mutableSelection.value, mutablePose.value, meshSelection, mutableMode.value),
			EditorStateChange.MeshSelectionChanged,
		)
		mutableMeshSelection.value = meshSelection
		refreshFlags()
		mutableChanges.tryEmit(EditorStateChange.MeshSelectionChanged)
	}

	/**
	 * Commits a mesh-vertex edit (a finished modal G / S / R gesture) as ONE undo step: each session
	 * drawable's base art-mesh positions become its entry in [newPositionsByDrawable].  An Edit session
	 * spans several meshes, so the per-drawable copy-on-write [withMeshPositions] edits fold into a
	 * single model (one history step, like [commitObjectPositions]).  Mid-gesture preview frames reach
	 * the renderer directly (transient), so a whole drag is a single step.  A model edit (rest geometry
	 * is document content), so it marks the document dirty; a no-op (every array unchanged / mismatched)
	 * records nothing.
	 *
	 * @param MeshChange change The edit descriptor (a [MeshChange.TransformVertices]).
	 * @param Map<DrawableId, FloatArray> newPositionsByDrawable Each edited drawable's committed rest positions.
	 */
	fun commitMeshPositions(change: MeshChange, newPositionsByDrawable: Map<DrawableId, FloatArray>) {
		val newModel =
			newPositionsByDrawable.entries.fold(mutableModel.value) { model, (drawableId, newPositions) ->
				model.withMeshPositions(drawableId, newPositions)
			}
		commit(change, newModel, mutablePose.value)
	}

	/**
	 * Commits an Object-mode transform of several drawables (a finished modal G / S / R gesture) as ONE undo
	 * step: each drawable's base art-mesh positions become its entry in [newPositionsByDrawable]. Folds the
	 * per-drawable copy-on-write [withMeshPositions] edits into a single model, so N moved drawables are one
	 * history step (not N). Mid-gesture preview frames reach the renderer directly (transient), so a whole drag
	 * is a single step. A model edit (rest geometry is document content), so it marks the document dirty; a
	 * no-op (every array unchanged / mismatched, so the fold returns the same instance) records nothing.
	 *
	 * @param MeshChange change The edit descriptor (a [MeshChange.TransformDrawables]).
	 * @param Map<DrawableId, FloatArray> newPositionsByDrawable Each moved drawable's committed rest positions.
	 */
	fun commitObjectPositions(change: MeshChange, newPositionsByDrawable: Map<DrawableId, FloatArray>) {
		val newModel =
			newPositionsByDrawable.entries.fold(mutableModel.value) { model, (drawableId, newPositions) ->
				model.withMeshPositions(drawableId, newPositions)
			}
		commit(change, newModel, mutablePose.value)
	}

	/**
	 * Commits a UV edit (a finished modal G / S / R gesture in the UV editor, or a Mirror command) as
	 * ONE undo step: each edited drawable's texture coordinates become its entry in [newUvsByDrawable].
	 * The texture-mapping twin of [commitMeshPositions] - the per-drawable copy-on-write [withMeshUvs]
	 * edits fold into a single model, so N edited meshes are one history step.  Mid-gesture preview
	 * frames reach the renderer directly (transient), so a whole drag is a single step.  A model edit
	 * (the sampled atlas texels are document content), so it marks the document dirty; a no-op (every
	 * array unchanged / mismatched) records nothing.
	 *
	 * @param MeshChange change The edit descriptor (a [MeshChange.TransformUvs] or [MeshChange.MirrorUvs]).
	 * @param Map<DrawableId, FloatArray> newUvsByDrawable Each edited drawable's committed atlas UVs.
	 */
	fun commitMeshUvs(change: MeshChange, newUvsByDrawable: Map<DrawableId, FloatArray>) {
		val newModel =
			newUvsByDrawable.entries.fold(mutableModel.value) { model, (drawableId, newUvs) ->
				model.withMeshUvs(drawableId, newUvs)
			}
		commit(change, newModel, mutablePose.value)
	}

	/**
	 * Mirrors the selected vertices' texture coordinates about the transform pivot as ONE undo step -
	 * the UV editor's Mirror U / V commands, serving the duplicated-and-flipped texture regions
	 * workflow (both eyes sampling one eye texture).  The pivot follows [pivotMode], resolved in UV
	 * space: Median Point mirrors about the covered vertices' combined median across every edited mesh,
	 * Individual Origins mirrors each connectivity island about its own median, Active Element anchors
	 * on the active element's median, and Cursor anchors on the UV cursor (each falling back to the
	 * combined median when unresolvable - a mirror should never silently do nothing because a pivot was
	 * never placed).  Mirroring is axis-aligned, so operating directly in normalized UV space matches
	 * the on-screen result regardless of the atlas page's size.  A no-op outside Edit mode or with an
	 * empty selection; a notice explains when no covered mesh carries an editable UV array.
	 *
	 * @param Boolean mirrorU True to mirror horizontally (u about the pivot), false vertically (v).
	 */
	fun mirrorSelectedUvs(mirrorU: Boolean) {
		if (mutableMode.value != EditorMode.Edit) {
			return
		}
		val selection = mutableMeshSelection.value
		if (selection.isEmpty) {
			return
		}
		val model = mutableModel.value
		// Resolve each session mesh's covered vertices, skipping meshes without an editable UV array
		// (imports may leave uvs empty; a malformed length is excluded by the same guard as withMeshUvs).
		val coveredByDrawable = LinkedHashMap<DrawableId, Set<Int>>()
		val meshByDrawable = LinkedHashMap<DrawableId, DrawableMesh>()
		for (drawableId in selection.drawableIds) {
			val mesh = model.drawables.firstOrNull { drawable -> drawable.id == drawableId }?.mesh ?: continue
			if (mesh.uvs.isEmpty() || mesh.uvs.size != mesh.positions.size) {
				continue
			}
			val covered = MeshTopology.coveredVertexIndices(selection.elementsOf(drawableId), mesh.indices)
			if (covered.isEmpty()) {
				continue
			}
			coveredByDrawable[drawableId] = covered
			meshByDrawable[drawableId] = mesh
		}
		if (coveredByDrawable.isEmpty()) {
			emitNotice("notice.uv.noUvs", NoticePlacement.NearCursor)
			return
		}
		val sharedPivot =
			if (latches.pivotMode.value == TransformPivotMode.IndividualOrigins) {
				null
			} else {
				resolveUvMirrorPivot(coveredByDrawable, meshByDrawable, selection)
			}
		val newUvsByDrawable = LinkedHashMap<DrawableId, FloatArray>()
		for ((drawableId, covered) in coveredByDrawable) {
			val mesh = meshByDrawable.getValue(drawableId)
			val groups =
				if (sharedPivot == null) {
					TransformPivots.islandGroups(mesh.uvs, covered, mesh.indices)
				} else {
					TransformPivots.sharedGroup(covered, sharedPivot.first, sharedPivot.second)
				}
			var mirroredUvs = mesh.uvs
			for (group in groups) {
				mirroredUvs =
					MeshTransforms.scaleVerticesAxis(
						mirroredUvs,
						group.vertexIndices,
						if (mirrorU) -1f else 1f,
						if (mirrorU) 1f else -1f,
						group.pivotX,
						group.pivotY,
					)
			}
			newUvsByDrawable[drawableId] = mirroredUvs
		}
		commitMeshUvs(MeshChange.MirrorUvs(newUvsByDrawable.keys.toList(), mirrorU), newUvsByDrawable)
	}

	/**
	 * Resolves the shared UV mirror pivot for the single-anchor pivot modes: Cursor anchors on the UV
	 * cursor and Active Element on the active element's covered median, each falling back to the
	 * combined covered median across every edited mesh - which is also the Median Point result.
	 *
	 * @param Map<DrawableId, Set<Int>> coveredByDrawable Each edited mesh's covered vertex indices.
	 * @param Map<DrawableId, DrawableMesh> meshByDrawable Each edited mesh, keyed like the covered map.
	 * @param MeshSelection selection The live selection (for the active element).
	 * @return Pair<Float, Float> The pivot's (u, v).
	 */
	private fun resolveUvMirrorPivot(
		coveredByDrawable: Map<DrawableId, Set<Int>>,
		meshByDrawable: Map<DrawableId, DrawableMesh>,
		selection: MeshSelection,
	): Pair<Float, Float> {
		when (latches.pivotMode.value) {
			TransformPivotMode.Cursor -> {
				val cursor = latches.uvCursor.value
				if (cursor != null) {
					return cursor.u to cursor.v
				}
			}

			TransformPivotMode.ActiveElement -> {
				val active = selection.activeElement
				val activeMesh = active?.let { activeElement -> meshByDrawable[activeElement.drawableId] }
				if (active != null && activeMesh != null) {
					val activeCovered = MeshTopology.coveredVertexIndices(setOf(active.element), activeMesh.indices)
					if (activeCovered.isNotEmpty()) {
						return MeshTransforms.medianPivot(activeMesh.uvs, activeCovered)
					}
				}
			}

			TransformPivotMode.MedianPoint, TransformPivotMode.IndividualOrigins -> {}
		}
		var sumU = 0f
		var sumV = 0f
		var coveredCount = 0
		for ((drawableId, covered) in coveredByDrawable) {
			val uvs = meshByDrawable.getValue(drawableId).uvs
			for (vertexIndex in covered) {
				sumU += uvs[vertexIndex * 2]
				sumV += uvs[vertexIndex * 2 + 1]
				coveredCount += 1
			}
		}
		if (coveredCount == 0) {
			// Unreachable today (callers pre-filter empty covered sets); the page centre is a safe anchor.
			return 0.5f to 0.5f
		}
		return (sumU / coveredCount) to (sumV / coveredCount)
	}

	/**
	 * Switches the Edit-mode select mode (vertex / edge / face) as its own undo step, converting the
	 * stored selection into the new domain with Blender's flush-down / derive-up rules (see
	 * [MeshSelectionOps.changeSelectMode]). The conversion is lossy by design, so the snapshot is what
	 * makes it recoverable. A no-op outside Edit mode — so the bound 1 / 2 / 3 commands need no context
	 * guard of their own and the keymap stays mode-agnostic — and a no-op when already in [selectMode].
	 * Not a document edit — leaves dirty untouched.
	 *
	 * @param MeshSelectMode selectMode The new select mode.
	 */
	fun setMeshSelectMode(selectMode: MeshSelectMode) {
		if (mutableMode.value != EditorMode.Edit) {
			return
		}
		val current = mutableMeshSelection.value
		val model = mutableModel.value
		val converted =
			MeshSelectionOps.changeSelectMode(current, selectMode) { drawableId ->
				model.drawables.firstOrNull { it.id == drawableId }?.mesh?.indices
			}
		if (converted == current) {
			return
		}
		history.push(
			EditorSnapshot(mutableModel.value, mutableSelection.value, mutablePose.value, converted, mutableMode.value),
			EditorStateChange.MeshSelectModeChanged(selectMode),
		)
		mutableMeshSelection.value = converted
		refreshFlags()
		mutableChanges.tryEmit(EditorStateChange.MeshSelectModeChanged(selectMode))
	}

	/**
	 * Selects every element of every session mesh in the current select mode (Blender's Select All) as
	 * one undo step.  A no-op outside Edit mode, or when the Edit session holds no meshes - so the bound
	 * command stays mode-agnostic (it dispatches to [selectAllObjects] in Object mode).  Not a document edit.
	 */
	fun selectAllMeshElements() {
		if (mutableMode.value != EditorMode.Edit) {
			return
		}
		val current = mutableMeshSelection.value
		val model = mutableModel.value
		setMeshSelection(MeshSelectionOps.selectAll(current) { drawableId -> model.drawables.firstOrNull { it.id == drawableId }?.mesh })
	}

	/**
	 * Inverts every session mesh's element selection within the current select mode (Blender's Ctrl+I) as
	 * one undo step.  A no-op outside Edit mode, or when the Edit session holds no meshes.  Not a document
	 * edit.
	 */
	fun invertMeshSelection() {
		if (mutableMode.value != EditorMode.Edit) {
			return
		}
		val current = mutableMeshSelection.value
		val model = mutableModel.value
		setMeshSelection(MeshSelectionOps.invert(current) { drawableId -> model.drawables.firstOrNull { it.id == drawableId }?.mesh })
	}

	/**
	 * Selects every selectable entity in the model (Object mode's Select All) as one undo step.  A no-op
	 * outside Object mode - so the bound command stays mode-agnostic (it dispatches to [selectAllMeshElements]
	 * in Edit mode).  Not a document edit.
	 */
	fun selectAllObjects() {
		if (mutableMode.value != EditorMode.Object) {
			return
		}
		setSelection(SelectionOps.selectAll(mutableSelection.value, mutableModel.value))
	}

	/**
	 * Inverts the object selection over every selectable entity (Object mode's Ctrl+I) as one undo step.  A
	 * no-op outside Object mode.  Not a document edit.
	 */
	fun invertObjectSelection() {
		if (mutableMode.value != EditorMode.Object) {
			return
		}
		setSelection(SelectionOps.invert(mutableSelection.value, mutableModel.value))
	}

	/**
	 * True while the active mesh operator was latched with proportional editing suppressed - the
	 * duplicate / rip auto-grabs, which place fresh copies and must never drag bystander vertices.
	 * Transient latch state (never snapshotted), reset whenever the operator latches or clears.
	 */
	val activeMeshOperatorSuppressesProportional: Boolean
		get() = latches.activeMeshOperatorSuppressesProportional

	/**
	 * Latches a modal mesh operator so the gizmo overlay begins the gesture. A no-op unless Edit mode is
	 * active with a drawable and a non-empty selection — so the bound G / S / R commands need no context
	 * guard of their own and the keymap stays mode-agnostic. For an edge or face selection the gesture
	 * moves the union of vertices the selected elements cover (resolved in the overlay).
	 *
	 * @param MeshOperatorKind kind The operator to begin (Grab / Scale / Rotate).
	 * @param String areaId The initiating viewport's area id (only its overlay drives the gesture).
	 * @param Boolean suppressProportional True to ignore proportional editing for this gesture (the
	 *   duplicate / rip auto-grabs; treated like Vertex Slide at every proportional gate).
	 */
	fun beginMeshOperator(kind: MeshOperatorKind, areaId: String, suppressProportional: Boolean = false) {
		if (mutableMode.value != EditorMode.Edit) {
			return
		}
		val selection = mutableMeshSelection.value
		if (selection.drawableIds.isEmpty() || selection.isEmpty) {
			return
		}
		latches.latchMeshOperator(kind, areaId, suppressProportional)
	}

	/** Clears the active modal mesh operator (the overlay calls this on confirm or cancel). */
	fun clearMeshOperator() {
		latches.clearMeshOperator()
	}

	/**
	 * Begins an Object-mode modal transform (Grab / Scale / Rotate) over the selected drawables' whole
	 * geometry - the Object-mode counterpart to [beginMeshOperator]. A no-op unless Object mode is active
	 * with an eligible selection: at least one selected target must be a drawable that carries a mesh (see
	 * [eligibleTransformDrawables]; parts, deformers, and mesh-less drawables in the selection are silently
	 * ignored, so a Select All that swept them in never blocks the gesture). The gesture is BLOCKED with a
	 * near-cursor notice when the pose is not at parameter defaults: the object overlay captures at the live
	 * pose, and writing a deformed capture back through the warp inverse corrupts the rest meshes - the
	 * Blender-style guard tells the user to reset the parameters first. Clears any other latched tool /
	 * operator (mutual exclusion) before latching.
	 *
	 * @param MeshOperatorKind kind The operator to begin (Grab / Scale / Rotate).
	 * @param String areaId The initiating viewport's area id (only its overlay drives the gesture).
	 */
	fun beginObjectOperator(kind: MeshOperatorKind, areaId: String) {
		if (mutableMode.value != EditorMode.Object) {
			return
		}
		if (eligibleTransformDrawables(mutableSelection.value, mutableModel.value) == null) {
			// Nothing transformable at all (empty, or only parts / deformers / mesh-less drawables).
			emitNotice("notice.transform.onlyDrawables", NoticePlacement.NearCursor)
			return
		}
		if (!isPoseNeutral(mutableModel.value, mutablePose.value)) {
			// Transforming rest geometry while the displayed pose is deformed would write garbage through
			// the deformer inverse; refuse and tell the user how to proceed (see the docblock).
			emitNotice("notice.transform.deformed", NoticePlacement.NearCursor)
			return
		}
		latches.latchObjectOperator(kind, areaId)
	}

	/** Clears the active modal object operator (the overlay calls this on confirm or cancel). */
	fun clearObjectOperator() {
		latches.clearObjectOperator()
	}

	/**
	 * Latches a modal UV operator so the UV editor's overlay begins the gesture - the UV-editor
	 * counterpart to [beginMeshOperator], moving texture coordinates instead of rest geometry.  A no-op
	 * unless Edit mode is active with a non-empty selection - so the bound G / S / R commands stay
	 * mode-agnostic - and Vertex Slide is refused (it is rest-geometry math; Blender's UV editor has no
	 * slide either).  The gesture is BLOCKED with a near-cursor notice when no covered mesh carries an
	 * editable UV array (imports may leave uvs empty), since latching would show a modal HUD that can
	 * never commit anything.  Clears any other latched tool / operator (mutual exclusion) before
	 * latching.
	 *
	 * @param MeshOperatorKind kind The operator to begin (Grab / Scale / Rotate).
	 * @param String areaId The initiating UV editor's area id (only its overlay drives the gesture).
	 */
	fun beginUvOperator(kind: MeshOperatorKind, areaId: String) {
		if (mutableMode.value != EditorMode.Edit) {
			return
		}
		if (kind == MeshOperatorKind.VertexSlide) {
			return
		}
		val selection = mutableMeshSelection.value
		if (selection.drawableIds.isEmpty() || selection.isEmpty) {
			return
		}
		val model = mutableModel.value
		val anyEditableUvs =
			selection.drawableIds.any { drawableId ->
				val mesh = model.drawables.firstOrNull { drawable -> drawable.id == drawableId }?.mesh
				mesh != null &&
					mesh.uvs.isNotEmpty() &&
					mesh.uvs.size == mesh.positions.size &&
					MeshTopology.coveredVertexIndices(selection.elementsOf(drawableId), mesh.indices).isNotEmpty()
			}
		if (!anyEditableUvs) {
			emitNotice("notice.uv.noUvs", NoticePlacement.NearCursor)
			return
		}
		latches.latchUvOperator(kind, areaId)
	}

	/** Clears the active modal UV operator (the overlay calls this on confirm or cancel). */
	fun clearUvOperator() {
		latches.clearUvOperator()
	}

	/**
	 * Arms the Box-select tool (Blender's B): the gizmo overlay shows full-viewport crosshair guides and the
	 * next drag boxes.  Mode-agnostic - in Edit mode it needs an active drawable (the box selects that mesh's
	 * elements); in Object mode it arms unconditionally (the box selects whole drawables).  A no-op in Edit
	 * mode without a drawable.  Clears any other latched tool / operator (mutual exclusion).
	 *
	 * @param String areaId The arming viewport's area id (only its overlay drives the drag).
	 */
	fun beginBoxSelect(areaId: String) {
		if (mutableMode.value == EditorMode.Edit && mutableMeshSelection.value.drawableIds.isEmpty()) {
			return
		}
		latches.armBoxSelect(areaId)
	}

	/**
	 * Arms the Circle-select tool (Blender's C) at the remembered radius.  Mode-agnostic like [beginBoxSelect]:
	 * needs an active drawable in Edit mode, arms unconditionally in Object mode.  Clears any other latched
	 * tool / operator (mutual exclusion).
	 *
	 * @param String areaId The arming viewport's area id (only its overlay drives the brush).
	 */
	fun beginCircleSelect(areaId: String) {
		if (mutableMode.value == EditorMode.Edit && mutableMeshSelection.value.drawableIds.isEmpty()) {
			return
		}
		latches.armCircleSelect(areaId)
	}

	/**
	 * Sets the Circle-select brush radius (clamped), remembering it for the next arm.  When a Circle tool is
	 * live its radius updates in place so the overlay redraws; otherwise only the remembered value moves.
	 *
	 * @param Float radiusPx The requested radius in viewport pixels.
	 */
	fun setCircleRadius(radiusPx: Float) {
		latches.setCircleRadius(radiusPx)
	}

	/** Grows the Circle-select radius by one step (numpad +); a no-op unless a Circle tool is live. */
	fun growCircleRadius() {
		latches.growCircleRadius()
	}

	/** Shrinks the Circle-select radius by one step (numpad -); a no-op unless a Circle tool is live. */
	fun shrinkCircleRadius() {
		latches.shrinkCircleRadius()
	}

	/** Clears any armed Box / Circle select tool (the overlay calls this on completion, Esc, or RMB). */
	fun clearSelectTool() {
		latches.clearSelectTool()
	}

	/**
	 * Arms the Zoom Region gesture (Blender's Shift+B) for [areaId].  Mode-agnostic - valid in Object and
	 * Edit mode.  Clears any latched Edit-mode tool so two overlays never capture at once.
	 *
	 * @param String areaId The viewport area the gesture will run in (the pointer's active area).
	 */
	fun armZoomRegion(areaId: String) {
		latches.armZoomRegion(areaId)
	}

	/** Disarms the Zoom Region gesture (the overlay calls this on completion, Esc, or RMB). */
	fun disarmZoomRegion() {
		latches.disarmZoomRegion()
	}

	/**
	 * The 2D cursor's world position, or null before any placement.  Transient session state like the
	 * tool latches (deliberately NOT part of EditorSnapshot - see [Cursor2d]); placed by Shift+RightClick
	 * in the viewport, moved by the snap commands, drawn by the HUD overlay, and read as the transform
	 * pivot in [TransformPivotMode.Cursor].
	 */
	val cursor2d: StateFlow<Cursor2d?> = latches.cursor2d

	/**
	 * Places (or moves) the 2D cursor.
	 *
	 * @param Float worldX The cursor's new world-space x.
	 * @param Float worldY The cursor's new world-space y.
	 */
	fun setCursor2d(worldX: Float, worldY: Float) {
		latches.setCursor2d(worldX, worldY)
	}

	/**
	 * The UV editor's cursor in normalized atlas coordinates, or null before any placement.  The
	 * texture-space sibling of [cursor2d] (see [UvCursor]): placed by Shift+RightClick in the UV editor,
	 * drawn by its overlay, and read as the UV transform pivot in [TransformPivotMode.Cursor].
	 */
	val uvCursor: StateFlow<UvCursor?> = latches.uvCursor

	/**
	 * Places (or moves) the UV editor's cursor.
	 *
	 * @param Float u The cursor's new normalized atlas u coordinate.
	 * @param Float v The cursor's new normalized atlas v coordinate.
	 */
	fun setUvCursor(u: Float, v: Float) {
		latches.setUvCursor(u, v)
	}

	/**
	 * The viewport grid geometry (major spacing + subdivisions) driving both the drawn backdrop grid and
	 * the grid snap increment.  Transient session state today (deliberately NOT snapshotted - like the 2D
	 * cursor); seeded from the global-default settings and, once the UMA format lands, from the per-file
	 * value.  Read by the snap commands ([GridConfig.snapStep]) and pushed to the renderer by the viewport
	 * binding.
	 */
	val gridConfig: StateFlow<GridConfig> = latches.gridConfig

	/**
	 * Sets the viewport grid geometry.  Called by the viewport binding when the global-default settings
	 * change (and, later, when a per-file value loads); the header overlay control will call it too.
	 *
	 * @param GridConfig config The new grid scale and subdivisions.
	 */
	fun setGridConfig(config: GridConfig) {
		latches.setGridConfig(config)
	}

	/**
	 * What a modal Scale / Rotate turns the selection about (the Period pie / the header dropdown).
	 * Transient editor state - it survives mode switches but is never snapshotted; the default is
	 * Blender's Median Point.
	 */
	val pivotMode: StateFlow<TransformPivotMode> = latches.pivotMode

	/**
	 * Selects the transform pivot mode.
	 *
	 * @param TransformPivotMode mode The pivot mode the next transforms anchor on.
	 */
	fun setPivotMode(mode: TransformPivotMode) {
		latches.setPivotMode(mode)
	}

	/**
	 * The axis the in-flight modal Grab / Scale is locked to, or null when unconstrained.  Set by the
	 * shell's key ladder (X / Z during a modal gesture - the keymap cannot see those keys, the operator
	 * swallows them), read by the gizmo overlays' drive loops, cleared whenever an operator latches or
	 * clears.  Transient coordination like [activeMeshOperator].
	 */
	val axisConstraint: StateFlow<TransformAxisConstraint?> = latches.axisConstraint

	/**
	 * Toggles the modal axis constraint (pressing a lock's own key again releases it; pressing the other
	 * axis switches).  A no-op unless a Grab or Scale operator is in flight - Rotate has no axis to lock
	 * and idle keys must not arm a stale constraint.
	 *
	 * @param TransformAxisConstraint axis The axis whose lock to toggle.
	 */
	fun toggleAxisConstraint(axis: TransformAxisConstraint) {
		latches.toggleAxisConstraint(axis)
	}

	/**
	 * The radial pie menu currently open over the viewport, or null.  Transient UI coordination: a
	 * command opens it (Period / Shift+S / the merge menu), the pie host composable renders it at the
	 * pointer, and picking an entry or Escape closes it.
	 */
	val activePieMenu: StateFlow<PieMenuKind?> = latches.activePieMenu

	/**
	 * Opens a pie menu over the viewport (closing any other transient latch is not needed - a pie is
	 * display-only and the key ladder swallows input while one is open).
	 *
	 * @param PieMenuKind kind The pie to open.
	 */
	fun openPieMenu(kind: PieMenuKind) {
		latches.openPieMenu(kind)
	}

	/** Closes the open pie menu (entry picked, Escape, or a click outside). */
	fun closePieMenu() {
		latches.closePieMenu()
	}

	/**
	 * Proportional editing (Blender's O): non-null while enabled, carrying the falloff curve and the
	 * influence radius.  Transient editor state like [pivotMode] - it survives mode switches but is
	 * never snapshotted; the Edit overlay reads it when a modal operator latches (and on mid-gesture
	 * radius scrolls) to weight the unselected vertices near the selection.
	 */
	val proportionalEdit: StateFlow<ProportionalEditState?> = latches.proportionalEdit

	/**
	 * Toggles proportional editing on or off (Blender's O), restoring the last falloff and radius on
	 * re-enable and confirming either way with a near-cursor notice (an idle toggle has no other
	 * visible effect - the influence circle only shows during a modal transform).
	 */
	fun toggleProportionalEdit() {
		latches.toggleProportionalEdit()
	}

	/**
	 * Toggles Connected Only for proportional editing (influence measured along mesh edges instead of
	 * straight-line, so the halo never leaps to unconnected geometry), enabling proportional editing
	 * if it was off - and then connected mode turns ON regardless of the remembered flag, since the
	 * command expresses the intent to use it.  Confirms either way with a near-cursor notice.
	 */
	fun toggleProportionalConnected() {
		latches.toggleProportionalConnected()
	}

	/**
	 * Selects the proportional falloff curve, enabling proportional editing if it was off - picking a
	 * falloff from the palette or header expresses the intent to use it, and silently updating a
	 * disabled state would look like the command did nothing.
	 *
	 * @param ProportionalFalloff falloff The falloff curve the influence weights follow.
	 */
	fun setProportionalFalloff(falloff: ProportionalFalloff) {
		latches.setProportionalFalloff(falloff)
	}

	/**
	 * Sets the proportional influence radius, clamped to the allowed range.  A no-op while proportional
	 * editing is off (the radius only changes from the mid-gesture scroll, which requires it on).
	 *
	 * @param Float radiusWorld The influence radius in world units (canvas px).
	 */
	fun setProportionalRadius(radiusWorld: Float) {
		latches.setProportionalRadius(radiusWorld)
	}

	/**
	 * Fires the geometry-dependent snap operations (Blender's Shift+S) for the active mode's overlay to
	 * execute: the posed world projections and the deformer-chain inverse those snaps need live with the
	 * overlays, not here (the same division as [meshConfirmRequests]).  The purely arithmetical snaps
	 * (cursor to world origin / to grid) never pass through - their command handlers set the cursor
	 * directly.
	 */
	val snapRequests: SharedFlow<SnapKind> = requestBus.snapRequests

	/**
	 * Requests a geometry-dependent snap (see [snapRequests]).
	 *
	 * @param SnapKind kind The snap to perform.
	 */
	fun requestSnap(kind: SnapKind) {
		requestBus.requestSnap(kind)
	}

	/**
	 * Fires Select Linked (Blender's L / Ctrl+L) for one overlay to execute: a keymap command carries
	 * no pointer position, so the overlay (which tracks the pointer and owns the projected geometry)
	 * picks the seed and floods.  The payload carries the flood variant AND the dispatch-time resolved
	 * area (see [SelectLinkedRequest]), so collectors gate deterministically on their own area id
	 * instead of re-reading a pointer-side volatile at collect time.
	 */
	val selectLinkedRequests: SharedFlow<SelectLinkedRequest> = requestBus.selectLinkedRequests

	/**
	 * Requests a Select Linked (see [selectLinkedRequests]).
	 *
	 * @param Boolean fromSelection True to flood from the whole selection (Ctrl+L), false from the cursor (L).
	 * @param String? areaId The executing overlay's area, resolved at command dispatch; null no-ops.
	 */
	fun requestSelectLinked(fromSelection: Boolean, areaId: String?) {
		requestBus.requestSelectLinked(SelectLinkedRequest(fromSelection, areaId))
	}

	/**
	 * Fires a UV snap (the UV editor's Shift+S pie) for one UV editor overlay to execute: the shown
	 * atlas page's dimensions and display geometry live with the overlay, so it performs the snap over
	 * the texture coordinates (the texture-space sibling of [snapRequests]).  The payload carries the
	 * operation AND the dispatch-time resolved area (see [UvSnapRequest]), so the collector gates
	 * deterministically on its own area id.
	 */
	val uvSnapRequests: SharedFlow<UvSnapRequest> = requestBus.uvSnapRequests

	/**
	 * Requests a UV snap (see [uvSnapRequests]).
	 *
	 * @param UvSnapRequest request The snap operation plus the executing overlay's area, resolved at
	 *   command dispatch; a null area (the hovered surface was not a UV editor) no-ops.
	 */
	fun requestUvSnap(request: UvSnapRequest) {
		requestBus.requestUvSnap(request)
	}

	/**
	 * Fires "switch the edited mesh to the drawable under the cursor" (Alt+Q) for the Edit overlay to
	 * execute - the pointer position and the pick live there (the same division as
	 * [selectLinkedRequests]).
	 */
	val switchObjectRequests: SharedFlow<Unit> = requestBus.switchObjectRequests

	/** Requests an Alt+Q edited-mesh switch (see [switchObjectRequests]). */
	fun requestSwitchObjectUnderCursor() {
		requestBus.requestSwitchObjectUnderCursor()
	}

	/**
	 * Fires a rip (Blender's V) for the Edit overlay to execute: which side of the fan follows the
	 * ripped copies depends on the pointer, which lives with the overlay (the same division as
	 * [selectLinkedRequests]).
	 */
	val ripRequests: SharedFlow<Unit> = requestBus.ripRequests

	/** Requests a rip at the pointer (see [ripRequests]). */
	fun requestRip() {
		requestBus.requestRip()
	}

	/**
	 * Commits a topology operation on one session mesh as ONE undo step: the model takes the edit (mesh
	 * swap, keyform-delta rebuild, glue remap - see [withMeshTopologyEdit]) and the mesh selection
	 * becomes the operation's result elements on that mesh, in the SAME history push - splitting them
	 * would let undo tear the selection from the topology it indexes into.  The ops produce vertex
	 * results; they are re-derived into the CURRENT select mode (Blender keeps the mode across a
	 * topology op - a face-mode duplicate leaves the new faces selected in face mode), falling back to
	 * vertex mode only when nothing in the current domain covers them (e.g. a duplicated lone edge
	 * copies as loose vertices, which no edge or face contains - stranding them unselected would hide
	 * the copies and starve the follow-up auto-grab).  A no-op edit records nothing.
	 *
	 * @param String labelKey The operation's history label key (change.mesh.duplicate / merge / rip / connect).
	 * @param DrawableId drawableId The edited mesh.
	 * @param TopologyOpResult result The op builder's outcome.
	 */
	fun commitMeshTopology(labelKey: String, drawableId: DrawableId, result: TopologyOpResult) {
		val newModel = mutableModel.value.withMeshTopologyEdit(drawableId, result.edit)
		if (newModel === mutableModel.value) {
			return
		}
		val current = mutableMeshSelection.value
		val vertexResult =
			MeshSelection(
				drawableIds = current.drawableIds,
				activeDrawableId = drawableId,
				selectMode = MeshSelectMode.Vertex,
				elementsByDrawable = if (result.newElements.isEmpty()) emptyMap() else mapOf(drawableId to result.newElements),
				activeElement = result.newElements.firstOrNull()?.let { element -> ActiveMeshElement(drawableId, element) },
			)
		val newSelection = rederiveTopologyResult(vertexResult, current.selectMode, drawableId, newModel)
		val change = MeshChange.TopologyEdit(drawableId, labelKey)
		history.push(EditorSnapshot(newModel, mutableSelection.value, mutablePose.value, newSelection, mutableMode.value), change)
		mutableModel.value = newModel
		mutableMeshSelection.value = newSelection
		refreshFlags()
		mutableChanges.tryEmit(change)
	}

	/**
	 * Converts a topology op's vertex-mode result selection into [selectMode] against [newModel] (the
	 * post-edit topology, where the new elements exist), via the strict derive-up rules of
	 * [MeshSelectionOps.changeSelectMode]; the first derived element becomes active.  Returns the
	 * vertex result unchanged when the session is already in vertex mode, when the op selected
	 * nothing, or when nothing in the target domain covers the new vertices (see
	 * [commitMeshTopology]'s docblock for that fallback's rationale).
	 *
	 * @param MeshSelection vertexResult The op's result selection, in vertex mode.
	 * @param MeshSelectMode selectMode The session's current select mode to re-derive into.
	 * @param DrawableId drawableId The edited mesh.
	 * @param PuppetModel newModel The model with the topology edit applied.
	 * @return MeshSelection The result selection in the kept mode, or the vertex fallback.
	 */
	private fun rederiveTopologyResult(
		vertexResult: MeshSelection,
		selectMode: MeshSelectMode,
		drawableId: DrawableId,
		newModel: PuppetModel,
	): MeshSelection {
		if (selectMode == MeshSelectMode.Vertex || vertexResult.elementsOf(drawableId).isEmpty()) {
			return vertexResult
		}
		val rederived =
			MeshSelectionOps.changeSelectMode(vertexResult, selectMode) { candidateId ->
				newModel.drawables.firstOrNull { drawable -> drawable.id == candidateId }?.mesh?.indices
			}
		val rederivedElements = rederived.elementsOf(drawableId)
		if (rederivedElements.isEmpty()) {
			return vertexResult
		}
		return rederived.copy(activeElement = ActiveMeshElement(drawableId, rederivedElements.first()))
	}

	/**
	 * Duplicates the ACTIVE session mesh's covered elements in place (Edit-mode Shift+D) as one undo
	 * step, leaving the copies selected - the caller follows with a Grab so the copies pull away under
	 * the pointer, Blender-style.  A no-op outside Edit mode or with nothing covered on the active mesh.
	 */
	fun duplicateSelectedElements() {
		if (mutableMode.value != EditorMode.Edit) {
			return
		}
		val selection = mutableMeshSelection.value
		val drawableId = selection.activeDrawableId ?: return
		val mesh = mutableModel.value.drawables.firstOrNull { it.id == drawableId }?.mesh ?: return
		val covered = MeshTopology.coveredVertexIndices(selection.elementsOf(drawableId), mesh.indices)
		val result = MeshTopologyOps.duplicateElements(mesh, covered) ?: return
		commitMeshTopology("change.mesh.duplicate", drawableId, result)
	}

	/**
	 * Merges the ACTIVE session mesh's selected vertices (Blender's M) as one undo step, leaving the
	 * survivor selected.  Vertex mode only - the first / last targets read the selection order, which
	 * only vertex elements carry directly.  Refusals explain themselves with a near-cursor notice.
	 *
	 * @param MergeTarget target Where the survivor lands (center / first / last).
	 */
	fun mergeSelectedVertices(target: MergeTarget) {
		if (mutableMode.value != EditorMode.Edit) {
			return
		}
		val selection = mutableMeshSelection.value
		val drawableId = selection.activeDrawableId ?: return
		if (selection.selectMode != MeshSelectMode.Vertex) {
			emitNotice("notice.merge.needsVertices", NoticePlacement.NearCursor)
			return
		}
		// The element set is insertion-ordered (a LinkedHashSet built by the gestures), so "first" is
		// the earliest-selected vertex; "last" prefers the active element (the most recent touch).
		val orderedVertices = selection.elementsOf(drawableId).filterIsInstance<MeshElement.Vertex>().map { vertex -> vertex.index }.toMutableList()
		(selection.activeElement?.element as? MeshElement.Vertex)?.let { activeVertex ->
			if (orderedVertices.remove(activeVertex.index)) {
				orderedVertices.add(activeVertex.index)
			}
		}
		if (orderedVertices.size < 2) {
			emitNotice("notice.merge.needsVertices", NoticePlacement.NearCursor)
			return
		}
		val mesh = mutableModel.value.drawables.firstOrNull { it.id == drawableId }?.mesh ?: return
		val result = MeshTopologyOps.mergeVertices(mesh, orderedVertices, target) ?: return
		commitMeshTopology("change.mesh.merge", drawableId, result)
	}

	/**
	 * Connects the ACTIVE session mesh's two selected vertices with a cut (Blender's J) as one undo
	 * step, leaving the cut path selected.  Exactly two selected vertices in vertex mode; a refusal
	 * (already connected, nothing crossed, degenerate geometry) explains itself with a notice.
	 */
	fun connectSelectedVertices() {
		if (mutableMode.value != EditorMode.Edit) {
			return
		}
		val selection = mutableMeshSelection.value
		val drawableId = selection.activeDrawableId ?: return
		val vertices = selection.elementsOf(drawableId).filterIsInstance<MeshElement.Vertex>().map { vertex -> vertex.index }
		if (selection.selectMode != MeshSelectMode.Vertex || vertices.size != 2) {
			emitNotice("notice.connect.needsTwoVertices", NoticePlacement.NearCursor)
			return
		}
		val mesh = mutableModel.value.drawables.firstOrNull { it.id == drawableId }?.mesh ?: return
		val result = MeshTopologyOps.connectVertices(mesh, vertices[0], vertices[1])
		if (result == null) {
			emitNotice("notice.connect.refused", NoticePlacement.NearCursor)
			return
		}
		commitMeshTopology("change.mesh.connect", drawableId, result)
	}

	/**
	 * Duplicates every eligible selected drawable (Object-mode Shift+D) as ONE undo step: each copy
	 * lands after its source in the org tree, and the selection becomes the copies - the caller follows
	 * with a Grab so they pull away under the pointer, Blender-style.
	 *
	 * @return List<DrawableId> The created copies (empty when nothing was eligible).
	 */
	fun duplicateSelectedDrawables(): List<DrawableId> {
		if (mutableMode.value != EditorMode.Object) {
			return emptyList()
		}
		val eligibleIds = eligibleTransformDrawables(mutableSelection.value, mutableModel.value) ?: return emptyList()
		var newModel = mutableModel.value
		val copies = ArrayList<DrawableId>(eligibleIds.size)
		for (drawableId in eligibleIds) {
			val (edited, copyId) = newModel.withDrawableDuplicated(drawableId) ?: continue
			newModel = edited
			copies.add(copyId)
		}
		if (copies.isEmpty() || newModel === mutableModel.value) {
			return emptyList()
		}
		val newSelection =
			Selection(
				copies.map { copyId -> SelectionTarget.Drawable(copyId) }.toSet<SelectionTarget>(),
				SelectionTarget.Drawable(copies.last()),
			)
		val change = DrawableChange.Duplicate(copies)
		history.push(EditorSnapshot(newModel, newSelection, mutablePose.value, mutableMeshSelection.value, mutableMode.value), change)
		mutableModel.value = newModel
		mutableSelection.value = newSelection
		refreshFlags()
		mutableChanges.tryEmit(change)
		return copies
	}

	/**
	 * Re-seeds the Edit session onto one drawable (Alt+Q's switch), as ONE undo step covering both
	 * selections: the session's meshes become just [drawableId] (with its remembered elements restored
	 * where they still fit), and the OBJECT selection moves onto the same drawable - so tabbing back to
	 * Object mode keeps the switched mesh instead of reviving the selection Edit mode was entered with.
	 * The outgoing meshes' element selections stash into the per-mesh memory first, and the
	 * remembered-drawable memory follows.  A no-op outside Edit mode or when the drawable carries no
	 * mesh.
	 *
	 * Built as one combined snapshot push (never chained setSelection + setMeshSelection - each of
	 * those snapshots the OTHER selection's pre-change value, which would tear the pair across two
	 * undo steps).
	 *
	 * @param DrawableId drawableId The mesh to edit next.
	 */
	fun switchEditDrawable(drawableId: DrawableId) {
		if (mutableMode.value != EditorMode.Edit) {
			return
		}
		val model = mutableModel.value
		if (model.drawables.none { drawable -> drawable.id == drawableId && drawable.mesh != null }) {
			return
		}
		elementMemory.stash(mutableMeshSelection.value)
		elementMemory.lastActiveDrawableId = drawableId
		val newObjectSelection = SelectionOps.replace(SelectionTarget.Drawable(drawableId))
		val newMeshSelection = elementMemory.restore(MeshSelection.editing(listOf(drawableId)), model)
		if (newObjectSelection == mutableSelection.value && newMeshSelection == mutableMeshSelection.value) {
			return
		}
		history.push(
			EditorSnapshot(model, newObjectSelection, mutablePose.value, newMeshSelection, mutableMode.value),
			EditorStateChange.MeshSelectionChanged,
		)
		mutableSelection.value = newObjectSelection
		mutableMeshSelection.value = newMeshSelection
		refreshFlags()
		mutableChanges.tryEmit(EditorStateChange.MeshSelectionChanged)
	}

	/**
	 * Fires when an in-flight modal mesh gesture should confirm. The working positions live in the desktop
	 * overlay, so the session cannot commit directly - it signals here and the overlay commits. This is the
	 * keyboard path (Enter); a primary click confirms in the overlay's own pointer loop.
	 */
	val meshConfirmRequests: SharedFlow<Unit> = requestBus.meshConfirmRequests

	/** Requests that the gizmo overlay confirm the in-flight modal gesture (bound to Enter, like a click). */
	fun requestMeshConfirm() {
		requestBus.requestMeshConfirm()
	}

	/**
	 * Fires when an in-flight selection gesture should be abandoned (Escape).  The box rubber-band and the
	 * circle stroke live in the overlay's local state, so the session cannot discard them directly - it
	 * signals here and the overlay clears them.  Clearing a latched tool ([clearSelectTool]) already tells
	 * the overlay to abandon its gesture through the tool flow; this is the extra path for a non-armed box
	 * drag, which owns no tool state to change.
	 */
	val meshGestureCancelRequests: SharedFlow<Unit> = requestBus.meshGestureCancelRequests

	/** Requests that the gizmo overlay abandon any in-flight box / circle selection gesture (bound to Escape). */
	fun requestMeshGestureCancel() {
		requestBus.requestMeshGestureCancel()
	}

	/** Steps back one undo level, republishing the model and selection. No-op when nothing to undo. */
	fun undo() {
		restore(history.undo() ?: return)
	}

	/** Steps forward one redo level, republishing the model and selection. No-op when nothing to redo. */
	fun redo() {
		restore(history.redo() ?: return)
	}

	/**
	 * Jumps the history cursor directly to [index], republishing the model and selection at that step. The
	 * history panel calls this when a row is clicked, so the user can leap across several undo levels at
	 * once. No-op when [index] is already the live step.
	 *
	 * @param Int index The target step index within [historyView].
	 */
	fun jumpTo(index: Int) {
		restore(history.jumpTo(index) ?: return)
	}

	/**
	 * Marks the current model as the saved baseline, clearing the dirty marker. Called after a successful
	 * Save. (The PuppetModel -> CMO3 lowering that actually persists edits is a later phase; this only
	 * moves the dirty baseline.)
	 */
	fun markSaved() {
		history.markSaved(mutableModel.value)
		refreshFlags()
	}

	/**
	 * Restores every session flow from [snapshot] - the history mechanism behind undo, redo, and jumpTo.
	 * Also updates the remembered active drawable and tears down all transient tool state (see the inline
	 * comment), then republishes the derived flags.
	 *
	 * @param EditorSnapshot snapshot The history snapshot to restore.
	 */
	private fun restore(snapshot: EditorSnapshot) {
		// The remembered drawable tracks whatever was most recently shown active, undo/redo included.
		(snapshot.selection.active as? SelectionTarget.Drawable)?.let { activeDrawable ->
			elementMemory.lastActiveDrawableId = activeDrawable.id
		}
		mutableModel.value = snapshot.model
		mutableSelection.value = snapshot.selection
		mutablePose.value = snapshot.pose
		mutableMeshSelection.value = snapshot.meshSelection
		// An undo / redo ends any in-flight gesture or armed tool, regardless of the restored mode: the select
		// tool and its overlays are shared across modes, so a tool armed in one mode must not survive a restore
		// into a snapshot of the other and drive the wrong overlay.
		latches.clearTransient(clearAxisConstraint = true, clearViewportGesture = true)
		latches.setPreviewSelection(null)
		latches.closePieMenu()
		mutableMode.value = snapshot.mode
		refreshFlags()
	}

	/**
	 * Republishes the flags derived from the model and the history stack - dirty, canUndo, canRedo, and
	 * the projected history view.  Called after every mutation, restore, and saved-baseline move.
	 */
	private fun refreshFlags() {
		history.refreshFlags(mutableModel.value)
	}
}
