// Adaptive exact orient2d and incircle, ported from robust-predicates (Vladimir Agafonkin, Unlicense /
// public domain), itself a port of Jonathan Richard Shewchuk's public-domain predicates.c ("Adaptive
// Precision Floating-Point Arithmetic and Fast Robust Geometric Predicates", 1997).  See CREDITS.md.
// robust-predicates negates orient2d for a downward y axis; this port keeps Shewchuk's original sign.
// Variable names follow Shewchuk's so each step can be checked against the paper.

package org.umamo.geometry.predicate

import kotlin.math.abs

/*
 * Both predicates return a double whose SIGN is exact for any finite input (barring underflow below
 * ~1e-150, which Shewchuk's error analysis excludes); its magnitude is only an approximation.  Each
 * first evaluates the determinant in plain double arithmetic and returns it when an a-priori error
 * bound proves the sign; only near-degenerate input (collinear or cocircular to within rounding) falls
 * through to the adaptive stages, which use the expansion arithmetic in ExpansionArithmetic.kt.
 *
 * Sign conventions (Shewchuk's): orient2d is positive when (b - a) x (c - a) > 0 - a, b, c turn
 * counterclockwise with y up, so clockwise ON SCREEN in a y-down frame such as an art raster or the
 * canvas.  incircle is positive when d lies strictly inside the circle through a, b, c, provided
 * orient2d(a, b, c) > 0 (the sign reverses otherwise).
 */

/** Fast-path error bound of orient2d. */
private const val CCW_ERROR_BOUND_A: Double = (3.0 + 16.0 * MACHINE_EPSILON) * MACHINE_EPSILON

/** Second-stage error bound of orient2d. */
private const val CCW_ERROR_BOUND_B: Double = (2.0 + 12.0 * MACHINE_EPSILON) * MACHINE_EPSILON

/** Third-stage error bound of orient2d. */
private const val CCW_ERROR_BOUND_C: Double = (9.0 + 64.0 * MACHINE_EPSILON) * MACHINE_EPSILON * MACHINE_EPSILON

/** Fast-path error bound of incircle. */
private const val ICC_ERROR_BOUND_A: Double = (10.0 + 96.0 * MACHINE_EPSILON) * MACHINE_EPSILON

/** Second-stage error bound of incircle. */
private const val ICC_ERROR_BOUND_B: Double = (4.0 + 48.0 * MACHINE_EPSILON) * MACHINE_EPSILON

/** Third-stage error bound of incircle. */
private const val ICC_ERROR_BOUND_C: Double = (44.0 + 576.0 * MACHINE_EPSILON) * MACHINE_EPSILON * MACHINE_EPSILON

/**
 * The orientation of three points: positive when a, b, c turn counterclockwise with y up (clockwise
 * on screen when y points down), negative when they turn the other way, zero exactly when they are
 * collinear.  The sign is exact.
 *
 * @param Double ax The first point's x.
 * @param Double ay The first point's y.
 * @param Double bx The second point's x.
 * @param Double by The second point's y.
 * @param Double cx The third point's x.
 * @param Double cy The third point's y.
 * @return Double A value with the exact sign of (b - a) x (c - a); its magnitude approximates twice the triangle's signed area.
 */
public fun orient2d(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double): Double = orient2dWith(ax, ay, bx, by, cx, cy, null)

/**
 * [orient2d] with a caller-owned scratch for the adaptive stages, so a hot loop never allocates.
 *
 * @param Double           ax      The first point's x.
 * @param Double           ay      The first point's y.
 * @param Double           bx      The second point's x.
 * @param Double           by      The second point's y.
 * @param Double           cx      The third point's x.
 * @param Double           cy      The third point's y.
 * @param PredicateScratch scratch Buffers confined to the calling thread.
 * @return Double A value with the exact sign of (b - a) x (c - a).
 */
internal fun orient2d(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double, scratch: PredicateScratch): Double = orient2dWith(ax, ay, bx, by, cx, cy, scratch)

/**
 * Whether d lies inside the circle through a, b, c: positive strictly inside, negative strictly
 * outside, zero exactly on it - when orient2d(a, b, c) > 0; the sign reverses otherwise.  The sign is
 * exact.
 *
 * @param Double ax The first circle point's x.
 * @param Double ay The first circle point's y.
 * @param Double bx The second circle point's x.
 * @param Double by The second circle point's y.
 * @param Double cx The third circle point's x.
 * @param Double cy The third circle point's y.
 * @param Double dx The query point's x.
 * @param Double dy The query point's y.
 * @return Double A value with the exact sign of the incircle determinant.
 */
public fun incircle(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double, dx: Double, dy: Double): Double = incircleWith(ax, ay, bx, by, cx, cy, dx, dy, null)

/**
 * [incircle] with a caller-owned scratch for the adaptive stages, so a hot loop never allocates.
 *
 * @param Double           ax      The first circle point's x.
 * @param Double           ay      The first circle point's y.
 * @param Double           bx      The second circle point's x.
 * @param Double           by      The second circle point's y.
 * @param Double           cx      The third circle point's x.
 * @param Double           cy      The third circle point's y.
 * @param Double           dx      The query point's x.
 * @param Double           dy      The query point's y.
 * @param PredicateScratch scratch Buffers confined to the calling thread.
 * @return Double A value with the exact sign of the incircle determinant.
 */
internal fun incircle(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double, dx: Double, dy: Double, scratch: PredicateScratch): Double = incircleWith(ax, ay, bx, by, cx, cy, dx, dy, scratch)

/**
 * The adaptive stages' working buffers.  Never shared between threads: the public predicates make a
 * fresh one only when the fast filter fails, and a triangulation owns one for its whole run (lattice
 * input lands on exact zeros constantly, so its slow path is hot).
 */
internal class PredicateScratch {
	/** orient2d's buffers, small enough to allocate eagerly. */
	val orient: Orient2dBuffers = Orient2dBuffers()

	private var incircleBuffers: IncircleBuffers? = null

	/** incircle's buffers (about 20 KB), allocated on first use. */
	val incircle: IncircleBuffers
		get() {
			val existing = incircleBuffers

			if (existing != null) {
				return existing
			}

			val created = IncircleBuffers()

			incircleBuffers = created

			return created
		}
}

/** orient2d's adaptive buffers, named after Shewchuk's. */
internal class Orient2dBuffers {
	val crossB: DoubleArray = DoubleArray(4)
	val sumC1: DoubleArray = DoubleArray(8)
	val sumC2: DoubleArray = DoubleArray(12)
	val sumD: DoubleArray = DoubleArray(16)
	val crossU: DoubleArray = DoubleArray(4)
}

/** incircle's adaptive buffers, named after Shewchuk's (bc, aa, axtbc, ...). */
internal class IncircleBuffers {
	val crossBc: DoubleArray = DoubleArray(4)
	val crossCa: DoubleArray = DoubleArray(4)
	val crossAb: DoubleArray = DoubleArray(4)
	val liftA: DoubleArray = DoubleArray(4)
	val liftB: DoubleArray = DoubleArray(4)
	val liftC: DoubleArray = DoubleArray(4)
	val productSumU: DoubleArray = DoubleArray(4)
	val productSumV: DoubleArray = DoubleArray(4)
	val adxTailBc: DoubleArray = DoubleArray(8)
	val adyTailBc: DoubleArray = DoubleArray(8)
	val bdxTailCa: DoubleArray = DoubleArray(8)
	val bdyTailCa: DoubleArray = DoubleArray(8)
	val cdxTailAb: DoubleArray = DoubleArray(8)
	val cdyTailAb: DoubleArray = DoubleArray(8)
	val tailCrossAb: DoubleArray = DoubleArray(8)
	val tailCrossBc: DoubleArray = DoubleArray(8)
	val tailCrossCa: DoubleArray = DoubleArray(8)
	val tailTailAb: DoubleArray = DoubleArray(4)
	val tailTailBc: DoubleArray = DoubleArray(4)
	val tailTailCa: DoubleArray = DoubleArray(4)
	val buffer8: DoubleArray = DoubleArray(8)
	val buffer16: DoubleArray = DoubleArray(16)
	val buffer16b: DoubleArray = DoubleArray(16)
	val buffer16c: DoubleArray = DoubleArray(16)
	val buffer32: DoubleArray = DoubleArray(32)
	val buffer32b: DoubleArray = DoubleArray(32)
	val buffer48: DoubleArray = DoubleArray(48)
	val buffer64: DoubleArray = DoubleArray(64)

	/** The running total (Shewchuk's fin); swapped with [finalSwap] by [finalAdd]. */
	var finalTotal: DoubleArray = DoubleArray(FINAL_CAPACITY)
		private set

	private var finalSwap: DoubleArray = DoubleArray(FINAL_CAPACITY)

	/**
	 * Adds an expansion into the running total (Shewchuk's finadd).
	 *
	 * @param Int         totalLength The running total's current length.
	 * @param Int         addedLength The added expansion's length.
	 * @param DoubleArray added       The added expansion.
	 * @return Int The running total's new length.
	 */
	fun finalAdd(totalLength: Int, addedLength: Int, added: DoubleArray): Int {
		val length = expansionSum(totalLength, finalTotal, addedLength, added, finalSwap)
		val previous = finalTotal

		finalTotal = finalSwap
		finalSwap = previous

		return length
	}

	/**
	 * Starts the running total from an expansion summed into it.
	 *
	 * @param Int         firstLength  The first expansion's length.
	 * @param DoubleArray first        The first expansion.
	 * @param Int         secondLength The second expansion's length.
	 * @param DoubleArray second       The second expansion.
	 * @return Int The running total's length.
	 */
	fun finalStart(firstLength: Int, first: DoubleArray, secondLength: Int, second: DoubleArray): Int = expansionSum(firstLength, first, secondLength, second, finalTotal)

	private companion object {
		/** Shewchuk's bound on incircle's final expansion. */
		const val FINAL_CAPACITY = 1152
	}
}

/**
 * The shared body of both orient2d overloads.
 *
 * @param Double            ax      The first point's x.
 * @param Double            ay      The first point's y.
 * @param Double            bx      The second point's x.
 * @param Double            by      The second point's y.
 * @param Double            cx      The third point's x.
 * @param Double            cy      The third point's y.
 * @param PredicateScratch? scratch Caller-owned buffers, or null to allocate on the slow path.
 * @return Double A value with the exact sign of (b - a) x (c - a).
 */
private fun orient2dWith(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double, scratch: PredicateScratch?): Double {
	val detLeft = (ax - cx) * (by - cy)
	val detRight = (ay - cy) * (bx - cx)
	val determinant = detLeft - detRight
	val detSum = abs(detLeft + detRight)

	if (abs(determinant) >= CCW_ERROR_BOUND_A * detSum) {
		return determinant
	}

	return orient2dAdaptive(ax, ay, bx, by, cx, cy, detSum, (scratch ?: PredicateScratch()).orient)
}

/**
 * orient2d's adaptive stages (Shewchuk's orient2dadapt): an exact expansion of the cofactor products,
 * refined only as far as the sign needs.
 *
 * @param Double          ax      The first point's x.
 * @param Double          ay      The first point's y.
 * @param Double          bx      The second point's x.
 * @param Double          by      The second point's y.
 * @param Double          cx      The third point's x.
 * @param Double          cy      The third point's y.
 * @param Double          detSum  The fast path's |detLeft + detRight|, scaling the error bounds.
 * @param Orient2dBuffers buffers The working buffers.
 * @return Double A value with the exact sign of the determinant.
 */
private fun orient2dAdaptive(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double, detSum: Double, buffers: Orient2dBuffers): Double {
	val acx = ax - cx
	val bcx = bx - cx
	val acy = ay - cy
	val bcy = by - cy

	crossProduct(acx, bcx, acy, bcy, buffers.crossB)

	var determinant = estimate(4, buffers.crossB)
	var errorBound = CCW_ERROR_BOUND_B * detSum

	if (determinant >= errorBound || -determinant >= errorBound) {
		return determinant
	}

	val acxTail = twoDiffTail(ax, cx, acx)
	val bcxTail = twoDiffTail(bx, cx, bcx)
	val acyTail = twoDiffTail(ay, cy, acy)
	val bcyTail = twoDiffTail(by, cy, bcy)

	if (acxTail == 0.0 && acyTail == 0.0 && bcxTail == 0.0 && bcyTail == 0.0) {
		return determinant
	}

	errorBound = CCW_ERROR_BOUND_C * detSum + RESULT_ERROR_BOUND * abs(determinant)
	determinant += (acx * bcyTail + bcy * acxTail) - (acy * bcxTail + bcx * acyTail)

	if (determinant >= errorBound || -determinant >= errorBound) {
		return determinant
	}

	crossProduct(acxTail, bcx, acyTail, bcy, buffers.crossU)
	val c1Length = expansionSum(4, buffers.crossB, 4, buffers.crossU, buffers.sumC1)
	crossProduct(acx, bcxTail, acy, bcyTail, buffers.crossU)
	val c2Length = expansionSum(c1Length, buffers.sumC1, 4, buffers.crossU, buffers.sumC2)
	crossProduct(acxTail, bcxTail, acyTail, bcyTail, buffers.crossU)
	val dLength = expansionSum(c2Length, buffers.sumC2, 4, buffers.crossU, buffers.sumD)

	return buffers.sumD[dLength - 1]
}

/**
 * The shared body of both incircle overloads.
 *
 * @param Double            ax      The first circle point's x.
 * @param Double            ay      The first circle point's y.
 * @param Double            bx      The second circle point's x.
 * @param Double            by      The second circle point's y.
 * @param Double            cx      The third circle point's x.
 * @param Double            cy      The third circle point's y.
 * @param Double            dx      The query point's x.
 * @param Double            dy      The query point's y.
 * @param PredicateScratch? scratch Caller-owned buffers, or null to allocate on the slow path.
 * @return Double A value with the exact sign of the incircle determinant.
 */
private fun incircleWith(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double, dx: Double, dy: Double, scratch: PredicateScratch?): Double {
	val adx = ax - dx
	val bdx = bx - dx
	val cdx = cx - dx
	val ady = ay - dy
	val bdy = by - dy
	val cdy = cy - dy

	val bdxcdy = bdx * cdy
	val cdxbdy = cdx * bdy
	val alift = adx * adx + ady * ady

	val cdxady = cdx * ady
	val adxcdy = adx * cdy
	val blift = bdx * bdx + bdy * bdy

	val adxbdy = adx * bdy
	val bdxady = bdx * ady
	val clift = cdx * cdx + cdy * cdy

	val determinant =
		alift * (bdxcdy - cdxbdy) +
			blift * (cdxady - adxcdy) +
			clift * (adxbdy - bdxady)

	val permanent =
		(abs(bdxcdy) + abs(cdxbdy)) * alift +
			(abs(cdxady) + abs(adxcdy)) * blift +
			(abs(adxbdy) + abs(bdxady)) * clift

	val errorBound = ICC_ERROR_BOUND_A * permanent

	if (determinant > errorBound || -determinant > errorBound) {
		return determinant
	}

	return incircleAdaptive(ax, ay, bx, by, cx, cy, dx, dy, permanent, (scratch ?: PredicateScratch()).incircle)
}

/**
 * incircle's adaptive stages (Shewchuk's incircleadapt).
 *
 * @param Double          ax        The first circle point's x.
 * @param Double          ay        The first circle point's y.
 * @param Double          bx        The second circle point's x.
 * @param Double          by        The second circle point's y.
 * @param Double          cx        The third circle point's x.
 * @param Double          cy        The third circle point's y.
 * @param Double          dx        The query point's x.
 * @param Double          dy        The query point's y.
 * @param Double          permanent The fast path's permanent, scaling the error bounds.
 * @param IncircleBuffers buffers   The working buffers.
 * @return Double A value with the exact sign of the determinant.
 */
private fun incircleAdaptive(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double, dx: Double, dy: Double, permanent: Double, buffers: IncircleBuffers): Double {
	val adx = ax - dx
	val bdx = bx - dx
	val cdx = cx - dx
	val ady = ay - dy
	val bdy = by - dy
	val cdy = cy - dy

	crossProduct(bdx, bdy, cdx, cdy, buffers.crossBc)
	crossProduct(cdx, cdy, adx, ady, buffers.crossCa)
	crossProduct(adx, ady, bdx, bdy, buffers.crossAb)

	val aLength = buffers.liftedCross(buffers.crossBc, adx, ady, buffers.buffer32)
	val bLength = buffers.liftedCross(buffers.crossCa, bdx, bdy, buffers.buffer32b)
	val abLength = expansionSum(aLength, buffers.buffer32, bLength, buffers.buffer32b, buffers.buffer64)
	val cLength = buffers.liftedCross(buffers.crossAb, cdx, cdy, buffers.buffer32)
	var finalLength = buffers.finalStart(abLength, buffers.buffer64, cLength, buffers.buffer32)

	var determinant = estimate(finalLength, buffers.finalTotal)
	var errorBound = ICC_ERROR_BOUND_B * permanent

	if (determinant >= errorBound || -determinant >= errorBound) {
		return determinant
	}

	val adxTail = twoDiffTail(ax, dx, adx)
	val adyTail = twoDiffTail(ay, dy, ady)
	val bdxTail = twoDiffTail(bx, dx, bdx)
	val bdyTail = twoDiffTail(by, dy, bdy)
	val cdxTail = twoDiffTail(cx, dx, cdx)
	val cdyTail = twoDiffTail(cy, dy, cdy)

	if (adxTail == 0.0 && bdxTail == 0.0 && cdxTail == 0.0 && adyTail == 0.0 && bdyTail == 0.0 && cdyTail == 0.0) {
		return determinant
	}

	errorBound = ICC_ERROR_BOUND_C * permanent + RESULT_ERROR_BOUND * abs(determinant)
	determinant += (
		(adx * adx + ady * ady) * ((bdx * cdyTail + cdy * bdxTail) - (bdy * cdxTail + cdx * bdyTail)) +
			2.0 * (adx * adxTail + ady * adyTail) * (bdx * cdy - bdy * cdx)
	) +
		(
			(bdx * bdx + bdy * bdy) * ((cdx * adyTail + ady * cdxTail) - (cdy * adxTail + adx * cdyTail)) +
				2.0 * (bdx * bdxTail + bdy * bdyTail) * (cdx * ady - cdy * adx)
		) +
		(
			(cdx * cdx + cdy * cdy) * ((adx * bdyTail + bdy * adxTail) - (ady * bdxTail + bdx * adyTail)) +
				2.0 * (cdx * cdxTail + cdy * cdyTail) * (adx * bdy - ady * bdx)
		)

	if (determinant >= errorBound || -determinant >= errorBound) {
		return determinant
	}

	if (bdxTail != 0.0 || bdyTail != 0.0 || cdxTail != 0.0 || cdyTail != 0.0) {
		squareSum(adx, ady, buffers.liftA)
	}

	if (cdxTail != 0.0 || cdyTail != 0.0 || adxTail != 0.0 || adyTail != 0.0) {
		squareSum(bdx, bdy, buffers.liftB)
	}

	if (adxTail != 0.0 || adyTail != 0.0 || bdxTail != 0.0 || bdyTail != 0.0) {
		squareSum(cdx, cdy, buffers.liftC)
	}

	var adxTailBcLength = 0
	var adyTailBcLength = 0
	var bdxTailCaLength = 0
	var bdyTailCaLength = 0
	var cdxTailAbLength = 0
	var cdyTailAbLength = 0

	if (adxTail != 0.0) {
		adxTailBcLength = scaleExpansion(4, buffers.crossBc, adxTail, buffers.adxTailBc)
		finalLength = buffers.addFirstOrderTailTerm(finalLength, adxTailBcLength, buffers.adxTailBc, 2.0 * adx, buffers.liftC, adxTail, bdy, buffers.liftB, -cdy)
	}

	if (adyTail != 0.0) {
		adyTailBcLength = scaleExpansion(4, buffers.crossBc, adyTail, buffers.adyTailBc)
		finalLength = buffers.addFirstOrderTailTerm(finalLength, adyTailBcLength, buffers.adyTailBc, 2.0 * ady, buffers.liftB, adyTail, cdx, buffers.liftC, -bdx)
	}

	if (bdxTail != 0.0) {
		bdxTailCaLength = scaleExpansion(4, buffers.crossCa, bdxTail, buffers.bdxTailCa)
		finalLength = buffers.addFirstOrderTailTerm(finalLength, bdxTailCaLength, buffers.bdxTailCa, 2.0 * bdx, buffers.liftA, bdxTail, cdy, buffers.liftC, -ady)
	}

	if (bdyTail != 0.0) {
		bdyTailCaLength = scaleExpansion(4, buffers.crossCa, bdyTail, buffers.bdyTailCa)
		finalLength = buffers.addFirstOrderTailTerm(finalLength, bdyTailCaLength, buffers.bdyTailCa, 2.0 * bdy, buffers.liftC, bdyTail, adx, buffers.liftA, -cdx)
	}

	if (cdxTail != 0.0) {
		cdxTailAbLength = scaleExpansion(4, buffers.crossAb, cdxTail, buffers.cdxTailAb)
		finalLength = buffers.addFirstOrderTailTerm(finalLength, cdxTailAbLength, buffers.cdxTailAb, 2.0 * cdx, buffers.liftB, cdxTail, ady, buffers.liftA, -bdy)
	}

	if (cdyTail != 0.0) {
		cdyTailAbLength = scaleExpansion(4, buffers.crossAb, cdyTail, buffers.cdyTailAb)
		finalLength = buffers.addFirstOrderTailTerm(finalLength, cdyTailAbLength, buffers.cdyTailAb, 2.0 * cdy, buffers.liftA, cdyTail, bdx, buffers.liftB, -adx)
	}

	if (adxTail != 0.0 || adyTail != 0.0) {
		val tailCrossBcLength: Int
		val tailTailBcLength: Int

		if (bdxTail != 0.0 || bdyTail != 0.0 || cdxTail != 0.0 || cdyTail != 0.0) {
			twoProductSum(bdxTail, cdy, bdx, cdyTail, buffers.productSumU)
			twoProductSum(cdxTail, -bdy, cdx, -bdyTail, buffers.productSumV)
			tailCrossBcLength = expansionSum(4, buffers.productSumU, 4, buffers.productSumV, buffers.tailCrossBc)
			crossProduct(bdxTail, bdyTail, cdxTail, cdyTail, buffers.tailTailBc)
			tailTailBcLength = 4
		} else {
			buffers.tailCrossBc[0] = 0.0
			tailCrossBcLength = 1
			buffers.tailTailBc[0] = 0.0
			tailTailBcLength = 1
		}

		if (adxTail != 0.0) {
			finalLength = buffers.addSecondOrderTailTerms(finalLength, adxTail, adxTailBcLength, buffers.adxTailBc, tailCrossBcLength, buffers.tailCrossBc, tailTailBcLength, buffers.tailTailBc, 2.0 * adx)
			if (bdyTail != 0.0) {
				finalLength = buffers.addScaledTwice(finalLength, buffers.liftC, adxTail, bdyTail)
			}
			if (cdyTail != 0.0) {
				finalLength = buffers.addScaledTwice(finalLength, buffers.liftB, -adxTail, cdyTail)
			}
		}

		if (adyTail != 0.0) {
			finalLength = buffers.addSecondOrderTailTerms(finalLength, adyTail, adyTailBcLength, buffers.adyTailBc, tailCrossBcLength, buffers.tailCrossBc, tailTailBcLength, buffers.tailTailBc, 2.0 * ady)
		}
	}
	if (bdxTail != 0.0 || bdyTail != 0.0) {
		val tailCrossCaLength: Int
		val tailTailCaLength: Int

		if (cdxTail != 0.0 || cdyTail != 0.0 || adxTail != 0.0 || adyTail != 0.0) {
			twoProductSum(cdxTail, ady, cdx, adyTail, buffers.productSumU)
			twoProductSum(adxTail, -cdy, adx, -cdyTail, buffers.productSumV)
			tailCrossCaLength = expansionSum(4, buffers.productSumU, 4, buffers.productSumV, buffers.tailCrossCa)
			crossProduct(cdxTail, cdyTail, adxTail, adyTail, buffers.tailTailCa)
			tailTailCaLength = 4
		} else {
			buffers.tailCrossCa[0] = 0.0
			tailCrossCaLength = 1
			buffers.tailTailCa[0] = 0.0
			tailTailCaLength = 1
		}

		if (bdxTail != 0.0) {
			finalLength = buffers.addSecondOrderTailTerms(finalLength, bdxTail, bdxTailCaLength, buffers.bdxTailCa, tailCrossCaLength, buffers.tailCrossCa, tailTailCaLength, buffers.tailTailCa, 2.0 * bdx)
			if (cdyTail != 0.0) {
				finalLength = buffers.addScaledTwice(finalLength, buffers.liftA, bdxTail, cdyTail)
			}
			if (adyTail != 0.0) {
				finalLength = buffers.addScaledTwice(finalLength, buffers.liftC, -bdxTail, adyTail)
			}
		}

		if (bdyTail != 0.0) {
			finalLength = buffers.addSecondOrderTailTerms(finalLength, bdyTail, bdyTailCaLength, buffers.bdyTailCa, tailCrossCaLength, buffers.tailCrossCa, tailTailCaLength, buffers.tailTailCa, 2.0 * bdy)
		}
	}
	if (cdxTail != 0.0 || cdyTail != 0.0) {
		val tailCrossAbLength: Int
		val tailTailAbLength: Int

		if (adxTail != 0.0 || adyTail != 0.0 || bdxTail != 0.0 || bdyTail != 0.0) {
			twoProductSum(adxTail, bdy, adx, bdyTail, buffers.productSumU)
			twoProductSum(bdxTail, -ady, bdx, -adyTail, buffers.productSumV)
			tailCrossAbLength = expansionSum(4, buffers.productSumU, 4, buffers.productSumV, buffers.tailCrossAb)
			crossProduct(adxTail, adyTail, bdxTail, bdyTail, buffers.tailTailAb)
			tailTailAbLength = 4
		} else {
			buffers.tailCrossAb[0] = 0.0
			tailCrossAbLength = 1
			buffers.tailTailAb[0] = 0.0
			tailTailAbLength = 1
		}

		if (cdxTail != 0.0) {
			finalLength = buffers.addSecondOrderTailTerms(finalLength, cdxTail, cdxTailAbLength, buffers.cdxTailAb, tailCrossAbLength, buffers.tailCrossAb, tailTailAbLength, buffers.tailTailAb, 2.0 * cdx)
			if (adyTail != 0.0) {
				finalLength = buffers.addScaledTwice(finalLength, buffers.liftB, cdxTail, adyTail)
			}
			if (bdyTail != 0.0) {
				finalLength = buffers.addScaledTwice(finalLength, buffers.liftA, -cdxTail, bdyTail)
			}
		}

		if (cdyTail != 0.0) {
			finalLength = buffers.addSecondOrderTailTerms(finalLength, cdyTail, cdyTailAbLength, buffers.cdyTailAb, tailCrossAbLength, buffers.tailCrossAb, tailTailAbLength, buffers.tailTailAb, 2.0 * cdy)
		}
	}

	return buffers.finalTotal[finalLength - 1]
}

/**
 * Writes `cross * deltaX^2 + cross * deltaY^2` - one lifted row of the incircle determinant.
 *
 * @param DoubleArray cross  The four-component cross-product expansion.
 * @param Double      deltaX The row point's x offset from d.
 * @param Double      deltaY The row point's y offset from d.
 * @param DoubleArray out    Receives the sum (needs 32 slots).
 * @return Int The number of components written.
 */
private fun IncircleBuffers.liftedCross(cross: DoubleArray, deltaX: Double, deltaY: Double, out: DoubleArray): Int {
	val xLength = scaleTwice(4, cross, deltaX, deltaX, buffer16)
	val yLength = scaleTwice(4, cross, deltaY, deltaY, buffer16b)

	return expansionSum(xLength, buffer16, yLength, buffer16b, out)
}

/**
 * Scales an expansion by two factors in turn through [IncircleBuffers.buffer8].
 *
 * @param Int         length    The expansion's length.
 * @param DoubleArray expansion The expansion (four components at most).
 * @param Double      first     The first factor.
 * @param Double      second    The second factor.
 * @param DoubleArray out       Receives the product (needs 16 slots).
 * @return Int The number of components written.
 */
private fun IncircleBuffers.scaleTwice(length: Int, expansion: DoubleArray, first: Double, second: Double, out: DoubleArray): Int {
	val intermediateLength = scaleExpansion(length, expansion, first, buffer8)

	return scaleExpansion(intermediateLength, buffer8, second, out)
}

/**
 * Adds `lift * first * second` into the running total.
 *
 * @param Int         finalLength The running total's length.
 * @param DoubleArray lift        A four-component squared-length expansion.
 * @param Double      first       The first factor.
 * @param Double      second      The second factor.
 * @return Int The running total's new length.
 */
private fun IncircleBuffers.addScaledTwice(finalLength: Int, lift: DoubleArray, first: Double, second: Double): Int {
	val length = scaleTwice(4, lift, first, second, buffer16)

	return finalAdd(finalLength, length, buffer16)
}

/**
 * Adds one first-order tail term: `tailCross * doubledDelta + firstLift * tail * firstFactor +
 * secondLift * tail * secondFactor` (one of incircleadapt's six adxtail .. cdytail blocks).
 *
 * @param Int         finalLength     The running total's length.
 * @param Int         tailCrossLength The length of [tailCross] (the cross product scaled by the tail).
 * @param DoubleArray tailCross       The cross product scaled by the tail.
 * @param Double      doubledDelta    Twice the matching coordinate offset.
 * @param DoubleArray firstLift       The first squared-length expansion.
 * @param Double      tail            The coordinate tail.
 * @param Double      firstFactor     The first lift's other factor.
 * @param DoubleArray secondLift      The second squared-length expansion.
 * @param Double      secondFactor    The second lift's other factor.
 * @return Int The running total's new length.
 */
private fun IncircleBuffers.addFirstOrderTailTerm(finalLength: Int, tailCrossLength: Int, tailCross: DoubleArray, doubledDelta: Double, firstLift: DoubleArray, tail: Double, firstFactor: Double, secondLift: DoubleArray, secondFactor: Double): Int {
	val crossLength = scaleExpansion(tailCrossLength, tailCross, doubledDelta, buffer16)
	val firstLength = scaleTwice(4, firstLift, tail, firstFactor, buffer16b)
	val secondLength = scaleTwice(4, secondLift, tail, secondFactor, buffer16c)
	val partialLength = expansionSum(crossLength, buffer16, firstLength, buffer16b, buffer32)
	val termLength = expansionSum(partialLength, buffer32, secondLength, buffer16c, buffer48)

	return finalAdd(finalLength, termLength, buffer48)
}

/**
 * Adds one coordinate tail's second-order terms (the body each `if (adxtail !== 0)` repeats in the
 * second half of incircleadapt).
 *
 * @param Int         finalLength         The running total's length.
 * @param Double      tail                The coordinate tail.
 * @param Int         tailCrossLength     The length of the cross product scaled by the tail.
 * @param DoubleArray tailCross           The cross product scaled by the tail.
 * @param Int         crossTailLength     The length of the tails' cross expansion.
 * @param DoubleArray crossTail           The tails' cross expansion (bct and friends).
 * @param Int         crossTailTailLength The length of the tail-by-tail cross product.
 * @param DoubleArray crossTailTail       The tail-by-tail cross product (bctt and friends).
 * @param Double      doubledDelta        Twice the matching coordinate offset.
 * @return Int The running total's new length.
 */
private fun IncircleBuffers.addSecondOrderTailTerms(
	finalLength: Int,
	tail: Double,
	tailCrossLength: Int,
	tailCross: DoubleArray,
	crossTailLength: Int,
	crossTail: DoubleArray,
	crossTailTailLength: Int,
	crossTailTail: DoubleArray,
	doubledDelta: Double,
): Int {
	val scaledCrossTailLength = scaleExpansion(crossTailLength, crossTail, tail, buffer16c)
	val firstLength = scaleExpansion(tailCrossLength, tailCross, tail, buffer16)
	val secondLength = scaleExpansion(scaledCrossTailLength, buffer16c, doubledDelta, buffer32)
	var length = finalAdd(finalLength, expansionSum(firstLength, buffer16, secondLength, buffer32, buffer48), buffer48)

	val scaledTailTailLength = scaleExpansion(crossTailTailLength, crossTailTail, tail, buffer8)
	val doubledLength = scaleExpansion(scaledTailTailLength, buffer8, doubledDelta, buffer16)
	val tailedLength = scaleExpansion(scaledTailTailLength, buffer8, tail, buffer16b)
	val crossTailedLength = scaleExpansion(scaledCrossTailLength, buffer16c, tail, buffer32)
	val partialLength = expansionSum(doubledLength, buffer16, tailedLength, buffer16b, buffer32b)
	val termLength = expansionSum(partialLength, buffer32b, crossTailedLength, buffer32, buffer64)

	length = finalAdd(length, termLength, buffer64)

	return length
}