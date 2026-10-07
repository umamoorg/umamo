package org.umamo.edit.keyform

import org.umamo.edit.EditorSession
import org.umamo.edit.TrackKeyRef
import org.umamo.runtime.model.KeyformTrackRef
import org.umamo.runtime.model.Parameter

/*
 * What an edit to a track's keys does to the keyform sheet's key selection.
 *
 * A [TrackKeyRef] names a row and an ordinal on that row's track, and a key edit can renumber a track: a
 * removal slides the keys above it down, an insert pushes them up, and a move or a drag that crosses a
 * neighbour re-sorts the axis.  A selection left untouched across one stops naming the key the user
 * selected and names whichever key took its ordinal instead.
 *
 * The rules live in :edit rather than in the sheet because they are statements about what an edit MEANS,
 * and several call sites need to agree on each - the aimed removal behind Alt+I and the lane menu, the
 * selected-keys removal behind Delete, and the summary-mark removal all go through one rule, and a
 * multi-key drag, a summary-mark drag, and the arrow-key nudge through another.  A [TrackKeyRef]'s row key
 * is opaque here, which is all this needs: the algebra compares row keys, it never resolves one, and a
 * caller that edits a track pairs each ref with the track it resolves to.
 */

/**
 * [current] with [removed] taken out and every ordinal that renumbers under them corrected.
 *
 * Only two kinds of ref are at risk from a removal: the removed keys themselves, and the LATER ordinals on
 * the same row, which slide down one place per key removed below them.  Everything else names exactly what
 * it named before, so a mark the user had not selected must leave their selection untouched rather than
 * clearing it wholesale.
 *
 * Matched on the parameter as well as the row because a linked pair renders one row under two sections, and
 * a removal renumbers only the axis it happened on.
 *
 * @param Set<TrackKeyRef> current The selection before the removal.
 * @param Set<TrackKeyRef> removed The keys the removal takes out.
 * @return Set<TrackKeyRef> The selection that survives it.
 */
fun selectionAfterKeyRemoval(current: Set<TrackKeyRef>, removed: Set<TrackKeyRef>): Set<TrackKeyRef> {
	if (removed.isEmpty()) {
		return current
	}
	return current
		.filterNot { key -> key in removed }
		.map { key ->
			val removedBelow =
				removed.count { gone ->
					gone.parameterId == key.parameterId && gone.rowKey == key.rowKey && gone.keyIndex < key.keyIndex
				}
			if (removedBelow == 0) key else key.copy(keyIndex = key.keyIndex - removedBelow)
		}.toSet()
}

/**
 * [current] with every ordinal at or above [inserted] shifted up to make room for it.
 *
 * The mirror of [selectionAfterKeyRemoval]: an insert renumbers every key at or above where it lands, so a
 * selection on one of those keys must shift up by one to keep naming the same key.  A key below the
 * insertion point already names the right key and is left untouched.
 *
 * @param Set<TrackKeyRef> current The selection before the insert.
 * @param TrackKeyRef inserted The new key, at the ordinal it takes.
 * @return Set<TrackKeyRef> The selection as it reads afterwards.
 */
fun selectionAfterKeyInsertion(current: Set<TrackKeyRef>, inserted: TrackKeyRef): Set<TrackKeyRef> =
	current
		.map { key ->
			val renumbers =
				key.parameterId == inserted.parameterId &&
					key.rowKey == inserted.rowKey &&
					key.keyIndex >= inserted.keyIndex
			if (renumbers) key.copy(keyIndex = key.keyIndex + 1) else key
		}.toSet()

/**
 * Runs [removeKeys] with the key selection re-pointed at what survives it, as ONE undo step.
 *
 * An empty [removed] is the caller saying it cannot name what it removed in sheet terms (a Properties row
 * has no sheet row), and leaves the selection exactly as it was.
 *
 * @param Set<TrackKeyRef> removed The keys [removeKeys] takes out, in sheet-row terms.
 * @param Function removeKeys The removal itself, which records its own step.
 */
fun EditorSession.removingKeys(removed: Set<TrackKeyRef>, removeKeys: () -> Unit) {
	editingKeys({ current -> selectionAfterKeyRemoval(current, removed) }, removeKeys)
}

/**
 * Runs [insertKey] with the key selection re-pointed past the key it adds, as ONE undo step.
 *
 * A null [inserted] is the caller saying no key is added, or that it cannot name the new one in sheet terms,
 * and leaves the selection exactly as it was.
 *
 * @param TrackKeyRef? inserted The new key at the ordinal it takes, or null when none is added.
 * @param Function insertKey The insert itself, which records its own step.
 */
fun EditorSession.insertingKey(inserted: TrackKeyRef?, insertKey: () -> Unit) {
	editingKeys({ current -> if (inserted == null) current else selectionAfterKeyInsertion(current, inserted) }, insertKey)
}

/**
 * Drags every key in [keys] by [fraction] of its parameter's range, with the key selection re-pointed at
 * where they land, as ONE undo step - the keyform sheet's multi-key drag, its summary-mark drag, and its
 * arrow-key nudge.
 *
 * The selection becomes exactly [keys] at their landed ordinals: a crossing renumbers the axis, so the refs
 * as they read before the drag would name whichever keys took those places.  A selected ref the caller could
 * not resolve to a track is not in [keys], so it drops out of the selection here.
 *
 * STAGE, EDIT, CONFIRM (see [EditorSession.stageKeySelection]), with the landings asked for BEFORE the drag
 * so the re-pointed selection rides the drag's own snapshot.  The confirm is what records the re-pointing
 * when the drag itself records nothing (clamped to a standstill against a range wall).
 *
 * @param List keys Each dragged key as the sheet names it, paired with the (track, parameter, key ordinal)
 *   it resolves to.
 * @param Float fraction The drag as a signed fraction of each parameter's range.
 */
fun EditorSession.dragTrackKeysKeepingSelection(keys: List<Pair<TrackKeyRef, Triple<KeyformTrackRef, Parameter, Int>>>, fraction: Float) {
	if (keys.isEmpty()) {
		return
	}
	val dragged = keys.map { (_, key) -> key }
	val landed = model.value.trackKeyDragLandings(dragged, fraction)
	val landedSelection =
		keys.mapIndexed { position, (keyRef, _) -> keyRef.copy(keyIndex = landed.getOrElse(position) { keyRef.keyIndex }) }.toSet()
	stageKeySelection(landedSelection)
	dragTrackKeys(dragged, fraction)
	setKeySelection(landedSelection)
}

/**
 * Moves one key to [toValue] and leaves it selected, as ONE undo step - the keyform sheet's single-mark drag.
 *
 * A key already in [selection] keeps the rest of the selection with it.  Any other key REPLACES the
 * selection, exactly as a click on it would: dragging an unselected key is a selection of that key first,
 * which is how every editor with a selection treats it.
 *
 * A move may cross the key's neighbours, which renumbers the axis, so the key's ref has to follow it to the
 * ordinal it lands on; keeping the old ordinal would leave the selection on whichever key took its place.
 * STAGE, EDIT, CONFIRM, with the landing asked for BEFORE the move so the selection rides the move's own
 * snapshot: staged afterwards, every recorded step would hold the pre-move ordinal, which redo would then
 * restore onto the wrong key.  The confirm records the selection when the move records nothing (released
 * where it was picked up).
 *
 * @param TrackKeyRef key The moved key as the sheet names it.
 * @param KeyformTrackRef track The track the key sits on.
 * @param Parameter parameter The parameter whose axis the key sits on.
 * @param Float toValue Where the key was released, in the parameter's units.
 * @param Set<TrackKeyRef> selection The key selection the gesture was made against.
 */
fun EditorSession.moveTrackKeySelectingIt(
	key: TrackKeyRef,
	track: KeyformTrackRef,
	parameter: Parameter,
	toValue: Float,
	selection: Set<TrackKeyRef>,
) {
	val landedIndex = model.value.trackKeyIndexAfterMove(track, parameter, key.keyIndex, toValue)
	val keptSelection = if (key in selection) selection - key else emptySet()
	val landedSelection = keptSelection + key.copy(keyIndex = landedIndex)
	stageKeySelection(landedSelection)
	moveTrackKey(track, parameter, key.keyIndex, toValue)
	setKeySelection(landedSelection)
}

/**
 * Runs [edit] with the key selection put through [reconcile], as ONE undo step.
 *
 * STAGE, EDIT, CONFIRM (see [EditorSession.stageKeySelection]): the reconciled selection is staged first so
 * the edit's own snapshot records it, and confirmed afterwards so it still reaches history when the edit
 * declines to record anything.  Staging it AFTER instead would leave every recorded step holding the
 * pre-edit refs, so undoing back to one would point the selection at whichever keys had taken those ordinals.
 *
 * An edit that refuses is rolled back to the selection as it was, because nothing renumbered - the
 * re-pointing would then be a shift with no edit under it.  Measured by model identity, which the edit ops
 * maintain: a refused edit hands back the instance it was given.
 *
 * @param Function reconcile The selection as it reads after [edit], given how it reads now.
 * @param Function edit The edit itself, which records its own step.
 */
private fun EditorSession.editingKeys(reconcile: (Set<TrackKeyRef>) -> Set<TrackKeyRef>, edit: () -> Unit) {
	val selectionBefore = keySelection.value
	val reconciled = reconcile(selectionBefore)
	val modelBeforeEdit = model.value
	stageKeySelection(reconciled)
	edit()
	if (model.value === modelBeforeEdit) {
		stageKeySelection(selectionBefore)
	} else {
		setKeySelection(reconciled)
	}
}