package org.umamo.ui.viewport.uv

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.render.ViewportCamera
import org.umamo.render.pick.PickCandidate
import org.umamo.ui.model.LocalSessionAtlasPages
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.hiddenPointerIcon
import org.umamo.ui.theme.selectionOverlayStyle
import org.umamo.ui.viewport.gizmo.GizmoMeshGeometry
import org.umamo.ui.viewport.gizmo.collectModalConfirmRequests
import org.umamo.ui.viewport.rememberViewportOverlayColors

/*
 * The UV editor's Object-mode gizmo overlay.  This file is the wiring: what the overlay collects, its guard,
 * what it holds per area and for how long, the effects in the order they launch, and the two layers it
 * draws.  Its parts:
 *   - UvModalTransform.kt: the commit side both UV overlays share (the latch ownership rule, cancel, end,
 *     abandon).
 *   - UvPlacementModalTransform.kt: this overlay's placement gesture over the shown atlas page (the
 *     off-thread capture, drive, confirm, the landing's ghost crops and notices) - the ModalTransformTarget
 *     the pointer loop hands a gesture's events to.
 *   - UvObjectGizmoSelection.kt: the marquee and the click pick over whole islands.
 *   - UvObjectGizmoPointerInput.kt: the pointer loop (placement gesture, idle pick and box).
 *   - UvObjectGizmoDraw.kt: the islands in the object-overlay style and the gesture chrome.
 *   - UvPlacementDraw.kt: the placement preview, the ghost crops, and the collision outlines.
 * The placement model and its evaluation are in UvPlacementGesture.kt, the strip registration in
 * UvPlacementAdjust.kt, and the UV cursor helpers both overlays use in UvCursorOverlay.kt.
 */

/**
 * The UV editor's Object-mode gizmo overlay, the mode-exclusive sibling of [UvEditGizmoOverlay]
 * (each one self-gates on the session's mode, the viewport overlay pair's convention): every
 * visible island on the shown surface draws in the Blender object-overlay style - unselected islands
 * dim (the idle palette), selected islands highlighted, the active island's outline emphasized -
 * and the islands are click targets writing the ONE session object selection, so a selection made
 * here flows out to the viewport and the outliner.
 *
 * The interaction vocabulary is the viewport Object gizmo's, through the same
 * [org.umamo.ui.viewport.gizmo.ObjectPickController]: a sub-threshold primary click picks the front-most
 * island under the cursor (plain replaces, Shift / Ctrl toggles membership, an unmodified click on empty
 * canvas clears - under Follow Selection that may hop the shown page to the first-meshed fallback, while a
 * pinned page holds), an Alt click resolves the overlap stack through the host's popup, a primary
 * drag box-selects every island with a vertex inside the box (Shift adds), and Shift+RightClick
 * places the UV cursor.
 * Picking is CPU-side over [islandPick] - display-space point-in-face in two tiers.  An island
 * whose art is opaque under the cursor wins, so over overlapping islands the visible art decides;
 * when none is opaque there, any island whose mesh contains the point is hit, so a click on an
 * island's transparent interior still selects it and only a click outside every mesh is empty
 * canvas.  The same overlay serves both surfaces: over a source layer it gates on that artwork's own
 * alpha and places the cursor through the layer's frame, which is the whole of the difference.
 *
 * Over an ATLAS PAGE the overlay also owns the placement gesture (UvPlacementModalTransform): a UV operator
 * latched in this area in Object mode (G / S / R) moves the selected drawables' art on the page, previews the
 * crops at their new spots and the islands translated with them, outlines any footprint that collides or
 * spills off the page, and commits ONE undo step.  Nothing is pushed to the puppet renderer during the drag:
 * a placement move is invisible in the 2D viewport by construction.  Only primary-driven events are consumed
 * while idle; pan / zoom and the plain right-click (the context menu) fall through, and a modal gesture owns
 * the pointer.
 *
 * Posed from the FRAME camera so it lags with the GL image during pan / zoom, and unclipped to the
 * image tile by design, so a mapping reaching past it stays visible - which is normal, since a mesh
 * rings outside the art it samples (the hosting area still clips to its own bounds).
 *
 * @param String areaId The UV editor area this overlay covers (keys the pointer loop).
 * @param EditorSession session The session owning the object selection, the model, and the latches.
 * @param List<GizmoMeshGeometry> geometries The shown islands' display-space gizmo geometry.
 * @param UvIslandPickController islandPick The shown surface's island picker (point pick, stack query, front ranks).
 * @param UvEditFrame frame The shown surface's texel size plus how a coordinate over it reaches the
 *   stored texture coordinates (an atlas page is the stored frame itself; a source layer is not).
 * @param ViewportCamera? camera The displayed frame's camera; null hides the overlay (no frame yet).
 * @param Int widthPx The area width in pixels.
 * @param Int heightPx The area height in pixels.
 * @param UvPlacementSurface? placementSurface The shown atlas page and the source-art store when the
 *   area shows a page, or null over a source layer (a latched placement operator is then dropped).
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
	islandPick: UvIslandPickController,
	frame: UvEditFrame,
	camera: ViewportCamera?,
	widthPx: Int,
	heightPx: Int,
	placementSurface: UvPlacementSurface?,
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
	// Held as State, not read here: the HUD reads it only while drawing a gesture this area owns.
	val axisConstraintState = session.axisConstraint.collectAsState()
	val committedModel by session.model.collectAsState()
	val tileByDrawableId = remember(committedModel) { tileIdsByDrawable(committedModel) }
	val pinnedTileIds = remember(committedModel) { pinnedTileIdsOf(committedModel) }
	val sessionAtlasPages = LocalSessionAtlasPages.current
	val viewportOverlayColors = rememberViewportOverlayColors()
	val overlayColors = LocalUmamoColors.current
	val overlayStyle = selectionOverlayStyle(overlayColors)

	// Live values the areaId-keyed pointer loop, the per-area holders, and the latch effect read, so a
	// pan / resize / shown-surface change mid-gesture is seen without re-keying.
	val liveCamera = rememberUpdatedState(camera)
	val liveSize = rememberUpdatedState(IntSize(widthPx, heightPx))
	val liveGeometries = rememberUpdatedState(geometries)
	val liveIslandPick = rememberUpdatedState(islandPick)
	val liveFrame = rememberUpdatedState(frame)
	val liveSurface = rememberUpdatedState(placementSurface)
	val liveAtlasPages = rememberUpdatedState(sessionAtlasPages)
	val liveDragStatus = rememberUpdatedState(placementDragStatusState)
	// The host's overlap callback closes over its render service, which can change while the area lives.
	val liveOverlapRequest = rememberUpdatedState(onOverlapRequest)

	// The placement gesture's commit side, one per area: the pointer loop and the collectors below keep the
	// instance they started with (see UvPlacementModalTransform).  Its gesture state is what the Box, the
	// pointer loop, and the chrome read.
	val modalTransform = remember(areaId) { UvPlacementModalTransform(areaId, session, liveAtlasPages, liveDragStatus) }
	val gesture = modalTransform.gesture

	// A committed move's crops linger at their new spots until the resolver's pages catch up with the
	// committed atlas; a resolver that never publishes (no page resolver at all) never gets a ghost.
	val ghostData = modalTransform.ghost
	val activeGhost = activePlacementGhost(ghostData, committedModel.atlas, sessionAtlasPages?.binding?.value?.atlas)
	LaunchedEffect(ghostData, activeGhost) {
		if (ghostData != null && activeGhost == null) {
			modalTransform.dismissGhost()
		}
	}

	// The box machinery over whole islands, one per area (see uvObjectMarquee).
	val marquee = remember(areaId) { uvObjectMarquee(session, liveGeometries) }

	// The idle click-pick / un-armed box flow bound to the island domain, one per area (see uvObjectPick).
	val objectPick =
		remember(areaId) {
			uvObjectPick(session, marquee, liveIslandPick, liveFrame, liveCamera, liveSize) { position, candidates ->
				liveOverlapRequest.value(position, candidates)
			}
		}

	// The unmount guard: area death (corner-join, space switch, workspace tab switch), leaving Object mode, or
	// losing the frame camera mid-gesture disposes this overlay, which cancels the latch effect below WITHOUT
	// running its teardown.  The gesture is abandoned - its latch cleared while it is still this area's, so
	// none restarts from a fresh gesture state when the overlay comes back - and the host's readout cleared;
	// a select gesture in flight is dropped, and the area-less viewportGestureActive flag it raised comes down.
	DisposableEffect(modalTransform) {
		onDispose {
			marquee.discard()
			objectPick.cancel()
			modalTransform.abandon()
		}
	}

	// Escape / the shell's select-gesture cancel: abandon the in-flight box without touching the selection.
	// The signal carries no area id, so every mounted collector fires - both cancels are no-ops when nothing
	// is in flight here.  While the box is live the controller's area-less viewportGestureActive flag routes
	// Escape to this signal BEFORE the shell ladder's Object-mode selection-clear branch, so Escape drops the
	// box instead of wiping the selection.  No tool-kind backstop rides here: armed tools cannot arm over a UV
	// area in Object mode.
	LaunchedEffect(session) {
		session.meshGestureCancelRequests.collect {
			marquee.cancel()
			objectPick.cancel()
		}
	}

	// Enter confirms the placement gesture (mirroring a primary click), gated to the INITIATING area through
	// the UV latch itself; the Edit overlay is not composed in Object mode, so no other collector can
	// double-commit.
	LaunchedEffect(session) {
		collectModalConfirmRequests(session, { session.activeUvOperator.value?.areaId == areaId }) {
			modalTransform.confirm()
		}
	}

	// Start the placement gesture as a UV operator latches IN THIS AREA; tear it down (clearing the host's
	// readout) as it clears.  The capture builds off-thread (see UvPlacementModalTransform.begin) from the
	// surface, the shown geometry, the selection, and the frame as they are when the effect runs.
	LaunchedEffect(activeOperator) {
		val operator = activeOperator?.takeIf { latched -> latched.areaId == areaId }
		if (operator == null) {
			modalTransform.end()
		} else {
			modalTransform.begin(operator, liveSurface.value, liveGeometries.value, session.selection.value, liveFrame.value)
		}
	}

	val ownsGesture = activeOperator?.areaId == areaId
	Box(
		modifier =
			modifier
				.fillMaxSize()
				.onGloballyPositioned { coordinates -> gesture.areaScreenOrigin = coordinates.positionOnScreen() }
				// While THIS AREA'S placement gesture runs, hide the OS cursor so only the overlay's drawn
				// cursor shows; a gesture owned by another area leaves this cursor alone.
				.then(
					if (ownsGesture) {
						Modifier.pointerHoverIcon(hiddenPointerIcon(), overrideDescendants = true)
					} else {
						Modifier
					},
				)
				.pointerInput(areaId) {
					uvObjectGizmoPointerLoop(areaId, session, modalTransform, objectPick, liveCamera, liveSize)
				},
	) {
		// Two sibling canvases, each in its OWN layer: a draw-state invalidation re-records every draw lambda
		// sharing a layer, so the per-move gesture chrome (the rubber band, the modal HUD) lives in a small
		// layer of its own and the island wireframes plus the drag preview - the expensive pass - stay cached
		// in theirs.  The wireframe layer composites OFFSCREEN: a default layer retains a display list that
		// every window repaint replays (re-stroking every edge), where the offscreen buffer rasterizes once
		// per content change and blits per frame.
		Canvas(
			modifier =
				Modifier.fillMaxSize().graphicsLayer {
					compositingStrategy = CompositingStrategy.Offscreen
				},
		) {
			val areaSize = IntSize(widthPx, heightPx)
			val capture = gesture.capture
			val result = capture?.result
			drawPlacementPreview(capture, result, activeGhost, overlayColors.overlayScrim, camera, areaSize)
			drawUvObjectIslands(
				geometries = geometries,
				frontRankById = islandPick.frontRankById,
				selection = objectSelection,
				selectMode = meshSelection.selectMode,
				tileByDrawableId = tileByDrawableId,
				pinnedTileIds = pinnedTileIds,
				collidingTileIds = result?.overlappingTileIds ?: emptySet(),
				preview = gesture.preview.takeIf { capture != null },
				colors = viewportOverlayColors,
				camera = camera,
				size = areaSize,
			)
			drawPlacementCollisions(capture, result, viewportOverlayColors.warning, camera, areaSize)
		}
		Canvas(modifier = Modifier.fillMaxSize().graphicsLayer()) {
			drawUvObjectGizmoChrome(
				marquee = marquee,
				gesture = gesture,
				owned = ownsGesture,
				axisConstraint = axisConstraintState,
				camera = camera,
				size = IntSize(widthPx, heightPx),
				style = overlayStyle,
				lineColor = overlayColors.viewportMarquee,
			)
		}
	}
}