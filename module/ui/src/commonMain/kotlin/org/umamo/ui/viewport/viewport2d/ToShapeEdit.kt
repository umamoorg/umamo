package org.umamo.ui.viewport.viewport2d

import org.umamo.edit.AdjustableOperation
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.ElementEditKind
import org.umamo.edit.NoticePlacement
import org.umamo.edit.TargetShape
import org.umamo.edit.transform.ModalCaptureSource
import org.umamo.edit.transform.ShapePiece
import org.umamo.edit.transform.ToShapeSettings
import org.umamo.edit.transform.handDepthRotationTheShape
import org.umamo.edit.transform.shapePiecesOf
import org.umamo.edit.transform.shapedOwnerPositions
import org.umamo.edit.transform.toShapeParameters
import org.umamo.edit.transform.toShapeSettingsOf
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ElementOwner
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.transform.DrawableWorldGeometry
import org.umamo.ui.transform.ElementEditStage
import org.umamo.ui.transform.LatticeWorldGeometry
import org.umamo.ui.transform.MeshDriveResult
import org.umamo.ui.transform.MeshJobOutcome
import org.umamo.ui.transform.OwnerWorldGeometry
import org.umamo.ui.transform.amendDrive
import org.umamo.ui.transform.commitDrive
import org.umamo.ui.transform.coveredVerticesOf
import org.umamo.ui.transform.foldDrive
import org.umamo.ui.transform.outcomeOf
import org.umamo.ui.viewport.gizmo.activeElementMedian

/*
 * To Shape as a Pose-mode lattice edit: the selected points captured in world space at the pose, bent round a cylinder
 * or a sphere (the :edit math), and landed the way a snap lands - each owner's outcome folded by the drive's own fold,
 * the lattices held pending at the pose.  The step registers on the operation strip with its Shape, Axis, and Factor
 * rows, whose reruns rebend the same frozen capture, so either command can become the other after the fact.  Each run
 * hands the depth rotation the radius and surface it laid down, so the next R about the same pivot turns that shape.
 */

/**
 * The selected points captured for a To Shape: each covered owner's frozen world geometry, the points it covers, the
 * pieces they bend in, and where they were captured.  The strip's reruns replay it, so it never reads the live model.
 *
 * @property Map<ElementOwner, OwnerWorldGeometry> geometryByOwner Each covered owner's frozen geometry, in capture order.
 * @property Map<ElementOwner, Set<Int>> coveredByOwner Each covered owner's covered points.
 * @property List<ShapePiece> pieces The pieces of the selection, each bent about its own pivot.
 * @property ShapePiece depthPiece The piece whose radius the depth rotation takes: the active owner's, else the first.
 * @property ElementEditStage stage Where the geometry was captured, and so how a moved lattice lands.
 */
internal class ToShapeCapture(
	val geometryByOwner: Map<ElementOwner, OwnerWorldGeometry>,
	val coveredByOwner: Map<ElementOwner, Set<Int>>,
	val pieces: List<ShapePiece>,
	val depthPiece: ShapePiece,
	val stage: ElementEditStage,
) {
	/** Each covered mesh's covered vertices, which a mesh step names. */
	val movedIndicesByDrawable: Map<DrawableId, List<Int>>
		get() =
			geometryByOwner.values
				.filterIsInstance<DrawableWorldGeometry>()
				.associate { geometry -> geometry.drawableId to coveredByOwner[geometry.owner].orEmpty().toList() }

	/**
	 * Whether [settings] would move any point at all: a selection with no reach, or a factor of 0, bends nothing.
	 *
	 * @param ToShapeSettings settings The shape to wrap onto.
	 * @return Boolean True when some point lands somewhere new.
	 */
	fun moves(settings: ToShapeSettings): Boolean =
		geometryByOwner.any { (owner, geometry) -> !shapedOwnerPositions(owner, geometry.world, pieces, settings).contentEquals(geometry.world) }

	/**
	 * Bends the capture onto [settings]' shape and folds it onto [baseModel]: each mesh's new rest shape, and each
	 * lattice's points with their pending forms at the pose.
	 *
	 * @param ToShapeSettings settings The shape to wrap onto.
	 * @param PuppetModel baseModel The model the moved rest shapes fold onto.
	 * @return MeshDriveResult The drive, landed by [commitDrive] or [amendDrive].
	 */
	fun drive(settings: ToShapeSettings, baseModel: PuppetModel): MeshDriveResult {
		val outcomes = ArrayList<MeshJobOutcome>(geometryByOwner.size)
		val latticeMoved = LinkedHashMap<DeformerId, Set<Int>>()

		for ((owner, geometry) in geometryByOwner) {
			val covered = coveredByOwner[owner].orEmpty()
			outcomes.add(geometry.outcomeOf(shapedOwnerPositions(owner, geometry.world, pieces, settings), covered))

			when (geometry) {
				is DrawableWorldGeometry -> {}

				is LatticeWorldGeometry -> {
					latticeMoved[geometry.deformerId] = covered
				}
			}
		}

		return foldDrive(baseModel, outcomes, latticeMoved, stage)
	}
}

/**
 * Captures the points the session's selection covers across [geometries] for a To Shape, wrapped in pieces by the
 * session's pivot mode as a Rotate would turn them.
 *
 * @param EditorSession session The session: its selection, pivot mode, and 2D cursor.
 * @param List<EditOwnerGeometry> geometries Every owner in the edit, as captured from the committed model.
 * @param ElementEditStage stage Where they were captured.
 * @return ToShapeCapture? The capture, or null when nothing is covered.
 */
internal fun toShapeCaptureOf(
	session: EditorSession,
	geometries: List<EditOwnerGeometry>,
	stage: ElementEditStage,
): ToShapeCapture? {
	val selection = session.meshSelection.value
	// The covered set Shift+S and the sidebar's Vertex / Median panel read too, so the three cannot disagree.
	val covered = coveredVerticesOf(selection, geometries.map { geometry -> geometry.worldGeometry }, geometries.associate { geometry -> geometry.owner to geometry.topology })

	if (covered.isEmpty()) {
		return null
	}

	val geometryByOwner = LinkedHashMap<ElementOwner, OwnerWorldGeometry>(covered.size)
	val sources =
		covered.map { owner ->
			val frozen = owner.geometry.frozen()
			geometryByOwner[frozen.owner] = frozen
			ModalCaptureSource(frozen.owner, frozen.world, owner.topology, owner.indices)
		}
	val activeAnchor = activeElementMedian(selection, geometries.map { geometry -> geometry.gizmo })
	val cursor = session.cursor2dOrWorldOrigin()
	val pieces = shapePiecesOf(sources, session.pivotMode.value, activeAnchor, cursor.worldX to cursor.worldZ)

	if (pieces.isEmpty()) {
		return null
	}

	val activeOwner = selection.activeElement?.owner ?: selection.activeOwner
	val depthPiece = pieces.firstOrNull { piece -> activeOwner in piece.members } ?: pieces.first()

	return ToShapeCapture(geometryByOwner, sources.associate { source -> source.owner to source.coveredIndices }, pieces, depthPiece, stage)
}

/**
 * Runs a To Shape over the selected points as one undo step, registered on the operation strip in [areaId] with its
 * Shape, Axis, and Factor rows, and hands the depth rotation the shape's radius and surface.  Pose mode only for now,
 * the one gate: a selection that covers nothing or would not move posts a near-cursor notice and records nothing.
 *
 * @param EditorSession session The session owning the selection and the commits.
 * @param List<EditOwnerGeometry> geometries The session owners' live geometry.
 * @param ElementEditStage stage Where [geometries] were captured.
 * @param TargetShape shape The surface the command named.
 * @param String areaId The viewport that executes, where the strip shows.
 * @return AdjustableOperation? The registered record, or null when nothing was recorded.
 */
internal fun handleToShapeRequest(
	session: EditorSession,
	geometries: List<EditOwnerGeometry>,
	stage: ElementEditStage,
	shape: TargetShape,
	areaId: String,
): AdjustableOperation? {
	if (session.mode.value != EditorMode.Pose || session.activeMeshOperator.value != null) {
		return null
	}

	val settings = ToShapeSettings(shape)
	val capture = toShapeCaptureOf(session, geometries, stage)?.takeIf { captured -> captured.moves(settings) }

	if (capture == null) {
		session.emitNotice("notice.toShape.nothing", NoticePlacement.NearCursor)

		return null
	}

	if (!session.commitDrive(capture.drive(settings, session.model.value), capture.stage, ElementEditKind.ToShape, capture.movedIndicesByDrawable)) {
		return null
	}

	session.handDepthRotationTheShape(capture.depthPiece, settings)

	return registerToShapeAdjustment(session, areaId, capture, settings)
}

/**
 * Registers the To Shape that just committed as the session's adjustable operation: its rows start at [settings],
 * and each adjustment rebends the RETAINED [capture] over the record's base, landing over the same step and handing the
 * depth rotation the adjusted shape.
 *
 * @param EditorSession session The session the To Shape committed into.
 * @param String areaId The viewport it ran in (where the strip shows).
 * @param ToShapeCapture capture The frozen capture.
 * @param ToShapeSettings settings The shape it landed with.
 * @return AdjustableOperation? The record, or null when the session refused the registration.
 */
internal fun registerToShapeAdjustment(
	session: EditorSession,
	areaId: String,
	capture: ToShapeCapture,
	settings: ToShapeSettings,
): AdjustableOperation? =
	session.registerAdjustableOperation(session.model.value, areaId, toShapeParameters(settings)) { record ->
		val adjusted = toShapeSettingsOf(record.parameters)

		if (session.amendDrive(record, capture.drive(adjusted, record.baseSnapshot.model), capture.stage)) {
			session.handDepthRotationTheShape(capture.depthPiece, adjusted)
		}
	}