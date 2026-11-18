package org.umamo.edit.transform

import org.umamo.edit.BrushMode
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
 * The two modes weigh the travel differently.  Drag GRABS what is under the brush when the stroke starts:
 * every point inside that first circle keeps the weight it had there and travels with the brush from then
 * on, so a point under the brush's centre follows the cursor exactly and one near its edge follows in part.
 * A point the brush only passes over on its way is NOT grabbed - it keeps its place - so a fast drag over
 * a mesh does not sweep up everything it flew past.
 *
 * Flow SMARS what the brush passes over: every stretch of the stroke leaves the points it went over where
 * it carried them, which is the original brush, and the points it went over stay deformed.
 *
 * The weight is the falloff curve over the distance from a point to the brush - the same curve, and the
 * same normalized distance, proportional editing weights a vertex by.  A point outside the brush's
 * radius weighs nothing, so it stays exactly where it was.
 *
 * The strength scales the whole travel, so the same stroke can be re-run at a new strength without the
 * stamps changing.
 *
 * @param BrushStroke stroke The stroke, whose stamps carry the path and whose last centre is where the
 *   brush is now.
 * @param FloatArray points The points in world space, interleaved x, z.
 * @param Float strength How much of the brush's travel a point under it takes.
 * @param ProportionalFalloff falloff The falloff curve shaping the weights.
 * @param BrushMode mode How the brush moves the points it covers.
 * @return FloatArray The per-point world offsets, interleaved x, z, the same length as [points].
 */
fun brushPointOffsets(
	stroke: BrushStroke,
	points: FloatArray,
	strength: Float,
	falloff: ProportionalFalloff,
	mode: BrushMode,
): FloatArray {
	val offsets = FloatArray(points.size)

	when (mode) {
		BrushMode.Drag -> dragPointOffsets(stroke, points, offsets, falloff)
		BrushMode.Flow -> flowPointOffsets(stroke, points, offsets, falloff)
	}

	for (componentIndex in offsets.indices) {
		offsets[componentIndex] *= strength
	}

	return offsets
}

/**
 * The offsets a Flow stroke gives its points: every stretch carries the points it went over, weighted by
 * the falloff over the distance from a point to the stretch's own centre.
 *
 * @param BrushStroke stroke The stroke, whose stamps carry the path.
 * @param FloatArray points The points in world space, interleaved x, z.
 * @param FloatArray offsets The offsets to fill, interleaved x, z.
 * @param ProportionalFalloff falloff The falloff curve shaping the weights.
 */
private fun flowPointOffsets(
	stroke: BrushStroke,
	points: FloatArray,
	offsets: FloatArray,
	falloff: ProportionalFalloff,
) {
	val stamps = stroke.stamps
	val pointCount = points.size / 2

	for (stretchIndex in 1 until stamps.size) {
		val start = stamps[stretchIndex - 1]
		val end = stamps[stretchIndex]
		val travelX = end.centreX - start.centreX
		val travelZ = end.centreZ - start.centreZ

		if (travelX == 0f && travelZ == 0f) {
			continue
		}

		if (start.radiusWorld <= 0f) {
			continue
		}

		for (pointIndex in 0 until pointCount) {
			val offsetX = points[pointIndex * 2] - start.centreX
			val offsetZ = points[pointIndex * 2 + 1] - start.centreZ
			val normalizedDistance = sqrt(offsetX * offsetX + offsetZ * offsetZ) / start.radiusWorld
			val weight = proportionalWeight(falloff, normalizedDistance, pointIndex)

			if (weight > 0f) {
				offsets[pointIndex * 2] += travelX * weight
				offsets[pointIndex * 2 + 1] += travelZ * weight
			}
		}
	}
}

/**
 * The offsets a Drag stroke gives its points: what the brush covered when the stroke began is GRABBED, and
 * each grabbed point travels with the brush from that first circle to where the brush is now, scaled by the
 * weight it had there.
 *
 * The grab is taken from the stroke's FIRST stamp alone, so the brush's later travel never picks up a point
 * it merely flew past - that is what makes a fast drag over a mesh deform only what it grabbed.
 *
 * @param BrushStroke stroke The stroke, whose first stamp is where the brush began and whose last centre is
 *   where it is now.
 * @param FloatArray points The points in world space, interleaved x, z.
 * @param FloatArray offsets The offsets to fill, interleaved x, z.
 * @param ProportionalFalloff falloff The falloff curve shaping the weights.
 */
private fun dragPointOffsets(
	stroke: BrushStroke,
	points: FloatArray,
	offsets: FloatArray,
	falloff: ProportionalFalloff,
) {
	val stamps = stroke.stamps
	val grab = stamps.firstOrNull() ?: return

	if (grab.radiusWorld <= 0f) {
		return
	}

	val pointCount = points.size / 2
	val travelX = stroke.lastCentreX - grab.centreX
	val travelZ = stroke.lastCentreZ - grab.centreZ

	for (pointIndex in 0 until pointCount) {
		val offsetX = points[pointIndex * 2] - grab.centreX
		val offsetZ = points[pointIndex * 2 + 1] - grab.centreZ
		val normalizedDistance = sqrt(offsetX * offsetX + offsetZ * offsetZ) / grab.radiusWorld
		val weight = proportionalWeight(falloff, normalizedDistance, pointIndex)

		if (weight > 0f) {
			offsets[pointIndex * 2] = travelX * weight
			offsets[pointIndex * 2 + 1] = travelZ * weight
		}
	}
}