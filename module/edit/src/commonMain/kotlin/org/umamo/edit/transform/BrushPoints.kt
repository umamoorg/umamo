package org.umamo.edit.transform

import org.umamo.edit.ProportionalFalloff
import kotlin.math.ceil
import kotlin.math.sqrt

/*
 * The deform brush's own arithmetic: the log of stamps a stroke lays down, and the merge that turns that
 * log into the world offset every point takes.  Both are pure data and pure math, so the live preview and
 * the committed stroke run the same function and cannot drift apart - which is what lets the operation
 * strip re-run a finished stroke at a new strength.
 *
 * The points are whatever the brush is over: a warp's lattice control points, or a mesh's vertices.
 *
 * The falloff weights come from ProportionalEditing.kt's proportionalWeight, so the brush and
 * proportional editing share Blender's eight curves rather than growing a second set.
 */

/**
 * One stamp of a deform brush: where the brush was, and how wide it was there.  The radius rides on the
 * stamp because the wheel can resize the brush mid-stroke, and a stretch laid down at one size is not the
 * stretch laid down at another.
 *
 * @property Float centreX The stamp's world-space x.
 * @property Float centreZ The stamp's world-space z (up).
 * @property Float radiusWorld The brush's radius there, in world units (canvas px).
 */
data class BrushStamp(
	val centreX: Float,
	val centreZ: Float,
	val radiusWorld: Float,
)

/**
 * The log of a brush stroke: every stamp the brush laid down, in order, and where the brush was when it
 * last did.  A stroke is a log rather than a merged delta because the operation strip re-runs it: changing
 * the strength or the falloff has to re-apply the same stamps under new weights, which a delta with the
 * weights already baked in cannot do.
 *
 * @property List<BrushStamp> stamps The stamps, in the order the brush laid them down.
 * @property Float lastCentreX Where the brush was when it last stamped, in world-space x.
 * @property Float lastCentreZ Where the brush was when it last stamped, in world-space z (up).
 */
data class BrushStroke(
	val stamps: List<BrushStamp>,
	val lastCentreX: Float,
	val lastCentreZ: Float,
) {
	/**
	 * This stroke with the brush moved to ([centreX], [centreZ]) and the stamps laid on the way there.
	 *
	 * The walk is in steps of at most half the radius, so a fast drag leaves no gap between the stamps and
	 * the brush never skips over a stretch of the model.  An empty stroke starts at the point it is given.
	 *
	 * @param Float centreX The brush's new world-space x.
	 * @param Float centreZ The brush's new world-space z (up).
	 * @param Float radiusWorld The brush's radius there, in world units (canvas px).
	 * @return BrushStroke The stroke with the stamps laid down.
	 */
	fun stampedTo(centreX: Float, centreZ: Float, radiusWorld: Float): BrushStroke {
		if (stamps.isEmpty()) {
			return BrushStroke(listOf(BrushStamp(centreX, centreZ, radiusWorld)), centreX, centreZ)
		}

		val deltaX = centreX - lastCentreX
		val deltaZ = centreZ - lastCentreZ
		val distance = sqrt(deltaX * deltaX + deltaZ * deltaZ)
		val stepLength = radiusWorld / 2f
		val stepCount = if (stepLength <= 0f) 0 else ceil(distance / stepLength).toInt()

		if (stepCount <= 0) {
			return copy(lastCentreX = centreX, lastCentreZ = centreZ)
		}

		val laid = ArrayList<BrushStamp>(stepCount)

		for (stepIndex in 1..stepCount) {
			val fraction = stepIndex.toFloat() / stepCount
			laid.add(BrushStamp(lastCentreX + deltaX * fraction, lastCentreZ + deltaZ * fraction, radiusWorld))
		}

		return BrushStroke(stamps + laid, centreX, centreZ)
	}

	/**
	 * This stroke with every stamp's radius replaced by [radiusWorld] - a re-run of the same path under a
	 * different brush size, which is what the operation strip's radius row asks for.
	 *
	 * @param Float radiusWorld The radius every stamp takes, in world units (canvas px).
	 * @return BrushStroke The stroke at the given radius.
	 */
	fun withRadius(radiusWorld: Float): BrushStroke = copy(stamps = stamps.map { stamp -> stamp.copy(radiusWorld = radiusWorld) })

	companion object {
		/**
		 * A stroke that has laid no stamp down yet.
		 *
		 * @return BrushStroke The empty stroke.
		 */
		fun empty(): BrushStroke = BrushStroke(emptyList(), 0f, 0f)

		/**
		 * A stroke that starts at the brush's first stamp.
		 *
		 * @param Float centreX The brush's first world-space x.
		 * @param Float centreZ The brush's first world-space z (up).
		 * @param Float radiusWorld The brush's radius there, in world units (canvas px).
		 * @return BrushStroke The stroke.
		 */
		fun starting(centreX: Float, centreZ: Float, radiusWorld: Float): BrushStroke =
			BrushStroke(listOf(BrushStamp(centreX, centreZ, radiusWorld)), centreX, centreZ)
	}
}

/**
 * The world offset every point takes from a brush stroke: the brush's travel, weighted by the falloff over
 * each point's distance to the brush.
 *
 * Each stretch between two stamps carries the points the brush covered at the start of it, and the weight
 * is the falloff curve over the distance from a point to that stamp's centre - the same curve, and the same
 * normalized distance, proportional editing weights a vertex by.  A point outside the brush's radius weighs
 * nothing, so it stays exactly where it was.
 *
 * The strength scales the whole travel, so the same stroke can be re-run at a new strength without the
 * stamps changing.
 *
 * @param List<BrushStamp> stamps The stroke's stamps, in order.
 * @param FloatArray points The points in world space, interleaved x, z.
 * @param Float strength How much of the brush's travel a point under it takes.
 * @param ProportionalFalloff falloff The falloff curve shaping the weights.
 * @return FloatArray The per-point world offsets, interleaved x, z, the same length as [points].
 */
fun brushPointOffsets(
	stamps: List<BrushStamp>,
	points: FloatArray,
	strength: Float,
	falloff: ProportionalFalloff,
): FloatArray {
	val offsets = FloatArray(points.size)
	val pointCount = points.size / 2

	for (stampIndex in 1 until stamps.size) {
		val previous = stamps[stampIndex - 1]
		val current = stamps[stampIndex]
		val deltaX = current.centreX - previous.centreX
		val deltaZ = current.centreZ - previous.centreZ

		if (deltaX == 0f && deltaZ == 0f) {
			continue
		}

		if (previous.radiusWorld <= 0f) {
			continue
		}

		for (pointIndex in 0 until pointCount) {
			val pointX = points[pointIndex * 2]
			val pointZ = points[pointIndex * 2 + 1]
			val distanceX = pointX - previous.centreX
			val distanceZ = pointZ - previous.centreZ
			val normalizedDistance = sqrt(distanceX * distanceX + distanceZ * distanceZ) / previous.radiusWorld
			val weight = proportionalWeight(falloff, normalizedDistance, pointIndex)

			if (weight > 0f) {
				offsets[pointIndex * 2] += deltaX * weight
				offsets[pointIndex * 2 + 1] += deltaZ * weight
			}
		}
	}

	for (componentIndex in offsets.indices) {
		offsets[componentIndex] *= strength
	}

	return offsets
}