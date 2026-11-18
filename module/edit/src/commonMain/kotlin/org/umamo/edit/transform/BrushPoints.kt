package org.umamo.edit.transform

import kotlin.math.ceil
import kotlin.math.sqrt

/*
 * The deform brush's own arithmetic: the log of stamps a stroke lays down.  The merge that turns that log
 * into the world offset every point takes lives beside it, in BrushMerge.kt.
 *
 * The points are whatever the brush is over: a warp's lattice control points, or a mesh's vertices.
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