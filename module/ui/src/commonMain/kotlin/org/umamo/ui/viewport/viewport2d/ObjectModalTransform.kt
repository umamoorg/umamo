package org.umamo.ui.viewport.viewport2d

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshChange
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.SelectionTarget
import org.umamo.edit.eligibleTransformDrawables
import org.umamo.edit.mesh.MeshRestPositions
import org.umamo.edit.mesh.commitObjectPositions
import org.umamo.edit.mesh.withMeshPositions
import org.umamo.edit.transform.IndividualOriginScope
import org.umamo.edit.transform.MeshTransforms
import org.umamo.edit.transform.ModalCaptureSource
import org.umamo.edit.transform.ModalTransformCapture
import org.umamo.edit.transform.buildModalTransformCapture
import org.umamo.render.ViewportCamera
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.transform.DrawableWorldGeometry
import org.umamo.ui.transform.captureDrawableWorlds
import org.umamo.ui.viewport.gizmo.ModalDriveWorker
import org.umamo.ui.viewport.gizmo.ModalGestureState
import org.umamo.ui.viewport.gizmo.ModalTransformTarget
import org.umamo.ui.viewport.gizmo.TransformGestureFrame
import org.umamo.ui.viewport.gizmo.gestureParameters

/**
 * The captured state of an in-flight Object-mode transform: the shared [ModalTransformCapture] (which owns
 * the pivot groups, the anchor, the frozen operator kind, and the rotation tracker) plus the per-drawable
 * [DrawableWorldGeometry] the drive loop needs to invert a transformed world shape back onto the rest
 * arrays.  The geometry is held in a map keyed on the drawable id, looked up by
 * [org.umamo.edit.ModalCaptureEntry], so nothing stays index-aligned with the capture's entry list.
 *
 * @property ModalTransformCapture transform The shared gesture capture (entries, groups, anchor, kind).
 * @property Map<DrawableId, DrawableWorldGeometry> geometryById Each captured drawable's world geometry.
 */
internal class ObjectGesture(
	val transform: ModalTransformCapture,
	val geometryById: Map<DrawableId, DrawableWorldGeometry>,
)

/**
 * The commit side of the Object overlay's modal G / S / R over whole drawables: it captures the selected
 * drawables when an operator latches in its area, drives whole-drawable previews about the shared pivot,
 * confirms as one TransformDrawables undo step, and cancels through the session's operator clear.  The
 * pointer-side mechanics live in [org.umamo.ui.viewport.gizmo.ModalTransformController], which is handed
 * this as its target; Object mode has no scroll behavior.  The drive runs through [drive], resolved per
 * pointer event here and computed off the UI thread in parallel, as the Edit transform's.
 *
 * One instance per area, created with `remember(areaId)`: the pointer loop and the session collectors are
 * long-running and keep the instance they started with, so it holds nothing that may change under them.
 * The session and the renderer are fixed for the area's life (the viewport is keyed on its document), and
 * everything else is read from the session when used.
 *
 * @param String areaId The viewport area the overlay covers; only an operator latched here drives.
 * @param EditorSession session The session owning the model, the object selection, and the operator latch.
 * @param Function pushPreview Pushes a folded preview model to the renderer.
 */
internal class ObjectModalTransform(
	private val areaId: String,
	private val session: EditorSession,
	private val pushPreview: (PuppetModel) -> Unit,
) : ModalTransformTarget {
	/**
	 * The per-area modal-gesture bookkeeping (last pointer, capture + preview, gesture origin, cursor wrap,
	 * pointer controller); the capture is the Object-mode gesture.
	 */
	val gesture = ModalGestureState<ObjectGesture, MeshRestPositions>()

	/**
	 * The drive: requests resolved per pointer event, the drawables computed off the UI thread in parallel,
	 * the result published back on it.  The overlay runs it (ModalDriveEffect); with no worker running, a
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
	 * Starts the gesture as an operator latches in this area: freezes each selected drawable's world
	 * geometry at the current object-mode pose and hands the shared builder its sources plus this area's
	 * active-element / cursor anchors.  Drops the operator when nothing transformable survives.
	 *
	 * @param MeshOperatorKind kind The latched operator.
	 */
	fun begin(kind: MeshOperatorKind) {
		val model = session.model.value
		val pose = session.pose.value
		val eligibleIds = eligibleTransformDrawables(session.selection.value, model)

		// A drawable with a hidden ancestor has no world mapping and the batch drops it rather than abort
		// the whole gesture (the others still transform).  One batch: the deformer chain bakes once per latch.
		val geometries = captureDrawableWorlds(model, pose, eligibleIds.orEmpty())
		val geometryById = geometries.associateBy { geometry -> geometry.drawableId }

		// Object mode moves every vertex of each drawable, so the covered set is the whole mesh.  Triangle
		// connectivity is unused here (WholeMesh pivots, no proportional editing), so an empty array serves.
		val sources =
			geometries.map { geometry ->
				ModalCaptureSource(geometry.drawableId, geometry.world, IntArray(0), geometry.allIndices)
			}

		// The two per-area anchors the shared builder cannot resolve itself: the active drawable's own
		// centroid and the 2D cursor.  The builder falls back to the combined median when nothing is active; an
		// unplaced cursor resolves to the world origin, like the snap commands.
		val activeAnchor =
			(session.selection.value.active as? SelectionTarget.Drawable)?.id
				?.let { activeId -> geometryById[activeId] }
				?.let { geometry -> MeshTransforms.medianPivot(geometry.world, geometry.allIndices) }
		val cursorAnchor = session.cursor2dOrWorldOrigin().let { cursor -> cursor.worldX to cursor.worldZ }
		val transform =
			buildModalTransformCapture(
				sources = sources,
				pivotMode = session.pivotMode.value,
				// Object mode's Individual Origins turns each whole drawable about its own centroid.
				individualOriginScope = IndividualOriginScope.WholeMesh,
				operatorKind = kind,
				activeAnchor = activeAnchor,
				cursorAnchor = cursorAnchor,
			)

		if (transform == null) {
			// Nothing transformable survived (all hidden, or the selection changed): drop the operator.
			session.clearObjectOperator()
		} else {
			gesture.begin(ObjectGesture(transform, geometryById), gesture.lastPointer)
		}
	}

	/**
	 * Tears the gesture down as the operator clears.
	 *
	 * @return Boolean True when this area owned a gesture, so the caller resyncs the renderer to the
	 *   committed model, discarding any throwaway preview; a bystander teardown (at mount, or another area's
	 *   latch) must not, or it would stomp the initiating area's live preview.
	 */
	fun end(): Boolean = gesture.end()

	/**
	 * Ends the gesture because the overlay is leaving composition mid-gesture: the mode changed, the area
	 * closed, or the area lost its camera.  The latch effect is cancelled with the overlay and never runs
	 * its teardown, so this does it instead.  The latch is cleared while it is still this area's - a mode
	 * switch has cleared it already, and a latch another area holds is not this one's to clear - so no
	 * gesture is left latched to an overlay that cannot drive it, and none restarts from a fresh gesture
	 * state when the overlay comes back.
	 *
	 * @return Boolean True when a gesture was in flight, so the caller resyncs the renderer to the
	 *   committed model rather than leave it on the uncommitted preview.
	 */
	fun abandon(): Boolean {
		if (session.activeObjectOperator.value?.areaId == areaId) {
			session.clearObjectOperator()
		}

		return gesture.end()
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
		val operator = session.activeObjectOperator.value?.takeIf { it.areaId == areaId } ?: return false

		return submitDrive(operator.kind, virtualPointer, camera, size)
	}

	/**
	 * Confirms the in-flight object transform at the latest pointer: settles a drive the worker has not
	 * published yet, commits every drawable's new rest shape as one undo step (a null / empty preview
	 * means no movement, so nothing commits), registers that step on the operation settings strip over the
	 * retained capture, then clears the operator - its teardown resyncs the renderer.
	 */
	override fun confirm() {
		drive.settle()
		val committed = gesture.preview
		val gestureData = gesture.capture
		val parameters = gesture.lastParameters

		if (committed != null && gestureData != null && committed.isNotEmpty()) {
			val transform = gestureData.transform
			val modelBefore = session.model.value
			session.commitObjectPositions(MeshChange.TransformDrawables(transform.drawableIds, transform.operatorKind), committed)
			// A commit that recorded nothing (the drawables landed where they started) has no step of its
			// own to amend, so it registers nothing.
			if (parameters != null && session.model.value !== modelBefore) {
				registerObjectTransformAdjustment(session, areaId, transform, gestureData.geometryById, parameters)
			}
		}
		session.clearObjectOperator()
	}

	/** Cancels the in-flight gesture; the teardown resyncs the renderer when the operator clears. */
	override fun cancel() {
		session.clearObjectOperator()
	}

	/**
	 * Resolves and submits a drive for one virtual-pointer position, on the UI thread: the pointer frame into
	 * the parameters every drawable applies about the shared pivot (once per event: the Rotate branch
	 * advances the accumulator, which must see every step) and the model to fold onto.
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
		// Proportional editing is an Edit-mode feature: object mode moves whole drawables, so there are no
		// unselected vertices to weight.
		val jobs = meshDriveJobs(transform, gestureData.geometryById, wholeMeshes = true)

		drive.submit(MeshDriveRequest(operator, parameters, jobs, null, session.model.value))

		return true
	}

	/**
	 * Lands one drive on the UI thread: the parameters the confirm hands the strip, the preview, and the
	 * folded model pushed to the renderer - folded again onto the session's model when a commit replaced the
	 * one the request folded onto, so the preview always shows the current model.
	 *
	 * @param MeshDriveRequest request The request.
	 * @param MeshDriveResult result Its result.
	 */
	private fun publishDrive(request: MeshDriveRequest, result: MeshDriveResult) {
		gesture.lastParameters = request.parameters
		gesture.preview = result.preview
		val current = session.model.value
		pushPreview(if (request.baseModel === current) result.folded else current.withMeshPositions(result.preview))
	}
}