package org.umamo.ui.workspace

import org.umamo.edit.EditorSession
import org.umamo.edit.NoticePlacement
import org.umamo.edit.captureChannelKey
import org.umamo.edit.channelValueAt
import org.umamo.edit.insertTrackKeyAt
import org.umamo.edit.nudgeTrackKeys
import org.umamo.edit.removeTrackKeys
import org.umamo.edit.trackKeyIndexAtPose
import org.umamo.runtime.model.KeyformTrackRef
import org.umamo.runtime.model.Parameter
import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandAvailability
import org.umamo.ui.model.KeyformHover
import org.umamo.ui.resources.*

/*
 * The keyform-authoring command table: insert / delete on the hovered keyable property, and the keyform
 * sheet's own selection and view operations.  Split out of ShellCommands.kt because this group is the one
 * that carries real authoring LOGIC (which parameter a hover writes on, what an insert captures, how a
 * removal renumbers) rather than a one-line dispatch into the session - keeping it here stops that logic
 * from being read as shell plumbing, and keeps the shell table to the commands it actually owns.
 *
 * Every target resolves at DISPATCH time (the hovered keyable, the hovered sheet), never latched at
 * registration - the same rule the viewport commands follow, and what lets `I` work with no prior
 * selection: point at Opacity and press it.
 */

/**
 * The keyform-authoring commands: insert / delete a key on the hovered keyable, and the keyform sheet's
 * delete-selected / nudge / frame-all.
 *
 * The sheet commands are registered ONCE here rather than per sheet area: CommandRegistry is
 * last-write-wins per id, so per-area registration made two open sheets clobber each other's handlers and
 * closing either area unregistered the shared ids out from under the survivor.  The acting sheet resolves
 * through [keyformSheets] at dispatch time - the hovered sheet, else the only open one, else (for the
 * selection ops) the unique one with keys selected.
 *
 * @param EditorSession? editorSession The open document's session, or null (every command then no-ops).
 * @param Function hoveredKeyable Resolves the hovered keyable property (a sheet lane or a Properties row)
 *   at dispatch time; null when the pointer is over nothing keyable.
 * @param Function hoveredSurface Resolves the last-touched editor surface at dispatch time, which names
 *   the acting sheet when it is a keyform sheet.
 * @param KeyformSheetViews keyformSheets The shell's open-sheet registry the sheet commands act through.
 * @return List<Command> The commands to register.
 */
internal fun shellKeyformCommands(
	editorSession: EditorSession?,
	hoveredKeyable: () -> KeyformHover?,
	hoveredSurface: () -> HoveredSurface?,
	keyformSheets: KeyformSheetViews,
): List<Command> {
	val hasDocument = CommandAvailability { editorSession != null }
	return listOf(
		Command("keyform.insert", title = Res.string.cmd_keyform_insert, availability = hasDocument) {
			editorSession?.let { session -> session.keyformInsert(hoveredKeyable()) }
		},
		Command("keyform.delete", title = Res.string.cmd_keyform_delete, availability = hasDocument) {
			editorSession?.let { session -> session.keyformRemove(hoveredKeyable()) }
		},
		Command("keyform.deleteSelectedKeys", title = Res.string.cmd_keyform_delete_keys, availability = hasDocument) {
			val sheet = keyformSheets.resolveForSelection(hoveredSheetArea(hoveredSurface))
			val removals = sheet?.selectedTracks().orEmpty()
			if (editorSession != null && sheet != null && removals.isNotEmpty()) {
				editorSession.removeTrackKeys(removals)
				// A removal renumbers every later key on its track, so no surviving ref can be trusted;
				// clearing is the honest outcome rather than pointing at a neighbour.
				sheet.clearSelection()
			}
		},
		Command("keyform.nudgeKeyLeft", title = Res.string.cmd_keyform_nudge_left, availability = hasDocument) {
			keyformSheets.resolveForSelection(hoveredSheetArea(hoveredSurface))?.let { sheet ->
				editorSession?.nudgeTrackKeys(sheet.selectedTracks(), -KEYFORM_NUDGE_FRACTION)
			}
		},
		Command("keyform.nudgeKeyRight", title = Res.string.cmd_keyform_nudge_right, availability = hasDocument) {
			keyformSheets.resolveForSelection(hoveredSheetArea(hoveredSurface))?.let { sheet ->
				editorSession?.nudgeTrackKeys(sheet.selectedTracks(), KEYFORM_NUDGE_FRACTION)
			}
		},
		Command("keyform.frameAll", title = Res.string.cmd_keyform_frame_all, availability = hasDocument) {
			keyformSheets.resolve(hoveredSheetArea(hoveredSurface))?.frameAll?.invoke()
		},
	)
}

/**
 * Captures a key on [hover]'s track at the current pose, keyed on the parameter that hover writes on.
 *
 * Captures the PENDING unkeyed edit when there is one, else the channel's current evaluated value - so an
 * insert after typing stores what was typed, and an insert with nothing edited pins what is already on
 * screen (Blender's behaviour, and how a rigger anchors a pose before moving on).
 *
 * Refuses with a notice rather than silently doing nothing, because both failure modes are things the user
 * can fix: nothing hovered, or no parameter targeted.
 *
 * @param KeyformHover? hover The hovered keyable property, or null when the pointer is over nothing.
 */
private fun EditorSession.keyformInsert(hover: KeyformHover?) {
	if (hover == null) {
		emitNotice("notice.keyform.noTarget", NoticePlacement.NearCursor)
		return
	}
	val parameter = keyformParameterOf(hover) ?: return
	val position = hover.position
	if (position != null) {
		// Aimed at a spot on a track, so the key lands THERE rather than at the playhead - pointing at a
		// place on a lane is a statement about which place, and keying the playhead instead would ignore
		// what was aimed at.  It holds whatever the track already evaluates to there, which is the same
		// shape-preserving insert the lane's own context menu performs.
		insertTrackKeyAt(hover.track, parameter, position)
		return
	}
	when (val track = hover.track) {
		is KeyformTrackRef.Channel -> {
			val target = track.target
			// No position to aim at (a Properties row), so this keys the pose.  A pending unkeyed edit
			// wins: the whole point of `I` after typing a value is to capture what was just typed, not what
			// is still stored.  With nothing pending, the channel's current evaluated value is captured
			// instead, pinning the pose already on screen (Blender's behaviour).
			val value =
				pendingChannelEdits.value[target] ?: model.value.channelValueAt(target, pose.value) ?: return
			captureChannelKey(target, parameter, value)
			// Only THIS target's pending edit was consumed; the pose did not move, so any other target's
			// typed value is still the value its user chose and must survive for its own insert.
			clearPendingChannelEdit(target)
		}

		// Geometry holds no value the user can have typed, so there is nothing to capture.
		is KeyformTrackRef.Geometry ->
			insertTrackKeyAt(track, parameter, pose.value[parameter.id] ?: parameter.default)
	}
}

/**
 * Removes the key at the current pose (or the pointed-at mark) from [hover]'s track.
 *
 * @param KeyformHover? hover The hovered keyable property, or null.
 */
private fun EditorSession.keyformRemove(hover: KeyformHover?) {
	if (hover == null) {
		emitNotice("notice.keyform.noTarget", NoticePlacement.NearCursor)
		return
	}
	val parameter = keyformParameterOf(hover) ?: return
	// Whichever mark is under the pointer when there is one to point at, and otherwise the key the pose is
	// standing on.  Either way it is an ORDINAL, and either way pointing at nothing removes nothing:
	// picking "the nearest key" would be a guess at which the user meant, and a wrong guess silently
	// destroys authored work.
	val keyIndex =
		if (hover.position != null) {
			hover.keyIndex ?: -1
		} else {
			model.value.trackKeyIndexAtPose(hover.track, parameter, pose.value)
		}
	if (keyIndex >= 0) {
		removeTrackKeys(listOf(Triple(hover.track, parameter, keyIndex)))
	}
}

/**
 * How far one arrow-key press moves a sheet key, as a fraction of its parameter's range.
 *
 * Relative rather than absolute because ranges differ by orders of magnitude across a rig - an angle
 * spans 60, an open/close spans 1 - so one absolute step would be invisible on the first and wild on the
 * second.  A hundredth puts a full sweep at a hundred presses, which reads as fine adjustment.
 */
private const val KEYFORM_NUDGE_FRACTION = 0.01f

/**
 * The last-touched keyform-sheet area id, or null when the last-touched surface is not a sheet.
 *
 * @param Function hoveredSurface The shell's last-touched-surface resolver.
 * @return String? The sheet area id, or null.
 */
private fun hoveredSheetArea(hoveredSurface: () -> HoveredSurface?): String? =
	hoveredSurface()?.takeIf { surface -> surface.kind == SpaceKind.KeyformSheet }?.areaId

/**
 * The parameter a keyform edit on [hover] writes on: the hover's OWN parameter when it carries one (a
 * sheet lane belongs to one section's axis, which need not be the selection's active member), else the
 * panel's targeted parameter.
 *
 * Emits the no-parameter notice and returns null when the hover's parameter no longer resolves.
 *
 * @param KeyformHover hover The hovered keyable.
 * @return Parameter? The parameter to write on, or null.
 */
private fun EditorSession.keyformParameterOf(hover: KeyformHover): Parameter? {
	val hoveredId = hover.parameterId ?: return targetedParameter()
	val parameter = model.value.parameters.firstOrNull { candidate -> candidate.id == hoveredId }
	if (parameter == null) {
		emitNotice("notice.keyform.noParameter", NoticePlacement.NearCursor)
	}
	return parameter
}

/**
 * The parameter a keyform edit would write on - the active member of the session's parameter selection.
 *
 * Emits a notice and returns null when nothing is targeted, since an insert with no axis to write on has
 * no sensible default: guessing a parameter would key the rig somewhere the user never looked.
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
