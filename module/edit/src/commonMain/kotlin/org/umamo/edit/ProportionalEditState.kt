package org.umamo.edit

/** The influence radius proportional editing starts with, in world units (canvas px). */
const val DEFAULT_PROPORTIONAL_RADIUS_WORLD: Float = 200f

/** The smallest allowed influence radius, in world units. */
const val MIN_PROPORTIONAL_RADIUS_WORLD: Float = 1f

/** The largest allowed influence radius, in world units. */
const val MAX_PROPORTIONAL_RADIUS_WORLD: Float = 100_000f

/**
 * The geometric factor one scroll-wheel step multiplies (or divides) the influence radius by during a
 * modal transform, so a step stays proportional at any scale (Blender resizes its proportional circle
 * the same way).
 */
const val PROPORTIONAL_RADIUS_STEP_FACTOR: Float = 1.1f

/**
 * [radiusWorld] clamped into the allowed proportional radius range - the one clamp every write of the
 * radius goes through (a mid-gesture scroll, the operation strip's write-back, and a saved document's
 * seed), so no path can store a radius another would refuse.
 *
 * @param Float radiusWorld The requested radius in world units (canvas px).
 * @return Float The radius within [MIN_PROPORTIONAL_RADIUS_WORLD]..[MAX_PROPORTIONAL_RADIUS_WORLD].
 */
fun clampProportionalRadius(radiusWorld: Float): Float = radiusWorld.coerceIn(MIN_PROPORTIONAL_RADIUS_WORLD, MAX_PROPORTIONAL_RADIUS_WORLD)

/**
 * The falloff curve shaping how a vertex's influence fades from 1 (at the selection) to 0 (at the
 * radius edge) - Blender's proportional-editing falloff set.  Every consumer (the saved editor state,
 * the commands, the operation strip) keys a curve by its name, never its position.
 */
enum class ProportionalFalloff {
	Smooth,
	Sphere,
	Root,
	InverseSquare,
	Sharp,
	Linear,
	Constant,
	Random,
}

/**
 * The proportional-editing configuration while it is enabled: the falloff curve, the influence
 * radius, and whether influence spreads only through CONNECTED geometry (Blender's Connected Only -
 * distances measured along mesh edges instead of straight-line, so the halo never leaps a gap to
 * nearby-but-unconnected geometry).  Transient session state like the tool latches (deliberately NOT
 * part of EditorSnapshot); the last configuration is remembered across off/on toggles, the
 * circle-select radius pattern.
 *
 * @property ProportionalFalloff falloff The falloff curve.
 * @property Float radiusWorld The influence radius, in world units (canvas px).
 * @property Boolean connectedOnly True to measure influence along mesh edges (geodesic) instead of
 *   straight-line distance.
 */
data class ProportionalEditState(
	val falloff: ProportionalFalloff,
	val radiusWorld: Float,
	val connectedOnly: Boolean = false,
)

/** The proportional configuration a session starts with: the smooth falloff at the default radius. */
val DEFAULT_PROPORTIONAL_EDIT_STATE: ProportionalEditState = ProportionalEditState(ProportionalFalloff.Smooth, DEFAULT_PROPORTIONAL_RADIUS_WORLD)