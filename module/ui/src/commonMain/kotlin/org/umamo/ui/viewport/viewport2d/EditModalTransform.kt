package org.umamo.ui.viewport.viewport2d

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.DEFAULT_PROPORTIONAL_RADIUS_WORLD
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.MeshSelection
import org.umamo.edit.MeshTopology
import org.umamo.edit.PROPORTIONAL_RADIUS_STEP_FACTOR
import org.umamo.edit.ProportionalEditState
import org.umamo.edit.mesh.MeshRestPositions
import org.umamo.edit.mesh.withMeshPositions
import org.umamo.edit.transform.IndividualOriginScope
import org.umamo.edit.transform.ModalCaptureSource
import org.umamo.edit.transform.ModalTransformCapture
import org.umamo.edit.transform.ProportionalRows
import org.umamo.edit.transform.buildModalTransformCapture
import org.umamo.edit.transform.pivotDepthSteppedFrom
import org.umamo.render.ViewportCamera
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.ElementOwner
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.WarpLatticeForm
import org.umamo.ui.transform.DrawableWorldGeometry
import org.umamo.ui.transform.ElementEditStage
import org.umamo.ui.transform.LatticeLanding
import org.umamo.ui.transform.LatticeWorldGeometry
import org.umamo.ui.transform.MeshDriveRequest
import org.umamo.ui.transform.MeshDriveResult
import org.umamo.ui.transform.OwnerWorldGeometry
import org.umamo.ui.transform.SlideLanding
import org.umamo.ui.transform.SlideMove
import org.umamo.ui.transform.commitDrive
import org.umamo.ui.transform.computeMeshDrive
import org.umamo.ui.transform.meshDriveJobs
import org.umamo.ui.transform.registerMeshTransformAdjustment
import org.umamo.ui.transform.registerSlideAdjustment
import org.umamo.ui.viewport.LiveMeshPreview
import org.umamo.ui.viewport.gizmo.ModalDriveWorker
import org.umamo.ui.viewport.gizmo.ModalGestureState
import org.umamo.ui.viewport.gizmo.ModalTransformTarget
import org.umamo.ui.viewport.gizmo.TransformGestureFrame
import org.umamo.ui.viewport.gizmo.activeElementMedian
import org.umamo.ui.viewport.gizmo.gestureParameters
import kotlin.math.abs
import kotlin.math.pow

/**
 * The captured state of an in-flight Edit- or Pose-mode transform: the shared [ModalTransformCapture] alongside
 * each captured owner's frozen world geometry and the stage it was captured at.  The geometry is keyed on the owner,
 * looked up by [org.umamo.edit.transform.ModalCaptureEntry], so nothing stays index-aligned.
 *
 * @property ModalTransformCapture transform The shared gesture capture (entries, groups, anchor, halos, kind).
 * @property Map<ElementOwner, OwnerWorldGeometry> geometryByOwner Each captured owner's world geometry, a bystander's
 *   included.
 * @property SlideContext? slide A Vertex Slide's candidates; null for every other operator.
 * @property ElementEditStage stage Where the geometry was captured, and so how a moved lattice lands.
 */
internal class EditGesture(
	val transform: ModalTransformCapture,
	val geometryByOwner: Map<ElementOwner, OwnerWorldGeometry>,
	val slide: SlideContext?,
	val stage: ElementEditStage,
)

/**
 * The commit side of the Edit overlay's modal G / S / R and Vertex Slide, in Edit and Pose mode: it captures the
 * moving meshes or lattices when an operator latches in its area, drives their previews through [drive], and lands
 * the step (a mesh by movement transfer, a lattice by its [LatticeLanding]) or cancels it.  The pointer-side mechanics live in [org.umamo.ui.viewport.gizmo.ModalTransformController],
 * which is handed this as its target.
 *
 * One instance per area, created with `remember(areaId)`: the pointer loop and the session collectors are
 * long-running and keep the instance they started with, so it holds nothing that may change under them.
 * The session and the renderer are fixed for the area's life (the viewport is keyed on its document), and
 * everything else is read from the session when used.
 *
 * @param String areaId The viewport area the overlay covers; only an operator latched here drives.
 * @param EditorSession session The session owning the model, the mesh selection, and the operator latch.
 * @param Function pushPreview Pushes a folded preview model to the renderer.
 * @param LiveMeshPreview? livePreview The window's live mesh preview, which each published drive writes and the
 *   gesture's end clears, or null outside a shell; a transform built without one keeps a private slot nothing reads.
 * @param Function pushLatticePreview Pushes a posed drag's pending forms to the renderer, which the art follows.
 */
internal class EditModalTransform(
	private val areaId: String,
	private val session: EditorSession,
	private val pushPreview: (PuppetModel) -> Unit,
	livePreview: LiveMeshPreview? = null,
	private val pushLatticePreview: (Map<DeformerId, WarpLatticeForm>) -> Unit = {},
) : ModalTransformTarget {
	/** The preview this transform writes: the window's, else a private slot nothing reads. */
	private val livePreview: LiveMeshPreview = livePreview ?: LiveMeshPreview()

	/**
	 * The per-area modal-gesture bookkeeping (last pointer, capture + preview, gesture origin, area origin,
	 * cursor wrap, pointer controller).  The capture is the Edit-mode gesture; preview holds each moving
	 * mesh's new rest shape.
	 */
	override val gesture = ModalGestureState<EditGesture, MeshRestPositions>()

	// The Vertex Slide's most recent landing (edge + factor), written as a drive publishes and read by the
	// confirm's strip registration.  Plain state, like ModalGestureState.lastParameters: nothing composed or
	// drawn reads it, and every reader checks the capture first, so a landing left from an earlier slide is
	// never read.
	private var slideLanding: SlideLanding? = null

	// The primary-modifier wheel's travel toward the next depth step: a notch can arrive split across events, and a
	// trackpad or high-resolution wheel sends fractions, so the travel gathers here and each whole notch steps once.
	private var pendingDepthSteps = 0f

	// The request the published preview answers: the confirm names the vertices it moved, which a radius
	// or falloff change since may have changed on the capture itself.
	private var publishedRequest: MeshDriveRequest? = null

	// The published drive - its meshes, lattices, moved sets, and posed forms - which the confirm lands.
	private var publishedResult: MeshDriveResult? = null

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
	 * Starts the gesture as an operator latches in this area, capturing the owners with covered vertices as movers
	 * and every other owner in the edit as a bystander a proportional halo can reach.  Bystanders join even while
	 * proportional editing is off, since O mid-gesture and the strip's rows can still turn it on over this capture.
	 *
	 * @param MeshOperatorKind kind The latched operator.
	 * @param List<EditOwnerGeometry> geometries The session owners' live geometry, as the overlay composed it.
	 * @param MeshSelection selection The mesh selection, as the overlay collected it (the session's flow can
	 *   lead it when a commit and a latch share one call stack, as the rip auto-grab does).
	 * @param ElementEditStage stage The stage [geometries] were captured at.
	 */
	fun begin(
		kind: MeshOperatorKind,
		geometries: List<EditOwnerGeometry>,
		selection: MeshSelection,
		stage: ElementEditStage,
	) {
		// Offer every owner in the edit to the shared capture builder, which keeps one with nothing covered as a
		// bystander.  A moving owner's world geometry is frozen so the whole drag transforms a fixed snapshot - a
		// COPY of a mesh's (rest / displayed / world), a lattice's bake as it is.  A bystander's is taken as the
		// overlay composed it: that capture is a fresh bake over the immutable model, nothing writes into its
		// arrays, and most bystanders never move, so copying every mesh in the edit per latch would buy nothing.
		val geometryByOwner = LinkedHashMap<ElementOwner, OwnerWorldGeometry>()
		val sources =
			geometries.map { geometry ->
				val elements = selection.elementsOf(geometry.owner)
				val coveredIndices = if (elements.isEmpty()) emptySet() else MeshTopology.coveredVertexIndices(elements, geometry.topology)
				val captured = if (coveredIndices.isEmpty()) geometry.worldGeometry else geometry.worldGeometry.frozen()
				geometryByOwner[geometry.owner] = captured
				ModalCaptureSource(geometry.owner, captured.world, geometry.topology, coveredIndices)
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

		gesture.begin(EditGesture(transform, geometryByOwner, slide, stage), gesture.lastPointer)
		slideLanding = null
		pendingDepthSteps = 0f
		publishedRequest = null
		publishedResult = null

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
	fun end(): Boolean = endGesture()

	/**
	 * Ends the gesture because the overlay is leaving composition mid-gesture, standing in for the teardown its
	 * cancelled latch effect never runs.  A latch still this area's is cleared, so no gesture is left latched to an
	 * overlay that cannot drive it.
	 *
	 * @return Boolean True when a gesture was in flight, so the caller resyncs the renderer to the
	 *   committed model rather than leave it on the uncommitted preview.
	 */
	fun abandon(): Boolean {
		if (session.activeMeshOperator.value?.areaId == areaId) {
			session.clearMeshOperator()
		}

		return endGesture()
	}

	/**
	 * Ends the gesture state, and clears the live preview when this transform owned the gesture that wrote it:
	 * a bystander's end (at mount, or as another area latches) leaves the owner's preview alone.
	 *
	 * @return Boolean True when a gesture was in flight.
	 */
	private fun endGesture(): Boolean {
		val owned = gesture.end()

		if (owned) {
			livePreview.restByDrawable = null
			livePreview.clearLattices()
		}

		return owned
	}

	/**
	 * Re-derives the capture's weights after a proportional toggle or falloff change MID-GESTURE (O falls
	 * through the shell ladder to the keymap while an operator runs); the next pointer move applies them.
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
	 * Confirms the in-flight gesture at the latest pointer, committing what moved as ONE undo step registered on the
	 * operation settings strip, then clears the operator (its teardown resyncs the renderer).
	 */
	override fun confirm() {
		drive.settle()

		val result = publishedResult
		val gestureData = gesture.capture
		val request = publishedRequest

		if (result != null && gestureData != null && request != null) {
			// The drive lands folded onto the model as it is now, as one step.
			val movedIndicesByDrawable =
				request.jobs.mapNotNull { job -> job.drawableId?.takeIf { drawableId -> drawableId in result.preview }?.let { drawableId -> drawableId to job.movedIndices.toList() } }.toMap()
			val recorded = session.commitDrive(result.foldedOnto(session.model.value), gestureData.stage, gestureData.transform.operatorKind, movedIndicesByDrawable)

			// The strip's rows for the step just pushed, over the RETAINED capture so an adjustment
			// replays the same frozen geometry - registered before the operator clears, since the
			// teardown drops the capture.  A commit that recorded nothing (the geometry landed where it
			// started) has no step of its own to amend, so it registers nothing.
			if (recorded) {
				registerOnStrip(gestureData)
			}
		}

		session.clearMeshOperator()
	}

	/**
	 * Registers the step just committed on the operation settings strip: a slide's Factor row, or the transform's
	 * rows with its proportional rows.
	 *
	 * @param EditGesture gestureData The gesture.
	 */
	private fun registerOnStrip(gestureData: EditGesture) {
		val transform = gestureData.transform
		val parameters = gesture.lastParameters

		if (transform.operatorKind == MeshOperatorKind.VertexSlide) {
			val slide = gestureData.slide
			val landing = slideLanding

			if (slide != null && landing != null) {
				registerSlideAdjustment(session, areaId, transform, gestureData.geometryByOwner, gestureData.stage, slide.owner, slide.activeVertex, landing.neighborIndex, landing.factor)
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
			registerMeshTransformAdjustment(
				session,
				areaId,
				transform,
				gestureData.geometryByOwner,
				gestureData.stage,
				parameters,
				proportional,
				rotatesInDepth = session.meshOperatorRotatesInDepth(transform.operatorKind),
			) { _, state ->
				session.setProportionalEdit(state)
			}
		}
	}

	/** Cancels the in-flight gesture; the teardown resyncs the renderer. */
	override fun cancel() {
		session.clearMeshOperator()
	}

	/**
	 * The wheel resizes the proportional influence radius mid-gesture (Blender's behavior): geometric
	 * steps and weights re-derived from the frozen originals at once, on the UI thread, and a drive
	 * submitted so the mesh responds without a pointer move.  With the primary modifier held during a Rotate
	 * rotating in depth about a locked axis, it pushes the pivot along Y instead - wheel up away from the viewer, one
	 * fixed world step per whole notch ([pivotDepthSteppedFrom]) - and re-drives the same way.
	 *
	 * @param Float steps The scroll in wheel steps; negative (wheel up) grows the radius.
	 * @param Boolean primaryHeld True while the primary modifier is held.
	 * @param ViewportCamera camera The area camera.
	 * @param IntSize size The area size in pixels.
	 */
	override fun onScroll(steps: Float, primaryHeld: Boolean, camera: ViewportCamera, size: IntSize) {
		val operator = session.activeMeshOperator.value?.takeIf { it.areaId == areaId } ?: return
		val proportional = session.proportionalEdit.value
		val gestureData = gesture.capture

		if (primaryHeld && session.meshOperatorRotatesInDepth(operator.kind) && session.axisConstraint.value != null) {
			// Travel gathered before the capture lands would arrive as a burst of steps once it does.
			if (gestureData == null) {
				return
			}

			pendingDepthSteps += steps
			val wholeSteps = pendingDepthSteps.toInt()

			if (wholeSteps != 0) {
				pendingDepthSteps -= wholeSteps
				var depth = session.pivotDepth.value

				repeat(abs(wholeSteps)) {
					depth = pivotDepthSteppedFrom(depth, wholeSteps.toFloat())
				}

				session.setPivotDepth(depth)
				redrive(camera, size)
			}

			return
		}

		// A scroll that pushes no depth drops a partial notch, so it never tips a later push over a step early.
		pendingDepthSteps = 0f

		if (steps != 0f && proportional != null && gestureData != null && session.meshOperatorTakesProportional(operator.kind)) {
			session.setProportionalRadius(proportional.radiusWorld * PROPORTIONAL_RADIUS_STEP_FACTOR.pow(-steps))
			val updated = session.proportionalEdit.value
			gestureData.transform.applyProportional(updated, updated?.radiusWorld ?: 0f)
			redrive(camera, size)
		}
	}

	/**
	 * Re-drives on an axis lock change, dropping any partial wheel notch first: travel gathered about one axis must not
	 * push the pivot for the next.
	 *
	 * @param ViewportCamera camera The area camera.
	 * @param IntSize size The area size in pixels.
	 */
	override fun onAxisLockChanged(camera: ViewportCamera, size: IntSize) {
		pendingDepthSteps = 0f
		redrive(camera, size)
	}

	/**
	 * Resolves and submits a drive for one virtual-pointer position, on the UI thread, for pointer motion and for a
	 * radius scroll alike.  The parameters resolve once per event because the Rotate branch advances its
	 * accumulator, which must see every step.
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
		val frame =
			TransformGestureFrame(
				transform.anchor,
				start,
				virtualPointer,
				session.axisConstraint.value,
				activeCamera,
				size,
				session.pivotDepth.value,
				session.depthSurface.value,
			)
		val parameters = gestureParameters(operator, frame, transform.rotationTracker)
		// Vertex Slide projects the pointer onto its edge: the best edge is re-picked from the live pointer
		// every move, and a pointer landing on no edge leaves the vertex where it was.
		val slide = gestureData.slide
		val slideMove =
			if (operator == MeshOperatorKind.VertexSlide && slide != null) {
				transform.entries
					.firstOrNull { entry -> entry.owner == slide.owner }
					?.let { entry -> slideLandingToward(slide, entry.positions, frame) }
					?.let { landing -> SlideMove(slide.owner, slide.activeVertex, landing) }
			} else {
				null
			}

		val jobs = meshDriveJobs(transform, gestureData.geometryByOwner, wholeMeshes = false)
		drive.submit(MeshDriveRequest(operator, parameters, jobs, slideMove, session.model.value, gestureData.stage))

		return true
	}

	/**
	 * Lands one drive on the UI thread: the state the confirm reads, the live preview, and the folded model pushed
	 * to the renderer - folded again onto the session's model when a commit replaced the one the request folded onto
	 * (a snap mid-drag), so the preview always shows the current model.
	 *
	 * @param MeshDriveRequest request The request.
	 * @param MeshDriveResult result Its result.
	 */
	private fun publishDrive(request: MeshDriveRequest, result: MeshDriveResult) {
		gesture.lastParameters = request.parameters
		request.slide?.let { move -> slideLanding = move.landing }
		publishedRequest = request
		publishedResult = result
		gesture.preview = result.preview
		livePreview.restByDrawable = result.preview
		livePreview.showLattices(result.latticeWorld, result.latticeForms)

		if (result.latticeForms.isNotEmpty()) {
			pushLatticePreview(result.latticeForms)
		}

		if (result.preview.isEmpty()) {
			return
		}

		val current = session.model.value

		pushPreview(if (request.baseModel === current) result.folded else current.withMeshPositions(result.preview))
	}
}