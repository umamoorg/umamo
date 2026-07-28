package org.umamo.edit

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
 *
 * トラック参照から実装（チャンネル／ジオメトリ）への唯一の振り分け。
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
 * Moves [track]'s key at [fromValue] to [toValue], as one undo step.
 *
 * @param KeyformTrackRef track The track to edit.
 * @param Parameter parameter The parameter whose axis the key sits on.
 * @param Int keyIndex The key's ordinal on that axis.
 * @param Float toValue The new position.
 */
fun EditorSession.moveTrackKey(track: KeyformTrackRef, parameter: Parameter, keyIndex: Int, toValue: Float) {
	// Clamped to the parameter's own range HERE rather than at each call site, so a pointer drag and a
	// keyboard nudge cannot disagree about where the ends are.  The grid then clamps at the neighbours;
	// between them, a key can never leave the range the sheet rules against.
	val clamped = toValue.coerceIn(minOf(parameter.min, parameter.max), maxOf(parameter.min, parameter.max))
	mutate(KeyformChange.MoveKey(channelOf(track))) { model ->
		model.withTrackKeyMoved(track, parameter, keyIndex, clamped)
	}
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
	mutate(KeyformChange.InsertKey(channelOf(track))) { model ->
		model.withTrackKeyInserted(track, parameter, position, pose.value)
	}
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
			val range = minOf(parameter.min, parameter.max)..maxOf(parameter.min, parameter.max)
			current.withTrackKeyMoved(track, parameter, keyIndex, (currentValue + step).coerceIn(range))
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
		when (track) {
			is KeyformTrackRef.Channel ->
				channelGridsOf(track.owner)?.get(track.target.channel)?.axes?.firstOrNull { candidate ->
					candidate.parameterId == parameter.id
				}

			is KeyformTrackRef.Geometry ->
				geometryAxisOf(track.owner, parameter.id)
		} ?: return null
	return axis.keys.getOrNull(keyIndex)
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
	mutate(KeyformChange.RemoveKey(channelOf(keys.first().first))) { model ->
		keys
			.sortedByDescending { (_, _, keyIndex) -> keyIndex }
			.fold(model) { current, (track, parameter, keyIndex) ->
				current.withTrackKeyRemoved(track, parameter, keyIndex)
			}
	}
}
