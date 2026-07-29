package org.umamo.ui.model

import androidx.compose.ui.graphics.Color
import org.umamo.edit.Pose
import org.umamo.runtime.keyform.keyIndexAt
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.KeyableTarget
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.channelGridsOf
import org.umamo.ui.theme.UmamoColors

/*
 * The keyed state of one editable field, and the colour that expresses it - Blender's keyframe tinting.
 *
 * What it communicates is not decoration: BetweenKeys says "edit here and it is lost unless you key it",
 * and ModifiedUnkeyed says "that is exactly what has happened".  Without them, manual keying silently
 * discards work on the next scrub, which is the single biggest friction a Cubism migrant hits (Cubism
 * auto-keys, so the situation never arises there).
 */

/** The keyed state of a field, in the order a rigger encounters them. */
enum class KeyedFieldState {
	/** The channel carries no track at all; the field edits a plain static and behaves like any other. */
	None,

	/** The channel is keyed and the pose sits exactly on one of its keys - editing writes that key. */
	OnKey,

	/**
	 * The channel is keyed but the pose is not sitting on one of its keys - an edit here needs an explicit
	 * key to survive.  What matters is the TRACK's own axes, not which parameter happens to be targeted:
	 * targeting decides where an insert lands, the track decides whether the value is stored.
	 */
	BetweenKeys,

	/** An edit has been made and not keyed; it is showing in the viewport and dies on the next scrub. */
	ModifiedUnkeyed,
}

/**
 * The tint for this state, or null for [KeyedFieldState.None] (the field keeps its ordinary colors).
 *
 * @param UmamoColors colors The active scheme.
 * @return Color? The tint, or null when the field is not keyed.
 */
fun KeyedFieldState.tint(colors: UmamoColors): Color? =
	when (this) {
		KeyedFieldState.None -> null
		KeyedFieldState.OnKey -> colors.keyedOnKey
		KeyedFieldState.BetweenKeys -> colors.keyedBetween
		KeyedFieldState.ModifiedUnkeyed -> colors.keyedModified
	}

/**
 * The FILL for this state, or null for [KeyedFieldState.None] (the control keeps its ordinary background).
 *
 * A text control expresses its keyed state by tinting its whole background rather than by drawing an
 * outline: a 1px stroke on a 20dp field is a small thing to read across a column of rows, and the column is
 * how the state is actually used.  Controls whose fill already MEANS something keep the outline instead -
 * a checkbox's fill is its checked state and a color swatch's fill is the user's own color, so neither has a
 * background to spend.  The token is translucent, so the control's own hover fill still shows through.
 *
 * @param UmamoColors colors The active scheme.
 * @return Color? The fill, or null when the field is not keyed.
 */
fun KeyedFieldState.backgroundTint(colors: UmamoColors): Color? =
	when (this) {
		KeyedFieldState.None -> null
		KeyedFieldState.OnKey -> colors.keyedOnKeyBackground
		KeyedFieldState.BetweenKeys -> colors.keyedBetweenBackground
		KeyedFieldState.ModifiedUnkeyed -> colors.keyedModifiedBackground
	}

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
