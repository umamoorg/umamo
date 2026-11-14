package org.umamo.ui.properties

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.umamo.runtime.model.KeyableTarget
import org.umamo.runtime.model.KeyformTrackRef
import org.umamo.ui.action.CommandFamily
import org.umamo.ui.action.CommandMenuRow
import org.umamo.ui.action.rememberCommandMenu
import org.umamo.ui.kit.menu.ContextMenuArea
import org.umamo.ui.workspace.keyableTrack

/*
 * The keyable-property row wrapper: hover targeting plus the Insert / Remove Keyframe context menu.
 *
 * Both affordances belong to the same rows and neither is useful without the other - the shortcut is the
 * fast path and the menu is how a user finds out the shortcut exists - so they are applied together
 * rather than left to be remembered separately at each call site.
 */

/** A keyable row's context menu: the key pair, Insert Keyframe and Delete Keyframe. */
internal val KEYABLE_ROW_MENU: List<CommandMenuRow> = listOf(CommandMenuRow.Members(CommandFamily.KeyformKey))

/**
 * Wraps [content] as a keyable property row over one channel: it publishes [target] on hover and carries the
 * keyframe context menu.
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
	KeyablePropertyRow(KeyformTrackRef.Channel(target), modifier, content)
}

/**
 * Wraps [content] as a keyable row over any keyform [track] - a channel, or a geometry track such as a warp's
 * lattice: it publishes the track on hover and carries the keyframe context menu.
 *
 * The menu is the key pair as the registry lists it, so a rebind reaches it and it shows whatever chord is bound.
 * Its commands resolve the hovered track when they fire, and a right-click leaves the pointer over this row, so
 * they aim here without the menu passing anything.  The rows gray out in Edit mode, where the commands do not
 * apply, read as the menu opens.
 *
 * @param KeyformTrackRef track The track this row keys.
 * @param Modifier modifier The layout modifier for the row.
 * @param Function content The row itself.
 */
@Composable
internal fun KeyablePropertyRow(
	track: KeyformTrackRef,
	modifier: Modifier = Modifier,
	content: @Composable () -> Unit,
) {
	ContextMenuArea(itemsOnOpen = rememberCommandMenu(KEYABLE_ROW_MENU), modifier = modifier.keyableTrack(track)) {
		content()
	}
}