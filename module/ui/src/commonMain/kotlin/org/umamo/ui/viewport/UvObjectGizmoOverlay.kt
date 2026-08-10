package org.umamo.ui.viewport

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.edit.selectableOf
import org.umamo.render.ViewportCamera
import org.umamo.render.pick.PickCandidate
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.drawRubberBand
import org.umamo.ui.theme.selectionOverlayStyle

/**
 * The UV editor's Object-mode gizmo overlay, the mode-exclusive sibling of [UvEditGizmoOverlay]
 * (each one self-gates on the session's mode, the viewport overlay pair's convention): every
 * visible island on the shown page draws in the Blender object-overlay style - unselected islands
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
 * Picking is CPU-side over [islandPick] - display-space point-in-face with the atlas alpha gate, so
 * a click on transparent triangle overhang falls through, exactly like the viewport's raster pick.
 * Only primary-driven events are consumed; pan / zoom and the plain right-click (the context menu)
 * fall through.
 *
 * Posed from the FRAME camera so it lags with the GL page during pan / zoom, and unclipped to the
 * page tile by design, so UVs outside it stay visible (the hosting area still clips to its own
 * bounds).
 *
 * @param String areaId The UV editor area this overlay covers (keys the pointer loop).
 * @param EditorSession session The session owning the object selection, the model, and the latches.
 * @param List<GizmoMeshGeometry> geometries The shown islands' display-space gizmo geometry.
 * @param UvIslandPick islandPick The page's island picker (point pick, stack query, front ranks).
 * @param Int pageWidth The shown atlas page's width in texels (the display mapping's scale).
 * @param Int pageHeight The shown atlas page's height in texels.
 * @param ViewportCamera? camera The displayed frame's camera; null hides the overlay (no frame yet).
 * @param Int widthPx The area width in pixels.
 * @param Int heightPx The area height in pixels.
 * @param Function onOverlapRequest Opens the host's overlap picker for an Alt click with 2+ candidates.
 * @param Modifier modifier The layout modifier.
 */
@Composable
internal fun UvObjectGizmoOverlay(
	areaId: String,
	session: EditorSession,
	geometries: List<GizmoMeshGeometry>,
	islandPick: UvIslandPick,
	pageWidth: Int,
	pageHeight: Int,
	camera: ViewportCamera?,
	widthPx: Int,
	heightPx: Int,
	onOverlapRequest: (Offset, List<PickCandidate>) -> Unit,
	modifier: Modifier = Modifier,
) {
	val mode by session.mode.collectAsState()
	val meshSelection by session.meshSelection.collectAsState()
	val objectSelection by session.selection.collectAsState()
	val gizmoColors = rememberMeshEditColors()
	val overlayColors = LocalUmamoColors.current
	if (mode == EditorMode.Edit || camera == null) {
		return
	}
	val overlayStyle = selectionOverlayStyle(overlayColors)

	// Live values the areaId-keyed pointer loop and the remembered controllers read, so a pan /
	// resize / page hop mid-gesture is seen without re-keying.
	val liveCamera = rememberUpdatedState(camera)
	val liveSize = rememberUpdatedState(IntSize(widthPx, heightPx))
	val liveGeometries = rememberUpdatedState(geometries)
	val liveIslandPick = rememberUpdatedState(islandPick)
	val livePageWidth = rememberUpdatedState(pageWidth)
	val livePageHeight = rememberUpdatedState(pageHeight)

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
					session.setUvCursor(
						displayToUvU(displayX, livePageWidth.value),
						displayToUvV(displayY, livePageHeight.value),
					)
				},
			)
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

	// The unmount guard: area death (corner-join, space switch, workspace tab switch) mid-box-drag
	// must not strand the area-less viewportGestureActive flag true - pan / zoom in every viewport
	// would stay suppressed.  The controller's boxing latch makes this a no-op otherwise.
	DisposableEffect(areaId) {
		onDispose { objectPick.cancel() }
	}

	Box(
		modifier =
			modifier
				.fillMaxSize()
				.pointerInput(areaId) {
					awaitPointerEventScope {
						while (true) {
							val event = awaitPointerEvent()
							val change = event.changes.firstOrNull() ?: continue
							// Nothing can legitimately be armed in a UV area in Object mode - UV operators
							// and select tools latch only in Edit mode, mesh / object operators only in
							// viewport areas - so ANY live latch is a foreign gesture: stay fully inert
							// (dropping an in-flight box) while one runs.  Escape and Enter stay global
							// through the shell ladder, and navigation falls through to the layer below.
							if (session.activeMeshOperator.value != null ||
								session.activeObjectOperator.value != null ||
								session.activeUvOperator.value != null ||
								session.activeSelectTool.value != null
							) {
								objectPick.cancel()
								continue
							}
							objectPick.handleIdleEvent(event, change, liveCamera.value, liveSize.value)
						}
					}
				},
	) {
		// Two sibling canvases, each in its OWN layer: a draw-state invalidation re-records every draw
		// lambda sharing a layer, so the per-move gesture chrome (the rubber band) lives in a small
		// layer of its own and the island wireframes - the expensive pass - stay cached in theirs.
		// The wireframe layer composites OFFSCREEN: a default layer retains a display list that every
		// window repaint replays (re-stroking every edge), where the offscreen buffer rasterizes once
		// per content change and blits per frame.
		Canvas(
			modifier =
				Modifier.fillMaxSize().graphicsLayer {
					compositingStrategy = CompositingStrategy.Offscreen
				},
		) {
			val selectedIds =
				objectSelection.targets
					.mapNotNull { target -> (target as? SelectionTarget.Drawable)?.id }
					.toSet()
			val activeId = (objectSelection.active as? SelectionTarget.Drawable)?.id
			// Islands paint back-to-front by the rest front rank, so the front-most island's wireframe
			// draws last - the painted stacking matches the pick order.  Styling is per-island palette
			// substitution: the idle palette IS the dim style; a selected island fills and outlines with
			// the selected colors; the active island keeps the selected fill under the active-green
			// outline (faceActive is deliberately never a fill - it would blank the art).
			val paintOrdered = geometries.sortedBy { geometry -> islandPick.frontRankById[geometry.drawableId] ?: 0f }
			for (geometry in paintOrdered) {
				val islandColors =
					when {
						geometry.drawableId == activeId ->
							gizmoColors.copy(faceIdle = gizmoColors.faceSelected, edgeIdle = gizmoColors.edgeActive)
						geometry.drawableId in selectedIds ->
							gizmoColors.copy(faceIdle = gizmoColors.faceSelected, edgeIdle = gizmoColors.edgeSelected)
						else -> gizmoColors
					}
				drawMeshWireframe(
					positions = geometry.positions,
					indices = geometry.indices,
					edges = geometry.edges,
					highlight = buildHighlightSets(emptySet(), null, meshSelection.selectMode, geometry.indices),
					selectMode = meshSelection.selectMode,
					colors = islandColors,
					camera = camera,
					size = IntSize(widthPx, heightPx),
					objectOverlay = true,
				)
			}
		}
		Canvas(modifier = Modifier.fillMaxSize().graphicsLayer()) {
			drawRubberBand(marquee.boxStart, marquee.boxCurrent, overlayStyle)
		}
	}
}