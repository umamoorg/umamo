package org.umamo.edit.transform

import org.umamo.edit.BrushMode
import org.umamo.edit.BrushSettings
import org.umamo.edit.MAX_BRUSH_RADIUS_WORLD
import org.umamo.edit.MAX_BRUSH_STRENGTH
import org.umamo.edit.MIN_BRUSH_RADIUS_WORLD
import org.umamo.edit.MIN_BRUSH_STRENGTH
import org.umamo.edit.OperatorParameter
import org.umamo.edit.ParameterChoice
import org.umamo.edit.ParameterUnit
import org.umamo.edit.ProportionalFalloff
import org.umamo.edit.choiceValue
import org.umamo.edit.floatValue

/*
 * The deform brush's face on the operation settings strip: the settings a finished stroke can be re-run
 * under - the mode it moves the points by, the strength it carries them with, the radius it reaches, and
 * the falloff curve shaping its influence.  The stroke itself is a log of stamps, so a re-run re-applies
 * those stamps under the new settings rather than editing a delta the weights were already baked into.
 */

/** The parameter keys the deform brush's rows carry (the strip maps them to labels). */
object BrushParameterKeys {
	const val MODE = "brush.mode"
	const val STRENGTH = "brush.strength"
	const val RADIUS = "brush.radius"
	const val FALLOFF = "brush.falloff"

	/** The label-key prefix of the Mode row's choices; the mode's [BrushMode.choiceKey] follows it. */
	const val MODE_CHOICE_PREFIX = "brush.mode."

	/** The label-key prefix of the Falloff row's choices; the curve's [ProportionalFalloff.choiceKey] follows it. */
	const val FALLOFF_CHOICE_PREFIX = "brush.falloff."
}

/**
 * The stable key of a brush mode: its name in lower camel case (drag, flow), the one spelling the Mode row
 * stores and the mode's command ids end with.
 */
val BrushMode.choiceKey: String
	get() = name.replaceFirstChar { first -> first.lowercaseChar() }

/** The Strength row's scrub step. */
private const val BRUSH_STRENGTH_STEP = 0.05f

/** The Radius row's scrub step, in world units. */
private const val BRUSH_RADIUS_STEP = 1f

/**
 * The deform brush's rows on the operation settings strip, in display order.
 *
 * @param BrushSettings settings The configuration the stroke landed with.
 * @return List The rows.
 */
fun brushParameters(settings: BrushSettings): List<OperatorParameter> =
	listOf(
		OperatorParameter.ChoiceParameter(
			key = BrushParameterKeys.MODE,
			labelKey = BrushParameterKeys.MODE,
			value = settings.mode.choiceKey,
			choices = BrushMode.entries.map { mode -> ParameterChoice(mode.choiceKey, BrushParameterKeys.MODE_CHOICE_PREFIX + mode.choiceKey) },
		),
		OperatorParameter.FloatParameter(
			key = BrushParameterKeys.STRENGTH,
			labelKey = BrushParameterKeys.STRENGTH,
			value = settings.strength,
			min = MIN_BRUSH_STRENGTH,
			max = MAX_BRUSH_STRENGTH,
			step = BRUSH_STRENGTH_STEP,
		),
		OperatorParameter.FloatParameter(
			key = BrushParameterKeys.RADIUS,
			labelKey = BrushParameterKeys.RADIUS,
			value = settings.radiusWorld,
			min = MIN_BRUSH_RADIUS_WORLD,
			max = MAX_BRUSH_RADIUS_WORLD,
			step = BRUSH_RADIUS_STEP,
			unit = ParameterUnit.Pixels,
		),
		OperatorParameter.ChoiceParameter(
			key = BrushParameterKeys.FALLOFF,
			labelKey = BrushParameterKeys.FALLOFF,
			value = settings.falloff.choiceKey,
			choices = ProportionalFalloff.entries.map { falloff -> ParameterChoice(falloff.choiceKey, BrushParameterKeys.FALLOFF_CHOICE_PREFIX + falloff.choiceKey) },
		),
	)

/**
 * The configuration a set of brush rows means, with the values the rows do not carry taken from [fallback].
 *
 * @param List<OperatorParameter> parameters The rows.
 * @param BrushSettings fallback The configuration to fall back on.
 * @return BrushSettings The configuration the rows mean.
 */
fun brushSettingsOf(parameters: List<OperatorParameter>, fallback: BrushSettings): BrushSettings =
	fallback.copy(
		strength = parameters.floatValue(BrushParameterKeys.STRENGTH, fallback.strength),
		radiusWorld = parameters.floatValue(BrushParameterKeys.RADIUS, fallback.radiusWorld),
		falloff =
			ProportionalFalloff.entries.firstOrNull { falloff ->
				falloff.choiceKey == parameters.choiceValue(BrushParameterKeys.FALLOFF, fallback.falloff.choiceKey)
			} ?: fallback.falloff,
		mode =
			BrushMode.entries.firstOrNull { mode ->
				mode.choiceKey == parameters.choiceValue(BrushParameterKeys.MODE, fallback.mode.choiceKey)
			} ?: fallback.mode,
	)