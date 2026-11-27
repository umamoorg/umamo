package org.umamo.edit.transform

import org.umamo.edit.AdjustableOperation
import org.umamo.edit.EditorSession
import org.umamo.edit.ProportionalEditState
import org.umamo.edit.SessionToolSettings
import org.umamo.edit.mesh.withMeshUvs
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel

/*
 * The UV editor's texture-coordinate transform on the operation settings strip.  Texture coordinates live in one
 * flat display space with no deformer between them and the model, so the whole rerun fits here: the retained
 * capture through the operator, written back through the gesture's frozen frame.  The 2D viewport's registrations
 * need :render's evaluator and stay in :ui.
 */

/**
 * Registers the UV editor's texture-coordinate transform that just committed as the session's
 * adjustable operation, its rows in display texels.  A rerun lands through [uvTransformLanding], the same
 * compute a value typed in the sidebar commits through, so an adjustment of either replays exactly what
 * landed.  The proportional radius is in texels too, so [onProportional] receives it apart from the state,
 * for the caller to place where the editor keeps its radius.
 *
 * @param EditorSession              session      The session the gesture committed into.
 * @param String                     areaId       The UV editor the gesture ran in.
 * @param ModalTransformCapture      transform    The frozen capture (display-space positions).
 * @param UvEditFrame                frame        The space the gesture was authored in.
 * @param TransformGestureParameters parameters   The numbers the gesture landed with, in texels.
 * @param ProportionalRows?          proportional The proportional rows (radius in texels), or null.
 * @param Function                   onProportional Receives the adjusted record, its proportional state
 *   (null for off), and the row's texel radius after each landed adjustment; unused when [proportional]
 *   is null.
 * @return AdjustableOperation? The record, or null when the session refused the registration or the
 *   operator has no rows.
 */
fun registerUvTransformAdjustment(
	session: EditorSession,
	areaId: String,
	transform: ModalTransformCapture,
	frame: UvEditFrame,
	parameters: TransformGestureParameters,
	proportional: ProportionalRows?,
	onProportional: (AdjustableOperation, ProportionalEditState?, Float) -> Unit,
): AdjustableOperation? {
	val kind = transform.operatorKind
	val rows = transformParameters(kind, parameters, TransformRowSpace.UvDisplay, proportional)

	if (rows.isEmpty()) {
		return null
	}

	return session.registerAdjustableOperation(session.model.value, areaId, rows) { record ->
		val adjusted = transformGestureParametersOf(kind, TransformRowSpace.UvDisplay, record.parameters)
		val proportionalRows = rederiveProportionalHalos(transform, record.parameters)
		val baseModel = record.baseSnapshot.model
		val landed = baseModel.withMeshUvs(uvTransformLanding(transform, frame, adjusted, baseModel))

		if (session.amendLastCommit(record, landed) && proportionalRows != null) {
			onProportional(record, proportionalRows.asState(), proportionalRows.radius)
		}
	}
}

/**
 * Where a UV transform lands: each drive job's frozen display coordinates through the operator, written back
 * through [frame] onto [baseModel]'s stored coordinates for the moved vertices alone, so the untouched ones stay
 * bit-identical.  The jobs are the gesture's own ([uvDriveJobs]), so a bystander the halo does not reach is left
 * out here exactly as the drive leaves it out.
 *
 * @param ModalTransformCapture transform The frozen capture (display-space positions).
 * @param UvEditFrame frame The space the coordinates were authored in.
 * @param TransformGestureParameters parameters The transform's numbers, in texels.
 * @param PuppetModel baseModel The model whose stored coordinates the moved vertices are written onto.
 * @return Map<DrawableId, FloatArray> Each moved mesh's new stored coordinates.
 */
internal fun uvTransformLanding(
	transform: ModalTransformCapture,
	frame: UvEditFrame,
	parameters: TransformGestureParameters,
	baseModel: PuppetModel,
): Map<DrawableId, FloatArray> {
	val jobs = uvDriveJobs(transform)
	val newUvsByDrawable = LinkedHashMap<DrawableId, FloatArray>(jobs.size)

	for (job in jobs) {
		val display = applyOperator(transform.operatorKind, job.positions, job.groups, parameters, job.influence)
		newUvsByDrawable[job.drawableId] = storedUvsForCommit(baseModel, job.drawableId, job.movedIndices, display, frame)
	}

	return newUvsByDrawable
}

/**
 * Sets proportional editing from a state whose radius is not a world radius, keeping the session's own remembered
 * world radius: what a UV transform's adjusted proportional rows hand back, since their radius is in texels.  The
 * remembered radius, not the live one, so turning proportional editing on from the strip never resets the 2D
 * viewport's radius to the default.
 *
 * @param ProportionalEditState? state The adjusted proportional state, or null for off.
 */
fun SessionToolSettings.setProportionalEditKeepingRadius(state: ProportionalEditState?) {
	setProportionalEdit(state?.copy(radiusWorld = proportionalSettings.value.radiusWorld))
}