package org.umamo.ui.transform

import org.umamo.edit.Pose
import org.umamo.edit.transform.MeshBounds
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.drawablesByPartSubtree

/**
 * The world bounds of everything [partId] holds at [pose], nested parts included: the box around its
 * drawables as the viewport draws them.  A part has no geometry of its own, so this is its placement, the way
 * a drawable's world bounds are its.  The drawables are captured through one resolver, so the deformer
 * chain bakes once for the part rather than once per drawable.
 *
 * @param PuppetModel model The document model.
 * @param Pose pose The shown pose.
 * @param PartId partId The part to measure.
 * @return MeshBounds? The bounds (x across, y up, the panel's Z), or null when nothing the part holds is
 *   on screen: it is empty, or every drawable in it is mesh-less or under a hidden ancestor.
 */
internal fun partWorldBounds(model: PuppetModel, pose: Pose, partId: PartId): MeshBounds? {
	val drawableIds = model.drawablesByPartSubtree()[partId].orEmpty()

	if (drawableIds.isEmpty()) {
		return null
	}

	var minX = Float.POSITIVE_INFINITY
	var minY = Float.POSITIVE_INFINITY
	var maxX = Float.NEGATIVE_INFINITY
	var maxY = Float.NEGATIVE_INFINITY

	for (geometry in captureDrawableWorlds(model, pose, drawableIds)) {
		val world = geometry.world
		var slotIndex = 0

		while (slotIndex + 1 < world.size) {
			minX = minOf(minX, world[slotIndex])
			maxX = maxOf(maxX, world[slotIndex])
			minY = minOf(minY, world[slotIndex + 1])
			maxY = maxOf(maxY, world[slotIndex + 1])
			slotIndex += 2
		}
	}

	if (minX > maxX) {
		return null
	}

	return MeshBounds((minX + maxX) / 2f, (minY + maxY) / 2f, maxX - minX, maxY - minY)
}