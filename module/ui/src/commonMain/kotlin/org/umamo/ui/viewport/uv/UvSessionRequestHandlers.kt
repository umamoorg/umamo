package org.umamo.ui.viewport.uv

import org.umamo.edit.EditorSession
import org.umamo.edit.MeshChange
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.MeshTopology
import org.umamo.edit.UvCursor
import org.umamo.edit.UvSnapKind
import org.umamo.edit.mesh.commitMeshUvs
import org.umamo.edit.transform.MeshTransforms
import org.umamo.edit.transform.snapToGrid
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.meshOf
import org.umamo.ui.viewport.GridConfig
import org.umamo.ui.viewport.gizmo.GizmoMeshGeometry
import kotlin.math.roundToInt

/**
 * Executes the UV editor's Shift+S snaps over the shown surface's texture coordinates: the cursor
 * moves read the UV cursor or the covered median and write the UV cursor directly, and the selection
 * moves transform the covered vertices in the texel display space and commit ONE TransformUvs step (the
 * same commit path a finished modal UV gesture uses).  All math is identity display space - no deformer
 * inverse, no movement transfer - since UVs live in one flat space; only the covered vertices of the
 * meshes on the shown surface participate (the overlay only shows one surface at a time, exactly as the
 * modal capture scopes).
 *
 * The grid snaps target the drawn UV grid: its major lines are the executing area's grid scale in texels
 * and the minor lines divide each cell by its subdivisions, anchored at the image origin, so a grid snap
 * rounds display coordinates to the grid's snap step.  The pixel snaps round to the nearest integer texel,
 * which is a pixel corner in this texel-unit space - the artwork-edge-accuracy target.
 *
 * @param EditorSession session The session owning the selection, the UV cursor, and the commit.
 * @param List<GizmoMeshGeometry> geometries The shown meshes' display-space gizmo geometry.
 * @param UvEditFrame frame The shown surface's texel size plus how a coordinate over it reaches the
 *   stored texture coordinates.  Snap-to-pixel therefore targets the SHOWN surface's texel lattice -
 *   an atlas texel over a page, an artwork texel over a source layer - which is the right target in
 *   each, though a placement that scales makes them different lattices.
 * @param UvSnapKind kind The requested snap.
 * @param GridConfig grid The executing area's grid, the scale in texels and the subdivisions its backdrop draws.
 */
internal fun handleUvSnapRequest(
	session: EditorSession,
	geometries: List<GizmoMeshGeometry>,
	frame: UvEditFrame,
	kind: UvSnapKind,
	grid: GridConfig,
) {
	val selection = session.meshSelection.value
	val coveredByMesh =
		geometries.mapNotNull { geometry ->
			val covered = MeshTopology.coveredVertexIndices(selection.elementsOf(geometry.drawableId), geometry.indices)
			if (covered.isEmpty()) null else geometry to covered
		}

	// The UV grid is the area's scale in texels over its subdivisions, anchored at the image origin - so a grid
	// snap rounds to the grid's snap step in display space on both axes, the same minor lines the area's
	// backdrop draws.
	val gridStep = grid.snapStep

	// The UV cursor in display space; an unplaced cursor rests at the stored origin (UV 0,0), the mesh
	// snap's "an unplaced cursor snaps from its resting place" rule in this space.
	val cursor = session.uvCursor.value ?: UvCursor(0f, 0f)
	val (cursorDisplayX, cursorDisplayY) = frame.displayAt(cursor.u, cursor.v)

	// The covered vertices' median in display space, across the shown meshes.
	var coveredSumX = 0f
	var coveredSumY = 0f
	var coveredCount = 0
	for ((geometry, covered) in coveredByMesh) {
		for (vertexIndex in covered) {
			coveredSumX += geometry.positions[vertexIndex * 2]
			coveredSumY += geometry.positions[vertexIndex * 2 + 1]
			coveredCount++
		}
	}

	when (kind) {
		UvSnapKind.CursorToPixels -> {
			val (cursorU, cursorV) =
				frame.storedUvAt(cursorDisplayX.roundToInt().toFloat(), cursorDisplayY.roundToInt().toFloat())
			session.setUvCursor(cursorU, cursorV)
		}

		UvSnapKind.CursorToGrid -> {
			val (cursorU, cursorV) =
				frame.storedUvAt(snapToGrid(cursorDisplayX, 0f, gridStep), snapToGrid(cursorDisplayY, 0f, gridStep))
			session.setUvCursor(cursorU, cursorV)
		}

		UvSnapKind.CursorToSelected -> {
			// Nothing selected on the shown surface: no median to move the cursor to (a silent no-op).
			if (coveredCount == 0) {
				return
			}
			val (cursorU, cursorV) = frame.storedUvAt(coveredSumX / coveredCount, coveredSumY / coveredCount)
			session.setUvCursor(cursorU, cursorV)
		}

		UvSnapKind.SelectionToPixels,
		UvSnapKind.SelectionToCursor,
		UvSnapKind.SelectionToCursorOffset,
		UvSnapKind.SelectionToGrid,
		-> {
			if (coveredCount == 0) {
				return
			}
			val medianX = coveredSumX / coveredCount
			val medianY = coveredSumY / coveredCount
			val model = session.model.value
			val newUvsByDrawable = LinkedHashMap<DrawableId, FloatArray>(coveredByMesh.size)
			val movedIndicesByDrawable = LinkedHashMap<DrawableId, List<Int>>(coveredByMesh.size)
			for ((geometry, covered) in coveredByMesh) {
				val display = geometry.positions
				val transformedDisplay =
					when (kind) {
						// Every covered vertex lands ON the cursor (Blender's pile-up semantics).
						UvSnapKind.SelectionToCursor ->
							MeshTransforms.collapseVertices(display, covered, cursorDisplayX, cursorDisplayY)

						// A rigid translate: the covered median lands on the cursor, offsets kept.
						UvSnapKind.SelectionToCursorOffset ->
							MeshTransforms.translateVertices(display, covered, cursorDisplayX - medianX, cursorDisplayY - medianY)

						// Each covered vertex rounds to its own nearest minor grid line (scale / subdivisions).
						UvSnapKind.SelectionToGrid ->
							display.copyOf().also { positions ->
								for (vertexIndex in covered) {
									positions[vertexIndex * 2] = snapToGrid(positions[vertexIndex * 2], 0f, gridStep)
									positions[vertexIndex * 2 + 1] = snapToGrid(positions[vertexIndex * 2 + 1], 0f, gridStep)
								}
							}

						// Each covered vertex rounds to its nearest pixel corner (an integer texel boundary).
						UvSnapKind.SelectionToPixels ->
							display.copyOf().also { positions ->
								for (vertexIndex in covered) {
									positions[vertexIndex * 2] = positions[vertexIndex * 2].roundToInt().toFloat()
									positions[vertexIndex * 2 + 1] = positions[vertexIndex * 2 + 1].roundToInt().toFloat()
								}
							}

						// The cursor moves were handled above; nothing else reaches here.
						UvSnapKind.CursorToPixels, UvSnapKind.CursorToSelected, UvSnapKind.CursorToGrid -> display
					}
				// Only the covered vertices are written; untouched ones keep their exact stored values
				// (see storedUvsWithMoved).
				val currentUvs = model.meshOf(geometry.drawableId)?.uvs ?: continue
				newUvsByDrawable[geometry.drawableId] = storedUvsWithMoved(currentUvs, covered, transformedDisplay, frame)
				movedIndicesByDrawable[geometry.drawableId] = covered.toList()
			}
			if (newUvsByDrawable.isNotEmpty()) {
				session.commitMeshUvs(MeshChange.TransformUvs(movedIndicesByDrawable, MeshOperatorKind.Grab), newUvsByDrawable)
			}
		}
	}
}