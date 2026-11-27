package org.umamo.edit.transform

import org.umamo.edit.DepthSurface
import org.umamo.edit.EditorSession
import org.umamo.edit.ElementTopology
import org.umamo.edit.PIVOT_DEPTH_LIMIT
import org.umamo.edit.SessionTestModels.meshModel
import org.umamo.edit.TargetShape
import org.umamo.edit.TransformPivotMode
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.ElementOwner
import org.umamo.runtime.model.WarpLatticeLayout
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies To Shape's bend: the sheet keeps its size along the surface (the middle at full scale, the sides
 * foreshortened), the degrees set how far round it wraps (180 puts the farthest point on the rim, 360 meets the ends at
 * the back, 0 stays flat), a cylinder about either axis leaves the other axis alone, a half sphere rounds every way
 * without folding a cell, the factor blends, an off-center pivot bends round its farther reach, a selection with no
 * reach stays put, Individual Origins bends each lattice round its own center, each bent point lands at the depth the
 * shape puts it at (blended by the factor), the session's depth rotation takes the radius scaled by the factor and the
 * Unchanged surface, a flat shape puts its setup back, a center past the Depth row's reach hands over no pivot, and a
 * depth rotation with the radius as its depth turns a half cylinder, and over Unchanged a whole one, exactly.
 */
class ToShapeTest {
	private val quarterTurn = (PI / 2.0).toFloat()
	private val sixthTurn = (PI / 6.0).toFloat()
	private val warp = ElementOwner.Deformer(DeformerId("warp"))
	private val second = ElementOwner.Deformer(DeformerId("second"))

	/** A wrap covering the front half of the shape. */
	private val halfTurn = 180f

	/** The radius a reach of 100 bends round on a half wrap: its farthest point lands on the rim, a quarter circle of arc away. */
	private val rimRadius = (200.0 / PI).toFloat()

	/**
	 * A square lattice's points, row-major with the bottom row first, [divisions] cells a side across
	 * [halfWidth] either way of the center.
	 *
	 * @param Int divisions The cells along each side.
	 * @param Float halfWidth Half the side's length.
	 * @param Float centerX The center's x.
	 * @param Float centerZ The center's z.
	 * @return FloatArray The interleaved points.
	 */
	private fun grid(divisions: Int, halfWidth: Float, centerX: Float = 0f, centerZ: Float = 0f): FloatArray {
		val positions = FloatArray((divisions + 1) * (divisions + 1) * 2)

		for (row in 0..divisions) {
			for (column in 0..divisions) {
				val pointIndex = row * (divisions + 1) + column
				positions[pointIndex * 2] = centerX - halfWidth + 2f * halfWidth * column / divisions
				positions[pointIndex * 2 + 1] = centerZ - halfWidth + 2f * halfWidth * row / divisions
			}
		}

		return positions
	}

	/**
	 * The point index at [row] and [column] of a [divisions]-cell grid.
	 *
	 * @param Int row The row, bottom first.
	 * @param Int column The column, left first.
	 * @param Int divisions The cells along each side.
	 * @return Int The point index.
	 */
	private fun pointAt(row: Int, column: Int, divisions: Int): Int = row * (divisions + 1) + column

	/**
	 * A whole lattice offered as a source, every point covered, its topology the grid's own unless given.
	 *
	 * @param ElementOwner owner The lattice.
	 * @param FloatArray positions Its points.
	 * @param Int divisions The cells along each side.
	 * @param ElementTopology topology Its topology.
	 * @return ModalCaptureSource The source.
	 */
	private fun source(
		owner: ElementOwner,
		positions: FloatArray,
		divisions: Int,
		topology: ElementTopology = ElementTopology.ofLattice(WarpLatticeLayout(divisions, divisions)),
	): ModalCaptureSource = ModalCaptureSource(owner, positions, topology, allPointIndices(positions))

	/**
	 * One lattice shaped about its median.
	 *
	 * @param FloatArray positions Its points.
	 * @param Int divisions The cells along each side.
	 * @param ToShapeSettings settings The shape.
	 * @param ElementTopology topology Its topology.
	 * @return FloatArray The shaped points.
	 */
	private fun shaped(
		positions: FloatArray,
		divisions: Int,
		settings: ToShapeSettings,
		topology: ElementTopology = ElementTopology.ofLattice(WarpLatticeLayout(divisions, divisions)),
	): FloatArray {
		val pieces = shapePiecesOf(listOf(source(warp, positions, divisions, topology)), TransformPivotMode.MedianPoint, null, null)

		return shapedOwnerPositions(warp, positions, pieces, settings)
	}

	/**
	 * One lattice shaped about its median with its depths.
	 *
	 * @param FloatArray positions Its points.
	 * @param FloatArray depths Its depths, one per point.
	 * @param Int divisions The cells along each side.
	 * @param ToShapeSettings settings The shape.
	 * @return PointsInDepth The shaped points and depths.
	 */
	private fun shapedInDepth(
		positions: FloatArray,
		depths: FloatArray,
		divisions: Int,
		settings: ToShapeSettings,
	): PointsInDepth {
		val pieces = shapePiecesOf(listOf(source(warp, positions, divisions)), TransformPivotMode.MedianPoint, null, null)

		return shapedOwnerPoints(warp, PointsInDepth(positions, depths), pieces, settings)
	}

	/** About Z every column lands at R sin(s / R) and no point moves vertically; the middle column stays exactly. */
	@Test
	fun aCylinderAboutZBendsTheColumnsKeepingTheirArcLength() {
		val positions = grid(4, 100f)
		val bent = shaped(positions, 4, ToShapeSettings(TargetShape.Cylinder, CylinderAxis.Z, degrees = halfTurn))

		for (row in 0..4) {
			for (column in 0..4) {
				val pointIndex = pointAt(row, column, 4)
				val arcLength = -100f + 50f * column
				assertEquals(rimRadius * sin(arcLength / rimRadius), bent[pointIndex * 2], 1e-3f, "column $column")
				assertEquals(positions[pointIndex * 2 + 1], bent[pointIndex * 2 + 1], 0f, "rows never move")
			}
		}

		assertEquals(rimRadius, bent[pointAt(0, 4, 4) * 2], 1e-3f, "the farthest column lands on the rim")
		assertEquals(0f, bent[pointAt(0, 2, 4) * 2], 0f, "the column on the pivot stays exactly")
		assertEquals(grid(4, 100f).toList(), positions.toList(), "the input array is never mutated")
	}

	/** Next to the pivot the bent sheet keeps its scale: a point 1 px out stays 1 px out, while the edges come in. */
	@Test
	fun theMiddleKeepsItsScale() {
		val row = floatArrayOf(-100f, 0f, -1f, 0f, 0f, 0f, 1f, 0f, 100f, 0f)
		val bent = shaped(row, 0, ToShapeSettings(TargetShape.Cylinder, CylinderAxis.Z, degrees = halfTurn), ElementTopology.unconnected(5))

		assertEquals(-1f, bent[1 * 2], 1e-3f)
		assertEquals(0f, bent[2 * 2], 0f, "the pivot stays exactly")
		assertEquals(1f, bent[3 * 2], 1e-3f)
		assertEquals(rimRadius, bent[4 * 2], 1e-3f)
		assertTrue(rimRadius < 100f, "the outline narrows to 2 / pi of the sheet")
	}

	/** About X the rows bend toward the top and bottom, and no point moves sideways. */
	@Test
	fun aCylinderAboutXBendsTheRows() {
		val positions = grid(4, 100f)
		val bent = shaped(positions, 4, ToShapeSettings(TargetShape.Cylinder, CylinderAxis.X, degrees = halfTurn))

		for (row in 0..4) {
			for (column in 0..4) {
				val pointIndex = pointAt(row, column, 4)
				val arcLength = -100f + 50f * row
				assertEquals(rimRadius * sin(arcLength / rimRadius), bent[pointIndex * 2 + 1], 1e-3f, "row $row")
				assertEquals(positions[pointIndex * 2], bent[pointIndex * 2], 0f, "columns never move")
			}
		}
	}

	/** A sphere bends every line out of the pivot: the corners land on the rim, the edge midpoints inside it. */
	@Test
	fun aSphereBendsOutOfThePivotEveryWay() {
		val positions = grid(4, 100f)
		val bent = shaped(positions, 4, ToShapeSettings(TargetShape.Sphere, degrees = halfTurn))
		val sphereRadius = rimRadius * sqrt(2f)
		val cornerOffset = sphereRadius * sqrt(0.5f)

		for ((row, column) in listOf(0 to 0, 0 to 4, 4 to 0, 4 to 4)) {
			val pointIndex = pointAt(row, column, 4)
			assertEquals(if (column == 0) -cornerOffset else cornerOffset, bent[pointIndex * 2], 1e-3f, "corner $row, $column x")
			assertEquals(if (row == 0) -cornerOffset else cornerOffset, bent[pointIndex * 2 + 1], 1e-3f, "corner $row, $column z")
		}

		assertEquals(sphereRadius * sin(100f / sphereRadius), bent[pointAt(2, 4, 4) * 2], 1e-3f, "an edge midpoint keeps its arc")
		assertEquals(0f, bent[pointAt(2, 4, 4) * 2 + 1], 0f)
		assertEquals(0f, bent[pointAt(2, 2, 4) * 2], 0f, "the center stays exactly")

		for (pointIndex in 0 until bent.size / 2) {
			val distance = sqrt(bent[pointIndex * 2] * bent[pointIndex * 2] + bent[pointIndex * 2 + 1] * bent[pointIndex * 2 + 1])
			assertTrue(distance <= sphereRadius + 1e-3f, "point $pointIndex stays inside the rim: $distance")
		}
	}

	/** A fine sphere keeps every row ordered left to right and every column bottom to top, so no cell folds. */
	@Test
	fun aSphereNeverFoldsALattice() {
		val divisions = 8
		val bent = shaped(grid(divisions, 100f), divisions, ToShapeSettings(TargetShape.Sphere, degrees = halfTurn))

		for (line in 0..divisions) {
			for (step in 1..divisions) {
				assertTrue(bent[pointAt(line, step, divisions) * 2] > bent[pointAt(line, step - 1, divisions) * 2], "row $line at column $step")
				assertTrue(bent[pointAt(step, line, divisions) * 2 + 1] > bent[pointAt(step - 1, line, divisions) * 2 + 1], "column $line at row $step")
			}
		}
	}

	/** A factor of 0 leaves every point exactly, and a factor of one half lands half way to the shape. */
	@Test
	fun theFactorBlendsTowardTheShape() {
		val positions = grid(4, 100f)
		val full = shaped(positions, 4, ToShapeSettings(TargetShape.Sphere, degrees = halfTurn))
		val half = shaped(positions, 4, ToShapeSettings(TargetShape.Sphere, factor = 0.5f, degrees = halfTurn))

		assertEquals(positions.toList(), shaped(positions, 4, ToShapeSettings(TargetShape.Sphere, factor = 0f, degrees = halfTurn)).toList())

		for (index in positions.indices) {
			assertEquals((positions[index] + full[index]) / 2f, half[index], 1e-3f, "component $index")
		}
	}

	/** A cursor pivot off center bends round the farther reach, so the far edge lands on the rim and the near one inside. */
	@Test
	fun anOffCenterPivotBendsRoundItsFartherReach() {
		val positions = grid(4, 100f)
		val pieces = shapePiecesOf(listOf(source(warp, positions, 4)), TransformPivotMode.Cursor, null, -50f to 0f)
		val cylinder = ToShapeSettings(TargetShape.Cylinder, CylinderAxis.Z, degrees = halfTurn)
		val bent = shapedOwnerPositions(warp, positions, pieces, cylinder)
		val radius = shapeRadiusOf(pieces.single(), cylinder)

		assertEquals((300.0 / PI).toFloat(), radius, 1e-3f)

		for (column in 0..4) {
			val arcLength = -50f + 50f * column
			assertEquals(-50f + radius * sin(arcLength / radius), bent[pointAt(0, column, 4) * 2], 1e-3f, "column $column")
		}

		assertEquals(-50f + radius, bent[pointAt(0, 4, 4) * 2], 1e-3f, "the far edge lands on the rim")
	}

	/** One point about its own median, or a column with nothing across the cylinder's axis, has no reach and stays exactly. */
	@Test
	fun aSelectionWithNoReachStaysPut() {
		val point = floatArrayOf(12f, -7f)
		val pointSource = ModalCaptureSource(warp, point, ElementTopology.unconnected(1), setOf(0))
		val pointPieces = shapePiecesOf(listOf(pointSource), TransformPivotMode.MedianPoint, null, null)
		val column = floatArrayOf(5f, 0f, 5f, 10f, 5f, 20f)
		val columnSource = ModalCaptureSource(warp, column, ElementTopology.unconnected(3), setOf(0, 1, 2))
		val columnPieces = shapePiecesOf(listOf(columnSource), TransformPivotMode.MedianPoint, null, null)

		assertEquals(point.toList(), shapedOwnerPositions(warp, point, pointPieces, ToShapeSettings(TargetShape.Sphere, degrees = halfTurn)).toList())
		assertEquals(column.toList(), shapedOwnerPositions(warp, column, columnPieces, ToShapeSettings(TargetShape.Cylinder, CylinderAxis.Z, degrees = halfTurn)).toList())
	}

	/** Individual Origins bends each selected lattice round its own center; Median Point bends both as one piece. */
	@Test
	fun individualOriginsShapesEachLatticeRoundItsOwnCenter() {
		val left = grid(4, 100f, centerX = -300f)
		val right = grid(4, 100f, centerX = 300f)
		val sources = listOf(source(warp, left, 4), source(second, right, 4))
		val cylinder = ToShapeSettings(TargetShape.Cylinder, CylinderAxis.Z, degrees = halfTurn)
		val apart = shapePiecesOf(sources, TransformPivotMode.IndividualOrigins, null, null)
		val together = shapePiecesOf(sources, TransformPivotMode.MedianPoint, null, null)
		val leftApart = shapedOwnerPositions(warp, left, apart, cylinder)
		val leftTogether = shapedOwnerPositions(warp, left, together, cylinder)
		val togetherRadius = (800.0 / PI).toFloat()

		assertEquals(2, apart.size)
		assertEquals(1, together.size)
		assertEquals(-300f + rimRadius, leftApart[pointAt(0, 4, 4) * 2], 1e-3f, "its own right edge lands on its own rim")
		assertEquals(-300f + rimRadius * sin(50f / rimRadius), leftApart[pointAt(0, 3, 4) * 2], 1e-3f)
		assertEquals(togetherRadius * sin(-200f / togetherRadius), leftTogether[pointAt(0, 4, 4) * 2], 1e-3f, "one piece bends round the shared median")
	}

	/** The radius fits the reach across the cylinder's axis, or out from the pivot for a sphere. */
	@Test
	fun theRadiusFitsTheShape() {
		val positions = grid(4, 100f)
		val piece = shapePiecesOf(listOf(source(warp, positions, 4)), TransformPivotMode.MedianPoint, null, null).single()

		assertEquals(rimRadius, shapeRadiusOf(piece, ToShapeSettings(TargetShape.Cylinder, CylinderAxis.Z, degrees = halfTurn)), 1e-3f)
		assertEquals(rimRadius, shapeRadiusOf(piece, ToShapeSettings(TargetShape.Cylinder, CylinderAxis.X, degrees = halfTurn)), 1e-3f)
		assertEquals(rimRadius * sqrt(2f), shapeRadiusOf(piece, ToShapeSettings(TargetShape.Sphere, degrees = halfTurn)), 1e-3f)
	}

	/**
	 * The session's depth rotation takes the radius laid down, scaled by the factor, and the Unchanged surface; a shape
	 * with nothing to hand over puts back the setup the To Shape found.
	 */
	@Test
	fun theDepthRotationTakesTheShapeLaidDown() {
		val piece = shapePiecesOf(listOf(source(warp, grid(4, 100f), 4)), TransformPivotMode.MedianPoint, null, null).single()
		val session = EditorSession(meshModel())
		val before = DepthRotationSetup(12f, DepthSurface.Flat)

		session.handDepthRotationTheShape(piece, ToShapeSettings(TargetShape.Sphere, degrees = halfTurn), before)

		assertEquals(rimRadius * sqrt(2f), session.pivotDepth.value, 1e-3f)
		assertEquals(DepthSurface.Unchanged, session.depthSurface.value, "the turn reads the depths the bend gave")

		session.handDepthRotationTheShape(piece, ToShapeSettings(TargetShape.Cylinder, factor = 0.5f, degrees = halfTurn), before)

		assertEquals(rimRadius * 0.5f, session.pivotDepth.value, 1e-3f, "half the factor takes the center half as deep")

		for (flat in listOf(ToShapeSettings(TargetShape.Cylinder, degrees = 0f), ToShapeSettings(TargetShape.Cylinder, factor = 0f))) {
			session.handDepthRotationTheShape(piece, ToShapeSettings(TargetShape.Sphere, degrees = halfTurn), before)
			session.handDepthRotationTheShape(piece, flat, before)

			assertEquals(before, session.depthRotationSetup, "$flat bends nothing, so the setup goes back")
		}
	}

	/**
	 * A wrap so slight its center sits past the Depth row's reach hands over no pivot rather than a clamped one, and a
	 * factor that brings the center within reach hands it over.
	 */
	@Test
	fun aCenterPastTheDepthLimitHandsOverNothing() {
		val piece = shapePiecesOf(listOf(source(warp, grid(4, 100f), 4)), TransformPivotMode.MedianPoint, null, null).single()
		val slight = ToShapeSettings(TargetShape.Cylinder, degrees = 0.1f)

		assertTrue(shapeRadiusOf(piece, slight) > PIVOT_DEPTH_LIMIT, "the radius passes the limit")
		assertNull(handedPivotDepthOf(piece, slight))
		val withinReach = slight.copy(factor = PIVOT_DEPTH_LIMIT * 0.5f / shapeRadiusOf(piece, slight))
		assertEquals(PIVOT_DEPTH_LIMIT * 0.5f, assertNotNull(handedPivotDepthOf(piece, withinReach)), 1f, "a factor that brings the center within reach hands it over")
	}

	/** A whole turn bends round half the radius: the farthest columns meet behind the pivot, the halfway ones sit on the rim. */
	@Test
	fun aWholeTurnMeetsAtTheBack() {
		val positions = grid(4, 100f)
		val bent = shaped(positions, 4, ToShapeSettings(TargetShape.Cylinder, CylinderAxis.Z))
		val wholeTurnRadius = (100.0 / PI).toFloat()

		assertEquals(360f, ToShapeSettings(TargetShape.Cylinder).degrees, "a run starts all the way round")
		assertEquals(0f, bent[pointAt(2, 0, 4) * 2], 1e-3f, "the left end comes round behind the pivot")
		assertEquals(0f, bent[pointAt(2, 4, 4) * 2], 1e-3f, "and so does the right end")
		assertEquals(-wholeTurnRadius, bent[pointAt(2, 1, 4) * 2], 1e-3f, "a quarter of the sheet out is on the rim")
		assertEquals(wholeTurnRadius, bent[pointAt(2, 3, 4) * 2], 1e-3f)
		assertEquals(0f, bent[pointAt(2, 2, 4) * 2], 0f, "the column on the pivot stays exactly")
	}

	/** The degrees set the radius between flat and all the way round: 90 bends gently, and 0 leaves every point exactly. */
	@Test
	fun theDegreesSetHowFarRoundTheSheetWraps() {
		val positions = grid(4, 100f)
		val piece = shapePiecesOf(listOf(source(warp, positions, 4)), TransformPivotMode.MedianPoint, null, null).single()
		val quarterRadius = (400.0 / PI).toFloat()
		val quarterTurnWrap = shaped(positions, 4, ToShapeSettings(TargetShape.Cylinder, CylinderAxis.Z, degrees = 90f))

		assertEquals(quarterRadius, shapeRadiusOf(piece, ToShapeSettings(TargetShape.Cylinder, CylinderAxis.Z, degrees = 90f)), 1e-3f)
		assertEquals(quarterRadius * sin(100f / quarterRadius), quarterTurnWrap[pointAt(2, 4, 4) * 2], 1e-3f)
		assertEquals(0f, shapeRadiusOf(piece, ToShapeSettings(TargetShape.Cylinder, CylinderAxis.Z, degrees = 0f)), "no degrees, no bend")
		assertEquals(positions.toList(), shaped(positions, 4, ToShapeSettings(TargetShape.Cylinder, CylinderAxis.Z, degrees = 0f)).toList())
	}

	/** A depth rotation about the same pivot, the radius as its depth, turns each column of a half cylinder by the angle exactly. */
	@Test
	fun aDepthRotationTurnsTheBentCylinderExactly() {
		val bent = shaped(grid(4, 100f), 4, ToShapeSettings(TargetShape.Cylinder, CylinderAxis.Z, degrees = halfTurn))
		val turned = MeshTransforms.rotateVerticesInDepth(bent, allPointIndices(bent), RotationAxis.Z, sixthTurn, rimRadius, DepthSurface.Cylinder, 0f, 0f)

		for (column in 0..4) {
			val columnAngle = (-1f + column / 2f) * quarterTurn
			assertEquals(rimRadius * sin(columnAngle + sixthTurn), turned[pointAt(2, column, 4) * 2], 1e-2f, "column $column")
		}
	}

	/** A whole turn's depths: R (1 - cos phi) round the cylinder, 0 at the front, R on the rim, 2R at the back; rows alike. */
	@Test
	fun aWholeTurnSaysHowDeepEachColumnLands() {
		val bent = shapedInDepth(grid(4, 100f), FloatArray(25), 4, ToShapeSettings(TargetShape.Cylinder, CylinderAxis.Z))
		val wholeTurnRadius = (100.0 / PI).toFloat()

		for (row in 0..4) {
			assertEquals(0f, bent.depths[pointAt(row, 2, 4)], 1e-4f, "the front column touches the art plane")
			assertEquals(wholeTurnRadius, bent.depths[pointAt(row, 1, 4)], 1e-3f, "the rim sits at the center's depth")
			assertEquals(wholeTurnRadius, bent.depths[pointAt(row, 3, 4)], 1e-3f)
			assertEquals(2f * wholeTurnRadius, bent.depths[pointAt(row, 0, 4)], 1e-3f, "the ends meet at the back")
			assertEquals(2f * wholeTurnRadius, bent.depths[pointAt(row, 4, 4)], 1e-3f)
		}
	}

	/** A sphere's depth grows with the distance from the pivot, the same every way round. */
	@Test
	fun aSphereSaysHowDeepEachPointLandsByItsDistance() {
		val bent = shapedInDepth(grid(4, 100f), FloatArray(25), 4, ToShapeSettings(TargetShape.Sphere, degrees = halfTurn))
		val radius = rimRadius * sqrt(2f)
		val sideDepth = radius * (1f - cos(100f / radius))

		assertEquals(0f, bent.depths[pointAt(2, 2, 4)], 0f, "the pivot touches the art plane")
		assertEquals(sideDepth, bent.depths[pointAt(2, 4, 4)], 1e-3f)
		assertEquals(sideDepth, bent.depths[pointAt(4, 2, 4)], 1e-3f, "a point above sits as deep as one beside")
		assertEquals(radius, bent.depths[pointAt(4, 4, 4)], 1e-3f, "the corners, farthest out, land on the rim")
	}

	/** The factor blends each depth from where it was; a point outside the selection keeps its own, and 0 degrees lays the sheet flat. */
	@Test
	fun theFactorBlendsTheDepthFromWhereItWas() {
		val positions = grid(4, 100f)
		val depths = FloatArray(25) { 40f }
		val wholeTurnRadius = (100.0 / PI).toFloat()
		val half = shapedInDepth(positions, depths, 4, ToShapeSettings(TargetShape.Cylinder, CylinderAxis.Z, factor = 0.5f))
		val pieces = listOf(ShapePiece(ShapeExtent(0f, 0f, 100f, 100f, 100f * sqrt(2f)), mapOf(warp to setOf(pointAt(2, 0, 4)))))
		val one = shapedOwnerPoints(warp, PointsInDepth(positions, depths), pieces, ToShapeSettings(TargetShape.Cylinder, CylinderAxis.Z))
		val flattened = shapedInDepth(positions, depths, 4, ToShapeSettings(TargetShape.Cylinder, CylinderAxis.Z, degrees = 0f))

		assertEquals(20f, half.depths[pointAt(2, 2, 4)], 1e-4f, "halfway from 40 to the front's 0")
		assertEquals(20f + wholeTurnRadius, half.depths[pointAt(2, 0, 4)], 1e-3f, "halfway from 40 to the back's 2R")
		assertEquals(40f, one.depths[pointAt(2, 1, 4)], 0f, "a point outside the selection keeps its depth")
		assertEquals(positions.toList(), flattened.positions.toList(), "0 degrees bends nothing")
		assertTrue(flattened.depths.all { depth -> depth == 0f }, "and lays the sheet on the art plane")
	}

	/**
	 * The primary case: a whole-turn cylinder turned about its center over Unchanged puts every column, the back half
	 * included, at R sin(phi + theta) and R (1 - cos(phi + theta)) deep, and two turns are one turn by their sum.
	 */
	@Test
	fun aWholeTurnCylinderTurnsRoundItsCenterOverUnchanged() {
		val bent = shapedInDepth(grid(4, 100f), FloatArray(25), 4, ToShapeSettings(TargetShape.Cylinder, CylinderAxis.Z))
		val radius = (100.0 / PI).toFloat()
		val every = allPointIndices(bent.positions)
		val turned = MeshTransforms.rotateVerticesInDepth(bent, every, RotationAxis.Z, sixthTurn, radius, DepthSurface.Unchanged, 0f, 0f)
		val twice =
			MeshTransforms.rotateVerticesInDepth(
				MeshTransforms.rotateVerticesInDepth(bent, every, RotationAxis.Z, sixthTurn, radius, DepthSurface.Unchanged, 0f, 0f),
				every,
				RotationAxis.Z,
				quarterTurn,
				radius,
				DepthSurface.Unchanged,
				0f,
				0f,
			)
		val once = MeshTransforms.rotateVerticesInDepth(bent, every, RotationAxis.Z, sixthTurn + quarterTurn, radius, DepthSurface.Unchanged, 0f, 0f)

		for (column in 0..4) {
			val columnAngle = (-2f + column) * quarterTurn
			val pointIndex = pointAt(2, column, 4)
			assertEquals(radius * sin(columnAngle + sixthTurn), turned.positions[pointIndex * 2], 1e-3f, "column $column")
			assertEquals(radius * (1f - cos(columnAngle + sixthTurn)), turned.depths[pointIndex], 1e-3f, "column $column's depth")
			assertEquals(once.positions[pointIndex * 2], twice.positions[pointIndex * 2], 1e-3f, "two turns are one by the sum")
			assertEquals(once.depths[pointIndex], twice.depths[pointIndex], 1e-3f)
		}
	}
}