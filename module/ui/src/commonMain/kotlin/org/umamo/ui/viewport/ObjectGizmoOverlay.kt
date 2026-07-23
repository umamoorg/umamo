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
import org.umamo.edit.IndividualOriginScope
import org.umamo.edit.MeshChange
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.MeshTransforms
import org.umamo.edit.ModalCaptureSource
import org.umamo.edit.ModalTransformCapture
import org.umamo.edit.Selection
import org.umamo.edit.SelectionOps
import org.umamo.edit.SelectionTarget
import org.umamo.edit.buildModalTransformCapture
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
 * The captured state of an in-flight Object-mode transform: the shared [ModalTransformCapture] (which owns
 * the pivot groups, the anchor, the frozen operator kind, and the rotation tracker) plus the per-drawable
 * [DrawableWorldGeometry] the drive loop needs to invert a transformed world shape back onto the base mesh.
 * The geometry is held in a map keyed on the drawable id, looked up by [org.umamo.edit.ModalCaptureEntry],
 * so nothing stays index-aligned with the capture's entry list.
 *
 * @property ModalTransformCapture transform The shared gesture capture (entries, groups, anchor, kind).
 * @property Map<DrawableId, DrawableWorldGeometry> geometryById Each captured drawable's world geometry.
 */
private class ObjectGesture(
	val transform: ModalTransformCapture,
	val geometryById: Map<DrawableId, DrawableWorldGeometry>,
)

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
 *     centroid, previewed straight to the renderer and committed as one undo step ([MeshChange.TransformDrawables]).
 *     A right-click or Escape cancels (the renderer re-syncs to the committed model); a primary click or Enter
 *     confirms.  Only drawables transform - a selection with nothing transformable blocks at the session
 *     guard ([EditorSession.beginObjectOperator]) before an operator ever latches here.
 *
 * The transform math ([applyOperator], [MeshTransforms], the shared world->base round trip), the cursor-wrap
 * scheme, and the screen projection ([worldToScreen] / [screenToWorld]) are shared verbatim with the Edit
 * gizmo; only the capture's per-drawable geometry and the selection domain (whole drawables) differ.
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

	// idleBoxing marks a non-armed box drag (started from a plain primary press rather than Blender's B),
	// whose sub-threshold release is a click pick; the rubber-band itself lives in the marquee controller.
	var idleBoxing by remember(areaId) { mutableStateOf(false) }
	// Per-drawable world centroids, snapshotted at the press that starts a select gesture (the pose is fixed for
	// the whole drag, so one snapshot serves every move) and tested against the region each frame.
	var cachedCentroids by remember(areaId) { mutableStateOf<Map<DrawableId, FloatArray>>(emptyMap()) }

	// The per-area modal-gesture bookkeeping (last pointer, capture + preview, gesture origin, cursor wrap,
	// pointer controller); the capture is the Object-mode gesture (shared transform capture + geometry map).
	val gesture = remember(areaId) { ModalGestureState<ObjectGesture>() }

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

	// The recomposition-gated tool-switch backstop the Edit and UV overlays already carry: an armed tool that
	// is cleared or switched (box<->circle) resolves any in-flight marquee.  Keyed on the tool KIND (not the
	// whole value) so a circle-brush resize does not re-fire and wipe the stroke mid-paint.  Object's body
	// mirrors its own cancel collector above (marquee plus the idle box), since it - unlike Edit / UV - also
	// carries an un-armed box drag; the pointer loop's own idle-box cancel and this one are order-independent.
	LaunchedEffect(selectToolKind(ownedSelectTool)) {
		marquee.cancel()
		cancelIdleBox()
	}

	// Confirms the in-flight object transform: commit every drawable's new base positions as one undo step (a
	// null / empty preview means no movement, so nothing commits), then clear the operator - its teardown
	// re-syncs the renderer.
	fun confirmObjectGesture() {
		val committed = gesture.preview
		val gestureData = gesture.capture
		if (committed != null && gestureData != null && committed.isNotEmpty()) {
			val transform = gestureData.transform
			session.commitObjectPositions(MeshChange.TransformDrawables(transform.drawableIds, transform.operatorKind), committed)
		}
		session.clearObjectOperator()
	}

	// Drives the modal preview for one virtual-pointer position: applies the operator to every captured
	// drawable's whole geometry about the shared pivot, maps each result back to local through the
	// deformer-chain inverse, and pushes the folded model to the renderer.  False when the capture has
	// not landed yet.
	fun driveObjectPreview(operator: MeshOperatorKind, virtualPointer: Offset, activeCamera: ViewportCamera, size: IntSize): Boolean {
		val start = gesture.gestureStart ?: return false
		val gestureData = gesture.capture ?: return false
		val transform = gestureData.transform
		// One pointer frame for the whole capture; only geometry and pivots vary per drawable.
		val frame = TransformGestureFrame(transform.anchor, start, virtualPointer, session.axisConstraint.value, activeCamera, size)
		val newBaseByDrawable = LinkedHashMap<DrawableId, FloatArray>(transform.entries.size)
		var folded = session.model.value
		for (entry in transform.entries) {
			val geometry = gestureData.geometryById.getValue(entry.drawableId)
			val transformedWorld =
				applyOperator(
					operator,
					entry.positions,
					entry.groups,
					frame,
					// Proportional editing is an Edit-mode feature: object mode moves whole
					// drawables, so there are no unselected vertices to weight.
					emptyMap(),
					transform.rotationTracker,
				)
			val newBase = geometry.worldToBase(transformedWorld, entry.coveredIndices)
			newBaseByDrawable[entry.drawableId] = newBase
			folded = folded.withMeshPositions(entry.drawableId, newBase)
		}
		gesture.preview = newBaseByDrawable
		service.setModel(folded)
		return true
	}

	// The modal gesture's commit-side seam: the Object overlay drives whole-drawable previews, confirms
	// as one TransformDrawables undo step, and cancels through the session's operator clear.  The
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
	// drawable's world geometry at the current object-mode pose and hand the shared builder its sources plus
	// this area's active-element / cursor anchors.  On clear (confirm or cancel), re-sync the renderer to the
	// committed model, discarding any throwaway preview the drive loop pushed.
	LaunchedEffect(activeObjectOperator) {
		val operator = activeObjectOperator?.takeIf { it.areaId == areaId }
		if (operator != null) {
			val model = session.model.value
			val pose = session.pose.value
			val eligibleIds = eligibleTransformDrawables(session.selection.value, model)
			// A drawable with a hidden ancestor has no world mapping and captures as null - skip it rather than
			// abort the whole gesture (the others still transform).
			val geometries = eligibleIds.orEmpty().mapNotNull { drawableId -> captureDrawableWorld(model, pose, drawableId) }
			val geometryById = geometries.associateBy { geometry -> geometry.drawableId }
			// Object mode moves every vertex of each drawable, so the covered set is the whole mesh.  Triangle
			// connectivity is unused here (WholeMesh pivots, no proportional editing), so an empty array serves.
			val sources =
				geometries.map { geometry ->
					ModalCaptureSource(geometry.drawableId, geometry.world, IntArray(0), geometry.allIndices)
				}
			// The two per-area anchors the shared builder cannot resolve itself: the active drawable's own
			// centroid and the 2D cursor.  The builder falls back to the combined median when either is null.
			val activeAnchor =
				(session.selection.value.active as? SelectionTarget.Drawable)?.id
					?.let { activeId -> geometryById[activeId] }
					?.let { geometry -> MeshTransforms.medianPivot(geometry.world, geometry.allIndices) }
			val cursorAnchor = session.cursor2d.value?.let { cursor -> cursor.worldX to cursor.worldY }
			val transform =
				buildModalTransformCapture(
					sources = sources,
					pivotMode = session.pivotMode.value,
					// Object mode's Individual Origins turns each whole drawable about its own centroid.
					individualOriginScope = IndividualOriginScope.WholeMesh,
					operatorKind = operator.kind,
					activeAnchor = activeAnchor,
					cursorAnchor = cursorAnchor,
				)
			if (transform == null) {
				// Nothing transformable survived (all hidden, or the selection changed): drop the operator.
				session.clearObjectOperator()
			} else {
				gesture.begin(ObjectGesture(transform, geometryById), gesture.lastPointer)
			}
		} else {
			// Resync the renderer only when THIS overlay owned a gesture: the effect also runs its else
			// branch at mount (and when another area's operator latches), and an unguarded setModel from
			// a viewport split open mid-gesture would stomp the initiating area's live preview.
			if (gesture.end()) {
				service.setModel(session.model.value)
			}
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
				.onGloballyPositioned { coordinates -> gesture.areaScreenOrigin = coordinates.positionOnScreen() }
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
							gesture.lastPointer = change.position
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
								gesture.lastPointer = gesture.modalController.handleEvent(event, change, modalTarget, activeCamera, size, gesture.areaScreenOrigin)
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
				pointer = gesture.lastPointer,
				boxDragInFlight = marquee.boxStart != null,
				viewport = fullSize,
				style = overlayStyle,
				crosshairCursor = LocalUmamoCursors.crosshair,
			)

			// Modal transform HUD, shared chrome with the Edit gizmo (see drawModalTransformHud).
			// Only the initiating area draws it: the capture exists solely in the overlay whose area
			// the operator latch names, so its presence IS the ownership gate.
			val captured = gesture.capture
			if (activeObjectOperator != null && captured != null) {
				val anchor = captured.transform.anchor
				drawModalTransformHud(
					axisConstraint = axisConstraint,
					pivotScreen = worldToScreen(anchor.first, anchor.second, camera, IntSize(widthPx, heightPx)),
					virtualPointer = gesture.cursorWrap.virtualPointer(gesture.lastPointer),
					realPointer = gesture.lastPointer,
					viewport = fullSize,
					lineColor = overlayColors.viewportMarquee,
					pointerCursor = LocalUmamoCursors.nsewScroll,
				)
			}
		}
	}
}
