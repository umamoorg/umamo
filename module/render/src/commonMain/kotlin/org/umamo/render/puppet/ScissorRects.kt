package org.umamo.render.puppet

import org.umamo.render.device.ScissorRect
import org.umamo.render.device.WorldToNdc
import kotlin.math.ceil
import kotlin.math.floor

// Padding (in ON-SCREEN pixels, scaled to framebuffer pixels by the pixel scale handed to scissorRectOf)
// added around a composite layer's conservative bounds before it becomes a scissor rect - a small guard so
// bilinear atlas sampling at the mesh edge and float rounding in the world->pixel map can never clip a
// fringe pixel the un-scissored path would keep.
private const val SCISSOR_PAD_PX = 2

/**
 * Maps world bounds to a padded, viewport-clamped scissor rectangle (top-left-origin pixels),
 * or null when the bounds land entirely off the viewport.
 *
 * Backend-neutral - it decides WHERE a layer's work is confined, in pixels, and nothing about how a
 * backend applies a scissor.
 *
 * @param PosedAabb  bounds         The world-space bounds.
 * @param WorldToNdc affine         The camera affine.
 * @param Int        viewportWidth  The viewport width in pixels.
 * @param Int        viewportHeight The viewport height in pixels.
 * @param Float      pixelScale     Framebuffer pixels per on-screen pixel (1 = native, 2 = a 2x supersample).
 * @return ScissorRect? The scissor rect, or null when empty.
 */
internal fun scissorRectOf(
	bounds: PosedAabb,
	affine: WorldToNdc,
	viewportWidth: Int,
	viewportHeight: Int,
	pixelScale: Float,
): ScissorRect? {
	val ndcXa = affine.scaleX * bounds.minX + affine.offsetX
	val ndcXb = affine.scaleX * bounds.maxX + affine.offsetX
	val ndcYa = affine.scaleY * bounds.minY + affine.offsetY
	val ndcYb = affine.scaleY * bounds.maxY + affine.offsetY
	val pixelLeft = (minOf(ndcXa, ndcXb) * 0.5f + 0.5f) * viewportWidth
	val pixelRight = (maxOf(ndcXa, ndcXb) * 0.5f + 0.5f) * viewportWidth
	// NDC +Y is up; top-left-origin rows count down from the top.
	val pixelTop = (0.5f - maxOf(ndcYa, ndcYb) * 0.5f) * viewportHeight
	val pixelBottom = (0.5f - minOf(ndcYa, ndcYb) * 0.5f) * viewportHeight
	// The fringe guard is a constant ON-SCREEN size, so it scales with the supersample factor, or a
	// 1x-sufficient pad under-covers the fringe at 2x+ and clips an edge texel.
	val pad = SCISSOR_PAD_PX * pixelScale
	val viewportWidthF = viewportWidth.toFloat()
	val viewportHeightF = viewportHeight.toFloat()
	// Pad and clamp in FLOAT space before the Int conversion.  A warp-extrapolated vertex can push a
	// pixel edge far past Int range; Float.toInt() would then saturate and the -/+ pad would wrap via
	// unchecked Int overflow to the WRONG end of the clamp, vanishing the layer instead of covering
	// the viewport.  Float coerceIn clamps ±Infinity correctly; a NaN edge never reaches here
	// (deformedWorldBounds publishes no bound for a non-finite vertex, so render() keeps the full
	// viewport path).
	val left = (floor(pixelLeft) - pad).coerceIn(0f, viewportWidthF).toInt()
	val right = (ceil(pixelRight) + pad).coerceIn(0f, viewportWidthF).toInt()
	val top = (floor(pixelTop) - pad).coerceIn(0f, viewportHeightF).toInt()
	val bottom = (ceil(pixelBottom) + pad).coerceIn(0f, viewportHeightF).toInt()

	if (right <= left || bottom <= top) {
		return null
	}

	return ScissorRect(left, top, right - left, bottom - top)
}

/**
 * Intersects two scissor rects, treating null as "the whole viewport".  A composite layer's own
 * bounds ([first]) are always contained in its parent layer's ([second]) by construction, so a
 * non-empty intersection is expected; an empty intersection (only reachable if that containment ever
 * failed) falls back to [first], the layer's own rect, rather than null - so it can never silently
 * widen to the whole viewport.
 *
 * @param ScissorRect? first  The inner rect (the composite layer's own bounds), or null.
 * @param ScissorRect? second The outer rect (the enclosing span's scissor), or null.
 * @return ScissorRect? The intersection, or [first] on an empty intersection, or null when both are null.
 */
internal fun intersectScissor(first: ScissorRect?, second: ScissorRect?): ScissorRect? {
	if (first == null) {
		return second
	}

	if (second == null) {
		return first
	}

	val left = maxOf(first.x, second.x)
	val top = maxOf(first.y, second.y)
	val right = minOf(first.x + first.width, second.x + second.width)
	val bottom = minOf(first.y + first.height, second.y + second.height)

	if (right <= left || bottom <= top) {
		return first
	}

	return ScissorRect(left, top, right - left, bottom - top)
}