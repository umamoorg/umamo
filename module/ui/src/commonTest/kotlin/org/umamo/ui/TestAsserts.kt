package org.umamo.ui

import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Asserts two interleaved point arrays agree component by component within [tolerance]: the one array comparison the
 * geometry tests share, so each states only how near is near enough.
 *
 * @param FloatArray expected The expected points.
 * @param FloatArray actual The actual points.
 * @param Float tolerance The largest difference a component may show.
 * @param String message What is compared.
 */
internal fun assertArrayNear(expected: FloatArray, actual: FloatArray, tolerance: Float, message: String) {
	assertEquals(expected.size, actual.size, message)

	for (componentIndex in expected.indices) {
		assertTrue(abs(expected[componentIndex] - actual[componentIndex]) <= tolerance, "$message at $componentIndex: ${expected[componentIndex]} vs ${actual[componentIndex]}")
	}
}