package org.umamo.edit

/**
 * The influence radius a deform brush starts with, in world units (canvas px).  A brush measures the
 * model, not the screen: the same radius covers the same part of the puppet at every zoom.
 */
const val DEFAULT_BRUSH_RADIUS_WORLD: Float = 120f

/** The smallest allowed brush radius, in world units. */
const val MIN_BRUSH_RADIUS_WORLD: Float = 1f

/** The largest allowed brush radius, in world units. */
const val MAX_BRUSH_RADIUS_WORLD: Float = 100_000f

/**
 * The strength a deform brush starts with: 1 carries the points under it exactly as far as the brush
 * travelled, and less carries them part of the way.
 */
const val DEFAULT_BRUSH_STRENGTH: Float = 1f

/** The smallest allowed brush strength. */
const val MIN_BRUSH_STRENGTH: Float = 0f

/** The largest allowed brush strength. */
const val MAX_BRUSH_STRENGTH: Float = 1f

/**
 * [radiusWorld] clamped into the allowed brush radius range - the one clamp every write of the radius goes
 * through (the wheel's resize, the operation strip's write-back, and a saved document's seed), so no path
 * can store a radius another would refuse.
 *
 * @param Float radiusWorld The requested radius in world units (canvas px).
 * @return Float The radius within [MIN_BRUSH_RADIUS_WORLD]..[MAX_BRUSH_RADIUS_WORLD].
 */
fun clampBrushRadius(radiusWorld: Float): Float = radiusWorld.coerceIn(MIN_BRUSH_RADIUS_WORLD, MAX_BRUSH_RADIUS_WORLD)

/**
 * [strength] clamped into the allowed brush strength range - the one clamp every write of the strength goes
 * through (the operation strip's write-back, and a saved document's seed), so no path can store a strength
 * another would refuse.
 *
 * @param Float strength The requested strength.
 * @return Float The strength within [MIN_BRUSH_STRENGTH]..[MAX_BRUSH_STRENGTH].
 */
fun clampBrushStrength(strength: Float): Float = strength.coerceIn(MIN_BRUSH_STRENGTH, MAX_BRUSH_STRENGTH)

/**
 * The deform brush's configuration: the falloff curve shaping its influence, the radius it reaches, and the
 * strength it carries the points under it with.  Transient session state, never part of
 * [EditorSnapshot]; the last configuration is remembered across off/on toggles and carried by a saved
 * document.
 *
 * @property ProportionalFalloff falloff The falloff curve, shared with proportional editing.
 * @property Float radiusWorld The influence radius, in world units (canvas px).
 * @property Float strength How much of the brush's travel a point under it takes, in [0, 1].
 */
data class BrushSettings(
	val falloff: ProportionalFalloff,
	val radiusWorld: Float,
	val strength: Float,
)

/** The configuration a session starts with: the smooth falloff at the default radius and full strength. */
val DEFAULT_BRUSH_SETTINGS: BrushSettings = BrushSettings(ProportionalFalloff.Smooth, DEFAULT_BRUSH_RADIUS_WORLD, DEFAULT_BRUSH_STRENGTH)