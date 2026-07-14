package org.umamo.ui.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalUriHandler
import io.github.vinceglb.filekit.absolutePath
import io.github.vinceglb.filekit.readString
import io.github.vinceglb.filekit.write
import io.github.vinceglb.filekit.writeString
import kotlinx.coroutines.launch
import org.umamo.edit.EditorSession
import org.umamo.format.FileKind
import org.umamo.format.cmo3.Cmo3
import org.umamo.storage.FileKitFilePicker
import org.umamo.storage.UmamoLog
import org.umamo.storage.platformFileFromSavedPath
import org.umamo.ui.LocalSettings
import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.Keymap
import org.umamo.ui.action.loadKeymap
import org.umamo.ui.document.Cmo3Document
import org.umamo.ui.document.Document
import org.umamo.ui.document.DocumentLoad
import org.umamo.ui.document.addRecentFile
import org.umamo.ui.document.loadDocument
import org.umamo.ui.document.recentFiles
import org.umamo.ui.kit.TopLevelMenu
import org.umamo.ui.l10n.applyAppLocale
import org.umamo.ui.menu.editMenu
import org.umamo.ui.menu.fileMenu
import org.umamo.ui.menu.helpMenu
import org.umamo.ui.menu.workspaceMenu
import org.umamo.ui.model.DrawableThumbnailer
import org.umamo.ui.model.LocalDrawableThumbnails
import org.umamo.ui.model.LocalEditorMode
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.model.LocalLiveParams
import org.umamo.ui.model.LocalPuppet
import org.umamo.ui.model.LocalPuppetRenderSync
import org.umamo.ui.model.LocalPuppetTextures
import org.umamo.ui.model.LocalPuppetViewportService
import org.umamo.ui.model.LocalSelection
import org.umamo.ui.model.rememberSessionEditorState
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.menu_open
import org.umamo.ui.resources.menu_save_as
import org.umamo.ui.viewport.LiveParamsAdapter
import org.umamo.ui.viewport.PuppetViewportServiceFactory
import org.umamo.ui.viewport.rememberPuppetViewportHost
import org.umamo.ui.workspace.INTERFACE_LAYOUT_KEY
import org.umamo.ui.workspace.PersistentEditorShell
import org.umamo.ui.workspace.decodeLayout
import org.umamo.ui.workspace.decodeLayoutText
import org.umamo.ui.workspace.decodeWorkspaceText
import org.umamo.ui.workspace.exportLayoutText
import org.umamo.ui.workspace.exportWorkspaceText

/**
 * The one editing session per open CMO3 document (the undo history + dirty state live here), recreated
 * when the document swaps; null for no document. Owned at the host level so both the host's own chrome
 * (e.g. the desktop title's unsaved marker) and [EditorApp] share the one session.
 *
 * @param Document? document The open document, or null.
 * @return EditorSession? The document's session, or null with no CMO3 open.
 */
@Composable
fun rememberEditorSessionFor(document: Document?): EditorSession? =
	remember(document) { (document as? Cmo3Document)?.let { EditorSession(it.puppet, it.liveParams.values) } }

/**
 * The shared editor shell: a custom in-window menu bar (File / Edit / Workspace / Help, drawn with the
 * kit menu system) over the document viewport. The Open/Save dialogs come from FileKit; [document] is
 * owned by the caller (so host chrome like the window title tracks it); [onOpen] swaps it; [onExit]
 * closes. Both apps mount this one composable - desktop supplies the GL [viewportServiceFactory],
 * Android passes null until its GLES renderer lands (viewport areas show placeholders, everything else
 * is identical).
 *
 * The menu bar is drawn in-window (not a host-OS menu strip) so it looks and behaves identically on
 * every platform and can sit on the workspace tab row to save vertical space - the Blender-style
 * choice. The trade-off is deliberate: there is no macOS system menu strip.
 *
 * 共有エディタシェル。OS ネイティブではなく自前のメニューバーを描画する（全プラットフォーム共通、
 * タブ行に同居して縦幅を節約）。デスクトップは GL ファクトリを渡し、Android は GLES 実装が載るまで null。
 *
 * @param Document? document The open document, or null.
 * @param EditorSession? session The open document's editing session (non-null for a CMO3 document); drives
 *   undo/redo, the Edit-menu enabled state, and the saved marker.
 * @param Function onOpen Called with a newly-opened document.
 * @param Function onExit Closes the application.
 * @param PuppetViewportServiceFactory? viewportServiceFactory Creates the platform render service, or
 *   null on a platform without a puppet renderer yet (viewport areas render placeholders).
 */
@Composable
fun EditorApp(
	document: Document?,
	session: EditorSession?,
	onOpen: (Document) -> Unit,
	onExit: () -> Unit,
	viewportServiceFactory: PuppetViewportServiceFactory?,
) {
	val settings = LocalSettings.current
	val scope = rememberCoroutineScope()
	val filePicker = remember { FileKitFilePicker() }
	val commandRegistry = remember { CommandRegistry() }
	val uriHandler = LocalUriHandler.current
	// Mirror the session's undo/redo availability for the Edit menu's enabled state. produceState runs
	// unconditionally (the session may be null with no document) and re-collects when the session swaps.
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
	// Resolve the keymap from settings (preset + overrides) and keep it live, so the menu's accelerator hints
	// match what the keyboard actually does after a preset switch or a rebind in the settings window.
	val keymap by produceState(initialValue = loadKeymap(settings), settings) {
		settings.changes.collect { changedKey ->
			if (changedKey.startsWith("input.keybinding")) {
				value = loadKeymap(settings)
			}
		}
	}
	// Re-read recent files whenever any setting changes, so the Open Recent menu stays current after Open.
	val recentFiles by produceState(initialValue = settings.recentFiles(), settings) {
		settings.changes.collect { value = settings.recentFiles() }
	}
	// The menu bar is built here, outside the shell's own ProvideAppLocale scope, so it must re-localize
	// itself when localization.locale changes - otherwise a runtime language switch leaves it stale.
	val locale by produceState(initialValue = settings.getString("localization.locale") ?: "en", settings) {
		settings.changes.collect { changedKey ->
			if (changedKey == "localization.locale") {
				value = settings.getString("localization.locale") ?: "en"
			}
		}
	}

	// A load failure raises the shell's modal alert (the document.openFailed command) so the user sees
	// why nothing opened; success records the recent file and swaps the document in.
	fun applyDocumentLoad(load: DocumentLoad) {
		when (load) {
			is DocumentLoad.Loaded -> {
				settings.addRecentFile(load.document.path)
				onOpen(load.document)
			}
			is DocumentLoad.Failed -> commandRegistry.invoke("document.openFailed", load.failure)
		}
	}

	fun openStoredPath(path: String) {
		scope.launch {
			applyDocumentLoad(loadDocument(platformFileFromSavedPath(path)))
		}
	}

	fun openViaPicker() {
		// FileKit's native dialog supplies its own (OS-localized) title, so none is passed here.
		// The filter is CMO3-only: layered-art formats have no document-open path since :reimport is
		// what would bind them into a session, and that binding doesn't exist yet.
		scope.launch {
			filePicker.openFile(listOf(FileKind.Cmo3.extension))?.let { picked ->
				applyDocumentLoad(loadDocument(picked))
			}
		}
	}

	fun saveAs(cmo3Document: Cmo3Document) {
		scope.launch {
			// Suggest the base name without the extension; FileKit re-appends ".cmo3".
			val suggestedName = cmo3Document.displayName.removeSuffix(".cmo3")
			filePicker.saveFile(suggestedName, "cmo3")?.let { destination ->
				// Writes the original CMO3 bytes, not the edited PuppetModel: there is no model -> CMO3
				// lowering yet, so edits made in the session are not persisted here. markSaved still moves
				// the dirty baseline so the modified marker clears, exercising the undo-history save
				// mechanism ahead of that lowering.
				destination.write(Cmo3.write(cmo3Document.cmo3))
				session?.markSaved()
				UmamoLog.info("saved ${destination.absolutePath()}")
			}
		}
	}

	fun exportAllWorkspaces() {
		// "the saved JSON from settings.json": the whole interface.layout, pretty-printed (null if unsaved).
		val text = exportLayoutText(settings) ?: return
		scope.launch {
			filePicker.saveFile("workspaces", "json")?.let { destination ->
				destination.writeString(text)
				UmamoLog.info("exported all workspaces to ${destination.absolutePath()}")
			}
		}
	}

	fun exportThisWorkspace() {
		// The active workspace from the persisted layout; its display name (or id) seeds the suggested filename.
		val active = settings.get(INTERFACE_LAYOUT_KEY)?.let { element -> decodeLayout(element) }?.activeWorkspace() ?: return
		val text = exportWorkspaceText(active)
		scope.launch {
			filePicker.saveFile(active.name ?: active.id, "json")?.let { destination ->
				destination.writeString(text)
				UmamoLog.info("exported workspace to ${destination.absolutePath()}")
			}
		}
	}

	fun importWorkspace() {
		scope.launch {
			filePicker.openFile(listOf("json"))?.let { picked ->
				// Detect the file shape: a whole layout overwrites all (the shell confirms); a single workspace
				// is appended as a new tab; anything else is rejected without touching the current layout.
				val text = picked.readString()
				val importedLayout = decodeLayoutText(text)
				if (importedLayout != null) {
					commandRegistry.invoke("workspace.applyLayout", importedLayout)
				} else {
					val importedWorkspace = decodeWorkspaceText(text)
					if (importedWorkspace != null) {
						commandRegistry.invoke("workspace.appendWorkspace", importedWorkspace)
					} else {
						UmamoLog.warn("invalid workspace file: ${picked.absolutePath()}")
					}
				}
			}
		}
	}

	// Register the file operations as real commands so the keymap drives them (Ctrl+O / Ctrl+S dispatch
	// through the shell's registry).  file.saveAs re-registers on a document swap so its handler closes
	// over the current document; it is a no-op unless a CMO3 is open.
	DisposableEffect(commandRegistry) {
		commandRegistry.register(Command("file.open", title = Res.string.menu_open) { openViaPicker() })
		onDispose { commandRegistry.unregister("file.open") }
	}
	DisposableEffect(commandRegistry, document) {
		val cmo3Document = document as? Cmo3Document
		commandRegistry.register(Command("file.saveAs", title = Res.string.menu_save_as) { cmo3Document?.let { saveAs(it) } })
		onDispose { commandRegistry.unregister("file.saveAs") }
	}

	// key(locale) re-resolves the menu's stringResource() calls against the new catalog when the language
	// changes (the same lever ProvideAppLocale uses for the shell's own subtree); the remember applies the
	// JVM locale the resolution reads, before buildAppMenu runs.  Scoped to just the menu, so a language
	// switch re-localizes the bar without re-mounting the viewport or the shell underneath.
	val appMenu =
		key(locale) {
			remember(locale) { applyAppLocale(locale) }
			buildAppMenu(
				document,
				recentFiles,
				keymap,
				canUndo,
				canRedo,
				::openStoredPath,
				::openViaPicker,
				::saveAs,
				onExit,
				// Undo / Redo dispatch through the registry like everything else, so the menu, the Ctrl/Cmd+Z
				// binding, and the palette share the one path; the rows are gated by canUndo / canRedo above.
				{ commandRegistry.invoke("edit.undo") },
				{ commandRegistry.invoke("edit.redo") },
				// The shell owns the settings overlay's visible state; the menu only dispatches the command, so
				// the menu, the keyboard binding, and the palette share one path (the same shape as workspace.new).
				{ commandRegistry.invoke("edit.preferences") },
				{ commandRegistry.invoke("workspace.new") },
				{ commandRegistry.invoke("workspace.reset") },
				::exportThisWorkspace,
				::exportAllWorkspaces,
				::importWorkspace,
				// Open the Help links through Compose's common UriHandler (browser on desktop, intent on
				// Android), failing quietly - a log line, never a crash - when the platform refuses.
				{ url -> runCatching { uriHandler.openUri(url) }.onFailure { failure -> UmamoLog.error("could not open $url", failure) } },
				// The Help dialogs open through the registry (the shell owns their visible state), the same
				// shape as edit.preferences.
				{ commandRegistry.invoke("help.credits") },
				{ commandRegistry.invoke("help.about") },
			)
		}
	DocumentViewport(
		document = document,
		session = session,
		commandRegistry = commandRegistry,
		appMenu = appMenu,
		viewportServiceFactory = viewportServiceFactory,
	)
}

/**
 * Builds the menu-bar data for the in-window menu bar from the shared per-menu builders. Item labels
 * are localized here (in composition) and accelerators are resolved from [keymap], so a row shows the
 * same chord the keyboard uses. Item actions call the supplied operations directly, so the menu works
 * regardless of command-registration timing.
 *
 * メニューバーのデータを共有ビルダーから構築する。ラベルはここで翻訳し、アクセラレータはキーマップから解決する。
 *
 * @param Document? document The open document (gates Save As).
 * @param List recentFiles The recent file paths for the Open Recent submenu.
 * @param Keymap keymap The keymap accelerators are resolved against.
 * @param Boolean canUndo Whether an undo step is available (gates the Edit menu's Undo row).
 * @param Boolean canRedo Whether a redo step is available (gates the Edit menu's Redo row).
 * @param Function openRecent Opens a recent file by its stored path.
 * @param Function openPicker Opens the file picker.
 * @param Function saveAs Saves the given CMO3 document via a picker.
 * @param Function onExit Closes the application.
 * @param Function onUndo Undoes one step (dispatches edit.undo).
 * @param Function onRedo Redoes one step (dispatches edit.redo).
 * @param Function onOpenPreferences Opens the settings window (dispatches edit.preferences).
 * @param Function onNewWorkspace Creates a new workspace (the + create path).
 * @param Function onResetWorkspace Resets the active workspace to its default layout.
 * @param Function onExportThisWorkspace Exports the active workspace to a file.
 * @param Function onExportAllWorkspaces Exports the whole layout to a file.
 * @param Function onImportWorkspace Imports a workspace/layout file.
 * @param Function openInBrowser Opens a Help-menu URL via the platform's UriHandler.
 * @param Function onOpenCredits Opens the Credits dialog (dispatches help.credits).
 * @param Function onOpenAbout Opens the About dialog (dispatches help.about).
 * @return List The top-level menus.
 */
@Composable
private fun buildAppMenu(
	document: Document?,
	recentFiles: List<String>,
	keymap: Keymap,
	canUndo: Boolean,
	canRedo: Boolean,
	openRecent: (String) -> Unit,
	openPicker: () -> Unit,
	saveAs: (Cmo3Document) -> Unit,
	onExit: () -> Unit,
	onUndo: () -> Unit,
	onRedo: () -> Unit,
	onOpenPreferences: () -> Unit,
	onNewWorkspace: () -> Unit,
	onResetWorkspace: () -> Unit,
	onExportThisWorkspace: () -> Unit,
	onExportAllWorkspaces: () -> Unit,
	onImportWorkspace: () -> Unit,
	openInBrowser: (String) -> Unit,
	onOpenCredits: () -> Unit,
	onOpenAbout: () -> Unit,
): List<TopLevelMenu> =
	listOf(
		fileMenu(
			keymap = keymap,
			recentFiles = recentFiles,
			canSaveAs = document is Cmo3Document,
			onOpen = openPicker,
			onOpenRecent = openRecent,
			onSaveAs = { (document as? Cmo3Document)?.let { saveAs(it) } },
			onExit = onExit,
		),
		editMenu(keymap, canUndo, canRedo, onUndo, onRedo, onOpenPreferences),
		workspaceMenu(keymap, onNewWorkspace, onResetWorkspace, onImportWorkspace, onExportThisWorkspace, onExportAllWorkspaces),
		helpMenu(keymap, openInBrowser, onOpenCredits, onOpenAbout),
	)

/**
 * Renders the open document inside the editor shell. For a CMO3 puppet, a per-area viewport host is
 * injected (when the platform supplies a render-service factory) and the runtime model + live params
 * are provided to the panels; with no document, the shell shows placeholders. With a null factory the
 * model locals still mount - the outliner, parameters, and thumbnails all work - only the viewport
 * areas render placeholders. The shell's workspace layout + locale are persisted via settings
 * regardless of the open document.
 *
 * @param Document? document The open document, or null.
 * @param EditorSession? session The open document's editing session (non-null for a CMO3 document).
 * @param CommandRegistry commandRegistry The registry the file commands are registered in (drives the keymap).
 * @param List appMenu The menu-bar contents, mounted by each shell.
 * @param PuppetViewportServiceFactory? viewportServiceFactory Creates the platform render service, or null.
 */
@Composable
private fun DocumentViewport(
	document: Document?,
	session: EditorSession?,
	commandRegistry: CommandRegistry,
	appMenu: List<TopLevelMenu>,
	viewportServiceFactory: PuppetViewportServiceFactory?,
) {
	when (document) {
		is Cmo3Document ->
			key(document) {
				// The session is created per-document by the host and is non-null for a CMO3 document;
				// the fallback only guards a desync. Panels read LocalPuppet (a live projection of the session
				// model) and drive edits through LocalEditorSession / the session-backed selection handle.
				val activeSession = session ?: remember(document) { EditorSession(document.puppet, document.liveParams.values) }
				val editorState = rememberSessionEditorState(activeSession)
				// The factory is fixed for the app's lifetime (a platform capability, not state), so the
				// conditional composable call is stable across recompositions.
				val viewport =
					if (viewportServiceFactory != null) {
						rememberPuppetViewportHost(document.puppet, document.textures, document.liveParams, activeSession, viewportServiceFactory)
					} else {
						null
					}
				val liveParamsHandle = remember(document, activeSession) { LiveParamsAdapter(document.liveParams, activeSession) }
				// Without a viewport the thumbnails come straight from the shared thumbnailer, so the
				// outliner's hover previews work before a platform puppet renderer exists.
				val thumbnails = viewport?.thumbnails ?: remember(document) { DrawableThumbnailer(document.puppet, document.textures) }
				val model by activeSession.model.collectAsState()
				CompositionLocalProvider(
					LocalPuppet provides model,
					LocalEditorSession provides activeSession,
					LocalLiveParams provides liveParamsHandle,
					LocalDrawableThumbnails provides thumbnails,
					LocalPuppetTextures provides document.textures,
					LocalPuppetRenderSync provides viewport?.renderSync,
					LocalPuppetViewportService provides viewport?.service,
					LocalSelection provides editorState,
					LocalEditorMode provides editorState,
				) {
					PersistentEditorShell(viewportHost = viewport?.host, commandRegistry = commandRegistry, appMenu = appMenu)
				}
			}
		null ->
			// No document open: the shell renders with placeholder viewport areas (no host injected).
			PersistentEditorShell(commandRegistry = commandRegistry, appMenu = appMenu)
	}
}
