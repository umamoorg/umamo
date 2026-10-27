package org.umamo.ui.viewport.viewport2d

import org.umamo.edit.EditorSession
import org.umamo.edit.MeshChange
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.TransformPivotMode
import org.umamo.edit.mesh.commitMeshPositions
import org.umamo.edit.transform.IndividualOriginScope
import org.umamo.edit.transform.ModalCaptureSource
import org.umamo.edit.transform.ModalTransformCapture
import org.umamo.edit.transform.ProportionalRows
import org.umamo.edit.transform.TransformGestureParameters
import org.umamo.edit.transform.TransformParameterKeys
import org.umamo.edit.transform.buildModalTransformCapture
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.transform.CoveredVertices
import org.umamo.ui.transform.DrawableWorldGeometry
import org.umamo.ui.transform.WorldMedian
import org.umamo.ui.transform.coveredMedian

/*
 * A typed or scrubbed Vertex / Median value as an Edit-mode move: a Grab of every covered vertex, captured,
 * driven, committed, and registered on the operation settings strip exactly as a viewport Grab is, so the step,
 * its label, and every later strip adjustment are the viewport's own.
 */

/** The strip rows whose adjustment is the rigger setting proportional editing, which the session then takes up. */
private val PROPORTIONAL_ROW_KEYS =
	setOf(
		TransformParameterKeys.PROPORTIONAL,
		TransformParameterKeys.FALLOFF,
		TransformParameterKeys.PROPORTIONAL_SIZE,
		TransformParameterKeys.CONNECTED_ONLY,
	)

/** The axis a median value is typed on. */
internal enum class MedianAxis {
	/** World x, across. */
	X,

	/** World z, up. */
	Z,
}

/**
 * The covered vertices captured for a move of their median: a Grab capture over every covered vertex with no
 * proportional weights, the geometry each mesh inverts through, and the median the move measures from.
 *
 * Built for one write and reused only until something commits: the operation strip keeps the capture of the step
 * it registers and re-derives its halos on adjustment, so a later write must capture afresh.
 *
 * @property ModalTransformCapture transform The Grab capture.
 * @property Map<DrawableId, DrawableWorldGeometry> geometryById Each moving mesh's captured geometry.
 * @property WorldMedian median The covered median as captured.
 */
internal class CoveredMedianMove(
	val transform: ModalTransformCapture,
	val geometryById: Map<DrawableId, DrawableWorldGeometry>,
	val median: WorldMedian,
) {
	/**
	 * The Grab that puts the median's [axis] coordinate on [worldValue]: the difference on that axis, and exactly
	 * none on the other, so a value typed on one axis never nudges the other by a rounding step.
	 *
	 * @param MedianAxis axis The axis typed on.
	 * @param Float worldValue The world coordinate the median should land on.
	 * @return TransformGestureParameters? The Grab, or null when the median is already there.
	 */
	fun parametersFor(axis: MedianAxis, worldValue: Float): TransformGestureParameters? {
		val delta =
			when (axis) {
				MedianAxis.X -> worldValue - median.worldX
				MedianAxis.Z -> worldValue - median.worldZ
			}

		if (delta == 0f) {
			return null
		}

		return when (axis) {
			MedianAxis.X -> TransformGestureParameters(delta, 0f, 1f, 1f, 0f)
			MedianAxis.Z -> TransformGestureParameters(0f, delta, 1f, 1f, 0f)
		}
	}

	/**
	 * Runs the Grab through the viewport drive's own compute, folded onto [baseModel].
	 *
	 * @param TransformGestureParameters parameters The Grab.
	 * @param PuppetModel baseModel The model the moved rest shapes fold onto.
	 * @return MeshDriveResult Each moved mesh's new rest shape and the folded model.
	 */
	fun drive(parameters: TransformGestureParameters, baseModel: PuppetModel): MeshDriveResult =
		computeMeshDrive(
			MeshDriveRequest(
				MeshOperatorKind.Grab,
				parameters,
				meshDriveJobs(transform, geometryById, wholeMeshes = false),
				null,
				baseModel,
			),
		)
}

/**
 * Captures [covered] for a move of their median.  The pivot is the median point with no anchors: a Grab ignores
 * the pivot, and Individual Origins would only build connectivity islands for nothing.  Each source carries its
 * mesh's triangles, so Connected Only works if the rigger turns proportional editing on from the strip afterward.
 *
 * @param List<CoveredVertices> covered The covered vertices, as captured from the committed model.
 * @return CoveredMedianMove? The move, or null when nothing is covered.
 */
internal fun coveredMedianMove(covered: List<CoveredVertices>): CoveredMedianMove? {
	val median = coveredMedian(covered) ?: return null
	val sources =
		covered.map { mesh ->
			ModalCaptureSource(mesh.geometry.drawableId, mesh.geometry.world, mesh.triangleIndices, mesh.indices)
		}
	val transform =
		buildModalTransformCapture(
			sources = sources,
			pivotMode = TransformPivotMode.MedianPoint,
			individualOriginScope = IndividualOriginScope.ConnectivityIsland,
			operatorKind = MeshOperatorKind.Grab,
			activeAnchor = null,
			cursorAnchor = null,
		) ?: return null

	return CoveredMedianMove(transform, covered.associate { mesh -> mesh.geometry.drawableId to mesh.geometry }, median)
}

/**
 * The model a scrub of the median shows for one frame, without committing anything.
 *
 * @param CoveredMedianMove move The captured move.
 * @param MedianAxis axis The axis scrubbed.
 * @param Float worldValue The world coordinate the median would land on.
 * @return PuppetModel? The previewed model, or null when it would change nothing or a modal transform owns the
 *   meshes.
 */
internal fun EditorSession.previewCoveredMedianMove(move: CoveredMedianMove, axis: MedianAxis, worldValue: Float): PuppetModel? {
	if (activeMeshOperator.value != null) {
		return null
	}

	val parameters = move.parametersFor(axis, worldValue) ?: return null

	return move.drive(parameters, model.value).folded
}

/**
 * Moves the covered vertices so their median's [axis] coordinate lands on [worldValue], as one undo step filed as
 * a move, and registers the step on the operation strip in [areaId] as a Grab: Move X and Move Z, then the
 * proportional rows, off for this move (the panel's move takes no falloff) but carrying the session's remembered
 * curve and Connected Only for the rigger to turn on.  A strip adjustment hands proportional editing back to the
 * session only when the rigger set one of its rows, so moving a Move row never switches proportional editing off.
 *
 * Nothing lands while a modal transform owns the meshes (its confirm owns the next step), or when the median is
 * already there: the drive always returns fresh arrays, so an empty move would otherwise record a step.
 *
 * @param CoveredMedianMove move The captured move.
 * @param MedianAxis axis The axis typed on.
 * @param Float worldValue The world coordinate the median should land on.
 * @param String areaId The area the strip shows in.
 */
internal fun EditorSession.moveCoveredMedianAdjustable(move: CoveredMedianMove, axis: MedianAxis, worldValue: Float, areaId: String) {
	if (activeMeshOperator.value != null) {
		return
	}

	val parameters = move.parametersFor(axis, worldValue) ?: return
	val result = move.drive(parameters, model.value)
	val movedIndicesByDrawable = LinkedHashMap<DrawableId, List<Int>>(move.transform.entries.size)

	for (entry in move.transform.entries) {
		if (entry.drawableId in result.preview) {
			movedIndicesByDrawable[entry.drawableId] = entry.movedIndices.toList()
		}
	}

	val modelBefore = model.value
	commitMeshPositions(MeshChange.TransformVertices(movedIndicesByDrawable, MeshOperatorKind.Grab), result.preview)

	// A commit that recorded nothing has no step of its own for the strip to amend.
	if (model.value === modelBefore) {
		return
	}

	val settings = proportionalSettings.value
	val proportional = ProportionalRows(false, settings.falloff, settings.radiusWorld, settings.connectedOnly)
	registerMeshTransformAdjustment(this, areaId, move.transform, move.geometryById, parameters, proportional) { record, state ->
		if (record.adjustedKey in PROPORTIONAL_ROW_KEYS) {
			setProportionalEdit(state)
		}
	}
}