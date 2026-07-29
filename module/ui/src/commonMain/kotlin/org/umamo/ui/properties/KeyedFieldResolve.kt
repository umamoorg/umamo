package org.umamo.ui.properties

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import org.umamo.edit.EditorSession
import org.umamo.edit.Pose
import org.umamo.edit.channelValueAt
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyableTarget
import org.umamo.runtime.model.KeyformOwner
import org.umamo.runtime.model.channelGridsOf
import org.umamo.ui.model.KeyedFieldState
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.model.LocalLiveParams
import org.umamo.ui.model.LocalPuppet
import org.umamo.ui.model.keyedFieldStateOf

/*
 * Resolving a properties-panel row's keyed state from the session.
 *
 * Kept beside the panel rather than inside the field primitives: the kit knows how to PAINT a keyed state
 * and nothing about parameters, poses, or sessions, which is what lets the same fields serve rows that are
 * not keyable at all.
 */

/**
 * The keyed state of [channel] on [drawable] against the currently targeted parameter.
 *
 * @param Drawable drawable The drawable the row edits.
 * @param FormChannel channel The channel the row edits.
 * @return KeyedFieldState The state to tint the field with.
 */
@Composable
internal fun keyedFieldStateOf(drawable: Drawable, channel: FormChannel): KeyedFieldState =
	keyedFieldStateOf(KeyformOwner.Drawable(drawable.id), channel)

/**
 * The keyed state of [channel] on any [owner] against the currently targeted parameter.
 *
 * @param KeyformOwner owner The entity the row edits.
 * @param FormChannel channel The channel the row edits.
 * @return KeyedFieldState The state to tint the field with.
 */
@Composable
internal fun keyedFieldStateOf(owner: KeyformOwner, channel: FormChannel): KeyedFieldState {
	val puppet = LocalPuppet.current ?: return KeyedFieldState.None
	val session = LocalEditorSession.current ?: return KeyedFieldState.None
	val parameterSelection by remember(session) { session.parameterSelection }.collectAsState()
	val pendingEdits by remember(session) { session.pendingChannelEdits }.collectAsState()
	return keyedFieldStateOf(
		puppet = puppet,
		target = KeyableTarget(owner, channel),
		parameterId = parameterSelection.active,
		pose = displayPose(session),
		pendingEdits = pendingEdits,
	)
}

/**
 * The pose the Properties panel should resolve against: the LIVE preview pose when a viewport is
 * publishing one, else the session's committed pose.
 *
 * A preview deliberately never touches session.pose (that is what keeps a whole drag to one undo step),
 * so resolving at the committed pose froze every keyable field and its OnKey/BetweenKeys tint at the
 * gesture-start value while the viewport animated - the exact field/viewport disagreement this resolver
 * exists to prevent.  The observed map is snapshot state, so the reading row recomposes as it moves.
 *
 * @param EditorSession session The open document's session.
 * @return Pose The pose to resolve displayed values at.
 */
@Composable
private fun displayPose(session: EditorSession): Pose {
	val committedPose by remember(session) { session.pose }.collectAsState()
	return LocalLiveParams.current?.observedValues ?: committedPose
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
	val pendingEdits by remember(session) { session.pendingChannelEdits }.collectAsState()
	val target = KeyableTarget(owner, channel)
	return pendingEdits[target] ?: puppet.channelValueAt(target, displayPose(session)) ?: stored
}

/**
 * Routes a keyable row's typed or picked [value]: a KEYED channel records it as a pending unkeyed edit,
 * an unkeyed channel writes the static through [writeStatic].
 *
 * The one place the rule lives.  Writing the static of a keyed channel is shadowed by the track, so the
 * edit appears to be silently rejected (the field snaps back and a following `I` captures the old track
 * value); a pending edit previews in the viewport and waits for `I`.  An unkeyed channel has no track to
 * shadow it, so the static is the real store.  Half the rows hand-copied this branch and the other half
 * shipped without it - which is exactly the drift a shared helper exists to prevent.
 *
 * @param KeyableTarget target The entity and channel the row edits.
 * @param ChannelValue value The value the user chose.
 * @param Function writeStatic Writes the owner's static (the unkeyed path).
 */
internal fun EditorSession.editKeyedChannel(target: KeyableTarget, value: ChannelValue, writeStatic: () -> Unit) {
	val keyed = model.value.channelGridsOf(target.owner)?.get(target.channel) != null
	if (keyed) {
		setPendingChannelEdit(target, value)
	} else {
		writeStatic()
	}
}

/**
 * The [displayedChannelValue] of a scalar channel, unwrapped.
 *
 * @param KeyformOwner owner The entity the row edits.
 * @param FormChannel channel The scalar channel the row edits.
 * @param Float stored The owner's static value.
 * @return Float The value to show.
 */
@Composable
internal fun displayedChannelScalar(owner: KeyformOwner, channel: FormChannel, stored: Float): Float =
	(displayedChannelValue(owner, channel, ChannelValue.Scalar(stored)) as? ChannelValue.Scalar)?.value ?: stored

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
