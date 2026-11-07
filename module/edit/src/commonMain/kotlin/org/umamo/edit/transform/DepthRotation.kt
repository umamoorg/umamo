package org.umamo.edit.transform

import kotlin.math.sign
import kotlin.math.sqrt

/*
 * The depth rotation: a modal Rotate about the world X or Z axis instead of the view axis Y.  The art lives in the X/Z
 * plane, so the turn lifts each point into 3D at a depth, rotates it about the pivot, and drops the depth again -
 * nothing ever stores a Y.  The position arrays' second component is world Z (up), as everywhere in the 2D viewport;
 * depth is world Y, positive into the screen.  The pivot can sit at a depth of its own (pushed along Y), and the
 * surface decides how deep each point sits before the turn: Flat keeps the whole plane at depth 0, Rounded lays the
 * points over a sphere around the pivot so the middle of the selection travels further than its outline.
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

/**
 * How deep each point sits before a depth rotation.  Flat keeps the art a plane, so the turn squashes it and slides it by
 * the pivot's depth; Rounded lays it over a sphere around the pivot, whose radius is the pivot's depth, so its middle
 * moves furthest - the look of a head turning.  The two agree at depth 0.
 */
enum class DepthSurface {
	Flat,
	Rounded,
}

/** The stable key of a rotation axis: its name in lower case (y, x, z), the one spelling the Axis row stores. */
val RotationAxis.choiceKey: String
	get() = name.lowercase()

/** The stable key of a depth surface: its name in lower camel case (flat, rounded), the one spelling the Surface row stores. */
val DepthSurface.choiceKey: String
	get() = name.replaceFirstChar { first -> first.lowercaseChar() }

/**
 * The depth offset of one point from the pivot before a depth rotation, along world Y (positive into the screen): Flat
 * puts every point on the art's plane, [pivotDepth] in front of the pivot; Rounded puts it on the sphere of radius
 * |[pivotDepth]| around the pivot, on the viewer's side for a pivot pushed away (a negative depth mirrors it), and a
 * point past the sphere's rim on the pivot's own plane.
 *
 * @param Float offsetX The point's offset from the pivot along world X.
 * @param Float offsetY The point's offset from the pivot along world Z (the positions' second component).
 * @param Float pivotDepth The pivot's depth along world Y, positive into the screen.
 * @param DepthSurface surface How deep the points sit.
 * @return Float The point's depth minus the pivot's.
 */
fun depthOffsetOf(offsetX: Float, offsetY: Float, pivotDepth: Float, surface: DepthSurface): Float =
	when (surface) {
		DepthSurface.Flat -> {
			-pivotDepth
		}

		DepthSurface.Rounded -> {
			val rimSquared = pivotDepth * pivotDepth - offsetX * offsetX - offsetY * offsetY

			if (rimSquared > 0f) -pivotDepth.sign * sqrt(rimSquared) else 0f
		}
	}