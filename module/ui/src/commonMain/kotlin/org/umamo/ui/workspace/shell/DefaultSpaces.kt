package org.umamo.ui.workspace.shell

import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.workspace.LocalViewportHost
import org.umamo.ui.workspace.SpaceDescriptor
import org.umamo.ui.workspace.SpaceKind
import org.umamo.ui.workspace.SpaceRegistry
import org.umamo.ui.workspace.spaces.PlaceholderSpace
import org.umamo.ui.workspace.spaces.history.HistorySpace
import org.umamo.ui.workspace.spaces.keyformsheet.KeyformSheetSpace
import org.umamo.ui.workspace.spaces.keyformsheet.keyformSheetHeaderControls
import org.umamo.ui.workspace.spaces.logs.LogsSpace
import org.umamo.ui.workspace.spaces.logs.logsHeaderControls
import org.umamo.ui.workspace.spaces.outliner.OutlinerSpace
import org.umamo.ui.workspace.spaces.outliner.outlinerHeaderControls
import org.umamo.ui.workspace.spaces.parameters.ParametersSpace
import org.umamo.ui.workspace.spaces.parameters.parametersHeaderControls
import org.umamo.ui.workspace.spaces.properties.PropertiesSpace
import org.umamo.ui.workspace.spaces.properties.propertiesHeaderControls
import org.umamo.ui.workspace.spaces.sources.SourcesSpace
import org.umamo.ui.workspace.spaces.sources.sourcesHeaderControls
import org.umamo.ui.workspace.spaces.uv.UvEditorSpace
import org.umamo.ui.workspace.spaces.uv.uvEditorHeaderControls
import org.umamo.ui.workspace.spaces.viewport2d.Viewport2DBody
import org.umamo.ui.workspace.spaces.viewport2d.viewport2DHeaderControls

/**
 * Builds the base [SpaceRegistry] every shell starts from: a descriptor for each [SpaceKind]. The 2D
 * viewport delegates to the host-injected [LocalViewportHost] (a plain backdrop when absent); every other
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
					headerContent = { scope -> viewport2DHeaderControls(scope) },
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