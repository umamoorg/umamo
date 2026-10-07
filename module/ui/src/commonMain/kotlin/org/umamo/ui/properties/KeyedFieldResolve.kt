package org.umamo.ui.properties

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import org.umamo.edit.EditorSession
import org.umamo.edit.Pose
import org.umamo.edit.keyform.channelValueAt
import org.umamo.runtime.keyform.keyIndexAt
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyableTarget
import org.umamo.runtime.model.KeyformOwner
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.channelGridsOf
import org.umamo.ui.kit.field.KeyedFieldState
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.model.LocalLiveParams
import org.umamo.ui.model.LocalPuppet

/*
 * Resolving a properties-panel row's keyed state from the session.
 *
 * Kept beside the panel rather than inside the field primitives: the kit knows how to PAINT a keyed state
 * and nothing about parameters, poses, or sessions, which is what lets the same fields serve rows that are
 * not keyable at all.
 */

/**
 * The keyed state of [channel] on [drawable] at the displayed pose.
 *
 * @param Drawable drawable The drawable the row edits.
 * @param FormChannel channel The channel the row edits.
 * @return KeyedFieldState The state to tint the field with.
 */
@Composable
internal fun keyedFieldStateOf(drawable: Drawable, channel: FormChannel): KeyedFieldState =
	keyedFieldStateOf(KeyformOwner.Drawable(drawable.id), channel)

/**
 * The keyed state of [channel] on any [owner] at the displayed pose.
 *
 * @param KeyformOwner owner The entity the row edits.
 * @param FormChannel channel The channel the row edits.
 * @return KeyedFieldState The state to tint the field with.
 */
@Composable
internal fun keyedFieldStateOf(owner: KeyformOwner, channel: FormChannel): KeyedFieldState {
	val puppet = LocalPuppet.current ?: return KeyedFieldState.None
	val session = LocalEditorSession.current ?: return KeyedFieldState.None
	val pendingEdits by remember(session) { session.pendingChannelEdits }.collectAsState()
	return keyedFieldStateOf(
		puppet = puppet,
		target = KeyableTarget(owner, channel),
		pose = displayPose(session),
		pendingEdits = pendingEdits,
	)
}

/**
 * The pose the Properties panel should resolve against: the LIVE preview pose when a viewport is
 * publishing one, else the session's committed pose.
 *
 * A preview deliberately never touches session.pose (that is what keeps a whole drag to one undo step), so
 * resolving at the committed pose alone would freeze every keyable field and its OnKey/BetweenKeys tint at
 * the gesture-start value while the viewport animated - the exact field/viewport disagreement this resolver
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
 * The same resolution order the renderer uses, which is the point.  Showing the static alone would be
 * wrong twice over: on a keyed channel the static is shadowed by the track, so the field would disagree
 * with the viewport at every pose; and a pending edit lives outside the model entirely, so typing a new
 * value on a keyed channel would appear to be rejected - the field would snap straight back to the shadowed
 * static.
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
 * The [displayedChannelValue] of a flag channel, unwrapped.
 *
 * @param KeyformOwner owner The entity the row edits.
 * @param FormChannel channel The flag channel the row edits.
 * @param Boolean stored The owner's static value.
 * @return Boolean The value to show.
 */
@Composable
internal fun displayedChannelFlag(owner: KeyformOwner, channel: FormChannel, stored: Boolean): Boolean =
	(displayedChannelValue(owner, channel, ChannelValue.Flag(stored)) as? ChannelValue.Flag)?.flag ?: stored

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

/**
 * The keyed state of [target] at [pose].
 *
 * The TRACK gates everything: a channel with no track stores an edit in its owner's static, which is a
 * plain undoable write with nothing uncommitted about it, so such a field is never tinted.  It reads the
 * pending map on every channel while a field is being scrubbed (see previewChannelEdit), so testing that
 * map before the track gate would paint the orange uncommitted warning across every ordinary drag.
 *
 * Past that gate ModifiedUnkeyed wins over the others: a pending edit is the most recent thing the user
 * did, and it is the state that carries a warning.
 *
 * On-key is resolved against the track's OWN axes rather than against whatever parameter is targeted, and
 * that distinction is the whole difference between the tint answering "is the value under this field
 * stored" and answering "is it stored on the axis you happen to have clicked".  Only the first is what a
 * rigger reads it as; the second would paint a keyed opacity as unstored whenever the target was the other
 * half of a linked pad - or nothing at all.  A multi-axis track is on-key only when the pose sits on a key
 * of EVERY axis, because that is exactly when a capture overwrites a cell instead of inserting one.  The
 * comparison uses the evaluator's own EPS_KEY snap tolerance rather than an exact compare, so the tint
 * agrees with the key the pose actually resolved to instead of flickering a hair either side of one.
 *
 * @param PuppetModel puppet The rig.
 * @param KeyableTarget target The entity and channel the field edits.
 * @param Pose pose The current pose.
 * @param Map pendingEdits The session's unkeyed edits.
 * @return KeyedFieldState The state to tint with.
 */
fun keyedFieldStateOf(
	puppet: PuppetModel,
	target: KeyableTarget,
	pose: Pose,
	pendingEdits: Map<KeyableTarget, ChannelValue>,
): KeyedFieldState {
	val track = puppet.channelGridsOf(target.owner)?.get(target.channel) ?: return KeyedFieldState.None
	if (target in pendingEdits) {
		return KeyedFieldState.ModifiedUnkeyed
	}
	// A zero-axis track holds one value everywhere and keys nothing, so there is no key to be sitting on -
	// but it still shadows the static, so an edit still needs keying.  That is BetweenKeys, not OnKey.
	if (track.axes.isEmpty()) {
		return KeyedFieldState.BetweenKeys
	}
	val onEveryAxis =
		track.axes.all { axis ->
			// A direct lookup, not a defaults map over every parameter: this runs per keyable row per
			// recomposition, and only this track's own parameters can ever be read.
			val poseValue =
				pose[axis.parameterId]
					?: puppet.parameters.firstOrNull { parameter -> parameter.id == axis.parameterId }?.default
					?: 0f
			track.keyIndexAt(axis.parameterId, poseValue) >= 0
		}
	return if (onEveryAxis) KeyedFieldState.OnKey else KeyedFieldState.BetweenKeys
}