package org.umamo.render.puppet

import org.umamo.render.PuppetTextures
import org.umamo.render.ViewportCamera
import org.umamo.render.device.RecordingRenderDevice
import org.umamo.render.eval.warpLatticeAt
import org.umamo.runtime.model.WarpLatticeForm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Pins the renderer's lattice override at the pose: a warp posed with a lattice standing in for its grid blend is
 * drawn there - the lattice layer and the picks read it - and the warp nested in it follows, with no model change.
 */
class LatticeOverridePoseTest {
	@Test
	fun anOverriddenLatticeIsDrawnAndItsChildFollows() {
		val model = latticeRigModel()
		val renderer = PuppetRenderer(model, PuppetTextures(emptyList(), emptyMap(), premultipliedAlpha = false), RecordingRenderDevice())
		renderer.initGl()
		renderer.setCamera(ViewportCamera(0f, 0f, 1f))
		renderer.setPose(emptyMap())
		val outerBefore = assertNotNull(renderer.latticeWorldPoints()[LATTICE_RIG_OUTER]).world.copyOf()
		val innerBefore = assertNotNull(renderer.latticeWorldPoints()[LATTICE_RIG_INNER]).world.copyOf()
		val grid = assertNotNull(warpLatticeAt(model, emptyMap(), LATTICE_RIG_OUTER)).gridLocal
		val shifted = FloatArray(grid.size) { componentIndex -> if (componentIndex % 2 == 0) grid[componentIndex] + 7f else grid[componentIndex] }

		renderer.setPose(emptyMap(), latticeOverrides = mapOf(LATTICE_RIG_OUTER to WarpLatticeForm(shifted)))

		val outerAfter = assertNotNull(renderer.latticeWorldPoints()[LATTICE_RIG_OUTER]).world
		val innerAfter = assertNotNull(renderer.latticeWorldPoints()[LATTICE_RIG_INNER]).world

		for (componentIndex in outerBefore.indices) {
			val expectedShift = if (componentIndex % 2 == 0) 7f else 0f
			assertEquals(outerBefore[componentIndex] + expectedShift, outerAfter[componentIndex], 1e-3f, "the overridden lattice is drawn at $componentIndex")
		}

		for (componentIndex in innerBefore.indices) {
			val expectedShift = if (componentIndex % 2 == 0) 7f else 0f
			assertEquals(innerBefore[componentIndex] + expectedShift, innerAfter[componentIndex], 1e-3f, "the warp inside follows at $componentIndex")
		}

		renderer.setPose(emptyMap())
		assertEquals(outerBefore[0], assertNotNull(renderer.latticeWorldPoints()[LATTICE_RIG_OUTER]).world[0], 1e-3f, "without the override it is back")
	}
}