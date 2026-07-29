package org.umamo.ui.workspace

import org.umamo.edit.EditorSession
import org.umamo.edit.NoticePlacement
import org.umamo.edit.captureKeyOnTrack
import org.umamo.edit.removeKeyOnTrack
import org.umamo.edit.removeTrackKeys
import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandAvailability
import org.umamo.ui.model.KeyformHover
import org.umamo.ui.resources.*

/*
 * The keyform-authoring command table: insert / delete on the hovered keyable property, and the keyform
 * sheet's own selection and view operations.
 *
 * What is here is POINTER bookkeeping, not authoring rules.  A command resolves what the pointer is over,
 * refuses when that is nothing, and hands the plain description of it - track, parameter, aim - to the
 * matching op in :edit (KeyformAimEdits.kt), which owns every decision about what the edit means.  Deciding
 * here instead would put authoring rules where only a running composition could exercise them, and would
 * let the sheet, a panel row, and a lane menu drift into meaning different things by the same gesture.
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
			editorSession?.let { session ->
				aimedKeyable(session, hoveredKeyable())?.let { hover ->
					session.captureKeyOnTrack(hover.track, hover.parameterId, hover.aim)
				}
			}
		},
		Command("keyform.delete", title = Res.string.cmd_keyform_delete, availability = hasDocument) {
			editorSession?.let { session ->
				aimedKeyable(session, hoveredKeyable())?.let { hover ->
					session.removeKeyOnTrack(hover.track, hover.parameterId, hover.aim)
				}
			}
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
			keyformSheets.resolveForSelection(hoveredSheetArea(hoveredSurface))?.nudgeSelection(-KEYFORM_NUDGE_FRACTION)
		},
		Command("keyform.nudgeKeyRight", title = Res.string.cmd_keyform_nudge_right, availability = hasDocument) {
			keyformSheets.resolveForSelection(hoveredSheetArea(hoveredSurface))?.nudgeSelection(KEYFORM_NUDGE_FRACTION)
		},
		Command("keyform.frameAll", title = Res.string.cmd_keyform_frame_all, availability = hasDocument) {
			keyformSheets.resolve(hoveredSheetArea(hoveredSurface))?.frameAll?.invoke()
		},
	)
}

/**
 * The keyable an authoring command acts on, or null after refusing with a notice.
 *
 * Refusing here rather than in :edit because "the pointer is over nothing" is a fact about the pointer, not
 * about the rig - the authoring ops take a track and so can never be in this state.
 *
 * @param EditorSession session The session that shows the notice.
 * @param KeyformHover? hover What the pointer is over, or null.
 * @return KeyformHover? The hover to act on, or null when there is none.
 */
private fun aimedKeyable(session: EditorSession, hover: KeyformHover?): KeyformHover? {
	if (hover == null) {
		session.emitNotice("notice.keyform.noTarget", NoticePlacement.NearCursor)
	}
	return hover
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
