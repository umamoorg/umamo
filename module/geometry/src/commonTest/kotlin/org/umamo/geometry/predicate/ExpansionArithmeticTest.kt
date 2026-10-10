package org.umamo.geometry.predicate

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Unit tests for the exact expansion arithmetic under the predicates: each tail recovers its
 * operation's roundoff exactly, and expansion sums and scales are exact.  This suite runs on the JVM
 * AND on linuxX64, where the fused-multiply-add check below is the reason the native target exists.
 */
class ExpansionArithmeticTest {
	@Test
	fun productTailSurvivesWithoutFusedMultiplyAdd() {
		// (1 + 2^-30)^2 = 1 + 2^-29 + 2^-60: the product rounds the 2^-60 away and the tail must
		// recover exactly that.  A compiler that fuses any multiply-add in the tail breaks this.
		val factor = 1.0 + TWO_TO_MINUS_30
		val product = factor * factor
		assertEquals(1.0 + 2.0 * TWO_TO_MINUS_30, product)
		assertEquals(TWO_TO_MINUS_60, twoProductTail(factor, factor, product))
		assertEquals(TWO_TO_MINUS_60, squareTail(factor, product))
	}

	@Test
	fun sumAndDifferenceTailsRecoverTheRoundoff() {
		val sum = 1.0 + TWO_TO_MINUS_60
		assertEquals(1.0, sum)
		assertEquals(TWO_TO_MINUS_60, twoSumTail(1.0, TWO_TO_MINUS_60, sum))
		assertEquals(TWO_TO_MINUS_60, fastTwoSumTail(1.0, TWO_TO_MINUS_60, sum))
		val difference = 1.0 - TWO_TO_MINUS_60
		assertEquals(1.0, difference)
		assertEquals(-TWO_TO_MINUS_60, twoDiffTail(1.0, TWO_TO_MINUS_60, difference))
	}

	@Test
	fun expansionSumCancelsExactly() {
		val out = DoubleArray(3)
		val length = expansionSum(2, doubleArrayOf(TWO_TO_MINUS_60, 1.0), 1, doubleArrayOf(-1.0), out)
		assertEquals(1, length)
		assertEquals(TWO_TO_MINUS_60, out[0])
	}

	@Test
	fun scaleExpansionKeepsTheLowComponent() {
		val out = DoubleArray(4)
		val length = scaleExpansion(2, doubleArrayOf(TWO_TO_MINUS_60, 1.0), 3.0, out)
		assertContentEquals(doubleArrayOf(3.0 * TWO_TO_MINUS_60, 3.0), out.copyOf(length))
	}

	@Test
	fun expansionSumsOfIntegersAreExact() {
		// Integers up to 2^60 do not fit a double, but as two-component expansions they are exact, and
		// every component of their sum is an integer, so Long arithmetic is an exact oracle.
		val random = Random(17)
		val out = DoubleArray(4)
		repeat(20_000) {
			val first = random.nextLong(-(1L shl 60), 1L shl 60)
			val second = random.nextLong(-(1L shl 60), 1L shl 60)
			val length = expansionSum(2, expansionOf(first), 2, expansionOf(second), out)
			assertEquals(first + second, integerValueOf(out, length), "$first + $second")
		}
	}

	@Test
	fun scaledExpansionsOfIntegersAreExact() {
		val random = Random(23)
		val out = DoubleArray(4)
		repeat(20_000) {
			val value = random.nextLong(-(1L shl 50), 1L shl 50)
			val factor = random.nextInt(-1024, 1024)
			val length = scaleExpansion(2, expansionOf(value), factor.toDouble(), out)
			assertEquals(value * factor, integerValueOf(out, length), "$value * $factor")
		}
	}

	@Test
	fun crossProductIsExactOnLargeIntegers() {
		// a*d - c*b with 2^26-sized integers: each product needs 52 bits plus a sign, and the
		// difference cancels most of them.
		val random = Random(29)
		val out = DoubleArray(4)
		repeat(20_000) {
			val a = random.nextLong(-(1L shl 29), 1L shl 29)
			val b = random.nextLong(-(1L shl 29), 1L shl 29)
			val c = random.nextLong(-(1L shl 29), 1L shl 29)
			val d = random.nextLong(-(1L shl 29), 1L shl 29)
			crossProduct(a.toDouble(), b.toDouble(), c.toDouble(), d.toDouble(), out)
			assertEquals(a * d - c * b, integerValueOf(out, 4), "$a * $d - $c * $b")
		}
	}

	/**
	 * A two-component expansion of an integer: the nearest double plus the exact remainder.
	 *
	 * @param Long value The integer, below 2^62 in magnitude.
	 * @return DoubleArray The expansion, smallest component first.
	 */
	private fun expansionOf(value: Long): DoubleArray {
		val high = value.toDouble()
		val low = (value - high.toLong()).toDouble()
		return doubleArrayOf(low, high)
	}

	/**
	 * The exact integer an expansion of integer components represents.
	 *
	 * @param DoubleArray expansion The expansion.
	 * @param Int         length    Its component count.
	 * @return Long The sum of its components, each converted exactly.
	 */
	private fun integerValueOf(expansion: DoubleArray, length: Int): Long {
		var total = 0L
		for (componentIndex in 0 until length) {
			val component = expansion[componentIndex]
			assertEquals(component, component.toLong().toDouble(), "component $component is not an integer")
			total += component.toLong()
		}
		return total
	}

	private companion object {
		/** 2^-30, exactly. */
		const val TWO_TO_MINUS_30: Double = 1.0 / (1L shl 30)

		/** 2^-60, exactly. */
		const val TWO_TO_MINUS_60: Double = 1.0 / (1L shl 60)
	}
}