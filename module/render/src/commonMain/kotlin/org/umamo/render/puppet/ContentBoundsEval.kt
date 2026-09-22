package org.umamo.render.puppet

import org.umamo.render.ContentBounds
import org.umamo.render.eval.DeformedGeometry
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel

/**
 * The side of the square an empty model with no canvas frames, in world units: room to start drawing in,
 * around the world origin, at a zoom where the grid reads as a grid.
 */
private const val EMPTY_FRAME_EXTENT = 1000f

/**
 * Computes the world-space extent of a deformed pose's shown drawables - the framing `view.fit` frames to.
 *
 * Backend-neutral: a bounding box over already-deformed positions, so every renderer backend shares it
 * rather than re-deriving it beside its own GPU calls.
 *
 * Hidden drawables are excluded deliberately: a hidden full-canvas guide image or background must not
 * stretch the framing of a view it is not drawn in.
 *
 * @param DeformedGeometry geometry The CPU-evaluated pose to measure.
 * @param Set<DrawableId>  shownIds The drawables actually drawn (the resolved visibility cascade).
 * @return ContentBounds? The extent, with each span floored at 1 so a degenerate model never divides by
 *   zero downstream; null when no shown drawable has a vertex, so there is nothing to measure.
 * @note Null rather than a sentinel rectangle on purpose.  The sweep starts from the float extremes, and a
 *   rectangle left there frames a camera at the edge of float range, where the grid has no precision
 *   left to draw with - a blank viewport.  That is the FIRST frame of a new, empty document, and what
 *   Fit does once every drawable is hidden, so the caller must choose what to frame instead
 *   ([emptyContentBoundsOf]).
 */
internal fun contentBoundsOf(geometry: DeformedGeometry, shownIds: Set<DrawableId>): ContentBounds? {
	var loX = Float.MAX_VALUE
	var loY = Float.MAX_VALUE
	var hiX = -Float.MAX_VALUE
	var hiY = -Float.MAX_VALUE
	for ((drawableId, world) in geometry.worldPositions) {
		// A hidden full-canvas guide image / background must not stretch the framing it isn't drawn in.
		if (drawableId !in shownIds) {
			continue
		}
		var coordIndex = 0
		while (coordIndex < world.size) {
			loX = minOf(loX, world[coordIndex])
			hiX = maxOf(hiX, world[coordIndex])
			loY = minOf(loY, world[coordIndex + 1])
			hiY = maxOf(hiY, world[coordIndex + 1])
			coordIndex += 2
		}
	}
	if (loX > hiX || loY > hiY) {
		return null
	}
	return ContentBounds(loX, loY, maxOf(hiX - loX, 1f), maxOf(hiY - loY, 1f))
}

/**
 * What a view frames when the model shows nothing to measure: the document's canvas, or - for a model that
 * carries no canvas - a square around the world origin.
 *
 * The canvas is where the art will go, so it is the honest frame for a new, empty document and for a rig
 * whose every drawable is hidden.  World space is canvas x with canvas y negated, so a canvas spanning
 * [0, width] x [0, height] occupies x in [0, width] and y in [-height, 0] here.
 *
 * @param PuppetModel model The model being framed.
 * @return ContentBounds The rectangle to fit.
 */
internal fun emptyContentBoundsOf(model: PuppetModel): ContentBounds =
	if (model.canvasWidth > 0f && model.canvasHeight > 0f) {
		ContentBounds(0f, -model.canvasHeight, model.canvasWidth, model.canvasHeight)
	} else {
		ContentBounds(model.worldOriginX - EMPTY_FRAME_EXTENT / 2f, model.worldOriginZ - EMPTY_FRAME_EXTENT / 2f, EMPTY_FRAME_EXTENT, EMPTY_FRAME_EXTENT)
	}