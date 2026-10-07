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
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.viewport.PuppetViewportService
import org.umamo.ui.viewport.gizmo.EditMeshOverlayProducer

/*
 * The 2D viewport's Edit-mode mesh overlay: the session's derive (gizmo/EditMeshOverlayProducer.kt)
 * published to the render service, which draws it over the art from the art's own deformed positions.
 */

/**
 * Publishes the Edit-mode mesh overlay to [service] for as long as it runs: derived from the mode, the
 * brush stroke's preview or else the committed mesh selection, the model, and the sizes, and published
 * whenever the derived value changes by identity (the producer hands back the same instance while nothing
 * it shows changed, so a Grab's confirm, which commits positions only, publishes nothing).
 *
 * The derive runs on [deriveDispatcher], one input at a time with the latest winning (a selection over a
 * whole large rig costs about a tenth of a second), and the publish lands back on the caller's dispatcher.
 *
 * @param PuppetViewportService service The render service to publish to.
 * @param EditorSession session The session to derive from.
 * @param Flow<MeshOverlaySizes> sizes The overlay sizes, re-emitted when the density changes.
 * @param CoroutineDispatcher deriveDispatcher Where the derive runs.
 */
internal suspend fun publishEditMeshOverlay(
	service: PuppetViewportService,
	session: EditorSession,
	sizes: Flow<MeshOverlaySizes>,
	deriveDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
	val producer = EditMeshOverlayProducer()
	combine(session.mode, session.meshSelection, session.meshPreviewSelection, session.model, sizes) { mode, committed, preview, model, overlaySizes ->
		EditMeshOverlayInputs(mode, preview ?: committed, model, overlaySizes)
	}
		.conflate()
		.map { inputs -> producer.produce(inputs.mode, inputs.selection, inputs.model, inputs.sizes) }
		.flowOn(deriveDispatcher)
		.conflate()
		.distinctUntilChanged { previous, next -> previous === next }
		.collect { overlay -> service.setMeshOverlay(overlay) }
}

/**
 * One derive's inputs, taken together so the derive never pairs one emission's selection with another's
 * model by accident of timing.
 *
 * @property EditorMode mode The editor mode.
 * @property MeshSelection selection The selection to show: the brush preview when one is live.
 * @property PuppetModel model The model.
 * @property MeshOverlaySizes sizes The overlay sizes.
 */
private class EditMeshOverlayInputs(
	val mode: EditorMode,
	val selection: MeshSelection,
	val model: PuppetModel,
	val sizes: MeshOverlaySizes,
)