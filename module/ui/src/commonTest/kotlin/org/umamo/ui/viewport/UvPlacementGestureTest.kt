package org.umamo.ui.viewport

import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.TransformAxisConstraint
import org.umamo.format.art.LayerBounds
import org.umamo.render.PlacementFootprint
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

	private fun mover(id: String, placement: AtlasPlacement, pivotDisplayX: Float, pivotDisplayY: Float): PlacementMover =
		PlacementMover(AtlasTileId(id), placement, trim, reserve = null, pivotDisplayX, pivotDisplayY, crop = null)

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
	): PlacementDragResult = evaluatePlacementDrag(kind, parameters, axisConstraint, movers, bystanders, pageWidth, pageHeight, extrude = 2)

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
	fun collisionsAndTheEdgeAreReported() {
		val moverA = mover("a", placement(20f, 30f), 25f, 45f)
		val moverB = mover("b", placement(60f, 30f), 65f, 45f)
		val bystander = PlacementBystander(AtlasTileId("c"), PlacementFootprint(33f, 30f, 43f, 40f))

		// A moves right by 2: its band (extrude 2) meets the bystander's band, B stays clear.
		val nudged = evaluate(MeshOperatorKind.Grab, parameters(deltaX = 2f), listOf(moverA, moverB), listOf(bystander))
		assertEquals(setOf(AtlasTileId("a"), AtlasTileId("c")), nudged.overlappingTileIds)
		assertEquals(1, nudged.status.overlapCount, "one mover collides")
		assertTrue(nudged.offPageTileIds.isEmpty())
		assertTrue(!nudged.status.offPage)

		// Both movers slide left by 25: A's footprint crosses the page's left edge.
		val spilled = evaluate(MeshOperatorKind.Grab, parameters(deltaX = -25f), listOf(moverA, moverB), listOf(bystander))
		assertEquals(setOf(AtlasTileId("a")), spilled.offPageTileIds)
		assertTrue(spilled.status.offPage)

		// Movers can collide with each other: B slides onto A.
		val stacked = evaluate(MeshOperatorKind.Grab, parameters(deltaX = 0f), listOf(moverA, PlacementMover(AtlasTileId("b"), placement(28f, 30f), trim, null, 33f, 45f, null)), emptyList())
		assertEquals(setOf(AtlasTileId("a"), AtlasTileId("b")), stacked.overlappingTileIds)
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