package org.umamo.interop.art.mesh

import org.umamo.format.art.LayerRaster
import org.umamo.geometry.mesh.PlanarTriangleMesh
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The art mesher on synthetic shapes, every mesh checked by the independent oracle in
 * ArtMeshTestSupport (orientation, manifold edges, used and distinct vertices, exact pins, every art
 * pixel covered, every boundary edge clear of the art by the minimum margin, and with holes filled
 * one disc per outline ring).
 */
class ArtMesherTest {
	private val standard = ArtMeshPreset.Standard.settings

	@Test
	fun aDiscGetsAnOutlineAnInnerRingAndALattice() {
		// Big enough that the interior deeper than the inner margin plus half a spacing (32 px) holds
		// lattice points.
		val raster = rasterOf(160, 160) { column, row -> inDisc(column, row, 80.0, 80.0, 70.0) }
		val result = generateArtMesh(raster, standard)
		assertValidArtMesh(raster, standard, result)
		val statistics = result.statistics!!
		assertEquals(1, statistics.outlineRingCount)
		assertEquals(1, statistics.innerRingCount)
		assertTrue(statistics.latticeVertexCount > 0)
		assertTrue(result.notices.isEmpty(), "${result.notices}")
	}

	@Test
	fun anAnnulusKeepsItsHole() {
		val raster = rasterOf(100, 100) { column, row -> inDisc(column, row, 50.0, 50.0, 40.0) && !inDisc(column, row, 50.0, 50.0, 16.0) }
		val result = generateArtMesh(raster, standard)
		val mesh = assertValidArtMesh(raster, standard, result)
		assertEquals(2, result.statistics!!.outlineRingCount)
		assertFalse(meshCovers(mesh, 50.0, 50.0), "the hole's center stays open")
	}

	@Test
	fun aFilledAnnulusIsOneSheetWithPointsAcrossItsHole() {
		val raster = rasterOf(200, 200) { column, row -> inDisc(column, row, 100.0, 100.0, 90.0) && !inDisc(column, row, 100.0, 100.0, 40.0) }
		val inHole = { mesh: PlanarTriangleMesh ->
			(0 until mesh.vertexCount).any { vertex -> distanceFromCenter(mesh, vertex, 100.0, 100.0) < 30.0 }
		}
		val kept = assertValidArtMesh(raster, standard, generateArtMesh(raster, standard))
		val filledSettings = standard.copy(fillHoles = true)
		val filledResult = generateArtMesh(raster, filledSettings)
		val filled = assertValidArtMesh(raster, filledSettings, filledResult)
		assertEquals(1, filledResult.statistics!!.outlineRingCount)
		assertFalse(inHole(kept), "with the hole kept no vertex sits in it")
		assertTrue(inHole(filled), "with the hole filled the lattice runs across it")
		assertTrue(meshCovers(filled, 100.0, 100.0))
	}

	@Test
	fun aNetTooHoleyForTheBudgetMeshesWhenFilled() {
		// 2 px strands every 24 px: 361 holes, each a ring of at least six vertices when kept.
		val raster = rasterOf(470, 470) { column, row -> column <= 457 && row <= 457 && (column % 24 < 2 || row % 24 < 2) }
		val keptResult = generateArtMesh(raster, standard)
		assertNull(keptResult.mesh)
		assertTrue(keptResult.notices.single() is ArtMeshNotice.OverBudget, "${keptResult.notices}")
		val filledSettings = standard.copy(fillHoles = true)
		val filledResult = generateArtMesh(raster, filledSettings)
		val mesh = assertValidArtMesh(raster, filledSettings, filledResult)
		val statistics = filledResult.statistics!!
		assertEquals(1, statistics.outlineRingCount)
		assertTrue(statistics.latticeVertexCount > 0)
		assertTrue(meshCovers(mesh, 228.5, 228.5), "a hole's center is covered")
	}

	@Test
	fun anIslandInsideAFilledHoleNeedsNoRingOfItsOwn() {
		val raster =
			rasterOf(120, 120) { column, row ->
				val inAnnulus = inDisc(column, row, 60.0, 60.0, 50.0) && !inDisc(column, row, 60.0, 60.0, 30.0)
				inAnnulus || inDisc(column, row, 60.0, 60.0, 6.0)
			}
		assertEquals(3, generateArtMesh(raster, standard).statistics!!.outlineRingCount)
		val filledSettings = standard.copy(fillHoles = true)
		val filledResult = generateArtMesh(raster, filledSettings)
		assertValidArtMesh(raster, filledSettings, filledResult)
		assertEquals(1, filledResult.statistics!!.outlineRingCount)
	}

	@Test
	fun islandsCloserThanTwiceTheOutlineLevelMerge() {
		// Standard puts the outline 6 px out, so islands under 12 px apart share one outline.
		val near = rasterOf(80, 40) { column, row -> inDisc(column, row, 20.0, 20.0, 10.0) || inDisc(column, row, 50.0, 20.0, 10.0) }
		val far = rasterOf(110, 40) { column, row -> inDisc(column, row, 20.0, 20.0, 10.0) || inDisc(column, row, 80.0, 20.0, 10.0) }
		val nearResult = generateArtMesh(near, standard)
		val farResult = generateArtMesh(far, standard)
		assertValidArtMesh(near, standard, nearResult)
		assertValidArtMesh(far, standard, farResult)
		assertEquals(1, nearResult.statistics!!.outlineRingCount)
		assertEquals(2, farResult.statistics!!.outlineRingCount)
	}

	@Test
	fun aOnePixelStrandIsMeshedFromItsOutline() {
		val raster = rasterOf(90, 9) { column, row -> row == 4 && column in 5..84 }
		val result = generateArtMesh(raster, standard)
		assertValidArtMesh(raster, standard, result)
		val statistics = result.statistics!!
		assertEquals(0, statistics.innerRingCount)
		assertEquals(0, statistics.latticeVertexCount)
	}

	@Test
	fun eachSpeckGetsAnOutlineOfTheMinimumSize() {
		val specks = listOf(10 to 10, 50 to 10, 90 to 10, 10 to 50, 90 to 50)
		val raster = rasterOf(100, 60) { column, row -> (column to row) in specks }
		val result = generateArtMesh(raster, standard)
		assertValidArtMesh(raster, standard, result)
		val statistics = result.statistics!!
		assertEquals(specks.size, statistics.outlineRingCount)
		assertTrue(statistics.outlineVertexCount >= specks.size * standard.minimumOutlinePoints)
	}

	@Test
	fun nothingAtOrAboveTheThresholdGivesNoMesh() {
		val empty = rasterOf(20, 20) { _, _ -> false }
		val faint = rasterOf(20, 20, alpha = 10) { column, row -> inDisc(column, row, 10.0, 10.0, 6.0) }

		for (raster in listOf(empty, faint)) {
			val result = generateArtMesh(raster, standard)
			assertNull(result.mesh)
			assertEquals(listOf<ArtMeshNotice>(ArtMeshNotice.NothingOpaque), result.notices)
		}
	}

	@Test
	fun aFullyOpaqueRasterMeshesPastItsEdges() {
		val raster = rasterOf(40, 30) { _, _ -> true }
		val mesh = assertValidArtMesh(raster, standard, generateArtMesh(raster, standard))
		assertTrue((0 until mesh.vertexCount).any { mesh.positions[2 * it] < 0f }, "the outline runs into negative coordinates")
	}

	@Test
	fun artCutByTheRasterEdgeIsCovered() {
		val raster = rasterOf(60, 50) { column, row -> inDisc(column, row, 0.0, 25.0, 20.0) }
		assertValidArtMesh(raster, standard, generateArtMesh(raster, standard))
	}

	@Test
	fun aSpeckInTheMouthOfACIsCovered() {
		// The C's opening faces right; a lone pixel sits in its mouth, where a chord bridging the
		// opening would cut it off if the cap check did not catch it.
		val raster =
			rasterOf(120, 120) { column, row ->
				val ring = inDisc(column, row, 60.0, 60.0, 45.0) && !inDisc(column, row, 60.0, 60.0, 30.0)
				val mouth = column > 60 && abs(row + 0.5 - 60.0) < 14.0
				(ring && !mouth) || (column == 98 && row == 60)
			}
		val coarse = standard.copy(outlineSpacing = 80.0)
		assertValidArtMesh(raster, coarse, generateArtMesh(raster, coarse))
	}

	@Test
	fun anLShapeAndACombAreCovered() {
		val shapeL = rasterOf(80, 80) { column, row -> (column in 10..25 && row in 10..70) || (column in 10..70 && row in 55..70) }
		val comb = rasterOf(100, 70) { column, row -> (row in 10..20 && column in 10..90) || (row in 10..60 && (column - 10) % 16 < 5 && column in 10..90) }

		for (raster in listOf(shapeL, comb)) {
			assertValidArtMesh(raster, standard, generateArtMesh(raster, standard))
		}
	}

	@Test
	fun aLevelLandingExactlyOnAGridNodeMeshesCleanly() {
		// Cell size 2 (margins 5 and 4) and minimum margin 1: the outline level is max(5, 1 + 4) = 5,
		// exactly the value of a cell three cells off the block (3 * 2 - 1).
		val tie = standard.copy(outerMargin = 5.0, innerMargin = 4.0, minimumMargin = 1.0)
		val raster = rasterOf(40, 40) { column, row -> column in 12..27 && row in 12..27 }
		val result = generateArtMesh(raster, tie)
		assertEquals(2, result.statistics!!.cellSize)
		assertValidArtMesh(raster, tie, result)
	}

	@Test
	fun pinsBecomeVerticesAtTheirExactPositions() {
		val raster = rasterOf(100, 100) { column, row -> inDisc(column, row, 50.0, 50.0, 35.0) }
		val pins = doubleArrayOf(50.25, 50.75, 30.1, 62.3, -12.5, -8.0)
		val result = generateArtMesh(raster, standard, pins)
		val mesh = assertValidArtMesh(raster, standard, result, pins)
		assertEquals(3, mesh.pinVertices.toSet().size)
	}

	@Test
	fun coincidentPinsShareAVertex() {
		val raster = rasterOf(60, 60) { column, row -> inDisc(column, row, 30.0, 30.0, 20.0) }
		val pins = doubleArrayOf(30.5, 30.5, 30.5, 30.5)
		val result = generateArtMesh(raster, standard, pins)
		val mesh = assertValidArtMesh(raster, standard, result, pins)
		assertEquals(mesh.pinVertices[0], mesh.pinVertices[1])
		assertTrue(ArtMeshNotice.PinsShareVertex(1) in result.notices)
	}

	@Test
	fun theBudgetWidensTheSpacing() {
		val raster = rasterOf(300, 300) { column, row -> inDisc(column, row, 150.0, 150.0, 140.0) }
		// At Standard spacing this disc needs about 64 vertices.
		val tight = standard.copy(vertexBudget = 40)
		val result = generateArtMesh(raster, tight)
		val mesh = assertValidArtMesh(raster, tight, result)
		assertTrue(mesh.vertexCount <= 40)
		assertTrue(result.notices.any { it is ArtMeshNotice.SpacingCoarsened }, "${result.notices}")
	}

	@Test
	fun moreSpecksThanTheBudgetCanRingAreOverBudget() {
		val raster = rasterOf(200, 200) { column, row -> column % 20 == 10 && row % 20 == 10 }
		val tight = standard.copy(vertexBudget = 100)
		val result = generateArtMesh(raster, tight)
		assertNull(result.mesh)
		assertTrue(result.notices.single() is ArtMeshNotice.OverBudget)
	}

	@Test
	fun randomArtIsAlwaysCovered() {
		val random = Random(149)

		repeat(12) {
			val width = random.nextInt(30, 120)
			val height = random.nextInt(30, 120)
			val discs = List(random.nextInt(1, 6)) { doubleArrayOf(random.nextDouble(0.0, width.toDouble()), random.nextDouble(0.0, height.toDouble()), random.nextDouble(2.0, 25.0)) }
			val holes = List(random.nextInt(0, 3)) { doubleArrayOf(random.nextDouble(0.0, width.toDouble()), random.nextDouble(0.0, height.toDouble()), random.nextDouble(2.0, 10.0)) }
			val raster =
				rasterOf(width, height) { column, row ->
					discs.any { inDisc(column, row, it[0], it[1], it[2]) } && holes.none { inDisc(column, row, it[0], it[1], it[2]) }
				}

			if (artPixels(raster, standard.alphaThreshold).isEmpty()) {
				return@repeat
			}

			for (preset in ArtMeshPreset.entries) {
				for (fillHoles in listOf(false, true)) {
					val settings = preset.settings.copy(fillHoles = fillHoles)
					assertValidArtMesh(raster, settings, generateArtMesh(raster, settings))
				}
			}
		}
	}

	@Test
	fun theOutputIsAFunctionOfItsInput() {
		val raster = noisyBlob()
		val first = generateArtMesh(raster, standard).mesh!!
		val second = generateArtMesh(raster, standard).mesh!!
		assertContentEquals(first.positions, second.positions)
		assertContentEquals(first.triangles, second.triangles)
	}

	@Test
	fun malformedSettingsAndPinsAreRejected() {
		assertFailsWith<IllegalArgumentException> { standard.copy(minimumMargin = 0.4) }
		assertFailsWith<IllegalArgumentException> { standard.copy(alphaThreshold = 0) }
		assertFailsWith<IllegalArgumentException> { standard.copy(minimumOutlinePoints = 2) }
		assertFailsWith<IllegalArgumentException> { standard.copy(outlineSpacing = 0.0) }
		assertFailsWith<IllegalArgumentException> { generateArtMesh(noisyBlob(), standard, doubleArrayOf(1.0)) }
		assertFailsWith<IllegalArgumentException> { generateArtMesh(noisyBlob(), standard, doubleArrayOf(Double.NaN, 1.0)) }
	}

	/**
	 * How far a mesh vertex lies from a point.
	 *
	 * @param PlanarTriangleMesh mesh    The mesh.
	 * @param Int                vertex  The vertex.
	 * @param Double             centerX The point's x.
	 * @param Double             centerY The point's y.
	 * @return Double The distance.
	 */
	private fun distanceFromCenter(mesh: PlanarTriangleMesh, vertex: Int, centerX: Double, centerY: Double): Double {
		val deltaX = mesh.positions[2 * vertex] - centerX
		val deltaY = mesh.positions[2 * vertex + 1] - centerY

		return sqrt(deltaX * deltaX + deltaY * deltaY)
	}

	/**
	 * A blob with ragged edges, a hole, and a few specks.
	 *
	 * @return LayerRaster The raster.
	 */
	private fun noisyBlob(): LayerRaster {
		val random = Random(151)
		val noise = BooleanArray(80 * 80) { random.nextInt(9) == 0 }

		return rasterOf(80, 80) { column, row ->
			val body = inDisc(column, row, 40.0, 40.0, 28.0) && !inDisc(column, row, 46.0, 36.0, 6.0)
			val ragged = inDisc(column, row, 40.0, 40.0, 31.0) && noise[row * 80 + column]
			body || ragged
		}
	}
}