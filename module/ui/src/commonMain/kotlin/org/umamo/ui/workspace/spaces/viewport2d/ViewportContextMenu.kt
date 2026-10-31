package org.umamo.ui.workspace.spaces.viewport2d

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.PieMenuKind
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.Keymap
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.action.LocalKeymap
import org.umamo.ui.action.formatAccelerator
import org.umamo.ui.kit.menu.MenuItem
import org.umamo.ui.workspace.commands.pieMenuEntriesFor

/*
 * The 2D viewport's context menu: what an idle right-click over the surface offers in each mode, as rows of existing
 * commands.  A context menu is contextual, so it holds the mode's operations and nothing of the area's (those stay on
 * the header, as in the UV editor).  Every row dispatches its command through the registry, reads its label from the
 * command's title and its accelerator from the keymap, and greys out when its command is unavailable - the row is
 * built from the command id alone, so it can never run one command and advertise another's chord.  A pie's choices
 * come in as a submenu under the title of the command that opens the pie, built from the same entry table the pie
 * draws, so a greyed chip is a greyed row.  Operations driven by the pointer's position (Rip, Vertex Slide, the
 * object switch) stay off it: from a menu, the pointer is on the menu.
 */

/** One row of the viewport's context menu. */
private sealed interface ViewportMenuRow {
	/**
	 * A command, by id.
	 *
	 * @property String commandId The command.
	 */
	data class Single(val commandId: String) : ViewportMenuRow

	/**
	 * A pie's choices as a submenu, under the title of the command that opens it.
	 *
	 * @property String openerId The command that opens the pie.
	 * @property PieMenuKind kind The pie.
	 */
	data class Choices(val openerId: String, val kind: PieMenuKind) : ViewportMenuRow

	/** A rule between groups. */
	data object Separator : ViewportMenuRow
}

/** Object mode's rows: whole-object operations, the snaps, and the art meshes. */
private val OBJECT_ROWS =
	listOf(
		ViewportMenuRow.Single("mesh.duplicate"),
		ViewportMenuRow.Single("object.toggleVisibility"),
		ViewportMenuRow.Separator,
		ViewportMenuRow.Choices("snap.pie", PieMenuKind.Snap),
		ViewportMenuRow.Separator,
		ViewportMenuRow.Single("mesh.generateFromArt"),
		ViewportMenuRow.Single("mesh.generateQuad"),
		ViewportMenuRow.Single("view.frameSelected"),
	)

/** Edit mode's rows: the topology operations, the element selection, the snaps, and the art meshes. */
private val EDIT_ROWS =
	listOf(
		ViewportMenuRow.Choices("mesh.merge", PieMenuKind.MergeTarget),
		ViewportMenuRow.Single("mesh.connect"),
		ViewportMenuRow.Single("mesh.duplicate"),
		ViewportMenuRow.Separator,
		ViewportMenuRow.Single("select.all"),
		ViewportMenuRow.Single("select.invert"),
		ViewportMenuRow.Single("mesh.selectLinked"),
		ViewportMenuRow.Separator,
		ViewportMenuRow.Choices("snap.pie", PieMenuKind.Snap),
		ViewportMenuRow.Separator,
		ViewportMenuRow.Single("mesh.generateFromArt"),
		ViewportMenuRow.Single("mesh.generateQuad"),
	)

/** Pose mode's rows: keying the posed warps, clearing what is held, the element selection, and the snaps. */
private val POSE_ROWS =
	listOf(
		ViewportMenuRow.Single("keyform.insert"),
		ViewportMenuRow.Single("keyform.delete"),
		ViewportMenuRow.Choices("keyform.insertPie", PieMenuKind.InsertKey),
		ViewportMenuRow.Single("keyform.clearPoseEdits"),
		ViewportMenuRow.Separator,
		ViewportMenuRow.Single("select.all"),
		ViewportMenuRow.Single("select.invert"),
		ViewportMenuRow.Separator,
		ViewportMenuRow.Choices("snap.pie", PieMenuKind.Snap),
	)

/**
 * The rows [mode] offers.
 *
 * @param EditorMode mode The mode.
 * @return List<ViewportMenuRow> The rows, top to bottom.
 */
private fun rowsFor(mode: EditorMode): List<ViewportMenuRow> =
	when (mode) {
		EditorMode.Object -> OBJECT_ROWS
		EditorMode.Edit -> EDIT_ROWS
		EditorMode.Pose -> POSE_ROWS
	}

/**
 * The viewport's context menu items for the session's mode, rebuilt whenever the mode or anything its commands'
 * availability reads changes (the model, both selections, and the held edits), so a row's greyed state is current
 * when the menu opens.  Empty with no session.
 *
 * @param EditorSession? session The open document's session, or null.
 * @return List<MenuItem> The items.
 */
@Composable
internal fun rememberViewportContextItems(session: EditorSession?): List<MenuItem> {
	val commands = LocalCommands.current
	val keymap = LocalKeymap.current

	if (session == null) {
		return emptyList()
	}

	val mode = session.mode.collectAsState().value
	// Observed for their changes alone: each recomposes the rows, whose availability reads the session directly.
	val observed =
		listOf(
			session.model.collectAsState().value,
			session.selection.collectAsState().value,
			session.meshSelection.collectAsState().value,
			session.pendingChannelEdits.collectAsState().value,
			session.pendingLatticeEdits.collectAsState().value,
		)

	return key(mode, observed) {
		rowsFor(mode).mapNotNull { row -> menuItemOf(row, commands, keymap) }
	}
}

/**
 * The menu item standing for [row], or null when its command is not registered.
 *
 * @param ViewportMenuRow row The row.
 * @param CommandRegistry commands The registry the rows dispatch through.
 * @param Keymap keymap The keymap the accelerators are read from.
 * @return MenuItem? The item.
 */
@Composable
private fun menuItemOf(
	row: ViewportMenuRow,
	commands: CommandRegistry,
	keymap: Keymap,
): MenuItem? =
	when (row) {
		is ViewportMenuRow.Single -> {
			commands[row.commandId]?.let { command ->
				MenuItem.Action(
					label = command.title?.let { title -> stringResource(title) } ?: command.id,
					onSelect = { commands.invoke(command.id) },
					shortcut = acceleratorOf(keymap, command.id),
					enabled = command.availability.isAvailable(),
				)
			}
		}

		is ViewportMenuRow.Choices -> {
			commands[row.openerId]?.let { opener ->
				val entries = pieMenuEntriesFor(row.kind, commands)

				MenuItem.Submenu(
					label = opener.title?.let { title -> stringResource(title) } ?: opener.id,
					items =
						entries.map { entry ->
							MenuItem.Action(
								label = stringResource(entry.label),
								onSelect = { commands.invoke(entry.commandId, entry.argument) },
								shortcut = acceleratorOf(keymap, entry.commandId),
								enabled = entry.enabled,
								icon = entry.icon,
							)
						},
					enabled = entries.any { entry -> entry.enabled },
				)
			}
		}

		ViewportMenuRow.Separator -> {
			MenuItem.Separator
		}
	}

/**
 * The accelerator text of the chord [keymap] binds to [commandId], or null when it binds none.
 *
 * @param Keymap keymap The keymap.
 * @param String commandId The command.
 * @return String? The accelerator text.
 */
private fun acceleratorOf(
	keymap: Keymap,
	commandId: String,
): String? = keymap.chordFor(commandId)?.let { chord -> formatAccelerator(chord) }