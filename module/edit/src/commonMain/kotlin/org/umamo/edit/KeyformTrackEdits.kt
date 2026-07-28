package org.umamo.edit

import org.umamo.runtime.model.KeyformTrackRef
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.PuppetModel

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
 * @param Float fromValue The key's current position.
 * @param Float toValue The requested new position.
 * @return PuppetModel The model with the key moved, or this on a refusal.
 */
fun PuppetModel.withTrackKeyMoved(
	track: KeyformTrackRef,
	parameter: Parameter,
	fromValue: Float,
	toValue: Float,
): PuppetModel =
	when (track) {
		is KeyformTrackRef.Channel -> withChannelKeyMoved(track.target, parameter, fromValue, toValue)
		is KeyformTrackRef.Geometry -> withGeometryKeyMoved(track.owner, parameter, fromValue, toValue)
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
 * @param Float position The key's parameter value.
 * @param Pose pose The current pose, which fixes the cell on every OTHER axis of a multi-keyed track.
 * @return PuppetModel The model with the key removed, or this on a refusal.
 */
fun PuppetModel.withTrackKeyRemoved(
	track: KeyformTrackRef,
	parameter: Parameter,
	position: Float,
	pose: Pose,
): PuppetModel =
	when (track) {
		is KeyformTrackRef.Channel -> withChannelKeyRemoved(track.target, parameter, pose + (parameter.id to position))
		is KeyformTrackRef.Geometry -> withGeometryKeyRemoved(track.owner, parameter, position)
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
 * @param Float fromValue The key's current position.
 * @param Float toValue The new position.
 */
fun EditorSession.moveTrackKey(track: KeyformTrackRef, parameter: Parameter, fromValue: Float, toValue: Float) {
	mutate(KeyformChange.MoveKey(channelOf(track))) { model ->
		model.withTrackKeyMoved(track, parameter, fromValue, toValue)
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
 * Removes every key in [keys] as ONE undo step - the sheet's Delete over a multi-key selection.
 *
 * One step rather than one per key because the user made one gesture; a per-key step would make undoing a
 * five-key delete take five presses.  Applied in DESCENDING position order so each removal cannot disturb
 * the positions the later ones still have to find.
 *
 * @param List keys The (track, parameter, key position) triples to remove.
 */
fun EditorSession.removeTrackKeys(keys: List<Triple<KeyformTrackRef, Parameter, Float>>) {
	if (keys.isEmpty()) {
		return
	}
	mutate(KeyformChange.RemoveKey(channelOf(keys.first().first))) { model ->
		keys
			.sortedByDescending { (_, _, position) -> position }
			.fold(model) { current, (track, parameter, position) ->
				current.withTrackKeyRemoved(track, parameter, position, pose.value)
			}
	}
}
