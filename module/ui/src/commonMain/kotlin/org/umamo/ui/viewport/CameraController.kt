package org.umamo.ui.viewport

import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshTopology
import org.umamo.edit.eligibleTransformDrawables
import org.umamo.render.eval.drawableLocalPosed
import org.umamo.render.eval.drawableSpaceMapping

/**
 * The camera operations one editor area exposes to the shell's view commands (Fit / 1:1 / zoom / Zoom
 * Region / Frame Selected).  Both the 2D viewport and the UV editor host camera-bearing areas, and each
 * registers one of these into the AreaCameraHub for its area's lifetime; the shell's view commands
 * resolve the hovered area's controller through the hub at dispatch time.  A new camera-bearing space
 * reuses this by registering its own implementation - never by adding a branch to the view commands.
 *
 * ビューコマンド（フィット／等倍／ズーム／ズーム領域／選択をフレーム）へエリアが公開するカメラ操作。
 * 2D ビューポートと UV エディタが各エリアの分を登録し、コマンドはホバー中のエリアの分を解決する。
 */
internal interface CameraController {
	/** Frames the area's content to fit. */
	fun fit()

	/** Sets true 1:1 (one content unit per screen pixel). */
	fun actualSize()

	/**
	 * Zooms in one step about the view centre.
	 *
	 * @param Boolean coarse Use the larger (Shift) step.
	 */
	fun zoomIn(coarse: Boolean)

	/**
	 * Zooms out one step about the view centre.
	 *
	 * @param Boolean coarse Use the larger (Shift) step.
	 */
	fun zoomOut(coarse: Boolean)

	/**
	 * Arms the drag-a-box-to-frame Zoom Region gesture over this area.  The area's
	 * mounted region overlay captures the drag and frames the box on release.
	 */
	fun armZoomRegion()

	/** Frames the selection's covered bounds; a no-op with nothing covered. */
	fun frameSelected()
}

/**
 * A [CameraController] backed by the shared [PuppetViewportService], bound to one fixed area.  The five
 * navigation ops are identical across every camera-bearing space - they only differ in which area they
 * target and in how Frame Selected computes its bounds - so this hoists them here against the fixed
 * [areaId], leaving [frameSelected] to each concrete space.
 *
 * @property PuppetViewportService service The render service holding this area's per-area camera.
 * @property EditorSession session The session, for the Zoom Region arming flag and Frame Selected bounds.
 * @property String areaId The area this controller drives (the same id its space registered under).
 */
internal abstract class ServiceCameraController(
	protected val service: PuppetViewportService,
	protected val session: EditorSession,
	protected val areaId: String,
) : CameraController {
	override fun fit() {
		service.fit(areaId)
	}

	override fun actualSize() {
		service.actualSize(areaId)
	}

	override fun zoomIn(coarse: Boolean) {
		service.zoomCentered(areaId, zoomIn = true, coarse = coarse)
	}

	override fun zoomOut(coarse: Boolean) {
		service.zoomCentered(areaId, zoomIn = false, coarse = coarse)
	}

	override fun armZoomRegion() {
		// Arms the session flag for this area; the area's top-level region overlay captures the drag and
		// calls service.zoomToRegion on release.  Works in Object and Edit mode alike.
		session.armZoomRegion(areaId)
	}
}

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
				val world = mapping.localToWorld(drawableLocalPosed(model, emptyMap(), drawableId) ?: mesh.positions)
				include(world, covered)
			}
		} else {
			// The selected drawables' whole posed geometry, at the LIVE pose - what the viewport shows.
			val pose = session.pose.value
			val eligibleIds = eligibleTransformDrawables(session.selection.value, model) ?: return
			for (drawableId in eligibleIds) {
				val mesh = model.drawables.firstOrNull { it.id == drawableId }?.mesh ?: continue
				val mapping = drawableSpaceMapping(model, pose, drawableId) ?: continue
				val world = mapping.localToWorld(drawableLocalPosed(model, pose, drawableId) ?: mesh.positions)
				include(world, 0 until world.size / 2)
			}
		}
		if (minX > maxX) {
			return
		}
		service.fitWorldRect(areaId, minX, minY, maxX, maxY)
	}
}

/**
 * The UV editor's camera controller for one UV atlas-page area.  Frame Selected frames the covered UV
 * bounds in the editor's display space: in Edit mode the covered vertices of the mesh selection, in
 * Object mode every vertex of the shown geometries (the selected drawables).  A one-texel floor keeps a
 * single-vertex frame from exploding the zoom to its clamp.
 *
 * @property PuppetViewportService service The render service holding this area's camera.
 * @property EditorSession session The session whose mode / mesh selection Frame Selected reads.
 * @property String areaId The UV editor area this controller drives.
 * @property Function geometries The live shown UV geometries in display space, re-read on each call.
 */
internal class UvSpaceCamera(
	service: PuppetViewportService,
	session: EditorSession,
	areaId: String,
	private val geometries: () -> List<GizmoMeshGeometry>,
) : ServiceCameraController(service, session, areaId) {
	override fun frameSelected() {
		// Mirror the 2D viewport's mode split (ViewportSpaceCamera.frameSelected): Edit mode frames the
		// covered (selected) vertices; Object mode has no element selection, so it frames every vertex of
		// the shown geometries - which in Object mode are exactly the selected drawables.  Without this
		// branch Object-mode Frame Selected is a no-op, because meshSelection is empty there and
		// coveredVertexIndices returns nothing.
		val editMode = session.mode.value == EditorMode.Edit
		val selection = session.meshSelection.value
		var minX = Float.MAX_VALUE
		var minY = Float.MAX_VALUE
		var maxX = -Float.MAX_VALUE
		var maxY = -Float.MAX_VALUE
		for (geometry in geometries()) {
			val vertexIndices: Iterable<Int> =
				if (editMode) {
					MeshTopology.coveredVertexIndices(selection.elementsOf(geometry.drawableId), geometry.indices)
				} else {
					0 until geometry.positions.size / 2
				}
			for (vertexIndex in vertexIndices) {
				minX = minOf(minX, geometry.positions[vertexIndex * 2])
				maxX = maxOf(maxX, geometry.positions[vertexIndex * 2])
				minY = minOf(minY, geometry.positions[vertexIndex * 2 + 1])
				maxY = maxOf(maxY, geometry.positions[vertexIndex * 2 + 1])
			}
		}
		if (minX > maxX) {
			return
		}
		// A one-texel floor keeps a single-vertex frame from exploding the zoom to its clamp.
		service.fitWorldRect(areaId, minX, minY, maxOf(maxX, minX + 1f), maxOf(maxY, minY + 1f))
	}
}
