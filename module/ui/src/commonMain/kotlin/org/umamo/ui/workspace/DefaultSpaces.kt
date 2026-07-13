package org.umamo.ui.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.workspace.spaces.EmptyViewportBackdrop
import org.umamo.ui.workspace.spaces.HistorySpace
import org.umamo.ui.workspace.spaces.InspectorSpace
import org.umamo.ui.workspace.spaces.OutlinerHeaderControls
import org.umamo.ui.workspace.spaces.OutlinerSpace
import org.umamo.ui.workspace.spaces.ParametersHeaderControls
import org.umamo.ui.workspace.spaces.ParametersSpace
import org.umamo.ui.workspace.spaces.PlaceholderSpace
import org.umamo.ui.workspace.spaces.UvEditorHeaderControls
import org.umamo.ui.workspace.spaces.UvEditorSpace
import org.umamo.ui.workspace.spaces.Viewport2DHeaderControls
import org.umamo.ui.workspace.spaces.ViewportSidebarDrawer
import org.umamo.ui.workspace.spaces.ViewportToolbarOverlay

/**
 * Builds the base [SpaceRegistry] every shell starts from: a descriptor for each [SpaceKind]. The 2D
 * viewport delegates to the host-injected [LocalViewportHost] (placeholder when absent); the others
 * are placeholders here and get real bodies as the panels land (Outliner/Parameters/Inspector in the
 * read-only-panels step, via withOverrides or by replacing these factories). The app layers its
 * overrides on with [SpaceRegistry.withOverrides].
 *
 * すべてのシェルの土台となる SpaceRegistry を構築する。2D ビューポートはホスト注入に委譲し、他は
 * 当面プレースホルダ。
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
					headerContent = { Viewport2DHeaderControls() },
				) { scope -> Viewport2DBody(scope) },
			SpaceKind.UvEditor to
				SpaceDescriptor(
					SpaceKind.UvEditor,
					Res.string.space_uv,
					LocalUmamoIcons.spaceTexture,
					headerContent = { UvEditorHeaderControls() },
				) { scope -> UvEditorSpace(scope) },
			SpaceKind.Outliner to
				SpaceDescriptor(
					SpaceKind.Outliner,
					Res.string.space_outliner,
					LocalUmamoIcons.spaceOutliner,
					headerContent = { scope -> OutlinerHeaderControls(scope) },
				) { scope -> OutlinerSpace(scope) },
			SpaceKind.Parameters to
				SpaceDescriptor(
					SpaceKind.Parameters,
					Res.string.space_parameters,
					LocalUmamoIcons.spaceParameters,
					headerContent = { scope -> ParametersHeaderControls(scope) },
				) { scope -> ParametersSpace(scope) },
			SpaceKind.Inspector to
				SpaceDescriptor(SpaceKind.Inspector, Res.string.space_inspector, LocalUmamoIcons.spaceInspector) { InspectorSpace() },
			SpaceKind.ToolDetails to
				SpaceDescriptor(SpaceKind.ToolDetails, Res.string.space_tooldetails, LocalUmamoIcons.spaceTool) { PlaceholderSpace(stringResource(Res.string.space_tooldetails)) },
			SpaceKind.History to
				SpaceDescriptor(SpaceKind.History, Res.string.space_history, LocalUmamoIcons.spaceHistory) { HistorySpace() },
		)
	return SpaceRegistry(descriptors)
}

/**
 * The 2D viewport body: delegates to the injected [LocalViewportHost] when one is available, else
 * shows the themed grid backdrop - with the floating chrome (the left tool toolbar and the
 * right sidebar drawer) overlaid either way, so the work surface reads the same before a document
 * opens (the toolbar renders disabled without a session).
 *
 * 2D ビューポートの本体。注入されたホストがあれば委譲、無ければチェッカー背景。左ツールバーと右
 * サイドバーをその上に重ねる。
 *
 * @param AreaScope scope The hosting area context (its id keys the host's GL surface).
 */
@Composable
private fun Viewport2DBody(scope: AreaScope) {
	val host = LocalViewportHost.current
	val chrome = LocalViewportChrome.current
	Box(modifier = Modifier.fillMaxSize()) {
		if (host != null) {
			host.Viewport2D(scope.areaId, Modifier.fillMaxSize())
		} else {
			EmptyViewportBackdrop()
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
