package org.umamo.ui.workspace.spaces.keyformsheet

import androidx.compose.ui.geometry.Rect
import org.umamo.edit.TrackKeyRef
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.ui.tracks.TrackAxis
import org.umamo.ui.tracks.TrackWindow
import org.umamo.ui.tracks.flattenTrackRows
import org.umamo.ui.tracks.laneMarkOffsetX

/**
 * Every key the window-space [region] encloses, across every section.
 *
 * Resolved against the lanes' own reported bounds rather than computed from row heights, since sections
 * fold, groups collapse, and the sheet scrolls.  It matches what is on screen rather than what is in the
 * model: only the flattened, currently-visible rows are walked, because laneBounds is never pruned and a
 * collapsed group's children keep their last rectangles, and the axis is the windowed one, so a zoomed
 * sheet maps a mark to the pixel it is drawn at.
 *
 * @param Rect region The marquee, in window coordinates.
 * @param List projections Each targeted parameter and its tracks.
 * @param Map laneBounds Each row key's last reported window bounds.
 * @param Float markRadiusPx The sheet's mark radius, which is also its lane end inset.
 * @param TrackWindow window The visible slice of each parameter's range, shared by every section.
 * @param Set<String> expandedKeys The open group rows, which decide which lanes exist.
 * @param Set<ParameterId> collapsedParameters The folded sections, whose rows are not on screen at all.
 * @return Set<TrackKeyRef> The enclosed keys.
 */
internal fun keysWithin(
	region: Rect,
	projections: List<Pair<Parameter, KeyformSheetProjection>>,
	laneBounds: Map<String, Rect>,
	markRadiusPx: Float,
	window: TrackWindow,
	expandedKeys: Set<String>,
	collapsedParameters: Set<ParameterId>,
): Set<TrackKeyRef> {
	val enclosed = mutableSetOf<TrackKeyRef>()

	for ((parameter, projection) in projections) {
		if (parameter.id in collapsedParameters) {
			continue
		}

		val (domainStart, domainEnd) = parameterDomain(parameter)
		val axis = window.axisOver(TrackAxis(domainStart, domainEnd))

		for (line in flattenTrackRows(projection.rows, expandedKeys)) {
			// Only rows with a track ref: a group header names an owner and a blend-shape row is not a
			// keyform grid, so neither has keys a selection could act on.
			val row = line.row
			val bounds = laneBounds[row.key] ?: continue

			if (!projection.tracksByRowKey.containsKey(row.key) || !bounds.overlapsVertically(region)) {
				continue
			}

			for (mark in row.marks) {
				// The same mapping the marks were drawn with, from the same function - re-deriving the end
				// inset here is how a marquee drifts by exactly one mark width.
				val drawnX = bounds.left + laneMarkOffsetX(axis, mark.position, bounds.width, markRadiusPx)

				if (drawnX in region.left..region.right) {
					enclosed.add(TrackKeyRef(parameter.id, row.key, mark.keyIndex))
				}
			}
		}
	}

	return enclosed
}

/**
 * Whether two rectangles share any vertical extent - the marquee's row test.
 *
 * @param Rect other The rectangle to test against.
 * @return Boolean True when their vertical extents overlap.
 */
private fun Rect.overlapsVertically(other: Rect): Boolean = top < other.bottom && bottom > other.top