package org.umamo.edit

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.KeyableTarget
import org.umamo.runtime.model.KeyformOwner
import org.umamo.runtime.model.KeyformTrackRef
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.WarpLatticeForm

/*
 * The rules a session's pending edits follow - the unkeyed channel values and Pose-mode lattices that stand in for
 * what a key would capture.  Each rule is written once here, so the viewport, the keyform sheet, the Properties
 * panel, and the capture read them alike.
 */

/**
 * What this mode shows of a session's pending edits: none while it pins the pose at rest, since each was made for
 * the rig's pose, which the mode does not show; all of them otherwise.
 *
 * @param Map<Key, Value> pending The pending edits.
 * @return Map<Key, Value> The edits shown.
 */
fun <Key, Value> EditorMode.shownPending(pending: Map<Key, Value>): Map<Key, Value> = if (pinsPose) emptyMap() else pending

/** The session's pending Pose-mode lattices as its mode shows them ([shownPending]), live. */
val EditorSession.shownPendingLatticeEdits: Flow<Map<DeformerId, WarpLatticeForm>>
	get() = combine(mode, pendingLatticeEdits) { editorMode, pending -> editorMode.shownPending(pending) }

/** The session's pending channel edits as its mode shows them ([shownPending]), live. */
val EditorSession.shownPendingChannelEdits: Flow<Map<KeyableTarget, ChannelValue>>
	get() = combine(mode, pendingChannelEdits) { editorMode, pending -> editorMode.shownPending(pending) }

/**
 * The tracks holding an edit a key at the pose would store: each typed channel value's track and each Pose-mode
 * lattice's geometry track.  The one rule for "this track holds an edit" - a key aimed anywhere on such a track keys
 * the edit at the pose, and the keyform sheet marks it there, so the mark and the key always agree.
 *
 * @param Map<KeyableTarget, ChannelValue> channels The pending channel values.
 * @param Map<DeformerId, WarpLatticeForm> lattices The pending Pose-mode lattices.
 * @return Set<KeyformTrackRef> The tracks holding them.
 */
fun heldTracks(channels: Map<KeyableTarget, ChannelValue>, lattices: Map<DeformerId, WarpLatticeForm>): Set<KeyformTrackRef> =
	channels.keys.mapTo(LinkedHashSet()) { target -> KeyformTrackRef.Channel(target) } +
		lattices.keys.map { deformerId -> KeyformTrackRef.Geometry(KeyformOwner.Deformer(deformerId)) }

/** The tracks holding an edit as the session's mode shows them ([heldTracks] over [shownPending]), live. */
val EditorSession.shownHeldTracks: Flow<Set<KeyformTrackRef>>
	get() = combine(shownPendingChannelEdits, shownPendingLatticeEdits) { channels, lattices -> heldTracks(channels, lattices) }

/**
 * The entries of [pending] a step from [before] to [after] at an unmoved pose keeps: each whose warp [after] still
 * holds with the same lattice forms and the same parent.  A pending lattice is a parent-space shape taken against the
 * warp's forms at the pose, so a write that replaces those forms (an Object move, an Edit reshape, a key) or
 * re-parents the warp leaves it describing something that is no longer there.
 *
 * @param Map<DeformerId, WarpLatticeForm> pending The pending lattices before the step.
 * @param PuppetModel before The model before the step.
 * @param PuppetModel after The model after the step.
 * @return Map<DeformerId, WarpLatticeForm> The entries the step keeps.
 */
internal fun pendingLatticesCarried(pending: Map<DeformerId, WarpLatticeForm>, before: PuppetModel, after: PuppetModel): Map<DeformerId, WarpLatticeForm> {
	if (pending.isEmpty() || after === before) {
		return pending
	}

	return pending.filterKeys { deformerId ->
		val previous = before.deformerById[deformerId] as? Deformer.Warp
		val next = after.deformerById[deformerId] as? Deformer.Warp
		previous != null && next != null && next.geometryGrid === previous.geometryGrid && next.parent == previous.parent
	}
}