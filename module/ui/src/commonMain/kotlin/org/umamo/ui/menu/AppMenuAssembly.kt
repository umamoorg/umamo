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
 * What the menu bar's rows do.  Every row that stands for a command names it through [dispatch], so the
 * menu, the keyboard binding, and the palette share the one path and a rebind reaches all three.  The
 * members beside it are the rows with no command behind them: each carries an argument or an effect that
 * belongs to the host (a recent file's path, a link's URL, closing the application).
 *
 * @property Function dispatch      Runs a command by id through the registry.
 * @property Function openRecent    Opens a recent file by its stored path.
 * @property Function exit          Closes the application, asking first over unsaved changes.
 * @property Function openInBrowser Opens a Help-menu URL through the platform's UriHandler.
 */
internal class AppMenuActions(
	val dispatch: (commandId: String) -> Unit,
	val openRecent: (path: String) -> Unit,
	val exit: () -> Unit,
	val openInBrowser: (url: String) -> Unit,
)

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
 * @param Boolean        canExport Whether an exportable puppet document is open (gates both Export rows).
 * @param AppMenuActions actions   What the rows do.
 * @return List The top-level menus.
 */
@Composable
internal fun buildAppMenu(
	settings: Settings,
	session: EditorSession?,
	canSave: Boolean,
	canExport: Boolean,
	actions: AppMenuActions,
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
			fileMenu(
				keymap = keymap,
				recentFiles = recentFiles,
				canExport = canExport,
				canSave = canSave,
				onNew = { actions.dispatch("file.new") },
				onOpen = { actions.dispatch("file.open") },
				onSave = { actions.dispatch("file.save") },
				onSaveAs = { actions.dispatch("file.saveAs") },
				// The artwork import is the shell's command (it needs the hovered area for its operation
				// strip), so the row dispatches it rather than calling a picker directly.
				onImportArtwork = { actions.dispatch("file.importArtwork") },
				onImportCmo3 = { actions.dispatch("file.importCmo3") },
				onOpenRecent = actions.openRecent,
				onImportMoc3 = { actions.dispatch("file.importMoc3") },
				onExportCmo3 = { actions.dispatch("file.exportCmo3") },
				onExportMoc3 = { actions.dispatch("file.exportMoc3") },
				onExit = actions.exit,
			),
			// The rows are gated by canUndo / canRedo; the shell owns the settings overlay's visible state,
			// so Preferences only dispatches its command.
			editMenu(
				keymap = keymap,
				canUndo = canUndo,
				canRedo = canRedo,
				onUndo = { actions.dispatch("edit.undo") },
				onRedo = { actions.dispatch("edit.redo") },
				onOpenPreferences = { actions.dispatch("edit.preferences") },
			),
			workspaceMenu(
				keymap = keymap,
				onNewWorkspace = { actions.dispatch("workspace.new") },
				onResetWorkspace = { actions.dispatch("workspace.reset") },
				onImportWorkspace = { actions.dispatch("workspace.import") },
				onExportThisWorkspace = { actions.dispatch("workspace.exportThis") },
				onExportAllWorkspaces = { actions.dispatch("workspace.exportAll") },
			),
			// The Help dialogs open through the registry too: the shell owns their visible state.
			helpMenu(
				keymap = keymap,
				openInBrowser = actions.openInBrowser,
				onOpenCredits = { actions.dispatch("help.credits") },
				onOpenAbout = { actions.dispatch("help.about") },
			),
		)
	}
}