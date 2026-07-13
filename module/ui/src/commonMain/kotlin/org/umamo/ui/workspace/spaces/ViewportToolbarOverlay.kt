package org.umamo.ui.workspace.spaces

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.ActiveSelectTool
import org.umamo.edit.MeshOperatorKind
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.kit.button.IconButton
import org.umamo.ui.kit.button.IconButtonAppearance
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.UmamoIcon

/** The square size of one toolbar button (generous for pen and touch). */
private val TOOLBAR_BUTTON_SIZE = 28.dp

/** The glyph size inside a toolbar button. */
private val TOOLBAR_GLYPH_SIZE = 18.dp

/**
 * The viewport's floating left tool toolbar (Blender's toolbar, reduced to a strip): the select tools
 * (Box, Circle) and the modal transforms (Grab, Rotate, Scale), each dispatching its registry command
 * so the buttons, shortcuts, pies, and palette all share one behavior.  The armed / in-flight state
 * lights the matching button.  With no open document the strip still renders, disabled, so the viewport
 * chrome reads the same before the first file opens.
 *
 * Visibility is [org.umamo.ui.workspace.LocalViewportChrome] (settings-backed, toggled by
 * view.toggleToolbar / T) - the caller gates on it, keeping this composable a pure strip.
 *
 * ビューポート左側の浮遊ツールバー。選択ツールとモーダル変形をコマンド経由で発行し、動作中のツールを
 * 点灯表示する。ドキュメント未オープン時は無効表示。
 *
 * @param Modifier modifier The layout modifier (the caller aligns it to the viewport's left edge).
 */
@Composable
internal fun ViewportToolbarOverlay(modifier: Modifier = Modifier) {
	val session = LocalEditorSession.current
	val colors = LocalUmamoColors.current
	val shapes = LocalUmamoShapes.current
	val enabled = session != null
	val selectTool = session?.activeSelectTool?.collectAsState()?.value
	val meshOperator = session?.activeMeshOperator?.collectAsState()?.value
	val objectOperator = session?.activeObjectOperator?.collectAsState()?.value
	val liveOperator = meshOperator ?: objectOperator
	Column(
		modifier =
			modifier
				.background(colors.panelBackground, shapes.small)
				.border(width = 1.dp, color = colors.panelBorder, shape = shapes.small)
				.padding(3.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(2.dp),
	) {
		ToolbarButton(
			commandId = "mesh.boxSelect",
			icon = LocalUmamoIcons.toolBoxSelect,
			label = stringResource(Res.string.cmd_mesh_box_select),
			active = selectTool is ActiveSelectTool.BoxArmed,
			enabled = enabled,
		)
		ToolbarButton(
			commandId = "mesh.circleSelect",
			icon = LocalUmamoIcons.toolCircleSelect,
			label = stringResource(Res.string.cmd_mesh_circle_select),
			active = selectTool is ActiveSelectTool.Circle,
			enabled = enabled,
		)
		ToolbarButton(
			commandId = "mesh.grab",
			icon = LocalUmamoIcons.toolGrab,
			label = stringResource(Res.string.cmd_mesh_grab),
			active = liveOperator?.kind == MeshOperatorKind.Grab,
			enabled = enabled,
		)
		ToolbarButton(
			commandId = "mesh.rotate",
			icon = LocalUmamoIcons.toolRotate,
			label = stringResource(Res.string.cmd_mesh_rotate),
			active = liveOperator?.kind == MeshOperatorKind.Rotate,
			enabled = enabled,
		)
		ToolbarButton(
			commandId = "mesh.scale",
			icon = LocalUmamoIcons.toolScale,
			label = stringResource(Res.string.cmd_mesh_scale),
			active = liveOperator?.kind == MeshOperatorKind.Scale,
			enabled = enabled,
		)
	}
}

/**
 * One toolbar button: a filled kit [IconButton] whose click dispatches [commandId] through the registry
 * (an unavailable command no-ops there, the same guard every entry point shares).
 *
 * @param String commandId The registry command the button dispatches.
 * @param UmamoIcon icon The button glyph.
 * @param String label The localized tooltip / accessibility label.
 * @param Boolean active Whether the tool is armed / in flight (lights the fill).
 * @param Boolean enabled Whether a document is open (disabled chrome otherwise).
 */
@Composable
private fun ToolbarButton(commandId: String, icon: UmamoIcon, label: String, active: Boolean, enabled: Boolean) {
	val commands = LocalCommands.current
	val shapes = LocalUmamoShapes.current
	IconButton(
		icon = icon,
		onClick = { commands.invoke(commandId) },
		contentDescription = label,
		appearance = IconButtonAppearance.Filled(shapes.small),
		size = DpSize(TOOLBAR_BUTTON_SIZE, TOOLBAR_BUTTON_SIZE),
		glyphSize = TOOLBAR_GLYPH_SIZE,
		active = active,
		enabled = enabled,
	)
}
