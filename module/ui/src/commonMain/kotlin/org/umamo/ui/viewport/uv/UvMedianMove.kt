package org.umamo.ui.viewport.uv

import androidx.compose.runtime.MutableState
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshChange
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.MeshSelection
import org.umamo.edit.TransformPivotMode
import org.umamo.edit.mesh.commitMeshUvs
import org.umamo.edit.mesh.withMeshUvs
import org.umamo.edit.transform.IndividualOriginScope
import org.umamo.edit.transform.ModalTransformCapture
import org.umamo.edit.transform.ProportionalRows
import org.umamo.edit.transform.TransformGestureParameters
import org.umamo.edit.transform.TransformParameterKeys
import org.umamo.edit.transform.buildModalTransformCapture
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.viewport.gizmo.GizmoMeshGeometry

/*
 * A typed or scrubbed UV Vertex / Median value as an Edit-mode move: a Grab of every covered texture coordinate on
 * the shown surface, captured, landed, committed, and registered on the operation settings strip exactly as the UV
 * editor's own Grab is, so the step, its label, and every later strip adjustment are that Grab's.
 */

/**
 * The covered texture coordinates captured for a move of their median: a Grab capture over every covered vertex
 * with no proportional weights and every other shown mesh in the edit as a bystander, the frame the coordinates were
 * authored in, the surface's radius, and the median the move measures from.  All frozen when the move is built, as a viewport gesture freezes them, so a page switch
 * between the first frame of a scrub and its release cannot move the target.
 *
 * Built from the committed coordinates at a write's first frame and reused only until something commits: the
 * operation strip keeps the capture of the step it registers and re-derives its halos on adjustment.
 *
 * @property ModalTransformCapture transform The Grab capture, positions in display texels (y up).
 * @property UvEditFrame frame The surface's authoring frame.
 * @property MutableState<Float?> radiusState The surface's proportional radius, which the strip's rows read and set.
 * @property UvDisplayMedian median The covered median as captured.
 */
internal class UvMedianMove(
	val transform: ModalTransformCapture,
	val frame: UvEditFrame,
	val radiusState: MutableState<Float?>,
	val median: UvDisplayMedian,
) {
	/**
	 * The Grab that puts the median's [axis] coordinate on [texelValue]: the difference on that axis, and exactly
	 * none on the other, so a value typed on one axis never nudges the other by a rounding step.
	 *
	 * @param TexelAxis axis The axis typed on.
	 * @param Float texelValue The y-down texel coordinate the median should land on.
	 * @return TransformGestureParameters? The Grab in display texels, or null when the median is already there.
	 */
	fun parametersFor(axis: TexelAxis, texelValue: Float): TransformGestureParameters? {
		val delta =
			when (axis) {
				TexelAxis.X -> texelValue - median.displayX
				TexelAxis.Y -> frame.displayYOfTexelY(texelValue) - median.displayY
			}

		if (delta == 0f) {
			return null
		}

		return when (axis) {
			TexelAxis.X -> TransformGestureParameters(delta, 0f, 1f, 1f, 0f)
			TexelAxis.Y -> TransformGestureParameters(0f, delta, 1f, 1f, 0f)
		}
	}

	/**
	 * Where the Grab lands on [baseModel]: the strip rerun's own compute.
	 *
	 * @param TransformGestureParameters parameters The Grab.
	 * @param PuppetModel baseModel The model the moved coordinates are written onto.
	 * @return Map<DrawableId, FloatArray> Each captured mesh's new stored coordinates.
	 */
	fun landing(parameters: TransformGestureParameters, baseModel: PuppetModel): Map<DrawableId, FloatArray> =
		uvTransformLanding(transform, frame, parameters, baseModel)
}

/**
 * Captures the coordinates [selection] covers across the shown meshes for a move of their median.  The pivot is the
 * median point with no anchors: a Grab ignores the pivot, and Individual Origins would only build connectivity islands
 * for nothing.  Every shown mesh in the edit is offered, each with its triangles, the way the UV editor's own Grab
 * offers them ([uvCaptureSources]), so turning proportional editing on from the strip afterward reaches the same
 * meshes that Grab's would - a straight-line halo into the meshes with nothing covered, Connected Only along each
 * mesh's own edges.
 *
 * @param MeshSelection selection The mesh selection.
 * @param List<GizmoMeshGeometry> geometries The shown meshes' display geometry, read from the COMMITTED coordinates.
 * @param UvEditFrame frame The surface's authoring frame.
 * @param MutableState<Float?> radiusState The surface's proportional radius.
 * @return UvMedianMove? The move, or null when nothing is covered.
 */
internal fun uvMedianMove(selection: MeshSelection, geometries: List<GizmoMeshGeometry>, frame: UvEditFrame, radiusState: MutableState<Float?>): UvMedianMove? {
	val covered = uvCoveredVertices(selection, geometries)
	val median = uvCoveredMedian(covered) ?: return null
	val coveredByOwner = covered.associate { mesh -> mesh.geometry.owner to mesh.indices }
	val sources = uvCaptureSources(selection, geometries) { geometry -> coveredByOwner[geometry.owner].orEmpty() }
	val transform =
		buildModalTransformCapture(
			sources = sources,
			pivotMode = TransformPivotMode.MedianPoint,
			individualOriginScope = IndividualOriginScope.ConnectivityIsland,
			operatorKind = MeshOperatorKind.Grab,
			activeAnchor = null,
			cursorAnchor = null,
		) ?: return null

	return UvMedianMove(transform, frame, radiusState, median)
}

/**
 * The model a scrub of the median shows for one frame, without committing anything.
 *
 * @param UvMedianMove move The captured move.
 * @param TexelAxis axis The axis scrubbed.
 * @param Float texelValue The y-down texel coordinate the median would land on.
 * @return PuppetModel? The previewed model, or null when it would change nothing or a UV transform owns the
 *   coordinates.
 */
internal fun EditorSession.previewUvMedianMove(move: UvMedianMove, axis: TexelAxis, texelValue: Float): PuppetModel? {
	if (activeUvOperator.value != null) {
		return null
	}

	val parameters = move.parametersFor(axis, texelValue) ?: return null
	val baseModel = model.value

	return baseModel.withMeshUvs(move.landing(parameters, baseModel))
}

/**
 * Moves the covered texture coordinates so their median's [axis] coordinate lands on [texelValue], as one undo step
 * filed as a UV move, and registers the step on the operation strip in [areaId] as the UV editor's Grab: Move X and
 * Move Y, then the proportional rows, off for this move but carrying the session's remembered curve and Connected
 * Only and the surface's radius.  A strip adjustment hands proportional editing back only when the rigger set one
 * of its rows, so moving a Move row never switches it off.
 *
 * Nothing lands while a UV transform owns the coordinates (its confirm owns the next step), or when the median is
 * already there.
 *
 * @param UvMedianMove move The captured move.
 * @param TexelAxis axis The axis typed on.
 * @param Float texelValue The y-down texel coordinate the median should land on.
 * @param String areaId The area the strip shows in.
 */
internal fun EditorSession.moveUvMedianAdjustable(move: UvMedianMove, axis: TexelAxis, texelValue: Float, areaId: String) {
	if (activeUvOperator.value != null) {
		return
	}

	val parameters = move.parametersFor(axis, texelValue) ?: return
	val modelBefore = model.value
	val landing = move.landing(parameters, modelBefore)
	val movedIndicesByDrawable = LinkedHashMap<DrawableId, List<Int>>(move.transform.entries.size)

	for (entry in move.transform.entries) {
		val drawableId = entry.drawableId ?: continue

		if (drawableId in landing) {
			movedIndicesByDrawable[drawableId] = entry.movedIndices.toList()
		}
	}

	commitMeshUvs(MeshChange.TransformUvs(movedIndicesByDrawable, MeshOperatorKind.Grab), landing)

	// A commit that recorded nothing has no step of its own for the strip to amend.
	if (model.value === modelBefore) {
		return
	}

	val settings = proportionalSettings.value
	val radius = resolvedUvProportionalRadius(move.radiusState, move.frame)
	val proportional = ProportionalRows(false, settings.falloff, radius, settings.connectedOnly)
	registerUvTransformAdjustment(this, areaId, move.transform, move.frame, parameters, proportional) { record, state, radiusDisplay ->
		if (record.adjustedKey in TransformParameterKeys.PROPORTIONAL_ROWS) {
			writeBackUvProportional(move.radiusState, state, radiusDisplay)
		}
	}
}