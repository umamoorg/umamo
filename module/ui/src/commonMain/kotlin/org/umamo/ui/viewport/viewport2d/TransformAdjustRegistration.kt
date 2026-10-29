package org.umamo.ui.viewport.viewport2d

import org.umamo.edit.AdjustableOperation
import org.umamo.edit.EditorSession
import org.umamo.edit.ElementTopology
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.ProportionalEditState
import org.umamo.edit.TransformPivotMode
import org.umamo.edit.floatValue
import org.umamo.edit.transform.IndividualOriginScope
import org.umamo.edit.transform.ModalCaptureSource
import org.umamo.edit.transform.ModalTransformCapture
import org.umamo.edit.transform.ProportionalRows
import org.umamo.edit.transform.TransformGestureParameters
import org.umamo.edit.transform.TransformParameterKeys
import org.umamo.edit.transform.TransformRowSpace
import org.umamo.edit.transform.buildModalTransformCapture
import org.umamo.edit.transform.meshBounds
import org.umamo.edit.transform.rederiveProportionalHalos
import org.umamo.edit.transform.slideParameters
import org.umamo.edit.transform.transformGestureParametersOf
import org.umamo.edit.transform.transformParameters
import org.umamo.runtime.model.ElementOwner
import org.umamo.ui.transform.DrawableWorldGeometry
import org.umamo.ui.transform.ElementEditStage
import org.umamo.ui.transform.LatticeWorldGeometry
import org.umamo.ui.transform.OwnerWorldGeometry
import org.umamo.ui.transform.amendLatticeLanding

/*
 * The modal transforms' registrations on the operation settings strip - the half that needs geometry.
 * The rows and what they mean live in :edit (TransformAdjust.kt, next to the merge's); what stays here
 * is the rerun each gesture needs, because a rerun replays the RETAINED capture through the viewport's
 * frozen world geometry (OwnerWorldGeometry, which needs :render's evaluator and so cannot sit in
 * :edit), applies the shared operator math (gizmo/TransformOperators.kt), and lands a lattice's points at the
 * stage they were captured at, as the confirm did.  These are the 2D viewport's gestures: the Edit- and Pose-mode
 * element transform, the vertex slide, and the Object-mode transform.  The UV editor's texture-coordinate transform
 * has the same shape over its own frozen frame (uv/UvTransformAdjustRegistration.kt).
 */

/**
 * Registers the Edit- or Pose-mode element transform that just committed as the session's adjustable operation.
 * Call it right after the commit and before the operator clears (its teardown drops the capture the
 * caller still holds).  An adjustment re-derives the halos on the RETAINED [transform] from its frozen
 * positions under the edited proportional rows, applies the edited numbers per owner, inverts each
 * mesh's world shape back onto the rest arrays through the frozen [geometryByOwner] (a lattice's points land at
 * [stage]), lands the result over the gesture's own step from the record's base, and hands the
 * proportional state back to
 * [onProportional] so the next gesture can start from it.  The adjusted record goes with it, so a caller
 * whose rows did not start from the session's own state can write back only what the rigger set there.
 * Synchronous: an evaluation is the per-pointer-frame cost.
 *
 * @param EditorSession              session      The session the gesture committed into.
 * @param String                     areaId       The viewport the gesture ran in (where the strip shows).
 * @param ModalTransformCapture      transform    The frozen capture.
 * @param Map                        geometryByOwner Each moving owner's frozen world geometry.
 * @param ElementEditStage           stage        Where the geometry was captured.
 * @param TransformGestureParameters parameters   The numbers the gesture landed with.
 * @param ProportionalRows?          proportional The proportional rows, or null when the gesture took no weights.
 * @param Function                   onProportional Receives the adjusted record and its proportional state
 *   (null for off) after each landed adjustment; unused when [proportional] is null.
 * @return AdjustableOperation? The record, or null when the session refused the registration or the
 *   operator has no rows.
 */
internal fun registerMeshTransformAdjustment(
	session: EditorSession,
	areaId: String,
	transform: ModalTransformCapture,
	geometryByOwner: Map<ElementOwner, OwnerWorldGeometry>,
	stage: ElementEditStage,
	parameters: TransformGestureParameters,
	proportional: ProportionalRows?,
	onProportional: (AdjustableOperation, ProportionalEditState?) -> Unit,
): AdjustableOperation? {
	val kind = transform.operatorKind
	val rows = transformParameters(kind, parameters, TransformRowSpace.World, proportional)

	if (rows.isEmpty()) {
		return null
	}

	return session.registerAdjustableOperation(session.model.value, areaId, rows) { record ->
		val adjusted = transformGestureParametersOf(kind, TransformRowSpace.World, record.parameters)
		val proportionalRows = rederiveProportionalHalos(transform, record.parameters)
		// The drive's own compute and landing, so a replay and the gesture it replays cannot drift apart.
		val jobs = meshDriveJobs(transform, geometryByOwner, wholeMeshes = false)

		if (session.amendDrive(record, computeMeshDrive(MeshDriveRequest(kind, adjusted, jobs, null, record.baseSnapshot.model, stage)), stage) && proportionalRows != null) {
			onProportional(record, proportionalRows.asState())
		}
	}
}

/**
 * Lands a rerun's drive over the adjusted operation's own step: its lattices at [stage] when it moved any, else its
 * meshes folded onto the record's base.
 *
 * @param AdjustableOperation record The record the rerun was given.
 * @param MeshDriveResult result The rerun's drive.
 * @param ElementEditStage stage Where the moved geometry was captured.
 * @return Boolean True when the step was replaced.
 */
private fun EditorSession.amendDrive(
	record: AdjustableOperation,
	result: MeshDriveResult,
	stage: ElementEditStage,
): Boolean = result.latticeLanding(stage)?.let { landing -> amendLatticeLanding(record, landing) } ?: amendLastCommit(record, result.folded)

/**
 * Registers the Vertex Slide that just committed as the session's adjustable operation, with its
 * one Factor row.  The rerun slides the same vertex along the same frozen edge by the row's factor
 * from the record's base, through the drive's own compute - a lattice's point along its grid line, landed at
 * [stage]; the capture's other owners stay where the base has them.
 *
 * @param EditorSession         session         The session the gesture committed into.
 * @param String                areaId          The viewport the gesture ran in.
 * @param ModalTransformCapture transform       The frozen capture.
 * @param Map                   geometryByOwner Each moving owner's frozen world geometry.
 * @param ElementEditStage      stage           Where the geometry was captured.
 * @param ElementOwner          owner           The mesh or lattice the sliding vertex lives in.
 * @param Int                   vertexIndex     The sliding vertex.
 * @param Int                   neighborIndex   The edge's far endpoint the slide landed toward.
 * @param Float                 factor          The landed factor.
 * @return AdjustableOperation? The record, or null when the session refused the registration.
 */
internal fun registerSlideAdjustment(
	session: EditorSession,
	areaId: String,
	transform: ModalTransformCapture,
	geometryByOwner: Map<ElementOwner, OwnerWorldGeometry>,
	stage: ElementEditStage,
	owner: ElementOwner,
	vertexIndex: Int,
	neighborIndex: Int,
	factor: Float,
): AdjustableOperation? =
	session.registerAdjustableOperation(session.model.value, areaId, slideParameters(factor)) { record ->
		val adjustedFactor = record.parameters.floatValue(TransformParameterKeys.SLIDE_FACTOR, factor)
		val jobs = meshDriveJobs(transform, geometryByOwner, wholeMeshes = false).filter { job -> job.owner == owner }
		val slide = SlideMove(owner, vertexIndex, SlideLanding(neighborIndex, adjustedFactor))
		val request = MeshDriveRequest(MeshOperatorKind.VertexSlide, TransformGestureParameters(0f, 0f, 1f, 1f, 0f), jobs, slide, record.baseSnapshot.model, stage)
		session.amendDrive(record, computeMeshDrive(request), stage)
	}

/**
 * Registers the Object-mode transform that just committed as the session's adjustable operation: the mesh
 * registration without proportional rows, each whole drawable inverted through its frozen geometry over every
 * vertex, and each warp in the capture moved as a whole by the adjusted affine (the Object drive's own landing).
 *
 * @param EditorSession              session      The session the gesture committed into.
 * @param String?                    areaId       The viewport the gesture ran in, or the area a panel-driven
 *   transform's strip should show in (null shows it nowhere).
 * @param ModalTransformCapture      transform    The frozen capture.
 * @param Map                        geometryByOwner Each moving drawable's frozen world geometry.
 * @param TransformGestureParameters parameters   The numbers the gesture landed with.
 * @return AdjustableOperation? The record, or null when the session refused the registration or the
 *   operator has no rows.
 */
internal fun registerObjectTransformAdjustment(
	session: EditorSession,
	areaId: String?,
	transform: ModalTransformCapture,
	geometryByOwner: Map<ElementOwner, DrawableWorldGeometry>,
	parameters: TransformGestureParameters,
): AdjustableOperation? {
	val kind = transform.operatorKind
	val rows = transformParameters(kind, parameters, TransformRowSpace.World, proportional = null)

	if (rows.isEmpty()) {
		return null
	}

	return session.registerAdjustableOperation(session.model.value, areaId, rows) { record ->
		val adjusted = transformGestureParametersOf(kind, TransformRowSpace.World, record.parameters)
		val jobs = meshDriveJobs(transform, geometryByOwner, wholeMeshes = true)
		val request = ObjectDriveRequest(MeshDriveRequest(kind, adjusted, jobs, null, record.baseSnapshot.model), warpAffinesOf(transform, kind, adjusted))
		session.amendLastCommit(record, computeObjectDrive(request).landed)
	}
}

/**
 * Registers a world transform of ONE drawable that committed outside any gesture - the Properties
 * panel's Position and Size rows - as the session's adjustable operation, with the same rows the
 * viewport's Object-mode G / S register and the same rerun over the frozen geometry.
 *
 * The capture is pinned to the drawable's bounds center rather than left on the default median pivot,
 * because the panel's Size row scales about the bounds center: the vertex mean sits off it on an
 * asymmetric mesh, and a Scale row adjusted about the mean would move the Position readout.  A Grab
 * ignores the pivot, so one capture rule serves both.
 *
 * @param EditorSession              session    The session the transform committed into.
 * @param String?                    areaId     The area the strip should show in, or null for nowhere.
 * @param DrawableWorldGeometry      geometry   The frozen geometry the commit was planned against.
 * @param MeshOperatorKind           kind       Grab for a move, Scale for a resize.
 * @param TransformGestureParameters parameters The numbers the commit landed with, in world space.
 * @return AdjustableOperation? The record, or null when the session refused the registration.
 */
internal fun registerDrawableWorldAdjustment(
	session: EditorSession,
	areaId: String?,
	geometry: DrawableWorldGeometry,
	kind: MeshOperatorKind,
	parameters: TransformGestureParameters,
): AdjustableOperation? {
	val bounds = meshBounds(geometry.world)
	val transform =
		buildModalTransformCapture(
			sources = listOf(ModalCaptureSource(geometry.owner, geometry.world, ElementTopology.unconnected(geometry.world.size / 2), geometry.allIndices)),
			pivotMode = TransformPivotMode.Cursor,
			individualOriginScope = IndividualOriginScope.WholeMesh,
			operatorKind = kind,
			activeAnchor = null,
			cursorAnchor = bounds.centerX to bounds.centerY,
		) ?: return null

	return registerObjectTransformAdjustment(session, areaId, transform, mapOf(geometry.owner to geometry), parameters)
}

/**
 * Registers a world transform of ONE warp that committed outside any gesture - the Properties panel's Position and
 * Size rows - as the session's adjustable operation, with the rows the viewport's Object-mode G / S register and the
 * same rerun, the warp moved as a whole.  Pinned to the lattice's bounds center, as [registerDrawableWorldAdjustment]
 * is, so an adjusted Size row scales about the point the panel scaled about.
 *
 * @param EditorSession              session    The session the transform committed into.
 * @param String?                    areaId     The area the strip should show in, or null for nowhere.
 * @param LatticeWorldGeometry       lattice    The warp's lattice as the commit was planned against.
 * @param MeshOperatorKind           kind       Grab for a move, Scale for a resize.
 * @param TransformGestureParameters parameters The numbers the commit landed with, in world space.
 * @return AdjustableOperation? The record, or null when the session refused the registration.
 */
internal fun registerWarpWorldAdjustment(
	session: EditorSession,
	areaId: String?,
	lattice: LatticeWorldGeometry,
	kind: MeshOperatorKind,
	parameters: TransformGestureParameters,
): AdjustableOperation? {
	val bounds = meshBounds(lattice.world)
	val pointCount = lattice.world.size / 2
	val transform =
		buildModalTransformCapture(
			sources = listOf(ModalCaptureSource(lattice.owner, lattice.world, ElementTopology.unconnected(pointCount), (0 until pointCount).toSet())),
			pivotMode = TransformPivotMode.Cursor,
			individualOriginScope = IndividualOriginScope.WholeMesh,
			operatorKind = kind,
			activeAnchor = null,
			cursorAnchor = bounds.centerX to bounds.centerY,
		) ?: return null

	return registerObjectTransformAdjustment(session, areaId, transform, emptyMap(), parameters)
}