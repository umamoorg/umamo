package org.umamo.ui.viewport.viewport2d

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.EditorSession
import org.umamo.edit.IndividualOriginScope
import org.umamo.edit.MeshChange
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.MeshTransforms
import org.umamo.edit.ModalCaptureSource
import org.umamo.edit.ModalTransformCapture
import org.umamo.edit.SelectionTarget
import org.umamo.edit.buildModalTransformCapture
import org.umamo.edit.eligibleTransformDrawables
import org.umamo.edit.withMeshPositions
import org.umamo.render.ViewportCamera
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.transform.DrawableWorldGeometry
import org.umamo.ui.transform.captureDrawableWorlds
import org.umamo.ui.viewport.gizmo.ModalGestureState
import org.umamo.ui.viewport.gizmo.ModalTransformTarget
import org.umamo.ui.viewport.gizmo.TransformGestureFrame
import org.umamo.ui.viewport.gizmo.applyOperator
import org.umamo.ui.viewport.gizmo.gestureParameters

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
internal class ObjectGesture(
	val transform: ModalTransformCapture,
	val geometryById: Map<DrawableId, DrawableWorldGeometry>,
)

/**
 * The commit side of the Object overlay's modal G / S / R over whole drawables: it captures the selected
 * drawables when an operator latches in its area, drives whole-drawable previews about the shared pivot,
 * confirms as one TransformDrawables undo step, and cancels through the session's operator clear.  The
 * pointer-side mechanics live in [org.umamo.ui.viewport.gizmo.ModalTransformController], which is handed
 * this as its target; Object mode has no scroll behavior.
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
	val gesture = ModalGestureState<ObjectGesture>()

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
	 * closed, or the area lost its camera.  The latch effect is cancelled with the overlay and never runs its teardown,
	 * so this does it instead.  The latch is cleared while it is still this area's - a mode switch has
	 * cleared it already, and a latch another area holds is not this one's to clear - so no gesture is
	 * left latched to an overlay that cannot drive it, and none restarts from a fresh gesture state when
	 * the overlay comes back.
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
	 * Drives the preview for one virtual-pointer position, when the latched operator is this area's.
	 *
	 * @param Offset virtualPointer The wrap-continuous pointer.
	 * @param ViewportCamera camera The area camera.
	 * @param IntSize size The area size in pixels.
	 * @return Boolean True when a preview was driven.
	 */
	override fun drivePreview(virtualPointer: Offset, camera: ViewportCamera, size: IntSize): Boolean {
		// Defensive ownership check (the pointer loop already gates): only the initiating area drives.
		val operator = session.activeObjectOperator.value?.takeIf { it.areaId == areaId } ?: return false
		return drive(operator.kind, virtualPointer, camera, size)
	}

	/**
	 * Confirms the in-flight object transform: commits every drawable's new base positions as one undo step
	 * (a null / empty preview means no movement, so nothing commits), registers that step on the operation
	 * settings strip over the retained capture, then clears the operator - its teardown resyncs the renderer.
	 */
	override fun confirm() {
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
	 * Drives the preview for one virtual-pointer position: applies the operator to every captured
	 * drawable's whole geometry about the shared pivot, maps each result back to local through the
	 * deformer-chain inverse, and pushes the folded model to the renderer.
	 *
	 * @param MeshOperatorKind operator The latched operator.
	 * @param Offset virtualPointer The wrap-continuous pointer.
	 * @param ViewportCamera activeCamera The area camera.
	 * @param IntSize size The area size in pixels.
	 * @return Boolean False when the capture has not landed yet.
	 */
	private fun drive(operator: MeshOperatorKind, virtualPointer: Offset, activeCamera: ViewportCamera, size: IntSize): Boolean {
		val start = gesture.gestureStart ?: return false
		val gestureData = gesture.capture ?: return false
		val transform = gestureData.transform
		// One pointer frame for the whole capture; only geometry and pivots vary per drawable.  The frame
		// resolves ONCE into the numbers every drawable applies; the confirm hands them to the settings strip.
		val frame = TransformGestureFrame(transform.anchor, start, virtualPointer, session.axisConstraint.value, activeCamera, size)
		val parameters = gestureParameters(operator, frame, transform.rotationTracker)
		gesture.lastParameters = parameters
		val newBaseByDrawable = LinkedHashMap<DrawableId, FloatArray>(transform.entries.size)
		var folded = session.model.value
		for (entry in transform.entries) {
			val geometry = gestureData.geometryById.getValue(entry.drawableId)
			// Proportional editing is an Edit-mode feature: object mode moves whole drawables, so there
			// are no unselected vertices to weight.
			val transformedWorld = applyOperator(operator, entry.positions, entry.groups, parameters, emptyMap())
			val newBase = geometry.worldToBase(transformedWorld, entry.coveredIndices)
			newBaseByDrawable[entry.drawableId] = newBase
			folded = folded.withMeshPositions(entry.drawableId, newBase)
		}
		gesture.preview = newBaseByDrawable
		pushPreview(folded)
		return true
	}
}