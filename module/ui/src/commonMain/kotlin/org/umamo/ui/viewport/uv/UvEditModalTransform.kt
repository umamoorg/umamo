package org.umamo.ui.viewport.uv

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.DEFAULT_PROPORTIONAL_RADIUS_WORLD
import org.umamo.edit.EditorSession
import org.umamo.edit.IndividualOriginScope
import org.umamo.edit.MeshChange
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.MeshSelection
import org.umamo.edit.MeshTopology
import org.umamo.edit.ModalCaptureSource
import org.umamo.edit.ModalTransformCapture
import org.umamo.edit.PROPORTIONAL_RADIUS_STEP_FACTOR
import org.umamo.edit.ProportionalEditState
import org.umamo.edit.ProportionalRows
import org.umamo.edit.buildModalTransformCapture
import org.umamo.edit.withMeshUvs
import org.umamo.render.ViewportCamera
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.viewport.gizmo.GizmoMeshGeometry
import org.umamo.ui.viewport.gizmo.ModalDriveWorker
import org.umamo.ui.viewport.gizmo.TransformGestureFrame
import org.umamo.ui.viewport.gizmo.activeElementMedian
import org.umamo.ui.viewport.gizmo.gestureParameters
import kotlin.math.pow

/** The smallest useful proportional influence radius in display (texel) units. */
private const val MIN_UV_PROPORTIONAL_RADIUS_DISPLAY = 1f

/**
 * The captured state of an in-flight UV transform: the shared [ModalTransformCapture] (its entries hold each
 * mesh's frozen display-space coordinates as their positions, plus the pivot groups, proportional halos, and
 * moved sets) together with the frame those coordinates were mapped in.  The texture-space sibling of the
 * Edit gesture, minus every deformer concern: UVs live in one flat display space, so there is no deformer
 * space mapping, no movement transfer, and no world/local split - the operator transforms the display
 * coordinates directly and the result converts back through the frame to the stored coordinates.
 *
 * The frame freezes here so the display-to-uv conversion at drive and commit always matches the space the
 * originals were mapped in (the shown surface can hop mid-gesture: the page commands still dispatch during a
 * modal, and the active drawable can change from another area).  The radius state freezes with it, because
 * the radius is in that frame's texels too.
 *
 * @property ModalTransformCapture transform The shared gesture capture (entries, groups, anchor, halos, kind).
 * @property UvEditFrame frame The space the gesture is authored in (its texel size and the conversion
 *   back to the stored coordinates).
 * @property MutableState<Float?> proportionalRadius The proportional radius of the surface the gesture began
 *   on, in its display texels; seeded by the time the gesture exists.
 */
internal class UvEditGesture(
	val transform: ModalTransformCapture,
	val frame: UvEditFrame,
	val proportionalRadius: MutableState<Float?>,
)

/**
 * The commit side of the UV Edit overlay's modal G / S / R over raw texture coordinates: it captures the
 * shown meshes' covered vertices when an operator latches in its area, drives the shared operator math over
 * the frozen display coordinates (no deformer inverse), previews the converted coordinates through the
 * render sync so the 2D viewport shows the art resampling as the mapping moves, confirms ONE undo step via
 * commitMeshUvs, and resizes the proportional influence radius on scroll.  The drive runs through [drive]:
 * each pointer event resolves a request here, on the UI thread, and the conversion runs off it, publishing
 * back here.
 *
 * The proportional radius is the UV editor's own, in display texels - the session's world radius is scaled
 * for the puppet canvas and means nothing on a texture surface.  The host keeps one per shown surface and
 * hands over the shown one through [proportionalRadius]; a gesture takes the radius state of the surface it
 * begins on and keeps it to the end, with its frame.  So a gesture begun after a page or layer switch works
 * on the surface now shown, while a switch mid-gesture leaves the wheel, the weights, the strip rows, and a
 * later strip adjustment all on the surface the gesture's coordinates are measured in.
 *
 * @param String areaId The UV editor area the overlay covers; only an operator latched here drives.
 * @param EditorSession session The session owning the mesh selection, the model, and the UV latch.
 * @param State<MutableState<Float?>> proportionalRadius The shown surface's radius state, as the host
 *   currently hands it over.
 * @param Function pushPreview Pushes a folded preview model to the renderer.
 */
internal class UvEditModalTransform(
	areaId: String,
	session: EditorSession,
	private val proportionalRadius: State<MutableState<Float?>>,
	private val pushPreview: (PuppetModel) -> Unit,
) : UvModalTransform<UvEditGesture>(areaId, session) {
	// The request the published preview answers: the confirm names the vertices it moved, which a radius
	// or falloff change since may have changed on the capture itself.
	private var publishedRequest: UvDriveRequest? = null

	/**
	 * The drive: requests resolved per pointer event, computed off the UI thread, the result published back
	 * on it.  The overlay runs it (ModalDriveEffect); with no worker running, a request computes inline.
	 */
	val drive =
		ModalDriveWorker<UvDriveRequest, UvDriveResult>(
			gesture = gesture,
			computeSequential = ::computeUvDrive,
			publish = { request, result -> publishDrive(request, result) },
		)

	/**
	 * Starts the gesture as an operator latches in this area.  The capture covers only the shown meshes
	 * with covered vertices; the anchor follows the pivot mode in display space.  Drops the operator when
	 * nothing on the shown surface is movable.
	 *
	 * @param MeshOperatorKind kind The latched operator.
	 * @param List<GizmoMeshGeometry> geometries The shown meshes' display geometry.
	 * @param MeshSelection selection The mesh selection.
	 * @param UvEditFrame frame The shown surface's frame, which the gesture freezes.
	 */
	fun begin(kind: MeshOperatorKind, geometries: List<GizmoMeshGeometry>, selection: MeshSelection, frame: UvEditFrame) {
		// Offer each shown mesh with covered vertices to the shared capture builder, its frozen display-space
		// coordinates as the source positions (no deformer mapping in UV space).
		val sources = ArrayList<ModalCaptureSource>()
		for (geometry in geometries) {
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
		// The two per-area anchors the shared builder cannot resolve itself, in display space: the active
		// element's own covered median and the UV cursor.  Null falls back to the shared median.
		val transform =
			buildModalTransformCapture(
				sources = sources,
				pivotMode = session.pivotMode.value,
				// UV islands split the same as Edit mode (UVs share the vertex index space).
				individualOriginScope = IndividualOriginScope.ConnectivityIsland,
				operatorKind = kind,
				activeAnchor = activeElementMedian(selection, geometries),
				cursorAnchor = uvCursorDisplay(session, frame),
			)
		if (transform == null) {
			// Nothing movable on the shown surface (the selection's covered meshes live elsewhere or carry
			// no editable UVs): drop the operator.
			session.clearUvOperator()
			return
		}
		// The radius resolves here even with proportional editing off, so the surface's first gesture seeds it.
		val radiusState = proportionalRadius.value
		transform.applyProportional(session.proportionalEdit.value, resolvedProportionalRadius(radiusState, frame))
		gesture.begin(UvEditGesture(transform, frame, radiusState), gesture.lastPointer)
		publishedRequest = null
	}

	/**
	 * Re-derives the capture's weights after a proportional toggle or falloff change MID-GESTURE (the
	 * keyboard-side complement of the scroll resize); the next pointer move applies them.
	 *
	 * @param ProportionalEditState? state The proportional state now in effect, or null for off.
	 */
	fun reapplyProportional(state: ProportionalEditState?) {
		val gestureData = gesture.capture
		if (gestureData != null && session.activeUvOperator.value != null) {
			gestureData.transform.applyProportional(state, resolvedProportionalRadius(gestureData.proportionalRadius, gestureData.frame))
		}
	}

	/**
	 * Confirms the in-flight gesture at the latest pointer: settles a drive the worker has not published yet,
	 * converts each moving mesh's display preview back to stored coordinates and commits them as ONE undo
	 * step, registers that step on the operation settings strip, then clears the operator (its teardown
	 * resyncs the renderer to the committed model the bridge republishes).  A null preview means no
	 * movement, so nothing commits.
	 */
	override fun confirm() {
		drive.settle()
		val committed = gesture.preview
		val gestureData = gesture.capture
		val parameters = gesture.lastParameters
		val request = publishedRequest
		if (committed != null && gestureData != null && request != null) {
			val transform = gestureData.transform
			val newUvsByDrawable = LinkedHashMap<DrawableId, FloatArray>(request.jobs.size)
			val vertexIndicesByDrawable = LinkedHashMap<DrawableId, List<Int>>(request.jobs.size)
			for (job in request.jobs) {
				val transformed = committed[job.drawableId] ?: continue
				// Only the moved vertices are written; untouched ones keep their exact stored values (see
				// storedUvsWithMoved).  Same discipline as the snap path, and the reason a gesture over a
				// wide selection does not report every mesh it covered as edited.
				newUvsByDrawable[job.drawableId] = storedUvsForCommit(session.model.value, job.drawableId, job.movedIndices, transformed, gestureData.frame)
				// The moved set, not just the covered set: proportional editing moves weighted unselected
				// vertices too, and the change metadata must name every vertex touched, as the drive that
				// computed these coordinates moved them.
				vertexIndicesByDrawable[job.drawableId] = job.movedIndices.toList()
			}
			if (newUvsByDrawable.isNotEmpty()) {
				val modelBefore = session.model.value
				session.commitMeshUvs(MeshChange.TransformUvs(vertexIndicesByDrawable, transform.operatorKind), newUvsByDrawable)
				// The strip's rows for the step just pushed, over the RETAINED capture and frame so an
				// adjustment replays the same frozen coordinates - registered before the operator clears,
				// since the teardown drops the capture.  A commit that recorded nothing has no step to amend.
				// The proportional radius here is the editor's own texel radius, so the write-back keeps the
				// session's world radius as it was and lands the row's value on the surface the gesture ran
				// on, which stays this step's surface whatever is shown when the row is edited.
				if (parameters != null && session.model.value !== modelBefore) {
					val radiusState = gestureData.proportionalRadius
					val proportional = ProportionalRows.of(session.proportionalEdit.value, resolvedProportionalRadius(radiusState, gestureData.frame))
					registerUvTransformAdjustment(session, areaId, transform, gestureData.frame, parameters, proportional) { state, radiusDisplay ->
						radiusState.value = radiusDisplay
						val radiusWorld = session.proportionalEdit.value?.radiusWorld ?: DEFAULT_PROPORTIONAL_RADIUS_WORLD
						session.setProportionalEdit(state?.copy(radiusWorld = radiusWorld))
					}
				}
			}
		}
		session.clearUvOperator()
	}

	/**
	 * The wheel resizes the display-unit influence radius mid-gesture (the Edit overlay's behavior, in this
	 * space's units): geometric steps and weights re-derived from the frozen originals at once, on the UI
	 * thread, and a drive submitted so the mapping responds without pointer motion.
	 *
	 * @param Float steps The scroll in wheel steps; negative (wheel up) grows the radius.
	 * @param ViewportCamera camera The area camera.
	 * @param IntSize size The area size in pixels.
	 */
	override fun onScroll(steps: Float, camera: ViewportCamera, size: IntSize) {
		val operator = ownedOperator() ?: return
		val proportional = session.proportionalEdit.value
		val gestureData = gesture.capture
		if (steps != 0f && proportional != null && gestureData != null) {
			val radiusState = gestureData.proportionalRadius
			val maxRadius = 4f * maxOf(gestureData.frame.displayWidth, gestureData.frame.displayHeight)
			val resized =
				(resolvedProportionalRadius(radiusState, gestureData.frame) * PROPORTIONAL_RADIUS_STEP_FACTOR.pow(-steps))
					.coerceIn(MIN_UV_PROPORTIONAL_RADIUS_DISPLAY, maxRadius)
			radiusState.value = resized
			gestureData.transform.applyProportional(proportional, resized)
			submitDrive(operator.kind, gesture.cursorWrap.virtualPointer(gesture.lastPointer), camera, size)
		}
	}

	/**
	 * Resolves and submits a drive for one virtual-pointer position, on the UI thread: the pointer frame
	 * into the parameters every mesh applies (once per event: the Rotate branch advances the accumulator,
	 * which must see every step), each mesh's halo as it is now, and the model to fold onto.
	 * Shared by Move and the radius Scroll.
	 *
	 * @param MeshOperatorKind operator The latched operator.
	 * @param Offset virtualPointer The wrap-continuous pointer.
	 * @param ViewportCamera camera The area camera.
	 * @param IntSize size The area size in pixels.
	 * @return Boolean False when the capture has not landed yet.
	 */
	override fun submitDrive(operator: MeshOperatorKind, virtualPointer: Offset, camera: ViewportCamera, size: IntSize): Boolean {
		val start = gesture.gestureStart ?: return false
		val gestureData = gesture.capture ?: return false
		val transform = gestureData.transform
		val frame = TransformGestureFrame(transform.anchor, start, virtualPointer, session.axisConstraint.value, camera, size)
		val parameters = gestureParameters(operator, frame, transform.rotationTracker)
		drive.submit(UvDriveRequest(operator, parameters, uvDriveJobs(transform), gestureData.frame, session.model.value))
		return true
	}

	/**
	 * Lands one drive on the UI thread: the parameters the confirm hands the settings strip, the preview,
	 * and the folded model pushed to the renderer - folded again onto the session's model when a commit
	 * replaced the one the request folded onto, so the preview always shows the current model.
	 *
	 * @param UvDriveRequest request The request.
	 * @param UvDriveResult result Its result.
	 */
	private fun publishDrive(request: UvDriveRequest, result: UvDriveResult) {
		gesture.lastParameters = request.parameters
		publishedRequest = request
		gesture.preview = result.preview
		val current = session.model.value
		pushPreview(if (request.baseModel === current) result.folded else current.withMeshUvs(result.storedUvs))
	}

	/**
	 * Resolves a surface's proportional radius, seeding it from the surface's size on first use.
	 *
	 * @param MutableState<Float?> radiusState The surface's radius state.
	 * @param UvEditFrame frame The surface's frame.
	 * @return Float The influence radius in display units.
	 */
	private fun resolvedProportionalRadius(radiusState: MutableState<Float?>, frame: UvEditFrame): Float {
		val current = radiusState.value
		if (current != null) {
			return current
		}
		val seeded = (minOf(frame.displayWidth, frame.displayHeight) / 8f).coerceAtLeast(MIN_UV_PROPORTIONAL_RADIUS_DISPLAY)
		radiusState.value = seeded
		return seeded
	}
}