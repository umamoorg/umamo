package org.umamo.ui.tracks

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/*
 * The track sheet's domain model, deliberately free of BOTH Compose and any Umamo concept.
 *
 * Nothing about parameters, keyform grids, or the puppet model may appear in this package.  A track sheet
 * is a list of labelled rows carrying marks along a horizontal axis, and that is all it knows - which is
 * what lets the keyform sheet (marks along a parameter's min..max) and the eventual animation dope sheet
 * (keys along a frame range) share it instead of growing two of everything.  The constraint is easy to
 * erode one convenient import at a time, so it is worth checking: `grep -r Parameter\\|Keyform\\|Puppet`
 * over this package must come back empty.
 *
 * トラックシートの領域モデル。Compose にも Umamo の概念にも依存しない。パラメータ軸でも時間軸でも
 * 同じ UI を使えるようにするための境界。
 */

/**
 * The horizontal domain a track sheet maps marks onto - the ONLY place a domain appears.
 *
 * The keyform sheet passes a parameter's min..max; a time-based sheet would pass a frame range.
 *
 * @property Float start The domain value at the track region's left edge.
 * @property Float end The domain value at its right edge.
 */
data class TrackAxis(
	val start: Float,
	val end: Float,
) {
	/** The domain's extent; zero for a degenerate axis, which every mapping below guards against. */
	val span: Float get() = end - start

	/**
	 * Maps a domain value to its 0..1 position across the track region.
	 *
	 * A degenerate axis (min == max, which a non-animatable parameter has) maps everything to the middle
	 * rather than dividing by zero - the row still renders, its marks simply stack.
	 *
	 * @param Float value The domain value.
	 * @return Float The fraction across the region, unclamped so an out-of-range mark stays detectable.
	 */
	fun fractionOf(value: Float): Float = if (span == 0f) 0.5f else (value - start) / span

	/**
	 * Maps a 0..1 position back to its domain value - the inverse of [fractionOf], for hit-testing a click.
	 *
	 * @param Float fraction The fraction across the region.
	 * @return Float The domain value there.
	 */
	fun valueAt(fraction: Float): Float = start + span * fraction

	/**
	 * Evenly spaced ruler ticks at a human-readable step covering the domain.
	 *
	 * The step is the largest of 1, 2, or 5 times a power of ten that still yields at least
	 * [minimumTickCount] ticks, so a -30..30 axis rules at 10 and a -1..1 axis at 0.5 rather than both
	 * getting an arbitrary fixed count.
	 *
	 * @param Int minimumTickCount The fewest ticks the result should contain.
	 * @return List<Float> The tick values, ascending, within the domain.
	 */
	fun ticks(minimumTickCount: Int = 4): List<Float> {
		val extent = abs(span)
		if (extent == 0f || minimumTickCount <= 0) {
			return listOf(start)
		}
		val roughStep = extent / minimumTickCount
		val magnitude = 10f.pow(floor(log10(roughStep.toDouble())).toFloat())
		val step =
			when {
				roughStep / magnitude >= 5f -> 5f * magnitude
				roughStep / magnitude >= 2f -> 2f * magnitude
				else -> magnitude
			}
		if (step <= 0f) {
			return listOf(start)
		}
		val lower = minOf(start, end)
		val upper = maxOf(start, end)
		val firstTick = floor(lower / step) * step
		val values = ArrayList<Float>()
		var tick = firstTick
		// Bounded independently of the arithmetic so a pathological step can never spin here.
		var guard = 0
		while (tick <= upper + step * 0.5f && guard < 1024) {
			if (tick >= lower - step * 0.5f) {
				values.add(tick)
			}
			tick += step
			guard++
		}
		return values
	}
}

/**
 * The shape a mark is drawn with, which is how a row distinguishes kinds of key at a glance.
 *
 * The keyform sheet uses [Circle] for a keyform-grid key and [Square] for a blend-shape key, matching the
 * marks the parameter slider already draws.  [Diamond] is reserved for the animation dope sheet's keys so
 * the two sheets stay visually distinct when both are open.
 */
enum class TrackKeyShape {
	Circle,
	Square,
	Diamond,
}

/**
 * One mark on a track.
 *
 * @property Float position The mark's domain value (a parameter value, a frame, …).
 * @property TrackKeyShape shape How to draw it.
 * @property Boolean selected Whether it is part of the current key selection.
 */
data class TrackKeyMark(
	val position: Float,
	val shape: TrackKeyShape = TrackKeyShape.Circle,
	val selected: Boolean = false,
)

/**
 * One row of a track sheet: a label and the marks along it.
 *
 * @property String key A stable identity for the row, so Compose keeps per-row state across list changes.
 * @property String label The row's primary text.
 * @property String? detail Optional secondary text, shown muted after the label.
 * @property Int depth Indent level, for rows that nest under an owner.
 * @property List<TrackKeyMark> marks The row's marks, in any order.
 */
data class TrackRow(
	val key: String,
	val label: String,
	val detail: String? = null,
	val depth: Int = 0,
	val marks: List<TrackKeyMark> = emptyList(),
)

/**
 * The mark nearest [domainValue] within [tolerance], or null - the shared hit test for clicking a mark.
 *
 * Compose-free so the picking rule is unit-testable and identical in every sheet built on this package.
 *
 * @param List<TrackKeyMark> marks The row's marks.
 * @param Float domainValue The clicked position in domain units.
 * @param Float tolerance The pick radius in domain units.
 * @return TrackKeyMark? The nearest mark in range, or null.
 */
fun nearestMark(marks: List<TrackKeyMark>, domainValue: Float, tolerance: Float): TrackKeyMark? {
	var best: TrackKeyMark? = null
	var bestDistance = tolerance
	for (mark in marks) {
		val distance = abs(mark.position - domainValue)
		if (distance <= bestDistance) {
			bestDistance = distance
			best = mark
		}
	}
	return best
}
