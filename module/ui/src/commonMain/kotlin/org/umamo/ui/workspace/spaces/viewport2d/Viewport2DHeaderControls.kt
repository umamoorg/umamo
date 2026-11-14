package org.umamo.ui.workspace.spaces.viewport2d

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.action.CommandFamily
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.action.LocalKeymap
import org.umamo.ui.action.formatAccelerator
import org.umamo.ui.kit.BelowAnchorPositionProvider
import org.umamo.ui.kit.chip.DropdownChip
import org.umamo.ui.kit.container.OverflowRowScope
import org.umamo.ui.kit.menu.Menu
import org.umamo.ui.kit.menu.MenuItem
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.workspace.AreaScope
import org.umamo.ui.workspace.spaces.MeshSelectModeButtons
import org.umamo.ui.workspace.spaces.OverlaysHeaderControl
import org.umamo.ui.workspace.spaces.PivotModeDropdown
import org.umamo.ui.workspace.spaces.ProportionalEditControls
import org.umamo.ui.workspace.spaces.SnapDropdown
import org.umamo.ui.workspace.spaces.SnapMenuKind
import org.umamo.ui.workspace.spaces.sessionProportionalSizeField

/**
 * The 2D viewport's space-specific header strip: the editor mode dropdown, then the edit controls the UV
 * editor's header also mounts, and the area's overlays control.  With no open document the chips render
 * disabled rather than vanishing, so the viewport chrome reads the same before the first file opens.
 *
 * Only the mode dropdown is pinned against the overflow: it is the control the whole viewport header is
 * about, and a header that hides its own subject is worse than one that clips.
 *
 * @param AreaScope scope The hosting area's scope carrying the viewport's view state.
 */
fun OverflowRowScope.viewport2DHeaderControls(scope: AreaScope) {
	val viewState = scope.spaceState(VIEWPORT_VIEW_STATE_KEY) { Viewport2DViewState() }
	pinnedItem("editorMode") { EditorModeDropdown() }
	item("selectMode") { MeshSelectModeButtons() }
	flexibleSpace()
	item("pivot") { PivotModeDropdown() }
	item("snap") { SnapDropdown(SnapMenuKind.Viewport2D) }
	item("proportional") { ProportionalEditControls(size = sessionProportionalSizeField()) }
	flexibleSpace()
	item("overlays") {
		// Disabled, not hidden, with no document: the strip reads the same before the first file opens.
		OverlaysHeaderControl(state = viewState.overlays, enabled = LocalEditorSession.current != null)
	}
}

/**
 * The editor-mode selector, Blender-style: the current mode's icon and label on the shared kit
 * [DropdownChip] face, opening a menu of the editor-mode family whose rows dispatch the matching set-mode command.
 * The family's table says which modes there are, what each looks like, and which is current.
 */
@Composable
private fun EditorModeDropdown() {
	val commands = LocalCommands.current
	val keymap = LocalKeymap.current
	val session = LocalEditorSession.current
	val enabled = session != null
	val editorMode = session?.mode?.collectAsState()?.value
	// Keyed on the mode for its changes alone: each re-reads the family, whose commands say which is current.
	val modes = key(editorMode) { commands.inGroup(CommandFamily.EditorMode) }
	val current = modes.firstOrNull { mode -> mode.isOn() }
	// Resolved in composition because the chip's semantics lambda is not composable.  A chip with no mode to show
	// is named by the family.
	val currentLabel = stringResource(current?.rowLabel ?: CommandFamily.EditorMode.heading)
	var expanded by remember { mutableStateOf(false) }
	// One row per mode; the menu's own dismiss closes the popup, so onSelect need not toggle `expanded`.
	// Each row shows its own command's bound chord (none by default - a hint appears if the user binds one).
	val items =
		modes.map { mode ->
			MenuItem.Action(
				label = mode.rowLabel?.let { label -> stringResource(label) } ?: mode.id,
				onSelect = { commands.invoke(mode.id) },
				shortcut = keymap.chordFor(mode.id)?.let { chord -> formatAccelerator(chord) },
				icon = mode.icon,
			)
		}
	DropdownChip(
		expanded = expanded,
		onExpandRequest = { expanded = true },
		contentDescription = currentLabel,
		icon = current?.icon,
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