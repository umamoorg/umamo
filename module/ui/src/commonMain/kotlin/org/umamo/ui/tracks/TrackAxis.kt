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
 * The visible slice of a domain, as fractions of it - the zoom / pan state of a track sheet.
 *
 * NORMALIZED rather than in domain units so one window drives several domains at once.  A keyform sheet
 * showing a linked pad has two parameters with unrelated ranges (an angle over -30..30 beside an open /
 * close over 0..1); a shared absolute window would overshoot the narrower one entirely, while a shared
 * fraction keeps both rulers at the same screen positions and the pad's two axes reading in step.
 *
 * @property Float start The visible range's start, as a fraction of the full domain.
 * @property Float end Its end, likewise.
 */
data class TrackWindow(
	val start: Float = 0f,
	val end: Float = 1f,
) {
	/** The visible fraction of the whole domain; 1 when framed to everything. */
	val span: Float get() = end - start

	/**
	 * This window as an axis over [full], which is what the sheet actually draws against.
	 *
	 * @param TrackAxis full The whole domain.
	 * @return TrackAxis The visible slice of it.
	 */
	fun axisOver(full: TrackAxis): TrackAxis =
		TrackAxis(full.start + full.span * start, full.start + full.span * end)

	/**
	 * This window zoomed by [factor] about [focus], a fraction of the VISIBLE width.
	 *
	 * Anchored on the pointer rather than the center, so zooming in on a cluster of keys keeps that
	 * cluster under the cursor instead of sliding it away.  Clamped so the window can never invert, shrink
	 * past [MIN_SPAN] (which would divide by nothing at the pixel mapping), or grow past the full domain.
	 *
	 * @param Float factor Multiplier on the visible span; below 1 zooms in.
	 * @param Float focus The zoom anchor, 0..1 across the visible width.
	 * @return TrackWindow The new window.
	 */
	fun zoomedBy(factor: Float, focus: Float): TrackWindow {
		val anchor = start + span * focus.coerceIn(0f, 1f)
		val newSpan = (span * factor).coerceIn(MIN_SPAN, 1f)
		// Hold the anchor at the same fraction of the visible width, then push the result back inside the
		// domain - zooming out near an end walks the window inward rather than off the edge.
		val newStart = (anchor - (anchor - start) * (newSpan / span)).coerceIn(0f, 1f - newSpan)
		return TrackWindow(newStart, newStart + newSpan)
	}

	/**
	 * This window panned by [fraction] of the FULL domain, stopping at either end.
	 *
	 * @param Float fraction The signed distance to move, as a fraction of the whole domain.
	 * @return TrackWindow The new window.
	 */
	fun pannedBy(fraction: Float): TrackWindow {
		val newStart = (start + fraction).coerceIn(0f, 1f - span)
		return TrackWindow(newStart, newStart + span)
	}

	companion object {
		/** The whole domain - what "frame all" resets to. */
		val Full: TrackWindow = TrackWindow()

		/**
		 * The tightest the window may zoom, as a fraction of the domain.
		 *
		 * A thousandth of a -30..30 axis is 0.06 units across the whole panel, which separates keys the
		 * evaluator itself treats as distinct (EPS_KEY is 0.001) by hundreds of pixels.  Tighter than that
		 * buys nothing and starts losing float precision in the pixel mapping.
		 */
		const val MIN_SPAN: Float = 0.001f
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
 * [keyIndex] is the mark's IDENTITY and [position] is only where it is drawn.  Two marks may sit at the
 * same pixel - nothing forbids keys a hair apart, and forbidding it would be the wrong cure - so anything
 * that edits a key has to say which one by ordinal.  Resolving "the key at this value" instead is
 * ambiguous exactly when it matters, and picks the wrong key silently.
 *
 * A summary mark (drawn on a collapsed group) is renumbered to its ordinal within the SUMMARY, which its
 * owner maps back to the whole set of child keys it stands for - so acting on one acts on all of them at
 * once rather than guessing at which channel was meant.
 *
 * @property Int keyIndex The mark's ordinal within its row's track.
 * @property Float position The mark's domain value (a parameter value, a frame, …).
 * @property TrackKeyShape shape How to draw it.
 * @property Boolean selected Whether it is part of the current key selection.
 * @property Boolean editable Whether it may be selected, dragged, or removed; a blend-shape binding key may
 *   not, which is why [summarizedMarks] leaves it out of a group's summary rather than folding it in.
 */
data class TrackKeyMark(
	val keyIndex: Int,
	val position: Float,
	val shape: TrackKeyShape = TrackKeyShape.Circle,
	val selected: Boolean = false,
	val editable: Boolean = true,
)

/**
 * The domain window a mark may be dragged within: the axis, end to end.
 *
 * Neighbours are NOT walls.  A key may be dragged past another - the sheet's owner re-sorts and permutes
 * to match - so clamping at a neighbour here would stop a gesture the model is happy to accept.  The axis
 * ends still bound it, because a key outside the domain is one the sheet cannot draw or reach again.
 *
 * @param TrackAxis axis The domain.
 * @return ClosedFloatingPointRange<Float> The legal range, ascending.
 */
fun dragBoundsOf(axis: TrackAxis): ClosedFloatingPointRange<Float> =
	minOf(axis.start, axis.end)..maxOf(axis.start, axis.end)

/**
 * A row's color band, which is how a sheet makes kinds of track separable at a glance.
 *
 * Abstract slots rather than colors or domain names, for the same reason [TrackAxis] is abstract: the
 * theme owns what each band actually looks like, and each sheet decides which of its own kinds map onto
 * which slot.  The keyform sheet reads them as owner / geometry / channel / blend shape; a time-based
 * sheet would read the same four as object / transform / custom property / layered action.
 */
enum class TrackRowTone {
	/** A grouping row that owns the tracks nested beneath it. */
	Group,

	/** A track carrying the row's primary subject. */
	Primary,

	/** A track carrying a secondary property of the same subject. */
	Secondary,

	/** A track from a source layered on top of the primary one. */
	Alternate,
}

/**
 * One row of a track sheet: a label, the marks along it, and any rows nested beneath it.
 *
 * A row is a TREE node rather than a flat line with a depth number, because collapsing has to hide a
 * whole subtree and a depth-tagged flat list cannot say where one ends.  [flattenTrackRows] turns the
 * tree back into display lines once the expansion state is known.
 *
 * @property String key A stable identity for the row, so Compose keeps per-row state across list changes.
 * @property String label The row's primary text.
 * @property String? detail Optional secondary text, shown under the label as a subtitle.
 * @property TrackRowTone tone The row's color band.
 * @property List<TrackKeyMark> marks The row's marks, in any order.
 * @property List<TrackRow> children Rows nested under this one, shown only while it is expanded.
 */
data class TrackRow(
	val key: String,
	val label: String,
	val detail: String? = null,
	val tone: TrackRowTone = TrackRowTone.Primary,
	val marks: List<TrackKeyMark> = emptyList(),
	val children: List<TrackRow> = emptyList(),
)

/**
 * One row as it is actually displayed: the row itself plus what the tree position implies about it.
 *
 * @property TrackRow row The row.
 * @property Int depth Its nesting level, for indentation.
 * @property Boolean expandable Whether it has children at all (so a chevron is drawn).
 * @property Boolean expanded Whether those children are currently shown.
 */
data class TrackRowLine(
	val row: TrackRow,
	val depth: Int,
	val expandable: Boolean,
	val expanded: Boolean,
)

/**
 * Flattens a row tree into display lines, descending only into rows whose key is in [expandedKeys].
 *
 * Compose-free so the expansion rule is unit-testable and identical in every sheet on this package.
 *
 * @param List<TrackRow> rows The root rows, in display order.
 * @param Set<String> expandedKeys The keys of the rows whose children are shown.
 * @return List<TrackRowLine> The visible lines, in display order.
 */
fun flattenTrackRows(rows: List<TrackRow>, expandedKeys: Set<String>): List<TrackRowLine> {
	val lines = ArrayList<TrackRowLine>()

	fun visit(row: TrackRow, depth: Int) {
		val expandable = row.children.isNotEmpty()
		val expanded = expandable && row.key in expandedKeys
		lines.add(TrackRowLine(row, depth, expandable, expanded))
		if (expanded) {
			for (child in row.children) {
				visit(child, depth + 1)
			}
		}
	}
	for (row in rows) {
		visit(row, 0)
	}
	return lines
}

/**
 * Every mark under [row] and its whole subtree, deduplicated by position and shape.
 *
 * What a collapsed group row draws, so folding a subtree away still shows WHERE its keys are rather than
 * hiding them entirely - the summary behaviour Blender's dope sheet has at every level.
 *
 * @param TrackRow row The row to summarize.
 * @return List<TrackKeyMark> The union of its own and its descendants' marks, ascending.
 */
fun summarizedMarks(row: TrackRow): List<TrackKeyMark> {
	val byPosition = LinkedHashMap<Float, TrackKeyMark>()

	fun visit(current: TrackRow) {
		for (mark in current.marks) {
			// Only an EDITABLE child key can be summarized: a summary mark is a handle on the keys beneath
			// it, so one standing partly on keys nothing can move would move only some of what it shows.
			if (mark.editable) {
				byPosition.getOrPut(mark.position) { mark }
			}
		}
		current.children.forEach(::visit)
	}
	visit(row)
	// Renumbered to its position in the SUMMARY.  The winner's own ordinal is meaningless across the group
	// (two channels keyed at one value can hold different ordinals), whereas a summary ordinal is a stable
	// name the owner can map back to the whole set of keys it stands for.
	return byPosition.values.sortedBy { mark -> mark.position }.mapIndexed { ordinal, mark ->
		mark.copy(keyIndex = ordinal)
	}
}

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
