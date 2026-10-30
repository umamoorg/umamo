package org.umamo.ui.viewport.viewport2d

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.render.puppet.LatticeOverlay
import org.umamo.render.puppet.LatticeRole
import org.umamo.render.puppet.MeshOverlaySizes
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.visibleDeformerIds
import org.umamo.ui.viewport.PuppetViewportService

/*
 * The 2D viewport's warp lattices: which warps the renderer draws a lattice over, in which role, and which of them
 * hold an unkeyed Pose-mode shape, published to the render service.  The renderer reads each lattice's points off the
 * pose it draws, so the value carries no positions and a pose change publishes nothing.
 */

/**
 * The lattice layer's sizes for a screen density: a 2.5 dp control-point dot radius and a 1 dp grid line, in
 * the display pixels the renderer multiplies by its render scale.  The face-dot size is unused.
 *
 * @param Density density The screen density.
 * @return MeshOverlaySizes The sizes in display pixels.
 */
internal fun latticeOverlaySizes(density: Density): MeshOverlaySizes =
	with(density) {
		MeshOverlaySizes(
			vertexDotRadiusPx = 2.5.dp.toPx(),
			edgeWidthPx = 1.dp.toPx(),
			faceDotRadiusPx = 0f,
		)
	}

/**
 * The lattices the 2D viewport draws in [mode]: in Object and Pose mode, every shown warp, the active one in
 * the active role, the other selected ones selected, and the rest idle (drawn only by an area that shows every
 * deformer); in Edit mode none, since Edit mode shows the edited mesh alone.  In Pose mode the warps being posed
 * are left to the session's cage, which draws their points, so the layer carries the others alone.  A warp holding
 * an unkeyed Pose-mode shape is marked pending, whatever its role.
 *
 * @param EditorMode mode The editor mode.
 * @param Selection selection The object selection.
 * @param PuppetModel model The model.
 * @param Set<DeformerId> shownDeformers The deformers shown after the visibility cascade ([visibleDeformerIds]).
 * @param MeshOverlaySizes sizes The dot and line sizes.
 * @param Set<DeformerId> posedIds The warps the Pose-mode session shapes, which its cage draws.
 * @param Set<DeformerId> pendingIds The warps holding an unkeyed Pose-mode shape.
 * @return LatticeOverlay? The lattices, or null when there is none to draw.
 */
internal fun latticeOverlayOf(
	mode: EditorMode,
	selection: Selection,
	model: PuppetModel,
	shownDeformers: Set<DeformerId>,
	sizes: MeshOverlaySizes,
	posedIds: Set<DeformerId> = emptySet(),
	pendingIds: Set<DeformerId> = emptySet(),
): LatticeOverlay? {
	if (mode == EditorMode.Edit) {
		return null
	}

	val roles = LinkedHashMap<DeformerId, LatticeRole>()

	for (deformer in model.deformers) {
		if (deformer !is Deformer.Warp || deformer.id !in shownDeformers || (mode == EditorMode.Pose && deformer.id in posedIds)) {
			continue
		}

		val target = SelectionTarget.Deformer(deformer.id)
		roles[deformer.id] =
			when {
				selection.active == target -> LatticeRole.Active
				target in selection -> LatticeRole.Selected
				else -> LatticeRole.Idle
			}
	}

	if (roles.isEmpty()) {
		return null
	}

	return LatticeOverlay(roles, sizes, pendingIds.filterTo(HashSet()) { deformerId -> deformerId in roles })
}

/**
 * Publishes the lattice overlay to [service] for as long as it runs: derived from the mode, the object
 * selection, the model, the posed and pending warps, and the sizes, and published only when the warps it lists,
 * their roles, their pending marks, or the sizes change - an edit that moves art, or a pose change, publishes
 * nothing.  The shown deformers are walked once per model.
 *
 * @param PuppetViewportService service The render service to publish to.
 * @param EditorSession session The session to derive from.
 * @param Flow<MeshOverlaySizes> sizes The lattice sizes, re-emitted when the density changes.
 */
internal suspend fun publishLatticeOverlay(
	service: PuppetViewportService,
	session: EditorSession,
	sizes: Flow<MeshOverlaySizes>,
) {
	val shownModels = session.model.map { model -> model to model.visibleDeformerIds() }
	val posedAndPending =
		combine(session.meshSelection, session.pendingLatticeEdits) { meshSelection, pending ->
			meshSelection.deformerIds.toHashSet() to pending.keys
		}
	combine(session.mode, session.selection, shownModels, sizes, posedAndPending) { mode, selection, (model, shownDeformers), latticeSizes, (posedIds, pendingIds) ->
		latticeOverlayOf(mode, selection, model, shownDeformers, latticeSizes, posedIds, pendingIds)
	}
		.distinctUntilChanged { previous, next -> sameLatticeOverlay(previous, next) }
		.collect { overlay -> service.setLatticeOverlay(overlay) }
}

/**
 * Whether two lattice overlays draw the same: the same warps, in the same roles, at the same sizes.
 *
 * @param LatticeOverlay? previous The published value.
 * @param LatticeOverlay? next The new one.
 * @return Boolean True when publishing [next] would change nothing.
 */
private fun sameLatticeOverlay(previous: LatticeOverlay?, next: LatticeOverlay?): Boolean {
	if (previous == null || next == null) {
		return previous == null && next == null
	}

	return previous.roles == next.roles && previous.sizes == next.sizes && previous.pendingIds == next.pendingIds
}