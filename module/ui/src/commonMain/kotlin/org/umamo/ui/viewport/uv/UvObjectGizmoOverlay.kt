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
 * what it holds per area and for how long, the effects in the order they launch, and the chrome layer it
 * draws.  The islands and the placement drag's preview are the render service's, drawn into the area's frame
 * from the scene the host publishes (UvSceneOverlay.kt).  Its parts:
 *   - UvModalTransform.kt: the commit side both UV overlays share (the latch ownership rule, cancel, end,
 *     abandon).
 *   - UvPlacementModalTransform.kt: this overlay's placement gesture over the shown atlas page (the
 *     off-thread capture, drive, confirm, the landing's ghost crops and notices) - the ModalTransformTarget
 *     the pointer loop hands a gesture's events to.
 *   - UvObjectGizmoSelection.kt: the marquee and the click pick over whole islands.
 *   - UvObjectGizmoPointerInput.kt: the pointer loop (placement gesture, idle pick and box).
 *   - UvObjectGizmoDraw.kt: the gesture chrome.
 *   - UvPlacementDraw.kt: the collision outlines.
 *   - UvPlacementScene.kt: the drag's and the landing's share of the area's scene, which the gesture writes.
 * The placement model and its evaluation are in UvPlacementGesture.kt, the strip registration in
 * UvPlacementAdjust.kt, and the UV cursor helpers both overlays use in UvCursorOverlay.kt.
 */

/**
 * The UV editor's Object-mode gizmo overlay, the mode-exclusive sibling of [UvEditGizmoOverlay]
 * (each one self-gates on the session's mode, the viewport overlay pair's convention): every
 * visible island on the shown surface is a click target writing the ONE session object selection, so a
 * selection made here flows out to the viewport and the outliner.  The islands themselves draw in the area's
 * frame, in the Blender object-overlay style (UvObjectSceneOverlay.kt).
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
 * latched in this area in Object mode (G / S / R) moves the selected drawables' art on the page, publishes the
 * drag to the host's [placementSceneState] (the area's frame then shows the crops at their new spots and the
 * islands moved with them), outlines any footprint that collides or spills off the page, and commits ONE undo
 * step.  Nothing is pushed to the puppet renderer during the drag:
 * a placement move is invisible in the 2D viewport by construction.  Only primary-driven events are consumed
 * while idle; pan / zoom and the plain right-click (the context menu) fall through, and a modal gesture owns
 * the pointer.
 *
 * Posed from the FRAME camera so it lags with the GL image during pan / zoom.
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
 * @param UvPlacementSceneState placementSceneState The host-owned placement scene this overlay's gesture
 *   writes per drive and at a landing, and the host's scene publish reads.
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
	placementSceneState: UvPlacementSceneState,
	onOverlapRequest: (Offset, List<PickCandidate>) -> Unit,
	modifier: Modifier = Modifier,
) {
	val mode by session.mode.collectAsState()
	if (mode == EditorMode.Edit || camera == null) {
		return
	}

	val activeOperator by session.activeUvOperator.collectAsState()
	// Held as State, not read here: the HUD reads it only while drawing a gesture this area owns.
	val axisConstraintState = session.axisConstraint.collectAsState()
	val committedModel by session.model.collectAsState()
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
	val liveSceneState = rememberUpdatedState(placementSceneState)
	// The host's overlap callback closes over its render service, which can change while the area lives.
	val liveOverlapRequest = rememberUpdatedState(onOverlapRequest)

	// The placement gesture's commit side, one per area: the pointer loop and the collectors below keep the
	// instance they started with (see UvPlacementModalTransform).  Its gesture state is what the Box, the
	// pointer loop, and the chrome read.
	val modalTransform = remember(areaId) { UvPlacementModalTransform(areaId, session, liveAtlasPages, liveDragStatus, liveSceneState) }
	val gesture = modalTransform.gesture

	// A committed move's crops linger at their new spots while its atlas is the committed one; an undo or a
	// newer commit takes them down here.  When its pages have landed is the engine's call (decision D20), and
	// a resolver that never publishes (no page resolver at all) never gets a ghost.
	val ghost = placementSceneState.ghost
	val committedAtlas = committedModel.atlas
	LaunchedEffect(ghost, committedAtlas) {
		if (ghost != null && activePlacementGhost(ghost, committedAtlas) == null) {
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
	// none restarts from a fresh gesture state when the overlay comes back - and the host's readout and the
	// placement scene cleared, the ghost with it; a select gesture in flight is dropped, and the area-less
	// viewportGestureActive flag it raised comes down.
	DisposableEffect(modalTransform) {
		onDispose {
			marquee.discard()
			objectPick.cancel()
			modalTransform.abandon()
			modalTransform.dismissGhost()
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
		// The chrome (the collision outlines, the rubber band, the modal HUD) in a small layer of its own: it reads
		// the pointer and the drive's result in the draw phase, so a move redraws this layer and nothing else.
		Canvas(modifier = Modifier.fillMaxSize().graphicsLayer()) {
			val capture = gesture.capture
			drawPlacementCollisions(capture, capture?.result, viewportOverlayColors.warning, camera, IntSize(widthPx, heightPx))
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