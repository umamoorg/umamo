package org.umamo.runtime.model

import org.umamo.runtime.eval.keyformBaseOf
import org.umamo.runtime.eval.referenceCellOf
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.ulp
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the delta arithmetic every importer and exporter shares: [deltaReaching] rebuilds the absolute exactly
 * between values of one magnitude and within the larger value's ulp otherwise, a delta it returns is stable
 * through a second round trip, and the base an importer takes is the reference cell's ([referenceCellOf],
 * [keyformBaseOf]).
 */
class MeshDeltasTest {
	private val parameterId = ParameterId("P")

	/** Between values of one magnitude - a keyform and its base in one space - every rebuild is exact. */
	@Test
	fun aDeltaWithinOneSpaceRebuildsExactly() {
		val random = Random(7)
		repeat(100_000) {
			val reference = random.nextFloat() * 2f - 0.5f
			val absolute = reference + (random.nextFloat() - 0.5f) * 0.2f
			assertEquals(absolute.toRawBits(), (reference + deltaReaching(reference, absolute)).toRawBits(), "$reference -> $absolute")
		}
	}

	/**
	 * Across magnitudes no delta reaches every bit, but the rebuild is within the larger value's ulp, and a second
	 * round trip from what it rebuilt is exact: a re-imported delta does not drift again.
	 */
	@Test
	fun aDeltaAcrossMagnitudesIsWithinTheLargerUlpAndStable() {
		val random = Random(11)
		repeat(100_000) {
			val reference = 1000f + random.nextFloat() * 4000f
			val absolute = random.nextFloat() - 0.5f
			val rebuilt = reference + deltaReaching(reference, absolute)
			assertTrue(abs(rebuilt - absolute) <= max(abs(reference), abs(absolute)).ulp, "$reference -> $absolute rebuilt as $rebuilt")
			assertEquals(rebuilt.toRawBits(), (reference + deltaReaching(reference, rebuilt)).toRawBits(), "a second round trip from $rebuilt")
		}
	}

	/** The reference cell sits at the key nearest each default, ties going to the lower key, else the first cell. */
	@Test
	fun theReferenceCellIsNearestTheDefault() {
		val axis = KeyformAxis(parameterId, floatArrayOf(-1f, 0f, 1f))
		val grid = KeyformGrid(listOf(axis), (0 until 3).map { keyIndex -> KeyformCell(intArrayOf(keyIndex), keyIndex) })
		assertEquals(1, referenceCellOf(grid) { 0f }!!.form)
		assertEquals(2, referenceCellOf(grid) { 0.9f }!!.form)
		assertEquals(0, referenceCellOf(grid) { -0.5f }!!.form, "a tie goes to the lower key")
		val sparse = KeyformGrid(listOf(axis), listOf(KeyformCell(intArrayOf(2), 2), KeyformCell(intArrayOf(0), 0)))
		assertEquals(2, referenceCellOf(sparse) { 0f }!!.form, "no cell at the nearest key falls back to the first cell")
	}

	/**
	 * The base is a copy of the reference cell's absolutes; the canvas mesh itself when there is no grid, when the
	 * lengths differ, or when the values are the canvas mesh's own.
	 */
	@Test
	fun theBaseIsTheReferenceShapeOrTheCanvasMesh() {
		val canvas = floatArrayOf(10f, 20f, 30f, 40f)
		val shape = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f)
		val grid = KeyformGrid(listOf(KeyformAxis(parameterId, floatArrayOf(0f))), listOf(KeyformCell(intArrayOf(0), shape)))
		val base = keyformBaseOf(canvas, grid, { 0f }) { form -> form }
		assertEquals(shape.toList(), base.toList())
		assertNotSame(shape, base, "the base never shares the stored array")
		assertSame(canvas, keyformBaseOf<FloatArray>(canvas, null, { 0f }) { form -> form }, "no grid")
		val short = KeyformGrid(grid.axes, listOf(KeyformCell(intArrayOf(0), floatArrayOf(0f, 0f))))
		assertSame(canvas, keyformBaseOf(canvas, short, { 0f }) { form -> form }, "a length mismatch")
		val same = KeyformGrid(grid.axes, listOf(KeyformCell(intArrayOf(0), canvas.copyOf())))
		assertSame(canvas, keyformBaseOf(canvas, same, { 0f }) { form -> form }, "the canvas mesh's own values")
	}

	/**
	 * The float32 neighbors step exactly as the platform nextUp / nextDown do: one raw-bits pattern away from or
	 * toward zero for either sign, both zeros out to the smallest subnormal, the largest finite float up to
	 * infinity, and NaN and the infinity in the step's direction left as they are.
	 */
	@Test
	fun theFloatNeighborsStepOneUlp() {
		assertEquals(Float.fromBits(0x3f800001), floatAbove(1f))
		assertEquals(Float.fromBits(0x3f7fffff), floatBelow(1f))
		assertEquals(Float.fromBits(0xbf7fffff.toInt()), floatAbove(-1f), "a negative steps toward zero")
		assertEquals(Float.fromBits(0xbf800001.toInt()), floatBelow(-1f), "and away from it")
		assertEquals(Float.MIN_VALUE, floatAbove(0f))
		assertEquals(Float.MIN_VALUE, floatAbove(-0f), "negative zero steps up like zero")
		assertEquals(-Float.MIN_VALUE, floatBelow(0f))
		assertEquals((-0f).toRawBits(), floatAbove(-Float.MIN_VALUE).toRawBits(), "the smallest negative subnormal steps up to negative zero")
		assertEquals(0f.toRawBits(), floatBelow(Float.MIN_VALUE).toRawBits(), "the smallest subnormal steps down to zero")
		assertEquals(Float.POSITIVE_INFINITY, floatAbove(Float.MAX_VALUE))
		assertEquals(Float.NEGATIVE_INFINITY, floatBelow(-Float.MAX_VALUE))
		assertEquals(Float.POSITIVE_INFINITY, floatAbove(Float.POSITIVE_INFINITY))
		assertEquals(Float.NEGATIVE_INFINITY, floatBelow(Float.NEGATIVE_INFINITY))
		assertTrue(floatAbove(Float.NaN).isNaN())
		assertEquals(-Float.MAX_VALUE, floatAbove(Float.NEGATIVE_INFINITY), "negative infinity steps up to the largest finite negative")
		// Zeros are pinned above: up from either one and back down lands on positive zero.
		val random = Random(3)
		repeat(10_000) {
			val value = Float.fromBits(random.nextInt())
			if (value.isFinite() && value != 0f) {
				assertEquals(value.toRawBits(), floatBelow(floatAbove(value)).toRawBits(), "$value up and back down")
				assertTrue(floatAbove(value) > value && floatBelow(value) < value, "$value lies between its neighbors")
			}
		}
	}

	/** A mesh's base is the canvas mesh's length, and the shared form keeps one array. */
	@Test
	fun aMeshKeepsItsTwoArraysOneLength() {
		assertFailsWith<IllegalArgumentException> { DrawableMesh(FloatArray(6), FloatArray(4), FloatArray(6), intArrayOf(0, 1, 2)) }
		val shared = DrawableMesh.withLocalEqualToCanvas(FloatArray(6), FloatArray(6), intArrayOf(0, 1, 2))
		assertSame(shared.positions, shared.localPositions)
		assertSame(shared.localPositions, shared.withUvs(FloatArray(6)).localPositions, "new coordinates keep both arrays")
	}
}