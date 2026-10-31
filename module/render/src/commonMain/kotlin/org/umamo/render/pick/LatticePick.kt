package org.umamo.render.pick

import org.umamo.render.ViewportCamera
import org.umamo.render.puppet.LatticePoints
import org.umamo.runtime.model.DeformerId

/*
 * Picking a warp by its drawn lattice, in screen space through the area's camera, so the hit radii are
 * on-screen pixels at every zoom, centered on the points and lines as they are drawn: a click lands on a
 * lattice exactly where its dots and grid lines are, and every control point is a target, not only the corners.
 */

/**
 * The warps whose lattice lies under the pointer, best first: a lattice with a control point within
 * [pointRadiusPx] of the pointer ranks above one with only a grid line within [lineRadiusPx], each tier nearer
 * first, and a tie goes to the warp later in [lattices]' order - in the model's order, the child drawn over its
 * parent.  A line takes a narrower band than a point so the art between a dense lattice's lines stays clickable.
 *
 * @param Map<DeformerId, LatticePoints> lattices The lattices that can be picked, in the model's deformer order.
 * @param Float pointerX The pointer x in screen pixels.
 * @param Float pointerY The pointer y in screen pixels.
 * @param Float pointRadiusPx How near a control point must be, in screen pixels.
 * @param Float lineRadiusPx How near a grid line must be, in screen pixels.
 * @param ViewportCamera camera The projecting camera.
 * @param Int viewportWidth The viewport width in pixels.
 * @param Int viewportHeight The viewport height in pixels.
 * @return List<DeformerId> The hit warps, best first; empty when none is near.
 */
fun latticeHitsAt(
	lattices: Map<DeformerId, LatticePoints>,
	pointerX: Float,
	pointerY: Float,
	pointRadiusPx: Float,
	lineRadiusPx: Float,
	camera: ViewportCamera,
	viewportWidth: Int,
	viewportHeight: Int,
): List<DeformerId> {
	val hits = ArrayList<LatticeHit>()
	var modelIndex = 0

	for ((deformerId, lattice) in lattices) {
		val pointHit = nearestVertex(lattice.world, pointerX, pointerY, pointRadiusPx, camera, viewportWidth, viewportHeight)

		if (pointHit != null) {
			hits.add(LatticeHit(deformerId, POINT_TIER, pointHit.distancePx, modelIndex))
		} else {
			nearestEdge(lattice.world, lattice.layout.edgeEndpoints, pointerX, pointerY, lineRadiusPx, camera, viewportWidth, viewportHeight)?.let { edgeHit ->
				hits.add(LatticeHit(deformerId, EDGE_TIER, edgeHit.distancePx, modelIndex))
			}
		}

		modelIndex++
	}

	hits.sortWith(compareBy<LatticeHit> { hit -> hit.tier }.thenBy { hit -> hit.distancePx }.thenByDescending { hit -> hit.modelIndex })

	return hits.map { hit -> hit.deformerId }
}

/**
 * Each lattice's center in world space, the mean of its control points: what a box or circle select tests,
 * as a drawable's centroid is for its art.
 *
 * @param Map<DeformerId, LatticePoints> lattices The lattices.
 * @return Map<DeformerId, FloatArray> A two-element [x, y] center per lattice, in the same order.
 */
fun latticeCenters(lattices: Map<DeformerId, LatticePoints>): Map<DeformerId, FloatArray> = centroidsOf(lattices.mapValues { (_, lattice) -> lattice.world })

/** The rank of a hit on a control point, ahead of one on a grid line. */
private const val POINT_TIER = 0

/** The rank of a hit on a grid line alone. */
private const val EDGE_TIER = 1

/**
 * One lattice under the pointer, with what ranks it.
 *
 * @property DeformerId deformerId The warp.
 * @property Int tier [POINT_TIER] or [EDGE_TIER].
 * @property Float distancePx The pointer's distance to the nearest hit point or line, in screen pixels.
 * @property Int modelIndex The warp's place in the model's order, the tie-break.
 */
private class LatticeHit(
	val deformerId: DeformerId,
	val tier: Int,
	val distancePx: Float,
	val modelIndex: Int,
)