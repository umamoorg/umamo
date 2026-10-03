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
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.hiddenPointerIcon
import org.umamo.ui.theme.selectionOverlayStyle
import org.umamo.ui.viewport.PuppetViewportService
import org.umamo.ui.viewport.gizmo.collectModalConfirmRequests
import org.umamo.ui.viewport.gizmo.selectToolKind
import org.umamo.ui.viewport.rememberViewportOverlayColors

/*
 * The Edit-mode gizmo overlay.  This file is the wiring: what the overlay collects, its guards, what it
 * holds per area and for how long, the effects in the order they launch, and the two layers it draws.
 * Its parts:
 *   - EditModalTransform.kt: the commit side of the modal G / S / R and Vertex Slide (capture, drive,
 *     confirm, cancel, the wheel) - the ModalTransformTarget the pointer loop hands a gesture's events to.
 *   - VertexSlide.kt: the slide's frozen candidates and the per-frame edge pick.
 *   - FrameMeshGeometry.kt: the displayed frame's geometry the wireframes pose from, reused per drawable.
 *   - EditGizmoRequests.kt: the area-gated collectors for Select Linked, Alt+Q, Rip, and the snaps.
 *   - EditGizmoPointerInput.kt: the pointer loop (modal transform, circle brush, idle selection).
 *   - EditGizmoSelection.kt: the marquee over mesh elements and the derived highlights.
 *   - EditGizmoDraw.kt: the wireframe pass and the gesture chrome, both read in the draw phase.
 *   - EditMeshGeometry.kt: the session meshes' live geometry at the neutral pose.
 * The collectors' handlers are in SessionRequestHandlers.kt, and the strip registrations in
 * TransformAdjustRegistration.kt.
 */

/**
 * The Edit-mode gizmo overlay: a Compose layer over the offscreen puppet image that draws the active
 * drawable's mesh (vertices, edges, and faces of its rest shape) and runs the modal G / S / R operators.
 * It is gated on Edit mode with an active drawable; in Object mode nothing is composed, so pointer input
 * flows untouched to the viewport navigation beneath.
 *
 * Selection follows Blender's select modes (vertex / edge / face, switched by the mesh.selectMode
 * commands): only the current mode's domain is clickable and stored, while the other domains highlight by
 * derivation - an edge lights up when both endpoints are selected, a face only when all three of its own
 * vertices (vertex mode) or edges (edge mode) are. The gizmo palette comes from the viewport.meshEdit
 * settings, so the colors follow the user's preferences live.
 *
 * Edit mode edits the neutral state of the base mesh, and only that - it is pinned to the neutral pose
 * (the render bridge feeds the renderer neutral parameters while the mode is active, and the parameter
 * panel is locked), so the session pose is never touched and blend-shape states are out of scope. The
 * shape shown is the rest shape as rendered - base + the neutral keyform blend, since real rigs park
 * parts elsewhere on the texture sheet and place them via those deltas - projected to world through the
 * composed parent-deformer chain ([drawableSpaceMapping]), so the wireframe sits on the art for warp and
 * rotation children too. A drag commits by movement transfer, writing only the drawable's rest arrays: the
 * base (DrawableMesh.localPositions) as `newBase = base + (displayed' - displayed)`, and the canvas mesh
 * (DrawableMesh.positions) by the same movement on the canvas; the neutral blend cancels out of the
 * subtraction, so no keyform cell resolution is involved, and blend-shape deltas (stored relative to the
 * base) follow the edit.
 *
 * Interaction mirrors Blender. Idle: a primary click selects the element under the cursor per the select
 * mode (Ctrl toggles, Shift adds), an empty primary drag rubber-bands a box, an empty click clears;
 * middle-drag pan and wheel zoom fall through (left unconsumed) to the navigation layer. Modal (an
 * operator latched on the session by a G / S / R command): pointer movement drives the transform live over
 * a copy-on-write working array covering the vertices the selected elements span, pushed to the renderer;
 * the wireframe itself is drawn from the model the displayed frame was rendered from ([frameModel]), so it
 * lags together with the textured raster instead of leading it. A primary click or Enter confirms (one undo
 * step), Esc or right-click cancels. While modal, all pointer input is swallowed.
 *
 * @param String areaId The viewport area this overlay covers.
 * @param PuppetViewportService service The render service (for live preview pushes).
 * @param EditorSession session The session owning the model, element selection, and active operator.
 * @param ViewportCamera? camera The camera the displayed frame was rendered at (world<->screen affine);
 *        null hides the overlay.
 * @param PuppetModel? frameModel The model the displayed frame's pixels reflect; the wireframe poses from
 *        it (not the live session model) so it stays glued to the raster during an edit. Null hides the
 *        overlay (no frame has landed yet - nothing to glue to).
 * @param Int widthPx The area width in pixels.
 * @param Int heightPx The area height in pixels.
 * @param State areaPointer Where the pointer last was in this area, tracked by the HOST so the
 *        pointer-addressed commands still resolve while this overlay is unmounted.
 * @param Function onOverlapRequest Opens the overlap-picker popup for an Alt+Q over 2+ stacked
 *        candidates (the pick switches the edited mesh).
 * @param Modifier modifier The layout modifier.
 */
@Composable
fun ViewportEditGizmoOverlay(
	areaId: String,
	service: PuppetViewportService,
	session: EditorSession,
	camera: ViewportCamera?,
	frameModel: PuppetModel?,
	widthPx: Int,
	heightPx: Int,
	areaPointer: State<Offset>,
	onOverlapRequest: (Offset, List<PickCandidate>) -> Unit,
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
	val viewportOverlayColors = rememberViewportOverlayColors()
	// Theme-level overlay chrome (the marquee) comes from the palette; the mesh gizmo colors above stay a
	// separate settings-backed system.
	val overlayColors = LocalUmamoColors.current

	val sessionDrawableIds = meshSelection.drawableIds

	if (mode != EditorMode.Edit || sessionDrawableIds.isEmpty() || camera == null || frameModel == null) {
		return
	}
	// The shared two-tone marching-ants style for the box / circle / crosshair affordances.
	val overlayStyle = selectionOverlayStyle(overlayColors)

	// Per-session-mesh geometry at the constant neutral pose Edit mode is pinned to.  Keyed on the model
	// (a commit swaps it) and the session's mesh list; NOT per rendered frame.
	val liveGeometry = remember(model, sessionDrawableIds) { editMeshGeometries(model, sessionDrawableIds) }

	// Live values the long-running pointer loop, the marquee callbacks, and the request collectors read
	// (they are keyed only on areaId / session, so they must not close over a stale camera / size / shape
	// / topology when the model, pan, or resize change mid-edit).
	val liveCamera = rememberUpdatedState(camera)
	val liveSize = rememberUpdatedState(IntSize(widthPx, heightPx))
	val liveGeometryState = rememberUpdatedState(liveGeometry)

	// The keymap-command collectors, mounted above the EMPTY-GEOMETRY guard below but still inside every
	// guard above it: this overlay is Edit-mode-only and so are these commands, so there is nothing to
	// gain by outliving the mode / camera / frame checks, and a second collector live in Object mode
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
	LaunchedEffect(session, areaId, service) {
		collectEditGizmoRequests(areaId, session, service, liveGeometryState, liveCamera, liveSize, areaPointer, onOverlapRequest)
	}

	if (liveGeometry.isEmpty()) {
		return
	}

	// The marquee (box + circle) machinery over mesh elements, one per area (see editMarquee).
	val marquee = remember(areaId) { editMarquee(session, liveGeometryState) }

	// The element pick and box select, armed or not, one per area (see MeshPickController).
	val meshPick = remember(areaId) { editMeshPick(session, marquee, liveGeometryState) }

	// What the draw pass reflects: the live stroke while one is in flight, else the committed selection - so
	// painted elements light up immediately during a Circle stroke.
	val effectiveSelection = marquee.circleStroke ?: meshSelection

	// What the draw pass highlights per mesh and domain (see editHighlights).
	val highlightByDrawable = remember(effectiveSelection, liveGeometry) { editHighlights(effectiveSelection, liveGeometry) }

	// The DISPLAYED frame's geometry per session mesh, which the wireframes pose from so they lag together
	// with the raster (see rememberFrameMeshGeometries).
	val frameGeometryByDrawable = rememberFrameMeshGeometries(frameModel, liveGeometry)

	// The modal transform's commit side, one per area: the pointer loop and the collectors below keep the
	// instance they started with (see EditModalTransform).  Its gesture state is what the Box, the pointer
	// loop, and the chrome read.
	val modalTransform = remember(areaId) { EditModalTransform(areaId, session, service::setModel) }
	val gesture = modalTransform.gesture

	// The unmount-mid-gesture guard: leaving Edit mode, closing the area, or every mesh in the edit ceasing
	// to project disposes this part of the overlay mid-gesture, which cancels the latch effect below WITHOUT
	// running its teardown - the renderer would be left on the uncommitted preview, and the latch on an
	// overlay that no longer exists.  A select gesture in flight is dropped the same way, nothing of it
	// landing, and the gesture flag it raised comes down.
	DisposableEffect(modalTransform) {
		onDispose {
			if (modalTransform.abandon()) {
				service.setModel(session.model.value)
			}
			marquee.discard()
			meshPick.cancel()
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

	// Start the modal gesture as an operator latches IN THIS AREA; tear it down (re-syncing the renderer
	// to the committed model) as it clears.  liveGeometry is the value this composition held when the
	// operator changed, and meshSelection is read as the effect runs, as the collected state has it.
	LaunchedEffect(activeOperator) {
		val operator = activeOperator?.takeIf { it.areaId == areaId }
		if (operator != null) {
			modalTransform.begin(operator.kind, liveGeometry, meshSelection)
		} else {
			// Resync the renderer only when THIS overlay owned a gesture: the effect also runs its else
			// branch at mount (and when another area's operator latches), and an unguarded setModel from
			// a viewport split open mid-gesture would stomp the initiating area's live preview.
			if (modalTransform.end()) {
				service.setModel(session.model.value)
			}
		}
	}

	// clipToBounds: Canvas drawing is not clipped to the layout bounds by default, so an off-screen vertex
	// would otherwise paint over the AreaHeader and neighbouring areas.
	Box(
		modifier =
			modifier
				.fillMaxSize()
				.clipToBounds()
				.onGloballyPositioned { coordinates -> gesture.areaScreenOrigin = coordinates.positionOnScreen() }
				// While THIS AREA'S modal transform runs or its select tool is armed, hide the OS cursor so
				// only the overlay's drawn cursor (double-arrow, crosshair, or brush circle) shows; plain idle
				// Edit mode adds no icon, leaving the navigation layer's cursor to show through.  A gesture
				// owned by another viewport leaves this area's cursor alone.
				.then(
					if (activeOperator?.areaId == areaId || ownedSelectTool != null) {
						Modifier.pointerHoverIcon(hiddenPointerIcon(), overrideDescendants = true)
					} else {
						Modifier
					},
				),
	) {
		// Two sibling canvases, each in its OWN layer: a draw-state invalidation re-records every draw
		// lambda sharing a layer, so the gesture chrome below (band, affordances, modal HUD) lives in
		// a small layer of its own and the wireframes - the expensive pass - stay cached in this one.
		// The chrome reads gesture.lastPointer, which updates on every pointer event (hover included),
		// so its layer redraws per move; this layer re-records only when the frame geometry, the
		// selection, or a live modal preview changes - and it composites OFFSCREEN, because a default
		// layer retains a display list that every window repaint replays (re-stroking every edge),
		// where the offscreen buffer rasterizes once per content change and blits per frame.
		Canvas(
			modifier =
				Modifier
					.fillMaxSize()
					.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
					.pointerInput(areaId) {
						editGizmoPointerLoop(areaId, session, modalTransform, marquee, meshPick, liveCamera, liveSize)
					},
		) {
			// The live Circle stroke drives the highlighted domain so painted elements light up mid-stroke.
			drawEditWireframes(
				geometries = liveGeometry,
				highlightByDrawable = highlightByDrawable,
				frameGeometryByDrawable = frameGeometryByDrawable,
				selectMode = effectiveSelection.selectMode,
				colors = viewportOverlayColors,
				camera = camera,
				size = IntSize(widthPx, heightPx),
			)
		}
		Canvas(modifier = Modifier.fillMaxSize().graphicsLayer()) {
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