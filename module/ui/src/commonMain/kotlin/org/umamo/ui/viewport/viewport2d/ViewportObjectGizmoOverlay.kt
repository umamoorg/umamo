package org.umamo.ui.viewport.viewport2d

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshTransforms
import org.umamo.render.ViewportCamera
import org.umamo.render.pick.PickCandidate
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.hiddenPointerIcon
import org.umamo.ui.theme.selectionOverlayStyle
import org.umamo.ui.viewport.PuppetViewportService
import org.umamo.ui.viewport.gizmo.ModalDriveEffect
import org.umamo.ui.viewport.gizmo.applyOperator
import org.umamo.ui.viewport.gizmo.collectModalConfirmRequests
import org.umamo.ui.viewport.gizmo.screenToWorld
import org.umamo.ui.viewport.gizmo.selectToolKind
import org.umamo.ui.viewport.gizmo.worldToScreen

/*
 * The Object-mode gizmo overlay.  This file is the wiring: what the overlay collects, its guard, what it
 * holds per area and for how long, the effects in the order they launch, and the chrome it draws.  Its
 * parts:
 *   - ObjectModalTransform.kt: the commit side of the modal G / S / R over whole drawables (capture, drive,
 *     confirm, cancel, abandon) - the ModalTransformTarget the pointer loop hands a gesture's events to.
 *   - ObjectGizmoSelection.kt: the selection anchors box and circle read (today each drawable's centroid),
 *     the pure circle-stamp and box functions, and this viewport's marquee and pick controller.
 *   - ObjectGizmoPointerInput.kt: the pointer loop (modal transform, circle brush, armed box, idle pick).
 *   - ObjectGizmoDraw.kt: the gesture chrome, read in the draw phase.
 *   - ObjectGizmoRequests.kt: the area-gated collector for the Shift+S snaps.
 * The shared pick and marquee flows are in gizmo/ObjectPickController.kt and gizmo/MarqueeSelectController.kt;
 * the snap handler is in SessionRequestHandlers.kt, and the strip registration in
 * TransformAdjustRegistration.kt.
 */

/**
 * The Object-mode gizmo overlay: the Object-mode counterpart to [ViewportEditGizmoOverlay], driving whole-drawable
 * selection and transforms over the puppet image.  Composed whenever Object mode is active with a camera.
 * Gated to Object mode (returns in Edit, where [ViewportEditGizmoOverlay] owns the viewport), so the two overlays
 * are mutually exclusive by mode and never both drive the pointer.  Middle-drag pan and wheel zoom are
 * never consumed here, so they fall through to the navigation layer beneath.
 *
 * Four gestures, mirroring Edit mode but over whole drawables rather than mesh elements:
 *   - Click pick (idle): the primary button picks the front-most drawable under the cursor on release
 *     (plain replaces, Shift / Ctrl toggles membership, an Alt click opens the overlap picker for stacked
 *     meshes, an unmodified click on empty canvas clears).
 *   - Box select: a primary drag rubber-bands and selects every drawable whose world centroid is enclosed
 *     (Shift adds to the current selection).  Works un-armed (a drag from empty canvas, Blender's default
 *     drag) and armed (Blender's B, which skips the pick and always boxes, then disarms).  Escape or a
 *     right-click abandons an in-flight drag.
 *   - Circle select (Blender's C): a brush paints drawables by centroid - a primary drag adds, a middle or
 *     Shift+primary drag erases; the stroke accumulates into a working selection committed once on release
 *     (one undo step), previewed live through the GPU tint via [SessionToolLatches.setPreviewSelection]; the wheel
 *     resizes the brush; a right-click leaves the tool keeping what was painted.
 *   - Grab / Scale / Rotate: a modal transform of every selected drawable's whole geometry about their combined
 *     centroid, previewed straight to the renderer and committed as one undo step ([MeshChange.TransformDrawables]).
 *     A right-click or Escape cancels (the renderer re-syncs to the committed model); a primary click or Enter
 *     confirms.  Only drawables transform - a selection with nothing transformable blocks at the session
 *     guard ([EditorSession.beginObjectOperator]) before an operator ever latches here.
 *
 * The transform math ([applyOperator], [MeshTransforms], the shared world->base round trip), the cursor-wrap
 * scheme, and the screen projection ([worldToScreen] / [screenToWorld]) are shared verbatim with the Edit
 * gizmo; only the capture's per-drawable geometry and the selection domain (whole drawables) differ.
 *
 * @param String areaId The viewport area this overlay covers.
 * @param PuppetViewportService service The render service (picking, live preview pushes, centroids).
 * @param EditorSession session The session owning the model, object selection, and active tool / operator.
 * @param ViewportCamera? camera The area's camera (world<->screen affine); null hides the overlay.
 * @param Int widthPx The area width in pixels.
 * @param Int heightPx The area height in pixels.
 * @param Function onOverlapRequest Opens the overlap-picker popup for an Alt-click with 2+ candidates.
 * @param Modifier modifier The layout modifier.
 */
@Composable
fun ViewportObjectGizmoOverlay(
	areaId: String,
	service: PuppetViewportService,
	session: EditorSession,
	camera: ViewportCamera?,
	widthPx: Int,
	heightPx: Int,
	onOverlapRequest: (Offset, List<PickCandidate>) -> Unit,
	modifier: Modifier = Modifier,
) {
	val mode by session.mode.collectAsState()
	val activeSelectTool by session.activeSelectTool.collectAsState()
	val activeObjectOperator by session.activeObjectOperator.collectAsState()
	// Held as State, not read here: the chrome reads it only while drawing a gesture this area owns.
	val axisConstraintState = session.axisConstraint.collectAsState()
	val overlayColors = LocalUmamoColors.current

	if (mode != EditorMode.Object || camera == null) {
		return
	}
	// Armed = a select tool or an object operator LATCHED IN THIS AREA owns the pointer; only then does the
	// overlay hide the OS cursor.  Idle is not inert: the loop still owns the click pick and the un-armed
	// box, consuming only primary-driven events, so middle-drag pan and wheel zoom fall through to the
	// navigation layer.  A gesture owned by another viewport leaves this area idle and inert.
	val ownedSelectTool = activeSelectTool?.takeIf { it.areaId == areaId }
	val armed = ownedSelectTool != null || activeObjectOperator?.areaId == areaId
	val overlayStyle = selectionOverlayStyle(overlayColors)

	// Live values the areaId-keyed pointer loop reads, so a pan / resize mid-gesture is seen without re-keying.
	val liveCamera = rememberUpdatedState(camera)
	val liveSize = rememberUpdatedState(IntSize(widthPx, heightPx))

	// The world points box and circle selection test against, one holder per area that the marquee, the pick
	// controller, and the armed box all refresh and read (see ObjectSelectionAnchors).
	val anchors = remember(areaId) { ObjectSelectionAnchors { service.drawableWorldCentroids() } }

	// The modal transform's commit side, one per area: the pointer loop and the collectors below keep the
	// instance they started with (see ObjectModalTransform).  Its gesture state is what the Box, the pointer
	// loop, and the HUD read.
	val modalTransform = remember(areaId) { ObjectModalTransform(areaId, session, service::setModel) }
	val gesture = modalTransform.gesture

	// The drive's worker, alive exactly as long as the transform: each pointer event submits a drive and the
	// result publishes back on the UI thread (see ModalDriveWorker).
	ModalDriveEffect(modalTransform.drive)

	// The marquee (box + circle) machinery over whole drawables, one per area (see viewportObjectMarquee).
	val marquee = remember(areaId) { viewportObjectMarquee(session, anchors) }

	// The idle click-pick / un-armed box flow and the armed box over whole drawables, one per area (see
	// viewportObjectPick and ObjectPickController).
	val objectPick = remember(areaId) { viewportObjectPick(areaId, session, service, marquee, anchors, onOverlapRequest) }

	// The unmount-mid-gesture guard: leaving Object mode or closing the area disposes this part of the overlay
	// mid-gesture, which cancels the latch effect below WITHOUT running its teardown - the renderer would be
	// left on the uncommitted preview, and the latch on an overlay that no longer exists.  A select gesture in
	// flight is dropped the same way, nothing of it landing: a box abandons and lowers the gesture flag it
	// raised, and a stroke goes uncommitted with its tint preview taken down.
	DisposableEffect(modalTransform) {
		onDispose {
			if (modalTransform.abandon()) {
				service.setModel(session.model.value)
			}
			marquee.discard()
			objectPick.cancel()
		}
	}

	// Escape resolves the in-flight select gesture: the shell routes the key through the session's cancel
	// signal (the gesture state lives in the marquee controller, which the session cannot reach directly).
	// A circle stroke keeps its paint (see MarqueeSelectController.cancel); an un-armed box drag abandons.
	LaunchedEffect(session) {
		session.meshGestureCancelRequests.collect {
			marquee.cancel()
			objectPick.cancel()
		}
	}

	// The recomposition-gated tool-switch backstop the Edit and UV overlays already carry: an armed tool that
	// is cleared or switched (box<->circle) resolves any in-flight marquee.  Keyed on the tool KIND (not the
	// whole value) so a circle-brush resize does not re-fire and wipe the stroke mid-paint.  Object's body
	// mirrors its own cancel collector above (marquee plus the idle box), since it - unlike Edit / UV - also
	// carries an un-armed box drag; the pointer loop's own idle-box cancel and this one are order-independent.
	LaunchedEffect(selectToolKind(ownedSelectTool)) {
		marquee.cancel()
		objectPick.cancel()
	}

	// Seed / tear down the transform capture as the operator latches / clears (see ObjectModalTransform.begin).
	// On clear (confirm or cancel), re-sync the renderer to the committed model, discarding any throwaway
	// preview the drive loop pushed.
	LaunchedEffect(activeObjectOperator) {
		val operator = activeObjectOperator?.takeIf { it.areaId == areaId }
		if (operator != null) {
			modalTransform.begin(operator.kind)
		} else {
			// Resync the renderer only when THIS overlay owned a gesture: the effect also runs its else
			// branch at mount (and when another area's operator latches), and an unguarded setModel from
			// a viewport split open mid-gesture would stomp the initiating area's live preview.
			if (modalTransform.end()) {
				service.setModel(session.model.value)
			}
		}
	}

	// Enter confirms the modal object gesture (mirroring a primary click); the shell routes the keypress
	// here, gated to the INITIATING area through the operator latch itself.
	LaunchedEffect(session) {
		collectModalConfirmRequests(session, { session.activeObjectOperator.value?.areaId == areaId }) {
			modalTransform.confirm()
		}
	}

	// The geometry-dependent Shift+S snaps for Object mode over the selected drawables' centroids.
	// Only the pointer's own area executes: every open 2D viewport composes this collector, and an
	// ungated request would commit once per viewport.
	// The handler ignores the area - a snap acts on the model - so the payload's id is purely the election.
	LaunchedEffect(session) {
		collectObjectGizmoRequests(areaId, session)
	}

	Box(
		modifier =
			modifier
				.fillMaxSize()
				.clipToBounds()
				.onGloballyPositioned { coordinates -> gesture.areaScreenOrigin = coordinates.positionOnScreen() }
				.then(
					if (armed) {
						Modifier.pointerHoverIcon(hiddenPointerIcon(), overrideDescendants = true)
					} else {
						Modifier
					},
				)
				.pointerInput(areaId) {
					objectGizmoPointerLoop(areaId, session, modalTransform, marquee, objectPick, liveCamera, liveSize)
				},
	) {
		Canvas(modifier = Modifier.fillMaxSize()) {
			drawObjectGizmoChrome(
				marquee = marquee,
				gesture = gesture,
				ownedSelectTool = ownedSelectTool,
				hudOperator = activeObjectOperator,
				axisConstraint = axisConstraintState,
				camera = camera,
				size = IntSize(widthPx, heightPx),
				style = overlayStyle,
				lineColor = overlayColors.viewportMarquee,
			)
		}
	}
}