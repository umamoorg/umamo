package org.umamo.ui.viewport.uv

import androidx.compose.runtime.MutableState
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
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.viewport.gizmo.applyOperator

/**
 * Registers the UV editor's texture-coordinate transform that just committed as the session's
 * adjustable operation.  The rows read in display texels; the rerun applies the edited numbers over
 * the frozen display coordinates and writes only the moved vertices back through [frame] onto the
 * BASE model's stored coordinates (the untouched ones stay bit-identical, as the commit keeps them; see
 * [storedUvsForCommit]).
 * The proportional radius is in texels too, so [onProportional] receives it separately from the
 * state for the caller to place where the editor keeps its radius, and the adjusted record goes with
 * them, so a caller whose rows did not start from the session's own state can write back only what the
 * rigger set there (see [writeBackUvProportional]).
 *
 * The landing is [uvTransformLanding], the same compute a value typed in the sidebar commits through, so
 * an adjustment of either replays exactly what landed.
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
internal fun registerUvTransformAdjustment(
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
 * Where a UV transform lands: each captured mesh's frozen display coordinates through the operator, written
 * back through [frame] onto [baseModel]'s stored coordinates for the moved vertices alone, so the untouched ones
 * stay bit-identical.  One compute for a strip adjustment's rerun and for a value typed in the sidebar, so the
 * first landing and every adjustment of it are the same function.
 *
 * @param ModalTransformCapture transform The frozen capture (display-space positions).
 * @param UvEditFrame frame The space the coordinates were authored in.
 * @param TransformGestureParameters parameters The transform's numbers, in texels.
 * @param PuppetModel baseModel The model whose stored coordinates the moved vertices are written onto.
 * @return Map<DrawableId, FloatArray> Each captured mesh's new stored coordinates.
 */
internal fun uvTransformLanding(
	transform: ModalTransformCapture,
	frame: UvEditFrame,
	parameters: TransformGestureParameters,
	baseModel: PuppetModel,
): Map<DrawableId, FloatArray> {
	val newUvsByDrawable = LinkedHashMap<DrawableId, FloatArray>(transform.entries.size)

	for (entry in transform.entries) {
		val drawableId = entry.drawableId ?: continue
		val display = applyOperator(transform.operatorKind, entry.positions, entry.groups, parameters, entry.influence)
		newUvsByDrawable[drawableId] = storedUvsForCommit(baseModel, drawableId, entry.movedIndices, display, frame)
	}

	return newUvsByDrawable
}

/**
 * Hands a UV transform's adjusted proportional rows back: the row's texel radius to the surface the transform
 * ran on, and the state to the session with the session's own remembered world radius, since a texel radius is
 * no world radius.  The remembered one, not the live one, so turning proportional editing on from the strip never
 * resets the 2D viewport's radius to the default.
 *
 * @param MutableState<Float?> radiusState The surface's texel radius state.
 * @param ProportionalEditState? state The adjusted proportional state, or null for off.
 * @param Float radiusDisplay The row's radius in texels.
 */
internal fun EditorSession.writeBackUvProportional(radiusState: MutableState<Float?>, state: ProportionalEditState?, radiusDisplay: Float) {
	radiusState.value = radiusDisplay
	setProportionalEdit(state?.copy(radiusWorld = proportionalSettings.value.radiusWorld))
}

/**
 * Resolves a surface's proportional radius, seeding it from the surface's size on first use.
 *
 * @param MutableState<Float?> radiusState The surface's radius state.
 * @param UvEditFrame frame The surface's frame.
 * @return Float The influence radius in display (texel) units.
 */
internal fun resolvedUvProportionalRadius(radiusState: MutableState<Float?>, frame: UvEditFrame): Float {
	val current = radiusState.value

	if (current != null) {
		return current
	}

	val seeded = uvProportionalRadiusSeed(frame.displayWidth, frame.displayHeight)
	radiusState.value = seeded

	return seeded
}