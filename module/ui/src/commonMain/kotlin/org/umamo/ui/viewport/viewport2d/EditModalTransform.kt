package org.umamo.ui.viewport.viewport2d

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.DEFAULT_PROPORTIONAL_RADIUS_WORLD
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshChange
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.MeshSelection
import org.umamo.edit.MeshTopology
import org.umamo.edit.PROPORTIONAL_RADIUS_STEP_FACTOR
import org.umamo.edit.ProportionalEditState
import org.umamo.edit.mesh.MeshRestPositions
import org.umamo.edit.mesh.commitMeshPositions
import org.umamo.edit.mesh.withMeshPositions
import org.umamo.edit.transform.IndividualOriginScope
import org.umamo.edit.transform.ModalCaptureSource
import org.umamo.edit.transform.ModalTransformCapture
import org.umamo.edit.transform.ProportionalRows
import org.umamo.edit.transform.buildModalTransformCapture
import org.umamo.render.ViewportCamera
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.transform.DrawableWorldGeometry
import org.umamo.ui.viewport.gizmo.ModalDriveWorker
import org.umamo.ui.viewport.gizmo.ModalGestureState
import org.umamo.ui.viewport.gizmo.ModalTransformTarget
import org.umamo.ui.viewport.gizmo.TransformGestureFrame
import org.umamo.ui.viewport.gizmo.activeElementMedian
import org.umamo.ui.viewport.gizmo.gestureParameters
import kotlin.math.pow

/**
 * The captured state of an in-flight Edit-mode transform: the shared [ModalTransformCapture] (entries with
 * their frozen positions, pivot groups, proportional halos, and moved sets, plus the anchor, the frozen
 * operator kind, and the rotation tracker) alongside the per-drawable frozen [DrawableWorldGeometry] the
 * drive loop inverts each transformed world shape back through.  The geometry is held in a map keyed on the
 * drawable id, looked up by [org.umamo.edit.ModalCaptureEntry], so nothing stays index-aligned.
 *
 * The geometry frozen here is a COPY of the live geometry's arrays (rest, displayed, world), so the whole
 * drag transforms a fixed snapshot even though the underlying model is immutable.
 *
 * @property ModalTransformCapture transform The shared gesture capture (entries, groups, anchor, halos, kind).
 * @property Map<DrawableId, DrawableWorldGeometry> geometryById Each moving drawable's frozen world geometry.
 * @property SlideContext? slide A Vertex Slide's candidates; null for every other operator.
 */
internal class EditGesture(
	val transform: ModalTransformCapture,
	val geometryById: Map<DrawableId, DrawableWorldGeometry>,
	val slide: SlideContext?,
)

/**
 * The commit side of the Edit overlay's modal G / S / R and Vertex Slide: it captures the moving meshes
 * when an operator latches in its area, drives per-mesh previews through the deformer inverse, confirms
 * via the movement transfer, cancels through the session's operator clear, and resizes the proportional
 * influence radius on scroll.  The drive runs through [drive]: each pointer event resolves a request here,
 * on the UI thread, and the meshes' inverses run off it, in parallel, publishing back here.
 * The pointer-side mechanics (stale discard, virtual pointer, wrap, button semantics) live in
 * [org.umamo.ui.viewport.gizmo.ModalTransformController], which is handed this as its target.
 *
 * One instance per area, created with `remember(areaId)`: the pointer loop and the session collectors are
 * long-running and keep the instance they started with, so it holds nothing that may change under them.
 * The session and the renderer are fixed for the area's life (the viewport is keyed on its document), and
 * everything else is read from the session when used.
 *
 * @param String areaId The viewport area the overlay covers; only an operator latched here drives.
 * @param EditorSession session The session owning the model, the mesh selection, and the operator latch.
 * @param Function pushPreview Pushes a folded preview model to the renderer.
 */
internal class EditModalTransform(
	private val areaId: String,
	private val session: EditorSession,
	private val pushPreview: (PuppetModel) -> Unit,
) : ModalTransformTarget {
	/**
	 * The per-area modal-gesture bookkeeping (last pointer, capture + preview, gesture origin, area origin,
	 * cursor wrap, pointer controller).  The capture is the Edit-mode gesture; preview holds each moving
	 * mesh's new rest shape.
	 */
	val gesture = ModalGestureState<EditGesture, MeshRestPositions>()

	// The Vertex Slide's most recent landing (edge + factor), written as a drive publishes and read by the
	// confirm's strip registration.  Plain state, like ModalGestureState.lastParameters: nothing composed or
	// drawn reads it, and every reader checks the capture first, so a landing left from an earlier slide is
	// never read.
	private var slideLanding: SlideLanding? = null

	// The request the published preview answers: the confirm names the vertices it moved, which a radius
	// or falloff change since may have changed on the capture itself.
	private var publishedRequest: MeshDriveRequest? = null

	/**
	 * The drive: requests resolved per pointer event, the meshes computed off the UI thread in parallel, the
	 * result published back on it.  The overlay runs it (ModalDriveEffect); with no worker running, a
	 * request computes inline.
	 */
	val drive =
		ModalDriveWorker<MeshDriveRequest, MeshDriveResult>(
			gesture = gesture,
			computeSequential = ::computeMeshDrive,
			computeOffThread = { request -> computeMeshDriveParallel(request) },
			publish = { request, result -> publishDrive(request, result) },
		)

	/**
	 * Starts the gesture as an operator latches in this area.  The capture covers only the session meshes
	 * with covered vertices (an edge or face selection moves the union of vertices its elements cover); a
	 * mesh with nothing selected does not move.  The shared pivot is the median of every covered vertex
	 * across the session, so all meshes scale / rotate about one point together.
	 *
	 * @param MeshOperatorKind kind The latched operator.
	 * @param List<EditMeshGeometry> geometries The session meshes' live geometry, as the overlay composed it.
	 * @param MeshSelection selection The mesh selection, as the overlay collected it (the session's flow can
	 *   lead it when a commit and a latch share one call stack, as the rip auto-grab does).
	 */
	fun begin(kind: MeshOperatorKind, geometries: List<EditMeshGeometry>, selection: MeshSelection) {
		// Freeze a COPY of each moving mesh's world geometry (rest / displayed / world) so the whole drag
		// transforms a fixed snapshot, and offer it to the shared capture builder as a source.  A mesh with
		// nothing selected does not move.
		val frozenById = LinkedHashMap<DrawableId, DrawableWorldGeometry>()
		val sources = ArrayList<ModalCaptureSource>()

		for (geometry in geometries) {
			val elements = selection.elementsOf(geometry.drawableId)
			if (elements.isEmpty()) {
				continue
			}
			val coveredIndices = MeshTopology.coveredVertexIndices(elements, geometry.mesh.indices)
			if (coveredIndices.isEmpty()) {
				continue
			}
			val frozen = geometry.worldGeometry.frozenCopy()
			frozenById[geometry.drawableId] = frozen
			sources.add(ModalCaptureSource(geometry.drawableId, frozen.world, geometry.mesh.indices, coveredIndices))
		}

		// The Active-Element anchor the builder cannot resolve itself: the active element's own covered
		// median.  Null falls back to the shared covered median inside the builder.
		val activeAnchor = activeElementMedian(selection, geometries.map { geometry -> geometry.gizmo })
		val cursorAnchor = session.cursor2dOrWorldOrigin().let { cursor -> cursor.worldX to cursor.worldZ }
		val transform =
			buildModalTransformCapture(
				sources = sources,
				pivotMode = session.pivotMode.value,
				// Edit mode's Individual Origins turns each connectivity island about its own centroid.
				individualOriginScope = IndividualOriginScope.ConnectivityIsland,
				operatorKind = kind,
				activeAnchor = activeAnchor,
				cursorAnchor = cursorAnchor,
			)

		if (transform == null) {
			// Nothing movable (the selection emptied between latch and capture): drop the operator.
			session.clearMeshOperator()
			return
		}

		// Proportional editing weights the unselected vertices near the selection; Vertex Slide is
		// positions-only single-vertex math, so it never takes weights - and a suppressed latch (the
		// duplicate / rip auto-grab) opts out the same way.
		val proportionalState =
			if (session.meshOperatorTakesProportional(kind)) {
				session.proportionalEdit.value
			} else {
				null
			}
		transform.applyProportional(proportionalState, proportionalState?.radiusWorld ?: 0f)

		// Vertex Slide needs an active vertex with at least one incident neighbor; only the CANDIDATES
		// freeze here - the best edge is re-picked from the live pointer every move (Blender re-picks
		// continuously, so the slide hops between connected edges mid-drag).  Without candidates the
		// operator drops, AFTER the gesture begins, so the teardown resyncs the renderer as for any gesture.
		val slide = if (kind == MeshOperatorKind.VertexSlide) slideContextFor(selection, geometries) else null

		gesture.begin(EditGesture(transform, frozenById, slide), gesture.lastPointer)
		slideLanding = null
		publishedRequest = null

		if (kind == MeshOperatorKind.VertexSlide && slide == null) {
			session.clearMeshOperator()
		}
	}

	/**
	 * Tears the gesture down as the operator clears.
	 *
	 * @return Boolean True when this area owned a gesture, so the caller resyncs the renderer to the
	 *   committed model; a bystander teardown (at mount, or another area's latch) must not, or it would
	 *   stomp the initiating area's live preview.
	 */
	fun end(): Boolean = gesture.end()

	/**
	 * Ends the gesture because the overlay is leaving composition mid-gesture: the mode changed, the area
	 * closed, or every mesh in the edit stopped projecting (the overlay returns before it reaches this
	 * transform).  The latch effect is cancelled with the overlay and never runs its teardown, so this does
	 * it instead.  The latch is cleared while it is still this area's - a mode switch has cleared it
	 * already, and a latch another area holds is not this one's to clear - so no gesture is left latched to
	 * an overlay that cannot drive it, and none restarts from a fresh gesture state when the overlay comes
	 * back.
	 *
	 * @return Boolean True when a gesture was in flight, so the caller resyncs the renderer to the
	 *   committed model rather than leave it on the uncommitted preview.
	 */
	fun abandon(): Boolean {
		if (session.activeMeshOperator.value?.areaId == areaId) {
			session.clearMeshOperator()
		}
		return gesture.end()
	}

	/**
	 * Re-derives the capture's weights after a proportional toggle or falloff change MID-GESTURE (O falls
	 * through the shell ladder to the keymap while an operator runs - Blender allows the same); the next
	 * pointer move applies them.  The scroll path recomputes inline, since it must re-drive the preview
	 * without pointer motion.  Vertex Slide never takes weights (the latch passed null and it stays null).
	 *
	 * @param ProportionalEditState? state The proportional state now in effect, or null for off.
	 */
	fun reapplyProportional(state: ProportionalEditState?) {
		val gestureData = gesture.capture
		val liveOperator = session.activeMeshOperator.value
		if (gestureData != null && liveOperator != null && session.meshOperatorTakesProportional(liveOperator.kind)) {
			gestureData.transform.applyProportional(state, state?.radiusWorld ?: 0f)
		}
	}

	/**
	 * Submits a drive for one virtual-pointer position, when the latched operator is this area's.
	 *
	 * @param Offset virtualPointer The wrap-continuous pointer.
	 * @param ViewportCamera camera The area camera.
	 * @param IntSize size The area size in pixels.
	 * @return Boolean True when a drive was submitted.
	 */
	override fun drivePreview(virtualPointer: Offset, camera: ViewportCamera, size: IntSize): Boolean {
		// Defensive ownership check (the pointer loop already gates): only the initiating area drives.
		val operator = session.activeMeshOperator.value?.takeIf { it.areaId == areaId } ?: return false

		return submitDrive(operator.kind, virtualPointer, camera, size)
	}

	/**
	 * Confirms the in-flight gesture at the latest pointer: settles a drive the worker has not published
	 * yet, commits each moving mesh's new rest shape as ONE undo step, registers that step on the
	 * operation settings strip, then clears the operator (its teardown resyncs the renderer).  A null
	 * preview means no movement, so nothing is committed.  The preview already holds rest shapes (the
	 * drive inverted them via worldToRest), so they are committed directly.
	 */
	override fun confirm() {
		drive.settle()

		val committed = gesture.preview
		val gestureData = gesture.capture
		val parameters = gesture.lastParameters
		val request = publishedRequest

		if (committed != null && gestureData != null && request != null) {
			val transform = gestureData.transform
			val restByDrawable = LinkedHashMap<DrawableId, MeshRestPositions>(request.jobs.size)
			val vertexIndicesByDrawable = LinkedHashMap<DrawableId, List<Int>>(request.jobs.size)

			for (job in request.jobs) {
				val transformed = committed[job.drawableId] ?: continue
				restByDrawable[job.drawableId] = transformed
				// The moved set, not just the covered set: proportional editing moves weighted
				// unselected vertices too, and the change metadata must name every vertex the edit touched,
				// as the drive that computed these positions moved them.
				vertexIndicesByDrawable[job.drawableId] = job.movedIndices.toList()
			}

			if (restByDrawable.isNotEmpty()) {
				val modelBefore = session.model.value
				session.commitMeshPositions(
					MeshChange.TransformVertices(vertexIndicesByDrawable, transform.operatorKind),
					restByDrawable,
				)

				// The strip's rows for the step just pushed, over the RETAINED capture so an adjustment
				// replays the same frozen geometry - registered before the operator clears, since the
				// teardown drops the capture.  A commit that recorded nothing (the geometry landed where it
				// started) has no step of its own to amend, so it registers nothing.
				if (session.model.value !== modelBefore) {
					val slide = gestureData.slide
					val landing = slideLanding

					if (transform.operatorKind == MeshOperatorKind.VertexSlide) {
						if (slide != null && landing != null) {
							registerSlideAdjustment(session, areaId, transform, gestureData.geometryById, slide.drawableId, slide.activeVertex, landing.neighborIndex, landing.factor)
						}
					} else if (parameters != null) {
						// A suppressed latch (the duplicate / rip auto-grab) took no weights, so it offers no
						// proportional rows; every other transform does, on or off, so the halo can be
						// added after the fact the way Blender's redo panel allows.
						val proportional =
							if (session.meshOperatorTakesProportional(transform.operatorKind)) {
								val state = session.proportionalEdit.value
								ProportionalRows.of(state, state?.radiusWorld ?: DEFAULT_PROPORTIONAL_RADIUS_WORLD)
							} else {
								null
							}
						registerMeshTransformAdjustment(session, areaId, transform, gestureData.geometryById, parameters, proportional) { state ->
							session.setProportionalEdit(state)
						}
					}
				}
			}
		}
		session.clearMeshOperator()
	}

	/** Cancels the in-flight gesture; the teardown resyncs the renderer. */
	override fun cancel() {
		session.clearMeshOperator()
	}

	/**
	 * The wheel resizes the proportional influence radius mid-gesture (Blender's behavior): geometric
	 * steps and weights re-derived from the frozen originals at once, on the UI thread, and a drive
	 * submitted so the mesh responds without a pointer move.
	 *
	 * @param Float steps The scroll in wheel steps; negative (wheel up) grows the radius.
	 * @param ViewportCamera camera The area camera.
	 * @param IntSize size The area size in pixels.
	 */
	override fun onScroll(steps: Float, camera: ViewportCamera, size: IntSize) {
		val operator = session.activeMeshOperator.value?.takeIf { it.areaId == areaId } ?: return
		val proportional = session.proportionalEdit.value
		val gestureData = gesture.capture

		if (steps != 0f && proportional != null && gestureData != null && session.meshOperatorTakesProportional(operator.kind)) {
			session.setProportionalRadius(proportional.radiusWorld * PROPORTIONAL_RADIUS_STEP_FACTOR.pow(-steps))
			val updated = session.proportionalEdit.value
			gestureData.transform.applyProportional(updated, updated?.radiusWorld ?: 0f)
			submitDrive(operator.kind, gesture.cursorWrap.virtualPointer(gesture.lastPointer), camera, size)
		}
	}

	/**
	 * Resolves and submits a drive for one virtual-pointer position, on the UI thread: the pointer frame
	 * into the parameters every mesh applies (once per event: the Rotate branch advances the accumulator,
	 * which must see every step), the Vertex Slide's landing, each mesh's halo as it is now, and the model
	 * to fold onto.  Shared by Move (pointer motion) and Scroll (a proportional radius change
	 * re-drives from the same frozen originals without waiting for the next pointer move).
	 *
	 * @param MeshOperatorKind operator The latched operator.
	 * @param Offset virtualPointer The wrap-continuous pointer.
	 * @param ViewportCamera activeCamera The area camera.
	 * @param IntSize size The area size in pixels.
	 * @return Boolean False when the capture has not landed yet.
	 */
	private fun submitDrive(operator: MeshOperatorKind, virtualPointer: Offset, activeCamera: ViewportCamera, size: IntSize): Boolean {
		val start = gesture.gestureStart ?: return false
		val gestureData = gesture.capture ?: return false
		val transform = gestureData.transform
		val frame = TransformGestureFrame(transform.anchor, start, virtualPointer, session.axisConstraint.value, activeCamera, size)
		val parameters = gestureParameters(operator, frame, transform.rotationTracker)
		// Vertex Slide projects the pointer onto its edge: the best edge is re-picked from the live pointer
		// every move, and a pointer landing on no edge leaves the vertex where it was.
		val slide = gestureData.slide
		val slideMove =
			if (operator == MeshOperatorKind.VertexSlide && slide != null) {
				transform.entries
					.firstOrNull { entry -> entry.drawableId == slide.drawableId }
					?.let { entry -> slideLandingToward(slide, entry.positions, frame) }
					?.let { landing -> SlideMove(slide.drawableId, slide.activeVertex, landing) }
			} else {
				null
			}

		val jobs = meshDriveJobs(transform, gestureData.geometryById, wholeMeshes = false)
		drive.submit(MeshDriveRequest(operator, parameters, jobs, slideMove, session.model.value))

		return true
	}

	/**
	 * Lands one drive on the UI thread: the parameters and the slide landing the confirm reads, the preview,
	 * and the folded model pushed to the renderer - folded again onto the session's model when a commit
	 * replaced the one the request folded onto (a snap mid-drag), so the preview always shows the current
	 * model.
	 *
	 * @param MeshDriveRequest request The request.
	 * @param MeshDriveResult result Its result.
	 */
	private fun publishDrive(request: MeshDriveRequest, result: MeshDriveResult) {
		gesture.lastParameters = request.parameters
		request.slide?.let { move -> slideLanding = move.landing }
		publishedRequest = request
		gesture.preview = result.preview
		val current = session.model.value

		pushPreview(if (request.baseModel === current) result.folded else current.withMeshPositions(result.preview))
	}
}