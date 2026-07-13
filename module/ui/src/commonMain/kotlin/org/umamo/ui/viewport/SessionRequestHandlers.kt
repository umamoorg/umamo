package org.umamo.ui.viewport

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.Cursor2d
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshChange
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.MeshSelectionOps
import org.umamo.edit.MeshTopology
import org.umamo.edit.MeshTopologyOps
import org.umamo.edit.MeshTransforms
import org.umamo.edit.NoticePlacement
import org.umamo.edit.SelectionTarget
import org.umamo.edit.SnapKind
import org.umamo.edit.isPoseNeutral
import org.umamo.edit.selectableOf
import org.umamo.edit.snapToGrid
import org.umamo.render.ViewportCamera
import org.umamo.render.eval.drawableLocalPosed
import org.umamo.render.eval.drawableSpaceMapping
import org.umamo.render.pick.PickCandidate
import org.umamo.runtime.model.DrawableId

/*
 * The bodies of the session-request handlers the gizmo overlays collect: keymap commands that carry
 * no pointer (Select Linked, Alt+Q switch-mesh, Rip, the Shift+S snaps) land on the session's request
 * flows, and only the overlay knows the pointer position and the projected geometry to execute them
 * against.  Each overlay keeps a thin, area-gated collector; the work lives here as plain functions,
 * testable without Compose.
 *
 * キーマップコマンド由来のセッションリクエストの実体。各オーバーレイは薄いコレクタだけを持つ。
 */

/**
 * Select Linked (Blender's L / Ctrl+L): flood the mesh connectivity from the element under the
 * pointer (or from every selected element) and union the islands into the selection in the current
 * domain, as one undo step.  Takes the geometry-source-agnostic gizmo records, so the Edit overlay
 * (world-posed shapes) and the UV editor (display-mapped texture coordinates) share the flood - UV
 * islands ARE topology islands, since UVs share the vertex index space.
 *
 * @param EditorSession session The session owning the selection.
 * @param List<GizmoMeshGeometry> geometries The shown meshes' gizmo geometry.
 * @param Boolean fromSelection True to flood from every selected element (Ctrl+L); false to flood
 *   from the element under the pointer (L).
 * @param Offset pointer The pointer in area-local pixels.
 * @param ViewportCamera camera The area camera.
 * @param IntSize size The area size in pixels.
 */
internal fun handleSelectLinkedRequest(
	session: EditorSession,
	geometries: List<GizmoMeshGeometry>,
	fromSelection: Boolean,
	pointer: Offset,
	camera: ViewportCamera,
	size: IntSize,
) {
	var selection = session.meshSelection.value
	if (fromSelection) {
		for (geometry in geometries) {
			val covered = MeshTopology.coveredVertexIndices(selection.elementsOf(geometry.drawableId), geometry.indices)
			if (covered.isEmpty()) {
				continue
			}
			val adjacency = MeshTopology.buildVertexAdjacency(geometry.positions.size / 2, geometry.indices)
			val reached = HashSet<Int>()
			for (seedVertex in covered) {
				if (seedVertex !in reached) {
					reached.addAll(MeshTopology.connectedVertices(adjacency, seedVertex))
				}
			}
			selection = MeshSelectionOps.selectLinked(selection, geometry.drawableId, reached, geometry.indices)
		}
	} else {
		val hit = hitTestMeshes(selection.selectMode, geometries, pointer, camera, size) ?: return
		val geometry = geometries.firstOrNull { candidate -> candidate.drawableId == hit.drawableId } ?: return
		val seedVertex = MeshTopology.coveredVertexIndices(setOf(hit.element), geometry.indices).firstOrNull() ?: return
		val adjacency = MeshTopology.buildVertexAdjacency(geometry.positions.size / 2, geometry.indices)
		selection =
			MeshSelectionOps.selectLinked(selection, hit.drawableId, MeshTopology.connectedVertices(adjacency, seedVertex), geometry.indices)
	}
	session.setMeshSelection(selection)
}

/**
 * Alt+Q: switch the edited mesh to the drawable under the pointer.  One candidate switches directly;
 * a stack opens the overlap-picker popup (the same picker Object mode's Alt-click uses), so the
 * rigger chooses by name and thumbnail instead of blind-cycling through the layers.
 *
 * @param EditorSession session The session owning the mesh selection.
 * @param PuppetViewportService service The render service (the GPU pick).
 * @param String areaId The requesting overlay's area (the pick target).
 * @param Offset pointer The pointer in area-local pixels.
 * @param Function onOverlapRequest Opens the overlap-picker popup for 2+ stacked candidates.
 */
internal fun handleSwitchEditDrawableRequest(
	session: EditorSession,
	service: PuppetViewportService,
	areaId: String,
	pointer: Offset,
	onOverlapRequest: (Offset, List<PickCandidate>) -> Unit,
) {
	val model = session.model.value
	val candidates =
		service.pickAllAt(areaId, pointer.x, pointer.y)
			.filter { candidate ->
				model.drawables.any { drawable -> drawable.id == candidate.id && drawable.mesh != null } &&
					model.selectableOf(SelectionTarget.Drawable(candidate.id))
			}
	when {
		candidates.isEmpty() -> return
		// The only thing under the cursor is the mesh already being edited: switching to it
		// would wipe the element selection for nothing.
		candidates.size == 1 && candidates.single().id == session.meshSelection.value.activeDrawableId -> return
		candidates.size == 1 -> session.switchEditDrawable(candidates.single().id)
		else -> onOverlapRequest(pointer, candidates)
	}
}

/**
 * Rip (Blender's V): duplicate the covered vertices and re-point the triangles on the pointer's side
 * at the copies, then auto-grab so they pull away under the pointer.  The side rule: a triangle
 * follows the copies when its screen centroid sits nearer the pointer than the covered median does.
 *
 * @param EditorSession session The session owning the mesh selection and topology commits.
 * @param List<EditMeshGeometry> geometries The session meshes' live geometry.
 * @param String areaId The collecting overlay's area id - the rip runs under this viewport's
 *   pointer, so its auto-grab latches with it as the initiating area.
 * @param Offset pointer The pointer in area-local pixels.
 * @param ViewportCamera camera The area camera.
 * @param IntSize size The area size in pixels.
 */
internal fun handleRipRequest(
	session: EditorSession,
	geometries: List<EditMeshGeometry>,
	areaId: String,
	pointer: Offset,
	camera: ViewportCamera,
	size: IntSize,
) {
	val selection = session.meshSelection.value
	val ripDrawableId = selection.activeDrawableId ?: return
	val geometry = geometries.firstOrNull { candidate -> candidate.drawableId == ripDrawableId } ?: return
	val covered = MeshTopology.coveredVertexIndices(selection.elementsOf(ripDrawableId), geometry.mesh.indices)
	if (covered.isEmpty()) {
		return
	}
	var coveredSumX = 0f
	var coveredSumY = 0f
	for (vertexIndex in covered) {
		coveredSumX += geometry.worldPosed[vertexIndex * 2]
		coveredSumY += geometry.worldPosed[vertexIndex * 2 + 1]
	}
	val medianScreen = worldToScreen(coveredSumX / covered.size, coveredSumY / covered.size, camera, size)
	val medianDistance = (medianScreen - pointer).getDistance()
	val result =
		MeshTopologyOps.ripElements(geometry.mesh, covered) { triangleIndex ->
			val cornerA = geometry.mesh.indices[triangleIndex * 3]
			val cornerB = geometry.mesh.indices[triangleIndex * 3 + 1]
			val cornerC = geometry.mesh.indices[triangleIndex * 3 + 2]
			val centroidScreen =
				worldToScreen(
					(geometry.worldPosed[cornerA * 2] + geometry.worldPosed[cornerB * 2] + geometry.worldPosed[cornerC * 2]) / 3f,
					(geometry.worldPosed[cornerA * 2 + 1] + geometry.worldPosed[cornerB * 2 + 1] + geometry.worldPosed[cornerC * 2 + 1]) / 3f,
					camera,
					size,
				)
			(centroidScreen - pointer).getDistance() < medianDistance
		}
	if (result == null) {
		session.emitNotice("notice.rip.nothing", NoticePlacement.NearCursor)
		return
	}
	session.commitMeshTopology("change.mesh.rip", ripDrawableId, result)
	session.beginMeshOperator(MeshOperatorKind.Grab, areaId, suppressProportional = true)
}

/**
 * Executes the geometry-dependent Shift+S snaps for Edit mode: the cursor moves read the covered
 * world median, and the selection moves ride the same movement-transfer pipeline as a finished Grab
 * (one undo step).  An unplaced cursor snaps from the world origin, its conceptual resting place.
 *
 * @param EditorSession session The session owning the selection, cursor, and commits.
 * @param List<EditMeshGeometry> geometries The session meshes' live geometry.
 * @param SnapKind kind The requested snap.
 */
internal fun handleEditSnapRequest(
	session: EditorSession,
	geometries: List<EditMeshGeometry>,
	kind: SnapKind,
) {
	val selection = session.meshSelection.value
	val coveredByMesh =
		geometries.mapNotNull { geometry ->
			val covered = MeshTopology.coveredVertexIndices(selection.elementsOf(geometry.drawableId), geometry.mesh.indices)
			if (covered.isEmpty()) null else geometry to covered
		}
	if (coveredByMesh.isEmpty()) {
		return
	}
	var coveredSumX = 0f
	var coveredSumY = 0f
	var coveredCount = 0
	for ((geometry, covered) in coveredByMesh) {
		for (vertexIndex in covered) {
			coveredSumX += geometry.worldPosed[vertexIndex * 2]
			coveredSumY += geometry.worldPosed[vertexIndex * 2 + 1]
			coveredCount++
		}
	}
	val medianX = coveredSumX / coveredCount
	val medianY = coveredSumY / coveredCount
	// The active element's own median (its covered vertices), or null when nothing is active.
	val active = selection.activeElement
	val activeGeometry = active?.let { candidate -> geometries.firstOrNull { it.drawableId == candidate.drawableId } }
	val activeMedian =
		if (active != null && activeGeometry != null) {
			val activeCovered = MeshTopology.coveredVertexIndices(setOf(active.element), activeGeometry.mesh.indices)
			if (activeCovered.isNotEmpty()) MeshTransforms.medianPivot(activeGeometry.worldPosed, activeCovered) else null
		} else {
			null
		}
	val model = session.model.value
	val cursor = session.cursor2d.value ?: Cursor2d(model.worldOriginX, model.worldOriginY)
	when (kind) {
		SnapKind.CursorToSelected -> session.setCursor2d(medianX, medianY)
		SnapKind.CursorToActive -> {
			// With nothing active the cursor falls back to the whole selection's median.
			val target = activeMedian ?: (medianX to medianY)
			session.setCursor2d(target.first, target.second)
		}
		else -> {
			// Selection to Active with nothing active has no target: a silent no-op (Blender
			// parity), never an empty history step.
			if (kind == SnapKind.SelectionToActive && activeMedian == null) {
				return
			}
			val newPositionsByDrawable = LinkedHashMap<DrawableId, FloatArray>(coveredByMesh.size)
			val movedIndicesByDrawable = LinkedHashMap<DrawableId, List<Int>>(coveredByMesh.size)
			for ((geometry, covered) in coveredByMesh) {
				val world = geometry.worldPosed
				val transformedWorld =
					when (kind) {
						// Every covered vertex lands ON the cursor (Blender's pile-up semantics).
						SnapKind.SelectionToCursor ->
							MeshTransforms.collapseVertices(world, covered, cursor.worldX, cursor.worldY)

						// A rigid translate: the covered median lands on the cursor, offsets kept.
						SnapKind.SelectionToCursorOffset ->
							MeshTransforms.translateVertices(world, covered, cursor.worldX - medianX, cursor.worldY - medianY)

						// Each covered vertex rounds to its own nearest grid point (the finest subdivision).
						SnapKind.SelectionToGrid ->
							world.copyOf().also { positions ->
								val step = session.gridConfig.value.snapStep
								for (vertexIndex in covered) {
									positions[vertexIndex * 2] = snapToGrid(positions[vertexIndex * 2], model.worldOriginX, step)
									positions[vertexIndex * 2 + 1] = snapToGrid(positions[vertexIndex * 2 + 1], model.worldOriginY, step)
								}
							}

						// Every covered vertex lands ON the active element's median - the same
						// pile-up as Selection to Cursor, not a rigid translate (Blender parity).
						SnapKind.SelectionToActive ->
							if (activeMedian != null) {
								MeshTransforms.collapseVertices(world, covered, activeMedian.first, activeMedian.second)
							} else {
								world
							}

						// The cursor moves were handled above; nothing else reaches here.
						SnapKind.CursorToSelected, SnapKind.CursorToActive -> world
					}
				val transformedDisplayed = geometry.mapping.worldToLocalLinearized(transformedWorld, geometry.displayed, world, covered)
				newPositionsByDrawable[geometry.drawableId] = movementToBase(geometry.mesh.positions, transformedDisplayed, geometry.displayed)
				movedIndicesByDrawable[geometry.drawableId] = covered.toList()
			}
			session.commitMeshPositions(MeshChange.MoveVertices(movedIndicesByDrawable), newPositionsByDrawable)
		}
	}
}

/**
 * Executes the geometry-dependent Shift+S snaps for Object mode over the selected drawables'
 * centroids: the cursor moves read them, the selection moves translate whole drawables through the
 * movement-transfer pipeline (one undo step).  The same pose-neutral guard as a transform applies to
 * the selection moves - writing a deformed capture back through the warp inverse corrupts rest meshes.
 *
 * @param EditorSession session The session owning the selection, cursor, and commits.
 * @param SnapKind kind The requested snap.
 */
internal fun handleObjectSnapRequest(session: EditorSession, kind: SnapKind) {
	val model = session.model.value
	val pose = session.pose.value
	val eligibleIds = org.umamo.edit.eligibleTransformDrawables(session.selection.value, model) ?: return
	// Per-drawable posed geometry, the same capture a transform freezes.
	val ids = ArrayList<DrawableId>()
	val displayedList = ArrayList<FloatArray>()
	val worldList = ArrayList<FloatArray>()
	val mappingsList = ArrayList<org.umamo.render.eval.DrawableSpaceMapping>()
	for (drawableId in eligibleIds) {
		val mapping = drawableSpaceMapping(model, pose, drawableId) ?: continue
		val base = model.drawables.firstOrNull { it.id == drawableId }?.mesh?.positions ?: continue
		val displayed = drawableLocalPosed(model, pose, drawableId) ?: base
		ids.add(drawableId)
		mappingsList.add(mapping)
		displayedList.add(displayed)
		worldList.add(mapping.localToWorld(displayed))
	}
	if (ids.isEmpty()) {
		return
	}
	val combined = MeshTransforms.combinedCentroid(worldList)
	val activeId = (session.selection.value.active as? SelectionTarget.Drawable)?.id
	val activeIndex = activeId?.let { candidate -> ids.indexOf(candidate) } ?: -1
	val activeCentroid =
		if (activeIndex >= 0) {
			MeshTransforms.medianPivot(worldList[activeIndex], (0 until worldList[activeIndex].size / 2).toSet())
		} else {
			combined
		}
	val cursor = session.cursor2d.value ?: Cursor2d(model.worldOriginX, model.worldOriginY)
	when (kind) {
		SnapKind.CursorToSelected -> session.setCursor2d(combined.first, combined.second)
		SnapKind.CursorToActive -> session.setCursor2d(activeCentroid.first, activeCentroid.second)
		else -> {
			if (!isPoseNeutral(model, pose)) {
				session.emitNotice("notice.transform.deformed", NoticePlacement.NearCursor)
				return
			}
			val newPositionsByDrawable = LinkedHashMap<DrawableId, FloatArray>(ids.size)
			for (drawableIndex in ids.indices) {
				val world = worldList[drawableIndex]
				val allIndices = (0 until world.size / 2).toSet()
				val ownCentroid = MeshTransforms.medianPivot(world, allIndices)
				val (deltaX, deltaY) =
					when (kind) {
						// Each drawable's centroid lands ON the target (Blender's pile-up semantics)...
						SnapKind.SelectionToCursor -> (cursor.worldX - ownCentroid.first) to (cursor.worldY - ownCentroid.second)
						SnapKind.SelectionToActive -> (activeCentroid.first - ownCentroid.first) to (activeCentroid.second - ownCentroid.second)
						SnapKind.SelectionToGrid ->
							session.gridConfig.value.snapStep.let { step ->
								(snapToGrid(ownCentroid.first, model.worldOriginX, step) - ownCentroid.first) to
									(snapToGrid(ownCentroid.second, model.worldOriginY, step) - ownCentroid.second)
							}

						// ...while Keep Offset translates the whole selection rigidly by one shared delta.
						SnapKind.SelectionToCursorOffset -> (cursor.worldX - combined.first) to (cursor.worldY - combined.second)

						// The cursor moves were handled above; nothing else reaches here.
						SnapKind.CursorToSelected, SnapKind.CursorToActive -> 0f to 0f
					}
				if (deltaX == 0f && deltaY == 0f) {
					continue
				}
				val transformedWorld = MeshTransforms.translateVertices(world, allIndices, deltaX, deltaY)
				val transformedDisplayed =
					mappingsList[drawableIndex].worldToLocalLinearized(transformedWorld, displayedList[drawableIndex], world, allIndices)
				val base = model.drawables.first { it.id == ids[drawableIndex] }.mesh!!.positions
				newPositionsByDrawable[ids[drawableIndex]] = movementToBase(base, transformedDisplayed, displayedList[drawableIndex])
			}
			if (newPositionsByDrawable.isNotEmpty()) {
				session.commitObjectPositions(MeshChange.MoveDrawables(newPositionsByDrawable.keys.toList()), newPositionsByDrawable)
			}
		}
	}
}
