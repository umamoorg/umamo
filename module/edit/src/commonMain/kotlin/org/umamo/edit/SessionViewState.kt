package org.umamo.edit

import org.umamo.runtime.eval.defaultValueLookup
import org.umamo.runtime.eval.poseValueLookup
import org.umamo.runtime.eval.warpDrawnAt
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ElementOwner
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.firstEditableDrawableInPanelOrder
import org.umamo.runtime.model.meshOf

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
 * @property EditorMode             mode                 The interaction mode; Edit and Pose hold only when they can be entered.
 * @property MeshSelectMode         selectMode           The element select mode, meaningful in Edit and Pose mode.
 * @property Cursor2d?              cursor2d             The 2D cursor, or null when never placed.
 * @property UvCursor?              uvCursor             The UV cursor, or null when never placed.
 * @property TransformPivotMode     pivotMode            What a modal Scale or Rotate turns about.
 * @property Boolean                proportionalEnabled  Whether proportional editing is on.
 * @property ProportionalEditState? proportionalSettings The proportional falloff, radius, and connected flag - kept
 *   while proportional editing is off, since a toggle brings them back - or null for the defaults.
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
 * The owners an Edit- or Pose-mode session opens on, and which of them is active: drawables' meshes, or warps'
 * lattices, never both (Edit mode edits the active item's kind).
 *
 * @property List<ElementOwner> owners The session's owners.
 * @property ElementOwner activeOwner The active owner.
 * @property Boolean activeSelected Whether [activeOwner] is the object selection's own active item, as opposed to a
 *   fallback the entry chose.
 */
internal class EditSeed(
	val owners: List<ElementOwner>,
	val activeOwner: ElementOwner,
	val activeSelected: Boolean,
) {
	/** The fresh element session the seed opens: its owners, the active one active, nothing selected yet. */
	val session: MeshSelection get() = MeshSelection.editing(owners, activeOwner)
}

/**
 * Whether Pose mode can open on this model: it poses deformers, so a model with none has nothing to pose.  The one
 * rule entering, returning to, and restoring Pose mode read.
 */
internal val PuppetModel.canPose: Boolean get() = deformers.isNotEmpty()

/** What an attempt to enter Edit mode comes to: a session to open, or a refusal. */
internal sealed interface EditEntry {
	/**
	 * Edit mode opens on [seed].
	 *
	 * @property EditSeed seed The owners to open on.
	 */
	class Opens(val seed: EditSeed) : EditEntry

	/**
	 * Edit mode stays shut.
	 *
	 * @property String? noticeKey The near-cursor notice that says why, or null when the model simply has nothing
	 *   editable.
	 */
	class Refused(val noticeKey: String?) : EditEntry
}

/**
 * What entering Edit mode opens on, by the active item's kind (Blender's rule).
 *
 * An active warp opens on every selected warp Edit mode can shape (one with a lattice the default pose draws, see
 * [hasRestLattice]); an active warp it cannot shape, or an active rotation deformer, refuses with a notice naming
 * why.  Otherwise the session opens on every selected mesh-carrying drawable (multi-mesh edit, the object
 * selection's active drawable becoming the active mesh), else the remembered owner when it is still editable, else
 * the topmost editable drawable in Parts-panel order - or nothing, which refuses the entry silently.
 *
 * @param PuppetModel model The session's model.
 * @param Selection selection The object selection.
 * @param ElementOwner? rememberedOwner The last owner that was active, or null.
 * @return EditEntry The session to open, or the refusal.
 */
internal fun editEntryOf(model: PuppetModel, selection: Selection, rememberedOwner: ElementOwner?): EditEntry {
	val active = selection.active

	if (active is SelectionTarget.Deformer) {
		when (val deformer = model.deformers.firstOrNull { candidate -> candidate.id == active.id }) {
			is Deformer.Rotation -> {
				return EditEntry.Refused("notice.edit.rotationNotEditable")
			}

			is Deformer.Warp -> {
				if (!model.hasRestLattice(deformer.id)) {
					return EditEntry.Refused(if (deformer.geometryGrid == null) "notice.edit.warpHasNoLattice" else "notice.edit.warpHiddenAtRest")
				}

				return EditEntry.Opens(EditSeed(selectedWarpOwners(model, selection, model.defaultValueLookup()), ElementOwner.Deformer(deformer.id), activeSelected = true))
			}

			null -> {}
		}
	}

	val selectedMeshedIds =
		selection.targets
			.filterIsInstance<SelectionTarget.Drawable>()
			.map { target -> target.id }
			.filter { candidateId -> model.meshOf(candidateId) != null }
	val activeSelectedId = (active as? SelectionTarget.Drawable)?.id?.takeIf { activeId -> activeId in selectedMeshedIds }

	if (selectedMeshedIds.isNotEmpty()) {
		val owners = selectedMeshedIds.map { drawableId -> ElementOwner.Drawable(drawableId) }
		val activeOwner = ElementOwner.Drawable(activeSelectedId ?: selectedMeshedIds.first())

		return EditEntry.Opens(EditSeed(owners, activeOwner, activeSelected = activeSelectedId != null))
	}

	val fallback = rememberedOwner?.takeIf { owner -> isEditableOwner(model, owner) } ?: model.firstEditableDrawableInPanelOrder()?.let { drawableId -> ElementOwner.Drawable(drawableId) }

	return if (fallback != null) EditEntry.Opens(EditSeed(listOf(fallback), fallback, activeSelected = false)) else EditEntry.Refused(null)
}

/**
 * What entering Pose mode opens on: Edit mode's seed narrowed to warps, since Pose mode shapes lattices and nothing
 * else - every selected warp the pose draws, the object selection's active warp active when it is one of them; else
 * the remembered warp when the pose draws it; else the first warp the pose draws.  Null with none of those: Pose mode
 * still scrubs and keys, so it opens on an empty session rather than refusing.
 *
 * @param PuppetModel model The session's model.
 * @param Selection selection The object selection.
 * @param ElementOwner? rememberedOwner The last owner that was active, or null.
 * @param Pose pose The pose Pose mode shows.
 * @return EditSeed? The warps to open on, or null for an empty session.
 */
internal fun poseSeedOf(model: PuppetModel, selection: Selection, rememberedOwner: ElementOwner?, pose: Pose): EditSeed? {
	val valueOf = model.poseValueLookup(pose)
	val warps = selectedWarpOwners(model, selection, valueOf)

	if (warps.isNotEmpty()) {
		val activeWarp = editOwnerOf(selection.active)?.takeIf { owner -> owner in warps }

		return EditSeed(warps, activeWarp ?: warps.first(), activeSelected = activeWarp != null)
	}

	val fallback =
		(rememberedOwner as? ElementOwner.Deformer)?.takeIf { owner -> model.warpDrawnAt(owner.id, valueOf) }
			?: model.deformers.firstOrNull { deformer -> deformer.isVisible && model.warpDrawnAt(deformer.id, valueOf) }?.let { warp -> ElementOwner.Deformer(warp.id) }

	return fallback?.let { owner -> EditSeed(listOf(owner), owner, activeSelected = false) }
}

/**
 * Whether [owner] can join a session in [mode]: in Edit mode a drawable that carries a mesh or a warp with a rest
 * lattice, in Pose mode a warp whose lattice [pose] draws, and nothing in Object mode.
 *
 * @param PuppetModel model The model.
 * @param ElementOwner owner The owner.
 * @param EditorMode mode The mode.
 * @param Pose pose The pose Pose mode shows.
 * @return Boolean True when the owner can be edited there.
 */
internal fun isSessionOwner(model: PuppetModel, owner: ElementOwner, mode: EditorMode, pose: Pose): Boolean =
	when (mode) {
		EditorMode.Object -> false
		EditorMode.Edit -> isEditableOwner(model, owner)
		EditorMode.Pose -> owner is ElementOwner.Deformer && model.warpDrawnAt(owner.id, model.poseValueLookup(pose))
	}

/**
 * The selected warps whose lattice the pose [valueOf] reads draws, as session owners in selection order.
 *
 * @param PuppetModel model The model.
 * @param Selection selection The object selection.
 * @param Function valueOf Each parameter's value at the pose.
 * @return List<ElementOwner> The warps.
 */
private fun selectedWarpOwners(model: PuppetModel, selection: Selection, valueOf: (ParameterId) -> Float): List<ElementOwner> =
	selection.targets
		.filterIsInstance<SelectionTarget.Deformer>()
		.filter { target -> model.warpDrawnAt(target.id, valueOf) }
		.map { target -> ElementOwner.Deformer(target.id) }

/**
 * Whether Edit mode can open on [owner]: a drawable that carries a mesh, or a warp with a rest lattice.
 *
 * @param PuppetModel model The model.
 * @param ElementOwner owner The owner.
 * @return Boolean True when it is editable.
 */
private fun isEditableOwner(model: PuppetModel, owner: ElementOwner): Boolean =
	when (owner) {
		is ElementOwner.Drawable -> model.meshOf(owner.id) != null
		is ElementOwner.Deformer -> model.hasRestLattice(owner.id)
	}

/**
 * Whether [deformerId] is a warp Edit mode can shape: one with a lattice the default pose draws.  Edit mode pins the
 * pose at the defaults, so a warp whose keyform grid does not bracket every default - or one under a deformer whose grid
 * does not - is hidden there and has no rest lattice to edit, which is exactly when the evaluator leaves it out.
 *
 * @param DeformerId deformerId The deformer.
 * @return Boolean True for a warp with a rest lattice.
 */
internal fun PuppetModel.hasRestLattice(deformerId: DeformerId): Boolean = warpDrawnAt(deformerId, defaultValueLookup())

/**
 * The Edit owner a selection target names: a drawable's mesh or a deformer's lattice, or null for a part.
 *
 * @param SelectionTarget? target The target, or null.
 * @return ElementOwner? Its owner, or null.
 */
fun editOwnerOf(target: SelectionTarget?): ElementOwner? =
	when (target) {
		is SelectionTarget.Drawable -> ElementOwner.Drawable(target.id)
		is SelectionTarget.Deformer -> ElementOwner.Deformer(target.id)
		is SelectionTarget.Part, null -> null
	}

/**
 * The selection target naming this Edit owner's item: the drawable, or the warp - the inverse of [editOwnerOf].
 *
 * @return SelectionTarget The target.
 */
fun ElementOwner.toSelectionTarget(): SelectionTarget =
	when (this) {
		is ElementOwner.Drawable -> SelectionTarget.Drawable(id)
		is ElementOwner.Deformer -> SelectionTarget.Deformer(id)
	}

/**
 * The snapshot a session opens on: the model and pose as given, with [viewState] fitted to the model laid in, so
 * the state a document was saved with is where its history STARTS rather than a run of undo steps on top of it.
 *
 * A saved Edit mode holds only when it can be entered over the restored selection, and a saved Pose mode only
 * when the model has a deformer; otherwise the session opens in Object mode, the same refusals
 * [EditorSession.setMode] makes.  Either mode opens its session seeded as entering it seeds one.
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

	val editSeed = if (viewState.mode == EditorMode.Edit) (editEntryOf(model, viewState.selection, rememberedOwner = null) as? EditEntry.Opens)?.seed else null
	val mode =
		when {
			editSeed != null -> EditorMode.Edit
			viewState.mode == EditorMode.Pose && model.canPose -> EditorMode.Pose
			else -> EditorMode.Object
		}
	// A Pose session opens on the warps the saved selection poses, as entering Pose mode seeds it.
	val sessionSeed = if (mode == EditorMode.Pose) poseSeedOf(model, viewState.selection, rememberedOwner = null, pose) else editSeed

	return EditorSnapshot(
		model = model,
		selection = viewState.selection,
		pose = pose,
		meshSelection = sessionSeed?.session?.copy(selectMode = viewState.selectMode) ?: MeshSelection(),
		mode = mode,
		parameterSelection = viewState.parameterSelection,
	)
}