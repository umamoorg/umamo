package org.umamo.ui.menu

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.action.Keymap
import org.umamo.ui.action.formatAccelerator
import org.umamo.ui.document.fileDisplayName
import org.umamo.ui.help.ProjectInfo
import org.umamo.ui.kit.MenuItem
import org.umamo.ui.kit.TopLevelMenu
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.menu_about
import org.umamo.ui.resources.menu_credits
import org.umamo.ui.resources.menu_documentation
import org.umamo.ui.resources.menu_edit
import org.umamo.ui.resources.menu_exit
import org.umamo.ui.resources.menu_file
import org.umamo.ui.resources.menu_help
import org.umamo.ui.resources.menu_import_moc3
import org.umamo.ui.resources.menu_open
import org.umamo.ui.resources.menu_open_recent
import org.umamo.ui.resources.menu_preferences
import org.umamo.ui.resources.menu_redo
import org.umamo.ui.resources.menu_save_as
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
 * Builds the File menu shared by every platform's menu bar. Open / Open Recent / Save As reach their
 * operations through the caller's handlers (which route through the file.open / file.saveAs commands
 * and the shared FileKit picker), so the menu, the keyboard, and the palette share one path. The Save
 * As row is gated on [canSaveAs] (a CMO3 document is open); Open Recent labels each stored path via
 * fileDisplayName and disables itself when the list is empty.
 *
 * 全プラットフォーム共通の File メニューを構築する。開く／最近使ったファイル／別名で保存はコマンド経由で
 * 到達し、メニュー・キーボード・パレットが同一経路を共有する。
 *
 * @param Keymap keymap The keymap the accelerator hints are resolved against.
 * @param List recentFiles The recent file paths for the Open Recent submenu, most-recent first.
 * @param Boolean canSaveAs Whether a saveable document is open (gates the Save As row).
 * @param Function onOpen Opens the file picker.
 * @param Function onOpenRecent Opens a recent file by its stored path.
 * @param Function onImportMoc3 Opens the MOC3 import picker (routes through file.importMoc3).
 * @param Function onSaveAs Saves the open document via a picker.
 * @param Function onExit Closes the application.
 * @return TopLevelMenu The File menu.
 */
@Composable
fun fileMenu(
	keymap: Keymap,
	recentFiles: List<String>,
	canSaveAs: Boolean,
	onOpen: () -> Unit,
	onOpenRecent: (String) -> Unit,
	onImportMoc3: () -> Unit,
	onSaveAs: () -> Unit,
	onExit: () -> Unit,
): TopLevelMenu =
	TopLevelMenu(
		label = stringResource(Res.string.menu_file),
		items =
			listOf(
				MenuItem.Action(
					label = stringResource(Res.string.menu_open),
					onSelect = onOpen,
					shortcut = keymap.chordFor("file.open")?.let { chord -> formatAccelerator(chord) },
				),
				MenuItem.Submenu(
					label = stringResource(Res.string.menu_open_recent),
					items = recentFiles.map { recent -> MenuItem.Action(fileDisplayName(recent), onSelect = { onOpenRecent(recent) }) },
					enabled = recentFiles.isNotEmpty(),
				),
				// A flat row rather than an Import submenu while MOC3 is the only import; fold into a
				// submenu when image import lands (TODO.md's File > Import/Export grouping).
				MenuItem.Action(
					label = stringResource(Res.string.menu_import_moc3),
					onSelect = onImportMoc3,
					shortcut = keymap.chordFor("file.importMoc3")?.let { chord -> formatAccelerator(chord) },
				),
				MenuItem.Action(
					label = stringResource(Res.string.menu_save_as),
					onSelect = onSaveAs,
					shortcut = keymap.chordFor("file.saveAs")?.let { chord -> formatAccelerator(chord) },
					enabled = canSaveAs,
				),
				MenuItem.Separator,
				MenuItem.Action(stringResource(Res.string.menu_exit), onSelect = onExit),
			),
	)

/**
 * Builds the Edit menu shared by every platform's menu bar, so desktop and the keyboardless tablet reach
 * the same entries from one source (the "one shared interface" guardrail).  Undo / Redo dispatch through
 * [onUndo] / [onRedo] (the caller routes them to the edit.undo / edit.redo commands) and are enabled per
 * [canUndo] / [canRedo]; Preferences dispatches through [onOpenPreferences].  Every item's accelerator
 * hint is resolved from [keymap] so each row shows the same chord the keyboard uses, and all three reach
 * their target by the one command path the keyboard and palette also use.
 *
 * 全プラットフォーム共通の Edit メニューを構築する。取り消し／やり直し／設定はコマンドに委譲し、メニュー・
 * キーボード・コマンドパレットが同一経路で到達する。アクセラレータはキーマップから解決する。
 *
 * @param Keymap keymap The keymap the accelerator hints are resolved against.
 * @param Boolean canUndo Whether an undo step is available (gates the Undo row).
 * @param Boolean canRedo Whether a redo step is available (gates the Redo row).
 * @param Function onUndo Undoes one step (the caller dispatches edit.undo).
 * @param Function onRedo Redoes one step (the caller dispatches edit.redo).
 * @param Function onOpenPreferences Opens the settings window (the caller dispatches edit.preferences).
 * @return TopLevelMenu The Edit menu.
 */
@Composable
fun editMenu(
	keymap: Keymap,
	canUndo: Boolean,
	canRedo: Boolean,
	onUndo: () -> Unit,
	onRedo: () -> Unit,
	onOpenPreferences: () -> Unit,
): TopLevelMenu =
	TopLevelMenu(
		label = stringResource(Res.string.menu_edit),
		items =
			listOf(
				MenuItem.Action(
					label = stringResource(Res.string.menu_undo),
					onSelect = onUndo,
					shortcut = keymap.chordFor("edit.undo")?.let { chord -> formatAccelerator(chord) },
					enabled = canUndo,
				),
				MenuItem.Action(
					label = stringResource(Res.string.menu_redo),
					onSelect = onRedo,
					shortcut = keymap.chordFor("edit.redo")?.let { chord -> formatAccelerator(chord) },
					enabled = canRedo,
				),
				MenuItem.Separator,
				MenuItem.Action(
					label = stringResource(Res.string.menu_preferences),
					onSelect = onOpenPreferences,
					shortcut = keymap.chordFor("edit.preferences")?.let { chord -> formatAccelerator(chord) },
				),
			),
	)

@Composable
fun workspaceMenu(
	keymap: Keymap,
	onNewWorkspace: () -> Unit,
	onResetWorkspace: () -> Unit,
	onImportWorkspace: () -> Unit,
	onExportThisWorkspace: () -> Unit,
	onExportAllWorkspaces: () -> Unit,
): TopLevelMenu =
	TopLevelMenu(
		label = stringResource(Res.string.menu_workspace),
		items =
			listOf(
				MenuItem.Action(
					stringResource(Res.string.workspace_new),
					onSelect = onNewWorkspace,
					shortcut = keymap.chordFor("workspace.new")?.let { chord -> formatAccelerator(chord) },
				),
				MenuItem.Action(
					stringResource(Res.string.menu_workspace_reset),
					onSelect = onResetWorkspace,
					shortcut = keymap.chordFor("workspace.reset")?.let { chord -> formatAccelerator(chord) },
				),
				MenuItem.Separator,
				MenuItem.Action(
					stringResource(Res.string.menu_workspace_import),
					onSelect = onImportWorkspace,
					shortcut = keymap.chordFor("workspace.import")?.let { chord -> formatAccelerator(chord) },
				),
				MenuItem.Action(
					stringResource(Res.string.menu_workspace_export_this),
					onSelect = onExportThisWorkspace,
					shortcut =
						keymap.chordFor("workspace.export_this")
							?.let { chord -> formatAccelerator(chord) },
				),
				MenuItem.Action(
					stringResource(Res.string.menu_workspace_export_all),
					onSelect = onExportAllWorkspaces,
					shortcut =
						keymap.chordFor("workspace.export_all")
							?.let { chord -> formatAccelerator(chord) },
				),
			),
	)

/**
 * Builds the Help menu shared by every platform's menu bar: the project links (opened through the
 * caller's browser handler) plus Credits and About, which dispatch through the help.credits /
 * help.about commands so the menu, the palette, and any future binding share one path.  The URLs
 * come from [ProjectInfo], the same source the About dialog shows.
 *
 * 全プラットフォーム共通の Help メニュー。リンク項目とクレジット／バージョン情報（コマンド経由）。
 *
 * @param Keymap keymap The keymap the accelerator hints are resolved against (none bound today).
 * @param Function openInBrowser Opens a URL via the platform's UriHandler.
 * @param Function onOpenCredits Opens the Credits dialog (the caller dispatches help.credits).
 * @param Function onOpenAbout Opens the About dialog (the caller dispatches help.about).
 * @return TopLevelMenu The Help menu.
 */
@Composable
fun helpMenu(
	keymap: Keymap,
	openInBrowser: (String) -> Unit,
	onOpenCredits: () -> Unit,
	onOpenAbout: () -> Unit,
): TopLevelMenu =
	TopLevelMenu(
		label = stringResource(Res.string.menu_help),
		items =
			listOf(
				MenuItem.Action(
					stringResource(Res.string.menu_source_code),
					onSelect = { openInBrowser(ProjectInfo.SOURCE_CODE_URL) },
				),
				MenuItem.Action(stringResource(Res.string.menu_web_site), onSelect = { openInBrowser(ProjectInfo.WEB_SITE_URL) }),
				MenuItem.Action(
					stringResource(Res.string.menu_documentation),
					onSelect = { openInBrowser(ProjectInfo.DOCUMENTATION_URL) },
				),
				MenuItem.Separator,
				MenuItem.Action(stringResource(Res.string.menu_credits), onSelect = onOpenCredits),
				MenuItem.Action(stringResource(Res.string.menu_about), onSelect = onOpenAbout),
			),
	)
