package org.umamo.ui.menu

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.settings.Settings
import org.umamo.ui.action.rememberLiveKeymap
import org.umamo.ui.document.recentFiles
import org.umamo.ui.kit.menu.TopLevelMenu
import org.umamo.ui.l10n.applyAppLocale
import org.umamo.ui.l10n.rememberLocaleTag

/**
 * Builds the in-window menu bar from the shared per-menu builders and keeps it live against the keymap,
 * the recent-files list, the session's undo and redo availability and mode, and the UI language.
 *
 * The bar is built outside the shell's own ProvideAppLocale scope, so it re-localizes itself, scoped to
 * the menu alone: a language switch re-localizes the bar without re-mounting the viewport or the shell
 * underneath.
 *
 * @param Settings       settings  The settings the keymap, the recent files, and the locale are read from.
 * @param EditorSession? session   The open document's session, or null; gates the Undo and Redo rows and the
 *   Select menu.
 * @param Boolean        canSave   Whether the open document can be saved (gates both Save rows).
 * @param Boolean        canExport Whether an exportable puppet document is open (gates the CMO3 and MOC3 rows).
 * @param Boolean        canExportImage Whether the open document can be rendered to an image (gates Export Image).
 * @param MenuDispatch   dispatch  Runs a command by id; every row of the bar stands for a command, so this is
 *   all the bar needs from its host, and a rebind reaches the menu, the keyboard, and the palette alike.
 * @param Boolean        canOpenLogFolder Whether the host registered Open Log Folder (shows its Help row).
 * @param Boolean        canCheckForUpdates Whether the host registered Check for Updates (shows its Help row).
 * @return List The top-level menus.
 */
@Composable
internal fun buildAppMenu(
	settings: Settings,
	session: EditorSession?,
	canSave: Boolean,
	canExport: Boolean,
	canExportImage: Boolean,
	dispatch: MenuDispatch,
	canOpenLogFolder: Boolean = false,
	canCheckForUpdates: Boolean = false,
): List<TopLevelMenu> {
	// produceState runs unconditionally (the session may be null with no document) and re-collects when
	// the session swaps.
	val canUndo by produceState(false, session) {
		val activeSession = session

		if (activeSession == null) {
			value = false
		} else {
			activeSession.canUndo.collect { value = it }
		}
	}
	val canRedo by produceState(false, session) {
		val activeSession = session

		if (activeSession == null) {
			value = false
		} else {
			activeSession.canRedo.collect { value = it }
		}
	}
	val inObjectMode by produceState(false, session) {
		val activeSession = session

		if (activeSession == null) {
			value = false
		} else {
			activeSession.mode.collect { mode -> value = mode == EditorMode.Object }
		}
	}
	val keymap by rememberLiveKeymap(settings)
	// Re-read on any settings change, so the Open Recent menu stays current after an open or a save.
	val recentFiles by produceState(initialValue = settings.recentFiles(), settings) {
		settings.changes.collect { value = settings.recentFiles() }
	}
	val locale by rememberLocaleTag(settings)

	// The key re-resolves the builders' stringResource() calls against the new catalog, and the remember
	// applies the platform locale that resolution reads before the builders run.
	return key(locale) {
		remember(locale) { applyAppLocale(locale) }
		listOf(
			fileMenu(keymap = keymap, recentFiles = recentFiles, canExport = canExport, canExportImage = canExportImage, canSave = canSave, dispatch = dispatch),
			editMenu(keymap = keymap, canUndo = canUndo, canRedo = canRedo, dispatch = dispatch),
			selectMenu(keymap = keymap, hasDocument = session != null, inObjectMode = inObjectMode, dispatch = dispatch),
			workspaceMenu(keymap = keymap, dispatch = dispatch),
			helpMenu(keymap = keymap, dispatch = dispatch, canOpenLogFolder = canOpenLogFolder, canCheckForUpdates = canCheckForUpdates),
		)
	}
}