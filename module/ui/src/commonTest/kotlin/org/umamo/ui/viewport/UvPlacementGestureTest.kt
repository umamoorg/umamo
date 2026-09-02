package org.umamo.ui.viewport

import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.TransformAxisConstraint
import org.umamo.format.art.LayerBounds
import org.umamo.format.atlas.AtlasPackReserve
import org.umamo.render.DecodedImage
import org.umamo.render.PageOccupancy
import org.umamo.render.PixelRect
import org.umamo.render.SampledRegion
import org.umamo.render.TileMeshMask
import org.umamo.render.TileOpaqueMask
import org.umamo.render.placementFootprint
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.applyUvAffine
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the placement drag's evaluation: Grab snaps to whole page pixels with the display flip
 * applied, Rotate and uniform Scale turn about the pivot and land on the placement's own TRS
 * components, an axis-locked Scale on a rotated tile scales the tile's own axis, the islands' display
 * affine agrees with the placement (the pivot stays put), and collisions with bystanders, other
 * movers, and the page edge are reported.
 */
class UvPlacementGestureTest {
	private val pageWidth = 100
	private val pageHeight = 80
	private val trim = LayerBounds(0, 0, 10, 10)

	/** A fully opaque 10x10 tile whose mesh covers exactly its art (plus the mask's one-pixel margin). */
	private val solidMask = TileOpaqueMask.of(opaqueRaster(10, 10), trim, alphaThreshold = 1)
	private val solidReserve = AtlasPackReserve(0, 0, 10, 10)
	private val solidMesh = quadMesh(0f, 0f, 10f, 10f)

	/** A mesh covering the rectangle [left, right) by [top, bottom) with two triangles. */
	private fun quadMesh(left: Float, top: Float, right: Float, bottom: Float): TileMeshMask =
		TileMeshMask.of(
			listOf(floatArrayOf(left, top, right, top, right, bottom), floatArrayOf(left, top, right, bottom, left, bottom)),
		)!!

	private fun opaqueRaster(width: Int, height: Int): DecodedImage {
		val rgba = ByteArray(width * height * 4)
		for (pixelIndex in 0 until width * height) {
			rgba[pixelIndex * 4 + 3] = 0xFF.toByte()
		}
		return DecodedImage(rgba, width, height)
	}

	private fun mover(
		id: String,
		placement: AtlasPlacement,
		pivotDisplayX: Float,
		pivotDisplayY: Float,
		reserve: AtlasPackReserve? = solidReserve,
		meshMask: TileMeshMask? = solidMesh,
		mask: TileOpaqueMask? = solidMask,
	): PlacementMover = PlacementMover(AtlasTileId(id), placement, trim, reserve, meshMask, mask, emptyList(), pivotDisplayX, pivotDisplayY, crop = null)

	/** A page painted opaque inside [painted] only. */
	private fun pageWith(painted: PixelRect, excluded: List<PixelRect>): PageOccupancy {
		val rgba = ByteArray(pageWidth * pageHeight * 4)
		for (y in painted.top until painted.bottom) {
			for (x in painted.left until painted.right) {
				rgba[(y * pageWidth + x) * 4 + 3] = 0xFF.toByte()
			}
		}
		return PageOccupancy.of(DecodedImage(rgba, pageWidth, pageHeight), excluded)
	}

	private fun bystander(id: String, placement: AtlasPlacement, meshMask: TileMeshMask?): PlacementBystander =
		PlacementBystander(
			AtlasTileId(id),
			placement,
			meshMask?.let { coverage -> SampledRegion(placement, coverage) },
			placementFootprint(placement, trim, reserve = null).expanded(2f),
		)

	private fun placement(x: Float, y: Float, rotationDegrees: Float = 0f, scale: Float = 1f): AtlasPlacement =
		AtlasPlacement(0, x, y, scale, scale, rotationDegrees)

	private fun parameters(
		deltaX: Float = 0f,
		deltaY: Float = 0f,
		factorX: Float = 1f,
		factorY: Float = 1f,
		rotationRadians: Float = 0f,
	): PlacementGestureParameters = PlacementGestureParameters(deltaX, deltaY, factorX, factorY, rotationRadians)

	private fun evaluate(
		kind: MeshOperatorKind,
		parameters: PlacementGestureParameters,
		movers: List<PlacementMover>,
		bystanders: List<PlacementBystander> = emptyList(),
		axisConstraint: TransformAxisConstraint? = null,
		occupancy: PageOccupancy? = null,
	): PlacementDragResult = evaluatePlacementDrag(kind, parameters, axisConstraint, movers, bystanders, occupancy, pageWidth, pageHeight, extrude = 2)

	private fun assertClose(expected: Float, actual: Float, message: String) {
		assertTrue(abs(expected - actual) < 1e-3f, "$message: expected $expected, was $actual")
	}

	@Test
	fun grabSnapsToWholePagePixelsThroughTheFlip() {
		val mover = mover("a", placement(20f, 30f), 25f, 45f)
		val result = evaluate(MeshOperatorKind.Grab, parameters(deltaX = 2.6f, deltaY = -3.4f), listOf(mover))

		val moved = result.placementByTile.getValue(mover.tileId)
		assertClose(23f, moved.positionX, "x snapped to 3")
		assertClose(33f, moved.positionY, "display y up 3.4 is page y down 3")
		assertEquals(3, result.status.deltaX)
		assertEquals(3, result.status.deltaY)
		// The islands follow the same move in display space.
		val displayed = applyUvAffine(floatArrayOf(25f, 45f), result.displayAffineByTile.getValue(mover.tileId))
		assertClose(28f, displayed[0], "island x")
		assertClose(42f, displayed[1], "island y (display y up)")
	}

	@Test
	fun rotateTurnsAboutThePivotAndTheIslandsAgree() {
		val original = placement(20f, 30f, rotationDegrees = 10f)
		val mover = mover("a", original, 40f, 40f)
		val quarterTurn = (PI / 2).toFloat()
		val result = evaluate(MeshOperatorKind.Rotate, parameters(rotationRadians = quarterTurn), listOf(mover))

		val turned = result.placementByTile.getValue(mover.tileId)
		assertClose(1f, turned.scaleX, "scale untouched")
		assertClose(1f, turned.scaleY, "scale untouched")
		// A display-space turn is the page-space turn mirrored: the placement's angle moves by -90.
		assertClose(-80f, turned.rotationDegrees, "the page angle mirrors the display angle")
		assertClose(-90f, result.status.angleDegrees, "the readout shows the page-space angle")
		// The pivot is a fixed point of the islands' affine.
		val pivot = applyUvAffine(floatArrayOf(40f, 40f), result.displayAffineByTile.getValue(mover.tileId))
		assertClose(40f, pivot[0], "pivot x fixed")
		assertClose(40f, pivot[1], "pivot y fixed")
	}

	@Test
	fun uniformScaleMultipliesBothAxesAboutThePivot() {
		val mover = mover("a", placement(20f, 30f, rotationDegrees = 45f), 40f, 40f)
		val result = evaluate(MeshOperatorKind.Scale, parameters(factorX = 2f, factorY = 2f), listOf(mover))

		val scaled = result.placementByTile.getValue(mover.tileId)
		assertClose(2f, scaled.scaleX, "scale x doubled")
		assertClose(2f, scaled.scaleY, "scale y doubled")
		assertClose(45f, scaled.rotationDegrees, "rotation untouched")
		val pivot = applyUvAffine(floatArrayOf(40f, 40f), result.displayAffineByTile.getValue(mover.tileId))
		assertClose(40f, pivot[0], "pivot x fixed")
		assertClose(40f, pivot[1], "pivot y fixed")
	}

	@Test
	fun axisLockedScaleOnARotatedTileScalesItsOwnAxis() {
		val mover = mover("a", placement(20f, 30f, rotationDegrees = 30f), 40f, 40f)
		val result =
			evaluate(
				MeshOperatorKind.Scale,
				parameters(factorX = 2f, factorY = 1f),
				listOf(mover),
				axisConstraint = TransformAxisConstraint.AxisX,
			)

		val scaled = result.placementByTile.getValue(mover.tileId)
		assertClose(2f, scaled.scaleX, "the tile's own x axis scaled")
		assertClose(1f, scaled.scaleY, "the tile's own y axis untouched")
		assertClose(30f, scaled.rotationDegrees, "rotation untouched - no shear was written")
	}

	@Test
	fun aMeshOverAnotherTilesPaintCollidesAndPaintNobodySamplesDoesNot() {
		// Mover A: opaque 10x10 at page (20, 30), mesh over exactly its art.  Bystander C paints page
		// pixels x 33..43, y 30..40 and its mesh covers only the right half of that paint (x 38..43).
		val moverA = mover("a", placement(20f, 30f), 25f, 45f)
		val painted = PixelRect(33, 30, 43, 40)
		val bystanderC = bystander("c", placement(33f, 30f), quadMesh(5f, 0f, 10f, 10f))
		val occupancy = pageWith(painted, excluded = listOf(PixelRect(18, 28, 32, 42)))

		// A moves right by 2: its mesh (with its margin) ends at x 32, one pixel short of C's paint, and
		// C's mesh starts at x 37, past A's paint and its band - nothing samples anything.
		val clear = evaluate(MeshOperatorKind.Grab, parameters(deltaX = 2f), listOf(moverA), listOf(bystanderC), occupancy = occupancy)
		assertTrue(clear.overlappingTileIds.isEmpty(), "adjacent paint with no mesh over it is not a collision, was ${clear.overlappingTileIds}")

		// A moves right by 5: A's mesh (x 24..36) now reads C's paint at x 33..35.
		val samplesPaint = evaluate(MeshOperatorKind.Grab, parameters(deltaX = 5f), listOf(moverA), listOf(bystanderC), occupancy = occupancy)
		assertEquals(setOf(AtlasTileId("a"), AtlasTileId("c")), samplesPaint.overlappingTileIds)
		assertEquals(setOf(AtlasTileId("a")), samplesPaint.samplingTileIds, "A is the sampler")
		assertEquals(setOf(AtlasTileId("c")), samplesPaint.paintingTileIds, "C is the painter")
		assertEquals(1, samplesPaint.status.overlapCount)

		// A moves right by 16: A's paint (x 36..46) lands under C's mesh (x 37..44) while A's own mesh
		// sits over C's paint too - both directions report.
		val underMesh = evaluate(MeshOperatorKind.Grab, parameters(deltaX = 16f), listOf(moverA), listOf(bystanderC), occupancy = occupancy)
		assertTrue(AtlasTileId("a") in underMesh.paintingTileIds, "A's paint is under C's mesh")
		assertTrue(AtlasTileId("c") in underMesh.samplingTileIds, "C samples A")

		// A moves right by 24: A's paint (x 44..54) sits past C's paint, and only C's mesh (to x 44)
		// reaches it - C samples A, A samples nothing.
		val reached = evaluate(MeshOperatorKind.Grab, parameters(deltaX = 24f), listOf(moverA), listOf(bystanderC), occupancy = occupancy)
		assertEquals(setOf(AtlasTileId("c")), reached.samplingTileIds, "only C's mesh reaches A's paint")
		assertTrue(reached.overlappingTileIds.containsAll(setOf(AtlasTileId("a"), AtlasTileId("c"))))

		// Paint nobody samples: a mover with no mesh over C's paint, when C's mesh is elsewhere.
		val meshless = mover("m", placement(20f, 30f), 25f, 45f, meshMask = null)
		val ignored = evaluate(MeshOperatorKind.Grab, parameters(deltaX = 5f), listOf(meshless), listOf(bystanderC), occupancy = occupancy)
		assertTrue(ignored.overlappingTileIds.isEmpty(), "overlapping paint with no mesh reading it is allowed, was ${ignored.overlappingTileIds}")
	}

	@Test
	fun overlappingBoxesWithDisjointTrianglesAndPixelsDoNotWarn() {
		// The screenshot: mover A's mesh overhangs its art on the right by ten pixels but only along a
		// thin diagonal; bystander C's paint sits inside A's mesh BOX yet off that diagonal, and C's
		// own mesh is a sliver that never reaches A's pixels.
		val overhang = TileMeshMask.of(listOf(floatArrayOf(0f, 0f, 10f, 10f, 20f, 2f), floatArrayOf(0f, 0f, 20f, 2f, 0f, 2f)))!!
		val moverA = mover("a", placement(20f, 30f), 25f, 45f, meshMask = overhang)
		// C paints a dot at page (36, 38): inside A's mesh box (x 20..40, y 30..40) after the move, but
		// far from the diagonal sliver.
		// No vacated spot to exclude: the synthetic page holds nothing but the dot.
		val occupancy = pageWith(PixelRect(36, 38, 37, 39), excluded = emptyList())
		val bystanderC = bystander("c", placement(30f, 34f), quadMesh(4f, 2f, 8f, 6f))
		val result = evaluate(MeshOperatorKind.Grab, parameters(deltaX = 0f), listOf(moverA), listOf(bystanderC), occupancy = occupancy)
		assertTrue(result.overlappingTileIds.isEmpty(), "boxes overlap, triangles and pixels do not: no warning, was ${result.overlappingTileIds}")
		// Move the dot under the sliver's start and it warns.
		val touching = pageWith(PixelRect(24, 31, 25, 32), excluded = emptyList())
		val hit = evaluate(MeshOperatorKind.Grab, parameters(deltaX = 0f), listOf(moverA), listOf(bystanderC), occupancy = touching)
		assertEquals(setOf(AtlasTileId("a")), hit.samplingTileIds, "the sliver over the dot samples it")
	}

	@Test
	fun theEdgeAndOtherMoversAreReported() {
		val moverA = mover("a", placement(20f, 30f), 25f, 45f)
		val moverB = mover("b", placement(60f, 30f), 65f, 45f)

		// Both slide left by 25: A's opaque bounds cross the page's left edge.
		val spilled = evaluate(MeshOperatorKind.Grab, parameters(deltaX = -25f), listOf(moverA, moverB))
		assertEquals(setOf(AtlasTileId("a")), spilled.offPageTileIds)
		assertTrue(spilled.status.offPage)

		// A mesh reaching past the page over transparent pixels is not a spill: mover with a wide mesh.
		val wideMesh = mover("w", placement(2f, 30f), 7f, 45f, meshMask = quadMesh(-5f, 0f, 10f, 10f))
		val reaching = evaluate(MeshOperatorKind.Grab, parameters(deltaX = 0f), listOf(wideMesh))
		assertTrue(reaching.offPageTileIds.isEmpty(), "the reserve alone does not spill")

		// Movers collide with each other: B stacked onto A samples A's paint and A samples B's.
		val stacked = evaluate(MeshOperatorKind.Grab, parameters(deltaX = 0f), listOf(moverA, mover("b", placement(28f, 30f), 33f, 45f)))
		assertEquals(setOf(AtlasTileId("a"), AtlasTileId("b")), stacked.overlappingTileIds)
		assertEquals(setOf(AtlasTileId("a"), AtlasTileId("b")), stacked.samplingTileIds)
	}

	@Test
	fun theTileToDisplayMappingFlipsOnceAfterThePlacement() {
		// A tile at page (20, 30): its origin shows at display (20, h - 30) and its rows run DOWN the
		// page, so the next row sits one texel lower in display space - not mirrored below the page.
		val mapping = tileToDisplayAffine(placement(20f, 30f), pageHeight)
		val origin = applyUvAffine(floatArrayOf(0f, 0f), mapping)
		val nextRow = applyUvAffine(floatArrayOf(0f, 1f), mapping)
		assertClose(20f, origin[0], "origin x")
		assertClose((pageHeight - 30).toFloat(), origin[1], "origin y")
		assertClose((pageHeight - 31).toFloat(), nextRow[1], "the next row is one texel lower")
	}

	@Test
	fun theFlipIsItsOwnInverse() {
		val affine = floatArrayOf(0.5f, -0.25f, 7f, 0.25f, 0.5f, -3f)
		val roundTrip = flipAffineFrame(flipAffineFrame(affine, pageHeight), pageHeight)
		for (componentIndex in affine.indices) {
			assertClose(affine[componentIndex], roundTrip[componentIndex], "component $componentIndex")
		}
	}
}