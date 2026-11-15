package org.umamo.ui.workspace.spaces.keyformsheet

import org.umamo.edit.EditorSession
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.edit.TrackKeyRef
import org.umamo.edit.keyform.removeTrackKeys
import org.umamo.edit.keyform.removingKeys
import org.umamo.runtime.model.KeyformOwner
import org.umamo.runtime.model.Parameter
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.Keymap
import org.umamo.ui.action.commandMenuItem
import org.umamo.ui.kit.menu.MenuItem
import org.umamo.ui.tracks.TrackLaneHit
import org.umamo.ui.tracks.TrackRow
import org.umamo.ui.workspace.KeyableHover
import org.umamo.ui.workspace.KeyformHover

/*
 * The sheet's two context menus, as lists of entries: a lane's, about a key at a position, and a row
 * label's, about the thing the row names.  Plain functions, called from lambdas the section writes in its
 * own composition; the track widgets take their menu builders as plain lambdas, and every label arrives
 * already resolved in KeyformSheetLabels.
 */

/**
 * The entries of a row label's menu: Select, for a group row whose owner can be selected, and nothing for
 * any other row.
 *
 * @param TrackRow row The row whose label was right-clicked.
 * @param KeyformSheetProjection projection The section's projection, which maps the row back to its owner.
 * @param EditorSession? session The editing session, or null with none, which offers nothing.
 * @param KeyformSheetLabels labels The sheet's chrome.
 * @return List<MenuItem> The entries.
 */
internal fun labelMenuItemsFor(
	row: TrackRow,
	projection: KeyformSheetProjection,
	session: EditorSession?,
	labels: KeyformSheetLabels,
): List<MenuItem> {
	val target = projection.ownerByRowKey[row.key]?.let(::selectionTargetOf)
	val ownerKind = projection.ownerKindByRowKey[row.key]

	return if (session == null || target == null || ownerKind == null) {
		emptyList()
	} else {
		listOf(
			MenuItem.Action(
				label = labels.selectOwner.getValue(ownerKind),
				onSelect = { session.setSelection(Selection(setOf(target), target)) },
			),
		)
	}
}

/**
 * The entries of a lane's menu: Delete on a mark (every key a summary mark stands for), Insert on empty
 * track, and nothing on a row with no track ref.
 *
 * Each single-key item is its command's own row, built from the command, so the menu can never drift from the
 * shortcut it advertises or from when the command applies.  The popup owns the pointer by the time an item fires,
 * so the clicked spot is re-published as the hover for the command's dispatch-time read and cleared right after.
 *
 * @param TrackLaneHit hit Where on the lane the menu was opened.
 * @param Parameter parameter The section's parameter.
 * @param KeyformSheetProjection projection The section's projection, which maps the row back to its track.
 * @param EditorSession? session The editing session, or null with none, which offers nothing.
 * @param Boolean keysEditable Whether the mode lets keys change; false grays out the summary's entry, which is not a
 *   command's row and so has no command to ask.
 * @param KeyableHover? keyableHover The shell's keyable hover, which the command reads, or null outside a shell.
 * @param CommandRegistry commands The registry the entries dispatch through.
 * @param Keymap keymap The keymap, for the shortcut each entry advertises.
 * @param KeyformSheetLabels labels The sheet's chrome.
 * @return List<MenuItem> The entries.
 */
internal fun laneMenuItemsFor(
	hit: TrackLaneHit,
	parameter: Parameter,
	projection: KeyformSheetProjection,
	session: EditorSession?,
	keysEditable: Boolean,
	keyableHover: KeyableHover?,
	commands: CommandRegistry,
	keymap: Keymap,
	labels: KeyformSheetLabels,
): List<MenuItem> {
	val track = projection.tracksByRowKey[hit.row.key]
	val hitMark = hit.mark
	val summaryMembers = hitMark?.let { mark -> projection.summaryMembers(hit.row.key, mark.keyIndex) }

	return when {
		// A summary mark stands for several keys, so its menu removes them all in one step.  There is no
		// Insert counterpart: a folded group cannot say which of its tracks a new key belongs on, and
		// picking one would be a guess.
		session != null && summaryMembers != null -> {
			listOf(
				MenuItem.Action(
					label = labels.deleteKey,
					onSelect = {
						// Reconciled rather than blanket-cleared, so deleting these keys never deselects the
						// user's unrelated marks.
						val removed =
							summaryMembers
								.map { member -> TrackKeyRef(parameter.id, member.rowKey, member.keyIndex) }
								.toSet()
						session.removingKeys(removed) {
							session.removeTrackKeys(summaryMembers.mapNotNull { member -> projection.trackKeyOf(parameter, member) })
						}
					},
					enabled = keysEditable,
				),
			)
		}

		session == null || track == null -> {
			emptyList()
		}

		hitMark != null -> {
			// The row key rides the hover, so the removal reconciles the key selection itself - see
			// EditorSession.removeKeyOnTrack.  Without it, deleting a selected mark would leave its ref on the
			// ordinal the removal freed, which is the neighbor: the mark to the right would light up as selected
			// and the next Delete would take it.
			val hover = KeyformHover(track, hit.value, hitMark.keyIndex, parameter.id, hit.row.key)

			listOfNotNull(keyRowOver(hover, "keyform.delete", labels.deleteKey, keyableHover, commands, keymap))
		}

		else -> {
			val hover = KeyformHover(track, hit.value, keyIndex = null, parameterId = parameter.id, rowKey = hit.row.key)

			listOfNotNull(keyRowOver(hover, "keyform.insert", labels.insertKey, keyableHover, commands, keymap))
		}
	}
}

/**
 * The row of one key command aimed at [hover]: the command's own row - its chord, and grayed whenever it does not
 * apply - with the spot the menu was opened over published as the hover around the dispatch, and cleared right
 * after.
 *
 * @param KeyformHover hover The spot the menu was opened over, as the command reads it.
 * @param String commandId The key command.
 * @param String label The row's label.
 * @param KeyableHover? keyableHover The shell's keyable hover, which the command reads, or null outside a shell.
 * @param CommandRegistry commands The registry the row dispatches through.
 * @param Keymap keymap The keymap, for the shortcut the row advertises.
 * @return MenuItem? The row, or null while the command is not registered.
 */
private fun keyRowOver(
	hover: KeyformHover,
	commandId: String,
	label: String,
	keyableHover: KeyableHover?,
	commands: CommandRegistry,
	keymap: Keymap,
): MenuItem? =
	commands[commandId]?.let { command ->
		commandMenuItem(command, label, commands, keymap) { dispatch ->
			keyableHover?.enter(hover)
			dispatch()
			keyableHover?.exit(hover.track)
		}
	}

/**
 * The selection target a keyform owner names, or null when it names nothing selectable.
 *
 * Glue is the null case, and deliberately so: it has no SelectionTarget, no outliner entry, and is
 * addressed by a mesh pair rather than an id, so there is nothing for a "select this" to put in the
 * selection.
 *
 * @param KeyformOwner owner The row's owner.
 * @return SelectionTarget? What to select, or null.
 */
private fun selectionTargetOf(owner: KeyformOwner): SelectionTarget? =
	when (owner) {
		is KeyformOwner.Drawable -> SelectionTarget.Drawable(owner.id)
		is KeyformOwner.Part -> SelectionTarget.Part(owner.id)
		is KeyformOwner.Deformer -> SelectionTarget.Deformer(owner.id)
		is KeyformOwner.Glue -> null
	}