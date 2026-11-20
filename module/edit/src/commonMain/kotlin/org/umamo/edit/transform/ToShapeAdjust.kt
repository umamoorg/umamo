package org.umamo.edit.transform

import org.umamo.edit.OperatorParameter
import org.umamo.edit.ParameterChoice
import org.umamo.edit.ParameterUnit
import org.umamo.edit.TargetShape
import org.umamo.edit.choiceValue
import org.umamo.edit.floatValue

/*
 * To Shape's face on the operation settings strip: one strip for both of its commands, so a To Shape (Cylinder) can
 * become a sphere after the fact and the other way round.  The Shape row names the surface, the Axis row the axis a
 * cylinder runs along (a sphere reads none, and keeps the row so switching back finds it), the Degrees row how far
 * round the shape the sheet wraps, and the Factor row how far the points go toward the shape.  The keys are declared here, beside the math, so the UI's label table names
 * constants rather than repeating strings.
 */

/** The parameter keys and choice label-key prefixes To Shape's rows carry. */
object ToShapeParameterKeys {
	/** The Shape row: the surface the points wrap onto. */
	const val SHAPE = "toShape.shape"

	/** The Axis row: the axis a cylinder runs along. */
	const val AXIS = "toShape.axis"

	/** The Degrees row: how far round the shape the sheet wraps. */
	const val DEGREES = "toShape.degrees"

	/** The Factor row: how far the points go toward the shape. */
	const val FACTOR = "toShape.factor"

	/** The label-key prefix of the Shape row's choices; the shape's [TargetShape.choiceKey] follows it. */
	const val SHAPE_CHOICE_PREFIX = "toShape.shape."

	/** The label-key prefix of the Axis row's choices; the axis's [CylinderAxis.choiceKey] follows it. */
	const val AXIS_CHOICE_PREFIX = "toShape.axis."
}

/** The Factor row's drag and arrow step. */
private const val TO_SHAPE_FACTOR_STEP = 0.01f

/** The Degrees row's drag and arrow step. */
private const val TO_SHAPE_DEGREES_STEP = 1f

/**
 * The strip's rows for a To Shape that landed with [settings]: Shape, Axis, Degrees, and Factor.
 *
 * @param ToShapeSettings settings The shape the points were wrapped onto.
 * @return List The four rows.
 */
fun toShapeParameters(settings: ToShapeSettings): List<OperatorParameter> =
	listOf(
		OperatorParameter.ChoiceParameter(
			ToShapeParameterKeys.SHAPE,
			ToShapeParameterKeys.SHAPE,
			settings.shape.choiceKey,
			TargetShape.entries.map { shape -> ParameterChoice(shape.choiceKey, ToShapeParameterKeys.SHAPE_CHOICE_PREFIX + shape.choiceKey) },
		),
		OperatorParameter.ChoiceParameter(
			ToShapeParameterKeys.AXIS,
			ToShapeParameterKeys.AXIS,
			settings.axis.choiceKey,
			CylinderAxis.entries.map { axis -> ParameterChoice(axis.choiceKey, ToShapeParameterKeys.AXIS_CHOICE_PREFIX + axis.choiceKey) },
		),
		OperatorParameter.FloatParameter(
			ToShapeParameterKeys.DEGREES,
			ToShapeParameterKeys.DEGREES,
			settings.degrees.coerceIn(0f, TO_SHAPE_FULL_TURN_DEGREES),
			0f,
			TO_SHAPE_FULL_TURN_DEGREES,
			TO_SHAPE_DEGREES_STEP,
			ParameterUnit.Degrees,
		),
		OperatorParameter.FloatParameter(ToShapeParameterKeys.FACTOR, ToShapeParameterKeys.FACTOR, settings.factor.coerceIn(0f, 1f), 0f, 1f, TO_SHAPE_FACTOR_STEP),
	)

/**
 * The settings the strip's rows name: a row that is absent or names nothing known falls back to Cylinder, Z, a factor of
 * 1, and a whole turn, and the factor and degrees are held to their ranges.
 *
 * @param List parameters The strip's rows.
 * @return ToShapeSettings The shape to wrap onto.
 */
fun toShapeSettingsOf(parameters: List<OperatorParameter>): ToShapeSettings =
	ToShapeSettings(
		shape = targetShapeOf(parameters.choiceValue(ToShapeParameterKeys.SHAPE, TargetShape.Cylinder.choiceKey)),
		axis = cylinderAxisOf(parameters.choiceValue(ToShapeParameterKeys.AXIS, CylinderAxis.Z.choiceKey)),
		factor = parameters.floatValue(ToShapeParameterKeys.FACTOR, 1f).let { factor -> if (factor.isNaN()) 1f else factor.coerceIn(0f, 1f) },
		degrees =
			parameters.floatValue(ToShapeParameterKeys.DEGREES, TO_SHAPE_FULL_TURN_DEGREES).let { degrees ->
				if (degrees.isNaN()) TO_SHAPE_FULL_TURN_DEGREES else degrees.coerceIn(0f, TO_SHAPE_FULL_TURN_DEGREES)
			},
	)