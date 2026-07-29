package org.umamo.ui.model

import androidx.compose.ui.graphics.Color
import org.umamo.edit.Pose
import org.umamo.runtime.keyform.axisIndexOf
import org.umamo.runtime.keyform.keyIndexAt
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.KeyableTarget
import org.umamo.runtime.model.ParameterId
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
	/** Not keyed on the targeted parameter; the field behaves like any other. */
	None,

	/** The channel is keyed and the pose sits exactly on one of its keys - editing writes that key. */
	OnKey,

	/** The channel is keyed but the pose is between keys - an edit here needs an explicit key to survive. */
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
 * The keyed state of [target] against [parameterId] at [pose].
 *
 * ModifiedUnkeyed wins over the others: a pending edit is the most recent thing the user did, and it is
 * the state that carries a warning.  On-key is decided with the evaluator's own EPS_KEY snap tolerance
 * rather than an exact compare, so the tint agrees with the key the pose actually resolved to instead of
 * flickering to "between" a hair either side of one.
 *
 * @param PuppetModel puppet The rig.
 * @param KeyableTarget target The entity and channel the field edits.
 * @param ParameterId? parameterId The targeted parameter, or null when none is.
 * @param Pose pose The current pose.
 * @param Map pendingEdits The session's unkeyed edits.
 * @return KeyedFieldState The state to tint with.
 */
fun keyedFieldStateOf(
	puppet: PuppetModel,
	target: KeyableTarget,
	parameterId: ParameterId?,
	pose: Pose,
	pendingEdits: Map<KeyableTarget, ChannelValue>,
): KeyedFieldState {
	val track = puppet.channelGridsOf(target.owner)?.get(target.channel)
	if (target in pendingEdits) {
		return KeyedFieldState.ModifiedUnkeyed
	}
	if (parameterId == null || track == null || track.axisIndexOf(parameterId) < 0) {
		return KeyedFieldState.None
	}
	// A direct lookup, not a defaults map over every parameter: this runs per keyable row per
	// recomposition, and only this one parameter's default can ever be read.
	val poseValue = pose[parameterId] ?: puppet.parameters.firstOrNull { parameter -> parameter.id == parameterId }?.default ?: 0f
	return if (track.keyIndexAt(parameterId, poseValue) >= 0) KeyedFieldState.OnKey else KeyedFieldState.BetweenKeys
}
