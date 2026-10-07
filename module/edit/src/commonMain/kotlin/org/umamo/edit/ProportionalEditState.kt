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
 * The falloff curve shaping how a vertex's influence fades from 1 (at the selection) to 0 (at the
 * radius edge) - Blender's proportional-editing falloff set, minus the randomized ones.
 *
 * プロポーショナル編集の減衰カーブの種類（Blender と同じ）。
 */
enum class ProportionalFalloff {
	Smooth,
	Sphere,
	Root,
	Sharp,
	Linear,
	Constant,
}

/**
 * The proportional-editing configuration while it is enabled: the falloff curve, the influence
 * radius, and whether influence spreads only through CONNECTED geometry (Blender's Connected Only -
 * distances measured along mesh edges instead of straight-line, so the halo never leaps a gap to
 * nearby-but-unconnected geometry).  Transient session state like the tool latches (deliberately NOT
 * part of EditorSnapshot); the last configuration is remembered across off/on toggles, the
 * circle-select radius pattern.
 *
 * プロポーショナル編集の設定（減衰カーブ、影響半径、接続のみ）。オン・オフをまたいで記憶される一時状態。
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