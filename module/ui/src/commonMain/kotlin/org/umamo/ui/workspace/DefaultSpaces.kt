package org.umamo.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.model.LocalPuppetViewportService
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.viewport.ViewportSpaceCamera
import org.umamo.ui.workspace.spaces.HistorySpace
import org.umamo.ui.workspace.spaces.KeyformSheetSpace
import org.umamo.ui.workspace.spaces.LogsSpace
import org.umamo.ui.workspace.spaces.OutlinerSpace
import org.umamo.ui.workspace.spaces.ParametersSpace
import org.umamo.ui.workspace.spaces.PlaceholderSpace
import org.umamo.ui.workspace.spaces.PropertiesSpace
import org.umamo.ui.workspace.spaces.SourcesSpace
import org.umamo.ui.workspace.spaces.UvEditorSpace
import org.umamo.ui.workspace.spaces.ViewportSidebarDrawer
import org.umamo.ui.workspace.spaces.ViewportToolbarOverlay
import org.umamo.ui.workspace.spaces.keyformSheetHeaderControls
import org.umamo.ui.workspace.spaces.logsHeaderControls
import org.umamo.ui.workspace.spaces.outlinerHeaderControls
import org.umamo.ui.workspace.spaces.parametersHeaderControls
import org.umamo.ui.workspace.spaces.propertiesHeaderControls
import org.umamo.ui.workspace.spaces.sourcesHeaderControls
import org.umamo.ui.workspace.spaces.uvEditorHeaderControls
import org.umamo.ui.workspace.spaces.viewport2DHeaderControls

/**
 * Builds the base [SpaceRegistry] every shell starts from: a descriptor for each [SpaceKind]. The 2D
 * viewport delegates to the host-injected [LocalViewportHost] (placeholder when absent); every other
 * space but [SpaceKind.ToolDetails] has a real body wired directly here (UvEditor, Outliner, Parameters,
 * KeyformSheet, Properties, History, Logs) - only ToolDetails still renders a [PlaceholderSpace] until
 * its own panel lands. The app layers additional overrides on with [SpaceRegistry.withOverrides].
 *
 * @return SpaceRegistry The base registry covering every SpaceKind.
 */
fun defaultSpaceRegistry(): SpaceRegistry {
	val descriptors =
		mapOf(
			SpaceKind.Viewport2D to
				SpaceDescriptor(
					SpaceKind.Viewport2D,
					Res.string.space_viewport2d,
					LocalUmamoIcons.spaceViewport,
					headerContent = { viewport2DHeaderControls() },
				) { scope -> Viewport2DBody(scope) },
			SpaceKind.UvEditor to
				SpaceDescriptor(
					SpaceKind.UvEditor,
					Res.string.space_uv,
					LocalUmamoIcons.spaceTexture,
					headerContent = { scope -> uvEditorHeaderControls(scope) },
				) { scope -> UvEditorSpace(scope) },
			SpaceKind.Sources to
				SpaceDescriptor(
					SpaceKind.Sources,
					Res.string.space_sources,
					LocalUmamoIcons.sources,
					headerContent = { scope -> sourcesHeaderControls(scope) },
				) { scope -> SourcesSpace(scope) },
			SpaceKind.Outliner to
				SpaceDescriptor(
					SpaceKind.Outliner,
					Res.string.space_outliner,
					LocalUmamoIcons.spaceOutliner,
					headerContent = { scope -> outlinerHeaderControls(scope) },
				) { scope -> OutlinerSpace(scope) },
			SpaceKind.Parameters to
				SpaceDescriptor(
					SpaceKind.Parameters,
					Res.string.space_parameters,
					LocalUmamoIcons.spaceParameters,
					headerContent = { scope -> parametersHeaderControls(scope) },
				) { scope -> ParametersSpace(scope) },
			SpaceKind.KeyformSheet to
				SpaceDescriptor(
					SpaceKind.KeyformSheet,
					Res.string.space_keyformsheet,
					LocalUmamoIcons.spaceKeyformSheet,
					headerContent = { scope -> keyformSheetHeaderControls(scope) },
				) { scope -> KeyformSheetSpace(scope) },
			SpaceKind.Properties to
				SpaceDescriptor(
					SpaceKind.Properties,
					Res.string.space_properties,
					LocalUmamoIcons.spaceProperties,
					headerContent = { scope -> propertiesHeaderControls(scope) },
				) { scope -> PropertiesSpace(scope) },
			SpaceKind.ToolDetails to
				SpaceDescriptor(
					SpaceKind.ToolDetails,
					Res.string.space_tooldetails,
					LocalUmamoIcons.spaceTool,
				) { PlaceholderSpace(stringResource(Res.string.space_tooldetails)) },
			SpaceKind.History to
				SpaceDescriptor(
					SpaceKind.History,
					Res.string.space_history,
					LocalUmamoIcons.spaceHistory,
				) { HistorySpace() },
			SpaceKind.Logs to
				SpaceDescriptor(
					SpaceKind.Logs,
					Res.string.space_logs,
					LocalUmamoIcons.logsTerminal,
					headerContent = { logsHeaderControls() },
				) { LogsSpace() },
		)
	return SpaceRegistry(descriptors)
}

/**
 * The 2D viewport body: the injected [LocalViewportHost]'s rendered surface, with the floating chrome (the
 * left tool toolbar and the right sidebar drawer) overlaid.
 *
 * The grid, the axes, and the canvas are the renderer's, drawn through its camera, for an empty document
 * as for a full one - the editor always has a document, so there is no state in which this would stand
 * in for them with a drawing of its own.  The one case with no host is a platform that has no puppet
 * renderer yet (Android until its GLES service lands); there the area is the plain viewport backdrop
 * color and nothing else, because a painted grid that cannot pan or zoom would promise a work surface
 * that is not there.
 *
 * @param AreaScope scope The hosting area context (its id keys the host's GL surface).
 */
@Composable
private fun Viewport2DBody(scope: AreaScope) {
	val host = LocalViewportHost.current
	val chrome = LocalViewportChrome.current
	// Register this viewport area's camera controller for its lifetime, into the same per-area hub the UV
	// editor registers into, so the shell's view commands (Fit / 1:1 / zoom / Frame Selected) resolve THIS
	// area when the pointer last touched it.  Only when a render service and session exist - with no
	// viewport nothing registers, matching the hidden view commands.
	val service = LocalPuppetViewportService.current
	val session = LocalEditorSession.current
	val areaCameraHub = LocalAreaCameraHub.current
	DisposableEffect(scope.areaId, service, session, areaCameraHub) {
		if (service != null && session != null && areaCameraHub != null) {
			areaCameraHub.register(scope.areaId, ViewportSpaceCamera(service, session, scope.areaId))
			onDispose { areaCameraHub.unregister(scope.areaId) }
		} else {
			onDispose { }
		}
	}
	Box(modifier = Modifier.fillMaxSize()) {
		if (host != null) {
			host.Viewport2D(scope.areaId, Modifier.fillMaxSize())
		} else {
			Box(modifier = Modifier.fillMaxSize().background(LocalUmamoColors.current.viewportGridBackground))
		}
		if (chrome.showToolbar) {
			ViewportToolbarOverlay(
				modifier =
					Modifier
						.align(Alignment.CenterStart)
						.padding(start = 6.dp),
			)
		}
		ViewportSidebarDrawer(modifier = Modifier.align(Alignment.CenterEnd))
	}
}