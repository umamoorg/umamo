package org.umamo.render.puppet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pins [fallbackColorFor]: stable per id, distinct across ids, and always in the readable mid-band. */
class FallbackColorTest {
	@Test
	fun fallbackColorForIsStableForTheSameId() {
		// The point of hashing the id rather than cycling a palette: a drawable keeps its color across
		// reorders and reloads.
		assertTrue(fallbackColorFor("mesh_a").contentEquals(fallbackColorFor("mesh_a")))
	}

	@Test
	fun fallbackColorForDiffersAcrossIds() {
		assertTrue(!fallbackColorFor("mesh_a").contentEquals(fallbackColorFor("mesh_b")), "neighbouring meshes must be tellable apart")
	}

	@Test
	fun fallbackColorForStaysInTheReadableMidBand() {
		// 0.35 floor + 0.6 span keeps every channel mid-bright, so the mesh reads against both the light
		// and dark grid backdrops.
		for (id in listOf("", "a", "some_long_drawable_id", "ParamAngleX", "0")) {
			val color = fallbackColorFor(id)
			assertEquals(4, color.size, "RGBA")
			for (channelIndex in 0..2) {
				assertTrue(color[channelIndex] >= 0.35f, "$id channel $channelIndex is at or above the floor")
				assertTrue(color[channelIndex] <= 0.95f, "$id channel $channelIndex is at or below floor + span")
			}
			assertEquals(0.85f, color[3], "alpha is fixed")
		}
	}
}
