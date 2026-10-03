package org.umamo.ui.viewport.viewport2d

import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshTopology
import org.umamo.edit.eligibleTransformDrawables
import org.umamo.render.eval.drawableLocalPosed
import org.umamo.render.eval.drawableSpaceMapping
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
		var minX = Float.MAX_VALUE
		var minY = Float.MAX_VALUE
		var maxX = -Float.MAX_VALUE
		var maxY = -Float.MAX_VALUE

		fun include(worldPositions: FloatArray, vertexIndices: Iterable<Int>) {
			for (vertexIndex in vertexIndices) {
				minX = minOf(minX, worldPositions[vertexIndex * 2])
				maxX = maxOf(maxX, worldPositions[vertexIndex * 2])
				minY = minOf(minY, worldPositions[vertexIndex * 2 + 1])
				maxY = maxOf(maxY, worldPositions[vertexIndex * 2 + 1])
			}
		}
		if (session.mode.value == EditorMode.Edit) {
			// The covered vertices of the session selection, at the neutral pose Edit mode is pinned to.
			val meshSelection = session.meshSelection.value
			for (drawableId in meshSelection.drawableIds) {
				val elements = meshSelection.elementsOf(drawableId)
				if (elements.isEmpty()) {
					continue
				}
				val mesh = model.drawables.firstOrNull { it.id == drawableId }?.mesh ?: continue
				val covered = MeshTopology.coveredVertexIndices(elements, mesh.indices)
				if (covered.isEmpty()) {
					continue
				}
				val mapping = drawableSpaceMapping(model, emptyMap(), drawableId) ?: continue
				val world = mapping.localToWorld(drawableLocalPosed(model, emptyMap(), drawableId) ?: mesh.localPositions)
				include(world, covered)
			}
		} else {
			// The selected drawables' whole posed geometry, at the LIVE pose - what the viewport shows.
			val pose = session.pose.value
			val eligibleIds = eligibleTransformDrawables(session.selection.value, model) ?: return
			for (drawableId in eligibleIds) {
				val mesh = model.drawables.firstOrNull { it.id == drawableId }?.mesh ?: continue
				val mapping = drawableSpaceMapping(model, pose, drawableId) ?: continue
				val world = mapping.localToWorld(drawableLocalPosed(model, pose, drawableId) ?: mesh.localPositions)
				include(world, 0 until world.size / 2)
			}
		}
		if (minX > maxX) {
			return
		}
		service.fitWorldRect(areaId, minX, minY, maxX, maxY)
	}
}