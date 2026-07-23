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
import org.umamo.edit.MeshElement
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
import org.umamo.edit.withMeshPositions
import org.umamo.render.ViewportCamera
import org.umamo.render.eval.DrawableSpaceMapping
import org.umamo.render.eval.drawableLocalPosed
import org.umamo.render.eval.drawableSpaceMapping
import org.umamo.render.pick.PickCandidate
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoCursors
import org.umamo.ui.theme.hiddenPointerIcon
import org.umamo.ui.transform.movementToBase
import kotlin.math.pow

/**
 * One session mesh's live geometry at the neutral pose: its mesh, derived unique edges, deformer-chain
 * mapping, rest shape (displayed = base + the neutral keyform blend), and world projection.  The Edit
 * session spans several meshes, so the overlay carries one of these per drawable.
 *
 * @property DrawableId drawableId The drawable this geometry belongs to.
 * @property DrawableMesh mesh The drawable's live mesh (positions, uvs, indices).
 * @property List<MeshElement.Edge> edges The mesh's unique edges, in first-encounter order.
 * @property DrawableSpaceMapping mapping The local-to-world deformer-chain projection.
 * @property FloatArray displayed The local rest shape the movement transfer anchors on.
 * @property FloatArray worldPosed The displayed shape projected to world space.
 */
internal class EditMeshGeometry(
	val drawableId: DrawableId,
	val mesh: DrawableMesh,
	val edges: List<MeshElement.Edge>,
	val mapping: DrawableSpaceMapping,
	val displayed: FloatArray,
	val worldPosed: FloatArray,
) {
	/** The geometry-source-agnostic view the shared element queries and wireframe draw take. */
	val gizmo = GizmoMeshGeometry(drawableId, mesh.indices, edges, worldPosed)
}

/**
 * One session mesh's geometry as the DISPLAYED frame rendered it, for the draw pass (the wireframe must
 * lag together with the raster, not lead it).  worldPosed is null when the frame could not project the
 * drawable (a hidden ancestor) - the draw pass then falls back to the live shape.
 *
 * @property IntArray indices The frame mesh's triangle vertex indices.
 * @property List<MeshElement.Edge> edges The frame mesh's unique edges.
 * @property FloatArray? worldPosed The frame's world-projected shape, or null when unprojectable.
 */
private class FrameMeshGeometry(
	val indices: IntArray,
	val edges: List<MeshElement.Edge>,
	val worldPosed: FloatArray?,
)

/**
 * The captured state of an in-flight Edit-mode transform, frozen when the operator latches so the whole
 * drag is atomic across the session meshes.  Each list is parallel to [drawableIds] and covers only the
 * meshes that actually move (those with covered vertices): [mappings] projects each mesh's local shape
 * to world (and back), [originalDisplayed] anchors the movement transfer, [originalWorld] is what the
 * pointer transforms, [capturedBase] is the rest mesh the movement lands on, and [capturedIndices] is
 * the union of vertices each mesh's selected elements cover.  [groups] carries each mesh's pivot
 * groups per the active [org.umamo.edit.TransformPivotMode] (one shared-anchor group, or one per
 * connectivity island for Individual Origins); [anchor] is the world-space point the pointer math
 * measures factors and angles against (and the dashed HUD line's origin).
 *
 * [influences] and [movedIndices] carry the proportional-editing state: per mesh, the influenced
 * UNSELECTED vertices (falloff weight + nearest covered vertex, the island-ownership key) and the full
 * set of vertices the gesture moves (covered plus influenced).  They are the capture's only mutable
 * fields (with [rotationTracker]'s accumulation) because a mid-gesture radius scroll re-derives them -
 * always from the frozen originals - while everything else stays fixed for the drag.
 */
private class EditGestureCapture(
	val drawableIds: List<DrawableId>,
	val mappings: List<DrawableSpaceMapping>,
	val originalDisplayed: List<FloatArray>,
	val originalWorld: List<FloatArray>,
	val capturedBase: List<FloatArray>,
	val capturedIndices: List<Set<Int>>,
	val triangleIndices: List<IntArray>,
	val groups: List<List<TransformPivotGroup>>,
	val anchor: Pair<Float, Float>,
) {
	/** The Rotate gesture's angle accumulator (unwrapped per-move increments; see RotationAngleTracker). */
	val rotationTracker = RotationAngleTracker()

	var influences: List<Map<Int, ProportionalInfluence>> = List(drawableIds.size) { emptyMap() }
		private set

	var movedIndices: List<Set<Int>> = capturedIndices
		private set

	/**
	 * Recomputes the proportional influence maps from the FROZEN original world shapes (never the live
	 * preview - a radius change mid-gesture must re-derive from the same geometry the gesture started
	 * on, or the influence ring would feed back on itself).  Connected Only measures the distances
	 * geodesically along [triangleIndices]' edge graph instead of straight-line.  Null state
	 * (proportional off, or a Vertex Slide) clears the influences so the gesture moves only the
	 * covered vertices.
	 *
	 * @param ProportionalEditState? state The proportional configuration, or null for none.
	 */
	fun applyProportional(state: ProportionalEditState?) {
		influences =
			drawableIds.indices.map { meshIndex ->
				when {
					state == null -> emptyMap()
					state.connectedOnly ->
						proportionalInfluencesConnected(
							originalWorld[meshIndex],
							capturedIndices[meshIndex],
							state.radiusWorld,
							state.falloff,
							triangleIndices[meshIndex],
						)
					else -> proportionalInfluences(originalWorld[meshIndex], capturedIndices[meshIndex], state.radiusWorld, state.falloff)
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
 * The Vertex Slide gesture's frozen context: the active vertex and its incident neighbor candidates.
 * Only the candidates freeze at latch - the best edge is re-picked from the live pointer every move,
 * so the slide follows the pointer across the vertex's fan instead of locking to the first edge.
 *
 * @property DrawableId drawableId The mesh the active vertex lives in.
 * @property Int activeVertex The sliding vertex's index.
 * @property IntArray neighborIndices The active vertex's incident neighbors (the edge candidates).
 */
private class SlideContext(
	val drawableId: DrawableId,
	val activeVertex: Int,
	val neighborIndices: IntArray,
)

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
 * rotation children too. A drag commits by movement transfer: `newBase = base + (displayed' - displayed)`,
 * writing only DrawableMesh.positions; the neutral blend cancels out of the subtraction, so no keyform
 * cell resolution is involved, and blend-shape deltas (stored relative to base) follow the edit.
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
 * 編集モードのギズモ重畳。ベースメッシュの静止状態のみを編集する。頂点・辺・面の選択モードを持ち、
 * モーダルな G / S / R を駆動する。
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
 * @param Function onOverlapRequest Opens the overlap-picker popup for an Alt+Q over 2+ stacked
 *        candidates (the pick switches the edited mesh).
 * @param Modifier modifier The layout modifier.
 */
@Composable
fun EditGizmoOverlay(
	areaId: String,
	service: PuppetViewportService,
	session: EditorSession,
	camera: ViewportCamera?,
	frameModel: PuppetModel?,
	widthPx: Int,
	heightPx: Int,
	onOverlapRequest: (Offset, List<PickCandidate>) -> Unit,
	modifier: Modifier = Modifier,
) {
	val mode by session.mode.collectAsState()
	val meshSelection by session.meshSelection.collectAsState()
	val model by session.model.collectAsState()
	val activeOperator by session.activeMeshOperator.collectAsState()
	val activeSelectTool by session.activeSelectTool.collectAsState()
	val axisConstraint by session.axisConstraint.collectAsState()
	val proportionalEdit by session.proportionalEdit.collectAsState()
	val gizmoColors = rememberMeshEditColors()
	// Theme-level overlay chrome (the marquee) comes from the palette; the mesh gizmo colors above stay a
	// separate settings-backed system.
	val overlayColors = LocalUmamoColors.current

	val sessionDrawableIds = meshSelection.drawableIds
	if (mode != EditorMode.Edit || sessionDrawableIds.isEmpty() || camera == null || frameModel == null) {
		return
	}
	// The shared two-tone marching-ants style for the box / circle / crosshair affordances.
	val overlayStyle = selectionOverlayStyle(overlayColors)

	// Per-session-mesh geometry at the constant neutral pose Edit mode is pinned to: each mesh's rest
	// shape (displayed = base + the neutral keyform blend), its deformer-chain mapping, and its world
	// projection, computed for every mesh in the session's selection.  A drawable whose mapping cannot
	// be built (a hidden ancestor) is skipped: it cannot be drawn, so it cannot be edited.
	// Keyed on the model (a commit swaps it) and the session's mesh list; NOT per rendered frame.
	val liveGeometry =
		remember(model, sessionDrawableIds) {
			sessionDrawableIds.mapNotNull { drawableId ->
				val mesh = model.drawables.firstOrNull { it.id == drawableId }?.mesh ?: return@mapNotNull null
				val mapping = drawableSpaceMapping(model, emptyMap(), drawableId) ?: return@mapNotNull null
				val displayed = drawableLocalPosed(model, emptyMap(), drawableId) ?: mesh.positions
				EditMeshGeometry(
					drawableId = drawableId,
					mesh = mesh,
					edges = MeshTopology.uniqueEdges(mesh.indices),
					mapping = mapping,
					displayed = displayed,
					worldPosed = mapping.localToWorld(displayed),
				)
			}
		}
	if (liveGeometry.isEmpty()) {
		return
	}

	// Live values the long-running pointer loop and the marquee callbacks read (they are keyed only on
	// areaId, so they must not close over a stale camera / size / shape / topology when the model, pan,
	// or resize change mid-edit).
	val liveCamera = rememberUpdatedState(camera)
	val liveSize = rememberUpdatedState(IntSize(widthPx, heightPx))
	val liveGeometryState = rememberUpdatedState(liveGeometry)

	// The marquee (box + circle) machinery over mesh elements: the stroke / rubber-band state and event
	// rules are shared (MarqueeSelectController); the callbacks bind them to the element domain.
	val marquee =
		remember(areaId) {
			MarqueeSelectController<MeshSelection>(
				seedStroke = { session.meshSelection.value },
				stampStroke = { working, erasing, center, radiusPx, stampCamera, stampSize ->
					circleSelection(working, erasing, center, radiusPx, liveGeometryState.value.map { it.gizmo }, stampCamera, stampSize)
				},
				commitStroke = { stroke -> session.setMeshSelection(stroke) },
				applyBox = { start, end, additive, boxCamera, boxSize ->
					val selection = session.meshSelection.value
					val insideByDrawable =
						liveGeometryState.value.associate { geometry ->
							geometry.drawableId to elementsInBox(selection.selectMode, geometry.gizmo, start, end, boxCamera, boxSize)
						}
					session.setMeshSelection(MeshSelectionOps.box(selection, insideByDrawable, additive = additive))
				},
				setCircleRadius = { radiusPx -> session.setCircleRadius(radiusPx) },
				clearTool = { session.clearSelectTool() },
			)
		}

	// What the draw pass reflects: the live stroke while one is in flight, else the committed selection - so
	// painted elements light up immediately during a Circle stroke.
	val effectiveSelection = marquee.circleStroke ?: meshSelection

	// What the draw pass highlights per mesh and domain: the effective selection for the current select
	// mode plus the cross-domain highlights derived with Blender's rules (both-endpoints for edges,
	// all-own-elements for faces). Derived for display only - never stored back into the selection.
	val highlightByDrawable =
		remember(effectiveSelection, liveGeometry) {
			liveGeometry.associate { geometry ->
				geometry.drawableId to
					buildHighlightSets(
						elements = effectiveSelection.elementsOf(geometry.drawableId),
						active = effectiveSelection.activeElement?.takeIf { it.drawableId == geometry.drawableId }?.element,
						selectMode = effectiveSelection.selectMode,
						triangleIndices = geometry.mesh.indices,
					)
			}
		}

	// The DISPLAYED frame's geometry per session mesh - what the raster under this overlay actually
	// shows.  The draw pass poses the wireframes from this (not the live session shape / working arrays),
	// so during a transform every mesh lags together with the raster instead of racing ahead of it.
	// Recomputed per rendered frame, but ONLY the frame's changed positions are per-frame work: the
	// topology (indices, edge set) and the deformer-chain mapping are invariant during a drag, so they
	// are reused from liveGeometry rather than rebuilt every frame.  withMeshPositions shares the indices
	// array by reference, and Edit mode pins the pose so the deformers never move - rebuilding them per
	// frame (a uniqueEdges plus a full buildDeformerWorlds per drawable) would make an all-vertex
	// transform lag, unlike Object mode's transform, which caches.  A missing member falls the draw pass
	// back to the session shape (a pathological transient, e.g. a hidden ancestor).
	val frameGeometryByDrawable =
		remember(frameModel, liveGeometry) {
			// One O(D) index of the frame's drawables, so the per-mesh lookups below are not an O(D^2) scan.
			val frameDrawablesById = frameModel.drawables.associateBy { it.id }
			liveGeometry.mapNotNull { liveEntry ->
				val drawableId = liveEntry.drawableId
				val frameMesh = frameDrawablesById[drawableId]?.mesh ?: return@mapNotNull null
				// Topology is unchanged during a transform (indices shared by reference), so reuse the edge set;
				// recompute only across a topology edit, where the frame briefly trails on the old indices.
				val edges =
					if (frameMesh.indices === liveEntry.mesh.indices) {
						liveEntry.edges
					} else {
						MeshTopology.uniqueEdges(frameMesh.indices)
					}
				// The deformers do not move during a mesh drag, so liveEntry.mapping (built once from the session
				// model) projects the frame's changed local positions correctly - no per-frame buildDeformerWorlds.
				val frameDisplayed = drawableLocalPosed(frameModel, emptyMap(), drawableId) ?: frameMesh.positions
				drawableId to
					FrameMeshGeometry(
						indices = frameMesh.indices,
						edges = edges,
						worldPosed = liveEntry.mapping.localToWorld(frameDisplayed),
					)
			}.toMap()
		}

	// Gesture-local capture: frozen at the moment an operator latches so the whole drag is atomic across
	// the session meshes (see EditGestureCapture); preview holds each moving mesh's transformed DISPLAYED
	// shape.
	var lastPointer by remember(areaId) { mutableStateOf(Offset.Zero) }
	var capture by remember(areaId) { mutableStateOf<EditGestureCapture?>(null) }
	var preview by remember(areaId) { mutableStateOf<Map<DrawableId, FloatArray>?>(null) }
	var gestureStart by remember(areaId) { mutableStateOf<Offset?>(null) }

	// The area's top-left in absolute screen pixels, tracked so a modal cursor-wrap can convert an
	// area-local target into the screen coordinate AWT Robot needs. Null until first layout, and unused on
	// platforms without cursor warp.
	var areaScreenOrigin by remember(areaId) { mutableStateOf<Offset?>(null) }

	// The gesture's cursor-wrap bookkeeping: the accumulated wrap offset (the virtual pointer), the
	// stale pre-warp-event guard, and the actual-landing fold rule (see CursorWrapState).
	val cursorWrap = remember(areaId) { CursorWrapState() }

	// The shared pointer-side driver of the modal gesture (stale discard, drive, wrap, buttons).
	val modalController = remember(areaId) { ModalTransformController(cursorWrap) }

	// The Vertex Slide edge, frozen at latch: (drawable, sliding vertex, edge's other endpoint) - the
	// incident edge whose far endpoint sat nearest the pointer when the operator armed.
	var slideContext by remember(areaId) { mutableStateOf<SlideContext?>(null) }

	// Confirms the in-flight gesture: transfer each moving mesh's displayed movement onto its base mesh
	// as ONE undo step, then clear the operator (its cleanup re-syncs the renderer). A null preview means
	// no movement, so nothing is committed.
	fun confirmGesture() {
		val committed = preview
		val captured = capture
		if (committed != null && captured != null) {
			val newPositionsByDrawable = LinkedHashMap<DrawableId, FloatArray>(captured.drawableIds.size)
			val vertexIndicesByDrawable = LinkedHashMap<DrawableId, List<Int>>(captured.drawableIds.size)
			for (meshIndex in captured.drawableIds.indices) {
				val capturedId = captured.drawableIds[meshIndex]
				val transformed = committed[capturedId] ?: continue
				newPositionsByDrawable[capturedId] =
					movementToBase(captured.capturedBase[meshIndex], transformed, captured.originalDisplayed[meshIndex])
				// The moved set, not just the covered set: proportional editing moves weighted
				// unselected vertices too, and the change metadata must name every vertex the edit touched.
				vertexIndicesByDrawable[capturedId] = captured.movedIndices[meshIndex].toList()
			}
			if (newPositionsByDrawable.isNotEmpty()) {
				session.commitMeshPositions(MeshChange.MoveVertices(vertexIndicesByDrawable), newPositionsByDrawable)
			}
		}
		session.clearMeshOperator()
	}

	// Drives the modal preview for one virtual-pointer position: applies the operator (or the Vertex
	// Slide edge projection) per captured mesh, maps each result back to local through the
	// deformer-chain inverse, and pushes the folded model to the renderer.  Shared by Move (pointer
	// motion) and Scroll (a proportional radius change must re-derive the preview from the same frozen
	// originals without waiting for the next pointer move).  False when the capture has not landed yet.
	fun driveModalPreview(operator: MeshOperatorKind, virtualPointer: Offset, activeCamera: ViewportCamera, size: IntSize): Boolean {
		val start = gestureStart ?: return false
		val captured = capture ?: return false
		// One pointer frame for the whole capture; only geometry and pivots vary per mesh.
		val frame = TransformGestureFrame(captured.anchor, start, virtualPointer, session.axisConstraint.value, activeCamera, size)
		val newPreview = LinkedHashMap<DrawableId, FloatArray>(captured.drawableIds.size)
		var folded = session.model.value
		for (meshIndex in captured.drawableIds.indices) {
			// Vertex Slide projects the pointer onto its edge (its own math); every
			// other operator goes through the shared pivot-group transform.  The BEST
			// incident edge is re-picked from the live pointer each move (nearest
			// neighbor endpoint on screen), so the slide hops between the active
			// vertex's edges as the pointer travels - only the candidates are frozen.
			val slide = slideContext
			val transformedWorld =
				if (operator == MeshOperatorKind.VertexSlide) {
					if (slide != null && captured.drawableIds[meshIndex] == slide.drawableId) {
						val original = captured.originalWorld[meshIndex]
						var bestNeighbor = -1
						var bestDistance = Float.MAX_VALUE
						for (neighbor in slide.neighborIndices) {
							val screen = worldToScreen(original[neighbor * 2], original[neighbor * 2 + 1], activeCamera, size)
							val distance = (screen - virtualPointer).getDistance()
							if (distance < bestDistance) {
								bestDistance = distance
								bestNeighbor = neighbor
							}
						}
						if (bestNeighbor >= 0) {
							slideVertexAlongEdge(original, slide.activeVertex, bestNeighbor, frame)
						} else {
							original
						}
					} else {
						captured.originalWorld[meshIndex]
					}
				} else {
					applyOperator(
						operator,
						captured.originalWorld[meshIndex],
						captured.groups[meshIndex],
						frame,
						captured.influences[meshIndex],
						captured.rotationTracker,
					)
				}
			val transformedDisplayed =
				captured.mappings[meshIndex].worldToLocalLinearized(
					transformedWorld,
					captured.originalDisplayed[meshIndex],
					captured.originalWorld[meshIndex],
					captured.movedIndices[meshIndex],
				)
			val capturedId = captured.drawableIds[meshIndex]
			newPreview[capturedId] = transformedDisplayed
			folded =
				folded.withMeshPositions(
					capturedId,
					movementToBase(captured.capturedBase[meshIndex], transformedDisplayed, captured.originalDisplayed[meshIndex]),
				)
		}
		preview = newPreview
		service.setModel(folded)
		return true
	}

	// The modal gesture's commit-side seam: the Edit overlay drives per-mesh previews through the
	// deformer inverse, confirms via the movement transfer, cancels through the session's operator
	// clear, and resizes the proportional influence radius on scroll.  The pointer-side mechanics
	// (stale discard, virtual pointer, wrap, button semantics) live in ModalTransformController.
	val modalTarget =
		object : ModalTransformTarget {
			override fun drivePreview(virtualPointer: Offset, camera: ViewportCamera, size: IntSize): Boolean {
				// Defensive ownership check (the pointer loop already gates): only the initiating area drives.
				val operator = session.activeMeshOperator.value?.takeIf { it.areaId == areaId } ?: return false
				return driveModalPreview(operator.kind, virtualPointer, camera, size)
			}

			override fun confirm() {
				confirmGesture()
			}

			override fun cancel() {
				session.clearMeshOperator()
			}

			override fun onScroll(steps: Float, camera: ViewportCamera, size: IntSize) {
				// The wheel resizes the proportional influence radius mid-gesture (Blender's
				// behavior): geometric steps, weights re-derived from the frozen originals, and
				// the preview re-driven immediately so the mesh responds without a pointer move.
				val operator = session.activeMeshOperator.value?.takeIf { it.areaId == areaId } ?: return
				val proportional = session.proportionalEdit.value
				val captured = capture
				if (steps != 0f &&
					proportional != null &&
					captured != null &&
					operator.kind != MeshOperatorKind.VertexSlide &&
					!session.activeMeshOperatorSuppressesProportional
				) {
					// Wheel up (negative y) grows the radius.
					session.setProportionalRadius(proportional.radiusWorld * PROPORTIONAL_RADIUS_STEP_FACTOR.pow(-steps))
					captured.applyProportional(session.proportionalEdit.value)
					driveModalPreview(operator.kind, cursorWrap.virtualPointer(lastPointer), camera, size)
				}
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
	val selectToolKind =
		when (ownedSelectTool) {
			null -> 0
			is ActiveSelectTool.BoxArmed -> 1
			is ActiveSelectTool.Circle -> 2
		}
	LaunchedEffect(selectToolKind) {
		marquee.cancel()
	}

	// The race-free cancel path: an already-suspended collector that resumes before the mouse release, so the
	// box rubber-band is discarded (and any held circle stroke committed) before the Release could commit it.
	// The shell fires this for every Edit-mode select-gesture cancel - the non-armed box drag (which owns no
	// tool state to change) and, alongside clearSelectTool, the armed box / circle tools.
	LaunchedEffect(session) {
		session.meshGestureCancelRequests.collect {
			marquee.cancel()
		}
	}

	// Enter confirms the modal gesture (mirroring a primary click); the shell routes the keypress here,
	// gated to the INITIATING area through the operator latch itself.
	LaunchedEffect(session) {
		collectModalConfirmRequests(session, { session.activeMeshOperator.value?.areaId == areaId }) {
			confirmGesture()
		}
	}

	// A proportional toggle or falloff change MID-GESTURE (O falls through the shell ladder to the
	// keymap while an operator runs - Blender allows the same) re-derives the capture's weights from
	// its frozen originals; the next pointer move applies them.  The scroll path recomputes inline
	// (it must re-drive the preview without pointer motion), so this collector is the keyboard-side
	// complement.  Vertex Slide never takes weights (the latch passed null and stays null here).
	LaunchedEffect(session) {
		session.proportionalEdit.collect { state ->
			val captured = capture
			val liveOperator = session.activeMeshOperator.value
			if (captured != null &&
				liveOperator != null &&
				liveOperator.kind != MeshOperatorKind.VertexSlide &&
				!session.activeMeshOperatorSuppressesProportional
			) {
				captured.applyProportional(state)
			}
		}
	}

	// Select Linked (Blender's L / Ctrl+L): a keymap command carries no pointer, so the request lands
	// here where the pointer and the projected geometry live.  The executing area was resolved at
	// dispatch into the payload, so this gate is deterministic - two collectors can never double- or
	// zero-execute on a pointer-side volatile racing the collect.
	LaunchedEffect(session) {
		session.selectLinkedRequests.collect { request ->
			if (session.mode.value != EditorMode.Edit || request.areaId != areaId) {
				return@collect
			}
			handleSelectLinkedRequest(
				session,
				liveGeometryState.value.map { it.gizmo },
				request.fromSelection,
				lastPointer,
				liveCamera.value,
				liveSize.value,
			)
		}
	}

	// Alt+Q: switch the edited mesh to the drawable under the pointer (or the overlap picker for a stack).
	LaunchedEffect(session) {
		session.switchObjectRequests.collect {
			if (session.mode.value != EditorMode.Edit || service.activeAreaId != areaId) {
				return@collect
			}
			handleSwitchEditDrawableRequest(session, service, areaId, lastPointer, onOverlapRequest)
		}
	}

	// Rip (Blender's V): duplicate the covered vertices, re-point the pointer-side triangles, auto-grab.
	LaunchedEffect(session) {
		session.ripRequests.collect {
			if (session.mode.value != EditorMode.Edit || service.activeAreaId != areaId) {
				return@collect
			}
			handleRipRequest(session, liveGeometryState.value, areaId, lastPointer, liveCamera.value, liveSize.value)
		}
	}

	// The geometry-dependent Shift+S snaps for Edit mode.  Only the pointer's own area executes: every
	// open 2D viewport composes this collector, and an ungated request would commit once per viewport.
	LaunchedEffect(session) {
		session.snapRequests.collect { kind ->
			if (session.mode.value != EditorMode.Edit || service.activeAreaId != areaId) {
				return@collect
			}
			handleEditSnapRequest(session, liveGeometryState.value, kind)
		}
	}

	// Start the modal gesture as an operator latches IN THIS AREA; tear it down (re-syncing the renderer
	// to the committed model) as it clears.  The capture covers only the session meshes with covered
	// vertices (an edge or face selection moves the union of vertices its elements cover); a mesh with
	// nothing selected does not move.  The shared pivot is the median of every covered vertex across the
	// session, so all meshes scale / rotate about one point together.
	LaunchedEffect(activeOperator) {
		val operator = activeOperator?.takeIf { it.areaId == areaId }
		if (operator != null) {
			val capturedIds = ArrayList<DrawableId>()
			val mappings = ArrayList<DrawableSpaceMapping>()
			val displayedList = ArrayList<FloatArray>()
			val worldList = ArrayList<FloatArray>()
			val baseList = ArrayList<FloatArray>()
			val indicesList = ArrayList<Set<Int>>()
			val geometryList = ArrayList<EditMeshGeometry>()
			var coveredSumX = 0f
			var coveredSumY = 0f
			var coveredCount = 0
			for (geometry in liveGeometry) {
				val elements = meshSelection.elementsOf(geometry.drawableId)
				if (elements.isEmpty()) {
					continue
				}
				val coveredIndices = MeshTopology.coveredVertexIndices(elements, geometry.mesh.indices)
				if (coveredIndices.isEmpty()) {
					continue
				}
				capturedIds.add(geometry.drawableId)
				mappings.add(geometry.mapping)
				displayedList.add(geometry.displayed.copyOf())
				worldList.add(geometry.worldPosed.copyOf())
				baseList.add(geometry.mesh.positions.copyOf())
				indicesList.add(coveredIndices)
				geometryList.add(geometry)
				for (vertexIndex in coveredIndices) {
					coveredSumX += geometry.worldPosed[vertexIndex * 2]
					coveredSumY += geometry.worldPosed[vertexIndex * 2 + 1]
					coveredCount++
				}
			}
			if (capturedIds.isEmpty()) {
				// Nothing movable (the selection emptied between latch and capture): drop the operator.
				session.clearMeshOperator()
			} else {
				// The gesture anchor per the active pivot mode: the covered median (the default and the
				// Individual Origins measuring center), the active element's own median, or the 2D cursor -
				// the latter two falling back to the covered median when absent.
				val coveredMedian = (coveredSumX / coveredCount) to (coveredSumY / coveredCount)
				val pivotMode = session.pivotMode.value
				val anchor =
					when (pivotMode) {
						TransformPivotMode.MedianPoint, TransformPivotMode.IndividualOrigins -> coveredMedian
						TransformPivotMode.ActiveElement -> {
							val active = meshSelection.activeElement
							val activeGeometry = active?.let { candidate -> geometryList.firstOrNull { it.drawableId == candidate.drawableId } }
							if (active != null && activeGeometry != null) {
								val activeCovered = MeshTopology.coveredVertexIndices(setOf(active.element), activeGeometry.mesh.indices)
								if (activeCovered.isNotEmpty()) {
									MeshTransforms.medianPivot(activeGeometry.worldPosed, activeCovered)
								} else {
									coveredMedian
								}
							} else {
								coveredMedian
							}
						}
						TransformPivotMode.Cursor ->
							session.cursor2d.value?.let { cursor -> cursor.worldX to cursor.worldY } ?: coveredMedian
					}
				val groups =
					capturedIds.indices.map { meshIndex ->
						if (pivotMode == TransformPivotMode.IndividualOrigins) {
							TransformPivots.islandGroups(worldList[meshIndex], indicesList[meshIndex], geometryList[meshIndex].mesh.indices)
						} else {
							TransformPivots.sharedGroup(indicesList[meshIndex], anchor.first, anchor.second)
						}
					}
				capture =
					EditGestureCapture(
						drawableIds = capturedIds,
						mappings = mappings,
						originalDisplayed = displayedList,
						originalWorld = worldList,
						capturedBase = baseList,
						capturedIndices = indicesList,
						triangleIndices = capturedIds.indices.map { meshIndex -> geometryList[meshIndex].mesh.indices },
						groups = groups,
						anchor = anchor,
					).also { fresh ->
						// Proportional editing weights the unselected vertices near the selection; Vertex
						// Slide is positions-only single-vertex math, so it never takes weights - and a
						// suppressed latch (the duplicate / rip auto-grab) opts out the same way.
						fresh.applyProportional(
							if (operator.kind == MeshOperatorKind.VertexSlide || session.activeMeshOperatorSuppressesProportional) {
								null
							} else {
								session.proportionalEdit.value
							},
						)
					}
				// Vertex Slide needs an active vertex with at least one incident neighbor; only the
				// CANDIDATES freeze here - the best edge is re-picked from the live pointer every move
				// (Blender re-picks continuously, so the slide hops between connected edges mid-drag).
				// Without candidates (no active vertex, or an isolated one) the operator drops.
				slideContext =
					if (operator.kind == MeshOperatorKind.VertexSlide) {
						val active = meshSelection.activeElement
						val activeVertex = (active?.element as? MeshElement.Vertex)?.index
						val activeGeometry = active?.let { candidate -> geometryList.firstOrNull { it.drawableId == candidate.drawableId } }
						if (activeVertex != null && activeGeometry != null) {
							val adjacency = MeshTopology.buildVertexAdjacency(activeGeometry.mesh.vertexCount, activeGeometry.mesh.indices)
							val neighbors = adjacency.getOrElse(activeVertex) { IntArray(0) }
							if (neighbors.isNotEmpty()) SlideContext(active.drawableId, activeVertex, neighbors) else null
						} else {
							null
						}
					} else {
						null
					}
				if (operator.kind == MeshOperatorKind.VertexSlide && slideContext == null) {
					session.clearMeshOperator()
				}
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

	// clipToBounds: Canvas drawing is not clipped to the layout bounds by default, so an off-screen vertex
	// would otherwise paint over the AreaHeader and neighbouring areas.
	Box(
		modifier =
			modifier
				.fillMaxSize()
				.clipToBounds()
				.onGloballyPositioned { coordinates -> areaScreenOrigin = coordinates.positionOnScreen() }
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
								val latchedOperator = session.activeMeshOperator.value
								val latchedTool = session.activeSelectTool.value
								// A gesture belongs to its initiating area: while another viewport's operator or tool is
								// live - or a UV operator, which can never belong to a viewport area - this overlay is
								// fully inert (no drive, no picks, no marquee).  Escape and Enter stay global through
								// the shell ladder, and navigation (pan / zoom) still falls through.
								if ((latchedOperator != null && latchedOperator.areaId != areaId) ||
									(latchedTool != null && latchedTool.areaId != areaId) ||
									session.activeUvOperator.value != null
								) {
									continue
								}
								val operator = latchedOperator
								val selectTool = latchedTool
								val activeCamera = liveCamera.value
								val size = liveSize.value
								// Zoom Region armed for this area: the top-level region overlay owns the drag. This gizmo's
								// idle branch would otherwise also start a box (it does not check isConsumed), so yield.
								if (session.zoomRegionArmedArea.value == areaId) {
									continue
								}
								if (operator != null) {
									// MODAL: the shared controller drives the transform over the captured shape and
									// swallows every event (stale discard, virtual-pointer drive, cursor wrap,
									// RMB-cancel / LMB-confirm, proportional-radius scroll via the target).
									lastPointer = modalController.handleEvent(event, change, modalTarget, activeCamera, size, areaScreenOrigin)
								} else if (selectTool is ActiveSelectTool.Circle) {
									// CIRCLE SELECT: the shared controller paints / erases / commits the stroke and
									// consumes every event (paired with the navigation gate so MMB / wheel do not
									// also pan / zoom); see MarqueeSelectController.handleCircleEvent.
									marquee.handleCircleEvent(event, change, selectTool.radiusPx, activeCamera, size)
								} else {
									// IDLE: element selection. Only primary-driven events are consumed, so middle-drag pan and
									// wheel zoom fall through to the navigation layer. Armed Box-select (Blender's B) instead starts
									// a box on any press (hit-tests skipped) and disarms after the drag; a right-click while armed cancels.
									val boxArmed = selectTool is ActiveSelectTool.BoxArmed
									when (event.type) {
										PointerEventType.Press ->
											if (event.buttons.isSecondaryPressed && event.keyboardModifiers.isShiftPressed) {
												// Shift+RightClick places the 2D cursor at the pointer (Blender's gesture); the
												// HUD overlay draws it and the Cursor pivot mode / snap menu anchor on it.
												val (worldX, worldY) = screenToWorld(change.position.x, change.position.y, activeCamera, size)
												session.setCursor2d(worldX, worldY)
												change.consume()
											} else if (boxArmed && event.buttons.isSecondaryPressed) {
												session.clearSelectTool()
												change.consume()
											} else if (event.buttons.isPrimaryPressed) {
												val current = session.meshSelection.value
												val hit = hitTestMeshes(current.selectMode, liveGeometryState.value.map { it.gizmo }, change.position, activeCamera, size)
												// Armed Box-select ignores the hit and always boxes (Blender's B), so a press on an
												// element still starts a box rather than selecting it.
												if (!boxArmed && hit != null) {
													val modifiers = event.keyboardModifiers
													val updated =
														when {
															// Shift and Ctrl both toggle membership (Blender-style): a second
															// modified click on a selected element deselects it.
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
													// A sub-threshold click clears the selection - but not while armed, where a bare
													// click just disarms the tool (below) rather than wiping the selection.
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
			// Draw every session mesh from the DISPLAYED frame's geometry so the wireframes lag together with
			// the raster during a transform instead of leading it (preview still feeds the commit, just not
			// the draw). Fall back to the live session shape only when the frame lacks a drawable - a
			// pathological transient.
			// The live Circle stroke drives the highlighted domain so painted elements light up mid-stroke.
			val selectMode = effectiveSelection.selectMode
			for (geometry in liveGeometry) {
				val highlight = highlightByDrawable[geometry.drawableId] ?: continue
				val frameGeometry = frameGeometryByDrawable[geometry.drawableId]
				drawMeshWireframe(
					positions = frameGeometry?.worldPosed ?: geometry.worldPosed,
					indices = frameGeometry?.indices ?: geometry.mesh.indices,
					edges = frameGeometry?.edges ?: geometry.edges,
					highlight = highlight,
					selectMode = selectMode,
					colors = gizmoColors,
					camera = camera,
					size = IntSize(widthPx, heightPx),
				)
			}

			// The rubber-band box (both plain and armed drags) shares the one selection-box style.
			drawRubberBand(marquee.boxStart, marquee.boxCurrent, overlayStyle)

			// Armed select-tool affordances (Blender B / C), shared chrome with the Object gizmo.  Only
			// the arming area draws them - the latch is session-global, every split viewport draws.
			drawSelectToolAffordances(
				tool = ownedSelectTool,
				pointer = lastPointer,
				boxDragInFlight = marquee.boxStart != null,
				viewport = Size(widthPx.toFloat(), heightPx.toFloat()),
				style = overlayStyle,
				crosshairCursor = LocalUmamoCursors.crosshair,
			)

			// Modal transform HUD, shared chrome with the Object gizmo (see drawModalTransformHud).
			// Only the initiating area draws the modal chrome: the capture exists solely in the overlay
			// whose area the operator latch names, so its presence IS the ownership gate.
			val hudOperator = activeOperator
			val hudPivot = capture?.anchor
			if (hudOperator != null && hudPivot != null) {
				// The proportional influence ring hugs the world-unit radius the weights use (scaled by the
				// frame camera).  Vertex Slide and a suppressed latch never take weights, so they show no ring.
				val proportionalState = proportionalEdit
				val ringRadiusPx =
					if (proportionalState != null &&
						hudOperator.kind != MeshOperatorKind.VertexSlide &&
						!session.activeMeshOperatorSuppressesProportional
					) {
						proportionalState.radiusWorld * camera.zoom
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
	}
}
