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
import org.umamo.edit.MeshElement
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.MeshSelection
import org.umamo.edit.MeshSelectionOps
import org.umamo.edit.MeshTopology
import org.umamo.edit.MeshTransforms
import org.umamo.edit.ModalCaptureSource
import org.umamo.edit.ModalTransformCapture
import org.umamo.edit.PROPORTIONAL_RADIUS_STEP_FACTOR
import org.umamo.edit.buildModalTransformCapture
import org.umamo.edit.withMeshPositions
import org.umamo.render.ViewportCamera
import org.umamo.render.eval.DrawableSpaceMapping
import org.umamo.render.eval.drawableLocalPosed
import org.umamo.render.pick.PickCandidate
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoCursors
import org.umamo.ui.theme.hiddenPointerIcon
import org.umamo.ui.transform.DrawableWorldGeometry
import org.umamo.ui.transform.captureDrawableWorld
import kotlin.math.pow

/**
 * One session mesh's live geometry at the neutral pose: its three-space [DrawableWorldGeometry] (base, the
 * posed rest shape, and its world projection, plus the deformer-chain mapping and the world->base inverse),
 * along with its mesh and derived unique edges.  The Edit session spans several meshes, so the overlay
 * carries one of these per drawable.
 *
 * The three-space geometry is the SAME primitive the object gizmo and the Properties transform panel use, so
 * an Edit-mode drag inverts a transformed world shape back onto the base mesh through the shared
 * [DrawableWorldGeometry.worldToBase] rather than an open-coded round trip.
 *
 * @property DrawableWorldGeometry worldGeometry The drawable's base / displayed / world geometry and inverse.
 * @property DrawableMesh mesh The drawable's live mesh (positions, uvs, indices).
 * @property List<MeshElement.Edge> edges The mesh's unique edges, in first-encounter order.
 */
internal class EditMeshGeometry(
	val worldGeometry: DrawableWorldGeometry,
	val mesh: DrawableMesh,
	val edges: List<MeshElement.Edge>,
) {
	/** The drawable this geometry belongs to. */
	val drawableId: DrawableId get() = worldGeometry.drawableId

	/** The local-to-world deformer-chain projection. */
	val mapping: DrawableSpaceMapping get() = worldGeometry.mapping

	/** The local rest shape the movement transfer anchors on (base + the neutral keyform blend). */
	val displayed: FloatArray get() = worldGeometry.displayed

	/** The displayed shape projected to world space. */
	val worldPosed: FloatArray get() = worldGeometry.world

	/** The geometry-source-agnostic view the shared element queries and wireframe draw take. */
	val gizmo = GizmoMeshGeometry(drawableId, mesh.indices, edges, worldGeometry.world)

	/**
	 * Inverts a transformed WORLD shape back onto the base mesh - the write-back a drag ends with.
	 *
	 * @param FloatArray transformedWorld The reshaped world positions.
	 * @param Set<Int> indices The vertices the transform touched.
	 * @return FloatArray The new base positions (a fresh array).
	 */
	fun worldToBase(transformedWorld: FloatArray, indices: Set<Int>): FloatArray = worldGeometry.worldToBase(transformedWorld, indices)
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
 * The captured state of an in-flight Edit-mode transform: the shared [ModalTransformCapture] (entries with
 * their frozen positions, pivot groups, proportional halos, and moved sets, plus the anchor, the frozen
 * operator kind, and the rotation tracker) alongside the per-drawable frozen [DrawableWorldGeometry] the
 * drive loop inverts each transformed world shape back through.  The geometry is held in a map keyed on the
 * drawable id, looked up by [org.umamo.edit.ModalCaptureEntry], so nothing stays index-aligned.
 *
 * The geometry frozen here is a COPY of the live geometry's arrays (base, displayed, world), so the whole
 * drag transforms a fixed snapshot even though the underlying model is immutable.
 *
 * @property ModalTransformCapture transform The shared gesture capture (entries, groups, anchor, halos, kind).
 * @property Map<DrawableId, DrawableWorldGeometry> geometryById Each moving drawable's frozen world geometry.
 */
private class EditGesture(
	val transform: ModalTransformCapture,
	val geometryById: Map<DrawableId, DrawableWorldGeometry>,
)

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
				// Edit mode is pinned to the neutral pose, so the three-space geometry is captured at emptyMap().
				// Null when the drawable has no world mapping (a hidden ancestor): it cannot be drawn, so it
				// cannot be edited - skip it, the same three-space primitive the object gizmo and Properties use.
				val worldGeometry = captureDrawableWorld(model, emptyMap(), drawableId) ?: return@mapNotNull null
				EditMeshGeometry(
					worldGeometry = worldGeometry,
					mesh = mesh,
					edges = MeshTopology.uniqueEdges(mesh.indices),
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

	// The per-area modal-gesture bookkeeping (last pointer, capture + preview, gesture origin, area origin,
	// cursor wrap, pointer controller).  The capture is the Edit-mode gesture (shared transform capture +
	// per-drawable frozen world geometry); preview holds each moving mesh's new BASE positions.
	val gesture = remember(areaId) { ModalGestureState<EditGesture>() }

	// The Vertex Slide edge, frozen at latch: (drawable, sliding vertex, edge's other endpoint) - the
	// incident edge whose far endpoint sat nearest the pointer when the operator armed.
	var slideContext by remember(areaId) { mutableStateOf<SlideContext?>(null) }

	// Confirms the in-flight gesture: commit each moving mesh's new base positions as ONE undo step, then
	// clear the operator (its cleanup re-syncs the renderer). A null preview means no movement, so nothing
	// is committed.  The preview already holds base positions (the drive loop inverted them via worldToBase),
	// so confirm commits them directly.
	fun confirmGesture() {
		val committed = gesture.preview
		val gestureData = gesture.capture
		if (committed != null && gestureData != null) {
			val transform = gestureData.transform
			val newPositionsByDrawable = LinkedHashMap<DrawableId, FloatArray>(transform.entries.size)
			val vertexIndicesByDrawable = LinkedHashMap<DrawableId, List<Int>>(transform.entries.size)
			for (entry in transform.entries) {
				val transformed = committed[entry.drawableId] ?: continue
				newPositionsByDrawable[entry.drawableId] = transformed
				// The moved set, not just the covered set: proportional editing moves weighted
				// unselected vertices too, and the change metadata must name every vertex the edit touched.
				vertexIndicesByDrawable[entry.drawableId] = entry.movedIndices.toList()
			}
			if (newPositionsByDrawable.isNotEmpty()) {
				session.commitMeshPositions(
					MeshChange.TransformVertices(vertexIndicesByDrawable, transform.operatorKind),
					newPositionsByDrawable,
				)
			}
		}
		session.clearMeshOperator()
	}

	// Drives the modal preview for one virtual-pointer position: applies the operator (or the Vertex
	// Slide edge projection) per captured mesh, inverts each transformed world shape back onto the base
	// mesh through the shared worldToBase round trip, and pushes the folded model to the renderer.  Shared
	// by Move (pointer motion) and Scroll (a proportional radius change must re-derive the preview from the
	// same frozen originals without waiting for the next pointer move).  False when the capture has not
	// landed yet.
	fun driveModalPreview(operator: MeshOperatorKind, virtualPointer: Offset, activeCamera: ViewportCamera, size: IntSize): Boolean {
		val start = gesture.gestureStart ?: return false
		val gestureData = gesture.capture ?: return false
		val transform = gestureData.transform
		// One pointer frame for the whole capture; only geometry and pivots vary per mesh.
		val frame = TransformGestureFrame(transform.anchor, start, virtualPointer, session.axisConstraint.value, activeCamera, size)
		val newPreview = LinkedHashMap<DrawableId, FloatArray>(transform.entries.size)
		var folded = session.model.value
		for (entry in transform.entries) {
			val geometry = gestureData.geometryById.getValue(entry.drawableId)
			// Vertex Slide projects the pointer onto its edge (its own math); every
			// other operator goes through the shared pivot-group transform.  The BEST
			// incident edge is re-picked from the live pointer each move (nearest
			// neighbor endpoint on screen), so the slide hops between the active
			// vertex's edges as the pointer travels - only the candidates are frozen.
			val slide = slideContext
			val transformedWorld =
				if (operator == MeshOperatorKind.VertexSlide) {
					if (slide != null && entry.drawableId == slide.drawableId) {
						val original = entry.positions
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
						entry.positions
					}
				} else {
					applyOperator(
						operator,
						entry.positions,
						entry.groups,
						frame,
						entry.influence,
						transform.rotationTracker,
					)
				}
			val newBase = geometry.worldToBase(transformedWorld, entry.movedIndices)
			newPreview[entry.drawableId] = newBase
			folded = folded.withMeshPositions(entry.drawableId, newBase)
		}
		gesture.preview = newPreview
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
				val gestureData = gesture.capture
				if (steps != 0f &&
					proportional != null &&
					gestureData != null &&
					operator.kind != MeshOperatorKind.VertexSlide &&
					!session.activeMeshOperatorSuppressesProportional
				) {
					// Wheel up (negative y) grows the radius.
					session.setProportionalRadius(proportional.radiusWorld * PROPORTIONAL_RADIUS_STEP_FACTOR.pow(-steps))
					val updated = session.proportionalEdit.value
					gestureData.transform.applyProportional(updated, updated?.radiusWorld ?: 0f)
					driveModalPreview(operator.kind, gesture.cursorWrap.virtualPointer(gesture.lastPointer), camera, size)
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
	LaunchedEffect(selectToolKind(ownedSelectTool)) {
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
			val gestureData = gesture.capture
			val liveOperator = session.activeMeshOperator.value
			if (gestureData != null &&
				liveOperator != null &&
				liveOperator.kind != MeshOperatorKind.VertexSlide &&
				!session.activeMeshOperatorSuppressesProportional
			) {
				gestureData.transform.applyProportional(state, state?.radiusWorld ?: 0f)
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
				gesture.lastPointer,
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
			handleSwitchEditDrawableRequest(session, service, areaId, gesture.lastPointer, onOverlapRequest)
		}
	}

	// Rip (Blender's V): duplicate the covered vertices, re-point the pointer-side triangles, auto-grab.
	LaunchedEffect(session) {
		session.ripRequests.collect {
			if (session.mode.value != EditorMode.Edit || service.activeAreaId != areaId) {
				return@collect
			}
			handleRipRequest(session, liveGeometryState.value, areaId, gesture.lastPointer, liveCamera.value, liveSize.value)
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
			// Freeze a COPY of each moving mesh's world geometry (base / displayed / world) so the whole drag
			// transforms a fixed snapshot, and offer it to the shared capture builder as a source.  A mesh with
			// nothing selected does not move.
			val frozenById = LinkedHashMap<DrawableId, DrawableWorldGeometry>()
			val sources = ArrayList<ModalCaptureSource>()
			for (geometry in liveGeometry) {
				val elements = meshSelection.elementsOf(geometry.drawableId)
				if (elements.isEmpty()) {
					continue
				}
				val coveredIndices = MeshTopology.coveredVertexIndices(elements, geometry.mesh.indices)
				if (coveredIndices.isEmpty()) {
					continue
				}
				val frozen =
					DrawableWorldGeometry(
						geometry.drawableId,
						geometry.mapping,
						geometry.mesh.positions.copyOf(),
						geometry.displayed.copyOf(),
						geometry.worldPosed.copyOf(),
					)
				frozenById[geometry.drawableId] = frozen
				sources.add(ModalCaptureSource(geometry.drawableId, frozen.world, geometry.mesh.indices, coveredIndices))
			}
			// The Active-Element anchor the builder cannot resolve itself: the active element's own covered
			// median.  Null falls back to the shared covered median inside the builder.
			val activeAnchor =
				run {
					val active = meshSelection.activeElement ?: return@run null
					val activeGeometry = liveGeometry.firstOrNull { it.drawableId == active.drawableId } ?: return@run null
					val activeCovered = MeshTopology.coveredVertexIndices(setOf(active.element), activeGeometry.mesh.indices)
					if (activeCovered.isEmpty()) {
						null
					} else {
						MeshTransforms.medianPivot(activeGeometry.worldPosed, activeCovered)
					}
				}
			val cursorAnchor = session.cursor2d.value?.let { cursor -> cursor.worldX to cursor.worldY }
			val transform =
				buildModalTransformCapture(
					sources = sources,
					pivotMode = session.pivotMode.value,
					// Edit mode's Individual Origins turns each connectivity island about its own centroid.
					individualOriginScope = IndividualOriginScope.ConnectivityIsland,
					operatorKind = operator.kind,
					activeAnchor = activeAnchor,
					cursorAnchor = cursorAnchor,
				)
			if (transform == null) {
				// Nothing movable (the selection emptied between latch and capture): drop the operator.
				session.clearMeshOperator()
			} else {
				// Proportional editing weights the unselected vertices near the selection; Vertex Slide is
				// positions-only single-vertex math, so it never takes weights - and a suppressed latch (the
				// duplicate / rip auto-grab) opts out the same way.
				val proportionalState =
					if (operator.kind == MeshOperatorKind.VertexSlide || session.activeMeshOperatorSuppressesProportional) {
						null
					} else {
						session.proportionalEdit.value
					}
				transform.applyProportional(proportionalState, proportionalState?.radiusWorld ?: 0f)
				gesture.begin(EditGesture(transform, frozenById), gesture.lastPointer)
				// Vertex Slide needs an active vertex with at least one incident neighbor; only the
				// CANDIDATES freeze here - the best edge is re-picked from the live pointer every move
				// (Blender re-picks continuously, so the slide hops between connected edges mid-drag).
				// Without candidates (no active vertex, or an isolated one) the operator drops.
				slideContext =
					if (operator.kind == MeshOperatorKind.VertexSlide) {
						val active = meshSelection.activeElement
						val activeVertex = (active?.element as? MeshElement.Vertex)?.index
						val activeGeometry = active?.let { candidate -> liveGeometry.firstOrNull { it.drawableId == candidate.drawableId } }
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
									gesture.lastPointer = gesture.modalController.handleEvent(event, change, modalTarget, activeCamera, size, gesture.areaScreenOrigin)
								} else if (selectTool is ActiveSelectTool.Circle) {
									// CIRCLE SELECT: the shared controller paints / erases / commits the stroke and
									// consumes every event (paired with the navigation gate so MMB / wheel do not
									// also pan / zoom); see MarqueeSelectController.handleCircleEvent.
									marquee.handleCircleEvent(event, change, selectTool.radiusPx, activeCamera, size)
								} else {
									// IDLE: element selection, shared with the UV editor (Shift+RightClick places the
									// viewport's 2D cursor here).  Only primary-driven events are consumed, so middle-drag
									// pan and wheel zoom fall through; armed Box-select boxes on any press and disarms.
									handleIdleMeshSelectionEvent(
										event = event,
										change = change,
										session = session,
										geometries = liveGeometryState.value.map { it.gizmo },
										marquee = marquee,
										boxArmed = selectTool is ActiveSelectTool.BoxArmed,
										camera = activeCamera,
										size = size,
										placeCursor = session::setCursor2d,
									)
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
				pointer = gesture.lastPointer,
				boxDragInFlight = marquee.boxStart != null,
				viewport = Size(widthPx.toFloat(), heightPx.toFloat()),
				style = overlayStyle,
				crosshairCursor = LocalUmamoCursors.crosshair,
			)

			// Modal transform HUD, shared chrome with the Object gizmo (see drawModalTransformHud).
			// Only the initiating area draws the modal chrome: the capture exists solely in the overlay
			// whose area the operator latch names, so its presence IS the ownership gate.
			val hudOperator = activeOperator
			val hudPivot = gesture.capture?.transform?.anchor
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
					virtualPointer = gesture.cursorWrap.virtualPointer(gesture.lastPointer),
					realPointer = gesture.lastPointer,
					viewport = Size(widthPx.toFloat(), heightPx.toFloat()),
					lineColor = overlayColors.viewportMarquee,
					pointerCursor = LocalUmamoCursors.nsewScroll,
					proportionalRadiusPx = ringRadiusPx,
				)
			}
		}
	}
}
