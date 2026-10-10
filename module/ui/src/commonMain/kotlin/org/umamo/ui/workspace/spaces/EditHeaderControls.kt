package org.umamo.ui.workspace.spaces

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.EditorMode
import org.umamo.edit.MAX_PROPORTIONAL_RADIUS_WORLD
import org.umamo.edit.MIN_PROPORTIONAL_RADIUS_WORLD
import org.umamo.edit.MeshSelectMode
import org.umamo.edit.ProportionalFalloff
import org.umamo.edit.TransformPivotMode
import org.umamo.edit.transform.choiceKey
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.kit.BelowAnchorPositionProvider
import org.umamo.ui.kit.StackAxis
import org.umamo.ui.kit.Tooltip
import org.umamo.ui.kit.button.ButtonGroup
import org.umamo.ui.kit.button.ButtonGroupItem
import org.umamo.ui.kit.chip.ChipToggle
import org.umamo.ui.kit.chip.DropdownChip
import org.umamo.ui.kit.chip.FilterSectionLabel
import org.umamo.ui.kit.chip.PopupChip
import org.umamo.ui.kit.field.Checkbox
import org.umamo.ui.kit.field.NumberField
import org.umamo.ui.kit.field.PropertyFieldRow
import org.umamo.ui.kit.field.formatDecimals
import org.umamo.ui.kit.menu.Menu
import org.umamo.ui.kit.menu.MenuItem
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.UmamoIcon
import org.umamo.ui.viewport.falloffLabel
import org.umamo.ui.workspace.commands.ProportionalConnectedRequest

/*
 * The Edit-mode header controls shared by the editor surfaces that host element editing - the 2D
 * viewport and the UV editor mount the same select-mode buttons, pivot panel, snap menu, and
 * proportional control, so the two headers stay one behavior (and one look) by construction.  Every control
 * mutates by dispatching registry commands, per the everything-through-the-action-registry rule; the
 * one exception is the proportional panel's size row, a value no command carries, which each header
 * routes to its own surface's radius.
 */

/** The opacity of the proportional panel while the tool is off: Blender's inactive look, still live. */
private const val PROPORTIONAL_PANEL_INACTIVE_ALPHA = 0.5f

/** The inset of the header panels' rows (the pivots; Connected Only, the curves, and the size) from their edges, the headings' own. */
private val HEADER_PANEL_ROW_INSET = 8.dp

/** The space between the proportional panel's groups (Connected Only, the curves, the size), Blender's separator gap. */
private val PROPORTIONAL_PANEL_GROUP_GAP = 6.dp

/** The fractional places the Proportional Size field shows and commits, the operation strip's own. */
private const val PROPORTIONAL_SIZE_DECIMALS = 2

/**
 * The vertex / edge / face select-mode buttons: a three-segment radio ButtonGroup whose lit segment is
 * the selection's current mode.  Each segment dispatches its mesh.selectMode command; the session
 * no-ops a same-mode set, so re-clicking the lit segment records nothing.  Renders nothing outside Edit
 * mode, so the commands' outside-Edit self-guard is belt-and-braces here but load-bearing for the bare
 * 1 / 2 / 3 key bindings.
 */
@Composable
internal fun MeshSelectModeButtons() {
	val commands = LocalCommands.current
	val session = LocalEditorSession.current ?: return
	val editorMode by session.mode.collectAsState()
	val meshSelection by session.meshSelection.collectAsState()
	if (editorMode != EditorMode.Edit) {
		return
	}
	val selectMode = meshSelection.selectMode
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
 * The transform pivot selector (Blender's Transform Pivot Point, the header face of the Period pie): the
 * chip wears the current pivot's glyph, and its panel heads one vertical [ButtonGroup] of the pivots, each
 * with the glyph the chip and the pie show for it, the current one lit - the proportional panel's option
 * group, so the header's two option panels work alike.  The panel stays open across picks.  Each segment
 * dispatches its transform.pivot command, so the pie, the palette, and this panel stay one behavior.
 */
@Composable
internal fun PivotModeDropdown() {
	val commands = LocalCommands.current
	val session = LocalEditorSession.current
	val enabled = session != null
	val pivotMode = session?.pivotMode?.collectAsState()?.value ?: TransformPivotMode.MedianPoint
	val title = stringResource(Res.string.header_transform_pivot_point)
	PopupChip(
		contentDescription = title,
		icon = pivotIcon(pivotMode),
		enabled = enabled,
	) {
		FilterSectionLabel(title)
		ButtonGroup(
			items =
				TransformPivotMode.entries.map { mode ->
					val label = pivotLabel(mode)
					ButtonGroupItem(
						icon = pivotIcon(mode),
						selected = mode == pivotMode,
						onClick = { commands.invoke(pivotCommandId(mode)) },
						contentDescription = label,
						label = label,
					)
				},
			modifier = Modifier.fillMaxWidth().padding(horizontal = HEADER_PANEL_ROW_INSET),
			axis = StackAxis.Vertical,
		)
	}
}

/** Which snap menu a header mounts: the 2D viewport's world snaps or the UV editor's texture-space ones. */
internal enum class SnapMenuKind {
	Viewport2D,
	UvEditor,
}

/**
 * One row of a snap menu: the command it dispatches, its title, and its glyph.
 *
 * @property String commandId The snap command the row dispatches.
 * @property StringResource title The command's title, the row's label.
 * @property UmamoIcon icon The row's glyph, the one the snap pie shows for the same command.
 */
private class SnapMenuRow(
	val commandId: String,
	val title: StringResource,
	val icon: UmamoIcon,
)

/** The 2D viewport's snap rows: the cursor moves, then the selection moves. */
private val VIEWPORT_SNAP_CURSOR_ROWS =
	listOf(
		SnapMenuRow("snap.cursorToWorldOrigin", Res.string.cmd_snap_cursor_world_origin, LocalUmamoIcons.cursor),
		SnapMenuRow("snap.cursorToGrid", Res.string.cmd_snap_cursor_grid, LocalUmamoIcons.cursor),
		SnapMenuRow("snap.cursorToSelected", Res.string.cmd_snap_cursor_selected, LocalUmamoIcons.cursor),
		SnapMenuRow("snap.cursorToActive", Res.string.cmd_snap_cursor_active, LocalUmamoIcons.cursor),
	)

/** The 2D viewport's selection snap rows. */
private val VIEWPORT_SNAP_SELECTION_ROWS =
	listOf(
		SnapMenuRow("snap.selectionToGrid", Res.string.cmd_snap_selection_grid, LocalUmamoIcons.selection),
		SnapMenuRow("snap.selectionToCursor", Res.string.cmd_snap_selection_cursor, LocalUmamoIcons.selection),
		SnapMenuRow("snap.selectionToCursorOffset", Res.string.cmd_snap_selection_cursor_offset, LocalUmamoIcons.selection),
		SnapMenuRow("snap.selectionToActive", Res.string.cmd_snap_selection_active, LocalUmamoIcons.selection),
	)

/** The UV editor's cursor snap rows, wearing the cursor glyph Blender's UV editor uses. */
private val UV_SNAP_CURSOR_ROWS =
	listOf(
		SnapMenuRow("uv.snap.cursorToPixels", Res.string.cmd_uv_snap_cursor_pixels, LocalUmamoIcons.pivotCursor),
		SnapMenuRow("uv.snap.cursorToSelected", Res.string.cmd_uv_snap_cursor_selected, LocalUmamoIcons.pivotCursor),
		SnapMenuRow("uv.snap.cursorToGrid", Res.string.cmd_uv_snap_cursor_grid, LocalUmamoIcons.pivotCursor),
	)

/** The UV editor's selection snap rows. */
private val UV_SNAP_SELECTION_ROWS =
	listOf(
		SnapMenuRow("uv.snap.selectionToPixels", Res.string.cmd_uv_snap_selection_pixels, LocalUmamoIcons.selection),
		SnapMenuRow("uv.snap.selectionToCursor", Res.string.cmd_uv_snap_selection_cursor, LocalUmamoIcons.selection),
		SnapMenuRow("uv.snap.selectionToCursorOffset", Res.string.cmd_uv_snap_selection_cursor_offset, LocalUmamoIcons.selection),
		SnapMenuRow("uv.snap.selectionToGrid", Res.string.cmd_uv_snap_selection_grid, LocalUmamoIcons.selection),
	)

/**
 * The snap menu (Blender's Shift+S, as a header dropdown): an icon-only magnet chip over a menu of the
 * surface's snap commands - the cursor moves, a separator, then the selection moves, Blender's grouping.
 * Each row dispatches the command the snap pie's entry dispatches and wears the pie entry's glyph, so the
 * pie, the palette, and this menu stay one behavior.  Every row is a one-shot command, so this stays a
 * menu: a pick runs it and closes.
 *
 * The 2D viewport's menu is the world snaps, offered in both modes and disabled with no document.  The UV
 * editor's is the texture-space snaps, which are Edit-mode operations, so it renders only in Edit mode.  A
 * pick acts on the header's own area: the click that opened the chip stamped that area as the hovered one,
 * and the open menu keeps the pointer from stamping another.
 *
 * @param SnapMenuKind kind Which surface's snaps the menu offers.
 */
@Composable
internal fun SnapDropdown(kind: SnapMenuKind) {
	val commands = LocalCommands.current
	val session = LocalEditorSession.current
	val editorMode = session?.mode?.collectAsState()?.value
	if (kind == SnapMenuKind.UvEditor && editorMode != EditorMode.Edit) {
		return
	}
	var expanded by remember { mutableStateOf(false) }
	val (cursorRows, selectionRows) =
		when (kind) {
			SnapMenuKind.Viewport2D -> VIEWPORT_SNAP_CURSOR_ROWS to VIEWPORT_SNAP_SELECTION_ROWS
			SnapMenuKind.UvEditor -> UV_SNAP_CURSOR_ROWS to UV_SNAP_SELECTION_ROWS
		}
	val cursorItems = cursorRows.map { row -> snapMenuItem(row) { commands.invoke(row.commandId) } }
	val selectionItems = selectionRows.map { row -> snapMenuItem(row) { commands.invoke(row.commandId) } }
	DropdownChip(
		expanded = expanded,
		onExpandRequest = { expanded = true },
		contentDescription = stringResource(Res.string.cmd_snap_pie),
		icon = LocalUmamoIcons.snap,
		enabled = session != null,
	) {
		Menu(
			items = cursorItems + MenuItem.Separator + selectionItems,
			onDismissRequest = { expanded = false },
			positionProvider = BelowAnchorPositionProvider,
		)
	}
}

/**
 * A snap row's menu entry: its title and glyph, running [onSelect] when picked.
 *
 * @param SnapMenuRow row The row.
 * @param Function onSelect Dispatches the row's command.
 * @return MenuItem.Action The entry.
 */
@Composable
private fun snapMenuItem(row: SnapMenuRow, onSelect: () -> Unit): MenuItem.Action =
	MenuItem.Action(label = stringResource(row.title), onSelect = onSelect, icon = row.icon)

/**
 * The Proportional Size row of the proportional panel: the radius a surface's proportional editing reaches,
 * in that surface's own pixels, and where an edit of it goes.  Each header supplies its own, because the two
 * surfaces keep different radii: the 2D viewport's is the session's world radius, a UV editor's is the
 * shown texture's own radius in texels.
 *
 * @property Float value The radius the field shows.
 * @property Function onValueChange Takes an edited radius.
 * @property ClosedFloatingPointRange<Float> range The radii the field accepts.
 */
@Immutable
internal class ProportionalSizeField(
	val value: Float,
	val onValueChange: (Float) -> Unit,
	val range: ClosedFloatingPointRange<Float>,
)

/**
 * The 2D viewport's Proportional Size row: the session's world radius, live while proportional editing is
 * on and remembered while it is off, edited straight through the session (a value with no command to carry
 * it, like the operation strip's write-back).
 *
 * @return ProportionalSizeField? The row, or null with no document open.
 */
@Composable
internal fun sessionProportionalSizeField(): ProportionalSizeField? {
	val session = LocalEditorSession.current ?: return null
	val settings by session.proportionalSettings.collectAsState()
	return ProportionalSizeField(
		value = settings.radiusWorld,
		onValueChange = session::setProportionalRadius,
		range = MIN_PROPORTIONAL_RADIUS_WORLD..MAX_PROPORTIONAL_RADIUS_WORLD,
	)
}

/**
 * The proportional-editing header control (Edit mode), Blender's two-part proportional control as ONE
 * [PopupChip]: the glyph half toggles proportional editing (dispatching mesh.proportional.toggle, the
 * command O binds to) and wears Blender's state glyph - off, on, or on with Connected Only - and the
 * chevron half, with the active falloff's curve beside the chevron, opens the settings panel.
 *
 * The panel is Blender's proportional settings popover: Connected Only, the falloff curves as a vertical
 * [ButtonGroup] with the active curve lit, and Proportional Size when the header supplies one.  The curve glyph and every row read the
 * session's proportional settings, so they show the configuration a toggle brings back while the tool is
 * off; the glyph dims and the panel dims with it, yet every row stays live, and a change made while off
 * waits for the next toggle (Blender's rule - a setting never switches the tool on).  The checkbox and the
 * curves dispatch their registry commands, the checkbox with the flag it sets so the change stays silent (the
 * box is its own confirmation); the size goes through [size].  Renders nothing outside Edit mode.
 *
 * @param ProportionalSizeField? size The surface's Proportional Size row, or null to leave the row out.
 */
@Composable
internal fun ProportionalEditControls(size: ProportionalSizeField?) {
	val commands = LocalCommands.current
	val colors = LocalUmamoColors.current
	val session = LocalEditorSession.current ?: return
	val editorMode by session.mode.collectAsState()
	val proportionalEdit by session.proportionalEdit.collectAsState()
	val settings by session.proportionalSettings.collectAsState()
	if (editorMode != EditorMode.Edit) {
		return
	}
	val enabled = proportionalEdit != null
	val stateIcon =
		when {
			enabled && settings.connectedOnly -> LocalUmamoIcons.proportionalConnected
			enabled -> LocalUmamoIcons.proportionalOn
			else -> LocalUmamoIcons.proportionalOff
		}
	PopupChip(
		contentDescription = stringResource(Res.string.header_proportional_falloff),
		icon = stateIcon,
		iconToggle =
			ChipToggle(
				active = enabled,
				onToggle = { commands.invoke("mesh.proportional.toggle") },
				contentDescription = stringResource(Res.string.cmd_mesh_proportional_toggle),
			),
		valueIcon = falloffIcon(settings.falloff),
		valueIconTint = if (enabled) null else colors.textMuted,
	) {
		// Blender's inactive look: the whole panel dims while the tool is off, and stays live.
		Column(modifier = Modifier.alpha(if (enabled) 1f else PROPORTIONAL_PANEL_INACTIVE_ALPHA)) {
			Tooltip(
				text = stringResource(Res.string.transform_options_connected_description),
				modifier = Modifier.padding(horizontal = HEADER_PANEL_ROW_INSET, vertical = 2.dp),
			) {
				Checkbox(
					checked = settings.connectedOnly,
					onCheckedChange = { checked -> commands.invoke("mesh.proportional.connectedToggle", ProportionalConnectedRequest(checked)) },
					label = stringResource(Res.string.transform_options_connected),
				)
			}
			Spacer(modifier = Modifier.height(PROPORTIONAL_PANEL_GROUP_GAP))
			// The curves as Blender's expanded enum: one butted column of options, the active curve lit.
			ButtonGroup(
				items =
					ProportionalFalloff.entries.map { falloff ->
						ButtonGroupItem(
							icon = falloffIcon(falloff),
							selected = falloff == settings.falloff,
							onClick = { commands.invoke("mesh.proportional.falloff.${falloff.choiceKey}") },
							contentDescription = falloffLabel(falloff),
							label = falloffLabel(falloff),
						)
					},
				modifier = Modifier.fillMaxWidth().padding(horizontal = HEADER_PANEL_ROW_INSET),
				axis = StackAxis.Vertical,
			)
			if (size != null) {
				Spacer(modifier = Modifier.height(PROPORTIONAL_PANEL_GROUP_GAP))
				ProportionalSizeRow(size)
			}
		}
	}
}

/**
 * The Proportional Size row: a property field row over the radius, in pixels.  A commit of the radius the
 * field already shows is no edit: the field commits on focus loss as well as on Enter, so closing the panel
 * hands back what it showed, rounded to its places, and that must not nudge a radius the wheel left between
 * them.
 *
 * @param ProportionalSizeField size The row's value, sink, and range.
 */
@Composable
private fun ProportionalSizeRow(size: ProportionalSizeField) {
	PropertyFieldRow(
		label = stringResource(Res.string.transform_options_proportional_size),
		description = stringResource(Res.string.transform_options_proportional_size_description),
		modifier = Modifier.padding(horizontal = HEADER_PANEL_ROW_INSET, vertical = 2.dp),
	) {
		NumberField(
			value = size.value,
			onValueChange = { radius ->
				if (formatDecimals(radius, PROPORTIONAL_SIZE_DECIMALS) != formatDecimals(size.value, PROPORTIONAL_SIZE_DECIMALS)) {
					size.onValueChange(radius)
				}
			},
			range = size.range,
			decimals = PROPORTIONAL_SIZE_DECIMALS,
			unitSuffix = stringResource(Res.string.unit_pixels),
			modifier = Modifier.fillMaxWidth(),
		)
	}
}

/**
 * A transform pivot's glyph: Blender's pivot icon, the one the Period pie shows for it, on the chip while the
 * pivot is current and on the pivot's menu row.
 *
 * @param TransformPivotMode pivotMode The pivot.
 * @return UmamoIcon The pivot's glyph.
 */
private fun pivotIcon(pivotMode: TransformPivotMode): UmamoIcon =
	when (pivotMode) {
		TransformPivotMode.MedianPoint -> LocalUmamoIcons.pivotMedian
		TransformPivotMode.IndividualOrigins -> LocalUmamoIcons.pivotIndividual
		TransformPivotMode.ActiveElement -> LocalUmamoIcons.pivotActive
		TransformPivotMode.Cursor -> LocalUmamoIcons.pivotCursor
	}

/**
 * A transform pivot's display name, the label of its command.
 *
 * @param TransformPivotMode pivotMode The pivot.
 * @return String The localized name.
 */
@Composable
private fun pivotLabel(pivotMode: TransformPivotMode): String =
	when (pivotMode) {
		TransformPivotMode.MedianPoint -> stringResource(Res.string.cmd_transform_pivot_median)
		TransformPivotMode.IndividualOrigins -> stringResource(Res.string.cmd_transform_pivot_individual)
		TransformPivotMode.ActiveElement -> stringResource(Res.string.cmd_transform_pivot_active)
		TransformPivotMode.Cursor -> stringResource(Res.string.cmd_transform_pivot_cursor)
	}

/**
 * The command that sets a transform pivot, the one the Period pie's entry dispatches too.
 *
 * @param TransformPivotMode pivotMode The pivot.
 * @return String The command id.
 */
private fun pivotCommandId(pivotMode: TransformPivotMode): String =
	when (pivotMode) {
		TransformPivotMode.MedianPoint -> "transform.pivot.median"
		TransformPivotMode.IndividualOrigins -> "transform.pivot.individual"
		TransformPivotMode.ActiveElement -> "transform.pivot.active"
		TransformPivotMode.Cursor -> "transform.pivot.cursor"
	}

/**
 * A falloff curve's glyph: Blender's curve icon, on the chip beside the chevron and on the curve's row.
 *
 * @param ProportionalFalloff falloff The falloff curve.
 * @return UmamoIcon The curve's glyph.
 */
private fun falloffIcon(falloff: ProportionalFalloff): UmamoIcon =
	when (falloff) {
		ProportionalFalloff.Smooth -> LocalUmamoIcons.falloffSmooth
		ProportionalFalloff.Sphere -> LocalUmamoIcons.falloffSphere
		ProportionalFalloff.Root -> LocalUmamoIcons.falloffRoot
		ProportionalFalloff.InverseSquare -> LocalUmamoIcons.falloffInverseSquare
		ProportionalFalloff.Sharp -> LocalUmamoIcons.falloffSharp
		ProportionalFalloff.Linear -> LocalUmamoIcons.falloffLinear
		ProportionalFalloff.Constant -> LocalUmamoIcons.falloffConstant
		ProportionalFalloff.Random -> LocalUmamoIcons.falloffRandom
	}