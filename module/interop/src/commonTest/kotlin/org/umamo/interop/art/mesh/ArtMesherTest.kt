package org.umamo.interop.art.mesh

import org.umamo.format.art.LayerRaster
import org.umamo.format.art.alphaField
import org.umamo.geometry.mesh.PlanarTriangleMesh
import org.umamo.geometry.mesh.measureQuality
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
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
 * one disc per outline ring).  The presets refine toward their minimum angle, so every test below
 * that meshes at a preset checks refinement's meshes too.
 */
class ArtMesherTest {
	private val standard = ArtMeshPreset.Standard.settings

	@Test
	fun aDiscGetsAnOutlineAnInnerRingAndALattice() {
		// Big enough that the interior deeper than the inner margin plus half a spacing (32 px) holds
		// lattice points.
		val raster = rasterOf(160, 160) { column, row -> inDisc(column, row, 80.0, 80.0, 70.0) }
		val (result, _) = generateValidated(raster, standard)
		val statistics = result.statistics!!
		assertEquals(1, statistics.outlineRingCount)
		assertEquals(1, statistics.innerRingCount)
		assertTrue(statistics.latticeVertexCount > 0)
		assertTrue(result.notices.isEmpty(), "${result.notices}")
	}

	@Test
	fun anAnnulusKeepsItsHole() {
		val raster = rasterOf(100, 100) { column, row -> inDisc(column, row, 50.0, 50.0, 40.0) && !inDisc(column, row, 50.0, 50.0, 16.0) }
		val (result, mesh) = generateValidated(raster, standard)
		assertEquals(2, result.statistics!!.outlineRingCount)
		assertFalse(meshCovers(mesh, 50.0, 50.0), "the hole's center stays open")
	}

	@Test
	fun aPocketTooSmallForTheOutlineSpacingIsMeshedOver() {
		// Standard's outline runs 6 px out, so a 10 px pocket's ring is about 8 px across: under 32 px around.
		val raster = rasterOf(100, 100) { column, row -> inDisc(column, row, 50.0, 50.0, 40.0) && !inDisc(column, row, 50.0, 50.0, 10.0) }
		val (result, mesh) = generateValidated(raster, standard)
		val statistics = result.statistics!!
		assertEquals(1, statistics.outlineRingCount)
		assertEquals(1, statistics.meshedOverRingCount, "the pocket traced a ring of its own")
		assertTrue(meshCovers(mesh, 50.0, 50.0), "the pocket is meshed over")
		assertEquals(1, eulerCharacteristic(mesh), "the mesh is one disc")
	}

	@Test
	fun theOutlineSpacingDecidesWhetherAPocketIsCutOut() {
		// A 12 px pocket's ring runs about 6 px from its center, so it is about 38 px around.
		val raster = rasterOf(100, 100) { column, row -> inDisc(column, row, 50.0, 50.0, 40.0) && !inDisc(column, row, 50.0, 50.0, 12.0) }
		val (keptResult, kept) = generateValidated(raster, standard.copy(outlineSpacing = 24.0))
		val (meshedOverResult, meshedOver) = generateValidated(raster, standard.copy(outlineSpacing = 64.0))
		assertEquals(2, keptResult.statistics!!.outlineRingCount)
		assertFalse(meshCovers(kept, 50.0, 50.0), "under a finer outline the pocket is a hole")
		assertEquals(1, meshedOverResult.statistics!!.outlineRingCount)
		assertTrue(meshCovers(meshedOver, 50.0, 50.0), "under a coarser outline it is meshed over")
	}

	@Test
	fun artInsideAPocketMeshedOverIsCovered() {
		// The pocket's ring (about 88 px around) and the speck's own ring inside it both go.
		val raster =
			rasterOf(100, 100) { column, row ->
				val inAnnulus = inDisc(column, row, 50.0, 50.0, 40.0) && !inDisc(column, row, 50.0, 50.0, 20.0)
				inAnnulus || inDisc(column, row, 50.0, 50.0, 2.0)
			}
		assertEquals(3, generateArtMesh(raster, standard).statistics!!.outlineRingCount)
		val (result, mesh) = generateValidated(raster, standard.copy(outlineSpacing = 120.0))
		val statistics = result.statistics!!
		assertEquals(1, statistics.outlineRingCount)
		assertEquals(2, statistics.meshedOverRingCount)
		assertEquals(1, eulerCharacteristic(mesh), "the mesh is one disc")
	}

	@Test
	fun onlySmallHolesAreLeftOutAndEachTakesWhatItEncloses() {
		val outer = squareRing(0.0, 0.0, 100.0, hole = false)
		val smallHole = squareRing(10.0, 10.0, 4.0, hole = true)
		val islandInSmallHole = squareRing(11.5, 11.5, 1.0, hole = false)
		val largeHole = squareRing(40.0, 40.0, 40.0, hole = true)
		val islandInLargeHole = squareRing(55.0, 55.0, 10.0, hole = false)
		val tinyIsland = squareRing(200.0, 200.0, 2.0, hole = false)
		val rings = listOf(outer, smallHole, islandInSmallHole, largeHole, islandInLargeHole, tinyIsland)
		val kept = withoutSmallHoles(rings, spacing = 20.0)
		assertEquals(listOf(outer, largeHole, islandInLargeHole, tinyIsland), kept, "a hole under the spacing goes with what it encloses; islands and larger holes stay")
		assertEquals(rings, withoutSmallHoles(rings, spacing = 16.0), "a hole exactly one spacing around stays")
	}

	@Test
	fun aFilledAnnulusIsOneSheetWithPointsAcrossItsHole() {
		val raster = rasterOf(200, 200) { column, row -> inDisc(column, row, 100.0, 100.0, 90.0) && !inDisc(column, row, 100.0, 100.0, 40.0) }
		val inHole = { mesh: PlanarTriangleMesh ->
			(0 until mesh.vertexCount).any { vertex -> distanceFromCenter(mesh, vertex, 100.0, 100.0) < 30.0 }
		}
		val kept = generateValidated(raster, standard).mesh
		val filledSettings = standard.copy(fillHoles = true)
		val (filledResult, filled) = generateValidated(raster, filledSettings)
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
		val (filledResult, mesh) = generateValidated(raster, filledSettings)
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
		val (filledResult, _) = generateValidated(raster, filledSettings)
		assertEquals(1, filledResult.statistics!!.outlineRingCount)
	}

	@Test
	fun islandsCloserThanTwiceTheOutlineLevelMerge() {
		// Standard puts the outline 6 px out, so islands under 12 px apart share one outline.
		val near = rasterOf(80, 40) { column, row -> inDisc(column, row, 20.0, 20.0, 10.0) || inDisc(column, row, 50.0, 20.0, 10.0) }
		val far = rasterOf(110, 40) { column, row -> inDisc(column, row, 20.0, 20.0, 10.0) || inDisc(column, row, 80.0, 20.0, 10.0) }
		val (nearResult, _) = generateValidated(near, standard)
		val (farResult, _) = generateValidated(far, standard)
		assertEquals(1, nearResult.statistics!!.outlineRingCount)
		assertEquals(2, farResult.statistics!!.outlineRingCount)
	}

	@Test
	fun aOnePixelStrandIsMeshedAsRungs() {
		val raster = rasterOf(90, 9) { column, row -> row == 4 && column in 5..84 }
		val (result, mesh) = generateValidated(raster, standard)
		val statistics = result.statistics!!

		assertEquals(1, statistics.strandCount)
		assertEquals(0, statistics.innerRingCount + statistics.latticeVertexCount + statistics.strandCenterCount)
		assertPairedAcross(mesh, 4.5, 8.0..82.0)
	}

	@Test
	fun aStrandTooSmallForTheMinimumOutlinePointsKeepsItsRungs() {
		// The one-pixel strand's ring comes to fewer samples than a raised minimum: the gaps outside the
		// rungs are halved to meet it, and the rungs stay paired, since the strand was laid out on them.
		val raster = rasterOf(90, 9) { column, row -> row == 4 && column in 5..84 }
		val settings = standard.copy(minimumOutlinePoints = 16)
		val (result, mesh) = generateValidated(raster, settings)
		val statistics = result.statistics!!

		assertEquals(1, statistics.strandCount)
		assertTrue(statistics.rungCount >= 2, "${statistics.rungCount}")
		assertTrue(statistics.outlineVertexCount >= 16, "${statistics.outlineVertexCount}")
		assertPairedAcross(mesh, 4.5, 8.0..82.0)
	}

	@Test
	fun aNarrowStrandPairsItsOutlineAcrossItWithNothingBetween() {
		// 10 px tall: narrower than twice the inner margin, so each rung is two vertices across.
		val raster = rasterOf(200, 30) { column, row -> row in 10 until 20 && column in 10 until 190 }
		val (result, mesh) = generateValidated(raster, standard)
		val statistics = result.statistics!!

		assertEquals(1, statistics.strandCount)
		assertTrue(statistics.rungCount >= 5, "${statistics.rungCount}")
		assertEquals(0, statistics.strandCenterCount + statistics.innerVertexCount + statistics.latticeVertexCount)
		assertPairedAcross(mesh, 15.0, 30.0..170.0)
	}

	@Test
	fun withStrandRungsOffAStrandIsMeshedFromItsOutline() {
		val raster = rasterOf(200, 50) { column, row -> row in 13 until 37 && column in 10 until 190 }
		val settings = standard.copy(strandRungs = false)
		val (result, mesh) = generateValidated(raster, settings)
		val statistics = result.statistics!!

		assertEquals(0, statistics.strandCount + statistics.rungCount + statistics.strandCenterCount)
		assertTrue(verticesWhere(mesh) { x, y -> abs(y - 25.0) < 1.0 && x in 30.0..170.0 }.isEmpty(), "no centerline")
	}

	@Test
	fun aWiderStrandWidthMeshesAnInnerRingsStrapAsRungs() {
		// 34 px tall: past Fine's 26 px strand width, so at the preset an inner ring runs through it.
		val raster = rasterOf(300, 80) { column, row -> row in 23 until 57 && column in 10 until 290 }
		val fine = ArtMeshPreset.Fine.settings
		val widened = fine.copy(strandWidth = 40.0)
		val (presetResult, _) = generateValidated(raster, fine)
		val (widenedResult, mesh) = generateValidated(raster, widened)
		val statistics = widenedResult.statistics!!
		val innerRingRows = verticesWhere(mesh) { x, y -> x in 50.0..250.0 && (abs(y - 29.0) < 2.0 || abs(y - 51.0) < 2.0) }

		assertEquals(0, presetResult.statistics!!.strandCount)
		assertTrue(presetResult.statistics.innerRingCount >= 1)
		assertEquals(1, statistics.strandCount)
		assertTrue(statistics.strandCenterCount >= 3, "${statistics.strandCenterCount}")
		assertTrue(innerRingRows.isEmpty(), "the rungs take the inner ring's place: $innerRingRows")
		assertPairedAcross(mesh, 40.0, 50.0..250.0)
	}

	@Test
	fun aStrandWideEnoughGetsACenterlineVertexAtEveryRung() {
		// 24 px tall: past twice the inner margin, still short of an inner ring's band.
		val raster = rasterOf(200, 50) { column, row -> row in 13 until 37 && column in 10 until 190 }
		val (result, mesh) = generateValidated(raster, standard)
		val statistics = result.statistics!!
		val centers = verticesWhere(mesh) { x, y -> abs(y - 25.0) < 1.0 && x in 30.0..170.0 }

		assertEquals(1, statistics.strandCount)
		// The rung at each end crosses the outline's turn around the tip, narrower than the art itself.
		assertEquals(statistics.rungCount - 2, statistics.strandCenterCount, "a centerline vertex on every rung across the full width")
		assertEquals(0, statistics.innerVertexCount + statistics.latticeVertexCount)
		assertPairedAcross(mesh, 25.0, 30.0..170.0)
		assertTrue(centers.size >= 3, "$centers")

		for ((centerX, _) in centers) {
			assertTrue(verticesWhere(mesh) { x, y -> y < 13.0 && abs(x - centerX) <= 3.0 }.isNotEmpty(), "the centerline vertex at x $centerX sits on a rung")
		}
	}

	@Test
	fun aStrandWideningBrieflyNearItsEndStaysOneStrip() {
		// 30 px wide, flaring to 46 px over its last 100 px: past the 40 px band only for its last 40 px
		// or so, too short for an inner ring there.
		val raster = flaredBar(flareStart = 150, endWidth = 46.0)
		val (result, mesh) = generateValidated(raster, standard)
		val statistics = result.statistics!!

		assertEquals(1, statistics.strandCount)
		assertEquals(0, statistics.innerVertexCount + statistics.latticeVertexCount)
		assertPairedAcross(mesh, 39.5, 30.0..190.0)
		assertTrue(verticesWhere(mesh) { x, y -> x in 225.0..240.0 && abs(y - 39.5) < 2.0 }.isNotEmpty(), "the strip carries on past the band, its centerline included")
	}

	@Test
	fun aLongWideStretchSplitsAStrandInTwo() {
		// 24 px wide with a 46 px bulge 120 px long in the middle - past the 40 px band but measured, and
		// long enough for an inner ring of its own - so the bulge is no strand and the narrow parts on
		// either side are two.
		val raster =
			rasterOf(320, 80) { column, row ->
				val bulge = (minOf(column - 90, 230 - column) / 20.0).coerceIn(0.0, 1.0)
				column in 10 until 310 && abs(row + 0.5 - 40.0) <= 12.0 + 11.0 * bulge
			}
		val (result, mesh) = generateValidated(raster, standard)
		val statistics = result.statistics!!

		assertEquals(2, statistics.strandCount)
		assertTrue(verticesWhere(mesh) { x, y -> x in 130.0..190.0 && abs(y - 39.5) < 2.0 }.isEmpty(), "no centerline through the bulge")
	}

	@Test
	fun aThinTailLeavesTheBodyAsARungStripWithNoInnerRingInIt() {
		// A disc with a 20 px tail: the disc's inner ring reaches into the tail, and is cut back out of it.
		val raster = rasterOf(340, 180) { column, row -> inDisc(column, row, 90.0, 90.0, 70.0) || (row in 80 until 100 && column in 150 until 330) }
		val (result, mesh) = generateValidated(raster, standard)
		val statistics = result.statistics!!

		assertTrue(statistics.strandCount >= 1)
		assertTrue(statistics.innerVertexCount > 0 && statistics.latticeVertexCount > 0, "the body keeps its interior")
		assertPairedAcross(mesh, 89.5, 200.0..310.0)

		for ((x, y) in verticesWhere(mesh) { x, _ -> x in 185.0..320.0 }) {
			assertTrue(y < 80.0 || y > 99.0 || abs(y - 89.5) <= 1.0, "($x, $y) in the tail is on its outline or its centerline")
		}
	}

	@Test
	fun aCurvedStrandsRungsFaceEachOtherAcrossIt() {
		val raster = curvedBand()
		val (result, mesh) = generateValidated(raster, standard)
		val outer = verticesWhere(mesh) { x, y -> radiusOf(x, y) > 107.0 }.map { (x, y) -> angleOf(x, y) }.filter { angle -> angle in 0.4..2.2 }
		val inner = verticesWhere(mesh) { x, y -> radiusOf(x, y) < 93.0 }.map { (x, y) -> angleOf(x, y) }

		assertTrue(result.statistics!!.strandCount >= 1)
		assertTrue(outer.size >= 4, "$outer")

		for (angle in outer) {
			assertTrue(inner.any { other -> abs(other - angle) < 0.05 }, "the outer vertex at $angle radians has one facing it inside")
		}
	}

	@Test
	fun aPinOnAStrandKeepsTheCenterlineAway() {
		val raster = rasterOf(200, 50) { column, row -> row in 13 until 37 && column in 10 until 190 }
		val pins = doubleArrayOf(100.25, 25.0)
		val (result, mesh) = generateValidated(raster, standard, pins)
		val nearPin = verticesWhere(mesh) { x, y -> abs(y - 25.0) < 1.5 && abs(x - 100.25) < standard.outlineSpacing / 2.0 }

		assertEquals(listOf(100.25 to 25.0), nearPin, "only the pin itself near the pin")
	}

	@Test
	fun eachSpeckGetsAnOutlineOfTheMinimumSize() {
		val specks = listOf(10 to 10, 50 to 10, 90 to 10, 10 to 50, 90 to 50)
		val raster = rasterOf(100, 60) { column, row -> (column to row) in specks }
		val (result, _) = generateValidated(raster, standard)
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
		val mesh = generateValidated(raster, standard).mesh
		assertTrue((0 until mesh.vertexCount).any { mesh.positions[2 * it] < 0f }, "the outline runs into negative coordinates")
	}

	@Test
	fun artCutByTheRasterEdgeIsCovered() {
		val raster = rasterOf(60, 50) { column, row -> inDisc(column, row, 0.0, 25.0, 20.0) }
		generateValidated(raster, standard).mesh
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
		generateValidated(raster, coarse).mesh
	}

	@Test
	fun anLShapeAndACombAreCovered() {
		val shapeL = rasterOf(80, 80) { column, row -> (column in 10..25 && row in 10..70) || (column in 10..70 && row in 55..70) }
		val comb = rasterOf(100, 70) { column, row -> (row in 10..20 && column in 10..90) || (row in 10..60 && (column - 10) % 16 < 5 && column in 10..90) }

		for (raster in listOf(shapeL, comb)) {
			generateValidated(raster, standard).mesh
		}
	}

	@Test
	fun aLevelLandingExactlyOnAGridNodeMeshesCleanly() {
		// Cell size 2 (margins 5 and 4) and minimum margin 1: the outline level is max(5, 1 + 4) = 5,
		// exactly the value of a cell three cells off the block (3 * 2 - 1).
		val tie = standard.copy(outerMargin = 5.0, innerMargin = 4.0, minimumMargin = 1.0)
		val raster = rasterOf(40, 40) { column, row -> column in 12..27 && row in 12..27 }
		val (result, _) = generateValidated(raster, tie)
		assertEquals(2, result.statistics!!.cellSize)
	}

	@Test
	fun pinsBecomeVerticesAtTheirExactPositions() {
		val raster = rasterOf(100, 100) { column, row -> inDisc(column, row, 50.0, 50.0, 35.0) }
		val pins = doubleArrayOf(50.25, 50.75, 30.1, 62.3, -12.5, -8.0)
		val (result, mesh) = generateValidated(raster, standard, pins)
		assertEquals(3, mesh.pinVertices.toSet().size)
	}

	@Test
	fun aPinOffTheArtJoinsItThroughItsSurroundings() {
		// A disc of art and a pin 20 px past the raster's right edge: alone the pin is meshed around on its
		// own; with the old triangle that joined it to the art counted as art, it is one piece with the rest.
		val raster = rasterOf(80, 96) { column, row -> inDisc(column, row, 40.0, 48.0, 16.0) }
		val pins = doubleArrayOf(100.0, 48.0)
		val alone = generateValidated(raster, standard, pins).mesh
		assertEquals(2, pieceCount(alone), "a lone pin off the art is an island")
		val surroundings = doubleArrayOf(100.0, 48.0, 50.0, 40.0, 50.0, 56.0)
		val joined = generateValidated(raster, standard, pins, surroundings).mesh
		assertEquals(1, pieceCount(joined), "the pin's surroundings join it to the art")
	}

	@Test
	fun coincidentPinsShareAVertex() {
		val raster = rasterOf(60, 60) { column, row -> inDisc(column, row, 30.0, 30.0, 20.0) }
		val pins = doubleArrayOf(30.5, 30.5, 30.5, 30.5)
		val (result, mesh) = generateValidated(raster, standard, pins)
		assertEquals(mesh.pinVertices[0], mesh.pinVertices[1])
		assertTrue(ArtMeshNotice.PinsShareVertex(1) in result.notices)
	}

	@Test
	fun theBudgetWidensTheSpacing() {
		val raster = rasterOf(300, 300) { column, row -> inDisc(column, row, 150.0, 150.0, 140.0) }
		// At Standard spacing this disc needs about 64 vertices.
		val tight = standard.copy(vertexBudget = 40)
		val (result, mesh) = generateValidated(raster, tight)
		assertTrue(mesh.vertexCount <= 40)
		assertTrue(result.notices.any { it is ArtMeshNotice.SpacingCoarsened }, "${result.notices}")
	}

	@Test
	fun theBudgetThinsTheInteriorBeforeTheOutline() {
		val raster = largeDisc()
		val placed = unconstrainedPlacedCountOf(raster, standard)
		val outline = placed.first
		// Room, past the reserve, for a third of the interior: thinning it alone fits, within its limit.
		val budget = (outline + (placed.second - outline) / 3) * 10 / 7
		val settings = standard.copy(vertexBudget = budget)
		val (result, _) = generateValidated(raster, settings)
		val statistics = result.statistics!!

		assertEquals(standard.outlineSpacing, statistics.outlineSpacing, "the outline keeps the spacing asked for")
		assertTrue(statistics.interiorSpacing!! > standard.interiorSpacing, "the interior thinned")
		assertTrue(statistics.interiorSpacing <= standard.interiorThinningLimit * standard.outlineSpacing, "within its limit")
		assertTrue(result.notices.contains(ArtMeshNotice.SpacingCoarsened(statistics.outlineSpacing, statistics.interiorSpacing)), "${result.notices}")
		assertTrue(placedCountOf(result).second <= budget - reserveOf(budget), "a coarsened mesh leaves refinement its share")
	}

	@Test
	fun pastItsLimitTheInteriorWidensWithTheOutline() {
		// The outline alone overruns the budget, so thinning the interior alone cannot fit.
		val raster = largeDisc()
		val outline = unconstrainedPlacedCountOf(raster, standard).first
		val settings = standard.copy(vertexBudget = outline - 4)
		val (result, _) = generateValidated(raster, settings)
		val statistics = result.statistics!!

		assertTrue(statistics.outlineSpacing > standard.outlineSpacing, "the outline widened")
		assertEquals(standard.interiorThinningLimit * statistics.outlineSpacing, statistics.interiorSpacing!!, 1e-9, "with the interior at its limit")
		assertTrue(result.notices.contains(ArtMeshNotice.SpacingCoarsened(statistics.outlineSpacing, statistics.interiorSpacing)), "${result.notices}")
	}

	@Test
	fun aHighLimitKeepsTheOutlineUntilTheInteriorIsGone() {
		val raster = largeDisc()
		val outline = unconstrainedPlacedCountOf(raster, standard).first
		val settings = standard.copy(vertexBudget = outline - 4, interiorThinningLimit = 64.0)
		val (result, _) = generateValidated(raster, settings)
		val statistics = result.statistics!!
		val limited = generateArtMesh(raster, standard.copy(vertexBudget = outline - 4)).statistics!!

		assertTrue(statistics.outlineSpacing < limited.outlineSpacing, "the outline gave way later: ${statistics.outlineSpacing} against ${limited.outlineSpacing}")
		assertTrue(statistics.interiorSpacing == null || statistics.interiorSpacing > limited.interiorSpacing!!, "and the interior thinned further first")
	}

	@Test
	fun aLimitBelowTheSettingsOwnRatioNeverDensifiesTheInterior() {
		// Interior spacing 8 outline spacings with a limit of 1: the limit is the settings' own ratio.
		val raster = largeDisc()
		val sparse = standard.copy(outlineSpacing = 16.0, interiorSpacing = 128.0, interiorThinningLimit = 1.0)
		val outline = unconstrainedPlacedCountOf(raster, sparse).first
		val settings = sparse.copy(vertexBudget = outline - 4)
		val statistics = generateArtMesh(raster, settings).statistics!!

		assertTrue(statistics.interiorSpacing == null || statistics.interiorSpacing >= 8.0 * statistics.outlineSpacing - 1e-9, "${statistics.outlineSpacing} / ${statistics.interiorSpacing}")
	}

	@Test
	fun aWidenedOutlineStopsWhereEveryRingIsAtItsMinimum() {
		// 2 px strands every 24 px, its 361 holes kept, and a budget just past the 362 rings' floor: the
		// reserve leaves the floor itself as the target, which the rings' refined chords never quite meet.
		val raster = rasterOf(470, 470) { column, row -> column <= 457 && row <= 457 && (column % 24 < 2 || row % 24 < 2) }
		val floor = 362 * standard.minimumOutlinePoints
		val settings = standard.copy(vertexBudget = floor + 28)
		val (result, _) = generateValidated(raster, settings)
		val coarsened = result.notices.filterIsInstance<ArtMeshNotice.SpacingCoarsened>().single()
		// No ring is longer than the raster's own boundary pushed out by the outline's level.
		val longestRing = 4.0 * (raster.width + 2.0 * (standard.outerMargin + 4.0))

		assertNull(coarsened.interiorSpacing)
		assertTrue(coarsened.outlineSpacing <= longestRing / standard.minimumOutlinePoints, "the spacing stops where widening changes nothing: ${coarsened.outlineSpacing}")
	}

	@Test
	fun theReserveGivesWayRatherThanLoseAMesh() {
		// Six stars whose refined outlines stay well above their rings' floor however wide the spacing:
		// at the smallest budget they mesh under, only the reserve giving way lets them in.
		val raster = spikyStars()
		val budget = (64..1024).first { candidate -> generateArtMesh(raster, standard.copy(vertexBudget = candidate)).mesh != null }
		val settings = standard.copy(vertexBudget = budget)
		val (result, _) = generateValidated(raster, settings)

		assertTrue(placedCountOf(result).second > settings.vertexBudget - reserveOf(settings.vertexBudget), "the outline alone overran the reserve, so the reserve gave way")
	}

	@Test
	fun aMeshThatFitsIsNeverCoarsenedToMakeRoom() {
		val raster = largeDisc()
		val placed = unconstrainedPlacedCountOf(raster, standard).second
		val settings = standard.copy(vertexBudget = placed)
		val (result, _) = generateValidated(raster, settings)
		val statistics = result.statistics!!

		assertEquals(standard.outlineSpacing, statistics.outlineSpacing)
		assertEquals(standard.interiorSpacing, statistics.interiorSpacing)
		assertTrue(result.notices.none { notice -> notice is ArtMeshNotice.SpacingCoarsened }, "${result.notices}")
		assertEquals(0, statistics.steinerVertexCount, "no room was made for refinement")
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
					generateValidated(raster, settings).mesh
				}
			}
		}
	}

	@Test
	fun theOutputIsAFunctionOfItsInput() {
		for (raster in listOf(noisyBlob(), curvedBand())) {
			val first = generateArtMesh(raster, standard).mesh!!
			val second = generateArtMesh(raster, standard).mesh!!
			assertContentEquals(first.positions, second.positions)
			assertContentEquals(first.triangles, second.triangles)
		}
	}

	@Test
	fun refinementLiftsATaperedTipAndLeavesOnlyAnglesItCannotHelp() {
		val raster = taperedTip()
		val unrefinedSettings = standard.copy(minimumAngle = 0.0)
		val unrefined = generateValidated(raster, unrefinedSettings).mesh
		val (result, refined) = generateValidated(raster, standard)
		val statistics = result.statistics!!
		val target = standard.minimumAngle

		assertTrue(statistics.steinerVertexCount > 0, "refinement added vertices")
		assertEquals(refined.vertexCount, unrefined.vertexCount + statistics.steinerVertexCount, "refinement only adds, after the same placed points")
		assertEquals(statistics.underTargetCount, refined.measureQuality(1.0).countBelow(target), "the reported count matches the mesh")
		assertEquals(statistics.underTargetCount, statistics.unfixableCount, "what stays below the target is what refinement cannot help")
		assertTrue(unrefined.measureQuality(1.0).countBelow(target) > statistics.underTargetCount, "fewer triangles below the target than without refinement")
		assertTrue(result.notices.none { notice -> notice is ArtMeshNotice.RefinementBudgetReached })
	}

	@Test
	fun aZeroMinimumAngleAddsNothing() {
		val settings = standard.copy(minimumAngle = 0.0)
		val raster = noisyBlob()
		val (result, _) = generateValidated(raster, settings)
		val statistics = result.statistics!!

		assertEquals(0, statistics.steinerVertexCount)
		assertEquals(0, statistics.boundarySplitCount)
		assertEquals(0, statistics.underTargetCount)
	}

	@Test
	fun refinementStaysWithinTheBudgetAndSaysWhenItRanOut() {
		val raster = noisyBlob()
		val unrefined = generateArtMesh(raster, standard.copy(minimumAngle = 0.0)).mesh!!

		for (room in listOf(0, 3)) {
			val tight = standard.copy(minimumAngle = MAXIMUM_ART_MESH_MINIMUM_ANGLE, vertexBudget = unrefined.vertexCount + room)
			val (result, mesh) = generateValidated(raster, tight)

			assertTrue(mesh.vertexCount <= tight.vertexBudget)
			assertTrue(result.statistics!!.steinerVertexCount <= room)
			assertTrue(result.notices.any { notice -> notice is ArtMeshNotice.RefinementBudgetReached }, "with $room spare: ${result.notices}")
		}
	}

	@Test
	fun anOutlineSplitKeepsTheMarginAndCutsOffNoArt() {
		// One art pixel square at columns 49..50, rows 49..50.
		val raster = rasterOf(100, 100) { column, row -> column in 49..50 && row in 49..50 }
		val field = raster.alphaField(alphaThreshold = 1, cellSize = 1, paddingCells = 4)!!
		val check = boundarySplitCheckOf(field, minimumMargin = 2.0)

		// A chord 4 px above the art.
		assertTrue(check.accepts(30.0, 45.0, 70.0, 45.0, 50.0, 44.9, false), "bulging away from the art")
		assertFalse(check.accepts(30.0, 45.0, 70.0, 45.0, 50.0, 47.5, false), "new chords within the margin")

		// A wide sliver around the art whose chords pass far beside it.
		val wideCheck = boundarySplitCheckOf(field, minimumMargin = 0.5)
		assertFalse(wideCheck.accepts(30.0, 30.0, 70.0, 30.0, 50.0, 80.0, true), "cutting the art out of the domain")
		assertTrue(wideCheck.accepts(30.0, 30.0, 70.0, 30.0, 50.0, 80.0, false), "the same split on the outside cuts nothing off")
	}

	@Test
	fun malformedSettingsAndPinsAreRejected() {
		assertFailsWith<IllegalArgumentException> { standard.copy(minimumMargin = 0.4) }
		assertFailsWith<IllegalArgumentException> { standard.copy(alphaThreshold = 0) }
		assertFailsWith<IllegalArgumentException> { standard.copy(minimumOutlinePoints = 2) }
		assertFailsWith<IllegalArgumentException> { standard.copy(outlineSpacing = 0.0) }
		assertFailsWith<IllegalArgumentException> { standard.copy(minimumAngle = -1.0) }
		assertFailsWith<IllegalArgumentException> { standard.copy(minimumAngle = MAXIMUM_ART_MESH_MINIMUM_ANGLE + 1.0) }
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
	 * A square ring wound the way the field traces one: around art with a positive shoelace area in
	 * raster coordinates, around a hole with a negative one.
	 *
	 * @param Double  left The square's left x.
	 * @param Double  top  Its top y.
	 * @param Double  side Its side length.
	 * @param Boolean hole Whether it goes around a hole.
	 * @return DoubleArray The ring, x0, y0, ...
	 */
	private fun squareRing(left: Double, top: Double, side: Double, hole: Boolean): DoubleArray {
		val right = left + side
		val bottom = top + side

		return if (hole) doubleArrayOf(left, top, left, bottom, right, bottom, right, top) else doubleArrayOf(left, top, right, top, right, bottom, left, bottom)
	}

	/**
	 * A disc of radius 150: an outline of about thirty points around an interior of a few hundred.
	 *
	 * @return LayerRaster The raster.
	 */
	private fun largeDisc(): LayerRaster = rasterOf(320, 320) { column, row -> inDisc(column, row, 160.0, 160.0, 150.0) }

	/**
	 * Six stars of twelve sharp spikes between radii 40 and 70, in a three-by-two grid.  The tips are
	 * 30 degrees apart, too far for an outline chord to skip one without cutting into it, so every star's
	 * outline keeps a vertex at each tip: twice the rings' floor at Standard, however wide the spacing.
	 *
	 * @return LayerRaster The raster.
	 */
	private fun spikyStars(): LayerRaster =
		rasterOf(480, 320) { column, row ->
			val deltaX = column + 0.5 - (80.0 + 160.0 * (column / 160))
			val deltaY = row + 0.5 - (80.0 + 160.0 * (row / 160))
			val turns = atan2(deltaY, deltaX) / (2.0 * PI) * 12.0
			val phase = turns - floor(turns)
			val radius = 40.0 + 30.0 * (1.0 - 2.0 * abs(phase - 0.5))
			deltaX * deltaX + deltaY * deltaY <= radius * radius
		}

	/**
	 * How many points a mesh of a raster places before refinement with no minimum angle and the largest
	 * budget, so nothing is coarsened: the outline's and the pins' first, then all of them.
	 *
	 * @param LayerRaster     raster   The raster.
	 * @param ArtMeshSettings settings The settings the angle and budget are lifted from.
	 * @return Pair<Int, Int> The outline-and-pins count and the total.
	 */
	private fun unconstrainedPlacedCountOf(raster: LayerRaster, settings: ArtMeshSettings): Pair<Int, Int> = placedCountOf(generateArtMesh(raster, settings.copy(minimumAngle = 0.0, vertexBudget = 4096)))

	/**
	 * How many points a result placed before refinement: the outline's and the pins' first, then all
	 * of them.
	 *
	 * @param ArtMeshResult result The result.
	 * @return Pair<Int, Int> The outline-and-pins count and the total.
	 */
	private fun placedCountOf(result: ArtMeshResult): Pair<Int, Int> {
		val statistics = result.statistics!!
		val outline = statistics.outlineVertexCount + statistics.pinCount

		return outline to outline + statistics.innerVertexCount + statistics.latticeVertexCount
	}

	/**
	 * The share of a budget a coarsened mesh keeps back for refinement: thirty percent, rounded up.
	 *
	 * @param Int budget The vertex budget.
	 * @return Int The reserve.
	 */
	private fun reserveOf(budget: Int): Int = (budget * 30 + 99) / 100

	/**
	 * A parabolic taper narrowing to a point, like a lock of hair: 180 px at its base, too short past
	 * strand width for rungs, so its tip is meshed from the outline and refinement.
	 *
	 * @return LayerRaster The raster.
	 */
	private fun taperedTip(): LayerRaster =
		rasterOf(300, 200) { column, row ->
			val offset = row - 100
			row in 10 until 190 && offset * offset * 300 <= (300 - column) * 8100
		}

	/**
	 * A bar 30 px wide from x 10 around row 40, flaring evenly from a column to a width at its end at x 250.
	 *
	 * @param Int    flareStart The column the flare starts at.
	 * @param Double endWidth   The width at the end.
	 * @return LayerRaster The raster.
	 */
	private fun flaredBar(flareStart: Int, endWidth: Double): LayerRaster =
		rasterOf(260, 80) { column, row ->
			val halfWidth = if (column < flareStart) 15.0 else 15.0 + (endWidth / 2.0 - 15.0) * (column - flareStart) / (250.0 - flareStart)
			column in 10 until 250 && abs(row + 0.5 - 40.0) <= halfWidth
		}

	/**
	 * A band 10 px wide around a quarter and more of a circle of radius 100.
	 *
	 * @return LayerRaster The raster.
	 */
	private fun curvedBand(): LayerRaster =
		rasterOf(300, 300) { column, row ->
			val radius = radiusOf(column + 0.5, row + 0.5)
			val angle = angleOf(column + 0.5, row + 0.5)
			radius in 95.0..105.0 && angle in 0.2..2.4
		}

	/**
	 * A point's distance from the curved band's center.
	 *
	 * @param Double x The point's x.
	 * @param Double y The point's y.
	 * @return Double The distance.
	 */
	private fun radiusOf(x: Double, y: Double): Double = sqrt((x - 150.0) * (x - 150.0) + (y - 150.0) * (y - 150.0))

	/**
	 * A point's angle around the curved band's center, radians.
	 *
	 * @param Double x The point's x.
	 * @param Double y The point's y.
	 * @return Double The angle.
	 */
	private fun angleOf(x: Double, y: Double): Double = atan2(y - 150.0, x - 150.0)

	/**
	 * The mesh's vertices a predicate picks.
	 *
	 * @param PlanarTriangleMesh mesh  The mesh.
	 * @param Function           picks Whether to pick the vertex at (x, y).
	 * @return List<Pair<Double, Double>> The picked vertices.
	 */
	private fun verticesWhere(mesh: PlanarTriangleMesh, picks: (Double, Double) -> Boolean): List<Pair<Double, Double>> =
		(0 until mesh.vertexCount).map { vertex -> mesh.positions[2 * vertex].toDouble() to mesh.positions[2 * vertex + 1].toDouble() }.filter { (x, y) -> picks(x, y) }

	/**
	 * Asserts a horizontal strand's outline vertices pair across it: over a stretch of x, every vertex
	 * above the centerline has one below it within 3 px, and every one below one above - a rung's ends
	 * are interpolated between chords about a working cell apart, so they can lean by a cell.
	 *
	 * @param PlanarTriangleMesh             mesh    The mesh.
	 * @param Double                         centerY The strand's centerline.
	 * @param ClosedFloatingPointRange<Double> along  The stretch of x checked.
	 */
	private fun assertPairedAcross(mesh: PlanarTriangleMesh, centerY: Double, along: ClosedFloatingPointRange<Double>) {
		val above = verticesWhere(mesh) { x, y -> y < centerY - 4.0 && x in along }.map { (x, _) -> x }
		val below = verticesWhere(mesh) { x, y -> y > centerY + 4.0 && x in along }.map { (x, _) -> x }

		assertTrue(above.size >= 2, "$above")

		for (x in above) {
			assertTrue(below.any { other -> abs(other - x) <= 3.0 }, "the vertex above at x $x has one below it: $below")
		}

		for (x in below) {
			assertTrue(above.any { other -> abs(other - x) <= 3.0 }, "the vertex below at x $x has one above it: $above")
		}
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