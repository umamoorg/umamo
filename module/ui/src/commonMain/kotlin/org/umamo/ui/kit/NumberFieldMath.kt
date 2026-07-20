package org.umamo.ui.kit

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round

/*
 * Pure, Compose-free numeric-field math: display / storage rounding, the min-max fill fraction, the
 * drag-scrub value mapping, and fixed-decimal formatting.  Extracted so the field's behavior is unit
 * testable without a Compose harness (see NumberFieldMathTest).
 */

/**
 * The storage-precision cap for a decimal NumberField.  A Float holds roughly six to seven significant
 * decimal digits, so the requested "up to eight places" is capped here - promote the field and its model
 * slot to Double if eight-place fidelity is ever genuinely needed.
 */
const val NUMBER_FIELD_STORAGE_DECIMALS = 6

/** Pixels of horizontal drag per one [step] increment while scrubbing (the pointer-scrub sensitivity). */
const val NUMBER_FIELD_SCRUB_PIXELS_PER_STEP = 6f

/**
 * The left-to-right fill fraction for a bounded field: where [value] sits in [min]..[max], clamped to
 * 0..1.  Returns null when the bounds are not a usable finite range (infinite endpoint, or max <= min),
 * so an unbounded field draws no fill - the "min AND max set" rule.
 *
 * @param Float value The current value.
 * @param Float min The range minimum.
 * @param Float max The range maximum.
 * @return Float? The fill fraction in 0..1, or null when unbounded.
 */
fun numberFieldFillFraction(value: Float, min: Float, max: Float): Float? {
	if (!min.isFinite() || !max.isFinite() || max <= min) {
		return null
	}
	return ((value - min) / (max - min)).coerceIn(0f, 1f)
}

/**
 * Rounds [value] to [decimals] fractional places (the field's storage rounding); [decimals] <= 0 rounds
 * to a whole number.  Capped at [NUMBER_FIELD_STORAGE_DECIMALS] since a Float cannot faithfully hold more.
 *
 * @param Float value The value to round.
 * @param Int decimals The number of fractional places to keep.
 * @return Float The rounded value.
 */
fun roundToDecimals(value: Float, decimals: Int): Float {
	val places = decimals.coerceIn(0, NUMBER_FIELD_STORAGE_DECIMALS)
	if (places <= 0) {
		return round(value)
	}
	val scale = 10.0.pow(places).toFloat()
	return round(value * scale) / scale
}

/**
 * Formats [value] to exactly [decimals] fractional places for display (Blender pads a float field to a
 * fixed width, e.g. "0.500"); [decimals] <= 0 shows a whole number.  Dependency-free (kotlinx / java.text
 * formatting is not available in commonMain).  Capped at [NUMBER_FIELD_STORAGE_DECIMALS].
 *
 * @param Float value The value to format.
 * @param Int decimals The number of fractional places to show.
 * @return String The fixed-decimal text.
 */
fun formatDecimals(value: Float, decimals: Int): String {
	val places = decimals.coerceIn(0, NUMBER_FIELD_STORAGE_DECIMALS)
	if (places <= 0) {
		return round(value.toDouble()).toLong().toString()
	}
	val scale = 10.0.pow(places).toLong()
	val scaled = round(value.toDouble() * scale).toLong()
	val sign = if (scaled < 0) "-" else ""
	val magnitude = abs(scaled)
	val whole = magnitude / scale
	val fraction = (magnitude % scale).toString().padStart(places, '0')
	return "$sign$whole.$fraction"
}

/**
 * Maps a horizontal drag onto a new value: [startValue] plus one [step] per
 * [NUMBER_FIELD_SCRUB_PIXELS_PER_STEP] pixels of [deltaPx], clamped to [range].  Rightward drag
 * increases, leftward decreases (screen-x convention).
 *
 * @param Float startValue The value at the gesture's start.
 * @param Float deltaPx The total horizontal drag distance from the start, in pixels.
 * @param Float step The value increment per scrub notch.
 * @param ClosedFloatingPointRange range The clamp range.
 * @return Float The scrubbed value.
 */
fun scrubValue(startValue: Float, deltaPx: Float, step: Float, range: ClosedFloatingPointRange<Float>): Float {
	val raw = startValue + (deltaPx / NUMBER_FIELD_SCRUB_PIXELS_PER_STEP) * step
	return raw.coerceIn(range.start, range.endInclusive)
}
