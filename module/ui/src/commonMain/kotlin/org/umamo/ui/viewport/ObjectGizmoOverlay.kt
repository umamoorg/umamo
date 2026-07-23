package org.umamo.ui.viewport

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.ActiveSelectTool
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshChange
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.MeshTransforms
import org.umamo.edit.RotationAngleTracker
import org.umamo.edit.Selection
import org.umamo.edit.SelectionOps
import org.umamo.edit.SelectionTarget
import org.umamo.edit.TransformPivotGroup
import org.umamo.edit.TransformPivotMode
import org.umamo.edit.TransformPivots
import org.umamo.edit.eligibleTransformDrawables
import org.umamo.edit.selectableOf
import org.umamo.edit.withMeshPositions
import org.umamo.render.ViewportCamera
import org.umamo.render.pick.PickCandidate
import org.umamo.render.pick.drawablesInBox
import org.umamo.render.pick.drawablesInCircle
import org.umamo.runtime.model.DrawableId
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoCursors
import org.umamo.ui.theme.hiddenPointerIcon
import org.umamo.ui.transform.DrawableWorldGeometry
import org.umamo.ui.transform.captureDrawableWorld
import kotlin.math.max
import kotlin.math.min

/**
 * The captured state of an in-flight Object-mode transform, frozen when the operator latches so the whole
 * drag is atomic across N drawables.  Each list is parallel to [drawableIds]: [mappings] projects a
 * drawable's local rest shape to world (and back), [displayed] is the local posed shape the movement is
 * measured from, [world] is that shape in world space (what [applyOperator] transforms), [base] is the rest
 * mesh the movement lands on, and [indices] is every vertex of that drawable (object transforms move the
 * whole mesh).  [groups] carries each drawable's pivot groups per the active
 * [org.umamo.edit.TransformPivotMode] (a shared anchor, or the drawable's own centroid for Individual
 * Origins); [anchor] is the world-space point the pointer math measures factors and angles against.
 */
private class ObjectGestureCapture(
	val geometries: List<DrawableWorldGeometry>,
	val indices: List<Set<Int>>,
	val groups: List<List<TransformPivotGroup>>,
	val anchor: Pair<Float, Float>,
) {
	/** The captured drawables, in capture order (the key every per-index list is aligned to). */
	val drawableIds: List<DrawableId> get() = geometries.map { geometry -> geometry.drawableId }

	/** The Rotate gesture's angle accumulator (unwrapped per-move increments; see RotationAngleTracker). */
	val rotationTracker = RotationAngleTracker()
}

/**
 * The Object-mode gizmo overlay: the Object-mode counterpart to [EditGizmoOverlay], driving whole-drawable
 * selection and transforms over the puppet image.  Composed whenever Object mode is active with a camera.
 * Gated to Object mode (returns in Edit, where [EditGizmoOverlay] owns the viewport), so the two overlays
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
 *     (one undo step), previewed live through the GPU tint via [EditorSession.setPreviewSelection]; the wheel
 *     resizes the brush; a right-click leaves the tool keeping what was painted.
 *   - Grab / Scale / Rotate: a modal transform of every selected drawable's whole geometry about their combined
 *     centroid, previewed straight to the renderer and committed as one undo step ([MeshChange.MoveDrawables]).
 *     A right-click or Escape cancels (the renderer re-syncs to the committed model); a primary click or Enter
 *     confirms.  Only drawables transform - a selection with nothing transformable blocks at the session
 *     guard ([EditorSession.beginObjectOperator]) before an operator ever latches here.
 *
 * The transform math ([applyOperator], [MeshTransforms], [movementToBase]), the cursor-wrap scheme, and the
 * screen projection ([worldToScreen] / [screenToWorld]) are shared verbatim with the Edit gizmo; only the
 * capture (N drawables, each with its own space mapping) and the selection domain (whole drawables) differ.
 *
 * オブジェクトモードのギズモ重畳。描画メッシュ全体の選択（クリック・ボックス・サークル）と G / S / R
 * 変形を駆動する。編集モードのギズモのオブジェクト版で、モードで相互排他。
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
fun ObjectGizmoOverlay(
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
	val axisConstraint by session.axisConstraint.collectAsState()
	val overlayColors = LocalUmamoColors.current

	if (mode != EditorMode.Object || camera == null) {
		return
	}
	// Armed = a select tool or an object operator LATCHED IN THIS AREA owns the pointer; only then does the
	// overlay hide the OS cursor and consume input.  When idle the loop still runs (to keep the operator
	// teardown effect mounted) but consumes nothing, so a plain click falls through to the navigation
	// layer's object picker.  A gesture owned by another viewport leaves this area idle and inert.
	val ownedSelectTool = activeSelectTool?.takeIf { it.areaId == areaId }
	val armed = ownedSelectTool != null || activeObjectOperator?.areaId == areaId
	val overlayStyle = selectionOverlayStyle(overlayColors)

	// Live values the areaId-keyed pointer loop reads, so a pan / resize mid-gesture is seen without re-keying.
	val liveCamera = rememberUpdatedState(camera)
	val liveSize = rememberUpdatedState(IntSize(widthPx, heightPx))

	var lastPointer by remember(areaId) { mutableStateOf(Offset.Zero) }
	// idleBoxing marks a non-armed box drag (started from a plain primary press rather than Blender's B),
	// whose sub-threshold release is a click pick; the rubber-band itself lives in the marquee controller.
	var idleBoxing by remember(areaId) { mutableStateOf(false) }
	// Per-drawable world centroids, snapshotted at the press that starts a select gesture (the pose is fixed for
	// the whole drag, so one snapshot serves every move) and tested against the region each frame.
	var cachedCentroids by remember(areaId) { mutableStateOf<Map<DrawableId, FloatArray>>(emptyMap()) }

	// Object-transform gesture capture (frozen when the operator latches) and its live preview: the new base
	// positions per drawable that the confirm commits, and that each move folds into the renderer's preview model.
	var capture by remember(areaId) { mutableStateOf<ObjectGestureCapture?>(null) }
	var preview by remember(areaId) { mutableStateOf<Map<DrawableId, FloatArray>?>(null) }
	var gestureStart by remember(areaId) { mutableStateOf<Offset?>(null) }
	var areaScreenOrigin by remember(areaId) { mutableStateOf<Offset?>(null) }
	// The gesture's cursor-wrap bookkeeping: the accumulated wrap offset (the virtual pointer), the
	// stale pre-warp-event guard, and the actual-landing fold rule (see CursorWrapState).
	val cursorWrap = remember(areaId) { CursorWrapState() }

	// The shared pointer-side driver of the modal gesture (stale discard, drive, wrap, buttons).
	val modalController = remember(areaId) { ModalTransformController(cursorWrap) }

	// The drawable ids a working selection currently paints, for the live GPU tint preview.
	fun Selection.drawableIds(): Set<DrawableId> =
		targets.mapNotNull { (it as? SelectionTarget.Drawable)?.id }.toSet()

	// One Circle-select brush stamp: enclose the drawables whose world centroid is within the brush, filter out
	// locked ones, and add them to (or remove them from) the working selection.  Radius is screen pixels, so it
	// converts to world units by the zoom; the centre unprojects to world space to match the cached centroids.
	fun circleStamp(working: Selection, erasing: Boolean, screenPos: Offset, radiusPx: Float, activeCamera: ViewportCamera, size: IntSize): Selection {
		val model = session.model.value
		val (worldX, worldY) = screenToWorld(screenPos.x, screenPos.y, activeCamera, size)
		val worldRadius = radiusPx / activeCamera.zoom
		val enclosed =
			drawablesInCircle(cachedCentroids, worldX, worldY, worldRadius)
				.map { SelectionTarget.Drawable(it) }
				.filter { model.selectableOf(it) }
		if (enclosed.isEmpty()) {
			return working
		}
		return if (erasing) {
			val remaining = working.targets - enclosed.toSet()
			Selection(remaining, working.active?.takeIf { it in remaining } ?: remaining.lastOrNull())
		} else {
			Selection(working.targets + enclosed, enclosed.last())
		}
	}

	// Applies a finished box drag: select every selectable drawable whose cached world centroid the box
	// encloses (Shift adds to the current selection).  Shared by the armed (Blender B) and un-armed paths.
	fun applyBoxSelection(start: Offset, end: Offset, additive: Boolean, activeCamera: ViewportCamera, size: IntSize) {
		val model = session.model.value
		val (worldStartX, worldStartY) = screenToWorld(start.x, start.y, activeCamera, size)
		val (worldEndX, worldEndY) = screenToWorld(end.x, end.y, activeCamera, size)
		val enclosed =
			drawablesInBox(cachedCentroids, min(worldStartX, worldEndX), min(worldStartY, worldEndY), max(worldStartX, worldEndX), max(worldStartY, worldEndY))
				.map { SelectionTarget.Drawable(it) }
				.filter { model.selectableOf(it) }
		val current = session.selection.value
		val newSelection =
			if (additive) {
				Selection(current.targets + enclosed, enclosed.lastOrNull() ?: current.active)
			} else {
				Selection(enclosed.toSet(), enclosed.lastOrNull())
			}
		session.setSelection(newSelection)
	}

	// Applies a primary click to the object selection (the sub-threshold end of an un-armed drag): picks
	// the front-most selectable drawable under the cursor - plain replaces, toggle / extend both toggle
	// membership (Blender-style, so a second modified click deselects), an unmodified click on empty canvas
	// clears, a modified one keeps the selection.  An Alt click instead opens the overlap picker when 2+
	// opaque meshes are stacked under the cursor, selects directly when exactly one is hit, and does nothing
	// on empty canvas.  Unselectable drawables are excluded, so a click passes through them.
	fun applyClickPick(position: Offset, toggle: Boolean, extend: Boolean, alt: Boolean) {
		val model = session.model.value
		if (alt) {
			val candidates =
				service.pickAllAt(areaId, position.x, position.y)
					.filter { candidate -> model.selectableOf(SelectionTarget.Drawable(candidate.id)) }
			when {
				candidates.size > 1 -> onOverlapRequest(position, candidates)
				candidates.size == 1 -> session.setSelection(SelectionOps.replace(SelectionTarget.Drawable(candidates.first().id)))
				else -> {
					// Alt-click on empty canvas: leave the selection as-is (Alt is the disambiguate gesture).
				}
			}
			return
		}
		val hit = service.pickAt(areaId, position.x, position.y)?.takeIf { model.selectableOf(SelectionTarget.Drawable(it)) }
		val current = session.selection.value
		val target = hit?.let { SelectionTarget.Drawable(it) }
		val next =
			when {
				target != null && (toggle || extend) -> SelectionOps.toggle(current, target)
				target != null -> SelectionOps.replace(target)
				toggle || extend -> current // a modified click on empty canvas keeps the selection
				else -> SelectionOps.clear() // a plain click on empty canvas clears
			}
		session.setSelection(next)
	}

	// The marquee (box + circle) machinery over whole drawables: the stroke / rubber-band state and event
	// rules are shared (MarqueeSelectController); the callbacks bind them to the drawable domain - the
	// centroid snapshot at stroke start and the live GPU tint preview are the Object-only extras.
	val marquee =
		remember(areaId) {
			MarqueeSelectController<Selection>(
				seedStroke = { session.selection.value },
				stampStroke = { working, erasing, center, radiusPx, stampCamera, stampSize ->
					circleStamp(working, erasing, center, radiusPx, stampCamera, stampSize)
				},
				commitStroke = { stroke -> session.setSelection(stroke) },
				applyBox = { start, end, additive, boxCamera, boxSize -> applyBoxSelection(start, end, additive, boxCamera, boxSize) },
				setCircleRadius = { radiusPx -> session.setCircleRadius(radiusPx) },
				clearTool = { session.clearSelectTool() },
				onStrokeBegin = { cachedCentroids = service.drawableWorldCentroids() },
				previewStroke = { stroke -> session.setPreviewSelection(stroke?.drawableIds()) },
			)
		}

	// Abandons an in-flight un-armed box drag, dropping the rubber-band without touching the selection.
	fun cancelIdleBox() {
		if (idleBoxing) {
			marquee.cancel()
			idleBoxing = false
			session.setViewportGestureActive(false)
		}
	}

	// Escape resolves the in-flight select gesture: the shell routes the key through the session's cancel
	// signal (the gesture state lives in the marquee controller, which the session cannot reach directly).
	// A circle stroke keeps its paint (see MarqueeSelectController.cancel); an un-armed box drag abandons.
	LaunchedEffect(session) {
		session.meshGestureCancelRequests.collect {
			marquee.cancel()
			cancelIdleBox()
		}
	}

	// Confirms the in-flight object transform: commit every drawable's new base positions as one undo step (a
	// null / empty preview means no movement, so nothing commits), then clear the operator - its teardown
	// re-syncs the renderer.
	fun confirmObjectGesture() {
		val committed = preview
		val captured = capture
		if (committed != null && captured != null && committed.isNotEmpty()) {
			session.commitObjectPositions(MeshChange.MoveDrawables(captured.drawableIds), committed)
		}
		session.clearObjectOperator()
	}

	// Drives the modal preview for one virtual-pointer position: applies the operator to every captured
	// drawable's whole geometry about the shared pivot, maps each result back to local through the
	// deformer-chain inverse, and pushes the folded model to the renderer.  False when the capture has
	// not landed yet.
	fun driveObjectPreview(operator: MeshOperatorKind, virtualPointer: Offset, activeCamera: ViewportCamera, size: IntSize): Boolean {
		val start = gestureStart ?: return false
		val captured = capture ?: return false
		// One pointer frame for the whole capture; only geometry and pivots vary per drawable.
		val frame = TransformGestureFrame(captured.anchor, start, virtualPointer, session.axisConstraint.value, activeCamera, size)
		val newBaseByDrawable = LinkedHashMap<DrawableId, FloatArray>(captured.geometries.size)
		var folded = session.model.value
		for (drawableIndex in captured.geometries.indices) {
			val geometry = captured.geometries[drawableIndex]
			val transformedWorld =
				applyOperator(
					operator,
					geometry.world,
					captured.groups[drawableIndex],
					frame,
					// Proportional editing is an Edit-mode feature: object mode moves whole
					// drawables, so there are no unselected vertices to weight.
					emptyMap(),
					captured.rotationTracker,
				)
			val newBase = geometry.worldToBase(transformedWorld, captured.indices[drawableIndex])
			newBaseByDrawable[geometry.drawableId] = newBase
			folded = folded.withMeshPositions(geometry.drawableId, newBase)
		}
		preview = newBaseByDrawable
		service.setModel(folded)
		return true
	}

	// The modal gesture's commit-side seam: the Object overlay drives whole-drawable previews, confirms
	// as one MoveDrawables undo step, and cancels through the session's operator clear.  The
	// pointer-side mechanics live in ModalTransformController; no scroll behavior in Object mode.
	val modalTarget =
		object : ModalTransformTarget {
			override fun drivePreview(virtualPointer: Offset, camera: ViewportCamera, size: IntSize): Boolean {
				// Defensive ownership check (the pointer loop already gates): only the initiating area drives.
				val operator = session.activeObjectOperator.value?.takeIf { it.areaId == areaId } ?: return false
				return driveObjectPreview(operator.kind, virtualPointer, camera, size)
			}

			override fun confirm() {
				confirmObjectGesture()
			}

			override fun cancel() {
				// The teardown effect re-syncs the renderer when the operator clears.
				session.clearObjectOperator()
			}
		}

	// Seed / tear down the transform capture as the operator latches / clears.  On latch, freeze each selected
	// drawable's mapping, posed shapes, base mesh, and vertex set at the current object-mode pose, plus the one
	// shared combined-centroid pivot.  On clear (confirm or cancel), re-sync the renderer to the committed model,
	// discarding any throwaway preview the drive loop pushed.
	LaunchedEffect(activeObjectOperator) {
		val operator = activeObjectOperator?.takeIf { it.areaId == areaId }
		if (operator != null) {
			val model = session.model.value
			val pose = session.pose.value
			val eligibleIds = eligibleTransformDrawables(session.selection.value, model)
			// A drawable with a hidden ancestor has no world mapping and captures as null - skip it rather than
			// abort the whole gesture (the others still transform).
			val geometries = eligibleIds.orEmpty().mapNotNull { drawableId -> captureDrawableWorld(model, pose, drawableId) }
			val ids = geometries.map { geometry -> geometry.drawableId }
			val worldList = geometries.map { geometry -> geometry.world }
			val indicesList = geometries.map { geometry -> geometry.allIndices }
			if (ids.isEmpty()) {
				// Nothing transformable survived (all hidden, or the selection changed): drop the operator.
				session.clearObjectOperator()
			} else {
				// The gesture anchor per the active pivot mode: the combined centroid (the default and the
				// Individual Origins measuring center), the active drawable's own centroid, or the 2D cursor -
				// the latter two falling back to the combined centroid when absent.
				val combined = MeshTransforms.combinedCentroid(worldList)
				val pivotMode = session.pivotMode.value
				val anchor =
					when (pivotMode) {
						TransformPivotMode.MedianPoint, TransformPivotMode.IndividualOrigins -> combined
						TransformPivotMode.ActiveElement -> {
							val activeId = (session.selection.value.active as? SelectionTarget.Drawable)?.id
							val activeIndex = activeId?.let { candidate -> ids.indexOf(candidate) } ?: -1
							if (activeIndex >= 0) {
								MeshTransforms.medianPivot(worldList[activeIndex], indicesList[activeIndex])
							} else {
								combined
							}
						}
						TransformPivotMode.Cursor ->
							session.cursor2d.value?.let { cursor -> cursor.worldX to cursor.worldY } ?: combined
					}
				val groups =
					ids.indices.map { drawableIndex ->
						if (pivotMode == TransformPivotMode.IndividualOrigins) {
							// Each drawable turns about its own centroid (the object-mode island).
							val ownCentroid = MeshTransforms.medianPivot(worldList[drawableIndex], indicesList[drawableIndex])
							TransformPivots.sharedGroup(indicesList[drawableIndex], ownCentroid.first, ownCentroid.second)
						} else {
							TransformPivots.sharedGroup(indicesList[drawableIndex], anchor.first, anchor.second)
						}
					}
				capture = ObjectGestureCapture(geometries, indicesList, groups, anchor)
				gestureStart = lastPointer
				preview = null
				cursorWrap.reset()
			}
		} else {
			// Resync the renderer only when THIS overlay owned a gesture: the effect also runs its else
			// branch at mount (and when another area's operator latches), and an unguarded setModel from
			// a viewport split open mid-gesture would stomp the initiating area's live preview.
			if (capture != null) {
				service.setModel(session.model.value)
			}
			capture = null
			preview = null
			gestureStart = null
			cursorWrap.reset()
		}
	}

	// Enter confirms the modal object gesture (mirroring a primary click); the shell routes the keypress
	// here, gated to the INITIATING area through the operator latch itself.
	LaunchedEffect(session) {
		collectModalConfirmRequests(session, { session.activeObjectOperator.value?.areaId == areaId }) {
			confirmObjectGesture()
		}
	}

	// The geometry-dependent Shift+S snaps for Object mode over the selected drawables' centroids.
	// Only the pointer's own area executes: every open 2D viewport composes this collector, and an
	// ungated request would commit once per viewport.
	LaunchedEffect(session) {
		session.snapRequests.collect { kind ->
			if (session.mode.value != EditorMode.Object || service.activeAreaId != areaId) {
				return@collect
			}
			handleObjectSnapRequest(session, kind)
		}
	}

	Box(
		modifier =
			modifier
				.fillMaxSize()
				.clipToBounds()
				.onGloballyPositioned { coordinates -> areaScreenOrigin = coordinates.positionOnScreen() }
				.then(
					if (armed) {
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
							lastPointer = change.position
							val latchedOperator = session.activeObjectOperator.value
							val latchedTool = session.activeSelectTool.value
							// A gesture belongs to its initiating area: while another viewport's operator or tool is
							// live - or a UV operator, which can never belong to a viewport area - this overlay is
							// fully inert (no drive, no picks, no marquee).  Escape and Enter stay global through
							// the shell ladder, and navigation (pan / zoom) still falls through.
							if ((latchedOperator != null && latchedOperator.areaId != areaId) ||
								(latchedTool != null && latchedTool.areaId != areaId) ||
								session.activeUvOperator.value != null
							) {
								if (idleBoxing) {
									cancelIdleBox()
								}
								continue
							}
							val operator = latchedOperator
							val tool = latchedTool
							val activeCamera = liveCamera.value
							val size = liveSize.value
							// A tool or operator armed mid-drag (via its keymap command) supersedes the un-armed box:
							// drop the rubber-band so its release handler cannot fire into the armed gesture's state.
							if (idleBoxing && (operator != null || tool != null)) {
								cancelIdleBox()
							}
							if (operator != null) {
								// MODAL transform: the shared controller drives every captured drawable over the
								// shared pivot and swallows every event (stale discard, virtual-pointer drive,
								// cursor wrap, RMB-cancel / LMB-confirm).
								lastPointer = modalController.handleEvent(event, change, modalTarget, activeCamera, size, areaScreenOrigin)
							} else if (tool is ActiveSelectTool.Circle) {
								// CIRCLE SELECT: the shared controller paints drawables by centroid, previews the
								// stroke through the GPU tint, and consumes every event; see
								// MarqueeSelectController.handleCircleEvent.
								marquee.handleCircleEvent(event, change, tool.radiusPx, activeCamera, size)
							} else if (tool is ActiveSelectTool.BoxArmed) {
								// BOX SELECT (armed): a drag rubber-bands; on release every drawable whose centroid is enclosed is
								// selected (Shift adds).  A right-click or a sub-threshold click just disarms.  One-shot: disarm after.
								when (event.type) {
									PointerEventType.Press ->
										if (event.buttons.isSecondaryPressed) {
											session.clearSelectTool()
											change.consume()
										} else if (event.buttons.isPrimaryPressed) {
											cachedCentroids = service.drawableWorldCentroids()
											marquee.beginBox(change.position)
											change.consume()
										}

									PointerEventType.Move ->
										if (marquee.dragBox(change.position)) {
											change.consume()
										}

									PointerEventType.Release -> {
										val boxRelease = marquee.releaseBox(change.position, event.keyboardModifiers.isShiftPressed, activeCamera, size)
										if (boxRelease != BoxRelease.None) {
											// Armed Box-select is one-shot: disarm after the drag (or a bare click).
											session.clearSelectTool()
											change.consume()
										}
									}

									else -> {}
								}
							} else {
								// IDLE (nothing armed): the primary button owns picking here.  A press starts a provisional
								// rubber-band; a drag past the threshold box-selects on release (Shift adds), a sub-threshold
								// release is the click pick (replace / toggle / Alt overlap).  A right-click or Escape abandons
								// the drag.  Only primary-driven events are consumed, so middle-drag pan and wheel zoom fall
								// through to the navigation layer.
								when (event.type) {
									PointerEventType.Press ->
										if (event.buttons.isSecondaryPressed && event.keyboardModifiers.isShiftPressed && !idleBoxing) {
											// Shift+RightClick places the 2D cursor at the pointer (Blender's gesture); the
											// HUD overlay draws it and the Cursor pivot mode / snap menu anchor on it.
											val (worldX, worldY) = screenToWorld(change.position.x, change.position.y, activeCamera, size)
											session.setCursor2d(worldX, worldY)
											change.consume()
										} else if (event.buttons.isSecondaryPressed) {
											if (idleBoxing) {
												cancelIdleBox()
												change.consume()
											}
										} else if (event.buttons.isPrimaryPressed && !event.buttons.isTertiaryPressed) {
											cachedCentroids = service.drawableWorldCentroids()
											marquee.beginBox(change.position)
											idleBoxing = true
											session.setViewportGestureActive(true)
											change.consume()
										}

									PointerEventType.Move ->
										if (idleBoxing && marquee.dragBox(change.position)) {
											change.consume()
										}

									PointerEventType.Release -> {
										if (idleBoxing) {
											val boxRelease = marquee.releaseBox(change.position, event.keyboardModifiers.isShiftPressed, activeCamera, size)
											if (boxRelease != BoxRelease.None) {
												if (boxRelease == BoxRelease.Click) {
													val modifiers = event.keyboardModifiers
													applyClickPick(
														change.position,
														toggle = modifiers.isCtrlPressed || modifiers.isMetaPressed,
														extend = modifiers.isShiftPressed,
														alt = modifiers.isAltPressed,
													)
												}
												idleBoxing = false
												session.setViewportGestureActive(false)
												change.consume()
											}
										}
									}

									else -> {}
								}
							}
						}
					}
				},
	) {
		Canvas(modifier = Modifier.fillMaxSize()) {
			val fullSize = Size(widthPx.toFloat(), heightPx.toFloat())
			// The box rubber-band.
			drawRubberBand(marquee.boxStart, marquee.boxCurrent, overlayStyle)

			// Armed select-tool affordances (Blender B / C), shared chrome with the Edit gizmo.  Only
			// the arming area draws them - the latch is session-global, every split viewport draws.
			drawSelectToolAffordances(
				tool = ownedSelectTool,
				pointer = lastPointer,
				boxDragInFlight = marquee.boxStart != null,
				viewport = fullSize,
				style = overlayStyle,
				crosshairCursor = LocalUmamoCursors.crosshair,
			)

			// Modal transform HUD, shared chrome with the Edit gizmo (see drawModalTransformHud).
			// Only the initiating area draws it: the capture exists solely in the overlay whose area
			// the operator latch names, so its presence IS the ownership gate.
			val captured = capture
			if (activeObjectOperator != null && captured != null) {
				drawModalTransformHud(
					axisConstraint = axisConstraint,
					pivotScreen = worldToScreen(captured.anchor.first, captured.anchor.second, camera, IntSize(widthPx, heightPx)),
					virtualPointer = cursorWrap.virtualPointer(lastPointer),
					realPointer = lastPointer,
					viewport = fullSize,
					lineColor = overlayColors.viewportMarquee,
					pointerCursor = LocalUmamoCursors.nsewScroll,
				)
			}
		}
	}
}
