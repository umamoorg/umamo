package org.umamo.render.eval

import org.umamo.runtime.model.Keyform
import kotlin.test.Test
import kotlin.test.assertEquals

class KeyformBlendTest {
	@Test
	fun blendSumsWeightedDeltas() {
		// base + 0.5*[2,4] + 1.0*[1,1] = [1+1+1, 1+2+1] = [3, 4]
		val base = floatArrayOf(1f, 1f)
		val keyforms =
			listOf(
				0.5f to Keyform(atValue = 0f, delta = floatArrayOf(2f, 4f)),
				1.0f to Keyform(atValue = 1f, delta = floatArrayOf(1f, 1f)),
			)
		val out = blend(base, keyforms)
		assertEquals(3f, out[0])
		assertEquals(4f, out[1])
	}
}
