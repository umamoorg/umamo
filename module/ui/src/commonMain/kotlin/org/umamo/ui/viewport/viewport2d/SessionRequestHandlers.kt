package org.umamo.ui.viewport.viewport2d

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshChange
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.MeshTopology
import org.umamo.edit.NoticePlacement
import org.umamo.edit.SelectionTarget
import org.umamo.edit.SnapKind
import org.umamo.edit.eligibleTransformTargets
import org.umamo.edit.mesh.MeshRestPositions
import org.umamo.edit.mesh.MeshTopologyOps
import org.umamo.edit.mesh.commitMeshPositions
import org.umamo.edit.mesh.commitMeshTopology
import org.umamo.edit.mesh.commitObjectPositions
import org.umamo.edit.mesh.commitObjectTransform
import org.umamo.edit.mesh.withMeshPositions
import org.umamo.edit.structure.selectableOf
import org.umamo.edit.switchEditOwner
import org.umamo.edit.transform.MeshTransforms
import org.umamo.edit.transform.beginMeshOperator
import org.umamo.edit.transform.isPoseNeutral
import org.umamo.edit.transform.snapToWorldGrid
import org.umamo.render.ViewportCamera
import org.umamo.render.eval.warpLatticeAt
import org.umamo.render.pick.PickCandidate
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ElementOwner
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.transform.DrawableWorldGeometry
import org.umamo.ui.transform.ElementEditStage
import org.umamo.ui.transform.LatticeWorldGeometry
import org.umamo.ui.transform.captureDrawableWorlds
import org.umamo.ui.transform.commitLatticeLanding
import org.umamo.ui.transform.coveredMedian
import org.umamo.ui.transform.coveredVerticesOf
import org.umamo.ui.transform.withWarpsTransformed
import org.umamo.ui.viewport.PuppetViewportService
import org.umamo.ui.viewport.gizmo.HIT_RADIUS_PX
import org.umamo.ui.viewport.gizmo.activeElementMedian
import org.umamo.ui.viewport.gizmo.worldToScreen

/*
 * The bodies of the session-request handlers the 2D viewport's gizmo overlays collect: keymap commands
 * that carry no pointer (Alt+Q switch-mesh, Rip, the Shift+S snaps) land on the session's request
 * flows, and only the overlay knows the pointer position and the projected geometry to execute them
 * against.  Each overlay keeps a thin, area-gated collector (the Edit overlay's is EditGizmoRequests.kt);
 * the work lives here as plain functions, testable without Compose.  Select Linked serves both surfaces
 * and sits with the shared selection input (gizmo/GizmoSelectionInput.kt); the UV editor's snap is in
 * uv/UvSessionRequestHandlers.kt.
 */

/**
 * Alt+Q: switch the session to the owner under the pointer.  In Edit mode that is the drawable whose art is there:
 * one candidate switches directly, and a stack opens the overlap-picker popup (the same picker Object mode's Alt-click
 * uses), so the rigger chooses by name and thumbnail instead of blind-cycling through the layers.  In Pose mode it is
 * the warp to pose ([poseWarpUnder]).
 *
 * @param EditorSession session The session owning the mesh selection.
 * @param PuppetViewportService service The render service (the GPU pick).
 * @param String areaId The requesting overlay's area (the pick target).
 * @param Offset pointer The pointer in area-local pixels.
 * @param Function onOverlapRequest Opens the overlap-picker popup for 2+ stacked candidates.
 */
internal fun handleSwitchEditOwnerRequest(
	session: EditorSession,
	service: PuppetViewportService,
	areaId: String,
	pointer: Offset,
	onOverlapRequest: (Offset, List<PickCandidate>) -> Unit,
) {
	if (session.mode.value == EditorMode.Pose) {
		poseWarpUnder(session, service, areaId, pointer)?.let { warpId -> session.switchEditOwner(ElementOwner.Deformer(warpId)) }

		return
	}

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

		candidates.size == 1 -> session.switchEditOwner(ElementOwner.Drawable(candidates.single().id))

		else -> onOverlapRequest(pointer, candidates)
	}
}

/**
 * The warp Alt+Q poses at [pointer] in Pose mode: the first selectable warp whose drawn lattice is under it (the
 * posed warps draw as the session's cage, so these are the others), else the nearest warp up the deformer chain of
 * the front-most art there that the pose draws - so pointing at art poses the lattice that moves it.
 *
 * @param EditorSession session The session.
 * @param PuppetViewportService service The render service (the lattice and art picks).
 * @param String areaId The requesting area.
 * @param Offset pointer The pointer in area-local pixels.
 * @return DeformerId? The warp, or null when there is none.
 */
private fun poseWarpUnder(
	session: EditorSession,
	service: PuppetViewportService,
	areaId: String,
	pointer: Offset,
): DeformerId? {
	val model = session.model.value
	val lattice =
		service.latticePicksAt(areaId, pointer.x, pointer.y, HIT_RADIUS_PX, LATTICE_LINE_HIT_RADIUS_PX)
			.firstOrNull { deformerId -> model.selectableOf(SelectionTarget.Deformer(deformerId)) }

	if (lattice != null) {
		return lattice
	}

	val drawableId = service.pickAt(areaId, pointer.x, pointer.y) ?: return null
	val deformerById = model.deformers.associateBy { deformer -> deformer.id }
	var parentId = model.drawables.firstOrNull { drawable -> drawable.id == drawableId }?.parentDeformerId
	val seen = HashSet<DeformerId>()

	while (parentId != null && seen.add(parentId)) {
		val deformer = deformerById[parentId] ?: return null

		if (deformer is Deformer.Warp && warpLatticeAt(model, session.pose.value, deformer.id, session.pendingLatticeEdits.value) != null) {
			return deformer.id
		}

		parentId = deformer.parent
	}

	return null
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
	val covered = MeshTopology.coveredVertexIndices(selection.elementsOf(ripDrawableId), geometry.topology)

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
 * Executes the geometry-dependent Shift+S snaps for Edit and Pose mode: the cursor moves read the covered
 * world median, and the selection moves ride the same pipeline as a finished Grab (one undo step) - the
 * movement transfer for a mesh, the lattice's landing at [stage] for a lattice.  An unplaced cursor snaps from the
 * world origin, its conceptual resting place.  The grid snap rounds to the executing area's own grid, the lines that
 * area draws.
 *
 * @param EditorSession session The session owning the selection, cursor, and commits.
 * @param List<EditOwnerGeometry> geometries The session owners' live geometry.
 * @param ElementEditStage stage Where [geometries] were captured.
 * @param SnapKind kind The requested snap.
 * @param Float gridStep The executing area's grid snap increment in world units.
 */
internal fun handleEditSnapRequest(
	session: EditorSession,
	geometries: List<EditOwnerGeometry>,
	stage: ElementEditStage,
	kind: SnapKind,
	gridStep: Float,
) {
	val selection = session.meshSelection.value
	// The covered set and median the sidebar's Vertex / Median panel reads too, so the two cannot disagree.
	val coveredByMesh =
		coveredVerticesOf(
			selection,
			geometries.map { geometry -> geometry.worldGeometry },
			geometries.associate { geometry -> geometry.owner to geometry.topology },
		)
	val median = coveredMedian(coveredByMesh) ?: return
	val medianX = median.worldX
	val medianY = median.worldZ
	// The active element's own median (its covered vertices), or null when nothing is active.
	val activeMedian = activeElementMedian(selection, geometries.map { geometry -> geometry.gizmo })
	val model = session.model.value
	val cursor = session.cursor2dOrWorldOrigin()

	when (kind) {
		SnapKind.CursorToSelected -> {
			session.setCursor2d(medianX, medianY)
		}

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

			val restByDrawable = LinkedHashMap<DrawableId, MeshRestPositions>(coveredByMesh.size)
			val movedIndicesByDrawable = LinkedHashMap<DrawableId, List<Int>>(coveredByMesh.size)
			val latticeWorld = LinkedHashMap<DeformerId, FloatArray>()
			val latticeMoved = LinkedHashMap<DeformerId, Set<Int>>()

			for (coveredMesh in coveredByMesh) {
				val geometry = coveredMesh.geometry
				val covered = coveredMesh.indices
				val world = geometry.world
				val transformedWorld =
					when (kind) {
						// Every covered vertex lands ON the cursor (Blender's pile-up semantics).
						SnapKind.SelectionToCursor -> {
							MeshTransforms.collapseVertices(world, covered, cursor.worldX, cursor.worldZ)
						}

						// A rigid translate: the covered median lands on the cursor, offsets kept.
						SnapKind.SelectionToCursorOffset -> {
							MeshTransforms.translateVertices(world, covered, cursor.worldX - medianX, cursor.worldZ - medianY)
						}

						// Each covered vertex rounds to its own nearest grid point (the finest subdivision).
						SnapKind.SelectionToGrid -> {
							world.copyOf().also { positions ->
								for (vertexIndex in covered) {
									val (snappedX, snappedZ) = model.snapToWorldGrid(positions[vertexIndex * 2], positions[vertexIndex * 2 + 1], gridStep)
									positions[vertexIndex * 2] = snappedX
									positions[vertexIndex * 2 + 1] = snappedZ
								}
							}
						}

						// Every covered vertex lands ON the active element's median - the same
						// pile-up as Selection to Cursor, not a rigid translate (Blender parity).
						SnapKind.SelectionToActive -> {
							if (activeMedian != null) {
								MeshTransforms.collapseVertices(world, covered, activeMedian.first, activeMedian.second)
							} else {
								world
							}
						}

						// The cursor moves were handled above; nothing else reaches here.
						SnapKind.CursorToSelected, SnapKind.CursorToActive -> {
							world
						}
					}

				when (geometry) {
					is DrawableWorldGeometry -> {
						restByDrawable[geometry.drawableId] = geometry.worldToRest(transformedWorld, covered)
						movedIndicesByDrawable[geometry.drawableId] = covered.toList()
					}

					is LatticeWorldGeometry -> {
						latticeWorld[geometry.deformerId] = transformedWorld
						latticeMoved[geometry.deformerId] = covered
					}
				}
			}

			// A snap relocates geometry without scaling or turning it, so it files under the move label; its lattices
			// land at the stage they were captured at, as a finished Grab's do.
			val landing = MeshDriveResult(restByDrawable, latticeWorld, model.withMeshPositions(restByDrawable), latticeMoved).latticeLanding(stage)

			if (landing != null) {
				session.commitLatticeLanding(landing, MeshOperatorKind.Grab)
			} else {
				session.commitMeshPositions(
					MeshChange.TransformVertices(movedIndicesByDrawable, MeshOperatorKind.Grab),
					restByDrawable,
				)
			}
		}
	}
}

/**
 * Executes the geometry-dependent Shift+S snaps for Object mode over the centroids of the items an Object-mode
 * transform moves ([eligibleTransformTargets]): a drawable's vertex mean, a warp's lattice point mean.  The cursor
 * moves read them; the selection moves translate whole drawables through the movement-transfer pipeline and whole
 * warps as objects, their art following (one undo step).  The same pose-neutral guard as a transform applies to the
 * selection moves - writing a deformed capture back through the warp inverse corrupts rest meshes.  The grid snap
 * rounds to the executing area's own grid, the lines that area draws.
 *
 * @param EditorSession session The session owning the selection, cursor, and commits.
 * @param SnapKind kind The requested snap.
 * @param Float gridStep The executing area's grid snap increment in world units.
 */
internal fun handleObjectSnapRequest(session: EditorSession, kind: SnapKind, gridStep: Float) {
	val model = session.model.value
	val pose = session.pose.value
	val targets = eligibleTransformTargets(session.selection.value, model) ?: return
	// Per-drawable posed geometry, the same batched capture a transform freezes, and each warp's lattice as the
	// pose draws it.  An item with a hidden ancestor is dropped - the rest still snap.
	val geometries = captureDrawableWorlds(model, pose, targets.drawableIds)
	val lattices = targets.warpIds.mapNotNull { warpId -> warpLatticeAt(model, pose, warpId)?.let { lattice -> warpId to lattice.world } }

	if (geometries.isEmpty() && lattices.isEmpty()) {
		return
	}

	val worldList = geometries.map { geometry -> geometry.world } + lattices.map { (_, world) -> world }
	val combined = MeshTransforms.combinedCentroid(worldList)
	val activeWorld =
		when (val active = session.selection.value.active) {
			is SelectionTarget.Drawable -> geometries.firstOrNull { geometry -> geometry.drawableId == active.id }?.world
			is SelectionTarget.Deformer -> lattices.firstOrNull { (warpId, _) -> warpId == active.id }?.second
			else -> null
		}
	val activeCentroid = activeWorld?.let { world -> MeshTransforms.medianPivot(world, (0 until world.size / 2).toSet()) } ?: combined
	val cursor = session.cursor2dOrWorldOrigin()

	when (kind) {
		SnapKind.CursorToSelected -> {
			session.setCursor2d(combined.first, combined.second)
		}

		SnapKind.CursorToActive -> {
			session.setCursor2d(activeCentroid.first, activeCentroid.second)
		}

		else -> {
			if (!isPoseNeutral(model, pose)) {
				session.emitNotice("notice.transform.deformed", NoticePlacement.NearCursor)

				return
			}

			val snap = ObjectSnap(kind, model, gridStep, cursor.worldX to cursor.worldZ, activeCentroid, combined)
			val restByDrawable = LinkedHashMap<DrawableId, MeshRestPositions>(geometries.size)

			for (geometry in geometries) {
				val world = geometry.world
				val allIndices = geometry.allIndices
				val (deltaX, deltaY) = snap.deltaFrom(MeshTransforms.medianPivot(world, allIndices))

				if (deltaX == 0f && deltaY == 0f) {
					continue
				}

				val transformedWorld = MeshTransforms.translateVertices(world, allIndices, deltaX, deltaY)
				restByDrawable[geometry.drawableId] = geometry.worldToRest(transformedWorld, allIndices)
			}

			val warpAffines = LinkedHashMap<DeformerId, FloatArray>(lattices.size)

			for ((warpId, world) in lattices) {
				val (deltaX, deltaY) = snap.deltaFrom(MeshTransforms.medianPivot(world, (0 until world.size / 2).toSet()))

				if (deltaX != 0f || deltaY != 0f) {
					warpAffines[warpId] = floatArrayOf(1f, 0f, deltaX, 0f, 1f, deltaY)
				}
			}

			if (warpAffines.isNotEmpty()) {
				val landed = model.withMeshPositions(restByDrawable).withWarpsTransformed(warpAffines)
				session.commitObjectTransform(MeshChange.TransformObjects(restByDrawable.keys.toList(), MeshOperatorKind.Grab, warpAffines.keys.toList()), landed)
			} else if (restByDrawable.isNotEmpty()) {
				session.commitObjectPositions(
					MeshChange.TransformObjects(restByDrawable.keys.toList(), MeshOperatorKind.Grab),
					restByDrawable,
				)
			}
		}
	}
}

/**
 * One Object-mode selection snap's targets, which every item's translation is measured to.
 *
 * @property SnapKind kind The requested selection snap.
 * @property PuppetModel model The model, whose grid a grid snap rounds to.
 * @property Float gridStep The executing area's grid snap increment in world units.
 * @property Pair<Float, Float> cursor The 2D cursor in world space.
 * @property Pair<Float, Float> activeCentroid The active item's centroid, or the combined one with nothing active.
 * @property Pair<Float, Float> combined The selection's combined centroid.
 */
private class ObjectSnap(
	val kind: SnapKind,
	val model: PuppetModel,
	val gridStep: Float,
	val cursor: Pair<Float, Float>,
	val activeCentroid: Pair<Float, Float>,
	val combined: Pair<Float, Float>,
) {
	/**
	 * The translation the snap moves one item by, from its own centroid.
	 *
	 * @param Pair<Float, Float> ownCentroid The item's centroid.
	 * @return Pair<Float, Float> The x and z move.
	 */
	fun deltaFrom(ownCentroid: Pair<Float, Float>): Pair<Float, Float> =
		when (kind) {
			// Each item's centroid lands ON the target (Blender's pile-up semantics)...
			SnapKind.SelectionToCursor -> {
				(cursor.first - ownCentroid.first) to (cursor.second - ownCentroid.second)
			}

			SnapKind.SelectionToActive -> {
				(activeCentroid.first - ownCentroid.first) to (activeCentroid.second - ownCentroid.second)
			}

			SnapKind.SelectionToGrid -> {
				model.snapToWorldGrid(ownCentroid.first, ownCentroid.second, gridStep).let { (snappedX, snappedZ) ->
					(snappedX - ownCentroid.first) to (snappedZ - ownCentroid.second)
				}
			}

			// ...while Keep Offset translates the whole selection rigidly by one shared delta.
			SnapKind.SelectionToCursorOffset -> {
				(cursor.first - combined.first) to (cursor.second - combined.second)
			}

			// The cursor moves never reach a selection snap.
			SnapKind.CursorToSelected, SnapKind.CursorToActive -> {
				0f to 0f
			}
		}
}