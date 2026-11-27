package org.umamo.ui.kit.field

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round

/*
 * Pure, Compose-free numeric-field math: display / storage rounding, the min-max fill fraction, the
 * drag-scrub value mapping, fixed-decimal formatting, and typed-entry parsing.  Extracted so the field's behavior is unit
 * testable without a Compose harness (see NumberFieldMathTest).
 */

/**
 * The storage-precision cap for a decimal NumberField.  A Float holds roughly six to seven significant
 * decimal digits, so the requested "up to eight places" is capped here - promote the field and its model
 * slot to Double if eight-place fidelity is ever genuinely needed.
 */
const val NUMBER_FIELD_STORAGE_DECIMALS = 6

/** Absorbs the rounding in a decade or mantissa computed through a logarithm, so 0.1 counts as 1 × 10⁻¹ and not 9.99 × 10⁻². */
private const val STEP_FOR_SPAN_EPSILON = 1e-9

/**
 * The number a decimal field's typed entry means, or null for an entry that is no number - "NaN" and "Infinity"
 * included: Kotlin's parse accepts both, a clamp passes NaN straight through, and no field value is ever meant to be
 * either.
 *
 * @param String typed The entered text.
 * @return Float? The finite number, or null to leave the field as it was.
 */
fun parseTypedFloat(typed: String): Float? = typed.trim().toFloatOrNull()?.takeIf { parsed -> parsed.isFinite() }

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
 * How far a scrub moves the value per pixel of horizontal drag.  A field given a [travelPx] crosses its whole range over
 * that travel, so every range takes the same hand motion end to end; a field without one, or whose range is not a
 * finite span, moves one [step] per [notchPx].
 *
 * @param ClosedFloatingPointRange range The field's range.
 * @param Float step The value increment per scrub notch.
 * @param Float notchPx The drag, in pixels, of one notch.
 * @param Float? travelPx The drag, in pixels, that crosses the whole range, or null to scrub by the notch.
 * @return Float The value change per pixel of drag.
 */
fun scrubValuePerPixel(range: ClosedFloatingPointRange<Float>, step: Float, notchPx: Float, travelPx: Float?): Float {
	val span = range.endInclusive - range.start
	val finiteSpan = range.start.isFinite() && range.endInclusive.isFinite() && span > 0f

	if (travelPx != null && travelPx > 0f && finiteSpan) {
		return span / travelPx
	}

	return step / notchPx
}

/**
 * Maps a horizontal drag onto a new value: [startValue] plus [deltaPx] at [valuePerPixel], brought into [range].
 * Rightward drag increases, leftward decreases (screen-x convention).
 *
 * @param Float startValue The value at the gesture's start.
 * @param Float deltaPx The total horizontal drag distance from the start, in pixels.
 * @param Float valuePerPixel The value change per pixel of drag, from [scrubValuePerPixel].
 * @param ClosedFloatingPointRange range The field's range.
 * @param Function bringIntoRange How a value past [range] comes back into it; clamps to the nearer end by default.
 * @return Float The scrubbed value.
 */
fun scrubValue(
	startValue: Float,
	deltaPx: Float,
	valuePerPixel: Float,
	range: ClosedFloatingPointRange<Float>,
	bringIntoRange: (Float) -> Float = { raw -> raw.coerceIn(range.start, range.endInclusive) },
): Float = bringIntoRange(startValue + deltaPx * valuePerPixel)

/**
 * A chevron step sized to a range: a tenth of [span], rounded down to 1, 2, or 5 times a power of ten, so a press moves
 * a readable amount on any range.  Never less than one place of [decimals], which is the least a commit keeps.
 *
 * @param Float span The width of the field's range.
 * @param Int decimals The fractional places the field keeps.
 * @return Float The step.
 */
fun numberFieldStepForSpan(span: Float, decimals: Int): Float {
	val smallest = 10.0.pow(-decimals.coerceIn(0, NUMBER_FIELD_STORAGE_DECIMALS))
	val tenth = span.toDouble() / 10.0

	if (!tenth.isFinite() || tenth <= smallest) {
		return smallest.toFloat()
	}

	val decade = 10.0.pow(floor(log10(tenth) + STEP_FOR_SPAN_EPSILON))
	val mantissa = tenth / decade
	val leading =
		when {
			mantissa >= 5.0 - STEP_FOR_SPAN_EPSILON -> 5.0
			mantissa >= 2.0 - STEP_FOR_SPAN_EPSILON -> 2.0
			else -> 1.0
		}

	return maxOf(leading * decade, smallest).toFloat()
}