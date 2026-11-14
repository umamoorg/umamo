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
import androidx.compose.runtime.key
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
import org.umamo.edit.editsElements
import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandFamily
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.CommandSection
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.action.ObserveForCommands
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
import org.umamo.ui.workspace.commands.ProportionalConnectedRequest

/*
 * The Edit- and Pose-mode header controls shared by the editor surfaces that host element editing - the 2D
 * viewport and the UV editor mount the same select-mode buttons, pivot panel, snap menu, and
 * proportional control, so the two headers stay one behavior (and one look) by construction.  Every control
 * mutates by dispatching registry commands, per the everything-through-the-action-registry rule; the
 * one exception is the proportional panel's size row, a value no command carries, which each header
 * routes to its own surface's radius.
 *
 * A control that offers a set of commands lists their family from the registry, so the command tables are what
 * decide its members, their order, their labels and glyphs, and which of them is current: nothing here names a
 * command of a family, and the pies and menus listing the same family cannot come to disagree with these.
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
 * The vertex / edge / face select-mode buttons: the select-mode family as a radio ButtonGroup whose lit segment
 * is the selection's current mode, shown only in the modes [shownIn] accepts.
 *
 * @param Function shownIn Whether a mode shows the buttons: the modes that edit elements by default; a surface that
 *   edits in fewer passes its own.
 */
@Composable
internal fun MeshSelectModeButtons(shownIn: (EditorMode) -> Boolean = { mode -> mode.editsElements }) {
	val commands = LocalCommands.current
	val session = LocalEditorSession.current ?: return
	val editorMode by session.mode.collectAsState()
	val meshSelection by session.meshSelection.collectAsState()

	if (!shownIn(editorMode)) {
		return
	}

	// Keyed on the select mode for its changes alone: each rebuilds the segments, whose commands say which is lit.
	ButtonGroup(items = key(meshSelection.selectMode) { familyButtons(CommandFamily.SelectMode, commands, labeled = false) })
}

/**
 * The transform pivot selector (Blender's Transform Pivot Point, the header face of the Period pie): a chip
 * wearing the current pivot's glyph over a panel of the pivot family, each option dispatching its command, so
 * the pie, the palette, and this panel stay one behavior.
 */
@Composable
internal fun PivotModeDropdown() {
	val commands = LocalCommands.current
	val session = LocalEditorSession.current
	val enabled = session != null
	val pivotMode = session?.pivotMode?.collectAsState()?.value
	val title = stringResource(Res.string.header_transform_pivot_point)
	// Keyed on the pivot for its changes alone: each rebuilds the options, whose commands say which is current.  Only
	// the options are keyed, so a pick leaves the open panel in place.
	val pivots = key(pivotMode) { familyButtons(CommandFamily.PivotMode, commands, labeled = true) }
	PopupChip(
		contentDescription = title,
		icon = pivots.firstOrNull { pivot -> pivot.selected }?.icon,
		enabled = enabled,
	) {
		FilterSectionLabel(title)
		ButtonGroup(
			items = pivots,
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
 * The snap menu (Blender's Shift+S, as a header dropdown): a magnet chip over the surface's snap family - the
 * cursor's snaps, a rule, then the selection's - each row dispatching its command and wearing its glyph, so the
 * pie, the palette, and this menu stay one behavior.  A pick acts on the header's own area: the click that opened
 * the chip stamped that area as the hovered one, and the open menu keeps the pointer from stamping another.
 *
 * @param SnapMenuKind kind Which surface's snaps the menu offers.
 */
@Composable
internal fun SnapDropdown(kind: SnapMenuKind) {
	val commands = LocalCommands.current
	val session = LocalEditorSession.current
	var expanded by remember { mutableStateOf(false) }
	val (cursorSnaps, selectionSnaps) =
		when (kind) {
			SnapMenuKind.Viewport2D -> CommandSection.SnapCursor to CommandSection.SnapSelection
			SnapMenuKind.UvEditor -> CommandSection.UvSnapCursor to CommandSection.UvSnapSelection
		}
	val cursorItems = commands.inGroup(cursorSnaps).map { snap -> snapMenuItem(snap) { commands.invoke(snap.id) } }
	val selectionItems = commands.inGroup(selectionSnaps).map { snap -> snapMenuItem(snap) { commands.invoke(snap.id) } }
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
 * A snap command's menu entry: its label and glyph, running [onSelect] when picked.
 *
 * @param Command snap The snap command.
 * @param Function onSelect Dispatches the command.
 * @return MenuItem.Action The entry.
 */
@Composable
private fun snapMenuItem(snap: Command, onSelect: () -> Unit): MenuItem.Action =
	MenuItem.Action(label = snap.rowLabel?.let { label -> stringResource(label) } ?: snap.id, onSelect = onSelect, icon = snap.icon)

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
 * The proportional-editing header control (Edit and Pose mode), Blender's two-part proportional control as ONE
 * [PopupChip]: the glyph half toggles proportional editing and the chevron half opens the settings panel.
 * The panel reads the session's proportional settings and stays live while the tool is off, so a change made
 * then waits for the next toggle (Blender's rule - a setting never switches the tool on).
 *
 * @param ProportionalSizeField? size The surface's Proportional Size row, or null to leave the row out.
 * @param Function shownIn Whether a mode shows the control: the modes that edit elements by default; a surface that
 *   edits in fewer passes its own.
 */
@Composable
internal fun ProportionalEditControls(
	size: ProportionalSizeField?,
	shownIn: (EditorMode) -> Boolean = { mode -> mode.editsElements },
) {
	val commands = LocalCommands.current
	val colors = LocalUmamoColors.current
	val session = LocalEditorSession.current ?: return
	val editorMode by session.mode.collectAsState()
	val proportionalEdit by session.proportionalEdit.collectAsState()
	val settings by session.proportionalSettings.collectAsState()

	if (!shownIn(editorMode)) {
		return
	}

	// The tool's own command says whether it is on: the same answer its key and the palette act on.
	ObserveForCommands(proportionalEdit)
	val toggle = commands["mesh.proportional.toggle"]
	val enabled = toggle?.isOn() == true
	val stateIcon =
		when {
			enabled && settings.connectedOnly -> LocalUmamoIcons.proportionalConnected
			enabled -> LocalUmamoIcons.proportionalOn
			else -> LocalUmamoIcons.proportionalOff
		}
	// Keyed on the falloff for its changes alone: each rebuilds the curves, whose commands say which is current.
	val curves = key(settings.falloff) { familyButtons(CommandFamily.ProportionalFalloff, commands, labeled = true) }
	PopupChip(
		contentDescription = stringResource(Res.string.header_proportional_falloff),
		icon = stateIcon,
		iconToggle =
			toggle?.let { command ->
				ChipToggle(
					active = enabled,
					onToggle = { commands.invoke(command.id) },
					contentDescription = command.title?.let { title -> stringResource(title) } ?: command.id,
				)
			},
		valueIcon = curves.firstOrNull { curve -> curve.selected }?.icon,
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
				items = curves,
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
 * A command family as button-group segments, in the family's order: each wears its command's glyph, is lit while its
 * command is the one in effect, and dispatches it when picked.  A segment is its glyph, so a member with none drawn
 * has no segment.
 *
 * @param CommandFamily family The family the segments stand for.
 * @param CommandRegistry commands The registry the family is listed from and its segments dispatch through.
 * @param Boolean labeled Whether a segment shows its member's label beside the glyph, as an option list does.
 * @return List<ButtonGroupItem> The segments.
 */
@Composable
private fun familyButtons(
	family: CommandFamily,
	commands: CommandRegistry,
	labeled: Boolean,
): List<ButtonGroupItem> =
	commands.inGroup(family).mapNotNull { member ->
		member.icon?.let { icon ->
			val label = member.rowLabel?.let { resource -> stringResource(resource) } ?: member.id

			ButtonGroupItem(
				icon = icon,
				selected = member.isOn(),
				onClick = { commands.invoke(member.id) },
				contentDescription = label,
				label = label.takeIf { labeled },
			)
		}
	}