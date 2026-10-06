package org.umamo.ui.viewport.uv

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
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
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshSelection
import org.umamo.render.ViewportCamera
import org.umamo.ui.model.LocalPuppetRenderSync
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.hiddenPointerIcon
import org.umamo.ui.theme.selectionOverlayStyle
import org.umamo.ui.viewport.gizmo.GizmoMeshGeometry
import org.umamo.ui.viewport.gizmo.collectModalConfirmRequests
import org.umamo.ui.viewport.gizmo.meshMarquee
import org.umamo.ui.viewport.gizmo.selectToolKind

/*
 * The UV editor's Edit-mode gizmo overlay.  This file is the wiring: what the overlay collects, its guards,
 * what it holds per area and for how long, the effects in the order they launch, and the chrome layer it
 * draws.  The wireframe itself is the render service's, drawn into the area's frame from the scene the
 * host publishes (UvSceneOverlay.kt).  Its parts:
 *   - UvModalTransform.kt: the commit side both UV overlays share (the latch ownership rule, cancel, end,
 *     abandon).
 *   - UvEditModalTransform.kt: this overlay's modal G / S / R over texture coordinates (capture, drive,
 *     confirm, the wheel, the proportional radius) - the ModalTransformTarget the pointer loop hands a
 *     gesture's events to.
 *   - UvEditGizmoRequests.kt: the area-gated collectors for Mirror U / V, Select Linked, and the snaps, and
 *     the drop of a latch made over a surface with nothing to edit.
 *   - UvEditGizmoPointerInput.kt: the pointer loop (modal transform, circle brush, idle selection).
 *   - UvEditGizmoSelection.kt: the element pick; the marquee is the shared gizmo/GizmoSelectionInput.kt
 *     meshMarquee.
 *   - UvEditGizmoDraw.kt: the gesture chrome, read in the draw phase.
 * The UV cursor helpers both overlays use are in UvCursorOverlay.kt, the snap handler in
 * UvSessionRequestHandlers.kt, and the strip registration in UvTransformAdjustRegistration.kt.
 */

/**
 * The UV editor's gizmo overlay: the Edit-mode interaction core the UV space composes over its texture
 * underlay - the atlas page or the source layer's artwork the area is showing.  Self-gates to Edit mode
 * with a camera - the mode-exclusive sibling of [UvObjectGizmoOverlay], so the host mounts both
 * unconditionally (the viewport overlay pair's convention).
 * Draws the gesture chrome, runs the idle element selection (click pick with Shift/Ctrl toggle, empty-drag
 * box, sub-threshold-click clear, the circle brush, whose live stroke it writes to the host's
 * [circleStrokeState] for the area's published wireframe), and drives the
 * modal G / S / R operators over raw texture coordinates through the shared
 * [org.umamo.ui.viewport.gizmo.ModalTransformController] - the same pointer semantics as the viewport
 * overlays (stale discard, virtual pointer, cursor wrap, LMB-confirm / RMB-cancel), with no deformer inverse
 * involved: the transformed display arrays convert back through the shown surface's frame to the stored
 * coordinates on drive and commit.
 *
 * Live preview streams through [LocalPuppetRenderSync]: each pointer frame folds the transformed UVs into an
 * uncommitted model and pushes it to the puppet renderer, so the 2D viewport shows the art resampling as the
 * mapping moves, and every UV area's wireframe follows it through the host's geometry; confirm commits ONE
 * undo step via commitMeshUvs and the session's model bridge republishes the committed model.  Gating follows the area-ownership contract: the capture effect and pointer drive key
 * on the UV latch's own areaId, bystander areas stay inert, and teardown resyncs the raster only when this
 * overlay owned a gesture.
 *
 * @param String areaId The UV editor area this overlay covers.
 * @param EditorSession session The session owning the selection and the UV operator latch.
 * @param List<GizmoMeshGeometry> geometries The shown meshes' display-space gizmo geometry.
 * @param UvEditFrame frame The shown surface's texel size plus how a coordinate over it reaches the
 *   stored texture coordinates (an atlas page is the stored frame itself; a source layer is not).
 * @param ViewportCamera? camera The area camera; null hides the overlay (no fit has landed yet).
 * @param Int widthPx The area width in pixels.
 * @param Int heightPx The area height in pixels.
 * @param State areaPointer Where the pointer last was in this area, tracked by the HOST so the
 *   pointer-addressed requests still resolve while the overlay's own pointer loop is not mounted.
 * @param MutableState<Float?> proportionalRadiusDisplayState The host-owned proportional radius of the shown
 *   surface, in display (texel) units: a gesture begun here takes it, seeds it, and resizes it, and the
 *   host's UvHudOverlay badge reads it - sibling overlays share state only through the session or the host.
 * @param MutableState<MeshSelection?> circleStrokeState The host-owned live circle stroke of this area: the
 *   marquee writes each stamp and null as the stroke ends, and the host's scene publish reads it.  Never
 *   read here, so a stamp recomposes nothing.
 * @param Modifier modifier The layout modifier.
 */
@Composable
internal fun UvEditGizmoOverlay(
	areaId: String,
	session: EditorSession,
	geometries: List<GizmoMeshGeometry>,
	frame: UvEditFrame,
	camera: ViewportCamera?,
	widthPx: Int,
	heightPx: Int,
	areaPointer: State<Offset>,
	proportionalRadiusDisplayState: MutableState<Float?>,
	circleStrokeState: MutableState<MeshSelection?>,
	modifier: Modifier = Modifier,
) {
	val mode by session.mode.collectAsState()
	val activeOperator by session.activeUvOperator.collectAsState()
	val activeSelectTool by session.activeSelectTool.collectAsState()
	// Held as State, not read here: the chrome reads them only while drawing a gesture this area owns.
	val axisConstraintState = session.axisConstraint.collectAsState()
	val proportionalEditState = session.proportionalEdit.collectAsState()
	val renderSync = LocalPuppetRenderSync.current
	val overlayColors = LocalUmamoColors.current
	if (mode != EditorMode.Edit || camera == null) {
		return
	}
	val overlayStyle = selectionOverlayStyle(overlayColors)

	// Live values the long-running pointer loop, the per-area holders, and the effects read (they are keyed
	// only on areaId / session, so they must not close over a stale camera / size / geometry / frame / radius
	// when those change mid-edit - the host hands over a different radius state for each surface it shows).
	val liveCamera = rememberUpdatedState(camera)
	val liveSize = rememberUpdatedState(IntSize(widthPx, heightPx))
	val liveGeometries = rememberUpdatedState(geometries)
	val liveFrame = rememberUpdatedState(frame)
	val liveRenderSync = rememberUpdatedState(renderSync)
	val liveRadiusState = rememberUpdatedState(proportionalRadiusDisplayState)
	val liveStrokeState = rememberUpdatedState(circleStrokeState)

	// The keymap-command collectors, mounted above the EMPTY-SURFACE guard below but still inside the mode and
	// camera guard above it: this overlay is Edit-mode-only and so are these commands.  What they must outlive
	// is the empty-surface return - a shown page or layer holding none of the edit's meshes - so a request
	// there answers with a notice rather than with nothing at all.  The pointer comes from the HOST area,
	// since the overlay's own pointer loop is not mounted past that guard.
	LaunchedEffect(session, areaId) {
		collectUvEditGizmoRequests(areaId, session, liveGeometries, liveFrame, liveCamera, liveSize, areaPointer)
	}

	if (geometries.isEmpty()) {
		// Over a surface with nothing to edit, a G / S / R or a B / C gets what a request gets: the parts below
		// that would begin, drive, and resolve it are not mounted, so a latch made here, or a tool still armed
		// as the surface emptied, is dropped with the requests' notice rather than left holding the area's pan
		// and zoom off.
		LaunchedEffect(activeOperator, activeSelectTool) {
			dropUvEditLatchesWithNothingToEdit(areaId, session)
		}
		return
	}

	// The box-select and circle-select machinery over the shared session selection, one per area.  The live
	// stroke goes to the host, whose scene publish draws it over this area alone.
	val marquee =
		remember(areaId) {
			meshMarquee(session, { liveGeometries.value }, previewStroke = { stroke -> liveStrokeState.value.value = stroke })
		}

	// The element pick and box select, armed or not, one per area (see uvEditMeshPick).
	val meshPick = remember(areaId) { uvEditMeshPick(session, marquee, liveGeometries, liveFrame) }

	// The modal transform's commit side, one per area: the pointer loop and the collectors below keep the
	// instance they started with (see UvEditModalTransform).  Its gesture state is what the Box, the pointer
	// loop, and the chrome read.
	val modalTransform =
		remember(areaId) {
			UvEditModalTransform(areaId, session, liveRadiusState) { folded -> liveRenderSync.value?.previewModel(folded) }
		}
	val gesture = modalTransform.gesture

	// The unmount-mid-gesture guard: leaving Edit mode, closing the area, losing the camera, or the shown
	// surface ceasing to hold any of the edit's meshes disposes this part of the overlay mid-gesture, which
	// cancels the latch effect below WITHOUT running its teardown - the renderer would be left on the
	// uncommitted preview, and the latch on an overlay that no longer exists.  A select gesture in flight is
	// dropped the same way, nothing of it landing, and the gesture flag it raised comes down.
	DisposableEffect(modalTransform) {
		onDispose {
			if (modalTransform.abandon()) {
				liveRenderSync.value?.resync()
			}
			marquee.discard()
			meshPick.cancel()
		}
	}

	// A tool change - armed, cleared by Escape / right-click, or switched between box and circle - resolves
	// any in-flight marquee gesture (the stroke commits, the box abandons).  Keyed on the tool KIND derived
	// from the AREA-OWNED tool, exactly like the viewport's Edit overlay: a brush resize makes a new
	// Circle(radius) that must not wipe the stroke, and a re-arm from another area reads as live-to-absent
	// here.  The race-free cancel path is the request collector below; this is the backstop for tool switches
	// and unmounts, where no signal is sent.
	val ownedSelectTool = activeSelectTool?.takeIf { tool -> tool.areaId == areaId }
	LaunchedEffect(selectToolKind(ownedSelectTool)) {
		marquee.cancel()
		meshPick.cancel()
	}

	// The race-free box cancel: the shell fires this for every Edit-mode select-gesture cancel, and an
	// already-suspended collector resumes before the mouse release could commit a cancelled box.
	LaunchedEffect(session) {
		session.meshGestureCancelRequests.collect {
			marquee.cancel()
			meshPick.cancel()
		}
	}

	// Enter confirms the modal gesture (mirroring a primary click), gated to the INITIATING area through the
	// UV latch itself - the mesh and object latches are mutually exclusive with it, so a viewport overlay's
	// confirm can never double-commit with this one.
	LaunchedEffect(session) {
		collectModalConfirmRequests(session, { session.activeUvOperator.value?.areaId == areaId }) {
			modalTransform.confirm()
		}
	}

	// A proportional toggle or falloff change MID-GESTURE re-derives the capture's weights from its frozen
	// originals (the keyboard-side complement of the scroll resize).
	LaunchedEffect(session) {
		session.proportionalEdit.collect { state ->
			modalTransform.reapplyProportional(state)
		}
	}

	// Start the modal gesture as a UV operator latches IN THIS AREA; tear it down (resyncing the renderer to
	// the committed model) as it clears.  The selection, the shown geometry, and the frame are read as the
	// effect runs.
	LaunchedEffect(activeOperator) {
		val operator = activeOperator?.takeIf { latched -> latched.areaId == areaId }
		if (operator != null) {
			modalTransform.begin(operator.kind, liveGeometries.value, session.meshSelection.value, liveFrame.value)
		} else {
			// Resync the renderer only when THIS overlay owned a gesture: the else branch also runs at mount and
			// when another area's operator latches, and an unguarded resync from a bystander would stomp the
			// initiating area's live preview (the ownership doc's mid-gesture-split guard).
			if (modalTransform.end()) {
				liveRenderSync.value?.resync()
			}
		}
	}

	// clipToBounds: Canvas drawing is not clipped to the layout bounds by default, so chrome near an edge (the
	// HUD's axis line, the brush circle) would otherwise paint over the AreaHeader and neighboring areas.
	Box(
		modifier =
			modifier
				.fillMaxSize()
				.clipToBounds()
				.onGloballyPositioned { coordinates -> gesture.areaScreenOrigin = coordinates.positionOnScreen() }
				// While THIS AREA'S modal transform runs or its select tool is armed, hide the OS cursor so only
				// the overlay's drawn cursor (double-arrow, crosshair, or brush circle) shows; a gesture owned by
				// another area leaves this cursor alone.
				.then(
					if (activeOperator?.areaId == areaId || ownedSelectTool != null) {
						Modifier.pointerHoverIcon(hiddenPointerIcon(), overrideDescendants = true)
					} else {
						Modifier
					},
				),
	) {
		// The gesture chrome (band, affordances, modal HUD) in a layer of its own, which also takes the pointer:
		// it reads gesture.lastPointer, which updates on every pointer event (hover included), so this small
		// layer redraws per move and nothing else does.
		Canvas(
			modifier =
				Modifier
					.fillMaxSize()
					.graphicsLayer()
					.pointerInput(areaId) {
						uvEditGizmoPointerLoop(areaId, session, modalTransform, marquee, meshPick, liveCamera, liveSize)
					},
		) {
			drawUvEditGizmoChrome(
				marquee = marquee,
				gesture = gesture,
				ownedSelectTool = ownedSelectTool,
				hudOperator = activeOperator,
				axisConstraint = axisConstraintState,
				proportionalEdit = proportionalEditState,
				camera = camera,
				size = IntSize(widthPx, heightPx),
				style = overlayStyle,
				lineColor = overlayColors.viewportMarquee,
			)
		}
	}
}