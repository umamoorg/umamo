package org.umamo.ui.menu

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import org.umamo.edit.EditorSession
import org.umamo.settings.Settings
import org.umamo.ui.action.rememberLiveKeymap
import org.umamo.ui.document.recentFiles
import org.umamo.ui.kit.TopLevelMenu
import org.umamo.ui.l10n.applyAppLocale
import org.umamo.ui.l10n.rememberLocaleTag

/**
 * Builds the in-window menu bar from the shared per-menu builders, and keeps it live: the accelerators
 * follow the keymap settings (so a row shows the chord the keyboard uses after a preset switch or a
 * rebind), Open Recent follows the recent-files list, the Edit rows follow the session's undo and redo
 * availability, and the labels follow the UI language.
 *
 * The bar is built outside the shell's own ProvideAppLocale scope, so it re-localizes itself: the
 * key on the locale re-resolves the builders' stringResource() calls against the new catalog, and the
 * remember applies the platform locale that resolution reads before the builders run.  That is scoped
 * to the menu alone, so a language switch re-localizes the bar without re-mounting the viewport or the
 * shell underneath.
 *
 * @param Settings       settings  The settings the keymap, the recent files, and the locale are read from.
 * @param EditorSession? session   The open document's session, or null; gates the Undo and Redo rows.
 * @param Boolean        canSave   Whether the open document can be saved (gates both Save rows).
 * @param Boolean        canExport Whether an exportable puppet document is open (gates the CMO3 and MOC3 rows).
 * @param Boolean        canExportImage Whether the open document can be rendered to an image (gates Export Image).
 * @param MenuDispatch   dispatch  Runs a command by id; every row of the bar stands for a command, so this is
 *   all the bar needs from its host, and a rebind reaches the menu, the keyboard, and the palette alike.
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
	val keymap by rememberLiveKeymap(settings)
	// Re-read on any settings change, so the Open Recent menu stays current after an open or a save.
	val recentFiles by produceState(initialValue = settings.recentFiles(), settings) {
		settings.changes.collect { value = settings.recentFiles() }
	}
	val locale by rememberLocaleTag(settings)
	return key(locale) {
		remember(locale) { applyAppLocale(locale) }
		listOf(
			fileMenu(keymap = keymap, recentFiles = recentFiles, canExport = canExport, canExportImage = canExportImage, canSave = canSave, dispatch = dispatch),
			editMenu(keymap = keymap, canUndo = canUndo, canRedo = canRedo, dispatch = dispatch),
			workspaceMenu(keymap = keymap, dispatch = dispatch),
			helpMenu(keymap = keymap, dispatch = dispatch),
		)
	}
}