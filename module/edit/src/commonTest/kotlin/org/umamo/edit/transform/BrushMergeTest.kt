package org.umamo.edit.transform

import org.umamo.edit.BrushMode
import org.umamo.edit.BrushSettings
import org.umamo.edit.ProportionalFalloff
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the brush's merge: folding a stroke as it grows gives what one merge over the whole stroke gives, in
 * either mode, and a changed setting starts the merge over.
 */
class BrushMergeTest {
	// How close an offset may sit to the value the whole-stroke merge gives.
	private val tolerance = 1e-5f

	/** A lattice of points the brush can reach. */
	private val lattice = floatArrayOf(0f, 0f, 20f, 0f, 20f, 20f, 0f, 20f, 10f, 10f)

	/**
	 * The offsets one merge over the whole stroke gives.
	 *
	 * @param BrushStroke stroke The stroke.
	 * @param BrushSettings settings The brush's configuration.
	 * @return FloatArray The offsets.
	 */
	private fun oneMergeOverTheWholeStroke(stroke: BrushStroke, settings: BrushSettings): FloatArray =
		BrushMerge(lattice).update(stroke, settings).copyOf()

	/**
	 * Asserts that a folded merge gave what one merge over the whole stroke gave.
	 *
	 * @param FloatArray expected The whole-stroke offsets.
	 * @param FloatArray actual The folded offsets.
	 * @param String message What was compared.
	 */
	private fun assertAgrees(expected: FloatArray, actual: FloatArray, message: String) {
		assertEquals(expected.size, actual.size, message)

		for (componentIndex in expected.indices) {
			assertTrue(
				abs(expected[componentIndex] - actual[componentIndex]) <= tolerance,
				"$message: component $componentIndex was ${actual[componentIndex]}, expected ${expected[componentIndex]}",
			)
		}
	}

	/** A stroke folded as it grows gives what one merge over the whole stroke gives, in either mode. */
	@Test
	fun aStrokeFoldedAsItGrowsAgreesWithOneMergeOverTheWholeStroke() {
		for (mode in BrushMode.entries) {
			val settings = BrushSettings(ProportionalFalloff.Smooth, 15f, 1f, mode)
			val merge = BrushMerge(lattice)
			var stroke = BrushStroke.starting(-5f, 0f, 15f)

			assertAgrees(oneMergeOverTheWholeStroke(stroke, settings), merge.update(stroke, settings), "$mode at the press")

			for (centreX in listOf(0f, 5f, 12f, 20f, 30f, 40f)) {
				stroke = stroke.stampedTo(centreX, 0f, 15f)

				assertAgrees(oneMergeOverTheWholeStroke(stroke, settings), merge.update(stroke, settings), "$mode at $centreX")
			}
		}
	}

	/** A falloff that changes mid-stroke starts the merge over, so the folded result is still the whole-stroke one. */
	@Test
	fun aChangedFalloffStartsTheMergeOver() {
		val settings = BrushSettings(ProportionalFalloff.Smooth, 15f, 1f, BrushMode.Flow)
		val merge = BrushMerge(lattice)
		var stroke = BrushStroke.starting(-5f, 0f, 15f).stampedTo(20f, 0f, 15f)

		merge.update(stroke, settings)

		val sharpened = settings.copy(falloff = ProportionalFalloff.Sharp)
		stroke = stroke.stampedTo(40f, 0f, 15f)

		assertAgrees(oneMergeOverTheWholeStroke(stroke, sharpened), merge.update(stroke, sharpened), "the sharpened stroke")
	}

	/** A mode that changes mid-stroke starts the merge over too. */
	@Test
	fun aChangedModeStartsTheMergeOver() {
		val settings = BrushSettings(ProportionalFalloff.Smooth, 15f, 1f, BrushMode.Drag)
		val merge = BrushMerge(lattice)
		var stroke = BrushStroke.starting(-5f, 0f, 15f).stampedTo(20f, 0f, 15f)

		merge.update(stroke, settings)

		val flowed = settings.copy(mode = BrushMode.Flow)
		stroke = stroke.stampedTo(40f, 0f, 15f)

		assertAgrees(oneMergeOverTheWholeStroke(stroke, flowed), merge.update(stroke, flowed), "the flowed stroke")
	}

	/** The strength scales the folded merge, so the same stroke lands further or shorter. */
	@Test
	fun theStrengthScalesTheFoldedMerge() {
		val settings = BrushSettings(ProportionalFalloff.Smooth, 15f, 1f, BrushMode.Drag)
		val merge = BrushMerge(lattice)
		val stroke = BrushStroke.starting(-5f, 0f, 15f).stampedTo(20f, 0f, 15f)
		val full = merge.update(stroke, settings).copyOf()
		val half = merge.update(stroke, settings.copy(strength = 0.5f)).copyOf()

		assertEquals(full[0] * 0.5f, half[0], tolerance)
	}
}