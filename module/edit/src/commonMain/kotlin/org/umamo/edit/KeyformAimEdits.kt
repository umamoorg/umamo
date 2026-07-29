package org.umamo.edit

import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.KeyableTarget
import org.umamo.runtime.model.KeyformTrackRef
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.channelGridsOf

/*
 * What "key this" and "unkey this" MEAN when the user asks for them - the intent layer over the concrete
 * ops in KeyformTrackEdits / KeyformChannelEdits / KeyformGeometryEdits.
 *
 * Three questions live here: which parameter an edit writes on, whether it acts where the user pointed or
 * where the pose stands, and whether a typed value becomes a pending edit or a static write.  All three are
 * editing SEMANTICS, so they belong in this module - a panel row, a keypress, and a lane context menu must
 * answer them identically, and none of them is the right place to decide.
 *
 * This is also why these are not in :ui, where they started out: taking a Compose-side hover type put
 * authoring rules in the UI module and made them untestable without a composition.  A :ui caller now passes
 * plain data - a track, an optional parameter id, and a KeyformAim - and keeps only the pointer bookkeeping
 * that produced them.
 */

/**
 * Where a keyform edit is aimed: at a place the user named, or at the pose the rig is standing in.
 *
 * The distinction is not cosmetic.  Pointing at a spot on a track is a statement about WHICH spot, so a key
 * lands there rather than at the playhead; and pointing at a track but not at a key means "that key, of
 * which there is none", which must remove nothing rather than falling back to the pose and destroying a key
 * the user never pointed at.
 */
sealed interface KeyformAim {
	/** No place was named, so the edit acts at the current pose - a property row, or a bare keypress. */
	data object Pose : KeyformAim

	/**
	 * A named place on the track's axis.
	 *
	 * @property Float position The parameter value pointed at.
	 * @property Int? keyIndex The ordinal of the key sitting there, or null when the pointer is between keys.
	 */
	data class Position(val position: Float, val keyIndex: Int?) : KeyformAim
}

/**
 * Captures a key on [track] at [aim], keyed on the parameter the edit writes on.
 *
 * An aimed capture inserts a shape-preserving key at that position, so it holds whatever the track already
 * evaluates to there.  A capture at the pose stores the PENDING unkeyed edit when there is one, else the
 * channel's current evaluated value - so an insert after typing stores what was typed, and an insert with
 * nothing edited pins what is already on screen (Blender's behaviour, and how a rigger anchors a pose before
 * moving on).
 *
 * Refuses with a notice rather than silently doing nothing when no parameter resolves, since that is a thing
 * the user can fix.
 *
 * @param KeyformTrackRef track The track to key.
 * @param ParameterId? parameterId The axis to key on, or null to use the session's targeted parameter.
 * @param KeyformAim aim Where the key lands.
 */
fun EditorSession.captureKeyOnTrack(track: KeyformTrackRef, parameterId: ParameterId?, aim: KeyformAim) {
	val parameter = keyformParameterFor(parameterId) ?: return
	if (aim is KeyformAim.Position) {
		insertTrackKeyAt(track, parameter, aim.position)
		return
	}
	when (track) {
		is KeyformTrackRef.Channel -> {
			val target = track.target
			val value = pendingChannelEdits.value[target] ?: model.value.channelValueAt(target, pose.value) ?: return
			captureChannelKey(target, parameter, value)
			// Only THIS target's pending edit was consumed; the pose did not move, so any other target's
			// typed value is still the value its user chose and must survive for its own capture.
			clearPendingChannelEdit(target)
		}

		// Geometry holds no value the user can have typed, so there is nothing to capture - the key goes in
		// at the pose and holds the shape already there.
		is KeyformTrackRef.Geometry ->
			insertTrackKeyAt(track, parameter, pose.value[parameter.id] ?: parameter.default)
	}
}

/**
 * Removes the key [aim] names from [track], on the parameter the edit writes on.
 *
 * Either way the key is an ORDINAL, and either way aiming at nothing removes nothing: picking "the nearest
 * key" would be a guess at which one the user meant, and a wrong guess silently destroys authored work.
 *
 * @param KeyformTrackRef track The track to unkey.
 * @param ParameterId? parameterId The axis to remove from, or null to use the session's targeted parameter.
 * @param KeyformAim aim The key pointed at, or [KeyformAim.Pose] to take the key the pose stands on.
 */
fun EditorSession.removeKeyOnTrack(track: KeyformTrackRef, parameterId: ParameterId?, aim: KeyformAim) {
	val parameter = keyformParameterFor(parameterId) ?: return
	val keyIndex =
		when (aim) {
			is KeyformAim.Position -> aim.keyIndex ?: -1
			is KeyformAim.Pose -> model.value.trackKeyIndexAtPose(track, parameter, pose.value)
		}
	if (keyIndex >= 0) {
		removeTrackKeys(listOf(Triple(track, parameter, keyIndex)))
	}
}

/**
 * Routes a keyable property's typed or picked [value]: a KEYED channel records it as a pending unkeyed
 * edit, an unkeyed channel writes the static through [writeStatic].
 *
 * The one place the rule lives.  Writing the static of a keyed channel is shadowed by the track, so the
 * edit appears to be silently rejected (the field snaps back and a following `I` captures the old track
 * value); a pending edit previews in the viewport and waits for `I`.  An unkeyed channel has no track to
 * shadow it, so the static is the real store.  Half the rows hand-copied this branch and the other half
 * shipped without it - which is exactly the drift a shared helper exists to prevent.
 *
 * @param KeyableTarget target The entity and channel being edited.
 * @param ChannelValue value The value the user chose.
 * @param Function writeStatic Writes the owner's static (the unkeyed path); the caller supplies it because
 *   which setter that is depends on the owner and channel.
 */
fun EditorSession.editKeyedChannel(target: KeyableTarget, value: ChannelValue, writeStatic: () -> Unit) {
	val keyed = model.value.channelGridsOf(target.owner)?.get(target.channel) != null
	if (keyed) {
		setPendingChannelEdit(target, value)
	} else {
		// A scrub previews through the pending buffer even on an unkeyed channel (see previewChannelEdit),
		// so the static write has to retire that preview - otherwise the stale pending value keeps
		// shadowing the very static just committed, and the field stays tinted as an uncommitted edit.
		clearPendingChannelEdit(target)
		writeStatic()
	}
}

/**
 * Previews [value] on [target] without committing anything - what a field reports while being scrubbed.
 *
 * Always the pending buffer, whether or not the channel is keyed.  For a keyed channel that is already
 * where a committed edit lands, so preview and commit coincide; for an unkeyed one it is the difference
 * between a live viewport and a history entry per pointer frame.  [editKeyedChannel] retires the preview
 * when the gesture ends.
 *
 * @param KeyableTarget target The entity and channel being scrubbed.
 * @param ChannelValue value The in-flight value.
 */
fun EditorSession.previewChannelEdit(target: KeyableTarget, value: ChannelValue) {
	setPendingChannelEdit(target, value)
}

/**
 * The parameter a keyform edit writes on: [parameterId] when the caller named one (a sheet lane belongs to
 * one section's axis, which need not be the selection's active member), else the targeted parameter.
 *
 * Emits the no-parameter notice and returns null when the named parameter no longer resolves.
 *
 * @param ParameterId? parameterId The axis the caller named, or null to fall back to the target.
 * @return Parameter? The parameter to write on, or null.
 */
private fun EditorSession.keyformParameterFor(parameterId: ParameterId?): Parameter? {
	val namedId = parameterId ?: return targetedParameter()
	val parameter = model.value.parameters.firstOrNull { candidate -> candidate.id == namedId }
	if (parameter == null) {
		emitNotice("notice.keyform.noParameter", NoticePlacement.NearCursor)
	}
	return parameter
}

/**
 * The parameter a keyform edit falls back to - the active member of the session's parameter selection.
 *
 * Emits a notice and returns null when nothing is targeted, since an edit with no axis to write on has no
 * sensible default: guessing a parameter would key the rig somewhere the user never looked.
 *
 * @return Parameter? The targeted parameter, or null.
 */
private fun EditorSession.targetedParameter(): Parameter? {
	val activeId = parameterSelection.value.active
	if (activeId == null) {
		emitNotice("notice.keyform.noParameter", NoticePlacement.NearCursor)
		return null
	}
	val parameter = model.value.parameters.firstOrNull { candidate -> candidate.id == activeId }
	if (parameter == null) {
		// A dangling id (a stale snapshot's target for a parameter since deleted) is as unfixable-by-waiting
		// as no target at all, so it gets the same notice rather than a silent no-op.
		emitNotice("notice.keyform.noParameter", NoticePlacement.NearCursor)
	}
	return parameter
}
