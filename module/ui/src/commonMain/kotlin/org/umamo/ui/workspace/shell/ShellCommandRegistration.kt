package org.umamo.ui.workspace.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalUriHandler
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.help.openLinkQuietly
import org.umamo.ui.model.LocalEditorMode
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.model.LocalPuppetTextures
import org.umamo.ui.model.LocalPuppetViewportService
import org.umamo.ui.model.LocalSelection
import org.umamo.ui.model.LocalSessionAtlasPages
import org.umamo.ui.model.LocalSourceArtRasters
import org.umamo.ui.model.repack.atlasRepackLauncher
import org.umamo.ui.workspace.KeyformHover
import org.umamo.ui.workspace.commands.ArtworkOperations
import org.umamo.ui.workspace.commands.SessionAvailability
import org.umamo.ui.workspace.commands.atlasCommands
import org.umamo.ui.workspace.commands.chromeCommands
import org.umamo.ui.workspace.commands.debugCommands
import org.umamo.ui.workspace.commands.displayCommands
import org.umamo.ui.workspace.commands.documentCommands
import org.umamo.ui.workspace.commands.fileArtworkCommands
import org.umamo.ui.workspace.commands.fileImageExportCommands
import org.umamo.ui.workspace.commands.frameCommands
import org.umamo.ui.workspace.commands.historyCommands
import org.umamo.ui.workspace.commands.keyformCommands
import org.umamo.ui.workspace.commands.modeCommands
import org.umamo.ui.workspace.commands.objectCommands
import org.umamo.ui.workspace.commands.overlayCommands
import org.umamo.ui.workspace.commands.proportionalCommands
import org.umamo.ui.workspace.commands.registerAll
import org.umamo.ui.workspace.commands.selectCommands
import org.umamo.ui.workspace.commands.snapCommands
import org.umamo.ui.workspace.commands.topologyCommands
import org.umamo.ui.workspace.commands.transformCommands
import org.umamo.ui.workspace.commands.uvCommands
import org.umamo.ui.workspace.commands.viewCommands
import org.umamo.ui.workspace.commands.workspaceCommands

/**
 * Registers the shell's own command tables (the commands package) into [commandRegistry], one effect per
 * group, for as long as the shell is composed.
 *
 * Each group's effect keys on the state its handlers close over, and registerAll returns the matching
 * cleanup so registration and unregistration can never drift apart.  The effects start in the order they
 * are declared here, and that is the order the palette lists the commands in.  The shell calls this
 * outside its locale key, so a language switch re-registers nothing.
 *
 * @param CommandRegistry    commandRegistry The registry to register into.
 * @param ShellControllers   controllers     The shell's controllers the tables close over.
 * @param ArtworkOperations? artwork         The app's artwork orchestrations over the hovered area, or null
 *   when no open document can take artwork.
 * @param Function?          exportImage     Export Image over the 2D viewport area the routing resolves, or
 *   null when nothing can be captured.
 */
@Composable
internal fun RegisterShellCommands(
	commandRegistry: CommandRegistry,
	controllers: ShellControllers,
	artwork: ArtworkOperations?,
	exportImage: ((viewportAreaId: String?) -> Unit)?,
) {
	val workspaces = controllers.workspaces
	val overlays = controllers.overlays
	val dragController = controllers.dragController
	val splitterDragCancel = controllers.splitterDragCancel
	val rowDragCancel = controllers.rowDragCancel
	val hoveredSurfaces = controllers.hoveredSurfaces
	val keyableHover = controllers.keyableHover
	val keyformSheetViews = controllers.keyformSheetViews
	val areaCameras = controllers.areaCameras
	val areaOverlays = controllers.areaOverlays
	val repackOptions = controllers.repackOptions
	val operationStrip = controllers.operationStrip
	// Read at dispatch: the command table registers once per session, and the app hands in a fresh
	// collaborator per composition.
	val currentArtwork by rememberUpdatedState(artwork)
	val currentExportImage by rememberUpdatedState(exportImage)

	// ONE routing seam serves every group, held by the controllers for the shell's lifetime (the Properties
	// panel's transform rows reach its strip-area answer through LocalOperationStripArea), so it cannot go
	// stale across a document swap and the groups that must NOT re-register on one can hold it safely.
	val service = LocalPuppetViewportService.current
	val routing = controllers.routing
	// Read at dispatch: the chrome table registers once, and the handler the platform provides is the
	// composition's to change.
	val currentUriHandler by rememberUpdatedState(LocalUriHandler.current)
	DisposableEffect(commandRegistry, dragController) {
		val cleanup =
			commandRegistry.registerAll(
				chromeCommands(overlays, dragController, splitterDragCancel, rowDragCancel, workspaces) { url -> currentUriHandler.openLinkQuietly(url) },
			)
		onDispose { cleanup() }
	}
	DisposableEffect(commandRegistry) {
		val cleanup =
			commandRegistry.registerAll(
				workspaceCommands(workspaces, overlays) { controllers.newWorkspaceBaseName } + documentCommands(overlays),
			)
		onDispose { cleanup() }
	}
	// Viewport navigation and overlay commands dispatch to the hovered surface at invocation time: the
	// hovered area's camera controller or overlay state through its hub (2D viewport or UV editor), a
	// no-op when none is registered. Re-registered when the render service changes (a new document /
	// renderer), which flips the availability gate.
	DisposableEffect(commandRegistry, service) {
		val cleanup =
			commandRegistry.registerAll(
				viewCommands(areaCameras, routing, service != null) + overlayCommands(areaOverlays, routing, service != null) + debugCommands { service },
			)
		onDispose { cleanup() }
	}
	// Frame All resolves the hovered editor to the command that editor means and re-dispatches THAT, so
	// it keys on the registry alone: it carries no viewport gate (a keyform sheet frames with no renderer
	// at all), and re-registering it on a renderer change would only shuffle its palette position.
	DisposableEffect(commandRegistry) {
		val cleanup = commandRegistry.registerAll(frameCommands(commandRegistry, routing))
		onDispose { cleanup() }
	}
	val selection = LocalSelection.current
	val editorMode = LocalEditorMode.current
	DisposableEffect(commandRegistry, selection, editorMode) {
		val cleanup = commandRegistry.registerAll(modeCommands(selection, editorMode))
		onDispose { cleanup() }
	}
	// The document-scoped groups, re-registered on a document swap so their handlers close over the
	// current session.  They do not key on the render service: the pointer's area resolves through the
	// shared routing seam, which reads it live, so a renderer change has nothing to re-register here.
	val editorSession = LocalEditorSession.current
	// One tier set shared by every document-scoped group below, so the tables hold the same availability
	// objects rather than copies apiece.  Keyed on the session exactly as their effects are.
	val availability = remember(editorSession) { SessionAvailability(editorSession) }
	// The repack orchestration the atlas table dispatches: decode + pack off-thread, one commit, the
	// refusal report routed through the shell's own modal chrome.  Every collaborator here changes
	// with the document - and so with the session the effect below keys on - so the closure never
	// outlives what it captured.
	val shellScope = rememberCoroutineScope()
	val artRasters = LocalSourceArtRasters.current
	val sessionAtlasPages = LocalSessionAtlasPages.current
	val puppetTextures = LocalPuppetTextures.current
	val repackAtlas: ((String?) -> Unit)? =
		if (editorSession != null && artRasters != null) {
			atlasRepackLauncher(
				session = editorSession,
				artRasters = artRasters,
				sessionAtlasPages = sessionAtlasPages,
				premultipliedAlpha = puppetTextures?.premultipliedAlpha ?: false,
				scope = shellScope,
				sessionOptions = repackOptions,
				report = { refusalReport -> commandRegistry.invoke("document.repackReport", refusalReport) },
			)
		} else {
			null
		}
	DisposableEffect(commandRegistry, editorSession, selection) {
		val cleanup =
			commandRegistry.registerAll(
				historyCommands(editorSession, availability, operationStrip) +
					objectCommands(editorSession, selection, availability) +
					transformCommands(editorSession, routing, availability) +
					selectCommands(editorSession, routing, keyformSheetViews, availability) +
					snapCommands(editorSession, routing, availability, areaOverlays) +
					uvCommands(editorSession, routing, availability) +
					topologyCommands(editorSession, routing, availability) +
					proportionalCommands(editorSession, availability) +
					displayCommands(editorSession, availability) +
					atlasCommands(availability, routing, repackAtlas) +
					fileArtworkCommands(routing) { currentArtwork } +
					fileImageExportCommands(routing) { currentExportImage },
			)
		onDispose { cleanup() }
	}
	// The keyform-authoring group, its own table because it closes over the hovered KEYABLE rather than
	// the session alone - the property the insert / delete write to, resolved at dispatch time.
	DisposableEffect(commandRegistry, editorSession) {
		val hoveredKeyable: () -> KeyformHover? = { keyableHover.hovered }
		val cleanup =
			commandRegistry.registerAll(
				keyformCommands(editorSession, hoveredKeyable, routing, keyformSheetViews, availability),
			)
		onDispose { cleanup() }
	}
}