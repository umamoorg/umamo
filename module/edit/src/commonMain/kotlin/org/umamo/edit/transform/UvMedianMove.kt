package org.umamo.edit.transform

import org.umamo.edit.EditorSession
import org.umamo.edit.MeshChange
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.mesh.commitMeshUvs
import org.umamo.edit.mesh.withMeshUvs
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel

/*
 * A typed or scrubbed UV Vertex / Median value as an Edit-mode move: a Grab of every covered texture coordinate on
 * the shown surface, captured, landed, committed, and registered on the operation settings strip exactly as the UV
 * editor's own Grab is, so the step, its label, and every later strip adjustment are that Grab's.
 */

/**
 * The mean display position of a set of covered vertices.
 *
 * @property Float displayX The median's display x in texels.
 * @property Float displayY The median's display y in texels, y up.
 * @property Int vertexCount How many vertices it is the mean of.
 */
class UvDisplayMedian(val displayX: Float, val displayY: Float, val vertexCount: Int)

/**
 * The covered texture coordinates captured for a move of their median: a Grab capture over the shown meshes, the
 * frame the coordinates were authored in, the surface's radius, and the median the move measures from.  All frozen
 * when the move is built, as a viewport gesture freezes them, so a page switch between the first frame of a scrub
 * and its release cannot move the target.
 *
 * Built from the committed coordinates at a write's first frame and reused only until something commits: the
 * operation strip keeps the capture of the step it registers and re-derives its halos on adjustment.
 *
 * @property ModalTransformCapture transform The Grab capture, positions in display texels (y up).
 * @property UvEditFrame frame The surface's authoring frame.
 * @property UvDisplayMedian median The covered median as captured.
 * @property Function radiusDisplay Reads the surface's proportional radius in display texels, seeding it on first
 *   use: what the strip's rows start from.
 * @property Function onRadiusAdjusted Hands the surface the radius an adjusted proportional row set.
 */
class UvMedianMove(
	val transform: ModalTransformCapture,
	val frame: UvEditFrame,
	val median: UvDisplayMedian,
	val radiusDisplay: () -> Float,
	val onRadiusAdjusted: (Float) -> Unit,
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
 * The model a scrub of the median shows for one frame, without committing anything.
 *
 * @param UvMedianMove move The captured move.
 * @param TexelAxis axis The axis scrubbed.
 * @param Float texelValue The y-down texel coordinate the median would land on.
 * @return PuppetModel? The previewed model, or null when it would change nothing or a UV transform owns the
 *   coordinates.
 */
fun EditorSession.previewUvMedianMove(move: UvMedianMove, axis: TexelAxis, texelValue: Float): PuppetModel? {
	if (activeUvOperator.value != null) {
		return null
	}

	val parameters = move.parametersFor(axis, texelValue) ?: return null
	val baseModel = model.value

	return baseModel.withMeshUvs(move.landing(parameters, baseModel))
}

/**
 * Moves the covered texture coordinates so their median's [axis] coordinate lands on [texelValue], as one undo step
 * registered on the operation strip in [areaId] as the UV editor's Grab.  Its proportional rows start off, and a
 * strip adjustment hands proportional editing back only when the rigger set one of those rows, so moving a Move row
 * never switches it off.
 *
 * @param UvMedianMove move The captured move.
 * @param TexelAxis axis The axis typed on.
 * @param Float texelValue The y-down texel coordinate the median should land on.
 * @param String areaId The area the strip shows in.
 */
fun EditorSession.moveUvMedianAdjustable(move: UvMedianMove, axis: TexelAxis, texelValue: Float, areaId: String) {
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
	val radius = move.radiusDisplay()
	val proportional = ProportionalRows(false, settings.falloff, radius, settings.connectedOnly)
	registerUvTransformAdjustment(this, areaId, move.transform, move.frame, parameters, proportional) { record, state, radiusDisplay ->
		if (record.adjustedKey in TransformParameterKeys.PROPORTIONAL_ROWS) {
			move.onRadiusAdjusted(radiusDisplay)
			setProportionalEditKeepingRadius(state)
		}
	}
}