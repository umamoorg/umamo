package org.umamo.ui.workspace.spaces

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.MeshSelectMode
import org.umamo.edit.ProportionalEditState
import org.umamo.edit.ProportionalFalloff
import org.umamo.edit.TransformPivotMode
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.kit.BelowAnchorPositionProvider
import org.umamo.ui.kit.DropdownChip
import org.umamo.ui.kit.Menu
import org.umamo.ui.kit.MenuItem
import org.umamo.ui.kit.button.ButtonGroup
import org.umamo.ui.kit.button.ButtonGroupItem
import org.umamo.ui.kit.button.IconButton
import org.umamo.ui.kit.button.IconButtonAppearance
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.viewport.falloffLabel

/*
 * The Edit-mode header controls shared by the editor surfaces that host element editing - the 2D
 * viewport and the UV editor mount the same select-mode buttons, pivot dropdown, and proportional
 * controls, so the two headers stay one behavior (and one look) by construction.  Every control
 * mutates only by dispatching registry commands, per the everything-through-the-action-registry rule.
 *
 * 編集モードの共有ヘッダ操作。2D ビューポートと UV エディタが同じ部品を載せる。変更はすべて
 * コマンドレジストリ経由。
 */

/**
 * The vertex / edge / face select-mode buttons: a three-segment radio ButtonGroup whose lit segment is
 * the selection's current mode.  Each segment dispatches its mesh.selectMode command; the session
 * no-ops a same-mode set, so re-clicking the lit segment records nothing.  Rendered only in Edit mode
 * (the callers gate), so the commands' outside-Edit self-guard is belt-and-braces here but load-bearing
 * for the bare 1 / 2 / 3 key bindings.
 *
 * 頂点・辺・面の選択モードボタン。点灯セグメントが現在のモード。各セグメントはコマンドを発行する。
 *
 * @param MeshSelectMode selectMode The selection's current element domain.
 */
@Composable
internal fun MeshSelectModeButtons(selectMode: MeshSelectMode) {
	val commands = LocalCommands.current
	ButtonGroup(
		items =
			listOf(
				ButtonGroupItem(
					icon = LocalUmamoIcons.meshSelectVertex,
					selected = selectMode == MeshSelectMode.Vertex,
					onClick = { commands.invoke("mesh.selectMode.vertex") },
					contentDescription = stringResource(Res.string.cmd_mesh_select_mode_vertex),
				),
				ButtonGroupItem(
					icon = LocalUmamoIcons.meshSelectEdge,
					selected = selectMode == MeshSelectMode.Edge,
					onClick = { commands.invoke("mesh.selectMode.edge") },
					contentDescription = stringResource(Res.string.cmd_mesh_select_mode_edge),
				),
				ButtonGroupItem(
					icon = LocalUmamoIcons.meshSelectFace,
					selected = selectMode == MeshSelectMode.Face,
					onClick = { commands.invoke("mesh.selectMode.face") },
					contentDescription = stringResource(Res.string.cmd_mesh_select_mode_face),
				),
			),
	)
}

/**
 * The transform pivot selector (Blender's pivot point dropdown, the header face of the Period pie):
 * the chip shows the current pivot's name; each row dispatches its transform.pivot command, so the
 * pie, the palette, and this dropdown stay one behavior.
 *
 * 変形ピボットのドロップダウン（Period パイのヘッダ版）。各行は transform.pivot コマンドを発行する。
 *
 * @param TransformPivotMode pivotMode The session's current pivot mode.
 * @param Boolean enabled Whether a document is open (disabled chrome otherwise).
 */
@Composable
internal fun PivotModeDropdown(pivotMode: TransformPivotMode, enabled: Boolean) {
	val commands = LocalCommands.current
	var expanded by remember { mutableStateOf(false) }
	val currentLabel =
		when (pivotMode) {
			TransformPivotMode.MedianPoint -> stringResource(Res.string.cmd_transform_pivot_median)
			TransformPivotMode.IndividualOrigins -> stringResource(Res.string.cmd_transform_pivot_individual)
			TransformPivotMode.ActiveElement -> stringResource(Res.string.cmd_transform_pivot_active)
			TransformPivotMode.Cursor -> stringResource(Res.string.cmd_transform_pivot_cursor)
		}
	val items =
		listOf(
			MenuItem.Action(label = stringResource(Res.string.cmd_transform_pivot_median), onSelect = { commands.invoke("transform.pivot.median") }),
			MenuItem.Action(label = stringResource(Res.string.cmd_transform_pivot_individual), onSelect = { commands.invoke("transform.pivot.individual") }),
			MenuItem.Action(label = stringResource(Res.string.cmd_transform_pivot_active), onSelect = { commands.invoke("transform.pivot.active") }),
			MenuItem.Action(label = stringResource(Res.string.cmd_transform_pivot_cursor), onSelect = { commands.invoke("transform.pivot.cursor") }),
		)
	DropdownChip(
		expanded = expanded,
		onExpandRequest = { expanded = true },
		contentDescription = stringResource(Res.string.cmd_transform_pivot_pie),
		icon = LocalUmamoIcons.transformPivot,
		label = currentLabel,
		enabled = enabled,
	) {
		Menu(
			items = items,
			onDismissRequest = { expanded = false },
			positionProvider = BelowAnchorPositionProvider,
		)
	}
}

/**
 * The proportional-editing header controls (Edit mode): a concentric-rings toggle lit while
 * proportional editing is on (dispatching mesh.proportional.toggle, the same command O binds to), and
 * while on, a falloff dropdown whose rows dispatch the mesh.proportional.falloff commands.
 *
 * プロポーショナル編集のヘッダ操作。トグルと、有効時のみ表示される減衰ドロップダウン。
 *
 * @param ProportionalEditState? proportionalEdit The session's proportional state, or null while off.
 */
@Composable
internal fun ProportionalEditControls(proportionalEdit: ProportionalEditState?) {
	val commands = LocalCommands.current
	val shapes = LocalUmamoShapes.current
	IconButton(
		icon = LocalUmamoIcons.proportionalEdit,
		onClick = { commands.invoke("mesh.proportional.toggle") },
		contentDescription = stringResource(Res.string.cmd_mesh_proportional_toggle),
		appearance = IconButtonAppearance.Filled(shapes.small),
		size = DpSize(26.dp, 20.dp),
		active = proportionalEdit != null,
	)
	if (proportionalEdit != null) {
		var expanded by remember { mutableStateOf(false) }
		val items =
			listOf(
				MenuItem.Action(label = falloffLabel(ProportionalFalloff.Smooth), onSelect = { commands.invoke("mesh.proportional.falloff.smooth") }),
				MenuItem.Action(label = falloffLabel(ProportionalFalloff.Sphere), onSelect = { commands.invoke("mesh.proportional.falloff.sphere") }),
				MenuItem.Action(label = falloffLabel(ProportionalFalloff.Root), onSelect = { commands.invoke("mesh.proportional.falloff.root") }),
				MenuItem.Action(label = falloffLabel(ProportionalFalloff.Sharp), onSelect = { commands.invoke("mesh.proportional.falloff.sharp") }),
				MenuItem.Action(label = falloffLabel(ProportionalFalloff.Linear), onSelect = { commands.invoke("mesh.proportional.falloff.linear") }),
				MenuItem.Action(label = falloffLabel(ProportionalFalloff.Constant), onSelect = { commands.invoke("mesh.proportional.falloff.constant") }),
			)
		DropdownChip(
			expanded = expanded,
			onExpandRequest = { expanded = true },
			contentDescription = stringResource(Res.string.header_proportional_falloff),
			label = falloffLabel(proportionalEdit.falloff),
		) {
			Menu(
				items = items,
				onDismissRequest = { expanded = false },
				positionProvider = BelowAnchorPositionProvider,
			)
		}
		// Connected Only (Blender's Alt+O): influence spreads along mesh edges instead of leaping
		// straight-line gaps.  Lit while on; same size family as the proportional toggle.
		IconButton(
			icon = LocalUmamoIcons.linked,
			onClick = { commands.invoke("mesh.proportional.connectedToggle") },
			contentDescription = stringResource(Res.string.cmd_mesh_proportional_connected),
			appearance = IconButtonAppearance.Filled(shapes.small),
			size = DpSize(26.dp, 20.dp),
			active = proportionalEdit.connectedOnly,
		)
	}
}
