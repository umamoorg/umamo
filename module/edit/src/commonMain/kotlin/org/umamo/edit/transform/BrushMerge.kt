package org.umamo.edit.transform

import org.umamo.edit.BrushMode
import org.umamo.edit.BrushSettings
import org.umamo.edit.ProportionalFalloff
import kotlin.math.sqrt

/*
 * The brush's merge over the points it covers, folded as a stroke grows rather than redone from the start: the
 * merge kept between the pointer events of one stroke, so a long stroke costs what its last stretch costs
 * rather than what all of them cost.
 */

/**
 * The brush's merge over one set of points, kept between the pointer events of a stroke.
 *
 * The drag mode weighs its grab once, against the circle the brush had when the stroke began, and every later
 * event only moves what it grabbed; the flow mode's smear is a sum over the stretches the stroke has laid down,
 * so a stretch is weighed once and never weighed again.  Either way a stroke folded as it grows gives what one
 * merge over the whole stroke gives, which the tests pin.
 *
 * @param FloatArray points The points in world space, interleaved x, z.
 */
class BrushMerge(
	private val points: FloatArray,
) {
	private val pointCount = points.size / 2

	/** The drag mode's grab: each point's weight in the circle the brush had when the stroke began. */
	private val grabWeights = FloatArray(pointCount)

	/** The flow mode's smear: the stretches weighed and summed, before the strength scales them. */
	private val smeared = FloatArray(points.size)

	/** The travel the drag mode's grab carries, and the flow mode's sum, before the strength scales them. */
	private val carried = FloatArray(points.size)

	/** The offsets handed out, the carried travel scaled by the strength. */
	private val offsets = FloatArray(points.size)

	/** The falloff the weights were weighed with, so a change of it starts the merge over. */
	private var falloff: ProportionalFalloff? = null

	/** The mode the merge is folding, so a change of it starts the merge over. */
	private var mode: BrushMode = BrushMode.Drag

	/** Whether the grab has been weighed yet. */
	private var grabbed = false

	/** How many stamps the smear has weighed, so a stretch is weighed once. */
	private var foldedStamps = 1

	/**
	 * The offsets the stroke has given the points so far, in world units, interleaved x, z.
	 *
	 * The array is the merge's own and the next call overwrites it, so a caller reads it before folding again.
	 *
	 * @param BrushStroke stroke The stroke, as it has grown.
	 * @param BrushSettings settings The brush's configuration.
	 * @return FloatArray The per-point world offsets, the same length as the points.
	 */
	fun update(stroke: BrushStroke, settings: BrushSettings): FloatArray {
		if (settings.falloff != falloff || settings.mode != mode) {
			reset(settings)
		}

		when (mode) {
			BrushMode.Drag -> dragTravel(stroke, settings)
			BrushMode.Flow -> flowTravel(stroke, settings)
		}

		for (componentIndex in offsets.indices) {
			offsets[componentIndex] = carried[componentIndex] * settings.strength
		}

		return offsets
	}

	/**
	 * Starts the merge over: the weights the falloff or the mode gave are void, and the smear's sum is void.
	 *
	 * @param BrushSettings settings The brush's configuration the merge restarts under.
	 */
	private fun reset(settings: BrushSettings) {
		falloff = settings.falloff
		mode = settings.mode
		grabbed = false
		foldedStamps = 1
		smeared.fill(0f)
	}

	/**
	 * The drag mode's travel: what the brush covered when the stroke began travels with the brush, and the
	 * weight each point had there travels with it.
	 *
	 * @param BrushStroke stroke The stroke, as it has grown.
	 * @param BrushSettings settings The brush's configuration.
	 */
	private fun dragTravel(stroke: BrushStroke, settings: BrushSettings) {
		val grab = stroke.stamps.firstOrNull()

		if (grab == null) {
			carried.fill(0f)

			return
		}

		if (!grabbed) {
			grabWeights.fill(0f)

			if (grab.radiusWorld > 0f) {
				for (pointIndex in 0 until pointCount) {
					val offsetX = points[pointIndex * 2] - grab.centreX
					val offsetZ = points[pointIndex * 2 + 1] - grab.centreZ
					val normalizedDistance = sqrt(offsetX * offsetX + offsetZ * offsetZ) / grab.radiusWorld
					grabWeights[pointIndex] = proportionalWeight(settings.falloff, normalizedDistance, pointIndex)
				}
			}

			grabbed = true
		}

		val travelX = stroke.lastCentreX - grab.centreX
		val travelZ = stroke.lastCentreZ - grab.centreZ

		for (pointIndex in 0 until pointCount) {
			carried[pointIndex * 2] = travelX * grabWeights[pointIndex]
			carried[pointIndex * 2 + 1] = travelZ * grabWeights[pointIndex]
		}
	}

	/**
	 * The flow mode's travel: every stretch the stroke has laid down carries the points it went over, and the
	 * stretches laid down since the last fold are the only ones weighed now.
	 *
	 * @param BrushStroke stroke The stroke, as it has grown.
	 * @param BrushSettings settings The brush's configuration.
	 */
	private fun flowTravel(stroke: BrushStroke, settings: BrushSettings) {
		val stamps = stroke.stamps
		val from = if (foldedStamps < 1) 1 else foldedStamps

		for (endIndex in from until stamps.size) {
			val start = stamps[endIndex - 1]
			val end = stamps[endIndex]
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
				val weight = proportionalWeight(settings.falloff, normalizedDistance, pointIndex)

				if (weight > 0f) {
					smeared[pointIndex * 2] += travelX * weight
					smeared[pointIndex * 2 + 1] += travelZ * weight
				}
			}
		}

		if (stamps.size > foldedStamps) {
			foldedStamps = stamps.size
		}

		smeared.copyInto(carried)
	}
}