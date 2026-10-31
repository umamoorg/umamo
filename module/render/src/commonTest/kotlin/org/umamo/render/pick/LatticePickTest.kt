package org.umamo.render.pick

import org.umamo.render.ViewportCamera
import org.umamo.render.puppet.LatticePoints
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.WarpLatticeLayout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the lattice pick: every control point is a target, a point outranks a grid line, a line takes its own
 * band, nearer outranks farther, a tie goes to the warp later in the model, and the radii are screen pixels at
 * every zoom.
 */
class LatticePickTest {
	private val viewportSize = 200
	private val camera = ViewportCamera(0f, 0f, 1f)
	private val parent = DeformerId("parent")
	private val child = DeformerId("child")

	/**
	 * A 2x2 lattice of points [spacing] apart from ([originX], [originZ]), in world space.
	 *
	 * @param Float originX The first point's x.
	 * @param Float originZ The first point's z.
	 * @param Float spacing The distance between neighboring points.
	 * @return LatticePoints The lattice.
	 */
	private fun lattice(originX: Float, originZ: Float, spacing: Float): LatticePoints {
		val layout = WarpLatticeLayout(2, 2)
		val world = FloatArray(layout.pointCount * 2)

		for (row in 0..2) {
			for (column in 0..2) {
				val pointIndex = layout.pointIndex(row, column)
				world[pointIndex * 2] = originX + column * spacing
				world[pointIndex * 2 + 1] = originZ - row * spacing
			}
		}

		return LatticePoints(layout, world)
	}

	/**
	 * The screen pixel a world point projects to through [camera].
	 *
	 * @param Float worldX The world x.
	 * @param Float worldZ The world z.
	 * @param ViewportCamera camera The camera.
	 * @return Pair<Float, Float> The screen x and y.
	 */
	private fun screenOf(worldX: Float, worldZ: Float, camera: ViewportCamera = this.camera): Pair<Float, Float> =
		worldToScreenX(worldX, camera, viewportSize) to worldToScreenY(worldZ, camera, viewportSize)

	@Test
	fun theMiddlePointIsATargetLikeTheCorners() {
		val lattices = mapOf(parent to lattice(-40f, 40f, 40f))
		val (middleX, middleY) = screenOf(0f, 0f)

		assertEquals(listOf(parent), latticeHitsAt(lattices, middleX + 2f, middleY, 3f, 3f, camera, viewportSize, viewportSize))
		assertTrue(latticeHitsAt(lattices, middleX + 10f, middleY + 10f, 3f, 3f, camera, viewportSize, viewportSize).isEmpty(), "inside a cell, off every line, is no hit")
	}

	@Test
	fun aGridLineIsATargetBetweenItsPoints() {
		val lattices = mapOf(parent to lattice(-40f, 40f, 40f))
		val (lineX, lineY) = screenOf(-20f, 40f)

		assertEquals(listOf(parent), latticeHitsAt(lattices, lineX, lineY + 2f, 3f, 3f, camera, viewportSize, viewportSize), "halfway along the top line")
	}

	@Test
	fun aPointOutranksALineAndNearerOutranksFarther() {
		// The child's corner sits 2 pixels from the pointer; the parent's top line runs 1 pixel from it.
		val lattices = mapOf(parent to lattice(-40f, 40f, 40f), child to lattice(-18f, 39f, 5f))
		val (pointerX, pointerY) = screenOf(-20f, 39f)

		assertEquals(listOf(child, parent), latticeHitsAt(lattices, pointerX, pointerY, 3f, 3f, camera, viewportSize, viewportSize), "the point first, though the line is nearer")

		val (nearX, nearY) = screenOf(-39f, 39f)
		val both = mapOf(parent to lattice(-40f, 40f, 40f), child to lattice(-37f, 40f, 40f))
		assertEquals(listOf(parent, child), latticeHitsAt(both, nearX, nearY, 3f, 3f, camera, viewportSize, viewportSize), "between two points, the nearer first")
	}

	@Test
	fun aLineTakesItsOwnNarrowerBand() {
		val lattices = mapOf(parent to lattice(-40f, 40f, 40f))
		val (lineX, lineY) = screenOf(-20f, 40f)

		assertEquals(listOf(parent), latticeHitsAt(lattices, lineX, lineY + 6f, 10f, 8f, camera, viewportSize, viewportSize), "6 pixels off a line, inside its band")
		assertTrue(latticeHitsAt(lattices, lineX, lineY + 6f, 10f, 4f, camera, viewportSize, viewportSize).isEmpty(), "and outside a narrower one, though a point would reach")
	}

	@Test
	fun aTieGoesToTheLaterWarp() {
		val shared = lattice(-40f, 40f, 40f)
		val lattices = mapOf(parent to shared, child to lattice(-40f, 40f, 40f))
		val (cornerX, cornerY) = screenOf(-40f, 40f)

		assertEquals(listOf(child, parent), latticeHitsAt(lattices, cornerX, cornerY, 3f, 3f, camera, viewportSize, viewportSize), "the child, drawn over its parent")
	}

	@Test
	fun theRadiusIsScreenPixelsAtEveryZoom() {
		val lattices = mapOf(parent to lattice(-40f, 40f, 40f))
		val zoomedOut = ViewportCamera(0f, 0f, 0.25f)
		val zoomedIn = ViewportCamera(0f, 0f, 4f)

		// Two world units off both middle grid lines: 8 pixels from each zoomed in, half a pixel zoomed out.
		val (outX, outY) = screenOf(2f, 2f, zoomedOut)
		val (inX, inY) = screenOf(2f, 2f, zoomedIn)

		assertEquals(listOf(parent), latticeHitsAt(lattices, outX, outY, 3f, 3f, zoomedOut, viewportSize, viewportSize))
		assertTrue(latticeHitsAt(lattices, inX, inY, 3f, 3f, zoomedIn, viewportSize, viewportSize).isEmpty(), "the same world distance is too far zoomed in")
	}

	@Test
	fun theCenterIsTheMeanOfThePoints() {
		val centers = latticeCenters(mapOf(parent to lattice(-40f, 40f, 40f), child to lattice(10f, 20f, 2f)))

		assertContentEquals(floatArrayOf(0f, 0f), centers[parent])
		assertContentEquals(floatArrayOf(12f, 18f), centers[child])
		assertEquals(listOf(parent, child), centers.keys.toList())
	}
}