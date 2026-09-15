package org.umamo.ui.viewport

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshChange
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.ModalTransformCapture
import org.umamo.edit.NoticePlacement
import org.umamo.edit.PlacementGestureRefusal
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.edit.placementGestureRefusal
import org.umamo.edit.selectableOf
import org.umamo.edit.setAtlasPlacements
import org.umamo.edit.withMeshUvs
import org.umamo.format.art.AlphaContour
import org.umamo.format.art.LayerBounds
import org.umamo.render.ViewportCamera
import org.umamo.render.pick.PickCandidate
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.applyUvAffine
import org.umamo.ui.model.LocalPuppetRenderSync
import org.umamo.ui.model.LocalSessionAtlasPages
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoCursors
import org.umamo.ui.theme.drawRubberBand
import org.umamo.ui.theme.hiddenPointerIcon
import org.umamo.ui.theme.selectionOverlayStyle

/**
 * One tile crop drawn at a committed placement while the page resolver is still composing the pixels
 * for that commit.
 *
 * @property ImageBitmap    crop      The trim's pixels.
 * @property LayerBounds    trim      The trim the crop covers, raster-local.
 * @property AtlasPlacement placement Where the tile was committed to.
 */
private class GhostCrop(
	val crop: ImageBitmap,
	val trim: LayerBounds,
	val placement: AtlasPlacement,
)

/**
 * A committed placement move's crops, drawn at their new spots until the resolver publishes pages for
 * the committed atlas - so the islands never sit over the OLD pixels for the derivation's duration.
 *
 * @property PuppetAtlas atlas      The atlas instance the commit published (the resolver keys by identity).
 * @property Int         pageHeight The page height, for the display flip.
 * @property List        crops      The crops and where they were committed.
 */
private class PlacementGhost(
	val atlas: PuppetAtlas,
	val pageHeight: Int,
	val crops: List<GhostCrop>,
)

/**
 * What a UV editor's Object overlay is drawn over, which decides what its G / S / R move: the art on
 * a page, or the mapping over a layer.  Handed in by the host rather than derived from the frame,
 * because the frame of an unpacked layer is the page view's own identity frame and cannot tell the
 * two apart.
 */
internal sealed interface UvObjectSurface {
	/** A source layer: the art is the frame, so a gesture moves the shown islands' mappings over it. */
	data object SourceLayer : UvObjectSurface

	/**
	 * An atlas page: a gesture moves placements, through the page and the source art the pages
	 * recompose from.
	 *
	 * @property UvPlacementSurface? placement The shown page and the art store, or null when the
	 *   document retains no source art - the gesture then refuses, since the pages could not be
	 *   recomposed after a move.
	 */
	class AtlasPage(val placement: UvPlacementSurface?) : UvObjectSurface
}

/**
 * The capture of an in-flight Object-mode gesture, one shape per surface.  The two share the pointer
 * loop, the modal HUD, the wireframe preview, and the latch lifecycle; they differ in what a pointer
 * frame evaluates and what the confirm commits, so each branch of the overlay dispatches on this.
 *
 * @property ModalTransformCapture transform The shared capture: the HUD pivot, the rotation tracker,
 *   and the operator that latched.
 */
private sealed interface UvObjectGesture {
	val transform: ModalTransformCapture

	/**
	 * Over an atlas page: the art and every island over it move together (UvPlacementGesture.kt).
	 *
	 * @property PlacementGesture placement The frozen placement gesture.
	 */
	class Placement(val placement: PlacementGesture) : UvObjectGesture {
		override val transform: ModalTransformCapture get() = placement.transform
	}

	/**
	 * Over a source layer: every selected island's whole mapping moves over the fixed art
	 * (UvMappingGesture.kt).
	 *
	 * @property UvGesture gesture The frozen mapping gesture.
	 */
	class Mapping(val gesture: UvGesture) : UvObjectGesture {
		override val transform: ModalTransformCapture get() = gesture.transform
	}
}

/**
 * The UV editor's Object-mode gizmo overlay, the mode-exclusive sibling of [UvEditGizmoOverlay]
 * (each one self-gates on the session's mode, the viewport overlay pair's convention): every
 * visible island on the shown surface draws in the Blender object-overlay style - unselected islands
 * dim (the idle palette), selected islands highlighted, the active island's outline emphasized -
 * and the islands are click targets writing the ONE session object selection, so a selection made
 * here flows out to the viewport and the outliner.
 *
 * The interaction vocabulary is the viewport Object gizmo's, through the same
 * [ObjectPickController]: a sub-threshold primary click picks the front-most opaque island under
 * the cursor (plain replaces, Shift / Ctrl toggles membership, an unmodified click on empty canvas
 * clears - under Follow Selection that may hop the shown page to the first-meshed fallback, while a
 * pinned page holds), an Alt click resolves the overlap stack through the host's popup, a primary
 * drag box-selects every island with a vertex inside the box (Shift adds), and Shift+RightClick
 * places the UV cursor.
 * Picking is CPU-side over [islandPick] - display-space point-in-face with the shown image's alpha
 * gate, so a click on transparent triangle overhang falls through, exactly like the viewport's raster
 * pick.  The same overlay serves both surfaces: over a source layer it gates on that artwork's own
 * alpha and places the cursor through the layer's frame, which is the whole of the difference.
 *
 * The overlay also owns the Object-mode transform gesture - a UV operator latched in this area (G /
 * S / R) - and what it moves is the SURFACE's call, since the two surfaces hold different things
 * fixed.  Over an ATLAS PAGE it is the placement gesture: the selected drawables' ART moves on the
 * page - each tile's pixels and every drawable over it together, so the vertex-to-art mapping never
 * changes.  The capture freezes the movers (their placements, trims, mesh reserves, and crops), the
 * page's bystanders, and the moving islands' display positions; each pointer frame evaluates the
 * placements through the shared operator parameters (UvPlacementGesture.kt), previews the crops at
 * their new spots and the islands translated with them, and outlines any footprint that collides or
 * spills off the page; confirm commits ONE undo step through setAtlasPlacements and the session's page
 * resolver recomposes the pixels.  Nothing is pushed to the puppet renderer during the drag: a
 * placement move is invisible in the 2D viewport by construction.
 *
 * Over a SOURCE LAYER the art cannot move within its own frame, so the object that moves is the
 * mesh: the mapping gesture takes every vertex of each selected island shown on the layer as one
 * object (UvMappingGesture.kt) and slides it over the fixed art, exactly the 2D viewport's Object-mode
 * transform read over texture coordinates.  Each pointer frame runs the shared operator math over the
 * frozen display coordinates and streams the re-derived stored coordinates to the puppet renderer,
 * since a mapping move IS visible in the 2D viewport; confirm converts back through the layer's
 * frame and commits ONE TransformUvs step, registered on the operation settings strip like the Edit
 * overlay's transform.  Only primary-driven events are consumed while idle; pan / zoom and the plain
 * right-click (the context menu) fall through, and a modal gesture owns the pointer.
 *
 * Posed from the FRAME camera so it lags with the GL image during pan / zoom, and unclipped to the
 * image tile by design, so a mapping reaching past it stays visible - which is normal, since a mesh
 * rings outside the art it samples (the hosting area still clips to its own bounds).
 *
 * @param String areaId The UV editor area this overlay covers (keys the pointer loop).
 * @param EditorSession session The session owning the object selection, the model, and the latches.
 * @param List<GizmoMeshGeometry> geometries The shown islands' display-space gizmo geometry.
 * @param UvIslandPick islandPick The shown surface's island picker (point pick, stack query, front ranks).
 * @param UvEditFrame frame The shown surface's texel size plus how a coordinate over it reaches the
 *   stored texture coordinates (an atlas page is the stored frame itself; a source layer is not).
 * @param ViewportCamera? camera The displayed frame's camera; null hides the overlay (no frame yet).
 * @param Int widthPx The area width in pixels.
 * @param Int heightPx The area height in pixels.
 * @param UvObjectSurface surface What the area shows - an atlas page (with its source-art store) or a
 *   source layer - which decides whether a latched operator moves placements or mappings.
 * @param MutableState<PlacementDragStatus?> placementDragStatusState The host-owned drag readout
 *   this overlay writes per pointer frame and the host's UvHudOverlay badge reads.
 * @param Function onOverlapRequest Opens the host's overlap picker for an Alt click with 2+ candidates.
 * @param Modifier modifier The layout modifier.
 */
@Composable
internal fun UvObjectGizmoOverlay(
	areaId: String,
	session: EditorSession,
	geometries: List<GizmoMeshGeometry>,
	islandPick: UvIslandPick,
	frame: UvEditFrame,
	camera: ViewportCamera?,
	widthPx: Int,
	heightPx: Int,
	surface: UvObjectSurface,
	placementDragStatusState: MutableState<PlacementDragStatus?>,
	onOverlapRequest: (Offset, List<PickCandidate>) -> Unit,
	modifier: Modifier = Modifier,
) {
	val mode by session.mode.collectAsState()
	if (mode == EditorMode.Edit || camera == null) {
		return
	}

	val meshSelection by session.meshSelection.collectAsState()
	val objectSelection by session.selection.collectAsState()
	val activeOperator by session.activeUvOperator.collectAsState()
	val axisConstraint by session.axisConstraint.collectAsState()
	val committedModel by session.model.collectAsState()
	val tileByDrawableId = remember(committedModel) { committedModel.drawables.mapNotNull { drawable -> drawable.atlasTileId?.let { tileId -> drawable.id to tileId } }.toMap() }
	val pinnedTileIds = remember(committedModel) { committedModel.atlas.tiles.filter { tile -> tile.pinned }.mapTo(HashSet()) { tile -> tile.id } }
	val sessionAtlasPages = LocalSessionAtlasPages.current
	val renderSync = LocalPuppetRenderSync.current
	val viewportOverlayColors = rememberViewportOverlayColors()
	val overlayColors = LocalUmamoColors.current
	val overlayStyle = selectionOverlayStyle(overlayColors)

	// Live values the areaId-keyed pointer loop, the remembered controllers, and the latch effect
	// read, so a pan / resize / shown-surface change mid-gesture is seen without re-keying.
	val liveCamera = rememberUpdatedState(camera)
	val liveSize = rememberUpdatedState(IntSize(widthPx, heightPx))
	val liveGeometries = rememberUpdatedState(geometries)
	val liveIslandPick = rememberUpdatedState(islandPick)
	val liveFrame = rememberUpdatedState(frame)
	val liveSurface = rememberUpdatedState(surface)
	val liveRenderSync = rememberUpdatedState(renderSync)

	// The per-area modal-gesture bookkeeping (the Edit overlay's shape); the capture is the surface's
	// gesture and the preview holds each moving island's display positions.
	val gesture = remember(areaId) { ModalGestureState<UvObjectGesture>() }
	var placementDragStatus by placementDragStatusState

	// A committed move's crops linger at their new spots until the resolver's pages catch up with the
	// committed atlas; a resolver that never publishes (no page resolver at all) never gets a ghost.
	var ghost by remember(areaId) { mutableStateOf<PlacementGhost?>(null) }
	val bindingAtlas = sessionAtlasPages?.binding?.value?.atlas
	val ghostData = ghost
	val activeGhost = ghostData?.takeIf { pending -> pending.atlas === committedModel.atlas && bindingAtlas !== pending.atlas }
	LaunchedEffect(ghostData, activeGhost) {
		if (ghostData != null && activeGhost == null) {
			ghost = null
		}
	}

	// The box machinery over whole islands.  The circle callbacks are dormant: a select tool cannot
	// arm over a UV area in Object mode (CommandRouting.selectToolArea keeps B / C Edit-only there),
	// so they stay sanely wired only so an unforeseen arming degrades to a no-op stroke.
	val marquee =
		remember(areaId) {
			MarqueeSelectController<Selection>(
				seedStroke = { session.selection.value },
				stampStroke = { working, _, _, _, _, _ -> working },
				commitStroke = { stroke -> session.setSelection(stroke) },
				applyBox = { start, end, additive, boxCamera, boxSize ->
					val model = session.model.value
					val enclosed =
						uvIslandsInBox(liveGeometries.value, start, end, boxCamera, boxSize)
							.map { drawableId -> SelectionTarget.Drawable(drawableId) }
							.filter { target -> model.selectableOf(target) }
					session.setSelection(resolveIslandBoxSelection(session.selection.value, enclosed, additive))
				},
				setCircleRadius = { radiusPx -> session.setCircleRadius(radiusPx) },
				clearTool = { session.clearSelectTool() },
			)
		}

	// The idle click-pick / un-armed box flow, bound to the island domain: the pick seams unproject
	// the click into display space and run the CPU island picker; the cursor seam converts the
	// unprojected point to normalized UV.  No onBoxBegin - there is no centroid cache to snapshot,
	// the box tests the live display geometry directly.
	val objectPick =
		remember(areaId) {
			ObjectPickController(
				session = session,
				marquee = marquee,
				pickTopmost = { position ->
					val (displayX, displayY) = screenToWorld(position.x, position.y, liveCamera.value, liveSize.value)
					liveIslandPick.value.topmostAt(displayX, displayY)
				},
				pickStack = { position ->
					val (displayX, displayY) = screenToWorld(position.x, position.y, liveCamera.value, liveSize.value)
					liveIslandPick.value.stackAt(displayX, displayY)
				},
				onOverlapRequest = onOverlapRequest,
				placeCursor = { displayX, displayY ->
					val (cursorU, cursorV) = liveFrame.value.storedUvAt(displayX, displayY)
					session.setUvCursor(cursorU, cursorV)
				},
			)
		}

	// What a landed evaluation shows: the crops as ghosts at their committed spots until the
	// resolver's pages land, and a notice when the result collides or spills.  Shared by the confirm
	// and by every adjustment from the operation settings strip.
	fun publishLanding(gestureData: PlacementGesture, result: PlacementDragResult) {
		val crops =
			gestureData.movers.mapNotNull { mover ->
				val crop = mover.crop ?: return@mapNotNull null
				GhostCrop(crop, mover.trim, result.placementByTile.getValue(mover.tileId))
			}
		if (sessionAtlasPages != null && crops.isNotEmpty()) {
			ghost = PlacementGhost(session.model.value.atlas, gestureData.pageHeight, crops)
		}
		if (result.overlappingTileIds.isNotEmpty()) {
			session.emitNotice("notice.uv.placement.overlap", NoticePlacement.NearCursor)
		} else if (result.offPageTileIds.isNotEmpty()) {
			session.emitNotice("notice.uv.placement.offPage", NoticePlacement.NearCursor)
		}
	}

	// Confirms an in-flight placement gesture: commit every mover whose placement changed as ONE undo
	// step under the operator's own label, publish the landing, and register the gesture on the
	// operation settings strip (an adjustment re-evaluates the same frozen gesture over that step).
	// No preview was ever pushed to the renderer, so there is nothing to resync.
	fun confirmPlacementGesture(gestureData: PlacementGesture) {
		val result = gestureData.result ?: return
		val changed = changedPlacements(gestureData.movers, result)
		if (changed.isEmpty()) {
			return
		}
		session.setAtlasPlacements(changed, gestureData.transform.operatorKind)
		publishLanding(gestureData, result)
		registerPlacementAdjustment(session, areaId, gestureData, result) { landed -> publishLanding(gestureData, landed) }
	}

	// Confirms an in-flight mapping gesture the Edit overlay's way: each moving island's display
	// preview converts back through the frozen layer frame to the stored coordinates and commits as
	// ONE undo step, then registers that step on the operation settings strip over the retained
	// capture (no proportional rows - Object mode weights no halo).  A null preview means no movement.
	fun confirmMappingGesture(gestureData: UvGesture) {
		val committed = gesture.preview ?: return
		val parameters = gesture.lastParameters ?: return
		val transform = gestureData.transform
		val modelBefore = session.model.value
		val newUvsByDrawable = LinkedHashMap<DrawableId, FloatArray>(transform.entries.size)
		val vertexIndicesByDrawable = LinkedHashMap<DrawableId, List<Int>>(transform.entries.size)
		for (entry in transform.entries) {
			val transformed = committed[entry.drawableId] ?: continue
			newUvsByDrawable[entry.drawableId] = storedUvsForCommit(modelBefore, entry.drawableId, entry.movedIndices, transformed, gestureData.frame)
			vertexIndicesByDrawable[entry.drawableId] = entry.movedIndices.toList()
		}
		if (newUvsByDrawable.isEmpty()) {
			return
		}
		session.commitMeshUvs(MeshChange.TransformUvs(vertexIndicesByDrawable, transform.operatorKind), newUvsByDrawable)
		// A commit that recorded nothing (the islands landed where they started) has no step to amend.
		if (session.model.value !== modelBefore) {
			registerUvTransformAdjustment(session, areaId, transform, gestureData.frame, parameters, proportional = null) { _, _ -> }
		}
	}

	// Confirms whichever gesture is in flight, then clears the operator (its teardown resyncs the
	// renderer when a mapping preview was streamed).
	fun confirmGesture() {
		when (val gestureData = gesture.capture) {
			is UvObjectGesture.Placement -> confirmPlacementGesture(gestureData.placement)
			is UvObjectGesture.Mapping -> confirmMappingGesture(gestureData.gesture)
			null -> Unit
		}
		session.clearUvOperator()
	}

	// Drives one pointer frame of a placement gesture: the shared operator parameters over the
	// capture's anchor, evaluated into a placement per mover and a display affine per moving island.
	fun drivePlacementPreview(gestureData: PlacementGesture, operator: MeshOperatorKind, virtualPointer: Offset, activeCamera: ViewportCamera, size: IntSize): Boolean {
		val start = gesture.gestureStart ?: return false
		val constraint = session.axisConstraint.value
		val pointerFrame = TransformGestureFrame(gestureData.transform.anchor, start, virtualPointer, constraint, activeCamera, size)
		val parameters = placementGestureParameters(operator, pointerFrame, gestureData.transform.rotationTracker)
		val result =
			evaluatePlacementDrag(
				operatorKind = operator,
				parameters = parameters,
				movers = gestureData.movers,
				bystanders = gestureData.bystanders,
				occupancy = gestureData.occupancy,
				pageWidth = gestureData.pageWidth,
				pageHeight = gestureData.pageHeight,
				extrude = gestureData.extrude,
			)
		gestureData.result = result
		val preview = LinkedHashMap<DrawableId, FloatArray>()
		for ((drawableId, frozen) in gestureData.frozenPositionsByDrawable) {
			val tileId = gestureData.tileByDrawable[drawableId] ?: continue
			val affine = result.displayAffineByTile[tileId] ?: continue
			preview[drawableId] = applyUvAffine(frozen, affine)
		}
		gesture.preview = preview
		placementDragStatus = result.status
		return true
	}

	// Drives one pointer frame of a mapping gesture: the shared operator math over the frozen display
	// coordinates (no halo), converted back through the layer frame and folded into an uncommitted
	// model for the puppet renderer, which shows the art sliding under the mesh as it happens.
	fun driveMappingPreview(gestureData: UvGesture, operator: MeshOperatorKind, virtualPointer: Offset, activeCamera: ViewportCamera, size: IntSize): Boolean {
		val start = gesture.gestureStart ?: return false
		val transform = gestureData.transform
		val pointerFrame = TransformGestureFrame(transform.anchor, start, virtualPointer, session.axisConstraint.value, activeCamera, size)
		val parameters = gestureParameters(operator, pointerFrame, transform.rotationTracker)
		gesture.lastParameters = parameters
		val preview = mappingPreview(transform, operator, parameters)
		gesture.preview = preview
		// The preview converts whole arrays: it is transient and never committed, so the drift the
		// commit avoids (storedUvsWithMoved) is invisible here.
		var folded = session.model.value
		for ((drawableId, display) in preview) {
			folded = folded.withMeshUvs(drawableId, gestureData.frame.storedUvs(display))
		}
		liveRenderSync.value?.previewModel(folded)
		return true
	}

	// Drives whichever gesture is in flight.  False before the capture has landed (a placement
	// capture builds off-thread).
	fun driveGesturePreview(operator: MeshOperatorKind, virtualPointer: Offset, activeCamera: ViewportCamera, size: IntSize): Boolean =
		when (val gestureData = gesture.capture) {
			is UvObjectGesture.Placement -> drivePlacementPreview(gestureData.placement, operator, virtualPointer, activeCamera, size)
			is UvObjectGesture.Mapping -> driveMappingPreview(gestureData.gesture, operator, virtualPointer, activeCamera, size)
			null -> false
		}

	// Ends the gesture, resyncing the renderer when a mapping preview was streamed to it.  The end()
	// boolean is the bystander gate: a teardown that owned no gesture must not stomp another area's
	// live preview.
	fun endGesture() {
		val ended = gesture.capture
		if (gesture.end()) {
			placementDragStatus = null
			if (ended is UvObjectGesture.Mapping) {
				liveRenderSync.value?.resync()
			}
		}
	}

	// The modal gesture's commit-side seam over the shared pointer-side controller.
	val modalTarget =
		object : ModalTransformTarget {
			override fun drivePreview(virtualPointer: Offset, camera: ViewportCamera, size: IntSize): Boolean {
				// Defensive ownership check (the pointer loop already gates): only the initiating area drives.
				val operator = session.activeUvOperator.value?.takeIf { latched -> latched.areaId == areaId } ?: return false
				return driveGesturePreview(operator.kind, virtualPointer, camera, size)
			}

			override fun confirm() {
				confirmGesture()
			}

			override fun cancel() {
				session.clearUvOperator()
			}
		}

	// Escape / the shell's select-gesture cancel: abandon the in-flight box without touching the
	// selection.  The signal carries no area id, so every mounted collector fires - both cancels are
	// no-ops when nothing is in flight here.  While the box is live the controller's area-less
	// viewportGestureActive flag routes Escape to this signal BEFORE the shell ladder's Object-mode
	// selection-clear branch, so Escape drops the box instead of wiping the selection.  No tool-kind
	// backstop rides here: armed tools cannot arm over a UV area in Object mode.
	LaunchedEffect(session) {
		session.meshGestureCancelRequests.collect {
			marquee.cancel()
			objectPick.cancel()
		}
	}

	// Enter confirms the placement gesture (mirroring a primary click), gated to the INITIATING area
	// through the UV latch itself; the Edit overlay is not composed in Object mode, so no other
	// collector can double-commit.
	LaunchedEffect(session) {
		collectModalConfirmRequests(session, { session.activeUvOperator.value?.areaId == areaId }) {
			confirmGesture()
		}
	}

	// Start the surface's gesture as a UV operator latches IN THIS AREA; tear it down as it clears.
	// The session admits any selection with a meshed drawable and leaves the rest to the surface, so
	// every refusal below is this overlay's own, each with its notice.  A placement capture builds
	// off-thread (it decodes rasters and wraps crops); a latch that clears while it builds (Escape, a
	// mode switch) must not begin a stale gesture, so the latch is re-checked after.
	LaunchedEffect(activeOperator) {
		val operator = activeOperator?.takeIf { latched -> latched.areaId == areaId }
		if (operator == null) {
			endGesture()
			return@LaunchedEffect
		}
		val model = session.model.value
		val selection = session.selection.value
		val shownGeometries = liveGeometries.value
		val pivotMode = session.pivotMode.value
		val cursorDisplay = session.uvCursor.value?.let { cursor -> liveFrame.value.displayAt(cursor.u, cursor.v) }
		val shownSurface = liveSurface.value
		if (shownSurface is UvObjectSurface.SourceLayer) {
			// The mapping move needs no decode: it freezes the islands already on screen.
			val build = buildUvMappingGesture(shownGeometries, selection, pivotMode, cursorDisplay, liveFrame.value, operator.kind)
			if (build == null) {
				session.emitNotice("notice.uv.mapping.notOnLayer", NoticePlacement.NearCursor)
				session.clearUvOperator()
			} else {
				gesture.begin(UvObjectGesture.Mapping(build), gesture.lastPointer)
			}
			return@LaunchedEffect
		}
		val pageSurface = (shownSurface as UvObjectSurface.AtlasPage).placement
		if (pageSurface == null) {
			// No source art to recompose the pages from: the same remedy as unpacked art.
			session.emitNotice("notice.uv.placement.noPlacedArt", NoticePlacement.NearCursor)
			session.clearUvOperator()
			return@LaunchedEffect
		}
		val refusal = model.placementGestureRefusal(selection)
		if (refusal != null) {
			val messageKey =
				when (refusal) {
					PlacementGestureRefusal.LayerAddressed -> "notice.uv.placement.layerAddressed"
					PlacementGestureRefusal.NoPlacedArt -> "notice.uv.placement.noPlacedArt"
					PlacementGestureRefusal.Pinned -> "notice.uv.placement.pinned"
				}
			session.emitNotice(messageKey, NoticePlacement.NearCursor)
			session.clearUvOperator()
			return@LaunchedEffect
		}
		val activeDrawableId = (selection.active as? SelectionTarget.Drawable)?.id
		val build =
			withContext(Dispatchers.Default) {
				buildPlacementGesture(model, pageSurface, selection, shownGeometries, pivotMode, activeDrawableId, cursorDisplay, operator.kind)
			}
		if (session.activeUvOperator.value != operator) {
			return@LaunchedEffect
		}
		when (build) {
			PlacementGestureBuild.NotOnPage -> {
				session.emitNotice("notice.uv.placement.notOnPage", NoticePlacement.NearCursor)
				session.clearUvOperator()
			}

			PlacementGestureBuild.NotDerivable -> {
				session.emitNotice("notice.uv.placement.notDerivable", NoticePlacement.NearCursor)
				session.clearUvOperator()
			}

			is PlacementGestureBuild.Ready -> gesture.begin(UvObjectGesture.Placement(build.gesture), gesture.lastPointer)
		}
	}

	// The unmount guard: area death (corner-join, space switch, workspace tab switch) or a mode switch
	// mid-gesture disposes this overlay, cancelling the latch effect above WITHOUT running its else
	// branch - so this must not strand the area-less viewportGestureActive flag true, leave the host's
	// readout showing, or leave the renderer on an un-committed mapping preview.  The controller's
	// boxing latch and the gesture's end() make all of it a no-op otherwise.
	DisposableEffect(areaId) {
		onDispose {
			objectPick.cancel()
			endGesture()
		}
	}

	val ownsGesture = activeOperator?.areaId == areaId
	Box(
		modifier =
			modifier
				.fillMaxSize()
				.onGloballyPositioned { coordinates -> gesture.areaScreenOrigin = coordinates.positionOnScreen() }
				// While THIS AREA'S placement gesture runs, hide the OS cursor so only the overlay's
				// drawn cursor shows; a gesture owned by another area leaves this cursor alone.
				.then(
					if (ownsGesture) {
						Modifier.pointerHoverIcon(hiddenPointerIcon(), overrideDescendants = true)
					} else {
						Modifier
					},
				)
				.pointerInput(areaId) {
					awaitPointerEventScope {
						while (true) {
							val event = awaitPointerEvent()
							val change = event.changes.firstOrNull() ?: continue
							gesture.lastPointer = change.position
							val latchedUvOperator = session.activeUvOperator.value
							// A gesture belongs to its initiating area: another area's UV operator, or any
							// viewport-owned operator or armed tool (which can never belong to a UV area in
							// Object mode), leaves this overlay fully inert - dropping an in-flight box.
							// Escape and Enter stay global through the shell ladder, and navigation falls
							// through to the layer below.
							if (session.activeMeshOperator.value != null ||
								session.activeObjectOperator.value != null ||
								(latchedUvOperator != null && latchedUvOperator.areaId != areaId) ||
								session.activeSelectTool.value != null
							) {
								objectPick.cancel()
								continue
							}
							val activeCamera = liveCamera.value
							val size = liveSize.value
							if (latchedUvOperator != null) {
								// MODAL: the surface's gesture owns the pointer through the shared controller
								// (stale discard, virtual pointer, cursor wrap, LMB-confirm / RMB-cancel).
								objectPick.cancel()
								gesture.lastPointer = gesture.modalController.handleEvent(event, change, modalTarget, activeCamera, size, gesture.areaScreenOrigin)
							} else {
								objectPick.handleIdleEvent(event, change, activeCamera, size)
							}
						}
					}
				},
	) {
		// Two sibling canvases, each in its OWN layer: a draw-state invalidation re-records every draw
		// lambda sharing a layer, so the per-move gesture chrome (the rubber band, the modal HUD) lives
		// in a small layer of its own and the island wireframes plus the drag preview - the expensive
		// pass - stay cached in theirs.  The wireframe layer composites OFFSCREEN: a default layer
		// retains a display list that every window repaint replays (re-stroking every edge), where the
		// offscreen buffer rasterizes once per content change and blits per frame.
		Canvas(
			modifier =
				Modifier.fillMaxSize().graphicsLayer {
					compositingStrategy = CompositingStrategy.Offscreen
				},
		) {
			val areaSize = IntSize(widthPx, heightPx)
			val capture = gesture.capture
			// The art-side chrome (crops, contours, collision tint) is the placement gesture's alone; a
			// mapping gesture moves only the wireframes, which the island loop below reads from the preview.
			val placementCapture = (capture as? UvObjectGesture.Placement)?.placement
			val result = placementCapture?.result
			val activePreview = gesture.preview.takeIf { capture != null }

			// The drag preview under the islands: each mover's original spot dimmed (the art is leaving
			// it), its crop drawn where the placement now puts it, then a committed move's ghosts while
			// the resolver composes the real pixels.
			if (placementCapture != null && result != null) {
				for (mover in placementCapture.movers) {
					drawTileQuad(mover.trim, mover.placement, placementCapture.pageHeight, camera, areaSize) { quad ->
						drawPath(quad, overlayColors.overlayScrim)
					}
				}
				for (mover in placementCapture.movers) {
					val crop = mover.crop ?: continue
					val placement = result.placementByTile[mover.tileId] ?: continue
					drawTileCrop(crop, mover.trim, placement, placementCapture.pageHeight, camera, areaSize)
				}
			}
			activeGhost?.let { pending ->
				for (ghostCrop in pending.crops) {
					drawTileCrop(ghostCrop.crop, ghostCrop.trim, ghostCrop.placement, pending.pageHeight, camera, areaSize)
				}
			}

			val selectedIds =
				objectSelection.targets
					.mapNotNull { target -> (target as? SelectionTarget.Drawable)?.id }
					.toSet()
			val activeId = (objectSelection.active as? SelectionTarget.Drawable)?.id
			// The islands of every colliding tile - the triangles ARE the sampled region, so they are the
			// honest thing to flag - draw their edges in the warning color, movers and bystanders alike;
			// a pinned tile's islands draw theirs in the pinned color, the warning winning while a
			// collision is live (a warning is transient, a pin is state).
			val collidingTileIds = result?.overlappingTileIds ?: emptySet()
			// Islands paint back-to-front by the rest front rank, so the front-most island's wireframe
			// draws last - the painted stacking matches the pick order.  Styling is per-island palette
			// substitution: the idle palette IS the dim style; a selected island fills and outlines with
			// the selected colors; the active island keeps the selected fill under the active-green
			// outline (faceActive is deliberately never a fill - it would blank the art).  During a
			// gesture (placement or mapping) a moving island draws from the live preview.
			val paintOrdered = geometries.sortedBy { geometry -> islandPick.frontRankById[geometry.drawableId] ?: 0f }
			for (geometry in paintOrdered) {
				val styled =
					when {
						geometry.drawableId == activeId ->
							viewportOverlayColors.copy(faceIdle = viewportOverlayColors.faceSelected, edgeIdle = viewportOverlayColors.edgeActive)
						geometry.drawableId in selectedIds ->
							viewportOverlayColors.copy(faceIdle = viewportOverlayColors.faceSelected, edgeIdle = viewportOverlayColors.edgeSelected)
						else -> viewportOverlayColors
					}
				val tileId = tileByDrawableId[geometry.drawableId]
				val islandColors =
					when {
						collidingTileIds.isNotEmpty() && tileId in collidingTileIds ->
							styled.copy(edgeIdle = viewportOverlayColors.warning, edgeSelected = viewportOverlayColors.warning, edgeActive = viewportOverlayColors.warning)
						tileId in pinnedTileIds ->
							styled.copy(edgeIdle = viewportOverlayColors.pinnedPlacement, edgeSelected = viewportOverlayColors.pinnedPlacement, edgeActive = viewportOverlayColors.pinnedPlacement)
						else -> styled
					}
				drawMeshWireframe(
					positions = activePreview?.get(geometry.drawableId) ?: geometry.positions,
					indices = geometry.indices,
					edges = geometry.edges,
					highlight = buildHighlightSets(emptySet(), null, meshSelection.selectMode, geometry.indices),
					selectMode = meshSelection.selectMode,
					colors = islandColors,
					camera = camera,
					size = areaSize,
					objectOverlay = true,
				)
			}

			// The painter's side of a collision on top: a mover whose paint lies under another tile's
			// triangles outlines its opaque region with the art's own contour (never a box), and a
			// spill outlines the trim that leaves the page.  A bystander painter has no contour without
			// a decode; its tinted islands stand for it.
			if (placementCapture != null && result != null) {
				val warningStroke = Stroke(width = 2f)
				for (mover in placementCapture.movers) {
					val placement = result.placementByTile[mover.tileId] ?: continue
					if (mover.tileId in result.paintingTileIds) {
						drawContours(mover.contours, placement, placementCapture.pageHeight, camera, areaSize, viewportOverlayColors.warning, warningStroke)
					}
					if (mover.tileId in result.offPageTileIds) {
						drawTileQuad(mover.trim, placement, placementCapture.pageHeight, camera, areaSize) { quad ->
							drawPath(quad, viewportOverlayColors.warning, style = warningStroke)
						}
					}
				}
			}
		}
		Canvas(modifier = Modifier.fillMaxSize().graphicsLayer()) {
			drawRubberBand(marquee.boxStart, marquee.boxCurrent, overlayStyle)

			// Modal transform HUD (axis line, pivot dash, drawn cursor), shared chrome with the other
			// gizmo overlays.  Only the initiating area draws it - the capture exists solely in the
			// overlay whose area the operator latch names.
			val hudPivot = gesture.capture?.transform?.anchor
			if (ownsGesture && hudPivot != null) {
				drawModalTransformHud(
					axisConstraint = axisConstraint,
					pivotScreen = worldToScreen(hudPivot.first, hudPivot.second, camera, IntSize(widthPx, heightPx)),
					virtualPointer = gesture.cursorWrap.virtualPointer(gesture.lastPointer),
					realPointer = gesture.lastPointer,
					viewport = Size(widthPx.toFloat(), heightPx.toFloat()),
					lineColor = overlayColors.viewportMarquee,
					pointerCursor = LocalUmamoCursors.nsewScroll,
				)
			}
		}
	}
}

/**
 * Where a tile-local point lands on screen under [placement]: through the placement into page pixels,
 * through the display flip, and through the area camera.
 *
 * @param Float tileX The tile-local x.
 * @param Float tileY The tile-local y.
 * @param FloatArray tileToDisplay The tile-to-display affine (the placement's, flipped).
 * @param ViewportCamera camera The area camera.
 * @param IntSize size The area size in pixels.
 * @return Offset The screen position.
 */
private fun tileToScreen(tileX: Float, tileY: Float, tileToDisplay: FloatArray, camera: ViewportCamera, size: IntSize): Offset {
	val displayX = tileToDisplay[0] * tileX + tileToDisplay[1] * tileY + tileToDisplay[2]
	val displayY = tileToDisplay[3] * tileX + tileToDisplay[4] * tileY + tileToDisplay[5]
	return worldToScreen(displayX, displayY, camera, size)
}

/**
 * Draws a tile's trim crop where [placement] puts it: the crop's pixel grid is carried by a matrix
 * fitted to three transformed trim corners, so rotation and scale come along, bilinear-filtered.
 *
 * @param ImageBitmap crop The trim's pixels.
 * @param LayerBounds trim The trim the crop covers, raster-local.
 * @param AtlasPlacement placement Where the tile sits.
 * @param Int pageHeight The page height, for the display flip.
 * @param ViewportCamera camera The area camera.
 * @param IntSize size The area size in pixels.
 */
private fun DrawScope.drawTileCrop(
	crop: ImageBitmap,
	trim: LayerBounds,
	placement: AtlasPlacement,
	pageHeight: Int,
	camera: ViewportCamera,
	size: IntSize,
) {
	val tileToDisplay = tileToDisplayAffine(placement, pageHeight)
	val origin = tileToScreen(trim.left.toFloat(), trim.top.toFloat(), tileToDisplay, camera, size)
	val xAxis = tileToScreen(trim.left + 1f, trim.top.toFloat(), tileToDisplay, camera, size) - origin
	val yAxis = tileToScreen(trim.left.toFloat(), trim.top + 1f, tileToDisplay, camera, size) - origin
	val matrix = Matrix()
	matrix.values[Matrix.ScaleX] = xAxis.x
	matrix.values[Matrix.SkewY] = xAxis.y
	matrix.values[Matrix.SkewX] = yAxis.x
	matrix.values[Matrix.ScaleY] = yAxis.y
	matrix.values[Matrix.TranslateX] = origin.x
	matrix.values[Matrix.TranslateY] = origin.y
	withTransform({ transform(matrix) }) {
		drawImage(image = crop, dstSize = IntSize(crop.width, crop.height), filterQuality = FilterQuality.Low)
	}
}

/**
 * Hands [draw] the screen-space quad of a tile's trim under [placement].
 *
 * @param LayerBounds trim The trim, raster-local.
 * @param AtlasPlacement placement Where the tile sits.
 * @param Int pageHeight The page height, for the display flip.
 * @param ViewportCamera camera The area camera.
 * @param IntSize size The area size in pixels.
 * @param Function draw Draws the closed quad.
 */
private fun DrawScope.drawTileQuad(
	trim: LayerBounds,
	placement: AtlasPlacement,
	pageHeight: Int,
	camera: ViewportCamera,
	size: IntSize,
	draw: DrawScope.(Path) -> Unit,
) {
	val tileToDisplay = tileToDisplayAffine(placement, pageHeight)
	val left = trim.left.toFloat()
	val top = trim.top.toFloat()
	val right = (trim.left + trim.width).toFloat()
	val bottom = (trim.top + trim.height).toFloat()
	val quad = Path()
	val first = tileToScreen(left, top, tileToDisplay, camera, size)
	quad.moveTo(first.x, first.y)
	for ((cornerX, cornerY) in listOf(right to top, right to bottom, left to bottom)) {
		val corner = tileToScreen(cornerX, cornerY, tileToDisplay, camera, size)
		quad.lineTo(corner.x, corner.y)
	}
	quad.close()
	draw(quad)
}

/**
 * Strokes a tile's opaque contours on screen under [placement]: each loop's lattice corners carried
 * through the placement and the camera, holes included, so the outline follows the art.
 *
 * @param List<AlphaContour> contours The loops, raster-local.
 * @param AtlasPlacement placement Where the tile sits.
 * @param Int pageHeight The page height, for the display flip.
 * @param ViewportCamera camera The area camera.
 * @param IntSize size The area size in pixels.
 * @param Color color The stroke color.
 * @param Stroke stroke The stroke style.
 */
private fun DrawScope.drawContours(
	contours: List<AlphaContour>,
	placement: AtlasPlacement,
	pageHeight: Int,
	camera: ViewportCamera,
	size: IntSize,
	color: Color,
	stroke: Stroke,
) {
	val tileToDisplay = tileToDisplayAffine(placement, pageHeight)
	for (contour in contours) {
		if (contour.points.size < 6) {
			continue
		}
		val outline = Path()
		var pointIndex = 0
		while (pointIndex + 1 < contour.points.size) {
			val screen = tileToScreen(contour.points[pointIndex].toFloat(), contour.points[pointIndex + 1].toFloat(), tileToDisplay, camera, size)
			if (pointIndex == 0) {
				outline.moveTo(screen.x, screen.y)
			} else {
				outline.lineTo(screen.x, screen.y)
			}
			pointIndex += 2
		}
		outline.close()
		drawPath(outline, color, style = stroke)
	}
}