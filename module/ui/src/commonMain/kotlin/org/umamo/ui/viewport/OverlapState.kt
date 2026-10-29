package org.umamo.ui.viewport

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import org.umamo.edit.SelectionTarget
import org.umamo.render.pick.PickCandidate
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.theme.LocalUmamoIcons
import kotlin.math.roundToInt

/**
 * One target an Alt click found under the cursor: a drawable whose art is there, or a warp whose drawn lattice
 * is.  The overlap picker lists these, and a surface's Alt stack is built from them.
 *
 * @property SelectionTarget target What a pick of this row selects.
 * @property Float centrality How unambiguously the click meant a drawable's art ([PickCandidate.centrality]);
 *   unused for a lattice, which a click takes first whenever it lands on one.
 */
class ObjectPickCandidate(
	val target: SelectionTarget,
	val centrality: Float = 0f,
)

/**
 * Art pick candidates as object pick candidates, each its drawable with its centrality.
 *
 * @return List<ObjectPickCandidate> The candidates, in the same order.
 */
internal fun List<PickCandidate>.asObjectPickCandidates(): List<ObjectPickCandidate> =
	map { candidate -> ObjectPickCandidate(SelectionTarget.Drawable(candidate.id), candidate.centrality) }

/**
 * The overlap-picker popup's state: where to anchor it, the candidate rows, the pre-highlighted row,
 * and the requesting surface's pick action (Object and Pose mode replace the object selection; Edit mode's
 * Alt+Q switches the edited mesh) - the popup itself is mode-agnostic.  Hosted by whichever space
 * mounts the popup (the 2D viewport and the UV editor each own one), area-local like the anchor.
 */
internal data class OverlapState(
	val anchor: IntOffset,
	val entries: List<OverlapEntry>,
	val defaultIndex: Int,
	val pick: (SelectionTarget) -> Unit,
)

/**
 * Builds the overlap-popup state from a hit at [position] with [candidates], in the stack's order.  The
 * pre-highlighted default is what a plain click there would take: the first lattice when the stack has one,
 * else the highest-centrality drawable (the most unambiguously clicked one).  A drawable's row carries its
 * layer-art thumbnail from [service] (cached there), or none when it is untextured; a warp's row carries the
 * warp deformer icon.  The name and thumbnail lookups are pure model/texture reads, so any area kind may call
 * this - a UV-editor area included.
 *
 * @param PuppetViewportService service The service that supplies the drawable names and (cached) thumbnails.
 * @param PuppetModel model The model the deformer names and parts are read from.
 * @param Offset position The cursor position to anchor the popup at, in area-local pixels.
 * @param List<ObjectPickCandidate> candidates The candidates under the cursor, in the stack's order.
 * @param Function pick Applies the chosen target per the requesting overlay's mode.
 * @return OverlapState The popup state.
 */
internal fun overlapStateFrom(
	service: PuppetViewportService,
	model: PuppetModel,
	position: Offset,
	candidates: List<ObjectPickCandidate>,
	pick: (SelectionTarget) -> Unit,
): OverlapState {
	val firstLattice = candidates.indexOfFirst { candidate -> candidate.target is SelectionTarget.Deformer }
	val defaultIndex =
		if (firstLattice >= 0) {
			firstLattice
		} else {
			candidates.indices.maxByOrNull { index -> candidates[index].centrality } ?: 0
		}

	return OverlapState(
		anchor = IntOffset(position.x.roundToInt(), position.y.roundToInt()),
		entries = candidates.map { candidate -> overlapEntryOf(service, model, candidate.target) },
		defaultIndex = defaultIndex,
		pick = pick,
	)
}

/**
 * One row of the popup for [target]: "Name (Part)" - what the rigger named it plus the part that owns it,
 * since that is what they recognize on a crowded stack.  A nameless entity falls back to its stable id, and
 * one with no part (or a part with no name) drops the parenthetical.
 *
 * @param PuppetViewportService service The service that supplies the drawable names and thumbnails.
 * @param PuppetModel model The model the deformer names and parts are read from.
 * @param SelectionTarget target The row's target.
 * @return OverlapEntry The row.
 */
private fun overlapEntryOf(service: PuppetViewportService, model: PuppetModel, target: SelectionTarget): OverlapEntry =
	when (target) {
		is SelectionTarget.Drawable -> {
			val drawableName = service.drawableNameFor(target.id)?.ifBlank { null } ?: target.id.raw
			val partName = service.partNameFor(target.id)?.ifBlank { null }
			OverlapEntry(target, labelOf(drawableName, partName), thumbnail = service.thumbnailFor(target.id))
		}

		is SelectionTarget.Deformer -> {
			val deformer = model.deformers.firstOrNull { candidate -> candidate.id == target.id }
			val deformerName = deformer?.name?.ifBlank { null } ?: target.id.raw
			val partName = deformer?.partId?.let { partId -> model.parts.firstOrNull { part -> part.id == partId }?.name?.ifBlank { null } }
			val icon = if (deformer is Deformer.Rotation) LocalUmamoIcons.rotationDeformer else LocalUmamoIcons.warpDeformer
			OverlapEntry(target, labelOf(deformerName, partName), icon = icon)
		}

		is SelectionTarget.Part -> {
			val partName = model.parts.firstOrNull { part -> part.id == target.id }?.name?.ifBlank { null } ?: target.id.raw
			OverlapEntry(target, partName, icon = LocalUmamoIcons.part)
		}
	}

/**
 * A row label: the name, with the owning part in parentheses when there is one.
 *
 * @param String name The entity's name.
 * @param String? partName Its part's name, or null.
 * @return String The label.
 */
private fun labelOf(name: String, partName: String?): String = if (partName != null) "$name ($partName)" else name