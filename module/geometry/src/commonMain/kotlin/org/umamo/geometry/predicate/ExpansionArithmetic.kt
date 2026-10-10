// Exact floating-point expansion arithmetic, ported from robust-predicates (Vladimir Agafonkin,
// Unlicense / public domain), itself a port of Jonathan Richard Shewchuk's public-domain predicates.c
// ("Adaptive Precision Floating-Point Arithmetic and Fast Robust Geometric Predicates", 1997).  See
// CREDITS.md.  Variable names follow Shewchuk's so each step can be checked against the paper.

package org.umamo.geometry.predicate

/*
 * An EXPANSION is a sum of doubles whose components are nonoverlapping and ordered by increasing
 * magnitude, so it represents a real number exactly.  Every routine here relies on each `+`, `-`, and
 * `*` rounding on its own under round-to-nearest-even: the JVM guarantees that, and the linuxX64 test
 * target checks Kotlin/Native keeps it (a fused multiply-add would break every tail below).
 *
 * Shewchuk's macros return two values; here each is a TAIL function that takes the already-rounded
 * result and returns the roundoff error, so nothing allocates a pair on the hot path.
 */

/** Half an ulp of 1.0 (2^-53): the relative error bound of one correctly rounded operation. */
internal const val MACHINE_EPSILON: Double = 1.1102230246251565e-16

/** 2^27 + 1, the Dekker / Veltkamp constant that splits a double into two 26-bit halves. */
internal const val SPLITTER: Double = 134217729.0

/** The error bound on the final approximation an adaptive predicate returns. */
internal const val RESULT_ERROR_BOUND: Double = (3.0 + 8.0 * MACHINE_EPSILON) * MACHINE_EPSILON

/**
 * The roundoff of `first + second` when `|first| >= |second|` (Shewchuk's Fast_Two_Sum).
 *
 * @param Double first  The larger-magnitude addend.
 * @param Double second The smaller-magnitude addend.
 * @param Double sum    The rounded sum `first + second`.
 * @return Double The exact error, so `first + second == sum + tail` holds exactly.
 */
internal fun fastTwoSumTail(first: Double, second: Double, sum: Double): Double = second - (sum - first)

/**
 * The roundoff of `first + second` for addends of any magnitude (Shewchuk's Two_Sum).
 *
 * @param Double first  The first addend.
 * @param Double second The second addend.
 * @param Double sum    The rounded sum `first + second`.
 * @return Double The exact error, so `first + second == sum + tail` holds exactly.
 */
internal fun twoSumTail(first: Double, second: Double, sum: Double): Double {
	val secondVirtual = sum - first
	val firstVirtual = sum - secondVirtual
	val secondRoundoff = second - secondVirtual
	val firstRoundoff = first - firstVirtual

	return firstRoundoff + secondRoundoff
}

/**
 * The roundoff of `first - second` (Shewchuk's Two_Diff_Tail).
 *
 * @param Double first      The minuend.
 * @param Double second     The subtrahend.
 * @param Double difference The rounded difference `first - second`.
 * @return Double The exact error, so `first - second == difference + tail` holds exactly.
 */
internal fun twoDiffTail(first: Double, second: Double, difference: Double): Double {
	val secondVirtual = first - difference
	val firstVirtual = difference + secondVirtual
	val secondRoundoff = secondVirtual - second
	val firstRoundoff = first - firstVirtual

	return firstRoundoff + secondRoundoff
}

/**
 * The high half of a Dekker split (Shewchuk's Split); the low half is `value - high`.
 *
 * @param Double value The value to split.
 * @return Double The high half, holding at most 26 significant bits.
 */
internal fun splitHigh(value: Double): Double {
	val scaled = SPLITTER * value

	return scaled - (scaled - value)
}

/**
 * The roundoff of `first * second` (Shewchuk's Two_Product).
 *
 * @param Double first   The first factor.
 * @param Double second  The second factor.
 * @param Double product The rounded product `first * second`.
 * @return Double The exact error, so `first * second == product + tail` holds exactly.
 */
internal fun twoProductTail(first: Double, second: Double, product: Double): Double {
	val firstHigh = splitHigh(first)
	val firstLow = first - firstHigh
	val secondHigh = splitHigh(second)
	val secondLow = second - secondHigh

	return twoProductTailPresplit(firstHigh, firstLow, secondHigh, secondLow, product)
}

/**
 * The roundoff of a product whose factors are both already split (the shared body of Shewchuk's
 * Two_Product and Two_Product_Presplit).
 *
 * @param Double firstHigh  The high half of the first factor.
 * @param Double firstLow   The low half of the first factor.
 * @param Double secondHigh The high half of the second factor.
 * @param Double secondLow  The low half of the second factor.
 * @param Double product    The rounded product.
 * @return Double The exact error of the product.
 */
internal fun twoProductTailPresplit(firstHigh: Double, firstLow: Double, secondHigh: Double, secondLow: Double, product: Double): Double {
	val firstError = product - firstHigh * secondHigh
	val secondError = firstError - firstLow * secondHigh
	val thirdError = secondError - firstHigh * secondLow

	return firstLow * secondLow - thirdError
}

/**
 * The roundoff of `value * value` (Shewchuk's Square).
 *
 * @param Double value  The value squared.
 * @param Double square The rounded square.
 * @return Double The exact error, so `value * value == square + tail` holds exactly.
 */
internal fun squareTail(value: Double, square: Double): Double {
	val high = splitHigh(value)
	val low = value - high
	val firstError = square - high * high
	val secondError = firstError - (high + high) * low

	return low * low - secondError
}

/**
 * Writes the four-component expansion of `(first1 + first0) - (second1 + second0)` (Shewchuk's
 * Two_Two_Diff), each pair being a two-component expansion.
 *
 * @param Double      first1  The high component of the minuend.
 * @param Double      first0  The low component of the minuend.
 * @param Double      second1 The high component of the subtrahend.
 * @param Double      second0 The low component of the subtrahend.
 * @param DoubleArray out     Receives the four components, smallest first, at indices 0..3.
 */
internal fun twoTwoDiff(first1: Double, first0: Double, second1: Double, second0: Double, out: DoubleArray) {
	// Two_One_Diff(first1, first0, second0) -> (upper, middle, out[0]).
	val lowDifference = first0 - second0
	out[0] = twoDiffTail(first0, second0, lowDifference)
	val upper = first1 + lowDifference
	val middle = twoSumTail(first1, lowDifference, upper)

	// Two_One_Diff(upper, middle, second1) -> (out[3], out[2], out[1]).
	val middleDifference = middle - second1
	out[1] = twoDiffTail(middle, second1, middleDifference)
	val top = upper + middleDifference
	out[2] = twoSumTail(upper, middleDifference, top)
	out[3] = top
}

/**
 * Writes the four-component expansion of `(first1 + first0) + (second1 + second0)` (Shewchuk's
 * Two_Two_Sum).
 *
 * @param Double      first1  The high component of the first expansion.
 * @param Double      first0  The low component of the first expansion.
 * @param Double      second1 The high component of the second expansion.
 * @param Double      second0 The low component of the second expansion.
 * @param DoubleArray out     Receives the four components, smallest first, at indices 0..3.
 */
internal fun twoTwoSum(first1: Double, first0: Double, second1: Double, second0: Double, out: DoubleArray) {
	// Two_One_Sum(first1, first0, second0) -> (upper, middle, out[0]).
	val lowSum = first0 + second0
	out[0] = twoSumTail(first0, second0, lowSum)
	val upper = first1 + lowSum
	val middle = twoSumTail(first1, lowSum, upper)

	// Two_One_Sum(upper, middle, second1) -> (out[3], out[2], out[1]).
	val middleSum = middle + second1
	out[1] = twoSumTail(middle, second1, middleSum)
	val top = upper + middleSum
	out[2] = twoSumTail(upper, middleSum, top)
	out[3] = top
}

/**
 * Writes the exact expansion of `a * d - c * b` (robust-predicates' Cross_Product).
 *
 * @param Double      a   The first factor of the positive product.
 * @param Double      b   The second factor of the negative product.
 * @param Double      c   The first factor of the negative product.
 * @param Double      d   The second factor of the positive product.
 * @param DoubleArray out Receives four components, smallest first.
 */
internal fun crossProduct(a: Double, b: Double, c: Double, d: Double, out: DoubleArray) {
	val positive = a * d
	val positiveTail = twoProductTail(a, d, positive)
	val negative = c * b
	val negativeTail = twoProductTail(c, b, negative)

	twoTwoDiff(positive, positiveTail, negative, negativeTail, out)
}

/**
 * Writes the exact expansion of `a * b + c * d` (robust-predicates' Two_Product_Sum).
 *
 * @param Double      a   The first factor of the first product.
 * @param Double      b   The second factor of the first product.
 * @param Double      c   The first factor of the second product.
 * @param Double      d   The second factor of the second product.
 * @param DoubleArray out Receives four components, smallest first.
 */
internal fun twoProductSum(a: Double, b: Double, c: Double, d: Double, out: DoubleArray) {
	val first = a * b
	val firstTail = twoProductTail(a, b, first)
	val second = c * d
	val secondTail = twoProductTail(c, d, second)

	twoTwoSum(first, firstTail, second, secondTail, out)
}

/**
 * Writes the exact expansion of `a * a + b * b` (robust-predicates' Square_Sum).
 *
 * @param Double      a   The first value squared.
 * @param Double      b   The second value squared.
 * @param DoubleArray out Receives four components, smallest first.
 */
internal fun squareSum(a: Double, b: Double, out: DoubleArray) {
	val first = a * a
	val firstTail = squareTail(a, first)
	val second = b * b
	val secondTail = squareTail(b, second)

	twoTwoSum(first, firstTail, second, secondTail, out)
}

/**
 * Sums two expansions, eliminating zero components (Shewchuk's fast_expansion_sum_zeroelim).
 *
 * The output may not alias either input.  Reads stop at each input's length; the original reads one
 * slot past the end, which C and JavaScript tolerate and a Kotlin array does not.
 *
 * @param Int         firstLength  The number of components of [first].
 * @param DoubleArray first        The first expansion.
 * @param Int         secondLength The number of components of [second].
 * @param DoubleArray second       The second expansion.
 * @param DoubleArray out          Receives the sum; needs room for firstLength + secondLength.
 * @return Int The number of components written to [out] (at least one).
 */
internal fun expansionSum(firstLength: Int, first: DoubleArray, secondLength: Int, second: DoubleArray, out: DoubleArray): Int {
	var firstIndex = 0
	var secondIndex = 0
	var firstNow = first[0]
	var secondNow = second[0]
	var accumulated: Double

	if ((secondNow > firstNow) == (secondNow > -firstNow)) {
		accumulated = firstNow
		firstIndex++
		firstNow = componentOrZero(first, firstIndex, firstLength)
	} else {
		accumulated = secondNow
		secondIndex++
		secondNow = componentOrZero(second, secondIndex, secondLength)
	}

	var outIndex = 0

	if (firstIndex < firstLength && secondIndex < secondLength) {
		val nextAccumulated: Double
		val tail: Double

		if ((secondNow > firstNow) == (secondNow > -firstNow)) {
			nextAccumulated = firstNow + accumulated
			tail = fastTwoSumTail(firstNow, accumulated, nextAccumulated)
			firstIndex++
			firstNow = componentOrZero(first, firstIndex, firstLength)
		} else {
			nextAccumulated = secondNow + accumulated
			tail = fastTwoSumTail(secondNow, accumulated, nextAccumulated)
			secondIndex++
			secondNow = componentOrZero(second, secondIndex, secondLength)
		}

		accumulated = nextAccumulated

		if (tail != 0.0) {
			out[outIndex] = tail
			outIndex++
		}

		while (firstIndex < firstLength && secondIndex < secondLength) {
			val sum: Double
			val sumTail: Double

			if ((secondNow > firstNow) == (secondNow > -firstNow)) {
				sum = accumulated + firstNow
				sumTail = twoSumTail(accumulated, firstNow, sum)
				firstIndex++
				firstNow = componentOrZero(first, firstIndex, firstLength)
			} else {
				sum = accumulated + secondNow
				sumTail = twoSumTail(accumulated, secondNow, sum)
				secondIndex++
				secondNow = componentOrZero(second, secondIndex, secondLength)
			}

			accumulated = sum

			if (sumTail != 0.0) {
				out[outIndex] = sumTail
				outIndex++
			}
		}
	}

	while (firstIndex < firstLength) {
		val sum = accumulated + firstNow
		val sumTail = twoSumTail(accumulated, firstNow, sum)

		firstIndex++
		firstNow = componentOrZero(first, firstIndex, firstLength)
		accumulated = sum

		if (sumTail != 0.0) {
			out[outIndex] = sumTail
			outIndex++
		}
	}

	while (secondIndex < secondLength) {
		val sum = accumulated + secondNow
		val sumTail = twoSumTail(accumulated, secondNow, sum)

		secondIndex++
		secondNow = componentOrZero(second, secondIndex, secondLength)
		accumulated = sum

		if (sumTail != 0.0) {
			out[outIndex] = sumTail
			outIndex++
		}
	}

	if (accumulated != 0.0 || outIndex == 0) {
		out[outIndex] = accumulated
		outIndex++
	}

	return outIndex
}

/**
 * Multiplies an expansion by a double, eliminating zero components (Shewchuk's
 * scale_expansion_zeroelim).  The output may not alias the input.
 *
 * @param Int         length    The number of components of [expansion].
 * @param DoubleArray expansion The expansion to scale.
 * @param Double      factor    The scale factor.
 * @param DoubleArray out       Receives the product; needs room for 2 * length.
 * @return Int The number of components written to [out] (at least one).
 */
internal fun scaleExpansion(length: Int, expansion: DoubleArray, factor: Double, out: DoubleArray): Int {
	val factorHigh = splitHigh(factor)
	val factorLow = factor - factorHigh
	var component = expansion[0]
	var accumulated = component * factor
	var tail = productTailBySplitFactor(component, factorHigh, factorLow, accumulated)
	var outIndex = 0

	if (tail != 0.0) {
		out[outIndex] = tail
		outIndex++
	}

	for (componentIndex in 1 until length) {
		component = expansion[componentIndex]
		val productHigh = component * factor
		val productLow = productTailBySplitFactor(component, factorHigh, factorLow, productHigh)
		val sum = accumulated + productLow
		tail = twoSumTail(accumulated, productLow, sum)

		if (tail != 0.0) {
			out[outIndex] = tail
			outIndex++
		}

		accumulated = productHigh + sum
		tail = fastTwoSumTail(productHigh, sum, accumulated)

		if (tail != 0.0) {
			out[outIndex] = tail
			outIndex++
		}
	}

	if (accumulated != 0.0 || outIndex == 0) {
		out[outIndex] = accumulated
		outIndex++
	}

	return outIndex
}

/**
 * The roundoff of `value * factor` when the factor is already split (Shewchuk's Two_Product_Presplit).
 *
 * @param Double value      The unsplit factor.
 * @param Double factorHigh The high half of the presplit factor.
 * @param Double factorLow  The low half of the presplit factor.
 * @param Double product    The rounded product.
 * @return Double The exact error of the product.
 */
private fun productTailBySplitFactor(value: Double, factorHigh: Double, factorLow: Double, product: Double): Double {
	val valueHigh = splitHigh(value)
	val valueLow = value - valueHigh

	return twoProductTailPresplit(valueHigh, valueLow, factorHigh, factorLow, product)
}

/**
 * Sums an expansion's components into one double, a one-ulp approximation of its value (Shewchuk's
 * estimate).
 *
 * @param Int         length    The number of components.
 * @param DoubleArray expansion The expansion.
 * @return Double The approximate value.
 */
internal fun estimate(length: Int, expansion: DoubleArray): Double {
	var total = expansion[0]

	for (componentIndex in 1 until length) {
		total += expansion[componentIndex]
	}

	return total
}

/**
 * One component of an expansion, or zero past its length - the value the original's out-of-bounds
 * read would have produced no use of, since the loop that reads it ends at the same check.
 *
 * @param DoubleArray expansion The expansion.
 * @param Int         index     The component index.
 * @param Int         length    The expansion's length.
 * @return Double The component, or 0.0 when [index] is past [length].
 */
private fun componentOrZero(expansion: DoubleArray, index: Int, length: Int): Double = if (index < length) expansion[index] else 0.0