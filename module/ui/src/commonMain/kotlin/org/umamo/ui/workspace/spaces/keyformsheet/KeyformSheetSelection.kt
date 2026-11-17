package org.umamo.ui.workspace.spaces.keyformsheet

import org.umamo.edit.EditorSession
import org.umamo.edit.TrackKeyRef
import org.umamo.edit.keyform.TrackKeyDragAnchor
import org.umamo.edit.keyform.dragTrackKeysKeepingSelection
import org.umamo.edit.keyform.limitedDragFraction
import org.umamo.runtime.eval.EPS_KEY
import org.umamo.runtime.model.KeyformTrackRef
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.ui.tracks.TrackKeyMark
import org.umamo.ui.tracks.TrackMarkSelection
import org.umamo.ui.tracks.TrackRow
import kotlin.math.abs

/*
 * The sheet's side of the key selection: what a click does to it, how its refs resolve to the keys the edit
 * ops take, and the drag that moves all of it.  The selection itself is session state
 * (EditorSession.keySelection), so undo can restore it, and what an edit does to it lives in :edit.
 */

/**
 * [current] with [clicked] toggled in or out - what a Shift+click on a mark leaves behind.
 *
 * A toggle rather than add-only because a click is the only gesture that can take a single mark back out of
 * a selection without starting over.  A summary mark's whole membership toggles as one unit, so a folded
 * group cannot end up half-selected by a gesture that showed the user a single mark; partly-selected counts
 * as not selected and the click completes it, which lets a second click undo the first.
 *
 * @param Set current The selection before the click.
 * @param Set clicked The keys the clicked mark stands for - one, or a summary's whole membership.
 * @return Set The selection after it.
 */
internal fun selectionAfterAdditiveClick(current: Set<TrackKeyRef>, clicked: Set<TrackKeyRef>): Set<TrackKeyRef> =
	if (clicked.isNotEmpty() && clicked.all { key -> key in current }) current - clicked else current + clicked

/**
 * The keys a mark stands for: every key stacked under a folded group's summary mark, or the one key a plain
 * mark names.
 *
 * @param ParameterId parameterId The section's parameter, which is part of a key's identity.
 * @param String rowKey The row the mark is drawn on.
 * @param Int markIndex The mark's ordinal on that row: a key's, or a summary's on a folded group row.
 * @return List<TrackKeyRef> The keys.
 */
internal fun KeyformSheetProjection.keysOfMark(parameterId: ParameterId, rowKey: String, markIndex: Int): List<TrackKeyRef> =
	summaryMembers(rowKey, markIndex) ?: listOf(TrackKeyRef(parameterId, rowKey, markIndex))

/**
 * The key selection a drag of a mark standing for [draggedKeys] works on: [current] when it already holds
 * every one of them, else exactly [draggedKeys].
 *
 * A partly-selected summary counts as not selected, for the reason [selectionAfterAdditiveClick] gives.  The
 * drag's start and its release both derive the selection here, so the two agree even when the release arrives
 * before the sheet has recomposed with what the start staged.
 *
 * @param Set current The key selection before the drag.
 * @param Collection draggedKeys The keys the dragged mark stands for.
 * @return Set The selection the drag works on.
 */
internal fun selectionDraggedWith(current: Set<TrackKeyRef>, draggedKeys: Collection<TrackKeyRef>): Set<TrackKeyRef> =
	if (current.containsAll(draggedKeys)) current else draggedKeys.toSet()

/**
 * The fraction of [parameter]'s range a drag of [mark] to [at] represents, or null when it is an ordinary
 * single-key move rather than a drag of the whole [selection].
 *
 * A fraction rather than an absolute delta because a selection can span two parameters (a linked pad shows
 * both axes at once) whose ranges differ by orders of magnitude.  Shared by the live preview and the release
 * so a drag can never preview as a group and commit as a single key, or the reverse.
 *
 * @param Parameter parameter The section's parameter.
 * @param Set selection The key selection the drag works on (see [selectionDraggedWith]).
 * @param Collection draggedKeys The keys the dragged mark stands for.
 * @param Boolean summary Whether the dragged mark is a folded group's summary mark.
 * @param TrackKeyMark mark The dragged mark, for the position the drag started from.
 * @param Float at The pointer's current (or released) domain position.
 * @return Float? The signed fraction, or null when this is not a group drag.
 */
internal fun groupDragFraction(
	parameter: Parameter,
	selection: Set<TrackKeyRef>,
	draggedKeys: Collection<TrackKeyRef>,
	summary: Boolean,
	mark: TrackKeyMark,
	at: Float,
): Float? {
	if (!selection.containsAll(draggedKeys) || (!summary && selection.size < 2)) {
		return null
	}

	val span = maxOf(parameter.max, parameter.min) - minOf(parameter.max, parameter.min)

	return if (span > 0f) (at - mark.position) / span else null
}

/**
 * The values a held-Ctrl scrub of empty track latches onto: every key on the section's track rows, once each.
 *
 * The rows are the ones the sheet's filters leave on screen, so a scrub latches only onto a key it can see,
 * as a key drag does; a key the Channels or Blend Shapes filter hides is no target here, though the
 * parameter slider, which shows every key, still snaps to it.
 *
 * @param List rows The section's root rows.
 * @param Set groupRowKeys The group rows' keys.
 * @return List The key positions, in row order.
 */
internal fun scrubSnapTargets(rows: List<TrackRow>, groupRowKeys: Set<String>): List<Float> {
	val positions = LinkedHashSet<Float>()
	forEachTrackRow(rows, groupRowKeys) { row -> row.marks.mapTo(positions) { mark -> mark.position } }

	return positions.toList()
}

/**
 * The values a held-Ctrl drag of a mark latches onto: the keys on other rows, so keys line up across tracks,
 * and the playhead, so a key can be moved to the pose on screen.
 *
 * A target moves every key the drag carries by the same offset, so it is dropped when that offset would set
 * any carried key down on a key its own track keeps: two keys in one place on a track are a span the
 * evaluator cannot resolve.  On the dragged mark's own rows that is a kept key at the target itself.  Keys of
 * another section's parameter travel by their own share of their own range and are not checked here.
 *
 * @param List rows The section's root rows.
 * @param Set groupRowKeys The group rows' keys.
 * @param ParameterId parameterId The section's parameter, which is part of a key's identity.
 * @param Collection draggedKeys The keys the dragged mark stands for.
 * @param Set moving The key selection the drag works on (see [selectionDraggedWith]).
 * @param Float startPosition Where the dragged mark sits before the drag.
 * @param Float? playhead The playhead's value, or null with none.
 * @return List The positions to latch onto, once each.
 */
internal fun markDragSnapTargets(
	rows: List<TrackRow>,
	groupRowKeys: Set<String>,
	parameterId: ParameterId,
	draggedKeys: Collection<TrackKeyRef>,
	moving: Set<TrackKeyRef>,
	startPosition: Float,
	playhead: Float?,
): List<Float> {
	val draggedRows = draggedKeys.mapTo(HashSet()) { key -> key.rowKey }
	val movingOrdinalsByRow =
		moving
			.filter { key -> key.parameterId == parameterId }
			.groupBy(keySelector = { key -> key.rowKey }, valueTransform = { key -> key.keyIndex })
			.mapValues { (_, ordinals) -> ordinals.toHashSet() }
	val candidates = LinkedHashSet<Float>()
	// Where the hand would have to land for a carried key to come down on a kept key of its own track.
	val blocked = HashSet<Float>()
	forEachTrackRow(rows, groupRowKeys) { row ->
		val movingOrdinals = movingOrdinalsByRow[row.key].orEmpty()
		val (carried, kept) = row.marks.partition { mark -> mark.keyIndex in movingOrdinals }

		if (row.key !in draggedRows) {
			kept.mapTo(candidates) { mark -> mark.position }
		}

		for (carriedMark in carried) {
			kept.mapTo(blocked) { keptMark -> startPosition + (keptMark.position - carriedMark.position) }
		}
	}

	if (playhead != null && abs(playhead - startPosition) > EPS_KEY) {
		candidates.add(playhead)
	}

	return candidates.filter { candidate -> blocked.none { held -> abs(held - candidate) <= EPS_KEY } }
}

/**
 * Runs [action] on every track row among [rows] and their whole subtrees.
 *
 * Group rows are skipped: they carry no keys of their own, and their summaries only repeat their children's.
 * The children are walked whether or not their group is expanded, since a folded key is still a key.
 *
 * @param List rows The rows to walk.
 * @param Set groupRowKeys The group rows' keys.
 * @param Function action What to do with each track row.
 */
private fun forEachTrackRow(rows: List<TrackRow>, groupRowKeys: Set<String>, action: (TrackRow) -> Unit) {
	for (row in rows) {
		if (row.key !in groupRowKeys) {
			action(row)
		}

		forEachTrackRow(row.children, groupRowKeys, action)
	}
}

/**
 * This row and its whole subtree with each mark's selection resolved against [selectedKeys].
 *
 * Recursive because selection is per-track but the tree is what gets rendered, and a child's marks have to
 * carry it whether or not its parent happens to be expanded.
 *
 * @param ParameterId parameterId The section's parameter, which is part of a key's identity.
 * @param Set<TrackKeyRef> selectedKeys The current selection.
 * @return TrackRow The row with selection applied.
 */
internal fun TrackRow.withSelection(parameterId: ParameterId, selectedKeys: Set<TrackKeyRef>): TrackRow =
	copy(
		marks =
			marks.map { mark ->
				val selected = TrackKeyRef(parameterId, key, mark.keyIndex) in selectedKeys
				mark.copy(selection = if (selected) TrackMarkSelection.Selected else TrackMarkSelection.Unselected)
			},
		children = children.map { child -> child.withSelection(parameterId, selectedKeys) },
	)

/**
 * The key [ref] names, as the (track, parameter, key ordinal) the edit ops take, or null when its row has no
 * track in this projection.
 *
 * The null is what makes a stale ref harmless: a row that is gone, filtered out, or read-only (a group row,
 * a blend-shape binding) resolves to nothing, so the ref simply drops out of whatever acts on the selection.
 *
 * @param Parameter parameter The projection's parameter.
 * @param TrackKeyRef ref The key, as the sheet names it.
 * @return Triple? The key the edit ops take, or null.
 */
internal fun KeyformSheetProjection.trackKeyOf(parameter: Parameter, ref: TrackKeyRef): Triple<KeyformTrackRef, Parameter, Int>? =
	tracksByRowKey[ref.rowKey]?.let { track -> Triple(track, parameter, ref.keyIndex) }

/**
 * Each of [refs] paired with the key it names, resolved in the section of its own parameter; a ref no
 * section resolves drops out.
 *
 * Across EVERY section, because a linked pad shows two and one selection can span both.  In the order of
 * [refs], because a drag's landings come back position for position.
 *
 * @param Iterable refs The keys, as the sheet names them.
 * @param List projections Each targeted parameter and its tracks.
 * @return List Each resolved ref, paired with the key the edit ops take.
 */
internal fun resolveTrackKeys(
	refs: Iterable<TrackKeyRef>,
	projections: List<Pair<Parameter, KeyformSheetProjection>>,
): List<Pair<TrackKeyRef, Triple<KeyformTrackRef, Parameter, Int>>> =
	refs.mapNotNull { ref ->
		projections
			.firstOrNull { (parameter, _) -> parameter.id == ref.parameterId }
			?.let { (parameter, projection) -> projection.trackKeyOf(parameter, ref) }
			?.let { key -> ref to key }
	}

/**
 * Previews ([commit] false) or applies ([commit] true) a drag of the WHOLE key selection by [fraction] of
 * each key's parameter range.
 *
 * The preview goes through the same clamp as the release, so it never shows the group traveling past the
 * wall it is about to stop at, and only the release records an undo step.  The sheet runs this rather than a
 * section because one selection can span both sections of a linked pad.
 *
 * @param EditorSession? session The editing session, or null with none, which only clears the preview.
 * @param List projections Each targeted parameter and its tracks.
 * @param Float fraction The drag as a signed fraction of each parameter's range.
 * @param TrackKeyDragAnchor? anchor The key under the hand, which a commit lands exactly where it was let go;
 *   null for a nudge, and unused by a preview.
 * @param Boolean commit Whether the drag has been released.
 */
internal fun KeyformSheetViewState.dragKeySelection(
	session: EditorSession?,
	projections: List<Pair<Parameter, KeyformSheetProjection>>,
	fraction: Float,
	anchor: TrackKeyDragAnchor?,
	commit: Boolean,
) {
	val plan = resolveTrackKeys(session?.keySelection?.value.orEmpty(), projections)

	if (session == null || plan.isEmpty()) {
		dragPreviewFraction = null
	} else if (commit) {
		dragPreviewFraction = null
		session.dragTrackKeysKeepingSelection(plan, fraction, anchor)
	} else {
		dragPreviewFraction = session.model.value.limitedDragFraction(plan.map { (_, key) -> key }, fraction)
	}
}