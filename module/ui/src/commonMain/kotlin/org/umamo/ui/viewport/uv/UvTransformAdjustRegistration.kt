package org.umamo.ui.viewport.uv

import org.umamo.edit.AdjustableOperation
import org.umamo.edit.EditorSession
import org.umamo.edit.ProportionalEditState
import org.umamo.edit.mesh.withMeshUvs
import org.umamo.edit.transform.ModalTransformCapture
import org.umamo.edit.transform.ProportionalRows
import org.umamo.edit.transform.TransformGestureParameters
import org.umamo.edit.transform.TransformRowSpace
import org.umamo.edit.transform.rederiveProportionalHalos
import org.umamo.edit.transform.transformGestureParametersOf
import org.umamo.edit.transform.transformParameters
import org.umamo.runtime.model.DrawableId
import org.umamo.ui.viewport.gizmo.applyOperator

/**
 * Registers the UV editor's texture-coordinate transform that just committed as the session's
 * adjustable operation.  The rows read in display texels; the rerun applies the edited numbers over
 * the frozen display coordinates and writes only the moved vertices back through [frame] onto the
 * BASE model's stored coordinates (the untouched ones stay bit-identical, as the commit keeps them; see
 * [storedUvsForCommit]).
 * The proportional radius is in texels too, so [onProportional] receives it separately from the
 * state for the caller to place where the editor keeps its radius.
 *
 * @param EditorSession              session      The session the gesture committed into.
 * @param String                     areaId       The UV editor the gesture ran in.
 * @param ModalTransformCapture      transform    The frozen capture (display-space positions).
 * @param UvEditFrame                frame        The space the gesture was authored in.
 * @param TransformGestureParameters parameters   The numbers the gesture landed with, in texels.
 * @param ProportionalRows?          proportional The proportional rows (radius in texels), or null.
 * @param Function                   onProportional Receives the adjusted proportional state (null for
 *   off) and the row's texel radius after each landed adjustment; unused when [proportional] is null.
 * @return AdjustableOperation? The record, or null when the session refused the registration or the
 *   operator has no rows.
 */
internal fun registerUvTransformAdjustment(
	session: EditorSession,
	areaId: String,
	transform: ModalTransformCapture,
	frame: UvEditFrame,
	parameters: TransformGestureParameters,
	proportional: ProportionalRows?,
	onProportional: (ProportionalEditState?, Float) -> Unit,
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
		val newUvsByDrawable = LinkedHashMap<DrawableId, FloatArray>(transform.entries.size)

		for (entry in transform.entries) {
			val display = applyOperator(kind, entry.positions, entry.groups, adjusted, entry.influence)
			newUvsByDrawable[entry.drawableId] = storedUvsForCommit(baseModel, entry.drawableId, entry.movedIndices, display, frame)
		}

		val landed = baseModel.withMeshUvs(newUvsByDrawable)

		if (session.amendLastCommit(record, landed) && proportionalRows != null) {
			onProportional(proportionalRows.asState(), proportionalRows.radius)
		}
	}
}