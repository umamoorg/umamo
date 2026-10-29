package org.umamo.ui.viewport.viewport2d

import org.umamo.edit.EditorSession
import org.umamo.edit.ElementTopology
import org.umamo.edit.SelectionTarget
import org.umamo.edit.editsElements
import org.umamo.edit.eligibleTransformDrawables
import org.umamo.ui.transform.WorldExtent
import org.umamo.ui.transform.captureDrawableWorlds
import org.umamo.ui.transform.captureEditModeMeshes
import org.umamo.ui.transform.coveredVerticesOf
import org.umamo.ui.transform.coveredWorldExtent
import org.umamo.ui.transform.elementEditStage
import org.umamo.ui.transform.worldExtentOf
import org.umamo.ui.viewport.PuppetViewportService
import org.umamo.ui.viewport.ServiceCameraController

/**
 * The 2D viewport's camera controller for one viewport area.  Frame Selected frames world-space posed
 * geometry: in Edit mode the covered vertices of the session selection at the neutral pose, in Object
 * and Pose mode the selected drawables' whole posed geometry and the selected warps' lattices at the live
 * pose - matching what the viewport shows.
 *
 * @property PuppetViewportService service The render service holding this area's camera.
 * @property EditorSession session The session whose selection / pose Frame Selected reads.
 * @property String areaId The viewport area this controller drives.
 */
internal class ViewportSpaceCamera(
	service: PuppetViewportService,
	session: EditorSession,
	areaId: String,
) : ServiceCameraController(service, session, areaId) {
	override fun frameSelected() {
		val model = session.model.value
		val extent =
			if (session.mode.value.editsElements) {
				// The covered vertices or lattice points of the session selection, at the stage the session edits at:
				// rest in Edit mode, the pose in Pose mode.
				val meshSelection = session.meshSelection.value
				val owners = editOwnerGeometries(model, meshSelection.owners, session.elementEditStage())
				val covered =
					coveredVerticesOf(
						meshSelection,
						owners.map { geometry -> geometry.worldGeometry },
						owners.associate { geometry -> geometry.owner to geometry.topology },
					)
				coveredWorldExtent(covered)
			} else {
				// The selected drawables' whole posed geometry and the selected warps' lattices, at the LIVE
				// pose - what the viewport shows.
				val selection = session.selection.value
				val drawableExtent = eligibleTransformDrawables(selection, model)?.let { eligibleIds -> worldExtentOf(captureDrawableWorlds(model, session.pose.value, eligibleIds)) }
				val selectedDeformers = selection.targets.mapNotNullTo(HashSet()) { target -> (target as? SelectionTarget.Deformer)?.id }
				val latticeExtent =
					service.latticeWorldExtent(selectedDeformers)?.let { bounds ->
						WorldExtent(bounds.minX, bounds.minY, bounds.minX + bounds.width, bounds.minY + bounds.height)
					}
				unionOf(drawableExtent, latticeExtent)
			}

		if (extent == null) {
			return
		}

		service.fitWorldRect(areaId, extent.minX, extent.minZ, extent.maxX, extent.maxZ)
	}
}

/**
 * The smallest box holding both boxes, or the one that exists.
 *
 * @param WorldExtent? first One box, or null.
 * @param WorldExtent? second The other, or null.
 * @return WorldExtent? Their union, or null when neither exists.
 */
private fun unionOf(first: WorldExtent?, second: WorldExtent?): WorldExtent? {
	if (first == null || second == null) {
		return first ?: second
	}

	return WorldExtent(minOf(first.minX, second.minX), minOf(first.minZ, second.minZ), maxOf(first.maxX, second.maxX), maxOf(first.maxZ, second.maxZ))
}