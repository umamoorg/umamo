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

/*
 * The Edit-mode header controls shared by the editor surfaces that host element editing - the 2D
 * viewport and the UV editor mount the same select-mode buttons, pivot dropdown, and proportional
 * control, so the two headers stay one behavior (and one look) by construction.  Every control
 * mutates by dispatching registry commands, per the everything-through-the-action-registry rule; the
 * one exception is the proportional panel's size row, a value no command carries, which each header
 * routes to its own surface's radius.
 */

/** The opacity of the proportional panel while the tool is off: Blender's inactive look, still live. */
private const val PROPORTIONAL_PANEL_INACTIVE_ALPHA = 0.5f

/** The inset of the proportional panel's checkbox, curves, and size row from its edges. */
private val PROPORTIONAL_PANEL_ROW_INSET = 8.dp

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
 * The transform pivot selector (Blender's pivot point dropdown, the header face of the Period pie):
 * the chip shows the current pivot's name; each row dispatches its transform.pivot command, so the
 * pie, the palette, and this dropdown stay one behavior.
 */
@Composable
internal fun PivotModeDropdown() {
	val commands = LocalCommands.current
	val session = LocalEditorSession.current
	val enabled = session != null
	val pivotMode = session?.pivotMode?.collectAsState()?.value ?: TransformPivotMode.MedianPoint
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
 * curves dispatch their registry commands; the size goes through [size].  Renders nothing outside Edit mode.
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
				modifier = Modifier.padding(horizontal = PROPORTIONAL_PANEL_ROW_INSET, vertical = 2.dp),
			) {
				Checkbox(
					checked = settings.connectedOnly,
					onCheckedChange = { commands.invoke("mesh.proportional.connectedToggle") },
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
				modifier = Modifier.fillMaxWidth().padding(horizontal = PROPORTIONAL_PANEL_ROW_INSET),
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
		modifier = Modifier.padding(horizontal = PROPORTIONAL_PANEL_ROW_INSET, vertical = 2.dp),
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