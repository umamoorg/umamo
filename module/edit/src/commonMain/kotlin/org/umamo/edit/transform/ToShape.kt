package org.umamo.edit.transform

import org.umamo.edit.DepthSurface
import org.umamo.edit.EditorSession
import org.umamo.edit.TargetShape
import org.umamo.edit.TransformPivotMode
import org.umamo.runtime.model.ElementOwner
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * To Shape: the selected points read as a flat sheet and bent around a cylinder or a sphere, then seen from the front -
 * the surfaces a depth rotation turns (DepthRotation.kt), laid down without turning.  The bend keeps the sheet's size
 * along the surface: a point an arc length s from the pivot lands at R sin(s / R), so the middle stays at full scale and
 * the sides foreshorten toward the rim, the way a label wrapped round a barrel looks.  The radius fits each piece of the
 * selection so the sheet wraps the chosen number of degrees round the shape: at 180 its farthest point lands on the rim
 * and it covers the front half, at 360 its two ends meet at the back, and toward 0 it relaxes back to flat.
 *
 * The bend also says how deep each point lands: at angle phi = s / R round the shape, R (1 - cos phi) behind the art
 * plane - 0 at the front, R on the rim, 2R at the back - with the shape's center line or point at depth R.  A lattice
 * keeps that depth, so a depth rotation with the pivot pushed to R over the Unchanged surface turns the bent points
 * exactly as the shape would turn, the back half included: a point at phi goes to R sin(phi + theta).
 */

/** A whole turn in degrees, the most the sheet wraps round the shape and where a run starts. */
const val TO_SHAPE_FULL_TURN_DEGREES = 360f

/** Half a turn in radians, the farthest round the shape a point of the sheet can sit from its front. */
private const val HALF_TURN = PI.toFloat()

/** Radians per degree, halved: a wrap of d degrees puts the farthest point d x this round from the front. */
private const val HALF_ARC_PER_DEGREE = (PI / 360.0).toFloat()

/**
 * The axis To Shape's cylinder runs along: Z (up) bends the selection left and right, so its sides foreshorten toward the
 * side edges, and X bends it top and bottom.  The view axis Y is never offered, since a cylinder pointing at the viewer
 * has nothing to bend.  The Axis row keys an axis by its [choiceKey].
 */
enum class CylinderAxis {
	X,
	Z,
}

/** The stable key of a cylinder axis: its name in lower case (x, z), the one spelling the Axis row stores. */
val CylinderAxis.choiceKey: String
	get() = name.lowercase()

/** The stable key of a target shape: its name in lower camel case (cylinder, sphere), the one spelling the Shape row stores. */
val TargetShape.choiceKey: String
	get() = name.replaceFirstChar { first -> first.lowercaseChar() }

/**
 * The cylinder axis a stored key names, falling back to Z (the head turn left and right) for a key no axis has.
 *
 * @param String key The axis's [CylinderAxis.choiceKey].
 * @return CylinderAxis The axis.
 */
fun cylinderAxisOf(key: String): CylinderAxis = CylinderAxis.entries.firstOrNull { axis -> axis.choiceKey == key } ?: CylinderAxis.Z

/**
 * The target shape a stored key names, falling back to Cylinder for a key no shape has.
 *
 * @param String key The shape's [TargetShape.choiceKey].
 * @return TargetShape The shape.
 */
fun targetShapeOf(key: String): TargetShape = TargetShape.entries.firstOrNull { shape -> shape.choiceKey == key } ?: TargetShape.Cylinder

/**
 * What one To Shape lays down: the surface, the axis a cylinder runs along (a sphere reads none), how far the points go
 * toward the shape, from 0 (where they were) to 1 (fully on it), and how many degrees round the shape the sheet wraps.
 *
 * @property TargetShape shape The surface.
 * @property CylinderAxis axis The axis a cylinder runs along.
 * @property Float factor How far toward the shape, 0 to 1.
 * @property Float degrees How far round the shape the sheet wraps, 0 (flat) to 360 (all the way round).
 */
data class ToShapeSettings(
	val shape: TargetShape,
	val axis: CylinderAxis = CylinderAxis.Z,
	val factor: Float = 1f,
	val degrees: Float = TO_SHAPE_FULL_TURN_DEGREES,
)

/**
 * How far one piece of a selection reaches from its pivot, in world px: across each axis and straight out, the reaches a
 * cylinder about Z, a cylinder about X, and a sphere fit their radius to.
 *
 * @property Float pivotX The pivot's world x.
 * @property Float pivotZ The pivot's world z (the positions' second component).
 * @property Float reachX The farthest any point sits from the pivot along x, either side.
 * @property Float reachZ The farthest any point sits from the pivot along z, either side.
 * @property Float reachRadial The farthest any point sits from the pivot.
 */
class ShapeExtent(
	val pivotX: Float,
	val pivotZ: Float,
	val reachX: Float,
	val reachZ: Float,
	val reachRadial: Float,
)

/**
 * One piece of a selection To Shape bends as a whole: the points that share a pivot, across every owner they belong
 * to, and how far they reach from it.
 *
 * @property ShapeExtent extent The piece's pivot and reach.
 * @property Map<ElementOwner, Set<Int>> members Each owner's points in the piece.
 */
class ShapePiece(
	val extent: ShapeExtent,
	val members: Map<ElementOwner, Set<Int>>,
)

/**
 * The radius a piece bends round under [settings]: the one that puts its farthest point across the cylinder's axis, or
 * out from the pivot for a sphere, half [ToShapeSettings.degrees] round from the front, so the whole sheet wraps that
 * many degrees.  0 when nothing bends: a piece with no reach, or a wrap of 0 degrees.
 *
 * @param ShapePiece piece The piece.
 * @param ToShapeSettings settings The shape.
 * @return Float The radius in world px, or 0 for no bend.
 */
fun shapeRadiusOf(piece: ShapePiece, settings: ToShapeSettings): Float {
	val halfArc = settings.degrees.coerceIn(0f, TO_SHAPE_FULL_TURN_DEGREES) * HALF_ARC_PER_DEGREE

	if (halfArc <= 0f) {
		return 0f
	}

	val reach =
		when (settings.shape) {
			TargetShape.Cylinder -> {
				when (settings.axis) {
					CylinderAxis.Z -> piece.extent.reachX
					CylinderAxis.X -> piece.extent.reachZ
				}
			}

			TargetShape.Sphere -> {
				piece.extent.reachRadial
			}
		}

	return reach / halfArc
}

/**
 * Sets the depth rotation's depth to the radius of the shape [settings] laid down and its surface to Unchanged, so an R
 * about the same pivot turns the bent points at the depths the bend gave them, round the very cylinder or sphere they
 * sit on.  A wrap that bends nothing (0 degrees) has no radius to hand over and leaves them as they are.
 *
 * @param ShapePiece piece The piece whose radius the depth rotation takes.
 * @param ToShapeSettings settings The shape laid down.
 */
fun EditorSession.handDepthRotationTheShape(piece: ShapePiece, settings: ToShapeSettings) {
	val radius = shapeRadiusOf(piece, settings)

	if (radius <= 0f) {
		return
	}

	setPivotDepth(radius)
	setDepthSurface(DepthSurface.Unchanged)
}

/**
 * The pieces To Shape bends a selection in, by the pivot mode as a Rotate would turn it: under Individual Origins one
 * piece per connectivity island (so each selected lattice is shaped round its own center), otherwise the whole covered
 * selection as one piece about the shared anchor.
 *
 * @param List<ModalCaptureSource> sources The owners offered, their positions in world space.
 * @param TransformPivotMode pivotMode The active pivot mode.
 * @param Pair<Float, Float>? activeAnchor The Active-Element anchor the caller resolved, world (x, z), or null for the median.
 * @param Pair<Float, Float>? cursorAnchor The Cursor anchor the caller resolved, world (x, z), or null for the median.
 * @return List<ShapePiece> The pieces; empty when nothing is covered.
 */
fun shapePiecesOf(
	sources: List<ModalCaptureSource>,
	pivotMode: TransformPivotMode,
	activeAnchor: Pair<Float, Float>?,
	cursorAnchor: Pair<Float, Float>?,
): List<ShapePiece> {
	val pivots = resolvePivotGroups(sources, pivotMode, IndividualOriginScope.ConnectivityIsland, activeAnchor, cursorAnchor) ?: return emptyList()
	val positionsByOwner = sources.associate { source -> source.owner to source.positions }

	if (pivotMode == TransformPivotMode.IndividualOrigins) {
		return pivots.groupsByOwner.flatMap { (owner, groups) ->
			groups.map { group -> pieceOf(group.pivotX, group.pivotY, mapOf(owner to group.vertexIndices), positionsByOwner) }
		}
	}

	val members = pivots.groupsByOwner.mapValues { (_, groups) -> groups.flatMapTo(LinkedHashSet()) { group -> group.vertexIndices } }

	return listOf(pieceOf(pivots.anchor.first, pivots.anchor.second, members, positionsByOwner))
}

/**
 * One piece over [members] about the pivot, its reach measured on their positions.
 *
 * @param Float pivotX The pivot's world x.
 * @param Float pivotZ The pivot's world z.
 * @param Map<ElementOwner, Set<Int>> members Each owner's points in the piece.
 * @param Map<ElementOwner, FloatArray> positionsByOwner Each owner's world positions.
 * @return ShapePiece The piece.
 */
private fun pieceOf(
	pivotX: Float,
	pivotZ: Float,
	members: Map<ElementOwner, Set<Int>>,
	positionsByOwner: Map<ElementOwner, FloatArray>,
): ShapePiece {
	var reachX = 0f
	var reachZ = 0f
	var reachRadial = 0f

	for ((owner, indices) in members) {
		val positions = positionsByOwner[owner] ?: continue

		for (vertexIndex in indices) {
			val offsetX = positions[vertexIndex * 2] - pivotX
			val offsetZ = positions[vertexIndex * 2 + 1] - pivotZ
			reachX = max(reachX, abs(offsetX))
			reachZ = max(reachZ, abs(offsetZ))
			reachRadial = max(reachRadial, sqrt(offsetX * offsetX + offsetZ * offsetZ))
		}
	}

	return ShapePiece(ShapeExtent(pivotX, pivotZ, reachX, reachZ, reachRadial), members)
}

/**
 * One owner's points with every piece it belongs to bent onto the shape, fresh arrays: the shaped positions and how
 * deep each lands.
 *
 * @param ElementOwner owner The owner.
 * @param PointsInDepth points Its world positions and depths (never mutated).
 * @param List<ShapePiece> pieces The pieces of the selection.
 * @param ToShapeSettings settings The shape to bend onto.
 * @return PointsInDepth The shaped points; copies of [points] when no piece holds the owner.
 */
fun shapedOwnerPoints(
	owner: ElementOwner,
	points: PointsInDepth,
	pieces: List<ShapePiece>,
	settings: ToShapeSettings,
): PointsInDepth =
	pieces.fold(PointsInDepth(points.positions.copyOf(), points.depths.copyOf())) { shaped, piece ->
		piece.members[owner]?.let { indices -> shapeVerticesInPlace(shaped, indices, piece.extent, shapeRadiusOf(piece, settings), settings) }
		shaped
	}

/**
 * [shapedOwnerPoints] for points on the art plane, a mesh's, whose depth nothing keeps.
 *
 * @param ElementOwner owner The owner.
 * @param FloatArray positions Its interleaved world positions (never mutated).
 * @param List<ShapePiece> pieces The pieces of the selection.
 * @param ToShapeSettings settings The shape to bend onto.
 * @return FloatArray The shaped positions; a copy of [positions] when no piece holds the owner.
 */
fun shapedOwnerPositions(
	owner: ElementOwner,
	positions: FloatArray,
	pieces: List<ShapePiece>,
	settings: ToShapeSettings,
): FloatArray = shapedOwnerPoints(owner, PointsInDepth(positions, FloatArray(positions.size / 2)), pieces, settings).positions

/**
 * Bends the given vertices of [points] onto the shape in place, each moving [ToShapeSettings.factor] of the way to where
 * the bent sheet shows it and how deep it sits there.  Each vertex reads only its own point, so the pieces of a
 * selection can be bent one after another into the same arrays.  A coordinate the bend leaves where it was - the axis a
 * cylinder runs along, the pivot itself - keeps its value exactly.
 *
 * @param PointsInDepth points The world positions and depths, written in place.
 * @param Set<Int> indices The vertices to bend.
 * @param ShapeExtent extent The piece's pivot.
 * @param Float radius The radius of the cylinder or sphere; round a radius of 0 the sheet stays flat, on the art plane.
 * @param ToShapeSettings settings The shape to bend onto.
 */
private fun shapeVerticesInPlace(
	points: PointsInDepth,
	indices: Set<Int>,
	extent: ShapeExtent,
	radius: Float,
	settings: ToShapeSettings,
) {
	val positions = points.positions
	val depths = points.depths

	for (vertexIndex in indices) {
		val offsetX = positions[vertexIndex * 2] - extent.pivotX
		val offsetZ = positions[vertexIndex * 2 + 1] - extent.pivotZ
		// The flat sheet's own depth: a radius of 0 bends nothing, so the sheet lies on the art plane.
		var shapedDepth = 0f

		if (radius > 0f) {
			when (settings.shape) {
				TargetShape.Cylinder -> {
					when (settings.axis) {
						CylinderAxis.Z -> {
							val angle = angleRoundShape(offsetX, radius)
							positions[vertexIndex * 2] = landedCoordinate(positions[vertexIndex * 2], extent.pivotX, offsetX, radius * sin(angle), settings.factor)
							shapedDepth = depthRoundShape(angle, radius)
						}

						CylinderAxis.X -> {
							val angle = angleRoundShape(offsetZ, radius)
							positions[vertexIndex * 2 + 1] = landedCoordinate(positions[vertexIndex * 2 + 1], extent.pivotZ, offsetZ, radius * sin(angle), settings.factor)
							shapedDepth = depthRoundShape(angle, radius)
						}
					}
				}

				TargetShape.Sphere -> {
					// The sheet bent over the sphere along each line out from the pivot, the arc length kept on that line.
					val distance = sqrt(offsetX * offsetX + offsetZ * offsetZ)

					if (distance > 0f) {
						val angle = angleRoundShape(distance, radius)
						val stretch = radius * sin(angle) / distance
						positions[vertexIndex * 2] = landedCoordinate(positions[vertexIndex * 2], extent.pivotX, offsetX, offsetX * stretch, settings.factor)
						positions[vertexIndex * 2 + 1] = landedCoordinate(positions[vertexIndex * 2 + 1], extent.pivotZ, offsetZ, offsetZ * stretch, settings.factor)
						shapedDepth = depthRoundShape(angle, radius)
					}
				}
			}
		}

		depths[vertexIndex] += (shapedDepth - depths[vertexIndex]) * settings.factor
	}
}

/**
 * How far round the shape a point an arc length [arcLength] from the pivot sits once the sheet bends round [radius]: 0
 * at the front, a quarter turn on the rim, half a turn at the back.  An arc past half a turn stays at the back.
 *
 * @param Float arcLength The point's offset from the pivot on the flat sheet, signed.
 * @param Float radius The radius it bends round, above 0.
 * @return Float The angle in radians, signed like [arcLength].
 */
private fun angleRoundShape(arcLength: Float, radius: Float): Float = (arcLength / radius).coerceIn(-HALF_TURN, HALF_TURN)

/**
 * How deep a point [angle] round a shape of [radius] sits behind the art plane, which touches the shape at its front:
 * R (1 - cos phi).
 *
 * @param Float angle The angle round the shape from its front, in radians.
 * @param Float radius The shape's radius.
 * @return Float The depth along world Y, in world px.
 */
private fun depthRoundShape(angle: Float, radius: Float): Float = radius * (1f - cos(angle))

/**
 * Where one coordinate of a bent point lands: [factor] of the way from where it was to its place on the shape, or exactly
 * where it was when it sits on the pivot, which the bend never moves.
 *
 * @param Float original The coordinate before the bend.
 * @param Float pivot The pivot's coordinate on the same axis.
 * @param Float offset The coordinate's offset from the pivot before the bend.
 * @param Float bentOffset Its offset from the pivot on the shape.
 * @param Float factor How far toward the shape, 0 to 1.
 * @return Float The landed coordinate.
 */
private fun landedCoordinate(
	original: Float,
	pivot: Float,
	offset: Float,
	bentOffset: Float,
	factor: Float,
): Float {
	if (offset == 0f) {
		return original
	}

	val shaped = pivot + bentOffset

	return original + (shaped - original) * factor
}