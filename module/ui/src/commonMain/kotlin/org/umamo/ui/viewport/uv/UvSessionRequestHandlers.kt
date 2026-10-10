package org.umamo.ui.viewport.uv

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshChange
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.MeshTopology
import org.umamo.edit.NoticePlacement
import org.umamo.edit.UvCursor
import org.umamo.edit.UvSnapKind
import org.umamo.edit.atlas.placementDragTileIds
import org.umamo.edit.atlas.placementSelectedTileIds
import org.umamo.edit.atlas.setAtlasPlacements
import org.umamo.edit.mesh.commitMeshUvs
import org.umamo.edit.transform.MeshTransforms
import org.umamo.edit.transform.snapToGrid
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.meshOf
import org.umamo.ui.viewport.GridConfig
import org.umamo.ui.viewport.gizmo.GizmoMeshGeometry
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * Executes the UV editor's Shift+S snaps in Edit mode, over the shown surface's texture coordinates (Object
 * mode moves placed art instead - see handleUvObjectSnapRequest): the cursor moves read the UV cursor or the
 * covered median and write the UV cursor directly, and the selection moves transform the covered vertices in
 * the texel display space and commit ONE TransformUvs step (the same commit path a finished modal UV gesture
 * uses).  All math is identity display space - no deformer
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
		UvSnapKind.CursorToPixels, UvSnapKind.CursorToGrid -> snapUvCursor(session, frame, kind, gridStep)

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

/**
 * Runs a UV snap that moves the UV cursor alone and reads no selection: Cursor to Pixels rounds the cursor to
 * the nearest texel corner of the shown surface, Cursor to Grid to the area's grid step, both in display space
 * and both from an unplaced cursor's resting place (UV 0,0).  One rule for the Edit and the Object handler,
 * whose other snaps read different selections; any other kind is not this function's and does nothing.
 *
 * @param EditorSession session The session owning the UV cursor.
 * @param UvEditFrame frame The shown surface's frame, mapping the cursor to display space and back.
 * @param UvSnapKind kind The requested snap.
 * @param Float gridStep The area's grid snap step in display texels.
 */
internal fun snapUvCursor(session: EditorSession, frame: UvEditFrame, kind: UvSnapKind, gridStep: Float) {
	val cursor = session.uvCursor.value ?: UvCursor(0f, 0f)
	val (cursorDisplayX, cursorDisplayY) = frame.displayAt(cursor.u, cursor.v)
	val (snappedX, snappedY) =
		when (kind) {
			UvSnapKind.CursorToPixels -> cursorDisplayX.roundToInt().toFloat() to cursorDisplayY.roundToInt().toFloat()
			UvSnapKind.CursorToGrid -> snapToGrid(cursorDisplayX, 0f, gridStep) to snapToGrid(cursorDisplayY, 0f, gridStep)
			UvSnapKind.CursorToSelected,
			UvSnapKind.SelectionToPixels,
			UvSnapKind.SelectionToCursor,
			UvSnapKind.SelectionToCursorOffset,
			UvSnapKind.SelectionToGrid,
			-> return
		}
	val (cursorU, cursorV) = frame.storedUvAt(snappedX, snappedY)
	session.setUvCursor(cursorU, cursorV)
}

/**
 * Executes the UV editor's Shift+S snaps in Object mode, where the unit of motion is a placed art tile: the
 * selection moves translate tile placements on the shown atlas page, and the cursor moves read them.  A
 * tile's origin is its footprint center (footprintCenterDisplay), the point Individual Origins turns it
 * about, so Cursor to Selected followed by Selection to Cursor moves nothing.
 *
 * - Cursor to Pixels / Grid move the cursor alone (snapUvCursor), over a page or a source layer.
 * - Cursor to Selected puts the cursor on the mean of the selected placed tiles' origins, pinned tiles
 *   included, since it moves none of them.
 * - Selection to Cursor lands each movable tile's origin on the cursor, Blender's pile-up.
 * - Selection to Cursor (Keep Offset) moves every movable tile by one delta, landing the mean of their
 *   origins on the cursor and keeping their layout.
 * - Selection to Grid rounds each origin to the area's grid step in display space, the lines its backdrop
 *   draws (anchored at the page's bottom-left, so the snap runs before the flip into page space).
 * - Selection to Pixels rounds each placement's position, where raster pixel (0,0) lands, to whole page
 *   pixels, so an unscaled, upright tile composes pixel for pixel instead of resampled.
 *
 * The movable tiles are the ones a placement drag would move: the selection's placed, unpinned tiles shown
 * on the page.  With none, the snap answers with the drag's own notices.  Every move commits as ONE placement
 * step, which re-derives the UVs of every drawable over each moved tile; like every snap, it registers no
 * operation-strip entry and runs none of the drag's overlap checks.  The origins need the movers' art
 * decoded, which runs on [computeDispatcher]; a document that changed while it ran drops the snap rather
 * than commit placements read from the old one.
 *
 * @param EditorSession session The session owning the selection, the UV cursor, and the commit.
 * @param UvPlacementSurface? surface The shown atlas page and the source-art store, or null over a source
 *   layer, where only the cursor-only snaps run.
 * @param List<GizmoMeshGeometry> geometries The shown islands' display geometry, which names the tiles shown.
 * @param UvEditFrame frame The shown surface's frame, mapping the UV cursor to display space and back.
 * @param UvSnapKind kind The requested snap.
 * @param GridConfig grid The executing area's grid.
 * @param CoroutineDispatcher computeDispatcher Where the art decode runs, off the UI thread.
 */
internal suspend fun handleUvObjectSnapRequest(
	session: EditorSession,
	surface: UvPlacementSurface?,
	geometries: List<GizmoMeshGeometry>,
	frame: UvEditFrame,
	kind: UvSnapKind,
	grid: GridConfig,
	computeDispatcher: CoroutineDispatcher,
) {
	when (kind) {
		UvSnapKind.CursorToPixels, UvSnapKind.CursorToGrid -> {
			snapUvCursor(session, frame, kind, grid.snapStep)
			return
		}

		UvSnapKind.CursorToSelected,
		UvSnapKind.SelectionToPixels,
		UvSnapKind.SelectionToCursor,
		UvSnapKind.SelectionToCursorOffset,
		UvSnapKind.SelectionToGrid,
		-> Unit
	}
	if (surface == null) {
		session.emitNotice("notice.uv.placement.pageViewOnly", NoticePlacement.NearCursor)
		return
	}
	val model = session.model.value
	if (!model.atlas.storedUvsAddressPages) {
		session.emitNotice("notice.uv.placement.layerAddressed", NoticePlacement.NearCursor)
		return
	}
	val selection = session.selection.value
	// Cursor to Selected only reads, so pinned art counts; a move takes the tiles a drag would move.
	val selectedTileIds = model.placementSelectedTileIds(selection)
	val subjectTileIds = if (kind == UvSnapKind.CursorToSelected) selectedTileIds else model.placementDragTileIds(selection)
	if (subjectTileIds.isEmpty()) {
		// The drag's answers: no placed art under the selection, or placed art that is all pinned.
		session.emitNotice(if (selectedTileIds.isEmpty()) "notice.uv.placement.noPlacedArt" else "notice.uv.placement.pinned", NoticePlacement.NearCursor)
		return
	}
	val shownDrawableIds = geometries.mapTo(HashSet()) { geometry -> geometry.drawableId }
	val shownTileIds = model.drawables.mapNotNullTo(HashSet()) { drawable -> drawable.atlasTileId?.takeIf { drawable.id in shownDrawableIds } }
	val candidateTileIds = subjectTileIds.filter { tileId -> tileId in shownTileIds }
	val origins = if (candidateTileIds.isEmpty()) emptyMap() else withContext(computeDispatcher) { placementFootprintCenters(model, surface, candidateTileIds) }
	if (origins == null) {
		session.emitNotice("notice.uv.placement.notDerivable", NoticePlacement.NearCursor)
		return
	}
	if (origins.isEmpty()) {
		session.emitNotice("notice.uv.placement.notOnPage", NoticePlacement.NearCursor)
		return
	}
	if (session.model.value !== model) {
		return
	}

	val meanX = origins.values.sumOf { origin -> origin.first.toDouble() }.toFloat() / origins.size
	val meanY = origins.values.sumOf { origin -> origin.second.toDouble() }.toFloat() / origins.size
	if (kind == UvSnapKind.CursorToSelected) {
		val (cursorU, cursorV) = frame.storedUvAt(meanX, meanY)
		session.setUvCursor(cursorU, cursorV)
		return
	}
	val cursor = session.uvCursor.value ?: UvCursor(0f, 0f)
	val (cursorDisplayX, cursorDisplayY) = frame.displayAt(cursor.u, cursor.v)
	val gridStep = grid.snapStep
	val changed = LinkedHashMap<AtlasTileId, AtlasPlacement?>()
	for ((tileId, origin) in origins) {
		val placement = model.atlas.tileById[tileId]?.placement ?: continue
		val (originX, originY) = origin
		val moved =
			when (kind) {
				UvSnapKind.SelectionToCursor -> placement.translatedInDisplay(cursorDisplayX - originX, cursorDisplayY - originY)
				UvSnapKind.SelectionToCursorOffset -> placement.translatedInDisplay(cursorDisplayX - meanX, cursorDisplayY - meanY)
				UvSnapKind.SelectionToGrid ->
					placement.translatedInDisplay(snapToGrid(originX, 0f, gridStep) - originX, snapToGrid(originY, 0f, gridStep) - originY)
				UvSnapKind.SelectionToPixels -> placement.copy(positionX = round(placement.positionX), positionY = round(placement.positionY))
				UvSnapKind.CursorToPixels, UvSnapKind.CursorToGrid, UvSnapKind.CursorToSelected -> placement
			}
		if (moved != placement) {
			changed[tileId] = moved
		}
	}
	if (changed.isNotEmpty()) {
		session.setAtlasPlacements(changed, MeshOperatorKind.Grab)
	}
}

/**
 * This placement moved by a display-space delta: display is y up and the page y down, so the page moves by
 * (deltaX, -deltaY).
 *
 * @param Float deltaX The move along display x, in texels.
 * @param Float deltaY The move along display y (up), in texels.
 * @return AtlasPlacement The moved placement.
 */
private fun AtlasPlacement.translatedInDisplay(deltaX: Float, deltaY: Float): AtlasPlacement =
	copy(positionX = positionX + deltaX, positionY = positionY - deltaY)