package org.umamo.ui.workspace.area

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.kit.BelowAnchorPositionProvider
import org.umamo.ui.kit.Surface
import org.umamo.ui.kit.chip.DropdownChip
import org.umamo.ui.kit.container.OverflowRow
import org.umamo.ui.kit.menu.Menu
import org.umamo.ui.kit.menu.MenuItem
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.workspace.AreaScope
import org.umamo.ui.workspace.LocalSpaceRegistry
import org.umamo.ui.workspace.layout.AreaCommand
import org.umamo.ui.workspace.layout.LeafArea

/** Header bar height - compact, since every area carries one. */
private val HEADER_HEIGHT = 28.dp

/**
 * The top strip of every leaf area: the area-type dropdown on the left (the agnostic "switch this
 * area to any space" control) and the current space's optional [org.umamo.ui.workspace.SpaceDescriptor.headerContent]
 * filling the rest of the width.  All structural edits emitted from here are [AreaCommand]s through
 * [onCommand], the single choke point the shell routes through the action registry - the header never
 * mutates the tree itself.
 *
 * @param LeafArea area The area this header belongs to.
 * @param AreaScope scope The area's stable scope, handed to the space's header content.
 * @param Function onCommand Sink for structural edits; this composable itself only emits SwitchSpace.
 * @param Modifier modifier The layout modifier.
 */
@Composable
fun AreaHeader(area: LeafArea, scope: AreaScope, onCommand: (AreaCommand) -> Unit, modifier: Modifier = Modifier) {
	val colors = LocalUmamoColors.current
	Surface(modifier = modifier.fillMaxWidth(), color = colors.headerBackground) {
		Row(
			modifier = Modifier.fillMaxWidth().height(HEADER_HEIGHT).padding(horizontal = 15.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			AreaTypeDropdown(area = area, onCommand = onCommand)
			val headerContent = LocalSpaceRegistry.current.descriptor(area.space).headerContent

			if (headerContent != null) {
				Spacer(modifier = Modifier.width(8.dp))
				// The slot owns the flexible middle: an OverflowRow over the whole region, so a space's own
				// flexibleSpace gaps resolve against all of it (centering, right-alignment) and any control
				// that will not fit collapses into the strip's trailing dropdown rather than being crushed.
				// The area-type chip stays OUTSIDE it - the header's own control, never collapsible, always
				// leftmost.
				OverflowRow(modifier = Modifier.weight(1f)) {
					headerContent(scope)
				}
			} else {
				Spacer(modifier = Modifier.weight(1f))
			}
		}
	}
}

/**
 * The area-type selector, Blender-style: a [DropdownChip] menu of every registered space, whose choice
 * emits a SwitchSpace command.  The area keeps its id, so per-area state such as camera position survives
 * switching away and back.
 *
 * @param LeafArea area The area whose space is being chosen.
 * @param Function onCommand Sink for the SwitchSpace command.
 */
@Composable
private fun AreaTypeDropdown(area: LeafArea, onCommand: (AreaCommand) -> Unit) {
	val registry = LocalSpaceRegistry.current
	val current = registry.descriptor(area.space)
	// Resolved in composition because the chip's semantics lambda is not composable.
	val currentTitle = stringResource(current.title)
	var expanded by remember { mutableStateOf(false) }
	// One menu row per registered space; selecting it switches this area and the menu's own dismiss closes
	// the popup (so onSelect need not toggle `expanded` itself).
	val items =
		registry.all.map { descriptor ->
			MenuItem.Action(
				label = stringResource(descriptor.title),
				onSelect = { onCommand(AreaCommand.SwitchSpace(area.id, descriptor.kind)) },
				icon = descriptor.icon,
			)
		}
	DropdownChip(
		expanded = expanded,
		onExpandRequest = { expanded = true },
		contentDescription = currentTitle,
		icon = current.icon,
	) {
		Menu(
			items = items,
			onDismissRequest = { expanded = false },
			positionProvider = BelowAnchorPositionProvider,
		)
	}
}