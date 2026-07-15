package org.umamo.ui.viewport

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
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
import org.umamo.edit.MeshSelection
import org.umamo.edit.MeshSelectionOps
import org.umamo.edit.MeshTopology
import org.umamo.edit.MeshTransforms
import org.umamo.edit.PROPORTIONAL_RADIUS_STEP_FACTOR
import org.umamo.edit.ProportionalEditState
import org.umamo.edit.ProportionalInfluence
import org.umamo.edit.RotationAngleTracker
import org.umamo.edit.TransformPivotGroup
import org.umamo.edit.TransformPivotMode
import org.umamo.edit.TransformPivots
import org.umamo.edit.proportionalInfluences
import org.umamo.edit.proportionalInfluencesConnected
import org.umamo.edit.withMeshUvs
import org.umamo.render.ViewportCamera
import org.umamo.runtime.model.DrawableId
import org.umamo.ui.model.LocalPuppetRenderSync
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoCursors
import kotlin.math.pow
import kotlin.math.roundToInt

/** The smallest useful proportional influence radius in display (texel) units. */
private const val MIN_UV_PROPORTIONAL_RADIUS_DISPLAY = 1f

/**
 * The captured state of an in-flight UV transform, frozen when the operator latches so the whole drag
 * is atomic across the shown meshes - the texture-space sibling of the Edit overlay's capture, minus
 * every deformer concern: UVs live in one flat display space, so there is no space mapping, no
 * movement transfer, and no world/local split.  The page dimensions freeze here too, so the
 * display-to-uv conversion at drive and commit always matches the space the originals were mapped in
 * (the shown page can hop mid-gesture if the active drawable changes from another area).
 *
 * [influences] and [movedIndices] carry the proportional-editing state exactly like the Edit capture:
 * re-derived from the FROZEN originals on a mid-gesture radius or falloff change, never from the live
 * preview.
 *
 * @property List<DrawableId> drawableIds The meshes the gesture moves (those with covered vertices).
 * @property List<FloatArray> originalDisplay Each mesh's frozen display-space coordinates.
 * @property List<Set<Int>> capturedIndices The union of vertices each mesh's selected elements cover.
 * @property List<IntArray> triangleIndices Each mesh's triangle vertex indices (for connectivity).
 * @property List<List<TransformPivotGroup>> groups Each mesh's pivot groups per the active pivot mode.
 * @property Pair<Float, Float> anchor The display-space point factors and angles measure against.
 * @property Int pageWidth The atlas page width the display mapping used, in texels.
 * @property Int pageHeight The atlas page height the display mapping used, in texels.
 */
private class UvGestureCapture(
	val drawableIds: List<DrawableId>,
	val originalDisplay: List<FloatArray>,
	val capturedIndices: List<Set<Int>>,
	val triangleIndices: List<IntArray>,
	val groups: List<List<TransformPivotGroup>>,
	val anchor: Pair<Float, Float>,
	val pageWidth: Int,
	val pageHeight: Int,
) {
	/** The Rotate gesture's angle accumulator (unwrapped per-move increments; see RotationAngleTracker). */
	val rotationTracker = RotationAngleTracker()

	var influences: List<Map<Int, ProportionalInfluence>> = List(drawableIds.size) { emptyMap() }
		private set

	var movedIndices: List<Set<Int>> = capturedIndices
		private set

	/**
	 * Recomputes the proportional influence maps from the FROZEN original display shapes.  The radius
	 * is the UV editor's own display-unit radius, not the session's canvas-px radiusWorld - the two
	 * spaces have unrelated scales - while the falloff curve and Connected Only follow the shared
	 * session state.  A null state clears the influences so the gesture moves only the covered set.
	 *
	 * @param ProportionalEditState? state The shared proportional configuration, or null for none.
	 * @param Float radiusDisplay The influence radius in display (texel) units.
	 */
	fun applyProportional(state: ProportionalEditState?, radiusDisplay: Float) {
		influences =
			drawableIds.indices.map { meshIndex ->
				when {
					state == null -> emptyMap()
					state.connectedOnly ->
						proportionalInfluencesConnected(
							originalDisplay[meshIndex],
							capturedIndices[meshIndex],
							radiusDisplay,
							state.falloff,
							triangleIndices[meshIndex],
						)
					else -> proportionalInfluences(originalDisplay[meshIndex], capturedIndices[meshIndex], radiusDisplay, state.falloff)
				}
			}
		movedIndices =
			drawableIds.indices.map { meshIndex ->
				if (influences[meshIndex].isEmpty()) {
					capturedIndices[meshIndex]
				} else {
					capturedIndices[meshIndex] + influences[meshIndex].keys
				}
			}
	}
}

/**
 * The UV editor's gizmo overlay: the Edit-mode interaction core the UV space composes over its atlas
 * underlay.  Draws the shown meshes' UV wireframes (from the live preview during a gesture), runs the
 * idle element selection (click pick with Shift/Ctrl toggle, empty-drag box, sub-threshold-click
 * clear), and drives the modal G / S / R operators over raw texture coordinates through the shared
 * [ModalTransformController] - the same pointer semantics as the viewport overlays (stale discard,
 * virtual pointer, cursor wrap, LMB-confirm / RMB-cancel), with an identity space mapping: the
 * transformed display arrays convert straight back to normalized UV on drive and commit, no deformer
 * inverse involved.
 *
 * Live preview streams through [LocalPuppetRenderSync]: each pointer frame folds the transformed UVs
 * into an uncommitted model and pushes it to the puppet renderer, so the 2D viewport shows the art
 * resampling as the mapping moves; confirm commits ONE undo step via commitMeshUvs and the session's
 * model bridge republishes the committed model.  Gating follows the area-ownership contract: the
 * capture effect and pointer drive key on the UV latch's own areaId, bystander areas stay inert, and
 * teardown resyncs the raster only when this overlay owned a gesture.
 *
 * UV エディタのギズモ重畳。要素選択とモーダル G / S / R をテクスチャ座標上で駆動する。プレビューは
 * レンダ同期ハンドル経由でパペットレンダラへ流れ、確定は 1 つの取り消し段になる。
 *
 * @param String areaId The UV editor area this overlay covers.
 * @param EditorSession session The session owning the selection and the UV operator latch.
 * @param List<GizmoMeshGeometry> geometries The shown meshes' display-space gizmo geometry.
 * @param Int pageWidth The shown atlas page's width in texels (the display mapping's scale).
 * @param Int pageHeight The shown atlas page's height in texels.
 * @param ViewportCamera? camera The area camera; null hides the overlay (no fit has landed yet).
 * @param Int widthPx The area width in pixels.
 * @param Int heightPx The area height in pixels.
 * @param Modifier modifier The layout modifier.
 */
@Composable
internal fun UvGizmoOverlay(
	areaId: String,
	session: EditorSession,
	geometries: List<GizmoMeshGeometry>,
	pageWidth: Int,
	pageHeight: Int,
	camera: ViewportCamera?,
	widthPx: Int,
	heightPx: Int,
	modifier: Modifier = Modifier,
) {
	val meshSelection by session.meshSelection.collectAsState()
	val activeOperator by session.activeUvOperator.collectAsState()
	val activeSelectTool by session.activeSelectTool.collectAsState()
	val axisConstraint by session.axisConstraint.collectAsState()
	val proportionalEdit by session.proportionalEdit.collectAsState()
	val uvCursor by session.uvCursor.collectAsState()
	val renderSync = LocalPuppetRenderSync.current
	val gizmoColors = rememberMeshEditColors()
	val overlayColors = LocalUmamoColors.current
	if (camera == null || geometries.isEmpty()) {
		return
	}
	val overlayStyle = selectionOverlayStyle(overlayColors)

	// Live values the long-running pointer loop and effects read (they are keyed only on areaId, so
	// they must not close over a stale camera / size / geometry / page when those change mid-edit).
	val liveCamera = rememberUpdatedState(camera)
	val liveSize = rememberUpdatedState(IntSize(widthPx, heightPx))
	val liveGeometries = rememberUpdatedState(geometries)
	val livePageWidth = rememberUpdatedState(pageWidth)
	val livePageHeight = rememberUpdatedState(pageHeight)
	val liveRenderSync = rememberUpdatedState(renderSync)

	// The box-select and circle-select machinery over the shared session selection.
	val marquee =
		remember(areaId) {
			MarqueeSelectController<MeshSelection>(
				seedStroke = { session.meshSelection.value },
				stampStroke = { working, erasing, center, radiusPx, stampCamera, stampSize ->
					circleSelection(working, erasing, center, radiusPx, liveGeometries.value, stampCamera, stampSize)
				},
				commitStroke = { stroke -> session.setMeshSelection(stroke) },
				applyBox = { start, end, additive, boxCamera, boxSize ->
					val selection = session.meshSelection.value
					val insideByDrawable =
						liveGeometries.value.associate { geometry ->
							geometry.drawableId to elementsInBox(selection.selectMode, geometry, start, end, boxCamera, boxSize)
						}
					session.setMeshSelection(MeshSelectionOps.box(selection, insideByDrawable, additive = additive))
				},
				setCircleRadius = { radiusPx -> session.setCircleRadius(radiusPx) },
				clearTool = { session.clearSelectTool() },
			)
		}

	// Gesture-local state, the Edit overlay's shape: the frozen capture, the per-mesh display-space
	// preview arrays, and the modal pointer bookkeeping.
	var lastPointer by remember(areaId) { mutableStateOf(Offset.Zero) }
	var capture by remember(areaId) { mutableStateOf<UvGestureCapture?>(null) }
	var preview by remember(areaId) { mutableStateOf<Map<DrawableId, FloatArray>?>(null) }
	var gestureStart by remember(areaId) { mutableStateOf<Offset?>(null) }
	var areaScreenOrigin by remember(areaId) { mutableStateOf<Offset?>(null) }
	val cursorWrap = remember(areaId) { CursorWrapState() }
	val modalController = remember(areaId) { ModalTransformController(cursorWrap) }

	// The UV editor's own proportional influence radius, in display (texel) units.  The session's
	// radiusWorld is scaled for the puppet canvas and means nothing on an atlas page, so only the
	// falloff curve and Connected Only are shared; the radius seeds from the page size on first use
	// and survives across gestures (the circle-select remembered-radius pattern).
	var proportionalRadiusDisplay by remember(areaId) { mutableStateOf<Float?>(null) }

	/**
	 * Resolves the effective proportional radius, seeding it from the current page on first use.
	 *
	 * @return Float The influence radius in display units.
	 */
	fun effectiveProportionalRadius(): Float {
		val current = proportionalRadiusDisplay
		if (current != null) {
			return current
		}
		val seeded =
			(minOf(livePageWidth.value, livePageHeight.value) / 8f).coerceAtLeast(MIN_UV_PROPORTIONAL_RADIUS_DISPLAY)
		proportionalRadiusDisplay = seeded
		return seeded
	}

	// Confirms the in-flight gesture: convert each moving mesh's display preview back to normalized
	// UV and commit as ONE undo step, then clear the operator (its teardown resyncs the renderer to
	// the committed model the bridge republishes).  A null preview means no movement - nothing commits.
	fun confirmGesture() {
		val committed = preview
		val captured = capture
		if (committed != null && captured != null) {
			val newUvsByDrawable = LinkedHashMap<DrawableId, FloatArray>(captured.drawableIds.size)
			val vertexIndicesByDrawable = LinkedHashMap<DrawableId, List<Int>>(captured.drawableIds.size)
			for (meshIndex in captured.drawableIds.indices) {
				val capturedId = captured.drawableIds[meshIndex]
				val transformed = committed[capturedId] ?: continue
				newUvsByDrawable[capturedId] = displayToUv(transformed, captured.pageWidth, captured.pageHeight)
				// The moved set, not just the covered set: proportional editing moves weighted
				// unselected vertices too, and the change metadata must name every vertex touched.
				vertexIndicesByDrawable[capturedId] = captured.movedIndices[meshIndex].toList()
			}
			if (newUvsByDrawable.isNotEmpty()) {
				session.commitMeshUvs(MeshChange.MoveUvs(vertexIndicesByDrawable), newUvsByDrawable)
			}
		}
		session.clearUvOperator()
	}

	// Drives the modal preview for one virtual-pointer position: the shared operator math over the
	// frozen display arrays (identity space - no inverse), converted back to UV and folded into an
	// uncommitted model for the puppet renderer.  Shared by Move and the radius Scroll.  False when
	// the capture has not landed yet.
	fun driveModalPreview(operator: MeshOperatorKind, virtualPointer: Offset, activeCamera: ViewportCamera, size: IntSize): Boolean {
		val start = gestureStart ?: return false
		val captured = capture ?: return false
		val frame = TransformGestureFrame(captured.anchor, start, virtualPointer, session.axisConstraint.value, activeCamera, size)
		val newPreview = LinkedHashMap<DrawableId, FloatArray>(captured.drawableIds.size)
		var folded = session.model.value
		for (meshIndex in captured.drawableIds.indices) {
			val transformedDisplay =
				applyOperator(
					operator,
					captured.originalDisplay[meshIndex],
					captured.groups[meshIndex],
					frame,
					captured.influences[meshIndex],
					captured.rotationTracker,
				)
			val capturedId = captured.drawableIds[meshIndex]
			newPreview[capturedId] = transformedDisplay
			folded = folded.withMeshUvs(capturedId, displayToUv(transformedDisplay, captured.pageWidth, captured.pageHeight))
		}
		preview = newPreview
		liveRenderSync.value?.previewModel(folded)
		return true
	}

	// The modal gesture's commit-side seam over the shared pointer-side controller.
	val modalTarget =
		object : ModalTransformTarget {
			override fun drivePreview(virtualPointer: Offset, camera: ViewportCamera, size: IntSize): Boolean {
				// Defensive ownership check (the pointer loop already gates): only the initiating area drives.
				val operator = session.activeUvOperator.value?.takeIf { it.areaId == areaId } ?: return false
				return driveModalPreview(operator.kind, virtualPointer, camera, size)
			}

			override fun confirm() {
				confirmGesture()
			}

			override fun cancel() {
				session.clearUvOperator()
			}

			override fun onScroll(steps: Float, camera: ViewportCamera, size: IntSize) {
				// The wheel resizes the display-unit influence radius mid-gesture (the Edit overlay's
				// behavior, in this space's units): geometric steps, weights re-derived from the frozen
				// originals, preview re-driven immediately so the mapping responds without pointer motion.
				val operator = session.activeUvOperator.value?.takeIf { it.areaId == areaId } ?: return
				val proportional = session.proportionalEdit.value
				val captured = capture
				if (steps != 0f && proportional != null && captured != null) {
					val maxRadius = 4f * maxOf(captured.pageWidth, captured.pageHeight)
					val resized =
						(effectiveProportionalRadius() * PROPORTIONAL_RADIUS_STEP_FACTOR.pow(-steps))
							.coerceIn(MIN_UV_PROPORTIONAL_RADIUS_DISPLAY, maxRadius)
					proportionalRadiusDisplay = resized
					captured.applyProportional(proportional, resized)
					driveModalPreview(operator.kind, cursorWrap.virtualPointer(lastPointer), camera, size)
				}
			}
		}

	// A tool change - armed, cleared by Escape / right-click, or switched between box and circle -
	// resolves any in-flight marquee gesture (the stroke commits, the box abandons).  Keyed on the
	// tool KIND derived from the AREA-OWNED tool, exactly like the Edit overlay: a brush resize makes
	// a new Circle(radius) that must not wipe the stroke, and a re-arm from another area reads as
	// live-to-absent here.  The race-free cancel path is the request collector below; this is the
	// backstop for tool switches and unmounts, where no signal is sent.
	val ownedSelectTool = activeSelectTool?.takeIf { tool -> tool.areaId == areaId }
	val selectToolKind =
		when (ownedSelectTool) {
			null -> 0
			is ActiveSelectTool.BoxArmed -> 1
			is ActiveSelectTool.Circle -> 2
		}
	LaunchedEffect(selectToolKind) {
		marquee.cancel()
	}

	// The race-free box cancel: the shell fires this for every Edit-mode select-gesture cancel, and an
	// already-suspended collector resumes before the mouse release could commit a cancelled box.
	LaunchedEffect(session) {
		session.meshGestureCancelRequests.collect {
			marquee.cancel()
		}
	}

	// Select Linked (Blender's L / Ctrl+L): the executing area was resolved at dispatch into the
	// payload, so this gate is deterministic.  UV islands are topology islands (UVs share the vertex
	// index space), so the shared flood runs verbatim over the display geometry.
	LaunchedEffect(session) {
		session.selectLinkedRequests.collect { request ->
			if (session.mode.value != EditorMode.Edit || request.areaId != areaId) {
				return@collect
			}
			handleSelectLinkedRequest(session, liveGeometries.value, request.fromSelection, lastPointer, liveCamera.value, liveSize.value)
		}
	}

	// The UV snap pie (Shift+S over the UV editor): the executing area was resolved at dispatch into
	// the payload, so this gate is deterministic.  The executor owns the shown page's dimensions and
	// the covered display geometry, so it performs the snap over the texture coordinates here.
	LaunchedEffect(session) {
		session.uvSnapRequests.collect { request ->
			if (session.mode.value != EditorMode.Edit || request.areaId != areaId) {
				return@collect
			}
			handleUvSnapRequest(session, liveGeometries.value, livePageWidth.value, livePageHeight.value, request.kind)
		}
	}

	// Enter confirms the modal gesture (mirroring a primary click), gated to the INITIATING area
	// through the UV latch itself - the mesh and object latches are mutually exclusive with it, so an
	// Edit-overlay confirm can never double-commit with this one.
	LaunchedEffect(session) {
		collectModalConfirmRequests(session, { session.activeUvOperator.value?.areaId == areaId }) {
			confirmGesture()
		}
	}

	// A proportional toggle or falloff change MID-GESTURE re-derives the capture's weights from its
	// frozen originals (the keyboard-side complement of the scroll resize).
	LaunchedEffect(session) {
		session.proportionalEdit.collect { state ->
			val captured = capture
			if (captured != null && session.activeUvOperator.value != null) {
				captured.applyProportional(state, effectiveProportionalRadius())
			}
		}
	}

	// Start the modal gesture as a UV operator latches IN THIS AREA; tear it down (resyncing the
	// renderer to the committed model) as it clears.  The capture covers only the shown meshes with
	// covered vertices; the anchor follows the pivot mode in display space.
	LaunchedEffect(activeOperator) {
		val operator = activeOperator?.takeIf { it.areaId == areaId }
		if (operator != null) {
			val capturedIds = ArrayList<DrawableId>()
			val displayList = ArrayList<FloatArray>()
			val indicesList = ArrayList<Set<Int>>()
			val triangleList = ArrayList<IntArray>()
			var coveredSumX = 0f
			var coveredSumY = 0f
			var coveredCount = 0
			val selection = session.meshSelection.value
			for (geometry in liveGeometries.value) {
				val elements = selection.elementsOf(geometry.drawableId)
				if (elements.isEmpty()) {
					continue
				}
				val coveredIndices = MeshTopology.coveredVertexIndices(elements, geometry.indices)
				if (coveredIndices.isEmpty()) {
					continue
				}
				capturedIds.add(geometry.drawableId)
				displayList.add(geometry.positions.copyOf())
				indicesList.add(coveredIndices)
				triangleList.add(geometry.indices)
				for (vertexIndex in coveredIndices) {
					coveredSumX += geometry.positions[vertexIndex * 2]
					coveredSumY += geometry.positions[vertexIndex * 2 + 1]
					coveredCount++
				}
			}
			if (capturedIds.isEmpty()) {
				// Nothing movable on the shown page (the selection's covered meshes live elsewhere or
				// carry no editable UVs): drop the operator.
				session.clearUvOperator()
			} else {
				val coveredMedian = (coveredSumX / coveredCount) to (coveredSumY / coveredCount)
				val pivotMode = session.pivotMode.value
				val anchor =
					when (pivotMode) {
						TransformPivotMode.MedianPoint, TransformPivotMode.IndividualOrigins -> coveredMedian
						TransformPivotMode.ActiveElement -> {
							val active = selection.activeElement
							val activeListIndex = active?.let { candidate -> capturedIds.indexOf(candidate.drawableId) } ?: -1
							if (active != null && activeListIndex >= 0) {
								val activeCovered = MeshTopology.coveredVertexIndices(setOf(active.element), triangleList[activeListIndex])
								if (activeCovered.isNotEmpty()) {
									MeshTransforms.medianPivot(displayList[activeListIndex], activeCovered)
								} else {
									coveredMedian
								}
							} else {
								coveredMedian
							}
						}
						TransformPivotMode.Cursor ->
							session.uvCursor.value?.let { cursor ->
								uvToDisplayX(cursor.u, livePageWidth.value) to uvToDisplayY(cursor.v, livePageHeight.value)
							} ?: coveredMedian
					}
				val groups =
					capturedIds.indices.map { meshIndex ->
						if (pivotMode == TransformPivotMode.IndividualOrigins) {
							TransformPivots.islandGroups(displayList[meshIndex], indicesList[meshIndex], triangleList[meshIndex])
						} else {
							TransformPivots.sharedGroup(indicesList[meshIndex], anchor.first, anchor.second)
						}
					}
				capture =
					UvGestureCapture(
						drawableIds = capturedIds,
						originalDisplay = displayList,
						capturedIndices = indicesList,
						triangleIndices = triangleList,
						groups = groups,
						anchor = anchor,
						pageWidth = livePageWidth.value,
						pageHeight = livePageHeight.value,
					).also { fresh ->
						fresh.applyProportional(session.proportionalEdit.value, effectiveProportionalRadius())
					}
				gestureStart = lastPointer
				preview = null
				cursorWrap.reset()
			}
		} else {
			// Resync the renderer only when THIS overlay owned a gesture: the else branch also runs at
			// mount and when another area's operator latches, and an unguarded resync from a bystander
			// would stomp the initiating area's live preview (the ownership doc's mid-gesture-split guard).
			if (capture != null) {
				liveRenderSync.value?.resync()
			}
			capture = null
			preview = null
			gestureStart = null
			cursorWrap.reset()
		}
	}

	// The unmount-mid-gesture raster guard: leaving Edit mode or closing the area disposes this
	// overlay, cancelling the latch effect above WITHOUT running its else branch - so the renderer
	// would strand on the un-committed preview.  The latch itself is cleared by the mode-switch /
	// restore teardown and the space's area-death effect; this restores the raster.
	DisposableEffect(areaId, session) {
		onDispose {
			if (capture != null) {
				liveRenderSync.value?.resync()
			}
		}
	}

	// What the draw pass reflects: the live circle stroke while one is in flight, else the committed
	// selection - so painted elements light up immediately during a Circle stroke.
	val effectiveSelection = marquee.circleStroke ?: meshSelection

	// What the draw pass highlights per mesh and domain (Blender's derive-up / flush-down rules).
	val highlightByDrawable =
		remember(effectiveSelection, geometries) {
			geometries.associate { geometry ->
				geometry.drawableId to
					buildHighlightSets(
						elements = effectiveSelection.elementsOf(geometry.drawableId),
						active = effectiveSelection.activeElement?.takeIf { activeElement -> activeElement.drawableId == geometry.drawableId }?.element,
						selectMode = effectiveSelection.selectMode,
						triangleIndices = geometry.indices,
					)
			}
		}

	// clipToBounds: Canvas drawing is not clipped to the layout bounds by default, so an off-page
	// vertex would otherwise paint over the AreaHeader and neighbouring areas.
	Box(
		modifier =
			modifier
				.fillMaxSize()
				.clipToBounds()
				.onGloballyPositioned { coordinates -> areaScreenOrigin = coordinates.positionOnScreen() }
				// While THIS AREA'S modal transform runs, hide the OS cursor so only the overlay's drawn
				// cursor (double-arrow, crosshair, or brush circle) shows; a gesture owned by another
				// area leaves this cursor alone.
				.then(
					if (activeOperator?.areaId == areaId || ownedSelectTool != null) {
						Modifier.pointerHoverIcon(hiddenPointerIcon(), overrideDescendants = true)
					} else {
						Modifier
					},
				),
	) {
		Canvas(
			modifier =
				Modifier
					.fillMaxSize()
					.pointerInput(areaId) {
						awaitPointerEventScope {
							while (true) {
								val event = awaitPointerEvent()
								val change = event.changes.firstOrNull() ?: continue
								lastPointer = change.position
								val latchedUvOperator = session.activeUvOperator.value
								val latchedTool = session.activeSelectTool.value
								// A gesture belongs to its initiating area, and the viewport-owned operators
								// can never belong to a UV area: while any of them (or another area's UV
								// operator / armed tool) is live, this overlay is fully inert - Escape and
								// Enter stay global through the shell key ladder, and navigation still falls
								// through to the layer below.
								if (session.activeMeshOperator.value != null ||
									session.activeObjectOperator.value != null ||
									(latchedUvOperator != null && latchedUvOperator.areaId != areaId) ||
									(latchedTool != null && latchedTool.areaId != areaId)
								) {
									continue
								}
								val activeCamera = liveCamera.value
								val size = liveSize.value
								if (latchedUvOperator != null) {
									// MODAL: the shared controller drives the transform over the captured
									// mapping and swallows every event.
									lastPointer = modalController.handleEvent(event, change, modalTarget, activeCamera, size, areaScreenOrigin)
								} else if (latchedTool is ActiveSelectTool.Circle) {
									// CIRCLE SELECT: the shared controller paints / erases / commits the stroke
									// and consumes every event (paired with the navigation gate so MMB / wheel
									// do not also pan / zoom).
									marquee.handleCircleEvent(event, change, latchedTool.radiusPx, activeCamera, size)
								} else {
									// IDLE: element selection over the shared session state.  Only
									// primary-driven events are consumed, so middle-drag pan and wheel zoom
									// fall through to the space's navigation layer.  Armed Box-select starts a
									// box on any press (hit-tests skipped) and disarms after the drag.
									val boxArmed = latchedTool is ActiveSelectTool.BoxArmed
									when (event.type) {
										PointerEventType.Press ->
											if (event.buttons.isSecondaryPressed && event.keyboardModifiers.isShiftPressed) {
												// Shift+RightClick places the UV cursor at the pointer (the
												// viewport's 2D-cursor gesture, in this space's units); the
												// Cursor pivot mode and Mirror anchor on it.
												val (displayX, displayY) = screenToWorld(change.position.x, change.position.y, activeCamera, size)
												session.setUvCursor(
													displayToUvU(displayX, livePageWidth.value),
													displayToUvV(displayY, livePageHeight.value),
												)
												change.consume()
											} else if (boxArmed && event.buttons.isSecondaryPressed) {
												session.clearSelectTool()
												change.consume()
											} else if (event.buttons.isPrimaryPressed) {
												val current = session.meshSelection.value
												val hit = hitTestMeshes(current.selectMode, liveGeometries.value, change.position, activeCamera, size)
												if (!boxArmed && hit != null) {
													val modifiers = event.keyboardModifiers
													val updated =
														when {
															// Shift and Ctrl both toggle membership (Blender-style).
															modifiers.isShiftPressed || modifiers.isCtrlPressed || modifiers.isMetaPressed ->
																MeshSelectionOps.toggle(current, hit.drawableId, hit.element)
															else -> MeshSelectionOps.replace(current, hit.drawableId, hit.element)
														}
													session.setMeshSelection(updated)
												} else {
													marquee.beginBox(change.position)
												}
												change.consume()
											}

										PointerEventType.Move ->
											if (marquee.dragBox(change.position)) {
												change.consume()
											}

										PointerEventType.Release -> {
											val boxRelease = marquee.releaseBox(change.position, event.keyboardModifiers.isShiftPressed, activeCamera, size)
											if (boxRelease != BoxRelease.None) {
												if (boxRelease == BoxRelease.Click && !boxArmed && !session.meshSelection.value.isEmpty) {
													// A sub-threshold click on empty canvas clears - but not while
													// armed, where a bare click just disarms the tool (below).
													session.setMeshSelection(MeshSelectionOps.clear(session.meshSelection.value))
												}
												// Armed Box-select is one-shot: disarm after the drag (or a bare click).
												if (boxArmed) {
													session.clearSelectTool()
												}
												change.consume()
											}
										}

										else -> {}
									}
								}
							}
						}
					},
		) {
			// The wireframes draw from the live preview arrays during a gesture - this Canvas IS the
			// display (no asynchronous raster to lag behind, unlike the viewport overlays' frame model).
			// The live circle stroke drives the highlighted domain so painted elements light up mid-stroke.
			val activePreview = preview.takeIf { capture != null }
			for (geometry in geometries) {
				val highlight = highlightByDrawable[geometry.drawableId] ?: continue
				drawMeshWireframe(
					positions = activePreview?.get(geometry.drawableId) ?: geometry.positions,
					indices = geometry.indices,
					edges = geometry.edges,
					highlight = highlight,
					selectMode = effectiveSelection.selectMode,
					colors = gizmoColors,
					camera = camera,
					size = IntSize(widthPx, heightPx),
				)
			}

			// The UV cursor (the texture-space 2D cursor): the shared crosshair marker at its display
			// position, under the gesture chrome so the HUD reads over it.
			uvCursor?.let { cursor ->
				drawCursorMarker(
					center =
						worldToScreen(
							uvToDisplayX(cursor.u, pageWidth),
							uvToDisplayY(cursor.v, pageHeight),
							camera,
							IntSize(widthPx, heightPx),
						),
					tint = overlayColors.viewportBadgeText,
				)
			}

			drawRubberBand(marquee.boxStart, marquee.boxCurrent, overlayStyle)

			// Armed select-tool affordances (Blender B / C), shared chrome with the viewport overlays.
			// Only the arming area draws them - the latch is session-global, every UV area composes this.
			drawSelectToolAffordances(
				tool = ownedSelectTool,
				pointer = lastPointer,
				boxDragInFlight = marquee.boxStart != null,
				viewport = Size(widthPx.toFloat(), heightPx.toFloat()),
				style = overlayStyle,
				crosshairCursor = LocalUmamoCursors.crosshair,
			)

			// Modal transform HUD (axis line, pivot dash, drawn cursor, proportional ring), shared
			// chrome with the viewport overlays.  Only the initiating area draws it - the capture
			// exists solely in the overlay whose area the operator latch names.
			val hudOperator = activeOperator
			val hudPivot = capture?.anchor
			if (hudOperator != null && hudPivot != null) {
				val ringRadiusPx =
					if (proportionalEdit != null) {
						(proportionalRadiusDisplay ?: 0f).takeIf { radius -> radius > 0f }?.times(camera.zoom)
					} else {
						null
					}
				drawModalTransformHud(
					axisConstraint = axisConstraint,
					pivotScreen = worldToScreen(hudPivot.first, hudPivot.second, camera, IntSize(widthPx, heightPx)),
					virtualPointer = cursorWrap.virtualPointer(lastPointer),
					realPointer = lastPointer,
					viewport = Size(widthPx.toFloat(), heightPx.toFloat()),
					lineColor = overlayColors.viewportMarquee,
					pointerCursor = LocalUmamoCursors.nsewScroll,
					proportionalRadiusPx = ringRadiusPx,
				)
			}
		}

		// The modal status badge (top center), the shared HUD piece: only the initiating area shows
		// it, and the proportional segment reads this space's display-unit (texel) radius.
		val badgeOperator = activeOperator?.takeIf { operator -> operator.areaId == areaId }
		if (badgeOperator != null) {
			val badgeRadius = if (proportionalEdit != null) proportionalRadiusDisplay?.roundToInt() else null
			ModalOperatorBadge(
				operatorKind = badgeOperator.kind,
				axisConstraint = axisConstraint,
				proportionalState = if (badgeRadius != null) proportionalEdit else null,
				proportionalRadius = badgeRadius,
			)
		}
	}
}
