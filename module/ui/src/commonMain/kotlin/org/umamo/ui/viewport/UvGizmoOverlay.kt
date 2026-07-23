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
import org.umamo.edit.MeshSelection
import org.umamo.edit.MeshSelectionOps
import org.umamo.edit.MeshTopology
import org.umamo.edit.MeshTransforms
import org.umamo.edit.ModalCaptureSource
import org.umamo.edit.ModalTransformCapture
import org.umamo.edit.PROPORTIONAL_RADIUS_STEP_FACTOR
import org.umamo.edit.buildModalTransformCapture
import org.umamo.edit.withMeshUvs
import org.umamo.render.ViewportCamera
import org.umamo.runtime.model.DrawableId
import org.umamo.ui.model.LocalPuppetRenderSync
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoCursors
import org.umamo.ui.theme.hiddenPointerIcon
import kotlin.math.pow
import kotlin.math.roundToInt

/** The smallest useful proportional influence radius in display (texel) units. */
private const val MIN_UV_PROPORTIONAL_RADIUS_DISPLAY = 1f

/**
 * The captured state of an in-flight UV transform: the shared [ModalTransformCapture] (its entries hold each
 * mesh's frozen display-space coordinates as their positions, plus the pivot groups, proportional halos, and
 * moved sets) together with the atlas page dimensions.  The texture-space sibling of the Edit gesture, minus
 * every deformer concern: UVs live in one flat display space, so there is no space mapping, no movement
 * transfer, and no world/local split - the operator transforms the display coordinates directly and the
 * result converts straight back to normalized UV.
 *
 * The page dimensions freeze here so the display-to-uv conversion at drive and commit always matches the
 * space the originals were mapped in (the shown page can hop mid-gesture if the active drawable changes from
 * another area).
 *
 * @property ModalTransformCapture transform The shared gesture capture (entries, groups, anchor, halos, kind).
 * @property Int pageWidth The atlas page width the display mapping used, in texels.
 * @property Int pageHeight The atlas page height the display mapping used, in texels.
 */
private class UvGesture(
	val transform: ModalTransformCapture,
	val pageWidth: Int,
	val pageHeight: Int,
)

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

	// The per-area modal-gesture bookkeeping (last pointer, capture + preview, gesture origin, area origin,
	// cursor wrap, pointer controller), the Edit overlay's shape.  The capture is the UV gesture (shared
	// transform capture + frozen page dimensions); preview holds each moving mesh's display-space coordinates.
	val gesture = remember(areaId) { ModalGestureState<UvGesture>() }

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
		val committed = gesture.preview
		val gestureData = gesture.capture
		if (committed != null && gestureData != null) {
			val transform = gestureData.transform
			val newUvsByDrawable = LinkedHashMap<DrawableId, FloatArray>(transform.entries.size)
			val vertexIndicesByDrawable = LinkedHashMap<DrawableId, List<Int>>(transform.entries.size)
			for (entry in transform.entries) {
				val transformed = committed[entry.drawableId] ?: continue
				newUvsByDrawable[entry.drawableId] = displayToUv(transformed, gestureData.pageWidth, gestureData.pageHeight)
				// The moved set, not just the covered set: proportional editing moves weighted
				// unselected vertices too, and the change metadata must name every vertex touched.
				vertexIndicesByDrawable[entry.drawableId] = entry.movedIndices.toList()
			}
			if (newUvsByDrawable.isNotEmpty()) {
				session.commitMeshUvs(MeshChange.TransformUvs(vertexIndicesByDrawable, transform.operatorKind), newUvsByDrawable)
			}
		}
		session.clearUvOperator()
	}

	// Drives the modal preview for one virtual-pointer position: the shared operator math over the
	// frozen display arrays (identity space - no inverse), converted back to UV and folded into an
	// uncommitted model for the puppet renderer.  Shared by Move and the radius Scroll.  False when
	// the capture has not landed yet.
	fun driveModalPreview(operator: MeshOperatorKind, virtualPointer: Offset, activeCamera: ViewportCamera, size: IntSize): Boolean {
		val start = gesture.gestureStart ?: return false
		val gestureData = gesture.capture ?: return false
		val transform = gestureData.transform
		val frame = TransformGestureFrame(transform.anchor, start, virtualPointer, session.axisConstraint.value, activeCamera, size)
		val newPreview = LinkedHashMap<DrawableId, FloatArray>(transform.entries.size)
		var folded = session.model.value
		for (entry in transform.entries) {
			val transformedDisplay =
				applyOperator(
					operator,
					entry.positions,
					entry.groups,
					frame,
					entry.influence,
					transform.rotationTracker,
				)
			newPreview[entry.drawableId] = transformedDisplay
			folded = folded.withMeshUvs(entry.drawableId, displayToUv(transformedDisplay, gestureData.pageWidth, gestureData.pageHeight))
		}
		gesture.preview = newPreview
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
				val gestureData = gesture.capture
				if (steps != 0f && proportional != null && gestureData != null) {
					val maxRadius = 4f * maxOf(gestureData.pageWidth, gestureData.pageHeight)
					val resized =
						(effectiveProportionalRadius() * PROPORTIONAL_RADIUS_STEP_FACTOR.pow(-steps))
							.coerceIn(MIN_UV_PROPORTIONAL_RADIUS_DISPLAY, maxRadius)
					proportionalRadiusDisplay = resized
					gestureData.transform.applyProportional(proportional, resized)
					driveModalPreview(operator.kind, gesture.cursorWrap.virtualPointer(gesture.lastPointer), camera, size)
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
	LaunchedEffect(selectToolKind(ownedSelectTool)) {
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
			handleSelectLinkedRequest(session, liveGeometries.value, request.fromSelection, gesture.lastPointer, liveCamera.value, liveSize.value)
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
			val gestureData = gesture.capture
			if (gestureData != null && session.activeUvOperator.value != null) {
				gestureData.transform.applyProportional(state, effectiveProportionalRadius())
			}
		}
	}

	// Start the modal gesture as a UV operator latches IN THIS AREA; tear it down (resyncing the
	// renderer to the committed model) as it clears.  The capture covers only the shown meshes with
	// covered vertices; the anchor follows the pivot mode in display space.
	LaunchedEffect(activeOperator) {
		val operator = activeOperator?.takeIf { it.areaId == areaId }
		if (operator != null) {
			val selection = session.meshSelection.value
			// Offer each shown mesh with covered vertices to the shared capture builder, its frozen
			// display-space coordinates as the source positions (no deformer mapping in UV space).
			val sources = ArrayList<ModalCaptureSource>()
			for (geometry in liveGeometries.value) {
				val elements = selection.elementsOf(geometry.drawableId)
				if (elements.isEmpty()) {
					continue
				}
				val coveredIndices = MeshTopology.coveredVertexIndices(elements, geometry.indices)
				if (coveredIndices.isEmpty()) {
					continue
				}
				sources.add(ModalCaptureSource(geometry.drawableId, geometry.positions.copyOf(), geometry.indices, coveredIndices))
			}
			// The two per-area anchors the shared builder cannot resolve itself, in display space: the
			// active element's own covered median and the UV cursor.  Null falls back to the shared median.
			val activeAnchor =
				run {
					val active = selection.activeElement ?: return@run null
					val activeGeometry = liveGeometries.value.firstOrNull { it.drawableId == active.drawableId } ?: return@run null
					val activeCovered = MeshTopology.coveredVertexIndices(setOf(active.element), activeGeometry.indices)
					if (activeCovered.isEmpty()) {
						null
					} else {
						MeshTransforms.medianPivot(activeGeometry.positions, activeCovered)
					}
				}
			val cursorAnchor =
				session.uvCursor.value?.let { cursor ->
					uvToDisplayX(cursor.u, livePageWidth.value) to uvToDisplayY(cursor.v, livePageHeight.value)
				}
			val transform =
				buildModalTransformCapture(
					sources = sources,
					pivotMode = session.pivotMode.value,
					// UV islands split the same as Edit mode (UVs share the vertex index space).
					individualOriginScope = IndividualOriginScope.ConnectivityIsland,
					operatorKind = operator.kind,
					activeAnchor = activeAnchor,
					cursorAnchor = cursorAnchor,
				)
			if (transform == null) {
				// Nothing movable on the shown page (the selection's covered meshes live elsewhere or
				// carry no editable UVs): drop the operator.
				session.clearUvOperator()
			} else {
				transform.applyProportional(session.proportionalEdit.value, effectiveProportionalRadius())
				gesture.begin(UvGesture(transform, livePageWidth.value, livePageHeight.value), gesture.lastPointer)
			}
		} else {
			// Resync the renderer only when THIS overlay owned a gesture: the else branch also runs at
			// mount and when another area's operator latches, and an unguarded resync from a bystander
			// would stomp the initiating area's live preview (the ownership doc's mid-gesture-split guard).
			if (gesture.end()) {
				liveRenderSync.value?.resync()
			}
		}
	}

	// The unmount-mid-gesture raster guard: leaving Edit mode or closing the area disposes this
	// overlay, cancelling the latch effect above WITHOUT running its else branch - so the renderer
	// would strand on the un-committed preview.  The latch itself is cleared by the mode-switch /
	// restore teardown and the space's area-death effect; this restores the raster.
	DisposableEffect(areaId, session) {
		onDispose {
			if (gesture.capture != null) {
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
				.onGloballyPositioned { coordinates -> gesture.areaScreenOrigin = coordinates.positionOnScreen() }
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
								gesture.lastPointer = change.position
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
									gesture.lastPointer = gesture.modalController.handleEvent(event, change, modalTarget, activeCamera, size, gesture.areaScreenOrigin)
								} else if (latchedTool is ActiveSelectTool.Circle) {
									// CIRCLE SELECT: the shared controller paints / erases / commits the stroke
									// and consumes every event (paired with the navigation gate so MMB / wheel
									// do not also pan / zoom).
									marquee.handleCircleEvent(event, change, latchedTool.radiusPx, activeCamera, size)
								} else {
									// IDLE: element selection, shared with the 2D viewport's Edit overlay.  Only
									// primary-driven events are consumed, so middle-drag pan and wheel zoom fall
									// through; armed Box-select boxes on any press and disarms.  Shift+RightClick
									// places the UV cursor (the viewport's 2D-cursor gesture, in texture space).
									handleIdleMeshSelectionEvent(
										event = event,
										change = change,
										session = session,
										geometries = liveGeometries.value,
										marquee = marquee,
										boxArmed = latchedTool is ActiveSelectTool.BoxArmed,
										camera = activeCamera,
										size = size,
										placeCursor = { displayX, displayY ->
											session.setUvCursor(
												displayToUvU(displayX, livePageWidth.value),
												displayToUvV(displayY, livePageHeight.value),
											)
										},
									)
								}
							}
						}
					},
		) {
			// The wireframes draw from the live preview arrays during a gesture - this Canvas IS the
			// display (no asynchronous raster to lag behind, unlike the viewport overlays' frame model).
			// The live circle stroke drives the highlighted domain so painted elements light up mid-stroke.
			val activePreview = gesture.preview.takeIf { gesture.capture != null }
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
				pointer = gesture.lastPointer,
				boxDragInFlight = marquee.boxStart != null,
				viewport = Size(widthPx.toFloat(), heightPx.toFloat()),
				style = overlayStyle,
				crosshairCursor = LocalUmamoCursors.crosshair,
			)

			// Modal transform HUD (axis line, pivot dash, drawn cursor, proportional ring), shared
			// chrome with the viewport overlays.  Only the initiating area draws it - the capture
			// exists solely in the overlay whose area the operator latch names.
			val hudOperator = activeOperator
			val hudPivot = gesture.capture?.transform?.anchor
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
					virtualPointer = gesture.cursorWrap.virtualPointer(gesture.lastPointer),
					realPointer = gesture.lastPointer,
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
