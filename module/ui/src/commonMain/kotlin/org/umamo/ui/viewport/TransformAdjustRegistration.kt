package org.umamo.ui.viewport

import org.umamo.edit.AdjustableOperation
import org.umamo.edit.EditorSession
import org.umamo.edit.ModalTransformCapture
import org.umamo.edit.ProportionalEditState
import org.umamo.edit.ProportionalRows
import org.umamo.edit.TransformGestureParameters
import org.umamo.edit.TransformParameterKeys
import org.umamo.edit.TransformRowSpace
import org.umamo.edit.floatValue
import org.umamo.edit.rederiveProportionalHalos
import org.umamo.edit.slideParameters
import org.umamo.edit.transformGestureParametersOf
import org.umamo.edit.transformParameters
import org.umamo.edit.withMeshPositions
import org.umamo.edit.withMeshUvs
import org.umamo.runtime.model.DrawableId
import org.umamo.ui.transform.DrawableWorldGeometry

/*
 * The modal transforms' registrations on the operation settings strip - the half that needs geometry.
 * The rows and what they mean live in :edit (TransformAdjust.kt, next to the merge's); what stays here
 * is the rerun each gesture needs, because a rerun replays the RETAINED capture through the viewport's
 * frozen world geometry (DrawableWorldGeometry, which needs :render's evaluator and so cannot sit in
 * :edit) or through the UV editor's frozen frame, and applies the operator math this package owns.
 * Three gestures share the shape: the Edit-mode mesh transform and the Object-mode drawable transform
 * in the 2D viewport, and the Edit-mode texture-coordinate transform in the UV editor.
 */

/**
 * Registers the Edit-mode mesh transform that just committed as the session's adjustable operation.
 * Call it right after the commit and before the operator clears (its teardown drops the capture the
 * caller still holds).  An adjustment re-derives the halos on the RETAINED [transform] from its frozen
 * positions under the edited proportional rows, applies the edited numbers per mesh, inverts each
 * world shape back onto the base mesh through the frozen [geometryById], lands the result over the
 * gesture's own step from the record's base, and hands the proportional state back to
 * [onProportional] so the next gesture starts from it.  Synchronous: an evaluation is the
 * per-pointer-frame cost.
 *
 * @param EditorSession              session      The session the gesture committed into.
 * @param String                     areaId       The viewport the gesture ran in (where the strip shows).
 * @param ModalTransformCapture      transform    The frozen capture.
 * @param Map                        geometryById Each moving drawable's frozen world geometry.
 * @param TransformGestureParameters parameters   The numbers the gesture landed with.
 * @param ProportionalRows?          proportional The proportional rows, or null when the gesture took no weights.
 * @param Function                   onProportional Receives the adjusted proportional state (null for
 *   off) after each landed adjustment; unused when [proportional] is null.
 * @return AdjustableOperation? The record, or null when the session refused the registration or the
 *   operator has no rows.
 */
internal fun registerMeshTransformAdjustment(
	session: EditorSession,
	areaId: String,
	transform: ModalTransformCapture,
	geometryById: Map<DrawableId, DrawableWorldGeometry>,
	parameters: TransformGestureParameters,
	proportional: ProportionalRows?,
	onProportional: (ProportionalEditState?) -> Unit,
): AdjustableOperation? {
	val kind = transform.operatorKind
	val rows = transformParameters(kind, parameters, TransformRowSpace.World, proportional)
	if (rows.isEmpty()) {
		return null
	}
	return session.registerAdjustableOperation(session.model.value, areaId, rows) { record ->
		val adjusted = transformGestureParametersOf(kind, TransformRowSpace.World, record.parameters)
		val proportionalRows = rederiveProportionalHalos(transform, record.parameters)
		val landed =
			transform.entries.fold(record.baseSnapshot.model) { model, entry ->
				val geometry = geometryById[entry.drawableId] ?: return@fold model
				val world = applyOperator(kind, entry.positions, entry.groups, adjusted, entry.influence)
				model.withMeshPositions(entry.drawableId, geometry.worldToBase(world, entry.movedIndices))
			}
		if (session.amendLastCommit(record, landed) && proportionalRows != null) {
			onProportional(proportionalRows.asState())
		}
	}
}

/**
 * Registers the Vertex Slide that just committed as the session's adjustable operation, with its
 * one Factor row.  The rerun slides the same vertex along the same frozen edge by the row's factor
 * from the record's base; the capture's other meshes stay where the base has them.
 *
 * @param EditorSession         session       The session the gesture committed into.
 * @param String                areaId        The viewport the gesture ran in.
 * @param ModalTransformCapture transform     The frozen capture.
 * @param Map                   geometryById  Each moving drawable's frozen world geometry.
 * @param DrawableId            drawableId    The mesh the sliding vertex lives in.
 * @param Int                   vertexIndex   The sliding vertex.
 * @param Int                   neighborIndex The edge's far endpoint the slide landed toward.
 * @param Float                 factor        The landed factor.
 * @return AdjustableOperation? The record, or null when the session refused the registration.
 */
internal fun registerSlideAdjustment(
	session: EditorSession,
	areaId: String,
	transform: ModalTransformCapture,
	geometryById: Map<DrawableId, DrawableWorldGeometry>,
	drawableId: DrawableId,
	vertexIndex: Int,
	neighborIndex: Int,
	factor: Float,
): AdjustableOperation? =
	session.registerAdjustableOperation(session.model.value, areaId, slideParameters(factor)) { record ->
		val entry = transform.entries.firstOrNull { candidate -> candidate.drawableId == drawableId } ?: return@registerAdjustableOperation
		val geometry = geometryById[drawableId] ?: return@registerAdjustableOperation
		val adjustedFactor = record.parameters.floatValue(TransformParameterKeys.SLIDE_FACTOR, factor)
		val world = slideVertexByFactor(entry.positions, vertexIndex, neighborIndex, adjustedFactor)
		session.amendLastCommit(record, record.baseSnapshot.model.withMeshPositions(drawableId, geometry.worldToBase(world, entry.movedIndices)))
	}

/**
 * Registers the Object-mode drawable transform that just committed as the session's adjustable
 * operation: the mesh registration without proportional rows, each whole drawable inverted through
 * its frozen geometry over every vertex.
 *
 * @param EditorSession              session      The session the gesture committed into.
 * @param String                     areaId       The viewport the gesture ran in.
 * @param ModalTransformCapture      transform    The frozen capture.
 * @param Map                        geometryById Each moving drawable's frozen world geometry.
 * @param TransformGestureParameters parameters   The numbers the gesture landed with.
 * @return AdjustableOperation? The record, or null when the session refused the registration or the
 *   operator has no rows.
 */
internal fun registerObjectTransformAdjustment(
	session: EditorSession,
	areaId: String,
	transform: ModalTransformCapture,
	geometryById: Map<DrawableId, DrawableWorldGeometry>,
	parameters: TransformGestureParameters,
): AdjustableOperation? {
	val kind = transform.operatorKind
	val rows = transformParameters(kind, parameters, TransformRowSpace.World, proportional = null)
	if (rows.isEmpty()) {
		return null
	}
	return session.registerAdjustableOperation(session.model.value, areaId, rows) { record ->
		val adjusted = transformGestureParametersOf(kind, TransformRowSpace.World, record.parameters)
		val landed =
			transform.entries.fold(record.baseSnapshot.model) { model, entry ->
				val geometry = geometryById[entry.drawableId] ?: return@fold model
				val world = applyOperator(kind, entry.positions, entry.groups, adjusted, emptyMap())
				model.withMeshPositions(entry.drawableId, geometry.worldToBase(world, entry.coveredIndices))
			}
		session.amendLastCommit(record, landed)
	}
}

/**
 * Registers the UV editor's texture-coordinate transform that just committed as the session's
 * adjustable operation.  The rows read in display texels; the rerun applies the edited numbers over
 * the frozen display coordinates and writes only the moved vertices back through [frame] onto the
 * BASE model's stored coordinates (the untouched ones stay bit-identical, as the commit keeps them).
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
		val landed =
			transform.entries.fold(record.baseSnapshot.model) { model, entry ->
				val display = applyOperator(kind, entry.positions, entry.groups, adjusted, entry.influence)
				model.withMeshUvs(entry.drawableId, storedUvsForCommit(model, entry.drawableId, entry.movedIndices, display, frame))
			}
		if (session.amendLastCommit(record, landed) && proportionalRows != null) {
			onProportional(proportionalRows.asState(), proportionalRows.radius)
		}
	}
}