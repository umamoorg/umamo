package org.umamo.edit

import org.umamo.runtime.keyform.keyIndexAfterMove
import org.umamo.runtime.keyform.keyIndexAt
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.KeyformTrackRef
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.channelGridsOf

/*
 * The one dispatch from "a track the sheet is pointing at" to the channel or geometry implementation.
 *
 * The sheet applies the same three gestures - drag a key, insert one, remove one - to every row it draws,
 * and which underlying op that becomes is not a decision the UI should be making twice per gesture.  It is
 * made once, here.
 */

/**
 * This model with [track]'s key at [fromValue] moved to [toValue] on [parameter]'s axis.
 *
 * @param KeyformTrackRef track The track to edit.
 * @param Parameter parameter The parameter whose axis the key sits on.
 * @param Int keyIndex The key's ordinal on that axis.
 * @param Float toValue The requested new position.
 * @return PuppetModel The model with the key moved, or this on a refusal.
 */
fun PuppetModel.withTrackKeyMoved(
	track: KeyformTrackRef,
	parameter: Parameter,
	keyIndex: Int,
	toValue: Float,
): PuppetModel =
	when (track) {
		is KeyformTrackRef.Channel -> withChannelKeyMoved(track.target, parameter, keyIndex, toValue)
		is KeyformTrackRef.Geometry -> withGeometryKeyMoved(track.owner, parameter, keyIndex, toValue)
	}

/**
 * This model with a shape-preserving key inserted at [position] on [track]'s axis for [parameter].
 *
 * @param KeyformTrackRef track The track to edit.
 * @param Parameter parameter The parameter whose axis to insert on.
 * @param Float position The new key's parameter value.
 * @param Pose pose The current pose, which fixes the cell on every OTHER axis of a multi-keyed track.
 * @return PuppetModel The model with the key inserted, or this on a refusal.
 */
fun PuppetModel.withTrackKeyInserted(
	track: KeyformTrackRef,
	parameter: Parameter,
	position: Float,
	pose: Pose,
): PuppetModel =
	when (track) {
		is KeyformTrackRef.Channel -> {
			val posed = pose + (parameter.id to position)
			val heldValue = channelValueAt(track.target, posed)
			if (heldValue == null) this else withChannelKeyCaptured(track.target, parameter, posed, heldValue)
		}

		is KeyformTrackRef.Geometry -> withGeometryKeyInserted(track.owner, parameter, position)
	}

/**
 * This model with [track]'s key at [position] removed from [parameter]'s axis.
 *
 * @param KeyformTrackRef track The track to edit.
 * @param Parameter parameter The parameter whose axis to remove from.
 * @param Int keyIndex The key's ordinal on that axis.
 * @return PuppetModel The model with the key removed, or this on a refusal.
 */
fun PuppetModel.withTrackKeyRemoved(track: KeyformTrackRef, parameter: Parameter, keyIndex: Int): PuppetModel =
	when (track) {
		is KeyformTrackRef.Channel -> withChannelKeyRemovedAt(track.target, parameter, keyIndex)
		is KeyformTrackRef.Geometry -> withGeometryKeyRemoved(track.owner, parameter, keyIndex)
	}

/** The history-label channel for [track] - null for geometry, which has no channel to name. */
private fun channelOf(track: KeyformTrackRef) =
	when (track) {
		is KeyformTrackRef.Channel -> track.target.channel
		is KeyformTrackRef.Geometry -> null
	}

/**
 * [value] clamped into [parameter]'s range, tolerating a reversed (min > max) range from a malformed
 * import.
 *
 * Centralized HERE rather than at each call site, so a pointer drag, a keyboard nudge, and a lane-edge
 * insert cannot disagree about where the ends are.
 *
 * @param Float value The requested parameter value.
 * @param Parameter parameter The parameter whose range bounds it.
 * @return Float The clamped value.
 */
private fun clampToParameterRange(value: Float, parameter: Parameter): Float =
	value.coerceIn(minOf(parameter.min, parameter.max), maxOf(parameter.min, parameter.max))

/**
 * Moves [track]'s key at [fromValue] to [toValue], as one undo step.
 *
 * @param KeyformTrackRef track The track to edit.
 * @param Parameter parameter The parameter whose axis the key sits on.
 * @param Int keyIndex The key's ordinal on that axis.
 * @param Float toValue The new position.
 * @return Int The ordinal the key holds AFTER the move, which a crossing changes.
 */
fun EditorSession.moveTrackKey(
	track: KeyformTrackRef,
	parameter: Parameter,
	keyIndex: Int,
	toValue: Float,
): Int {
	// Within the parameter's range a key may cross its neighbours freely; the grid re-sorts and permutes
	// its cells to match.  Only the range itself is a wall - a key outside it can never be reached again.
	val clamped = clampToParameterRange(toValue, parameter)
	// Resolved BEFORE the mutate, against the grid this ordinal still refers to.  A crossing renumbers the
	// axis, so afterwards the ordinal names a different key - which is exactly why the caller has to be
	// told the new one rather than left holding the old.
	val landedIndex =
		trackGridOf(model.value, track)?.keyIndexAfterMove(parameter.id, keyIndex, clamped) ?: keyIndex
	mutate(KeyformChange.MoveKey(channelOf(track))) { model ->
		model.withTrackKeyMoved(track, parameter, keyIndex, clamped)
	}
	return landedIndex
}

/**
 * Inserts a shape-preserving key at [position] on [track], as one undo step.
 *
 * Shape-preserving means the new key holds whatever the track already evaluates to there, so the insert is
 * a handle to edit from rather than an edit in itself.
 *
 * @param KeyformTrackRef track The track to edit.
 * @param Parameter parameter The parameter whose axis to insert on.
 * @param Float position The new key's parameter value.
 */
fun EditorSession.insertTrackKeyAt(track: KeyformTrackRef, parameter: Parameter, position: Float) {
	// Clamped like a move: the lane's edge inset extrapolates a hit past the domain ends, and an unclamped
	// insert would author a key outside the range that no scrub can ever reach.
	val clamped = clampToParameterRange(position, parameter)
	mutate(KeyformChange.InsertKey(channelOf(track))) { model ->
		model.withTrackKeyInserted(track, parameter, clamped, pose.value)
	}
}

/**
 * Moves every key in [keys] to [toValue] as ONE undo step - the summary-row drag.
 *
 * They go to the same place rather than by the same delta because that is what dragging a summary mark
 * means: the marks were stacked at one value and stay stacked.  Each still lands through its own track's
 * `withKeyMoved`, so a key that would collide with a neighbour on ITS track is nudged the sub-pixel
 * distance that keeps the span resolvable - the group can drift apart by at most EPS_SPAN, which is far
 * below anything the sheet can draw.
 *
 * @param List keys The (track, parameter, key ordinal) triples to move.
 * @param Float toValue The destination, in the parameter's units.
 */
fun EditorSession.moveTrackKeys(keys: List<Triple<KeyformTrackRef, Parameter, Int>>, toValue: Float) {
	if (keys.isEmpty()) {
		return
	}
	mutate(KeyformChange.MoveKey(channelOf(keys.first().first))) { model ->
		keys.fold(model) { current, (track, parameter, keyIndex) ->
			current.withTrackKeyMoved(track, parameter, keyIndex, clampToParameterRange(toValue, parameter))
		}
	}
}

/**
 * Drags every key in [keys] by [fraction] of its parameter's range, as ONE undo step - the multi-select
 * mark drag.
 *
 * By a fraction rather than an absolute delta because a selection can span two parameters (a linked pad
 * shows both axes at once) whose ranges differ by orders of magnitude.  Within ONE parameter a fraction of
 * its range IS the absolute delta, so the common case is exactly what the hand did; across two it is the
 * only reading that keeps the group together on screen.
 *
 * Clamped to the MOST CONSTRAINED member rather than per key: clamping individually would let the keys
 * nearest an end pile up while the rest kept travelling, silently destroying the spacing the rigger
 * authored.  Reducing the whole drag instead just stops the group at the wall.
 *
 * Each key is re-found BY VALUE inside the fold rather than trusted to keep its ordinal: a key that crosses
 * an unselected neighbour renumbers the axis under the keys still to be moved.
 *
 * @param List keys The (track, parameter, key ordinal) triples to drag.
 * @param Float fraction The drag as a signed fraction of each parameter's range.
 * @return List<Int> Each key's ordinal AFTER the drag, in the order given - crossings renumber the axis, so
 *   a caller holding the old ordinals would be pointing at whichever keys took their place.
 */
fun EditorSession.dragTrackKeys(keys: List<Triple<KeyformTrackRef, Parameter, Int>>, fraction: Float): List<Int> {
	val startingOrdinals = keys.map { (_, _, keyIndex) -> keyIndex }
	if (keys.isEmpty() || fraction == 0f) {
		return startingOrdinals
	}
	val beforeDrag = model.value
	// Aligned WITH keys, holding null where a ref no longer resolves - the caller gets one ordinal back per
	// key it passed, so a stale ref must not shift every later answer by one.
	val moves =
		keys.map { (track, parameter, keyIndex) ->
			beforeDrag.trackKeyValue(track, parameter, keyIndex)?.let { fromValue -> KeyDrag(track, parameter, fromValue) }
		}
	val live = moves.filterNotNull()
	if (live.isEmpty()) {
		return startingOrdinals
	}
	val effectiveFraction = live.fold(fraction) { limited, move -> move.limitedTo(limited) }
	if (effectiveFraction == 0f) {
		return startingOrdinals
	}
	val destinations = live.associateWith { move -> move.destinationAt(effectiveFraction) }
	mutate(KeyformChange.MoveKey(channelOf(keys.first().first))) { current ->
		// Nearest the destination first, so a run of keys shuffling the same way cannot have an earlier one
		// clamp against a neighbour that is about to move out of the way.
		val ordered = if (effectiveFraction > 0f) live.sortedByDescending { it.fromValue } else live.sortedBy { it.fromValue }
		ordered.fold(current) { working, move ->
			val liveIndex = working.trackKeyIndexAt(move.track, move.parameter, move.fromValue)
			if (liveIndex < 0) {
				working
			} else {
				working.withTrackKeyMoved(move.track, move.parameter, liveIndex, destinations.getValue(move))
			}
		}
	}
	// Re-resolved against the NEW model by the value each key was sent to, which is the only description of
	// a key that survives its own move.
	val afterDrag = model.value
	return keys.mapIndexed { position, (track, parameter, keyIndex) ->
		val destination = moves[position]?.let { move -> destinations[move] } ?: return@mapIndexed keyIndex
		afterDrag.trackKeyIndexAt(track, parameter, destination).takeIf { index -> index >= 0 } ?: keyIndex
	}
}

/**
 * One key's in-flight drag: where it is now, and what bounds how far it may go.
 *
 * @property KeyformTrackRef track The track it sits on.
 * @property Parameter parameter The parameter whose axis it sits on.
 * @property Float fromValue Its position before the drag.
 */
private class KeyDrag(val track: KeyformTrackRef, val parameter: Parameter, val fromValue: Float) {
	/** The parameter's span, floored so a degenerate (min == max) parameter cannot divide by zero. */
	private val span: Float get() = (maxOf(parameter.min, parameter.max) - minOf(parameter.min, parameter.max)).takeIf { it > 0f } ?: 1f

	/**
	 * [fraction] reduced, if needed, so this key stays inside its parameter's range.
	 *
	 * @param Float fraction The requested drag.
	 * @return Float The fraction this key can take, same sign or zero.
	 */
	fun limitedTo(fraction: Float): Float {
		val low = minOf(parameter.min, parameter.max)
		val high = maxOf(parameter.min, parameter.max)
		return if (fraction > 0f) {
			minOf(fraction, ((high - fromValue) / span).coerceAtLeast(0f))
		} else {
			maxOf(fraction, ((low - fromValue) / span).coerceAtMost(0f))
		}
	}

	/**
	 * Where this key lands at [fraction] of its parameter's range.
	 *
	 * @param Float fraction The (already limited) drag.
	 * @return Float The destination, clamped defensively to the range.
	 */
	fun destinationAt(fraction: Float): Float =
		(fromValue + span * fraction).coerceIn(minOf(parameter.min, parameter.max), maxOf(parameter.min, parameter.max))
}

/**
 * Nudges every key in [keys] along its parameter by [fraction] of that parameter's range, as ONE undo step.
 *
 * Relative to each parameter's own range rather than an absolute step, because ranges differ by orders of
 * magnitude across a rig (an angle spans 60, an open/close spans 1) and one absolute step would be either
 * imperceptible on the first or catastrophic on the second.
 *
 * Applied in the direction of travel - keys nearest the destination first - so a run of keys shuffling the
 * same way cannot have an earlier one clamp against a neighbour that is about to move out of the way.
 *
 * @param List keys The (track, parameter, key ordinal) triples to nudge.
 * @param Float fraction The step as a signed fraction of each parameter's range.
 */
fun EditorSession.nudgeTrackKeys(keys: List<Triple<KeyformTrackRef, Parameter, Int>>, fraction: Float) {
	if (keys.isEmpty() || fraction == 0f) {
		return
	}
	mutate(KeyformChange.MoveKey(channelOf(keys.first().first))) { model ->
		val ordered = if (fraction > 0f) keys.sortedByDescending { it.third } else keys.sortedBy { it.third }
		ordered.fold(model) { current, (track, parameter, keyIndex) ->
			val currentValue = current.trackKeyValue(track, parameter, keyIndex) ?: return@fold current
			val step = (parameter.max - parameter.min) * fraction
			current.withTrackKeyMoved(track, parameter, keyIndex, clampToParameterRange(currentValue + step, parameter))
		}
	}
}

/**
 * The parameter value key [keyIndex] currently sits at on [track], or null when there is no such key.
 *
 * @param KeyformTrackRef track The track to read.
 * @param Parameter parameter The parameter whose axis to read.
 * @param Int keyIndex The key's ordinal on that axis.
 * @return Float? Its current position, or null.
 */
fun PuppetModel.trackKeyValue(track: KeyformTrackRef, parameter: Parameter, keyIndex: Int): Float? {
	val axis =
		trackGridOf(this, track)?.axes?.firstOrNull { candidate -> candidate.parameterId == parameter.id }
			?: return null
	return axis.keys.getOrNull(keyIndex)
}

/**
 * The ordinal of [track]'s key at the current [pose] on [parameter]'s axis, or -1 when the pose is not on
 * one.
 *
 * Within EPS_KEY, the same tolerance `bindBracket` snaps with - so "on a key" here means exactly what it
 * means to the evaluator, and the field tint, the shortcut, and the rendered result cannot disagree about
 * whether the pose is sitting on a key.
 *
 * @param KeyformTrackRef track The track to read.
 * @param Parameter parameter The parameter whose axis to read.
 * @param Pose pose The pose to resolve against.
 * @return Int The key's ordinal, or -1.
 */
fun PuppetModel.trackKeyIndexAtPose(track: KeyformTrackRef, parameter: Parameter, pose: Pose): Int {
	val grid = trackGridOf(this, track) ?: return -1
	return grid.keyIndexAt(parameter.id, pose[parameter.id] ?: parameter.default)
}

/**
 * The ordinal of [track]'s key sitting at [value] on [parameter]'s axis, or -1 when none does.
 *
 * Within EPS_KEY, the same tolerance the evaluator snaps with - so "the key at this value" means what it
 * means everywhere else.  The value-addressed sibling of [trackKeyIndexAtPose], for the one case where an
 * ordinal cannot be trusted: after a move that may have crossed a neighbour and renumbered the axis.
 *
 * @param KeyformTrackRef track The track to read.
 * @param Parameter parameter The parameter whose axis to read.
 * @param Float value The parameter value to look at.
 * @return Int The key's ordinal, or -1.
 */
fun PuppetModel.trackKeyIndexAt(track: KeyformTrackRef, parameter: Parameter, value: Float): Int =
	trackGridOf(this, track)?.keyIndexAt(parameter.id, value) ?: -1

/** The grid behind [track] - its owner's channel track or its geometry - or null when there is none. */
private fun trackGridOf(puppet: PuppetModel, track: KeyformTrackRef): KeyformGrid<*>? =
	when (track) {
		is KeyformTrackRef.Channel -> puppet.channelGridsOf(track.owner)?.get(track.target.channel)
		is KeyformTrackRef.Geometry -> puppet.geometryGridOf(track.owner)
	}

/**
 * Removes every key in [keys] as ONE undo step - the sheet's Delete over a multi-key selection.
 *
 * One step rather than one per key because the user made one gesture; a per-key step would make undoing a
 * five-key delete take five presses.  Applied in DESCENDING index order so each removal cannot renumber
 * the keys the later ones still have to find.
 *
 * @param List keys The (track, parameter, key ordinal) triples to remove.
 */
fun EditorSession.removeTrackKeys(keys: List<Triple<KeyformTrackRef, Parameter, Int>>) {
	if (keys.isEmpty()) {
		return
	}
	mutate(KeyformChange.DeleteKey(channelOf(keys.first().first))) { model ->
		keys
			.sortedByDescending { (_, _, keyIndex) -> keyIndex }
			.fold(model) { current, (track, parameter, keyIndex) ->
				current.withTrackKeyRemoved(track, parameter, keyIndex)
			}
	}
}
