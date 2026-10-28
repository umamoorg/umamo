package org.umamo.ui.viewport.viewport2d

import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.eligibleTransformDrawables
import org.umamo.ui.transform.captureDrawableWorlds
import org.umamo.ui.transform.captureEditModeMeshes
import org.umamo.ui.transform.coveredVerticesOf
import org.umamo.ui.transform.coveredWorldExtent
import org.umamo.ui.transform.worldExtentOf
import org.umamo.ui.viewport.PuppetViewportService
import org.umamo.ui.viewport.ServiceCameraController

/**
 * The 2D viewport's camera controller for one viewport area.  Frame Selected frames world-space posed
 * geometry: in Edit mode the covered vertices of the session selection at the neutral pose, in Object
 * mode the selected drawables' whole posed geometry at the live pose - matching what the viewport shows.
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
			if (session.mode.value == EditorMode.Edit) {
				// The covered vertices of the session selection, at the neutral pose Edit mode is pinned to.
				val meshSelection = session.meshSelection.value
				val meshes = captureEditModeMeshes(model, meshSelection.drawableIds)
				val covered =
					coveredVerticesOf(
						meshSelection,
						meshes.map { captured -> captured.geometry },
						meshes.associate { captured -> captured.geometry.drawableId to captured.mesh.indices },
					)
				coveredWorldExtent(covered)
			} else {
				// The selected drawables' whole posed geometry, at the LIVE pose - what the viewport shows.
				val eligibleIds = eligibleTransformDrawables(session.selection.value, model) ?: return
				worldExtentOf(captureDrawableWorlds(model, session.pose.value, eligibleIds))
			}

		if (extent == null) {
			return
		}

		service.fitWorldRect(areaId, extent.minX, extent.minZ, extent.maxX, extent.maxZ)
	}
}