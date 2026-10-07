package org.umamo.ui.workspace.spaces.keyformsheet

import org.umamo.edit.EditorSession
import org.umamo.edit.TrackKeyRef
import org.umamo.edit.keyform.dragTrackKeysKeepingSelection
import org.umamo.edit.keyform.limitedDragFraction
import org.umamo.runtime.model.KeyformTrackRef
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.ui.tracks.TrackKeyMark
import org.umamo.ui.tracks.TrackRow

/*
 * The sheet's side of the key selection: what a click does to it, how its refs resolve to the keys the edit
 * ops take, and the drag that moves all of it.  The selection itself is session state
 * (EditorSession.keySelection), so undo can restore it, and what an edit does to it lives in :edit.
 */

/**
 * [current] with [clicked] toggled in or out - what a Shift+click on a mark leaves behind.
 *
 * TOGGLE rather than add-only because a click is the only gesture that can take a single mark back OUT of
 * a selection without starting over; the marquee stays add-only, which is the convention for a region
 * gesture.
 *
 * A summary mark's whole membership toggles as ONE unit - all of them or none - so a folded group cannot
 * end up half-selected by a gesture that showed the user a single mark.  Partly-selected therefore counts
 * as "not selected" and the click completes it, which is the reading that lets a second click undo the
 * first.
 *
 * Internal rather than private so the rule can be pinned without a composition: the UI test can only
 * reach as far as "Shift arrived", and what the sheet then DOES with it is the part worth stating.
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
 * Dragging a mark that is not selected selects it first, the way a click on it would, and the drag then
 * moves what it selected - which is how every editor with a selection treats a drag.  A partly-selected
 * summary counts as not selected, for the reason [selectionAfterAdditiveClick] gives: a folded group must
 * not be dragged half-selected by a gesture that showed the user a single mark.
 *
 * The drag's start and its release both derive the selection here, so the two agree even when the release
 * arrives before the sheet has recomposed with what the start staged.
 *
 * @param Set current The key selection before the drag.
 * @param Collection draggedKeys The keys the dragged mark stands for.
 * @return Set The selection the drag works on.
 */
internal fun selectionDraggedWith(current: Set<TrackKeyRef>, draggedKeys: Collection<TrackKeyRef>): Set<TrackKeyRef> =
	if (current.containsAll(draggedKeys)) current else draggedKeys.toSet()

/**
 * The fraction of [parameter]'s range a drag of [mark] to [at] represents, or null when it is not a GROUP
 * drag at all.
 *
 * A GROUP drag moves the whole [selection].  A summary mark always drags that way, because the keys it
 * stands for are only reachable through the selection; a plain mark does once anything else is selected with
 * it.  Null - meaning "handle this as an ordinary single-key move" - when the dragged keys are not all in
 * [selection], when a plain mark is the whole selection, or when the parameter has no range to take a
 * fraction of.
 *
 * A fraction rather than an absolute delta because a selection can span two parameters (a linked pad shows
 * both axes at once) whose ranges differ by orders of magnitude.  Within ONE parameter a fraction of its
 * range IS the distance the hand moved.
 *
 * Shared by the live preview and the release so the two cannot disagree about which gesture this is - the
 * failure mode being a drag that previews as a group and commits as a single key, or the reverse.
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
 * This row and its whole subtree with each mark's selected flag resolved against [selectedKeys].
 *
 * Recursive because selection is per-track but the tree is what gets rendered, and a child's marks have to
 * carry the flag whether or not its parent happens to be expanded.
 *
 * @param ParameterId parameterId The section's parameter, which is part of a key's identity.
 * @param Set<TrackKeyRef> selectedKeys The current selection.
 * @return TrackRow The row with selection applied.
 */
internal fun TrackRow.withSelection(parameterId: ParameterId, selectedKeys: Set<TrackKeyRef>): TrackRow =
	copy(
		marks =
			marks.map { mark ->
				mark.copy(
					selected =
						TrackKeyRef(
							parameterId,
							key,
							mark.keyIndex,
						) in selectedKeys,
				)
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
 * PREVIEW then COMMIT, through the same clamp: while the pointer moves, the fraction is only recorded (the
 * marks draw shifted by it and the model is untouched); on release it is applied as one undo step.  A
 * per-move commit would push an undo entry per pixel, and a preview at the unclamped fraction would show
 * the group travelling past the wall it is about to stop at.
 *
 * Resolved against EVERY section, because a linked pad shows two and one box select can enclose keys in
 * both - which is why the sheet runs this and hands it to its sections as an action, since a section sees
 * only its own projection.
 *
 * @param EditorSession? session The editing session, or null with none, which only clears the preview.
 * @param List projections Each targeted parameter and its tracks.
 * @param Float fraction The drag as a signed fraction of each parameter's range.
 * @param Boolean commit Whether the drag has been released.
 */
internal fun KeyformSheetViewState.dragKeySelection(
	session: EditorSession?,
	projections: List<Pair<Parameter, KeyformSheetProjection>>,
	fraction: Float,
	commit: Boolean,
) {
	val plan = resolveTrackKeys(session?.keySelection?.value.orEmpty(), projections)
	if (session == null || plan.isEmpty()) {
		dragPreviewFraction = null
	} else if (commit) {
		dragPreviewFraction = null
		session.dragTrackKeysKeepingSelection(plan, fraction)
	} else {
		dragPreviewFraction = session.model.value.limitedDragFraction(plan.map { (_, key) -> key }, fraction)
	}
}