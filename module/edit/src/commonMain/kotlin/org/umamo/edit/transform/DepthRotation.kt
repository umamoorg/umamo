package org.umamo.edit.transform

import org.umamo.edit.DepthSurface
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sign
import kotlin.math.sqrt

/*
 * The depth rotation: a modal Rotate about the world X or Z axis instead of the view axis Y.  The art lives in the X/Z
 * plane, so the turn lifts each point into 3D at a depth, rotates it about the pivot, and drops the depth again -
 * nothing ever stores a Y.  The position arrays' second component is world Z (up), as everywhere in the 2D viewport;
 * depth is world Y, positive into the screen.  The pivot can sit at a depth of its own (pushed along Y), and the
 * surface decides how deep each point sits before the turn: Flat keeps the whole plane at depth 0, Cylinder bends it
 * around a cylinder along the rotation axis so every row (or column) curves alike, and Sphere lays it over a sphere
 * around the pivot so the middle of the selection travels further than its outline.
 */

/**
 * The axis a modal Rotate turns about.  Y is the view axis - the ordinary in-plane rotation every R starts with -
 * and X and Z are the world axes in the art's plane, which rotate the art in depth (see [MeshTransforms.rotateVerticesInDepth]).
 * The operation strip's Axis row keys an axis by its [choiceKey].
 */
enum class RotationAxis {
	Y,
	X,
	Z,
}

/** The stable key of a rotation axis: its name in lower case (y, x, z), the one spelling the Axis row stores. */
val RotationAxis.choiceKey: String
	get() = name.lowercase()

/** The stable key of a depth surface: its name in lower camel case (flat, cylinder, sphere), the one spelling the Surface row stores. */
val DepthSurface.choiceKey: String
	get() = name.replaceFirstChar { first -> first.lowercaseChar() }

/**
 * The depth offset of one point from the pivot before a depth rotation, along world Y (positive into the screen): Flat
 * puts every point on the art's plane, [pivotDepth] in front of the pivot; Cylinder puts it on the cylinder of radius
 * |[pivotDepth]| through the pivot along [axis], and Sphere on the sphere of that radius around the pivot - on the
 * viewer's side for a pivot pushed away (a negative depth mirrors it), and a point past the rim on the pivot's own plane.
 *
 * @param Float offsetX The point's offset from the pivot along world X.
 * @param Float offsetZ The point's offset from the pivot along world Z (the positions' second component).
 * @param Float pivotDepth The pivot's depth along world Y, positive into the screen.
 * @param DepthSurface surface How deep the points sit.
 * @param RotationAxis axis The axis the rotation goes about, which the Cylinder surface runs along.
 * @return Float The point's depth minus the pivot's.
 */
fun depthOffsetOf(offsetX: Float, offsetZ: Float, pivotDepth: Float, surface: DepthSurface, axis: RotationAxis): Float =
	when (surface) {
		DepthSurface.Flat -> {
			-pivotDepth
		}

		// The cylinder's cross-section reads only the offset across its axis: about Z the horizontal one, so every row
		// sits at the same depths; about X the vertical one.
		DepthSurface.Cylinder -> {
			val across =
				when (axis) {
					RotationAxis.Z -> offsetX
					RotationAxis.X -> offsetZ
					RotationAxis.Y -> 0f
				}

			onDepthRim(pivotDepth, across * across)
		}

		DepthSurface.Sphere -> {
			onDepthRim(pivotDepth, offsetX * offsetX + offsetZ * offsetZ)
		}
	}

/**
 * The depth offset of a point on a round surface of radius |[pivotDepth]| around the pivot, at [distanceSquared] from
 * its center line or point: on the viewer's side for a pushed pivot, and on the pivot's plane past the rim.
 *
 * @param Float pivotDepth The pivot's depth along world Y, the surface's signed radius.
 * @param Float distanceSquared The point's squared distance from the surface's center line or point.
 * @return Float The point's depth minus the pivot's.
 */
private fun onDepthRim(pivotDepth: Float, distanceSquared: Float): Float {
	val rimSquared = pivotDepth * pivotDepth - distanceSquared

	return if (rimSquared > 0f) -pivotDepth.sign * sqrt(rimSquared) else 0f
}

/** How far one wheel step pushes a depth rotation's pivot along world Y, in world px, whatever the zoom. */
const val PIVOT_DEPTH_STEP = 5f

/**
 * The pivot depth one wheel step moves [pivotDepth] to: the next multiple of [PIVOT_DEPTH_STEP] away from the viewer for
 * wheel up, toward the viewer for wheel down.  A depth typed off the step grid lands back on it, so stepping can always
 * return to 0.
 *
 * @param Float pivotDepth The pivot's depth along world Y, in world px.
 * @param Float steps The direction; only its sign is read (negative is wheel up), and the caller gathers a scroll into
 *   whole notches.
 * @return Float The stepped depth; [pivotDepth] unchanged for a zero scroll.
 */
fun pivotDepthSteppedFrom(pivotDepth: Float, steps: Float): Float {
	val gridIndex = pivotDepth / PIVOT_DEPTH_STEP

	return when {
		steps < 0f -> (floor(gridIndex) + 1f) * PIVOT_DEPTH_STEP
		steps > 0f -> (ceil(gridIndex) - 1f) * PIVOT_DEPTH_STEP
		else -> pivotDepth
	}
}