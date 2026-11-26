package org.umamo.runtime.model

/*
 * The geometry half of a split keyform: the only channel whose payload is per-vertex / per-control-point,
 * the only one the GPU consumes, and the only one whose out-of-range pose HIDES its entity.  That is why
 * geometry stays a separate typed field on each owner rather than another FormChannel entry - a scalar
 * track can never be handed to the delta-texture bake by mistake, because it would not type-check.
 *
 * These mirror the bundled MeshForm / WarpForm / RotationForm minus their scalar and color channels, which
 * moved to ChannelGrids.  The bundled classes stay as the import intermediate and the blend-shape payload.
 */

/**
 * A drawable keyform's geometry: per-vertex position deltas (interleaved x,y) relative to the mesh's
 * keyform-space base (DrawableMesh.localPositions).
 *
 * Stored as deltas rather than absolute positions to match the GPU vertex-shader morph `p = base + Σ wᵢ·Δᵢ`
 * - the delta table is exactly what the shader texel-fetches per active corner.  The base is in the
 * keyforms' own space, so each delta is a difference within one space, which float32 holds.
 */
class MeshDeltaForm(val positionDeltas: FloatArray)

/**
 * A warp deformer keyform's geometry: the ABSOLUTE FFD lattice control-point positions (interleaved x,y), and how
 * deep each point sits.
 *
 * Absolute, not deltas: a warp source carries no separate rest lattice to be relative to, so these forms
 * are the only place the lattice geometry exists, and a warp with no keyforms has no lattice at all - the
 * evaluator gives it no world, so nothing under it is placed.
 *
 * The depth is editing data only: the deformation is 2D, so nothing that evaluates or exports the lattice reads it.
 * It lets the editing tools that bend and turn the lattice in 3D carry the third axis from one operation to the next.
 *
 * @property FloatArray controlPoints The control points, interleaved x, y, in the warp's parent space.
 * @property LatticeDepth depth How deep each control point sits; flat unless a 3D edit gave it depth.
 */
class WarpLatticeForm(
	val controlPoints: FloatArray,
	val depth: LatticeDepth = LatticeDepth.Flat,
)

/**
 * How deep a warp lattice's control points sit along world Y, in world px from the art plane, positive into the
 * screen.  Each lattice owns its own depth: a child warp does not inherit its parent's, and a parent's 2D scale does
 * not scale it.
 */
sealed interface LatticeDepth {
	/** Every control point on the art plane: depth 0. */
	data object Flat : LatticeDepth

	/**
	 * One depth per control point.
	 *
	 * @property FloatArray values The depths, one per control point in the lattice's point order.
	 */
	class PerPoint(val values: FloatArray) : LatticeDepth
}

/**
 * The depth of control point [pointIndex]: 0 on a flat lattice, and 0 for an index past the stored values.
 *
 * @param Int pointIndex The control point.
 * @return Float Its depth in world px.
 */
fun LatticeDepth.depthAt(pointIndex: Int): Float =
	when (this) {
		LatticeDepth.Flat -> 0f
		is LatticeDepth.PerPoint -> values.getOrElse(pointIndex) { 0f }
	}

/** Whether every control point lies on the art plane: flat, or a depth of all 0. */
val LatticeDepth.isFlat: Boolean
	get() =
		when (this) {
			LatticeDepth.Flat -> true
			is LatticeDepth.PerPoint -> values.all { value -> value == 0f }
		}

/**
 * The depths of [pointCount] control points as a fresh array, a flat lattice's all 0.
 *
 * @param Int pointCount How many control points the lattice has.
 * @return FloatArray One depth per control point.
 */
fun LatticeDepth.valuesFor(pointCount: Int): FloatArray = FloatArray(pointCount) { pointIndex -> depthAt(pointIndex) }

/**
 * The depth [values] describe: flat when every value is 0, so a lattice laid back on the art plane carries nothing.
 *
 * @param FloatArray values One depth per control point.
 * @return LatticeDepth The depth.
 */
fun latticeDepthOf(values: FloatArray): LatticeDepth = if (values.all { value -> value == 0f }) LatticeDepth.Flat else LatticeDepth.PerPoint(values)

/**
 * Whether two lattices' depths are exactly equal over [pointCount] control points - bit-equal values, a flat lattice
 * reading as all 0 - the depth half of an exact form comparison.
 *
 * @param LatticeDepth left The first depth.
 * @param LatticeDepth right The second depth.
 * @param Int pointCount How many control points the lattices have.
 * @return Boolean True when every point sits at the same depth.
 */
fun latticeDepthsEqual(left: LatticeDepth, right: LatticeDepth, pointCount: Int): Boolean {
	if (left === right || (left is LatticeDepth.Flat && right is LatticeDepth.Flat)) {
		return true
	}

	return left.valuesFor(pointCount).contentEquals(right.valuesFor(pointCount))
}

/**
 * A rotation deformer keyform's geometry: the ABSOLUTE pivot transform captured at one grid cell.
 *
 * The reflection flags are [FormChannel.FLIP_X] / [FormChannel.FLIP_Y] channels rather than fields here,
 * because they snap to the floor cell rather than blending and so do not belong beside values that
 * interpolate.
 */
class RotationPivotForm(
	val originX: Float,
	val originY: Float,
	val angle: Float,
	val scale: Float,
)