package org.umamo.ui.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.serialization.json.JsonObject
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.EditorSession
import org.umamo.storage.FileKitFilePicker
import org.umamo.ui.LocalSettings
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.document.ArtDocument
import org.umamo.ui.document.Document
import org.umamo.ui.document.DocumentFile
import org.umamo.ui.document.ImageExportSessionOptions
import org.umamo.ui.document.Moc3ExportSessionOptions
import org.umamo.ui.document.PuppetDocument
import org.umamo.ui.document.UmaDocument
import org.umamo.ui.document.openDroppedFiles
import org.umamo.ui.kit.FileDropTarget
import org.umamo.ui.menu.buildAppMenu
import org.umamo.ui.model.SessionAtlasPages
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.title_untitled_document
import org.umamo.ui.settings.LocalQuickSetup
import org.umamo.ui.settings.QuickSetupState
import org.umamo.ui.viewport.PuppetViewportServiceFactory
import org.umamo.ui.workspace.AreaViewStates
import org.umamo.ui.workspace.EDITOR_STATE_AREAS
import org.umamo.ui.workspace.EDITOR_STATE_SESSION
import org.umamo.ui.workspace.LocalAreaViewStates
import org.umamo.ui.workspace.commands.fileCommands
import org.umamo.ui.workspace.commands.fileExportCommands
import org.umamo.ui.workspace.commands.registerAll
import org.umamo.ui.workspace.sessionViewStateOf

/**
 * The one editing session per open puppet document (the undo history + dirty state live here),
 * recreated when the document swaps; null for no document. Owned at the host level so both the host's
 * own chrome (e.g. the desktop title's unsaved marker) and [EditorApp] share the one session.
 *
 * A `.uma` opens on the session state it was saved with (docs/format/UMA.md § 7.4): the selection, the mode, the
 * cursors, and the tool settings are laid into the session's FIRST snapshot, so none of it is an undo step.  The
 * pose arrives the same way every document's does, through its live parameters.
 *
 * @param Document? document The open document, or null.
 * @return EditorSession? The document's session, or null with no puppet document open.
 */
@Composable
fun rememberEditorSessionFor(document: Document?): EditorSession? =
	remember(document) {
		(document as? PuppetDocument)?.let { puppetDocument ->
			val savedState = (puppetDocument as? UmaDocument)?.uma?.editorState?.get(EDITOR_STATE_SESSION) as? JsonObject
			EditorSession(puppetDocument.puppet, puppetDocument.liveParams.values, initialViewState = sessionViewStateOf(savedState))
		}
	}

/**
 * Where the open document saves - one [DocumentFile] per document, keyed on the same identity as the session
 * and owned at the host level for the same reason: the desktop title shows the `.uma` name the holder carries.
 *
 * @param Document? document The open document, or null.
 * @return DocumentFile? The document's save target, or null with no document.
 */
@Composable
fun rememberDocumentFileFor(document: Document?): DocumentFile? = remember(document) { document?.let { DocumentFile(it) } }

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
 * @param Document? document The open document, or null.
 * @param EditorSession? session The open document's editing session (non-null for a puppet document); drives
 *   undo/redo, the Edit-menu enabled state, and the saved marker.
 * @param DocumentFile? documentFile Where the document saves (the `.uma` path and the model the next save lays
 *   over), remembered by the host beside the session; null with no document.
 * @param Function onOpen Called with a newly-opened document.
 * @param Function onExit Closes the application; File > Exit runs it through the unsaved-changes guard.
 * @param ExitGuard exitGuard The host's route into the same guard, for the exits the host owns (the window's
 *   close button, the OS's quit, Android's back gesture); the shell installs its guard here while composed.
 * @param PuppetViewportServiceFactory? viewportServiceFactory Creates the platform render service, or
 *   null on a platform without a puppet renderer yet (viewport areas render placeholders).
 * @param HostOpenRequests? openRequests Files the operating system asks the running editor to open, or null for
 *   a host that receives none.
 */
@Composable
fun EditorApp(
	document: Document?,
	session: EditorSession?,
	documentFile: DocumentFile?,
	onOpen: (Document) -> Unit,
	onExit: () -> Unit,
	exitGuard: ExitGuard,
	viewportServiceFactory: PuppetViewportServiceFactory?,
	openRequests: HostOpenRequests? = null,
) {
	val settings = LocalSettings.current
	val scope = rememberCoroutineScope()
	val filePicker = remember { FileKitFilePicker() }
	val commandRegistry = remember { CommandRegistry() }
	// The MOC3 export dialog's session memory: sticky for the application's life, never persisted.
	// Held here rather than in the shell because it must survive document swaps (nothing in this
	// remember block is keyed on the document), which is also why it outlives the export controller.
	val moc3ExportOptions = remember { Moc3ExportSessionOptions() }
	// Export Image's session memory, held here for the same reason.
	val imageExportOptions = remember { ImageExportSessionOptions() }
	// Quick Setup opens on a first run - no user settings file when the app loaded - and is held here for the
	// same reason: a file opened while it is up swaps the document and rebuilds the shell, which must not close it.
	val quickSetup = remember { QuickSetupState(visible = !settings.foundUserFile) }
	// The session's effective atlas pages: a repack swaps them and undo swaps them back, driven by the
	// model through the resolver's collector.  Created up here rather than in the viewport wiring so
	// a save and an export read the same page set the viewport shows.
	val sessionAtlasPages =
		remember(document, session) {
			if (document is PuppetDocument && session != null) {
				SessionAtlasPages(session, document.puppet.atlas, document.textures, document.artRasters)
			} else {
				null
			}
		}
	LaunchedEffect(sessionAtlasPages) {
		sessionAtlasPages?.follow()
	}
	// An artwork import that could not carry everything as drawn says so once, on the status bar; the
	// detail is already in the log.  Keyed on the session as well, so the notice lands on the session
	// that owns the new document rather than the one being torn down.
	LaunchedEffect(document, session) {
		if (document is ArtDocument && session != null && document.importNotices.isNotEmpty()) {
			session.emitNotice("notice.import.artworkNotes")
		}
	}
	// Every area's view state for this document, seeded from the editor state the file was saved with (UMA §7.3).
	// Remembered here, beside the document read, so it is never paired with another document's areas.
	val areaViewStates = remember(document) { AreaViewStates((document as? UmaDocument)?.uma?.editorState?.get(EDITOR_STATE_AREAS) as? JsonObject) }
	// Where this document's render service is handed to a save's thumbnail and Export Image, filled by the
	// viewport wiring while the service lives.
	val viewportSlot = remember(document) { DocumentViewportSlot() }

	// Everything derived from the open document, as one value built beside the document read.  The
	// controllers made per document hold it; the ones that outlive documents read it through the holder
	// below.  They register their commands once (Ctrl+O and the palette dispatch through the registry) and
	// the exit guard installs once, so a controller that held a document would keep asking about whichever
	// one was open when it was made - none at all on a normal launch, which is a silent skip of the whole
	// unsaved-changes prompt.  The holder always reads the context of the composition that is live now.
	val context =
		remember(document, session, documentFile, sessionAtlasPages, areaViewStates, viewportSlot) {
			OpenDocumentContext(document, session, documentFile, sessionAtlasPages, areaViewStates, viewportSlot)
		}
	val currentContext by rememberUpdatedState(context)
	val currentOnOpen by rememberUpdatedState(onOpen)
	val currentOnExit by rememberUpdatedState(onExit)
	val currentUntitledName by rememberUpdatedState(stringResource(Res.string.title_untitled_document))
	val services =
		remember {
			EditorAppServices(
				settings = settings,
				scope = scope,
				filePicker = filePicker,
				commandRegistry = commandRegistry,
				current = { currentContext },
				onOpen = { opened -> currentOnOpen(opened) },
				untitledName = { currentUntitledName },
			)
		}
	val save = remember { DocumentSaveController(services) }
	val open = remember { DocumentOpenController(services, save) }

	// A file the operating system hands the running editor - a double-clicked document on macOS, a VIEW intent
	// on Android - opens the way a recent file does, unsaved-changes check included.
	LaunchedEffect(openRequests) {
		openRequests?.requests?.collect { requestedPath -> open.openStoredPath(requestedPath) }
	}

	// The document's artwork watcher with its whole life (see rememberDocumentWatch): it follows the
	// model's source list and the watch-mode setting, and lands its events on the session.
	val documentWatch = rememberDocumentWatch(document, session, scope, settings, commandRegistry)
	// The per-document controllers, remade with the context so each works from one consistent document,
	// session, and page set.  Artwork exists only for a puppet document; without one the shell hides its commands.
	val artwork = remember(context, documentWatch) { context.puppet?.let { puppet -> ArtworkController(services, puppet, documentWatch) } }
	val export = remember(context) { DocumentExportController(services, context.puppet, moc3ExportOptions) }
	// Export Image draws through the puppet renderer, so it exists only for a puppet document on a platform
	// that has one; without it the shell hides the command and the menu disables its row.
	val imageExport =
		remember(context) {
			context.puppet?.takeIf { viewportServiceFactory != null }?.let { puppet ->
				ImageExportController(services, puppet, context.viewport, imageExportOptions)
			}
		}
	val exportImage = remember(imageExport) { imageExport?.let { controller -> { viewportAreaId: String? -> controller.exportImage(viewportAreaId) } } }

	// The host's exits pass through the same guard as File > Exit.  Installed once per guard: the gate reads
	// the live document, so the closure's own age does not matter.
	DisposableEffect(exitGuard) {
		val cleanup = exitGuard.install { exit -> save.confirmExit(exit) }
		onDispose { cleanup() }
	}

	// Register the document operations as real commands so the keymap and the palette drive them (Ctrl+O
	// dispatches through the shell's registry).  The tables themselves live with every other command table
	// in org.umamo.ui.workspace.commands; only the actions are supplied here, where the document loader is.
	DisposableEffect(commandRegistry) {
		val cleanup =
			commandRegistry.registerAll(
				fileCommands(
					onNew = { open.newDocument() },
					onOpen = { open.openViaPicker() },
					onSave = { save.save(saveAs = false) },
					onSaveAs = { save.save(saveAs = true) },
					canSave = { save.canSaveNow() },
					onImportCmo3 = { open.importCmo3ViaPicker() },
					onImportMoc3 = { open.importMoc3ViaPicker() },
					onOpenPath = { path -> open.openStoredPath(path) },
					onExit = { save.confirmExit { currentOnExit() } },
				),
			)
		onDispose { cleanup() }
	}
	// Keyed on the export controller, which is remade with the document and the session: the handlers
	// always reach the pair the export reconciles from, consistent by construction.
	DisposableEffect(commandRegistry, export) {
		val cleanup =
			commandRegistry.registerAll(
				fileExportCommands(
					canExport = { export.canExport },
					onExportCmo3 = { export.exportCmo3() },
					onExportMoc3 = { export.exportMoc3() },
				),
			)
		onDispose { cleanup() }
	}

	// The menu bar, kept live by its own assembly (keymap, recent files, undo state, locale).  Every row
	// stands for a command, so the registry is all it is handed.
	val appMenu =
		buildAppMenu(
			settings = settings,
			session = session,
			canSave = save.canSaveNow(),
			canExport = export.canExport,
			canExportImage = imageExport != null,
			dispatch = { commandId, argument -> commandRegistry.invoke(commandId, argument) },
		)
	// A file dropped on the window takes the same way in as one chosen from a dialog: a document replaces
	// what is open (through the unsaved-changes gate file.openPath carries), artwork is added to it.  Both
	// go through the registry rather than straight to a controller - that is what gives the add the hovered
	// area its operation strip shows in, and what keeps a drop under the same availability gate as the menu.
	FileDropTarget(onDrop = { paths -> openDroppedFiles(paths, commandRegistry) }) {
		CompositionLocalProvider(LocalAreaViewStates provides areaViewStates, LocalQuickSetup provides quickSetup) {
			DocumentViewport(
				document = document,
				session = session,
				sessionAtlasPages = sessionAtlasPages,
				commandRegistry = commandRegistry,
				appMenu = appMenu,
				viewportServiceFactory = viewportServiceFactory,
				filePicker = filePicker,
				artwork = artwork?.operations,
				sourceWatch = documentWatch?.state,
				sourceSuggestions = artwork?.suggestionState,
				viewportSlot = viewportSlot,
				exportImage = exportImage,
			)
		}
	}
}