package org.umamo.ui.properties

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import org.umamo.edit.channelValueAt
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyableTarget
import org.umamo.runtime.model.KeyformOwner
import org.umamo.ui.model.KeyedFieldState
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.model.LocalPuppet
import org.umamo.ui.model.keyedFieldStateOf

/*
 * Resolving a properties-panel row's keyed state from the session.
 *
 * Kept beside the panel rather than inside the field primitives: the kit knows how to PAINT a keyed state
 * and nothing about parameters, poses, or sessions, which is what lets the same fields serve rows that are
 * not keyable at all.
 *
 * プロパティ行のキー状態の解決。kit 側は描画のみを知り、モデルには触れない。
 */

/**
 * The keyed state of [channel] on [drawable] against the currently targeted parameter.
 *
 * @param Drawable drawable The drawable the row edits.
 * @param FormChannel channel The channel the row edits.
 * @return KeyedFieldState The state to tint the field with.
 */
@Composable
internal fun keyedFieldStateOf(drawable: Drawable, channel: FormChannel): KeyedFieldState {
	val puppet = LocalPuppet.current ?: return KeyedFieldState.None
	val session = LocalEditorSession.current ?: return KeyedFieldState.None
	val parameterSelection by remember(session) { session.parameterSelection }.collectAsState()
	val pose by remember(session) { session.pose }.collectAsState()
	val pendingEdits by remember(session) { session.pendingChannelEdits }.collectAsState()
	return keyedFieldStateOf(
		puppet = puppet,
		target = KeyableTarget(KeyformOwner.Drawable(drawable.id), channel),
		parameterId = parameterSelection.active,
		pose = pose,
		pendingEdits = pendingEdits,
	)
}

/**
 * The value a keyable row should DISPLAY: the pending unkeyed edit, else the track's value at the current
 * pose, else the owner's static.
 *
 * The same resolution order the renderer uses, which is the point.  Showing the static alone was wrong
 * twice over: on a keyed channel the static is shadowed by the track, so the field disagreed with the
 * viewport at every pose; and a pending edit lives outside the model entirely, so typing a new value on a
 * keyed channel appeared to be rejected - the field snapped straight back to the shadowed static.
 *
 * @param KeyformOwner owner The entity the row edits.
 * @param FormChannel channel The channel the row edits.
 * @param ChannelValue stored The owner's static value, used when nothing overrides it.
 * @return ChannelValue The value to show.
 */
@Composable
internal fun displayedChannelValue(owner: KeyformOwner, channel: FormChannel, stored: ChannelValue): ChannelValue {
	val puppet = LocalPuppet.current ?: return stored
	val session = LocalEditorSession.current ?: return stored
	val pose by remember(session) { session.pose }.collectAsState()
	val pendingEdits by remember(session) { session.pendingChannelEdits }.collectAsState()
	val target = KeyableTarget(owner, channel)
	return pendingEdits[target] ?: puppet.channelValueAt(target, pose) ?: stored
}

/**
 * The [displayedChannelValue] of a color channel, unwrapped.
 *
 * @param KeyformOwner owner The entity the row edits.
 * @param FormChannel channel The color channel the row edits.
 * @param ColorRgb stored The owner's static color.
 * @return ColorRgb The color to show.
 */
@Composable
internal fun displayedChannelColor(owner: KeyformOwner, channel: FormChannel, stored: ColorRgb): ColorRgb =
	(displayedChannelValue(owner, channel, ChannelValue.Color(stored)) as? ChannelValue.Color)?.color ?: stored
