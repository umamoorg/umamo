package org.umamo.ui.viewport

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locks the UV editor's display mapping: texel units with the V axis flipped into the camera's Y-up
 * convention.  The orientation assertion is the load-bearing one - stored v = 0 is the TOP row of the
 * atlas image, so it must land at display y = pageHeight (the top in a Y-up world), or the wireframe
 * renders vertically mirrored against the drawn page.
 */
class UvDisplayMappingTest {
	/** v = 0 (the image's top row) maps to y = pageHeight; v = 1 (the bottom row) maps to y = 0. */
	@Test
	fun orientationLock() {
		assertEquals(512f, uvToDisplayY(0f, 512), 1e-4f, "the top row lands at the Y-up top")
		assertEquals(0f, uvToDisplayY(1f, 512), 1e-4f, "the bottom row lands at the Y-up bottom")
		assertEquals(0f, uvToDisplayX(0f, 1024), 1e-4f)
		assertEquals(1024f, uvToDisplayX(1f, 1024), 1e-4f)
	}

	/** Round-tripping an array through display space and back is the identity (within float noise). */
	@Test
	fun roundTripIdentity() {
		val uvs = floatArrayOf(0f, 0f, 1f, 1f, 0.25f, 0.75f, 0.6f, 0.1f)
		val roundTripped = displayToUv(uvToDisplay(uvs, 2048, 1024), 2048, 1024)
		assertEquals(uvs.size, roundTripped.size)
		for (componentIndex in uvs.indices) {
			assertEquals(uvs[componentIndex], roundTripped[componentIndex], 1e-5f, "component $componentIndex round-trips")
		}
	}

	/** Non-square pages scale each axis by its own texel extent, so aspect is preserved on screen. */
	@Test
	fun nonSquarePageAspect() {
		val display = uvToDisplay(floatArrayOf(0.5f, 0.5f), 2048, 512)
		assertEquals(1024f, display[0], 1e-3f, "u scales by the page width")
		assertEquals(256f, display[1], 1e-3f, "v scales by the page height")
	}
}
