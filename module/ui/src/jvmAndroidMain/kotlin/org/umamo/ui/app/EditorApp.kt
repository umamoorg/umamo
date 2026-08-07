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
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readString
import io.github.vinceglb.filekit.write
import io.github.vinceglb.filekit.writeString
import kotlinx.coroutines.launch
import org.umamo.edit.EditorSession
import org.umamo.format.FileKind
import org.umamo.format.cmo3.Cmo3
import org.umamo.interop.ExportNotice
import org.umamo.interop.ExportReport
import org.umamo.interop.moc3.Moc3Sidecars
import org.umamo.storage.FileKitFilePicker
import org.umamo.storage.UmamoLog
import org.umamo.storage.platformFileFromSavedPath
import org.umamo.ui.LocalSettings
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.Keymap
import org.umamo.ui.action.loadKeymap
import org.umamo.ui.document.Document
import org.umamo.ui.document.DocumentLoad
import org.umamo.ui.document.Moc3Document
import org.umamo.ui.document.Moc3ExportSessionOptions
import org.umamo.ui.document.PuppetDocument
import org.umamo.ui.document.addRecentFile
import org.umamo.ui.document.exportSuggestedName
import org.umamo.ui.document.exportedModelFor
import org.umamo.ui.document.loadDocument
import org.umamo.ui.document.prepareCmo3Export
import org.umamo.ui.document.prepareMoc3Export
import org.umamo.ui.document.recentFiles
import org.umamo.ui.document.writeMoc3Bundle
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
import org.umamo.ui.viewport.LiveParamsAdapter
import org.umamo.ui.viewport.PuppetViewportServiceFactory
import org.umamo.ui.viewport.rememberPuppetViewportHost
import org.umamo.ui.workspace.ExportOptionsRequest
import org.umamo.ui.workspace.INTERFACE_LAYOUT_KEY
import org.umamo.ui.workspace.PersistentEditorShell
import org.umamo.ui.workspace.commands.fileCommands
import org.umamo.ui.workspace.commands.fileExportCommands
import org.umamo.ui.workspace.commands.logCommands
import org.umamo.ui.workspace.commands.registerAll
import org.umamo.ui.workspace.decodeLayout
import org.umamo.ui.workspace.decodeLayoutText
import org.umamo.ui.workspace.decodeWorkspaceText
import org.umamo.ui.workspace.exportLayoutText
import org.umamo.ui.workspace.exportWorkspaceText
import kotlin.random.Random

/**
 * The one editing session per open puppet document (the undo history + dirty state live here),
 * recreated when the document swaps; null for no document. Owned at the host level so both the host's
 * own chrome (e.g. the desktop title's unsaved marker) and [EditorApp] share the one session.
 *
 * @param Document? document The open document, or null.
 * @return EditorSession? The document's session, or null with no puppet document open.
 */
@Composable
fun rememberEditorSessionFor(document: Document?): EditorSession? =
	remember(document) { (document as? PuppetDocument)?.let { EditorSession(it.puppet, it.liveParams.values) } }

/**
 * The shared editor shell: a custom in-window menu bar (File / Edit / Workspace / Help, drawn with the
 * kit menu system) over the document viewport. The Import/Export dialogs come from FileKit; [document] is
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
 * @param EditorSession? session The open document's editing session (non-null for a puppet document); drives
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
	// The MOC3 export dialog's session memory: sticky for the application's life, never persisted.
	// Held here rather than in the shell because it must survive document swaps (nothing in this
	// remember block is keyed on the document) and because the export closures below read it.
	val moc3ExportOptions = remember { Moc3ExportSessionOptions() }
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

	// Replacing the document discards its session - the undo history and any unexported edits go with
	// it - so a dirty document asks first.  The shell owns the confirm dialog (document.confirmReplace),
	// keeping its Escape/Enter routing with every other overlay.
	fun confirmIfDirty(proceed: () -> Unit) {
		if (session?.dirty?.value == true) {
			commandRegistry.invoke("document.confirmReplace", proceed)
		} else {
			proceed()
		}
	}

	fun openStoredPath(path: String) {
		confirmIfDirty {
			scope.launch {
				applyDocumentLoad(loadDocument(platformFileFromSavedPath(path)))
			}
		}
	}

	fun importCmo3ViaPicker() {
		// FileKit's native dialog supplies its own (OS-localized) title, so none is passed here.
		// The filter is CMO3-only: layered-art formats have no import path since :reimport is what
		// would bind them into a session, and that binding doesn't exist yet.  MOC3 comes in through
		// its own row (importMoc3ViaPicker), keeping the source-project / baked-runtime distinction
		// visible in the UI.  Open/Save is reserved for the native UMA format.
		confirmIfDirty {
			scope.launch {
				filePicker.openFile(listOf(FileKind.Cmo3.extension))?.let { picked ->
					applyDocumentLoad(loadDocument(picked))
				}
			}
		}
	}

	fun importMoc3ViaPicker() {
		// The picked .moc3 routes through loadDocument's file-level MOC3 branch, which discovers the
		// model3.json manifest, cdi3 display info, and atlas pages next to the file.
		confirmIfDirty {
			scope.launch {
				filePicker.openFile(listOf(FileKind.Moc3.extension))?.let { picked ->
					applyDocumentLoad(loadDocument(picked))
				}
			}
		}
	}

	// Surfaces an export's report: every notice to the log, and the report itself to the alert the
	// shell shows.  Both exports end this way - anything unrepresentable is stated, never dropped.
	fun reportExport(report: ExportReport) {
		for (notice in report.notices) {
			UmamoLog.warn("export: ${describeExportNotice(notice)}")
		}
		if (!report.isEmpty) {
			commandRegistry.invoke("document.exportReport", report)
		}
	}

	fun exportCmo3(puppetDocument: PuppetDocument) {
		scope.launch {
			val suggestedName = exportSuggestedName(puppetDocument.displayName)
			filePicker.saveFile(suggestedName, FileKind.Cmo3.extension)?.let { destination ->
				val prepared =
					prepareCmo3Export(
						document = puppetDocument,
						edited = exportedModelFor(puppetDocument, session),
						modelName = suggestedName,
						nowMillis = System.currentTimeMillis(),
						obfuscateKey = Random.nextInt(),
					)
				destination.write(Cmo3.write(prepared.model))
				// True export semantics: an export is not a save, so the dirty baseline stays put - the
				// modified marker clears only once UMA Save exists and owns markSaved.
				reportExport(prepared.report)
				UmamoLog.info("exported ${destination.absolutePath()}")
			}
		}
	}

	fun exportMoc3(puppetDocument: PuppetDocument) {
		// Options first, destination second: the choices do not depend on where the family lands, and
		// recording them on confirm - before the picker - keeps them sticky through a cancelled picker.
		val moc3Document = puppetDocument as? Moc3Document
		val seedModel = exportedModelFor(puppetDocument, session)
		commandRegistry.invoke(
			"document.exportOptionsMoc3",
			ExportOptionsRequest.Moc3(
				initial = moc3ExportOptions.dialogOptionsFor(puppetDocument.path, seedModel),
				physicsAvailable = moc3Document?.sidecars?.any { sidecar -> sidecar.kind == Moc3Sidecars.SidecarKind.Physics } == true,
				userDataAvailable = moc3Document?.sidecars?.any { sidecar -> sidecar.kind == Moc3Sidecars.SidecarKind.UserData } == true,
				canvasWidth = seedModel.canvasWidth,
				canvasHeight = seedModel.canvasHeight,
				onConfirm = { options ->
					moc3ExportOptions.recordConfirmed(puppetDocument.path, options)
					scope.launch {
						filePicker.saveFile(exportSuggestedName(puppetDocument.displayName), FileKind.Moc3.extension)?.let { destination ->
							val bundle =
								prepareMoc3Export(
									document = puppetDocument,
									// Re-resolved at write time: the dialog is modeless enough that the
									// session could undo between confirm and the picker closing.
									edited = exportedModelFor(puppetDocument, session),
									// FileKit appends the extension, so the picked handle's own name is authoritative.
									destinationName = destination.name,
									options = options,
								)
							val written = writeMoc3Bundle(destination, bundle)
							reportExport(bundle.report)
							UmamoLog.info("exported $written file(s) as ${destination.absolutePath()}")
						}
					}
				},
			),
		)
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

	fun exportLog() {
		// The retained UmamoLog buffer as plain text, one line per entry - the same lines the terminal
		// printed, for a user who launched without one.  Read at write time so the file captures the log
		// as of when the save is confirmed, not when the button was pressed.
		scope.launch {
			filePicker.saveFile("umamo-log", "txt")?.let { destination ->
				destination.writeString(UmamoLog.entries.value.joinToString("\n") { entry -> entry.message })
				UmamoLog.info("exported log to ${destination.absolutePath()}")
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

	// Register the file and log operations as real commands so the keymap and the palette drive them
	// (Ctrl+O dispatches through the shell's registry).  The tables themselves live with every other
	// command table in org.umamo.ui.workspace.commands; only the actions are supplied here, where the file
	// picker and document loader are.
	DisposableEffect(commandRegistry) {
		val cleanup =
			commandRegistry.registerAll(
				fileCommands({ importCmo3ViaPicker() }, { importMoc3ViaPicker() }) + logCommands { exportLog() },
			)
		onDispose { cleanup() }
	}
	// Keyed on the session as well as the document: the handler closes over BOTH, so re-registering
	// on either change keeps the pair the export reconciles from consistent by construction.
	DisposableEffect(commandRegistry, document, session) {
		// Both puppet document kinds export, to either format: Export CMO3 reconciles onto a CMO3-origin
		// document's retained graph and synthesizes a fresh one for a MOC3-origin document, while Export
		// MOC3 bakes fresh from the model whatever the origin.
		val exportableDocument = document as? PuppetDocument
		val cleanup =
			commandRegistry.registerAll(
				fileExportCommands(
					canExport = { exportableDocument != null },
					onExportCmo3 = { exportableDocument?.let { exportCmo3(it) } },
					onExportMoc3 = { exportableDocument?.let { exportMoc3(it) } },
				),
			)
		onDispose { cleanup() }
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
				::importCmo3ViaPicker,
				::importMoc3ViaPicker,
				::exportCmo3,
				::exportMoc3,
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
 * @param Document? document The open document (gates both Export rows).
 * @param List recentFiles The recent file paths for the Open Recent submenu.
 * @param Keymap keymap The keymap accelerators are resolved against.
 * @param Boolean canUndo Whether an undo step is available (gates the Edit menu's Undo row).
 * @param Boolean canRedo Whether a redo step is available (gates the Edit menu's Redo row).
 * @param Function openRecent Opens a recent file by its stored path.
 * @param Function importCmo3 Opens the CMO3 import picker.
 * @param Function importMoc3 Opens the MOC3 import picker.
 * @param Function exportCmo3 Exports the given puppet document via a picker (CMO3-origin
 *                            reconciles; MOC3-origin synthesizes a fresh graph).
 * @param Function exportMoc3 Exports the given puppet document's moc family via a picker.
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
	importCmo3: () -> Unit,
	importMoc3: () -> Unit,
	exportCmo3: (PuppetDocument) -> Unit,
	exportMoc3: (PuppetDocument) -> Unit,
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
			canExport = document is PuppetDocument,
			onImportCmo3 = importCmo3,
			onOpenRecent = openRecent,
			onImportMoc3 = importMoc3,
			onExportCmo3 = { (document as? PuppetDocument)?.let { exportCmo3(it) } },
			onExportMoc3 = { (document as? PuppetDocument)?.let { exportMoc3(it) } },
			onExit = onExit,
		),
		editMenu(keymap, canUndo, canRedo, onUndo, onRedo, onOpenPreferences),
		workspaceMenu(keymap, onNewWorkspace, onResetWorkspace, onImportWorkspace, onExportThisWorkspace, onExportAllWorkspaces),
		helpMenu(keymap, openInBrowser, onOpenCredits, onOpenAbout),
	)

/**
 * One log line for an export notice - the headless-visible mirror of the shell's report alert.
 *
 * Deliberately English and deliberately structural: the log is a diagnostic surface, read off a bug
 * report rather than by a rigger mid-edit, so it wants text that is stable across locales and greps
 * straight back to a call site.  Printing the reason itself gives that for free and, unlike a second
 * hand-written copy of the alert's prose, cannot drift from the case list it describes.
 *
 * @param ExportNotice notice The notice to describe.
 * @return String The log text.
 */
private fun describeExportNotice(notice: ExportNotice): String =
	when (notice) {
		is ExportNotice.UnsupportedChange ->
			if (notice.subject == null) {
				"[${notice.category}] ${notice.reason}"
			} else {
				"[${notice.category}] ${notice.subject}: ${notice.reason}"
			}
		is ExportNotice.WeldDivergence -> "weld divergence on ${notice.drawableNames.joinToString()}"
		is ExportNotice.FeatureStripped ->
			"${notice.feature} is not in the exported moc version; removed from " +
				notice.subjects.take(8).joinToString() +
				if (notice.subjects.size > 8) " (+${notice.subjects.size - 8} more)" else ""
		is ExportNotice.MissingSourceArt ->
			"no source artwork: the CMO3 was built around a stand-in document rebuilt from ${notice.pageCount} atlas page(s); " +
				"it will not render in the Cubism Editor until the original layered art is reconciled in"
	}

/**
 * Renders the open document inside the editor shell. For a puppet document (CMO3 or MOC3), a per-area
 * viewport host is injected (when the platform supplies a render-service factory) and the runtime
 * model + live params are provided to the panels; with no document, the shell shows placeholders.
 * With a null factory the model locals still mount - the outliner, parameters, and thumbnails all
 * work - only the viewport areas render placeholders. The shell's workspace layout + locale are
 * persisted via settings regardless of the open document.
 *
 * @param Document? document The open document, or null.
 * @param EditorSession? session The open document's editing session (non-null for a puppet document).
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
		is PuppetDocument ->
			key(document) {
				// The session is created per-document by the host and is non-null for a puppet document;
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
