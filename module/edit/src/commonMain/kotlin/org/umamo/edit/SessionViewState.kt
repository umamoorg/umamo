package org.umamo.edit

import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.firstEditableDrawableInPanelOrder

/**
 * How a document was being worked on, as opposed to what it is: the session state a saved document carries so a
 * rigger reopens it where they left off (docs/format/UMA.md § 7.4).  None of it is document content - nothing here
 * mints a model, so none of it marks a document dirty - and the pose rides beside it as the session's initial pose.
 *
 * Plain data with no format knowledge: whoever reads the file builds one, and the session validates it against
 * the model it opens on ([fittedTo]), since a saved id can name an object the file no longer has.
 *
 * @property Selection              selection            The object selection.
 * @property ParameterSelection     parameterSelection   The parameters targeted for keyform authoring.
 * @property EditorMode             mode                 The interaction mode; Edit holds only when it can be entered.
 * @property MeshSelectMode         selectMode           The mesh select mode, meaningful in Edit mode.
 * @property Cursor2d?              cursor2d             The 2D cursor, or null when never placed.
 * @property UvCursor?              uvCursor             The UV cursor, or null when never placed.
 * @property TransformPivotMode     pivotMode            What a modal Scale or Rotate turns about.
 * @property Boolean                proportionalEnabled  Whether proportional editing is on.
 * @property ProportionalEditState? proportionalSettings The proportional falloff, radius, and connected flag - kept
 *   while proportional editing is off, since a toggle brings them back - or null for the defaults.
 * @property GridConfig?            gridConfig           The document's own grid, or null to follow the application's.
 */
data class SessionViewState(
	val selection: Selection = Selection(),
	val parameterSelection: ParameterSelection = ParameterSelection(),
	val mode: EditorMode = EditorMode.Object,
	val selectMode: MeshSelectMode = MeshSelectMode.Vertex,
	val cursor2d: Cursor2d? = null,
	val uvCursor: UvCursor? = null,
	val pivotMode: TransformPivotMode = TransformPivotMode.MedianPoint,
	val proportionalEnabled: Boolean = false,
	val proportionalSettings: ProportionalEditState? = null,
	val gridConfig: GridConfig? = null,
)

/**
 * This state with every reference [model] cannot satisfy taken out: a selected target the model lacks is dropped,
 * and an active that did not survive falls to one that did.  A saved file can name an object deleted by another
 * writer or by a hand edit, and a selection holding a ghost would hand every command a target that is not there.
 *
 * @param PuppetModel model The model the session opens on.
 * @return SessionViewState The state that fits the model.
 */
internal fun SessionViewState.fittedTo(model: PuppetModel): SessionViewState {
	val partIds = model.parts.mapTo(HashSet()) { part -> part.id }
	val drawableIds = model.drawables.mapTo(HashSet()) { drawable -> drawable.id }
	val deformerIds = model.deformers.mapTo(HashSet()) { deformer -> deformer.id }
	val targets =
		selection.targets.filterTo(LinkedHashSet()) { target ->
			when (target) {
				is SelectionTarget.Part -> target.id in partIds
				is SelectionTarget.Drawable -> target.id in drawableIds
				is SelectionTarget.Deformer -> target.id in deformerIds
			}
		}
	val parameterIds = model.parameters.mapTo(HashSet()) { parameter -> parameter.id }
	val targetedParameters = parameterSelection.ids.filterTo(LinkedHashSet()) { parameterId -> parameterId in parameterIds }
	return copy(
		selection = Selection(targets, selection.active?.takeIf { active -> active in targets } ?: targets.lastOrNull()),
		parameterSelection =
			ParameterSelection(
				targetedParameters,
				parameterSelection.active?.takeIf { active -> active in targetedParameters } ?: targetedParameters.lastOrNull(),
			),
	)
}

/**
 * The meshes an Edit-mode session opens on, and which of them is active.
 *
 * @property List<DrawableId> drawableIds   The session's meshes.
 * @property DrawableId       activeId      The active mesh.
 * @property Boolean          activeSelected Whether [activeId] is the object selection's own active drawable, as opposed
 *   to a fallback the entry chose.
 */
internal class EditSeed(
	val drawableIds: List<DrawableId>,
	val activeId: DrawableId,
	val activeSelected: Boolean,
)

/**
 * What entering Edit mode opens on: every selected mesh-carrying drawable (multi-mesh edit, the object selection's
 * active drawable becoming the active mesh), else the remembered drawable, else the topmost editable drawable in
 * Parts-panel order - or null when the model has nothing editable, which refuses the entry.
 *
 * @param PuppetModel model                The session's model.
 * @param Selection   selection            The object selection.
 * @param DrawableId? rememberedDrawableId The last drawable that was active, or null.
 * @return EditSeed? The meshes to open on, or null when Edit mode cannot be entered.
 */
internal fun editSeedOf(model: PuppetModel, selection: Selection, rememberedDrawableId: DrawableId?): EditSeed? {
	val selectedMeshedIds =
		selection.targets
			.filterIsInstance<SelectionTarget.Drawable>()
			.map { target -> target.id }
			.filter { candidateId -> model.drawables.any { drawable -> drawable.id == candidateId && drawable.mesh != null } }
	val activeSelectedId = (selection.active as? SelectionTarget.Drawable)?.id?.takeIf { activeId -> activeId in selectedMeshedIds }
	val remembered = rememberedDrawableId?.takeIf { candidateId -> model.drawables.any { drawable -> drawable.id == candidateId && drawable.mesh != null } }
	val seedDrawableIds =
		when {
			selectedMeshedIds.isNotEmpty() -> selectedMeshedIds
			remembered != null -> listOf(remembered)
			else -> listOfNotNull(model.firstEditableDrawableInPanelOrder())
		}
	if (seedDrawableIds.isEmpty()) {
		return null
	}
	return EditSeed(seedDrawableIds, activeSelectedId ?: seedDrawableIds.first(), activeSelectedId != null)
}

/**
 * The snapshot a session opens on: the model and pose as given, with [viewState] fitted to the model laid in, so
 * the state a document was saved with is where its history STARTS rather than a run of undo steps on top of it.
 *
 * A saved Edit mode holds only when it can be entered over the restored selection; when the model has nothing
 * editable the session opens in Object mode, the same refusal [EditorSession.setMode] makes.
 *
 * @param PuppetModel        model     The document model at open.
 * @param Pose               pose      The pose at open.
 * @param SessionViewState?  viewState The saved session state, already fitted, or null for a plain open.
 * @return EditorSnapshot The session's first snapshot.
 */
internal fun openingSnapshotOf(model: PuppetModel, pose: Pose, viewState: SessionViewState?): EditorSnapshot {
	if (viewState == null) {
		return EditorSnapshot(model, Selection(), pose)
	}
	val editSeed = if (viewState.mode == EditorMode.Edit) editSeedOf(model, viewState.selection, rememberedDrawableId = null) else null
	return EditorSnapshot(
		model = model,
		selection = viewState.selection,
		pose = pose,
		meshSelection = editSeed?.let { seed -> MeshSelection.editing(seed.drawableIds, seed.activeId).copy(selectMode = viewState.selectMode) } ?: MeshSelection(),
		mode = if (editSeed != null) EditorMode.Edit else EditorMode.Object,
		parameterSelection = viewState.parameterSelection,
	)
}