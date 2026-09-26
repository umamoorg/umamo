package org.umamo.ui.menu

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.action.Keymap
import org.umamo.ui.action.formatAccelerator
import org.umamo.ui.document.fileDisplayName
import org.umamo.ui.kit.MenuItem
import org.umamo.ui.kit.TopLevelMenu
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.cmd_workspace_next
import org.umamo.ui.resources.cmd_workspace_prev
import org.umamo.ui.resources.menu_about
import org.umamo.ui.resources.menu_credits
import org.umamo.ui.resources.menu_documentation
import org.umamo.ui.resources.menu_edit
import org.umamo.ui.resources.menu_exit
import org.umamo.ui.resources.menu_export
import org.umamo.ui.resources.menu_export_cmo3
import org.umamo.ui.resources.menu_export_image
import org.umamo.ui.resources.menu_export_moc3
import org.umamo.ui.resources.menu_file
import org.umamo.ui.resources.menu_file_new
import org.umamo.ui.resources.menu_file_open
import org.umamo.ui.resources.menu_file_save
import org.umamo.ui.resources.menu_file_save_as
import org.umamo.ui.resources.menu_help
import org.umamo.ui.resources.menu_import
import org.umamo.ui.resources.menu_import_artwork
import org.umamo.ui.resources.menu_import_cmo3
import org.umamo.ui.resources.menu_import_moc3
import org.umamo.ui.resources.menu_open_log_folder
import org.umamo.ui.resources.menu_open_recent
import org.umamo.ui.resources.menu_preferences
import org.umamo.ui.resources.menu_quick_setup
import org.umamo.ui.resources.menu_redo
import org.umamo.ui.resources.menu_source_code
import org.umamo.ui.resources.menu_undo
import org.umamo.ui.resources.menu_web_site
import org.umamo.ui.resources.menu_workspace
import org.umamo.ui.resources.menu_workspace_export_all
import org.umamo.ui.resources.menu_workspace_export_this
import org.umamo.ui.resources.menu_workspace_import
import org.umamo.ui.resources.menu_workspace_reset
import org.umamo.ui.resources.workspace_new

/**
 * Runs a command by id through the action registry, with the argument an argument-only command takes (a
 * recent file's path) or null.  The one thing a menu row does.
 */
typealias MenuDispatch = (commandId: String, argument: Any?) -> Unit

/**
 * One menu row that stands for a command.  The row is built from the command's id ALONE: selecting it
 * dispatches that id, and its accelerator hint is whatever chord the keymap binds to that same id.  Naming
 * the id once is the point - a row that dispatched one id and looked its hint up under another would show
 * no chord, or the wrong one, and nothing would say so.
 *
 * @param String       label     The localized row label.
 * @param String       commandId The command the row stands for.
 * @param Keymap       keymap    The keymap the accelerator hint is resolved against.
 * @param MenuDispatch dispatch  Runs a command by id.
 * @param Boolean      enabled   Whether the row can be selected.
 * @return MenuItem.Action The row.
 */
private fun commandRow(
	label: String,
	commandId: String,
	keymap: Keymap,
	dispatch: MenuDispatch,
	enabled: Boolean = true,
): MenuItem.Action =
	MenuItem.Action(
		label = label,
		onSelect = { dispatch(commandId, null) },
		shortcut = keymap.chordFor(commandId)?.let { chord -> formatAccelerator(chord) },
		enabled = enabled,
	)

/**
 * Builds the File menu shared by every platform's menu bar.  Open, Save, and Save As mean the native
 * `.uma` document; artwork, CMO3, and MOC3 come in through the Import submenu (artwork first: it is the
 * headline workflow's entry), and CMO3 / MOC3 are interop boundaries that leave through Export.  Every
 * row dispatches its file.* command, so the menu, the keyboard, and the palette share one path.  Both
 * Save rows are gated on [canSave] (a puppet document that did not open read-only), the CMO3 and MOC3
 * Export rows on [canExport] (a puppet document is open; the CMO3 export reconciles onto a CMO3-origin
 * document's retained graph and synthesizes a fresh one otherwise), and Export Image on [canExportImage]
 * (a puppet document is open on a platform with a puppet renderer to draw it); Open Recent labels each stored path via
 * fileDisplayName, disables itself when the list is empty, and hands the path to file.openPath, which
 * opens or imports by what the file is.
 *
 * @param Keymap       keymap      The keymap the accelerator hints are resolved against.
 * @param List         recentFiles The recent file paths for the Open Recent submenu, most-recent first.
 * @param Boolean      canExport   Whether an exportable puppet document is open (gates the CMO3 and MOC3 rows).
 * @param Boolean      canExportImage Whether the open document can be rendered to an image (gates Export Image).
 * @param Boolean      canSave     Whether the open document can be saved (gates both Save rows).
 * @param MenuDispatch dispatch    Runs a command by id.
 * @return TopLevelMenu The File menu.
 */
@Composable
fun fileMenu(
	keymap: Keymap,
	recentFiles: List<String>,
	canExport: Boolean,
	canExportImage: Boolean,
	canSave: Boolean,
	dispatch: MenuDispatch,
): TopLevelMenu =
	TopLevelMenu(
		label = stringResource(Res.string.menu_file),
		items =
			listOf(
				commandRow(stringResource(Res.string.menu_file_new), "file.new", keymap, dispatch),
				commandRow(stringResource(Res.string.menu_file_open), "file.open", keymap, dispatch),
				MenuItem.Submenu(
					label = stringResource(Res.string.menu_open_recent),
					// A recent file is a row per path rather than a command per path, so these carry their
					// argument and show no chord.
					items = recentFiles.map { recent -> MenuItem.Action(fileDisplayName(recent), onSelect = { dispatch("file.openPath", recent) }) },
					enabled = recentFiles.isNotEmpty(),
				),
				commandRow(stringResource(Res.string.menu_file_save), "file.save", keymap, dispatch, enabled = canSave),
				commandRow(stringResource(Res.string.menu_file_save_as), "file.saveAs", keymap, dispatch, enabled = canSave),
				MenuItem.Submenu(
					label = stringResource(Res.string.menu_import),
					items =
						listOf(
							commandRow(stringResource(Res.string.menu_import_artwork), "file.importArtwork", keymap, dispatch),
							commandRow(stringResource(Res.string.menu_import_cmo3), "file.importCmo3", keymap, dispatch),
							commandRow(stringResource(Res.string.menu_import_moc3), "file.importMoc3", keymap, dispatch),
						),
				),
				MenuItem.Submenu(
					label = stringResource(Res.string.menu_export),
					items =
						listOf(
							commandRow(stringResource(Res.string.menu_export_cmo3), "file.exportCmo3", keymap, dispatch, enabled = canExport),
							commandRow(stringResource(Res.string.menu_export_moc3), "file.exportMoc3", keymap, dispatch, enabled = canExport),
							commandRow(stringResource(Res.string.menu_export_image), "file.exportImage", keymap, dispatch, enabled = canExportImage),
						),
				),
				MenuItem.Separator,
				commandRow(stringResource(Res.string.menu_exit), "file.exit", keymap, dispatch),
			),
	)

/**
 * Builds the Edit menu shared by every platform's menu bar, so desktop and the keyboardless tablet reach
 * the same entries from one source (the "one shared interface" guardrail).  Undo and Redo dispatch
 * edit.undo / edit.redo and are enabled per [canUndo] / [canRedo]; Preferences dispatches
 * edit.preferences, whose overlay the shell owns.
 *
 * @param Keymap       keymap   The keymap the accelerator hints are resolved against.
 * @param Boolean      canUndo  Whether an undo step is available (gates the Undo row).
 * @param Boolean      canRedo  Whether a redo step is available (gates the Redo row).
 * @param MenuDispatch dispatch Runs a command by id.
 * @return TopLevelMenu The Edit menu.
 */
@Composable
fun editMenu(
	keymap: Keymap,
	canUndo: Boolean,
	canRedo: Boolean,
	dispatch: MenuDispatch,
): TopLevelMenu =
	TopLevelMenu(
		label = stringResource(Res.string.menu_edit),
		items =
			listOf(
				commandRow(stringResource(Res.string.menu_undo), "edit.undo", keymap, dispatch, enabled = canUndo),
				commandRow(stringResource(Res.string.menu_redo), "edit.redo", keymap, dispatch, enabled = canRedo),
				MenuItem.Separator,
				commandRow(stringResource(Res.string.menu_preferences), "edit.preferences", keymap, dispatch),
			),
	)

/**
 * Builds the Workspace menu shared by every platform's menu bar: Previous / Next (the same tab shift the
 * strip's context menu and primary+PageUp / PageDown reach), then New (the same create path as the tab
 * strip's "+"), Reset (the shell confirms first), and the layout's trips to and from a file.  Every row
 * dispatches its workspace.* command.
 *
 * @param Keymap       keymap   The keymap the accelerator hints are resolved against.
 * @param MenuDispatch dispatch Runs a command by id.
 * @return TopLevelMenu The Workspace menu.
 */
@Composable
fun workspaceMenu(
	keymap: Keymap,
	dispatch: MenuDispatch,
): TopLevelMenu =
	TopLevelMenu(
		label = stringResource(Res.string.menu_workspace),
		items =
			listOf(
				commandRow(stringResource(Res.string.cmd_workspace_prev), "workspace.prev", keymap, dispatch),
				commandRow(stringResource(Res.string.cmd_workspace_next), "workspace.next", keymap, dispatch),
				MenuItem.Separator,
				commandRow(stringResource(Res.string.workspace_new), "workspace.new", keymap, dispatch),
				commandRow(stringResource(Res.string.menu_workspace_reset), "workspace.reset", keymap, dispatch),
				MenuItem.Separator,
				commandRow(stringResource(Res.string.menu_workspace_import), "workspace.import", keymap, dispatch),
				commandRow(stringResource(Res.string.menu_workspace_export_this), "workspace.exportThis", keymap, dispatch),
				commandRow(stringResource(Res.string.menu_workspace_export_all), "workspace.exportAll", keymap, dispatch),
			),
	)

/**
 * Builds the Help menu shared by every platform's menu bar: the project links, then Quick Setup and, where the host
 * can show a folder, Open Log Folder, then Credits and About.  Every row dispatches its help.* command - the links
 * open through the shell's handler, the log folder through the host's, and the three dialogs through the overlay
 * state the shell owns - so the palette reaches every one of them as well.
 *
 * @param Keymap       keymap           The keymap the accelerator hints are resolved against.
 * @param MenuDispatch dispatch         Runs a command by id.
 * @param Boolean      canOpenLogFolder Whether the host registered Open Log Folder; its row is left out otherwise.
 * @return TopLevelMenu The Help menu.
 */
@Composable
fun helpMenu(
	keymap: Keymap,
	dispatch: MenuDispatch,
	canOpenLogFolder: Boolean = false,
): TopLevelMenu {
	val quickSetupRow = commandRow(stringResource(Res.string.menu_quick_setup), "help.quickSetup", keymap, dispatch)
	val logFolderRow = commandRow(stringResource(Res.string.menu_open_log_folder), "help.openLogFolder", keymap, dispatch)
	return TopLevelMenu(
		label = stringResource(Res.string.menu_help),
		items =
			listOfNotNull(
				commandRow(stringResource(Res.string.menu_source_code), "help.sourceCode", keymap, dispatch),
				commandRow(stringResource(Res.string.menu_web_site), "help.webSite", keymap, dispatch),
				commandRow(stringResource(Res.string.menu_documentation), "help.documentation", keymap, dispatch),
				MenuItem.Separator,
				quickSetupRow,
				logFolderRow.takeIf { canOpenLogFolder },
				MenuItem.Separator,
				commandRow(stringResource(Res.string.menu_credits), "help.credits", keymap, dispatch),
				commandRow(stringResource(Res.string.menu_about), "help.about", keymap, dispatch),
			),
	)
}