package org.umamo.render

import org.umamo.format.art.LayerBounds
import org.umamo.format.atlas.AtlasPackReserve
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.applyUvAffine
import org.umamo.runtime.model.placementAffine
import org.umamo.runtime.model.storedToArtAffineForTile
import kotlin.math.ceil
import kotlin.math.floor

/*
 * The placement gizmo's page-space bookkeeping: the mesh reach each tile must keep clear, and the
 * footprint a placement occupies on its page, for the overlap and off-page warnings a drag shows.
 *
 * Session-free and GL-free.  It lives beside the derivation rather than in the editing module because
 * a footprint is a derivation question (which pixels will the compose write) and needs the trim the
 * derivation draws, and because the repack and the drag must reserve by ONE rule - a reserve computed
 * two ways would let a hand-nudged tile land where the next repack refuses to put it.
 */

/**
 * Per tile, the union of its bound drawables' mesh reach in the tile's own art frame, rounded out to
 * whole pixels - what a pack must keep clear BEYOND the opaque pixels.
 *
 * The pixels alone do not bound what samples a tile: an art mesh rings outside the opaque region
 * (Erica's reach up to 58px past their trim, and past the raster itself), so a pack spaced by
 * opaque bounds puts one tile's mesh footprint over its neighbor's art - the mesh then renders the
 * neighbor's pixels wherever they are opaque.  The official editor's own packing keeps mesh
 * footprints disjoint, and this is how the repack matches it.
 *
 * A drawable whose mapping will not resolve (a placement naming a page the atlas lacks) contributes
 * nothing here; if it is bound, the repack's refusal pass aborts before the reserve could have
 * mattered.
 *
 * @param PuppetModel model The model whose bindings to measure.
 * @return Map Each bound tile's reserve, absent when a tile has no measurable mesh.
 */
public fun meshReserveByTile(model: PuppetModel): Map<AtlasTileId, AtlasPackReserve> {
	val boundsByTile = HashMap<AtlasTileId, FloatArray>()
	for (drawable in model.drawables) {
		val tileId = drawable.atlasTileId ?: continue
		val tile = model.atlas.tileById[tileId] ?: continue
		val uvs = drawable.mesh?.uvs ?: continue
		if (uvs.size < 2) {
			continue
		}
		val storedToArt = model.atlas.storedToArtAffineForTile(tileId) ?: continue
		val artUvs = applyUvAffine(uvs, storedToArt)
		val bounds = boundsByTile.getOrPut(tileId) { floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE) }
		var componentIndex = 0
		while (componentIndex + 1 < artUvs.size) {
			val artX = artUvs[componentIndex] * tile.width
			val artY = artUvs[componentIndex + 1] * tile.height
			bounds[0] = minOf(bounds[0], artX)
			bounds[1] = minOf(bounds[1], artY)
			bounds[2] = maxOf(bounds[2], artX)
			bounds[3] = maxOf(bounds[3], artY)
			componentIndex += 2
		}
	}
	return boundsByTile.mapValues { (_, bounds) ->
		AtlasPackReserve(
			left = floor(bounds[0]).toInt(),
			top = floor(bounds[1]).toInt(),
			right = ceil(bounds[2]).toInt(),
			bottom = ceil(bounds[3]).toInt(),
		)
	}
}

/**
 * A placement's axis-aligned extent on its page, in continuous page pixels: the bounds of the
 * tile-local rectangle (the trim, widened by the mesh reserve) through the placement.
 *
 * A rotated tile's footprint is its bounding box, so a test over one over-warns for a turned tile and
 * never under-warns - the first-cut approximation the gizmo's warnings run on.
 *
 * @property Float left   The left edge.
 * @property Float top    The top edge.
 * @property Float right  The right edge, exclusive.
 * @property Float bottom The bottom edge, exclusive.
 */
data class PlacementFootprint(
	val left: Float,
	val top: Float,
	val right: Float,
	val bottom: Float,
) {
	/**
	 * This footprint grown by [margin] on every side - the gutter a neighbor must stay clear of.
	 *
	 * @param Float margin The growth in page pixels.
	 * @return PlacementFootprint The grown footprint.
	 */
	fun expanded(margin: Float): PlacementFootprint = PlacementFootprint(left - margin, top - margin, right + margin, bottom + margin)

	/**
	 * True when this footprint and [other] share any area (touching edges do not count).
	 *
	 * @param PlacementFootprint other The footprint to test against.
	 * @return Boolean Whether they overlap.
	 */
	fun overlaps(other: PlacementFootprint): Boolean =
		left < other.right && other.left < right && top < other.bottom && other.top < bottom

	/**
	 * True when any part of this footprint lies outside a page of the given size.
	 *
	 * @param Int pageWidth  The page width in pixels.
	 * @param Int pageHeight The page height in pixels.
	 * @return Boolean Whether the footprint spills off the page.
	 */
	fun exceeds(pageWidth: Int, pageHeight: Int): Boolean = left < 0f || top < 0f || right > pageWidth || bottom > pageHeight
}

/**
 * The footprint [placement] occupies: its tile's [trim] widened by [reserve] (when the tile has a
 * measurable mesh), carried through the placement onto the page.
 *
 * @param AtlasPlacement    placement The tile's placement.
 * @param LayerBounds       trim      The tile's opaque bounds, raster-local.
 * @param AtlasPackReserve? reserve   The tile's mesh reach, raster-local, or null when none.
 * @return PlacementFootprint The page-space bounds.
 */
fun placementFootprint(placement: AtlasPlacement, trim: LayerBounds, reserve: AtlasPackReserve?): PlacementFootprint {
	var localLeft = trim.left.toFloat()
	var localTop = trim.top.toFloat()
	var localRight = (trim.left + trim.width).toFloat()
	var localBottom = (trim.top + trim.height).toFloat()
	if (reserve != null) {
		localLeft = minOf(localLeft, reserve.left.toFloat())
		localTop = minOf(localTop, reserve.top.toFloat())
		localRight = maxOf(localRight, reserve.right.toFloat())
		localBottom = maxOf(localBottom, reserve.bottom.toFloat())
	}
	val affine = placementAffine(placement)
	var left = Float.POSITIVE_INFINITY
	var top = Float.POSITIVE_INFINITY
	var right = Float.NEGATIVE_INFINITY
	var bottom = Float.NEGATIVE_INFINITY
	for (cornerIndex in 0 until 4) {
		val cornerX = if (cornerIndex and 1 == 0) localLeft else localRight
		val cornerY = if (cornerIndex and 2 == 0) localTop else localBottom
		val pageX = affine[0] * cornerX + affine[1] * cornerY + affine[2]
		val pageY = affine[3] * cornerX + affine[4] * cornerY + affine[5]
		left = minOf(left, pageX)
		top = minOf(top, pageY)
		right = maxOf(right, pageX)
		bottom = maxOf(bottom, pageY)
	}
	return PlacementFootprint(left, top, right, bottom)
}