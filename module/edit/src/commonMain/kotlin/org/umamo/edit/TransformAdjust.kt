package org.umamo.edit

import kotlin.math.PI

/*
 * The modal transforms' face on the operation settings strip - the half that knows no geometry: the
 * numbers a Grab / Rotate / Scale (or a Vertex Slide) landed with as the strip's typed rows, the
 * parameters those rows mean, and the proportional rows' state.  The registrations that replay a
 * frozen capture through the viewport's world geometry or the UV editor's frame live in :ui
 * (viewport/TransformAdjustRegistration.kt) and cannot be here: :edit and :render are siblings over
 * :runtime, and the world-to-base inverse needs :render's evaluator.
 *
 * There is no axis-constraint row, for the reason the placement gesture has none: a lock only zeroes
 * one Grab component or pins one Scale factor at 1, so the per-component rows already say it.
 */

/**
 * The numbers one modal transform applies, in the positions' units: a Grab's translation, a Scale's
 * factors, a Rotate's angle, the rest at identity.  During a drag the UI resolves one from each pointer
 * frame; after the commit the operation settings strip shows it as rows and builds one back from them,
 * so the same transform runs from a pointer or a typed value.
 *
 * @property Float deltaX          The Grab translation's x (identity 0 for the other operators).
 * @property Float deltaY          The Grab translation's y, in the positions' sense (y up for world space).
 * @property Float factorX         The Scale factor along x (identity 1 for the other operators).
 * @property Float factorY         The Scale factor along y.
 * @property Float rotationRadians The Rotate angle in the positions' sense (identity 0 for the others).
 */
class TransformGestureParameters(
	val deltaX: Float,
	val deltaY: Float,
	val factorX: Float,
	val factorY: Float,
	val rotationRadians: Float,
) {
	companion object {
		/** The parameters that move nothing. */
		val IDENTITY = TransformGestureParameters(0f, 0f, 1f, 1f, 0f)
	}
}

/** The parameter keys the transform gestures' rows carry (the strip maps them to labels). */
object TransformParameterKeys {
	const val MOVE_X = "transform.moveX"
	const val MOVE_Y = "transform.moveY"
	const val MOVE_Z = "transform.moveZ"
	const val ANGLE = "transform.angle"
	const val SCALE_X = "transform.scaleX"
	const val SCALE_Y = "transform.scaleY"
	const val SCALE_Z = "transform.scaleZ"
	const val PROPORTIONAL = "transform.proportional"
	const val FALLOFF = "transform.falloff"
	const val PROPORTIONAL_SIZE = "transform.proportionalSize"
	const val CONNECTED_ONLY = "transform.connectedOnly"
	const val SLIDE_FACTOR = "transform.slideFactor"

	/** The label-key prefix of the Falloff row's choices; the curve's [ProportionalFalloff.choiceKey] follows it. */
	const val FALLOFF_CHOICE_PREFIX = "transform.falloff."
}

/** The widest angle an Angle row accepts, in degrees either way. */
const val TRANSFORM_ANGLE_LIMIT = 360f

/** The smallest factor a Scale row accepts - a zero scale would collapse the geometry. */
const val TRANSFORM_SCALE_MIN = 0.01f

/** The largest factor a Scale row accepts. */
const val TRANSFORM_SCALE_MAX = 100f

/** The Scale rows' scrub step. */
const val TRANSFORM_SCALE_STEP = 0.01f

/** The farthest a Move row reaches either way, in the gesture space's units (world px or texels). */
private const val TRANSFORM_MOVE_LIMIT = 100_000f

/** The Proportional Size row's scrub step, in the gesture space's units. */
private const val PROPORTIONAL_SIZE_STEP = 1f

/** The Vertex Slide Factor row's scrub step. */
private const val SLIDE_FACTOR_STEP = 0.01f

/**
 * The space a transform's rows read in - which decides the axis labels and the sign conventions.
 */
enum class TransformRowSpace {
	/**
	 * World units in a 2D viewport: y up, shown as the Z axis (the project presents the 2D plane as X
	 * and Z per the Y+ forward, Z+ up convention, and the axis-lock badge says "along Z").
	 */
	World,

	/**
	 * Display texels in the UV editor: the vertical row reads y DOWN, as the page and the placement
	 * strip's rows do, and the angle's sign flips with it (the flip mirrors a rotation's sense).
	 */
	UvDisplay,
}

/**
 * The proportional-editing rows' values: whether it applied, the curve, the radius in the gesture
 * space's units, and Connected Only.  Built from the session state at confirm and read back from the
 * rows at each adjustment.
 *
 * @property Boolean             enabled       Whether the halo applies.
 * @property ProportionalFalloff falloff       The falloff curve.
 * @property Float               radius        The influence radius, in the gesture space's units.
 * @property Boolean             connectedOnly Whether influence spreads only along mesh edges.
 */
class ProportionalRows(
	val enabled: Boolean,
	val falloff: ProportionalFalloff,
	val radius: Float,
	val connectedOnly: Boolean,
) {
	/**
	 * The state the rows describe, or null while the flag is off - what the capture's halos re-derive
	 * from.  The state's radius is [radius] in the gesture space's units; a caller writing it back to the
	 * session's WORLD radius from another space substitutes its own.
	 *
	 * @return ProportionalEditState? The state, or null.
	 */
	fun asState(): ProportionalEditState? = if (enabled) ProportionalEditState(falloff, radius, connectedOnly) else null

	companion object {
		/**
		 * The rows for the session's live state at confirm, with [radius] the radius the gesture actually
		 * used (the session's world radius in the viewport, the editor's texel radius in the UV editor)
		 * and the defaults standing in for the curve and the flag while the state is off.
		 *
		 * @param ProportionalEditState? state  The session's proportional state, or null while off.
		 * @param Float                  radius The radius the gesture ran with, in the gesture space's units.
		 * @return ProportionalRows The rows.
		 */
		fun of(state: ProportionalEditState?, radius: Float): ProportionalRows =
			ProportionalRows(
				enabled = state != null,
				falloff = state?.falloff ?: ProportionalFalloff.Smooth,
				radius = radius,
				connectedOnly = state?.connectedOnly ?: false,
			)
	}
}

/**
 * The strip's rows for a Grab / Rotate / Scale that landed with [parameters]: the move's two
 * components, the angle in degrees, or the two factors, followed by the proportional rows when the
 * gesture could take weights.  A Vertex Slide has no rows here (see [slideParameters]).
 *
 * @param MeshOperatorKind           kind         The operator that ran.
 * @param TransformGestureParameters parameters   The numbers it landed with, in the gesture space's units.
 * @param TransformRowSpace          space        The space the rows read in.
 * @param ProportionalRows?          proportional The proportional rows, or null when the gesture took no
 *   weights (Object mode, a suppressed auto-grab).
 * @return List The rows, empty for an operator that has none.
 */
fun transformParameters(
	kind: MeshOperatorKind,
	parameters: TransformGestureParameters,
	space: TransformRowSpace,
	proportional: ProportionalRows?,
): List<OperatorParameter> {
	val rows = ArrayList<OperatorParameter>()
	when (kind) {
		MeshOperatorKind.Grab -> {
			rows.add(moveRow(TransformParameterKeys.MOVE_X, parameters.deltaX))
			rows.add(moveRow(space.verticalMoveKey, space.rowVertical(parameters.deltaY)))
		}

		MeshOperatorKind.Rotate ->
			rows.add(
				OperatorParameter.FloatParameter(
					TransformParameterKeys.ANGLE,
					TransformParameterKeys.ANGLE,
					space.rowDegrees(parameters.rotationRadians),
					-TRANSFORM_ANGLE_LIMIT,
					TRANSFORM_ANGLE_LIMIT,
					unit = ParameterUnit.Degrees,
				),
			)

		MeshOperatorKind.Scale -> {
			rows.add(scaleRow(TransformParameterKeys.SCALE_X, parameters.factorX))
			rows.add(scaleRow(space.verticalScaleKey, parameters.factorY))
		}

		MeshOperatorKind.VertexSlide -> return emptyList()
	}
	if (proportional != null) {
		rows.addAll(proportionalParameters(proportional))
	}
	return rows
}

/**
 * The gesture parameters the strip's rows mean - the inverse of [transformParameters], back into the
 * gesture space's form the operator math takes.  A missing row reads as identity, so the rows of one
 * operator never move another.
 *
 * @param MeshOperatorKind  kind       The operator the rows belong to.
 * @param TransformRowSpace space      The space the rows read in.
 * @param List              parameters The rows, possibly edited.
 * @return TransformGestureParameters The parameters to apply.
 */
fun transformGestureParametersOf(
	kind: MeshOperatorKind,
	space: TransformRowSpace,
	parameters: List<OperatorParameter>,
): TransformGestureParameters =
	when (kind) {
		MeshOperatorKind.Grab ->
			TransformGestureParameters(
				deltaX = parameters.floatValue(TransformParameterKeys.MOVE_X, 0f),
				deltaY = space.rowVertical(parameters.floatValue(space.verticalMoveKey, 0f)),
				factorX = 1f,
				factorY = 1f,
				rotationRadians = 0f,
			)

		MeshOperatorKind.Rotate ->
			TransformGestureParameters(
				deltaX = 0f,
				deltaY = 0f,
				factorX = 1f,
				factorY = 1f,
				rotationRadians = space.radiansOfRow(parameters.floatValue(TransformParameterKeys.ANGLE, 0f)),
			)

		MeshOperatorKind.Scale ->
			TransformGestureParameters(
				deltaX = 0f,
				deltaY = 0f,
				factorX = parameters.floatValue(TransformParameterKeys.SCALE_X, 1f),
				factorY = parameters.floatValue(space.verticalScaleKey, 1f),
				rotationRadians = 0f,
			)

		MeshOperatorKind.VertexSlide -> TransformGestureParameters.IDENTITY
	}

/**
 * The proportional rows the strip's rows carry, or null when the gesture registered none.
 *
 * @param List parameters The rows, possibly edited.
 * @return ProportionalRows? The rows' values, or null.
 */
fun proportionalRowsOf(parameters: List<OperatorParameter>): ProportionalRows? {
	if (parameters.none { parameter -> parameter.key == TransformParameterKeys.PROPORTIONAL }) {
		return null
	}
	val falloffKey = parameters.choiceValue(TransformParameterKeys.FALLOFF, ProportionalFalloff.Smooth.choiceKey)
	return ProportionalRows(
		enabled = parameters.booleanValue(TransformParameterKeys.PROPORTIONAL, false),
		falloff = ProportionalFalloff.entries.firstOrNull { falloff -> falloff.choiceKey == falloffKey } ?: ProportionalFalloff.Smooth,
		radius = parameters.floatValue(TransformParameterKeys.PROPORTIONAL_SIZE, MIN_PROPORTIONAL_RADIUS_WORLD),
		connectedOnly = parameters.booleanValue(TransformParameterKeys.CONNECTED_ONLY, false),
	)
}

/**
 * Re-derives [transform]'s proportional halos from its frozen positions under the rows' proportional
 * settings, when the rows carry any: the entries' influence and moved sets change with the radius,
 * curve, and flag.  For a rerun over a RETAINED capture, which is no longer live, so rewriting its
 * halos is safe.
 *
 * @param ModalTransformCapture transform  The frozen capture.
 * @param List                  parameters The rows, possibly edited.
 * @return ProportionalRows? The rows the halos were derived under, or null when the rows carry none
 *   (the capture is left as it was).
 */
fun rederiveProportionalHalos(transform: ModalTransformCapture, parameters: List<OperatorParameter>): ProportionalRows? {
	val rows = proportionalRowsOf(parameters) ?: return null
	val state = rows.asState()
	transform.applyProportional(state, state?.radiusWorld ?: 0f)
	return rows
}

/**
 * The strip's one row for a Vertex Slide: how far along its edge the vertex landed.
 *
 * @param Float factor The landed factor in [0, 1].
 * @return List The Factor row.
 */
fun slideParameters(factor: Float): List<OperatorParameter> =
	listOf(OperatorParameter.FloatParameter(TransformParameterKeys.SLIDE_FACTOR, TransformParameterKeys.SLIDE_FACTOR, factor, 0f, 1f, SLIDE_FACTOR_STEP))

/** The stable choice key of a falloff curve, as the Falloff row stores it. */
val ProportionalFalloff.choiceKey: String
	get() = name.lowercase()

/** The proportional rows: the flag, the curve, the size, and Connected Only. */
private fun proportionalParameters(proportional: ProportionalRows): List<OperatorParameter> =
	listOf(
		OperatorParameter.BooleanParameter(TransformParameterKeys.PROPORTIONAL, TransformParameterKeys.PROPORTIONAL, proportional.enabled),
		OperatorParameter.ChoiceParameter(
			TransformParameterKeys.FALLOFF,
			TransformParameterKeys.FALLOFF,
			proportional.falloff.choiceKey,
			ProportionalFalloff.entries.map { falloff -> ParameterChoice(falloff.choiceKey, TransformParameterKeys.FALLOFF_CHOICE_PREFIX + falloff.choiceKey) },
		),
		OperatorParameter.FloatParameter(
			TransformParameterKeys.PROPORTIONAL_SIZE,
			TransformParameterKeys.PROPORTIONAL_SIZE,
			proportional.radius,
			MIN_PROPORTIONAL_RADIUS_WORLD,
			MAX_PROPORTIONAL_RADIUS_WORLD,
			PROPORTIONAL_SIZE_STEP,
			ParameterUnit.Pixels,
		),
		OperatorParameter.BooleanParameter(TransformParameterKeys.CONNECTED_ONLY, TransformParameterKeys.CONNECTED_ONLY, proportional.connectedOnly),
	)

/** A Move row in the gesture space's pixel units. */
private fun moveRow(key: String, value: Float): OperatorParameter =
	OperatorParameter.FloatParameter(key, key, value, -TRANSFORM_MOVE_LIMIT, TRANSFORM_MOVE_LIMIT, unit = ParameterUnit.Pixels)

/** A Scale row. */
private fun scaleRow(key: String, value: Float): OperatorParameter =
	OperatorParameter.FloatParameter(key, key, value, TRANSFORM_SCALE_MIN, TRANSFORM_SCALE_MAX, TRANSFORM_SCALE_STEP)

/** The key of the vertical Move row: Z in world space, Y on the page. */
private val TransformRowSpace.verticalMoveKey: String
	get() =
		when (this) {
			TransformRowSpace.World -> TransformParameterKeys.MOVE_Z
			TransformRowSpace.UvDisplay -> TransformParameterKeys.MOVE_Y
		}

/** The key of the vertical Scale row: Z in world space, Y on the page. */
private val TransformRowSpace.verticalScaleKey: String
	get() =
		when (this) {
			TransformRowSpace.World -> TransformParameterKeys.SCALE_Z
			TransformRowSpace.UvDisplay -> TransformParameterKeys.SCALE_Y
		}

/**
 * The vertical component as the row reads it - and, being its own inverse, as the gesture reads a
 * row: world y is up and shown as-is; display y is flipped to read down like the page.
 */
private fun TransformRowSpace.rowVertical(value: Float): Float =
	when (this) {
		TransformRowSpace.World -> value
		TransformRowSpace.UvDisplay -> -value
	}

/** The angle row for a gesture rotation: degrees, with the page space's sign flip. */
private fun TransformRowSpace.rowDegrees(rotationRadians: Float): Float = rowVertical((rotationRadians * 180.0 / PI).toFloat())

/** The gesture rotation an angle row means: the inverse of [rowDegrees]. */
private fun TransformRowSpace.radiansOfRow(degrees: Float): Float = (rowVertical(degrees) * PI / 180.0).toFloat()