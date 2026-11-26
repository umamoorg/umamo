package org.umamo.ui.transform

import org.umamo.edit.AdjustableOperation
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.ElementEditKind
import org.umamo.edit.ElementTopology
import org.umamo.edit.NoticePlacement
import org.umamo.edit.TargetShape
import org.umamo.edit.transform.DepthRotationSetup
import org.umamo.edit.transform.ModalCaptureSource
import org.umamo.edit.transform.PointsInDepth
import org.umamo.edit.transform.ShapePiece
import org.umamo.edit.transform.ToShapeSettings
import org.umamo.edit.transform.depthRotationSetup
import org.umamo.edit.transform.handDepthRotationTheShape
import org.umamo.edit.transform.shapePiecesOf
import org.umamo.edit.transform.shapedOwnerPoints
import org.umamo.edit.transform.shapedOwnerPositions
import org.umamo.edit.transform.toShapeParameters
import org.umamo.edit.transform.toShapeSettingsOf
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ElementOwner
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.latticeDepthOf
import org.umamo.runtime.model.valuesFor

/*
 * To Shape as a Pose-mode lattice edit: the selected points captured in world space at the pose, bent round a cylinder
 * or a sphere (the :edit math), and landed the way a snap lands - each owner's outcome folded by the drive's own fold,
 * the lattices held pending at the pose with the depth the bend gave them.  The step registers on the operation strip
 * with its Shape, Axis, Degrees, and Factor rows, whose reruns rebend the same frozen capture, so either command can
 * become the other after the fact.  Each run hands the depth rotation the depth of the shape's center and the Unchanged
 * surface, so the next R about the same pivot turns that shape.
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
	 * Whether [settings] would move any point at all, in the plane or in depth: a flat selection with no reach, or a
	 * factor of 0, bends nothing.
	 *
	 * @param ToShapeSettings settings The shape to wrap onto.
	 * @return Boolean True when some point lands somewhere new.
	 */
	fun moves(settings: ToShapeSettings): Boolean =
		geometryByOwner.any { (owner, geometry) ->
			when (geometry) {
				is DrawableWorldGeometry -> {
					!shapedOwnerPositions(owner, geometry.world, pieces, settings).contentEquals(geometry.world)
				}

				is LatticeWorldGeometry -> {
					val original = pointsOf(geometry)
					val shaped = shapedOwnerPoints(owner, original, pieces, settings)
					!shaped.positions.contentEquals(original.positions) || !shaped.depths.contentEquals(original.depths)
				}
			}
		}

	/**
	 * A lattice's captured points with their depths, one per point.
	 *
	 * @param LatticeWorldGeometry geometry The lattice.
	 * @return PointsInDepth The points.
	 */
	private fun pointsOf(geometry: LatticeWorldGeometry): PointsInDepth = PointsInDepth(geometry.world, geometry.depth.valuesFor(geometry.world.size / 2))

	/**
	 * One owner bent onto [settings]' shape: a mesh's new rest shape, or a lattice's points with their new depths.
	 *
	 * @param ElementOwner owner The owner.
	 * @param OwnerWorldGeometry geometry Its frozen geometry.
	 * @param ToShapeSettings settings The shape to wrap onto.
	 * @return MeshJobOutcome The outcome.
	 */
	private fun shapedOutcome(
		owner: ElementOwner,
		geometry: OwnerWorldGeometry,
		settings: ToShapeSettings,
	): MeshJobOutcome {
		val covered = coveredByOwner[owner].orEmpty()

		return when (geometry) {
			is DrawableWorldGeometry -> {
				geometry.outcomeOf(shapedOwnerPositions(owner, geometry.world, pieces, settings), covered)
			}

			is LatticeWorldGeometry -> {
				val shaped = shapedOwnerPoints(owner, pointsOf(geometry), pieces, settings)
				geometry.outcomeOf(shaped.positions, covered, latticeDepthOf(shaped.depths))
			}
		}
	}

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
			outcomes.add(shapedOutcome(owner, geometry, settings))

			when (geometry) {
				is DrawableWorldGeometry -> {}

				is LatticeWorldGeometry -> {
					latticeMoved[geometry.deformerId] = coveredByOwner[owner].orEmpty()
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
 * @param List<OwnerWorldGeometry> geometries Every owner in the edit, as captured from the committed model.
 * @param Map<ElementOwner, ElementTopology> topologyByOwner Each owner's vertices, edges, and faces.
 * @param Pair<Float, Float>? activeAnchor The active element's own median, world (x, z), or null with nothing active.
 * @param ElementEditStage stage Where they were captured.
 * @return ToShapeCapture? The capture, or null when nothing is covered.
 */
internal fun toShapeCaptureOf(
	session: EditorSession,
	geometries: List<OwnerWorldGeometry>,
	topologyByOwner: Map<ElementOwner, ElementTopology>,
	activeAnchor: Pair<Float, Float>?,
	stage: ElementEditStage,
): ToShapeCapture? {
	val selection = session.meshSelection.value
	// The covered set Shift+S and the sidebar's Vertex / Median panel read too, so the three cannot disagree.
	val covered = coveredVerticesOf(selection, geometries, topologyByOwner)

	if (covered.isEmpty()) {
		return null
	}

	val geometryByOwner = LinkedHashMap<ElementOwner, OwnerWorldGeometry>(covered.size)
	val sources =
		covered.map { coveredOwner ->
			val frozen = coveredOwner.geometry.frozen()
			geometryByOwner[frozen.owner] = frozen
			ModalCaptureSource(frozen.owner, frozen.world, coveredOwner.topology, coveredOwner.indices)
		}
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
 * Shape, Axis, Degrees, and Factor rows, and hands the depth rotation the depth of the shape's center over the
 * Unchanged surface.  It runs in Pose mode alone, never while a modal operator holds the session; a selection that
 * covers nothing or would not move posts a near-cursor notice and records nothing.
 *
 * @param EditorSession session The session owning the selection and the commits.
 * @param List<OwnerWorldGeometry> geometries The session owners' live world geometry.
 * @param Map<ElementOwner, ElementTopology> topologyByOwner Each owner's vertices, edges, and faces.
 * @param Pair<Float, Float>? activeAnchor The active element's own median, world (x, z), or null with nothing active.
 * @param ElementEditStage stage Where [geometries] were captured.
 * @param TargetShape shape The surface the command named.
 * @param String areaId The viewport that executes, where the strip shows.
 * @return AdjustableOperation? The registered record, or null when nothing was recorded.
 */
internal fun runToShape(
	session: EditorSession,
	geometries: List<OwnerWorldGeometry>,
	topologyByOwner: Map<ElementOwner, ElementTopology>,
	activeAnchor: Pair<Float, Float>?,
	stage: ElementEditStage,
	shape: TargetShape,
	areaId: String,
): AdjustableOperation? {
	if (session.mode.value != EditorMode.Pose || session.activeMeshOperator.value != null) {
		return null
	}

	val settings = ToShapeSettings(shape)
	val capture = toShapeCaptureOf(session, geometries, topologyByOwner, activeAnchor, stage)?.takeIf { captured -> captured.moves(settings) }

	if (capture == null) {
		session.emitNotice("notice.toShape.nothing", NoticePlacement.NearCursor)

		return null
	}

	val depthRotationBefore = session.depthRotationSetup

	if (!session.commitDrive(capture.drive(settings, session.model.value), capture.stage, ElementEditKind.ToShape, capture.movedIndicesByDrawable)) {
		return null
	}

	session.handDepthRotationTheShape(capture.depthPiece, settings, depthRotationBefore)

	return registerToShapeAdjustment(session, areaId, capture, settings, depthRotationBefore)
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
 * @param DepthRotationSetup depthRotationBefore The depth rotation's setup before the To Shape ran, which an adjustment
 *   that bends nothing puts back.
 * @return AdjustableOperation? The record, or null when the session refused the registration.
 */
internal fun registerToShapeAdjustment(
	session: EditorSession,
	areaId: String,
	capture: ToShapeCapture,
	settings: ToShapeSettings,
	depthRotationBefore: DepthRotationSetup,
): AdjustableOperation? =
	session.registerAdjustableOperation(session.model.value, areaId, toShapeParameters(settings)) { record ->
		val adjusted = toShapeSettingsOf(record.parameters)

		if (session.amendDrive(record, capture.drive(adjusted, record.baseSnapshot.model), capture.stage)) {
			session.handDepthRotationTheShape(capture.depthPiece, adjusted, depthRotationBefore)
		}
	}