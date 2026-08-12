package org.umamo.ui.viewport

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import org.umamo.render.pick.PickCandidate
import org.umamo.runtime.model.DrawableId
import org.umamo.ui.model.OverlapEntry
import kotlin.math.roundToInt

/**
 * The overlap-picker popup's state: where to anchor it, the candidate rows, the pre-highlighted row,
 * and the requesting surface's pick action (Object mode replaces the object selection; Edit mode's
 * Alt+Q switches the edited mesh) - the popup itself is mode-agnostic.  Hosted by whichever space
 * mounts the popup (the 2D viewport and the UV editor each own one), area-local like the anchor.
 */
internal data class OverlapState(
	val anchor: IntOffset,
	val entries: List<OverlapEntry>,
	val defaultIndex: Int,
	val pick: (DrawableId) -> Unit,
)

/**
 * Builds the overlap-popup state from a hit at [position] with [candidates] (front-to-back). The rows
 * keep that front-to-back order; the pre-highlighted default is the highest-centrality candidate (the
 * most unambiguously clicked one). Each row gets the drawable's layer-art thumbnail from [service]
 * (cached there); an untextured drawable yields null and renders as a label-only row.  The name and
 * thumbnail lookups are pure model/texture reads, so any area kind may call this - a UV-editor area
 * included.
 *
 * @param PuppetViewportService service The service that supplies (and caches) the layer thumbnails.
 * @param Offset position The cursor position to anchor the popup at, in area-local pixels.
 * @param List candidates The opaque candidates under the cursor, front-to-back.
 * @param Function pick Applies the chosen drawable per the requesting overlay's mode.
 * @return OverlapState The popup state.
 */
internal fun overlapStateFrom(
	service: PuppetViewportService,
	position: Offset,
	candidates: List<PickCandidate>,
	pick: (DrawableId) -> Unit,
): OverlapState =
	OverlapState(
		anchor = IntOffset(position.x.roundToInt(), position.y.roundToInt()),
		entries =
			candidates.map { candidate ->
				// "Raw (Part)" - the stable drawable id plus the owning part's name, so the rigger can tell
				// what they are selecting; falls back to just the id for a drawable with no owning part.
				val partName = service.partNameFor(candidate.id)
				val label = if (partName != null) "${candidate.id.raw} ($partName)" else candidate.id.raw
				OverlapEntry(candidate.id, label, service.thumbnailFor(candidate.id))
			},
		defaultIndex = candidates.indices.maxByOrNull { index -> candidates[index].centrality } ?: 0,
		pick = pick,
	)