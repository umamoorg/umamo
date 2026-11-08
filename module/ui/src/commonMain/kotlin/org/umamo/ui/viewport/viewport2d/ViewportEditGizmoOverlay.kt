package org.umamo.ui.viewport.viewport2d

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.EditorSession
import org.umamo.edit.editsElements
import org.umamo.render.ViewportCamera
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.hiddenPointerIcon
import org.umamo.ui.theme.selectionOverlayStyle
import org.umamo.ui.transform.elementEditStageOf
import org.umamo.ui.transform.rememberElementEditStage
import org.umamo.ui.viewport.LocalAreaOverlays
import org.umamo.ui.viewport.LocalLiveMeshPreview
import org.umamo.ui.viewport.ObjectPickCandidate
import org.umamo.ui.viewport.PuppetViewportService
import org.umamo.ui.viewport.gizmo.ModalDriveEffect
import org.umamo.ui.viewport.gizmo.collectAxisLockChanges
import org.umamo.ui.viewport.gizmo.collectModalConfirmRequests
import org.umamo.ui.viewport.gizmo.selectToolKind
import org.umamo.ui.viewport.resyncAfterElementGesture

/*
 * The element gizmo overlay of Edit and Pose mode.  This file is the wiring: what the overlay collects, its guards,
 * what it holds per area and for how long, the effects in the order they launch, and the layer it draws.  What the
 * two modes do differently - where the elements are captured and how a lattice edit lands - is the stage
 * (transform/ElementEditStage.kt), read once here and handed to everything below.
 * Its parts:
 *   - EditModalTransform.kt: the commit side of the modal G / S / R and Vertex Slide (capture, drive,
 *     confirm, cancel, the wheel) - the ModalTransformTarget the pointer loop hands a gesture's events to.
 *   - VertexSlide.kt: the slide's frozen candidates and the per-frame edge pick.
 *   - EditGizmoRequests.kt: the area-gated collectors for Select Linked, Alt+Q, Rip, and the snaps.
 *   - EditGizmoPointerInput.kt: the pointer loop (modal transform, circle brush, idle selection).
 *   - LatticeHover.kt: the idle lattice the pointer is over in Pose mode, drawn in the hover color (shared with
 *     the Object overlay).
 *   - EditGizmoSelection.kt: the marquee and the element pick over mesh elements.
 *   - EditGizmoDraw.kt: the gesture chrome, read in the draw phase.
 *   - EditMeshGeometry.kt: the session owners' live geometry at the stage, which the picks read.
 *   - MeshOverlayPublish.kt and LatticeCagePublish.kt: the wireframe, dots, face fills, and lattice cage as data
 *     for the renderer, which draws them into the frame with the art; the viewport binding publishes them, not this
 *     overlay.
 * The collectors' handlers are in SessionRequestHandlers.kt, and the strip registrations in
 * TransformAdjustRegistration.kt.
 */

/**
 * The element gizmo overlay of Edit and Pose mode: a Compose layer over the offscreen puppet image that runs the
 * element selection and the modal G / S / R operators and draws their gesture chrome.  The mesh or cage itself is
 * drawn by the renderer into the image, from the overlays the viewport binding publishes, so it and the art are the
 * same pixels.  It composes only in a mode that edits elements ([editsElements]) with something in the session, so
 * pointer input otherwise flows untouched to the viewport navigation beneath.
 *
 * Pose mode works as Edit mode does over the warps it poses, at the session's pose instead of at rest; the stage
 * ([elementEditStageOf]) carries the difference.  Interaction mirrors Blender's mesh edit mode.
 *
 * @param String areaId The viewport area this overlay covers.
 * @param PuppetViewportService service The render service (for live preview pushes).
 * @param EditorSession session The session owning the model, element selection, and active operator.
 * @param ViewportCamera? camera The camera the displayed frame was rendered at (world<->screen affine);
 *        null hides the overlay.
 * @param Int widthPx The area width in pixels.
 * @param Int heightPx The area height in pixels.
 * @param State areaPointer Where the pointer last was in this area, tracked by the HOST so the
 *        pointer-addressed commands still resolve while this overlay is unmounted.
 * @param Function onOverlapRequest Opens the overlap-picker popup for an Alt+Q over 2+ stacked
 *        candidates: the drawables under the pointer in Edit mode, the lattices in Pose mode (the pick
 *        switches the edited or posed owner).
 * @param Modifier modifier The layout modifier.
 */
@Composable
fun ViewportEditGizmoOverlay(
	areaId: String,
	service: PuppetViewportService,
	session: EditorSession,
	camera: ViewportCamera?,
	widthPx: Int,
	heightPx: Int,
	areaPointer: State<Offset>,
	onOverlapRequest: (Offset, List<ObjectPickCandidate>) -> Unit,
	modifier: Modifier = Modifier,
) {
	val mode by session.mode.collectAsState()
	val meshSelection by session.meshSelection.collectAsState()
	val model by session.model.collectAsState()
	val activeOperator by session.activeMeshOperator.collectAsState()
	val activeSelectTool by session.activeSelectTool.collectAsState()
	// Held as State, not read here: the chrome reads them only while drawing a gesture this area owns.
	val axisConstraintState = session.axisConstraint.collectAsState()
	val proportionalEditState = session.proportionalEdit.collectAsState()
	// Theme-level overlay chrome (the marquee) comes from the palette; the mesh colors are settings-backed
	// and reach the renderer through the viewport binding.
	val overlayColors = LocalUmamoColors.current

	val sessionOwners = meshSelection.owners

	if (!mode.editsElements || sessionOwners.isEmpty() || camera == null) {
		return
	}

	// Where the elements are captured and how a lattice edit lands: at rest in Edit mode, at the pose with the pending
	// lattices in place in Pose mode.  Remembered once, so every reader below agrees.
	val stage = rememberElementEditStage(session)

	// The shared two-tone marching-ants style for the box / circle / crosshair affordances.
	val overlayStyle = selectionOverlayStyle(overlayColors)

	// Per-session-owner geometry at the stage: each mesh at rest, or each lattice at rest or at the pose.  Keyed on
	// the model (a commit swaps it), the session's owners, and the stage; NOT per rendered frame.
	val liveGeometry = remember(model, sessionOwners, stage) { editOwnerGeometries(model, sessionOwners, stage) }

	// Live values the long-running pointer loop, the marquee callbacks, and the request collectors read
	// (they are keyed only on areaId / session, so they must not close over a stale camera / size / shape
	// / topology when the model, pan, or resize change mid-edit).
	val liveCamera = rememberUpdatedState(camera)
	val liveSize = rememberUpdatedState(IntSize(widthPx, heightPx))
	val liveGeometryState = rememberUpdatedState(liveGeometry)
	val liveStage = rememberUpdatedState(stage)

	// The keymap-command collectors, mounted above the EMPTY-GEOMETRY guard below but still inside every
	// guard above it: this overlay serves the element modes only and so do these commands, so there is nothing to
	// gain by outliving the mode / selection / camera checks, and a second collector live in Object mode
	// would only duplicate what the object overlay already runs.
	//
	// What they must outlive is the empty-geometry return.  liveGeometry goes empty when every drawable
	// in the session selection sits behind a hidden ancestor: the overlay stops drawing, and past this
	// point so would the collectors, leaving a request nothing receives and therefore no feedback at all.
	// Alt+Q switch-object is the sharp case - it is the command you would reach for to ESCAPE that state,
	// and it needs neither geometry nor a camera to do it.
	//
	// The pointer comes from the HOST area rather than this overlay's gesture state, which stops being
	// written once the guard below fires.
	val overlays = LocalAreaOverlays.current
	LaunchedEffect(session, areaId, service, overlays) {
		collectEditGizmoRequests(areaId, session, service, liveGeometryState, liveStage, liveCamera, liveSize, areaPointer, onOverlapRequest, overlays)
	}

	if (liveGeometry.isEmpty()) {
		return
	}

	// The marquee (box + circle) machinery over mesh elements, one per area (see editMarquee).
	val marquee = remember(areaId) { editMarquee(session, liveGeometryState) }

	// The element pick and box select, armed or not, one per area (see MeshPickController).
	val meshPick = remember(areaId) { editMeshPick(session, marquee, liveGeometryState) }

	// The idle lattice the pointer is over in Pose mode, drawn in the hover color in this area alone, one per area
	// (see LatticeHover).
	val latticeHover = remember(areaId) { LatticeHover(areaId, service) { session.model.value } }

	// The modal transform's commit side, one per area: the pointer loop and the collectors below keep the
	// instance they started with (see EditModalTransform).  Its gesture state is what the Box, the pointer
	// loop, and the chrome read.
	val livePreview = LocalLiveMeshPreview.current
	val modalTransform = remember(areaId) { EditModalTransform(areaId, session, service::setModel, livePreview, service::setLatticePreview) }
	val gesture = modalTransform.gesture

	// The drive's worker, alive exactly as long as the transform: each pointer event submits a drive and the
	// result publishes back on the UI thread (see ModalDriveWorker).
	ModalDriveEffect(modalTransform.drive)

	// The unmount-mid-gesture guard: leaving Edit or Pose mode, closing the area, or every owner in the edit ceasing
	// to project disposes this part of the overlay mid-gesture, which cancels the latch effect below WITHOUT
	// running its teardown - the renderer would be left on the uncommitted preview, and the latch on an
	// overlay that no longer exists.  A select gesture in flight is dropped the same way, nothing of it
	// landing, and the gesture flag it raised comes down.  The lattice hover clears with it.
	DisposableEffect(modalTransform) {
		onDispose {
			if (modalTransform.abandon()) {
				service.resyncAfterElementGesture(session)
			}

			marquee.discard()
			meshPick.cancel()
			latticeHover.clear()
		}
	}

	// A tool change - armed, cleared by Escape / right-click, or switched between box and circle - resolves any
	// in-flight gesture (see MarqueeSelectController.cancel: the stroke commits, the box abandons). This clears
	// a cancelled circle stroke / box rubber-band that would otherwise linger as the drawn selection until the
	// next stroke reseeded it.
	//
	// This effect is recomposition-gated (the key must change, a recomposition must run, then the effect
	// relaunches), so it can lose the race to a mouse release still in flight - a cancelled armed box would
	// then commit through the idle Release path before this ran. The authoritative, race-free cancel is the
	// meshGestureCancelRequests signal below (fired by the shell alongside clearSelectTool); this effect is the
	// backstop for tool switches and leaving Edit, where no signal is sent.
	//
	// Keyed on the tool KIND (none / box / circle), not the whole tool value: resizing the circle brush makes
	// a new Circle(radius), which must NOT re-fire and wipe the stroke mid-paint.  The kind is derived from
	// the AREA-OWNED tool: re-arming the brush from another viewport keeps the raw kind unchanged, but this
	// area's stroke must still resolve - to its owner the tool went from live to absent.
	val ownedSelectTool = activeSelectTool?.takeIf { it.areaId == areaId }
	LaunchedEffect(selectToolKind(ownedSelectTool)) {
		marquee.cancel()
		meshPick.cancel()
	}

	// The race-free cancel path: an already-suspended collector that resumes before the mouse release, so the
	// box rubber-band is discarded (and any held circle stroke committed) before the Release could commit it.
	// The shell fires this for every Edit-mode select-gesture cancel - the non-armed box drag (which owns no
	// tool state to change) and, alongside clearSelectTool, the armed box / circle tools.
	LaunchedEffect(session) {
		session.meshGestureCancelRequests.collect {
			marquee.cancel()
			meshPick.cancel()
		}
	}

	// Enter confirms the modal gesture (mirroring a primary click); the shell routes the keypress here,
	// gated to the INITIATING area through the operator latch itself.
	LaunchedEffect(session) {
		collectModalConfirmRequests(session, { session.activeMeshOperator.value?.areaId == areaId }) {
			modalTransform.confirm()
		}
	}

	// A proportional toggle or falloff change MID-GESTURE re-derives the capture's weights (the
	// keyboard-side complement of the scroll resize; see EditModalTransform.reapplyProportional).
	LaunchedEffect(session) {
		session.proportionalEdit.collect { state ->
			modalTransform.reapplyProportional(state)
		}
	}

	// A depth surface cycled MID-GESTURE (T in the key ladder) re-drives at once, so the press shows without a pointer
	// move; with no gesture here the re-drive does nothing.
	LaunchedEffect(session) {
		session.depthSurface.collect {
			modalTransform.redrive(liveCamera.value, liveSize.value)
		}
	}

	// An axis lock toggled MID-GESTURE (X / Z in the key ladder) re-drives at once, so the lock shows - and a confirm
	// before the next pointer move lands it.
	LaunchedEffect(session) {
		collectAxisLockChanges(session, modalTransform, liveCamera, liveSize)
	}

	// Start the modal gesture as an operator latches IN THIS AREA; tear it down (re-syncing the renderer
	// to the committed model and lattices) as it clears.  liveGeometry and stage are the values this composition held
	// when the operator changed, and meshSelection is read as the effect runs, as the collected state has it.
	LaunchedEffect(activeOperator) {
		val operator = activeOperator?.takeIf { it.areaId == areaId }

		if (operator != null) {
			modalTransform.begin(operator.kind, liveGeometry, meshSelection, stage)
		} else {
			// Resync the renderer only when THIS overlay owned a gesture: the effect also runs its else
			// branch at mount (and when another area's operator latches), and an unguarded resync from
			// a viewport split open mid-gesture would stomp the initiating area's live preview.
			if (modalTransform.end()) {
				service.resyncAfterElementGesture(session)
			}
		}
	}

	// clipToBounds: Canvas drawing is not clipped to the layout bounds by default, so chrome reaching past
	// the area (a HUD line to an off-screen pivot) would otherwise paint over the AreaHeader and neighbouring
	// areas.
	Box(
		modifier =
			modifier
				.fillMaxSize()
				.clipToBounds()
				.onGloballyPositioned { coordinates -> gesture.areaScreenOrigin = coordinates.positionOnScreen() },
	) {
		// The gesture chrome (band, affordances, modal HUD) in a small layer of its own: it reads
		// gesture.lastPointer, which updates on every pointer event (hover included), so it redraws per move
		// and nothing else does.  The pointer loop sits on the same canvas; the mesh is in the image beneath.
		Canvas(
			modifier =
				Modifier
					.fillMaxSize()
					.graphicsLayer()
					.pointerInput(areaId) {
						editGizmoPointerLoop(areaId, session, modalTransform, marquee, meshPick, latticeHover, liveCamera, liveSize)
					}
					// While THIS AREA'S modal transform runs or its select tool is armed, hide the OS cursor so
					// only the overlay's drawn cursor (double-arrow, crosshair, or brush circle) shows; plain idle
					// Edit mode adds no icon, leaving the navigation layer's cursor to show through.  A gesture
					// owned by another viewport leaves this area's cursor alone.
					// The hidden cursor joins AFTER the pointer input, below the loop's node: armed above it, it would re-route
					// the hit path, and Compose delivers the next event to the loop down both the old and the new path.
					.then(
						if (activeOperator?.areaId == areaId || ownedSelectTool != null) {
							Modifier.pointerHoverIcon(hiddenPointerIcon(), overrideDescendants = true)
						} else {
							Modifier
						},
					),
		) {
			drawEditGizmoChrome(
				marquee = marquee,
				gesture = gesture,
				ownedSelectTool = ownedSelectTool,
				hudOperator = activeOperator,
				axisConstraint = axisConstraintState,
				proportionalEdit = proportionalEditState,
				session = session,
				camera = camera,
				size = IntSize(widthPx, heightPx),
				style = overlayStyle,
				lineColor = overlayColors.viewportMarquee,
			)
		}
	}
}