package org.umamo.ui.viewport.viewport2d

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshSelection
import org.umamo.render.puppet.MeshOverlaySizes
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.visibleDrawableIds
import org.umamo.ui.viewport.PuppetViewportService
import org.umamo.ui.viewport.gizmo.EditMeshOverlayProducer

/*
 * The 2D viewport's mesh overlay: the session's derive (gizmo/EditMeshOverlayProducer.kt) published to the
 * render service, which draws it over the art from the art's own deformed positions - the Edit cage, and
 * the wireframe of the shown meshes (those outside the edit, or every one in Object mode) while some 2D
 * area asks for it.
 */

/**
 * Publishes the mesh overlay to [service] for as long as it runs: derived from the mode, the brush stroke's
 * preview or else the committed mesh selection, the model, the sizes, and whether any 2D area asks for the
 * wireframe, and published whenever the derived value changes by identity (the producer hands back the
 * same instance while nothing it shows changed, so a Grab's confirm, which commits positions only,
 * publishes nothing).  The wireframe covers the model's shown drawables, the same set the renderer is
 * handed to draw, so hidden parts stay hidden; the set is walked once per model, not per derive, and not at
 * all while no area asks for the wireframe.
 *
 * The derive runs on [deriveDispatcher], one input at a time with the latest winning (a selection over a
 * whole large rig costs about a tenth of a second), and the publish lands back on the caller's dispatcher.
 *
 * @param PuppetViewportService service The render service to publish to.
 * @param EditorSession session The session to derive from.
 * @param Flow<MeshOverlaySizes> sizes The overlay sizes, re-emitted when the density changes.
 * @param Flow<Boolean> wireframeWanted Whether any 2D area asks for the wireframe.
 * @param CoroutineDispatcher deriveDispatcher Where the derive runs.
 */
internal suspend fun publishMeshOverlay(
	service: PuppetViewportService,
	session: EditorSession,
	sizes: Flow<MeshOverlaySizes>,
	wireframeWanted: Flow<Boolean>,
	deriveDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
	val producer = EditMeshOverlayProducer()
	val shownModels = session.model.map { model -> ShownModel(model) }
	combine(session.mode, session.meshSelection, session.meshPreviewSelection, shownModels, sizes) { mode, committed, preview, shown, overlaySizes ->
		MeshOverlayInputs(mode, preview ?: committed, shown, overlaySizes)
	}
		.combine(wireframeWanted) { inputs, wanted -> inputs to wanted }
		.conflate()
		.map { (inputs, wanted) ->
			producer.produce(inputs.mode, inputs.selection, inputs.shown.model, inputs.sizes, wireframeOver = if (wanted) inputs.shown.shownIds else null)
		}
		.flowOn(deriveDispatcher)
		.conflate()
		.distinctUntilChanged { previous, next -> previous === next }
		.collect { overlay -> service.setMeshOverlay(overlay) }
}

/**
 * One model with its shown drawables, walked at most once and only once some area asks for the wireframe:
 * the set depends on the model alone, where a derive runs for every selection, preview, and size change
 * besides.  One derive at a time reads it, so the walk needs no lock.
 *
 * @property PuppetModel model The model.
 */
private class ShownModel(val model: PuppetModel) {
	/** The drawables the renderer is handed to draw, which the wireframe covers. */
	val shownIds: Set<DrawableId> by lazy(LazyThreadSafetyMode.NONE) { model.visibleDrawableIds() }
}

/**
 * One derive's inputs, taken together so the derive never pairs one emission's selection with another's
 * model by accident of timing.
 *
 * @property EditorMode mode The editor mode.
 * @property MeshSelection selection The selection to show: the brush preview when one is live.
 * @property ShownModel shown The model, with its shown drawables.
 * @property MeshOverlaySizes sizes The overlay sizes.
 */
private class MeshOverlayInputs(
	val mode: EditorMode,
	val selection: MeshSelection,
	val shown: ShownModel,
	val sizes: MeshOverlaySizes,
)