package org.umamo.ui.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import org.umamo.edit.EditorSession
import org.umamo.storage.FilePicker
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.document.Document
import org.umamo.ui.document.PuppetDocument
import org.umamo.ui.document.systemSourceFilePresence
import org.umamo.ui.kit.TopLevelMenu
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
import org.umamo.ui.model.LocalSessionAtlasPages
import org.umamo.ui.model.LocalSourceArtRasters
import org.umamo.ui.model.LocalSourceFilePresence
import org.umamo.ui.model.LocalSourceSuggestions
import org.umamo.ui.model.LocalSourceWatch
import org.umamo.ui.model.SessionAtlasPages
import org.umamo.ui.model.SourceSuggestionState
import org.umamo.ui.model.SourceWatchState
import org.umamo.ui.model.rememberSessionEditorState
import org.umamo.ui.rememberIntSetting
import org.umamo.ui.settings.HistorySettings
import org.umamo.ui.viewport.AtlasPageBinding
import org.umamo.ui.viewport.LiveParamsAdapter
import org.umamo.ui.viewport.PuppetViewportServiceFactory
import org.umamo.ui.viewport.rememberPuppetViewportHost
import org.umamo.ui.workspace.LocalAreaViewStates
import org.umamo.ui.workspace.PersistentEditorShell
import org.umamo.ui.workspace.commands.ArtworkOperations

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
 * @param SessionAtlasPages? sessionAtlasPages The session's resolved atlas page set (repack/undo
 *   aware); null with no puppet document, in which case the document's own decoded textures are used.
 * @param CommandRegistry commandRegistry The registry the file commands are registered in (drives the keymap).
 * @param List appMenu The menu-bar contents, mounted by each shell.
 * @param PuppetViewportServiceFactory? viewportServiceFactory Creates the platform render service, or null.
 * @param FilePicker filePicker The app's native dialogs, forwarded to the shell for its own file commands.
 * @param ArtworkOperations? artwork The app's artwork orchestrations over the hovered area, handed to
 *   the shell for a puppet document only (the shell registers the commands; see fileArtworkCommands); null
 *   with no puppet document, which hides them.
 * @param SourceWatchState? sourceWatch The document's artwork watcher's state for the Sources space, or null.
 * @param SourceSuggestionState? sourceSuggestions The published relink suggestions for the Sources space's review chips, or null.
 */
@Composable
internal fun DocumentViewport(
	document: Document?,
	session: EditorSession?,
	sessionAtlasPages: SessionAtlasPages?,
	commandRegistry: CommandRegistry,
	appMenu: List<TopLevelMenu>,
	viewportServiceFactory: PuppetViewportServiceFactory?,
	filePicker: FilePicker,
	artwork: ArtworkOperations?,
	sourceWatch: SourceWatchState?,
	sourceSuggestions: SourceSuggestionState?,
) {
	when (document) {
		is PuppetDocument ->
			key(document) {
				// The session is created per-document by the host and is non-null for a puppet document;
				// the fallback only guards a desync. Panels read LocalPuppet (a live projection of the session
				// model) and drive edits through LocalEditorSession / the session-backed selection handle.
				val activeSession = session ?: remember(document) { EditorSession(document.puppet, document.liveParams.values) }
				// The undo cap is a preference, not a construction-time constant, so it is applied here rather
				// than passed to the constructor: the desktop host builds the session outside the settings
				// provider, and a committed change has to reach the stack of the document already open. Keying
				// the session's own remember on the value would instead discard the stack on every change.
				val historyLimit by rememberIntSetting(HistorySettings.HISTORY_LIMIT_KEY, HistorySettings.HISTORY_LIMIT_DEFAULT)
				LaunchedEffect(activeSession, historyLimit) {
					activeSession.historyLimit = historyLimit
				}
				val editorState = rememberSessionEditorState(activeSession)
				// The session's resolved page set - the pixels the model's atlas value denotes.  The
				// fallback pair only guards the same desync the session fallback above does.
				val atlasPages =
					sessionAtlasPages?.binding?.value
						?: remember(document) { AtlasPageBinding(document.puppet.atlas, document.textures) }
				// The factory is fixed for the app's lifetime (a platform capability, not state), so the
				// conditional composable call is stable across recompositions.
				val areaViewStates = LocalAreaViewStates.current
				val viewport =
					if (viewportServiceFactory != null) {
						rememberPuppetViewportHost(
							document.puppet,
							atlasPages,
							document.artRasters,
							document.liveParams,
							activeSession,
							viewportServiceFactory,
							// Each area reopens on the view it was saved with (UMA §7.3); read once, as the service is built.
							initialCameras = remember(areaViewStates) { areaViewStates?.restoredCameras().orEmpty() },
						)
					} else {
						null
					}
				// A save reads every area's camera through the holder, which is where both sides already meet by
				// area id; the reader goes when the service does, so a save never asks a disposed engine.
				val viewportService = viewport?.service
				DisposableEffect(areaViewStates, viewportService) {
					val reader = viewportService?.let { service -> service::cameras }
					areaViewStates?.cameraReader = reader
					onDispose {
						if (areaViewStates?.cameraReader === reader) {
							areaViewStates?.cameraReader = null
						}
					}
				}
				val liveParamsHandle = remember(document, activeSession) { LiveParamsAdapter(document.liveParams, activeSession) }
				// Without a viewport the thumbnails come straight from the shared thumbnailer, so the
				// outliner's hover previews work before a platform puppet renderer exists.  Keyed on the
				// page set: a repack stales every crop, and the live path gets the same eviction through
				// the picker's setTextures.
				val thumbnails =
					viewport?.thumbnails
						?: remember(document, atlasPages) { DrawableThumbnailer(document.puppet, atlasPages.textures) }
				val model by activeSession.model.collectAsState()
				CompositionLocalProvider(
					LocalPuppet provides model,
					LocalEditorSession provides activeSession,
					LocalLiveParams provides liveParamsHandle,
					LocalDrawableThumbnails provides thumbnails,
					LocalPuppetTextures provides atlasPages.textures,
					LocalSessionAtlasPages provides sessionAtlasPages,
					LocalSourceArtRasters provides document.artRasters,
					LocalSourceFilePresence provides systemSourceFilePresence,
					LocalSourceWatch provides sourceWatch,
					LocalSourceSuggestions provides sourceSuggestions,
					LocalPuppetRenderSync provides viewport?.renderSync,
					LocalPuppetViewportService provides viewport?.service,
					LocalSelection provides editorState,
					LocalEditorMode provides editorState,
				) {
					PersistentEditorShell(
						viewportHost = viewport?.host,
						commandRegistry = commandRegistry,
						appMenu = appMenu,
						// Registered by the shell (see fileArtworkCommands): the strip shows in the hovered work surface.
						artwork = artwork,
						filePicker = filePicker,
					)
				}
			}
		null ->
			// No document open: the shell renders with placeholder viewport areas (no host injected).
			PersistentEditorShell(commandRegistry = commandRegistry, appMenu = appMenu, filePicker = filePicker)
	}
}