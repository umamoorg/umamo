package org.umamo.ui.properties

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import org.umamo.runtime.model.KeyableTarget
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.action.LocalKeymap
import org.umamo.ui.action.formatAccelerator
import org.umamo.ui.kit.ContextMenuArea
import org.umamo.ui.kit.MenuItem
import org.umamo.ui.model.keyableTarget
import org.umamo.ui.resources.*

/*
 * The keyable-property row wrapper: hover targeting plus the Insert / Remove Keyframe context menu.
 *
 * Both affordances belong to the same rows and neither is useful without the other - the shortcut is the
 * fast path and the menu is how a user finds out the shortcut exists - so they are applied together
 * rather than left to be remembered separately at each call site.
 */

/**
 * Wraps [content] as a keyable property row: it publishes [target] on hover and carries the keyframe
 * context menu.
 *
 * The menu dispatches through the action registry rather than calling the session directly, per the
 * project's input rule - so a rebind reaches the menu too, and the menu shows whatever chord is currently
 * bound.  The commands resolve the HOVERED target when they fire, and a right-click necessarily leaves the
 * pointer over this row, so they aim here without the menu having to pass anything.
 *
 * @param KeyableTarget target The entity and channel this row edits.
 * @param Modifier modifier The layout modifier for the row.
 * @param Function content The row itself.
 */
@Composable
internal fun KeyablePropertyRow(
	target: KeyableTarget,
	modifier: Modifier = Modifier,
	content: @Composable () -> Unit,
) {
	val commands = LocalCommands.current
	val keymap = LocalKeymap.current
	val items =
		listOf(
			MenuItem.Action(
				label = stringResource(Res.string.cmd_keyform_insert),
				onSelect = { commands.invoke("keyform.insert") },
				shortcut = keymap.chordFor("keyform.insert")?.let { chord -> formatAccelerator(chord) },
			),
			MenuItem.Action(
				label = stringResource(Res.string.cmd_keyform_delete),
				onSelect = { commands.invoke("keyform.delete") },
				shortcut = keymap.chordFor("keyform.delete")?.let { chord -> formatAccelerator(chord) },
			),
		)
	ContextMenuArea(items = items, modifier = modifier.keyableTarget(target)) {
		content()
	}
}