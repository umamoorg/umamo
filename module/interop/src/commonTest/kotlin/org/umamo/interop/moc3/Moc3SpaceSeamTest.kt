package org.umamo.interop.moc3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The MOC3 coordinate seam round-trips to float precision.
 *
 * Every value an export writes goes through one of these inverses, so a discrepancy here is a
 * discrepancy in every exported position at once - and one that a semantic round trip cannot see,
 * because it would convert and unconvert with the same wrong pair and cancel.
 *
 * The rotation scale seam gets its own case because it is the one piece of this that is not a
 * per-value transform: which factor applies depends on a deformer's ANCESTRY, so the two directions
 * can agree on the arithmetic and still disagree on which deformers it applies to.
 */
class Moc3SpaceSeamTest {
	private val canvas = MocCanvasMapping(pixelsPerUnit = 900f, originX = 450f, originY = 600f)

	/** Values chosen to span sign, magnitude, and zero rather than a single happy sample. */
	private val points = floatArrayOf(0f, 0f, 1.5f, -2.25f, -1000f, 1000f, 0.001f, -0.001f)

	@Test
	fun pointsRoundTripThroughEverySpace() {
		for (space in PointSpace.entries) {
			val stored = convertPointsToMoc(space, convertPointsToRuntime(space, points, canvas), canvas)
			for (index in points.indices) {
				assertTrue(
					kotlin.math.abs(stored[index] - points[index]) <= 1e-3f,
					"$space point[$index]: ${points[index]} -> ${stored[index]}",
				)
			}
		}
	}

	@Test
	fun deltasRoundTripThroughEverySpace() {
		for (space in PointSpace.entries) {
			val stored = convertDeltasToMoc(space, convertDeltasToRuntime(space, points, canvas), canvas)
			for (index in points.indices) {
				assertTrue(
					kotlin.math.abs(stored[index] - points[index]) <= 1e-3f,
					"$space delta[$index]: ${points[index]} -> ${stored[index]}",
				)
			}
		}
	}

	/**
	 * A delta must NOT pick up the canvas origin - it cancels out of a difference - while a point must.
	 * Getting this wrong shifts every keyed offset by the origin, which looks like a plausible rig at
	 * the default pose and falls apart the moment a parameter moves.
	 */
	@Test
	fun rootSpaceDeltasScaleButDoNotTranslate() {
		val delta = floatArrayOf(1f, 1f)
		val asDelta = convertDeltasToRuntime(PointSpace.ModelRoot, delta, canvas)
		val asPoint = convertPointsToRuntime(PointSpace.ModelRoot, delta, canvas)
		assertEquals(900f, asDelta[0], "a root delta scales by ppu alone")
		assertEquals(450f + 900f, asPoint[0], "a root point also translates by the origin")
	}

	/** A zero pixels-per-unit substitutes a unit scale, so the stored values stay finite rather than infinite. */
	@Test
	fun aDegenerateCanvasIsTheIdentity() {
		val degenerate = MocCanvasMapping(pixelsPerUnit = 0f, originX = 0f, originY = 0f)
		val stored = convertPointsToMoc(PointSpace.ModelRoot, points, degenerate)
		for (value in stored) {
			assertTrue(value.isFinite(), "a zero ppu must not produce infinities: $value")
		}
	}

	/**
	 * Only the FIRST rotation on each root path carries the px→model factor.
	 *
	 * Chain: rootRotation -> warp -> nestedRotation.  The nested rotation's direct parent is a WARP, so
	 * a predicate that only looked at the immediate parent would wrongly give it the factor too - the
	 * question is whether a rotation appears anywhere above, not directly above.
	 */
	@Test
	fun onlyTheFirstRotationOnAPathCarriesTheUnitFactor() {
		val parents = intArrayOf(-1, 0, 1)
		val isRotation = booleanArrayOf(true, false, true)
		val flags = rotationAncestorFlags(3, { parents[it] }, { isRotation[it] })

		assertTrue(!flags[0], "the root rotation has no rotation above it")
		assertTrue(flags[1], "the warp sits under a rotation")
		assertTrue(flags[2], "the nested rotation sits under one too, through the warp")

		assertEquals(900f, rotationScaleFactor(flags[0], canvas), "the first rotation carries the factor")
		assertEquals(1f, rotationScaleFactor(flags[2], canvas), "a nested rotation inherits it instead")
	}

	/** A malformed parent cycle terminates instead of hanging the export. */
	@Test
	fun aCyclicParentChainTerminates() {
		val parents = intArrayOf(1, 0)
		val flags = rotationAncestorFlags(2, { parents[it] }, { false })
		assertTrue(flags.none { it }, "no rotation exists, cycle or not")
	}
}