package org.umamo.edit.transform

import org.umamo.edit.OperatorParameter
import org.umamo.edit.ParameterChoice
import org.umamo.edit.ParameterUnit
import org.umamo.edit.TargetShape
import org.umamo.edit.withParameter
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies To Shape's strip rows: they read back as the settings they were built from, the Axis row offers only X and Z,
 * the Degrees row runs 0 to 360 in degrees, and a row that is missing, unknown, or out of range falls back to Cylinder,
 * Z, a factor of 1, and a whole turn.
 */
class ToShapeAdjustTest {
	/** Every row reads back as what it was built from. */
	@Test
	fun theRowsRoundTrip() {
		val settings = ToShapeSettings(TargetShape.Sphere, CylinderAxis.X, 0.25f, 135f)
		val degreesRow = toShapeParameters(settings).first { row -> row.key == ToShapeParameterKeys.DEGREES } as OperatorParameter.FloatParameter

		assertEquals(settings, toShapeSettingsOf(toShapeParameters(settings)))
		assertEquals(
			listOf(ToShapeParameterKeys.SHAPE, ToShapeParameterKeys.AXIS, ToShapeParameterKeys.DEGREES, ToShapeParameterKeys.FACTOR),
			toShapeParameters(settings).map { row -> row.key },
		)
		assertEquals(0f, degreesRow.min)
		assertEquals(360f, degreesRow.max)
		assertEquals(ParameterUnit.Degrees, degreesRow.unit)
	}

	/** The Shape row offers both surfaces and the Axis row exactly X and Z, each choice under its prefix. */
	@Test
	fun theChoicesAreTheTwoShapesAndTwoAxes() {
		val rows = toShapeParameters(ToShapeSettings(TargetShape.Cylinder))
		val shapeRow = rows.first { row -> row.key == ToShapeParameterKeys.SHAPE } as OperatorParameter.ChoiceParameter
		val axisRow = rows.first { row -> row.key == ToShapeParameterKeys.AXIS } as OperatorParameter.ChoiceParameter

		assertEquals(listOf("cylinder", "sphere"), shapeRow.choices.map { choice -> choice.key })
		assertEquals(listOf("x", "z"), axisRow.choices.map { choice -> choice.key })
		assertEquals(listOf("toShape.axis.x", "toShape.axis.z"), axisRow.choices.map { choice -> choice.labelKey })
	}

	/** A run starts about Z at the full factor and all the way round, and an empty row list reads as that cylinder. */
	@Test
	fun theDefaultsAreZTheFullFactorAndAWholeTurn() {
		assertEquals(ToShapeSettings(TargetShape.Cylinder, CylinderAxis.Z, 1f, 360f), ToShapeSettings(TargetShape.Cylinder))
		assertEquals(ToShapeSettings(TargetShape.Cylinder, CylinderAxis.Z, 1f, 360f), toShapeSettingsOf(emptyList()))
	}

	/** An unknown shape or axis key falls back, and the factor is held to 0..1 with NaN read as the full factor. */
	@Test
	fun unknownAndOutOfRangeRowsFallBack() {
		val rows =
			toShapeParameters(ToShapeSettings(TargetShape.Sphere, CylinderAxis.X))
				.withParameter(ToShapeParameterKeys.SHAPE, OperatorParameter.ChoiceParameter(ToShapeParameterKeys.SHAPE, ToShapeParameterKeys.SHAPE, "cube", listOf(ParameterChoice("cube", null))))
				.withParameter(ToShapeParameterKeys.AXIS, OperatorParameter.ChoiceParameter(ToShapeParameterKeys.AXIS, ToShapeParameterKeys.AXIS, "y", listOf(ParameterChoice("y", null))))
		val tooFar = rows.withParameter(ToShapeParameterKeys.FACTOR, OperatorParameter.FloatParameter(ToShapeParameterKeys.FACTOR, ToShapeParameterKeys.FACTOR, 3f, 0f, 1f))
		val notANumber = rows.withParameter(ToShapeParameterKeys.FACTOR, OperatorParameter.FloatParameter(ToShapeParameterKeys.FACTOR, ToShapeParameterKeys.FACTOR, Float.NaN, 0f, 1f))
		val pastAWholeTurn = rows.withParameter(ToShapeParameterKeys.DEGREES, OperatorParameter.FloatParameter(ToShapeParameterKeys.DEGREES, ToShapeParameterKeys.DEGREES, 720f, 0f, 360f))
		val noDegrees = rows.withParameter(ToShapeParameterKeys.DEGREES, OperatorParameter.FloatParameter(ToShapeParameterKeys.DEGREES, ToShapeParameterKeys.DEGREES, Float.NaN, 0f, 360f))

		assertEquals(TargetShape.Cylinder, toShapeSettingsOf(rows).shape)
		assertEquals(CylinderAxis.Z, toShapeSettingsOf(rows).axis)
		assertEquals(1f, toShapeSettingsOf(tooFar).factor)
		assertEquals(1f, toShapeSettingsOf(notANumber).factor)
		assertEquals(360f, toShapeSettingsOf(pastAWholeTurn).degrees)
		assertEquals(360f, toShapeSettingsOf(noDegrees).degrees)
	}
}