package org.umamo.render.puppet

import org.umamo.format.raster.RasterImage
import org.umamo.render.SupersampledSurface
import org.umamo.render.ViewportCamera
import org.umamo.render.device.RenderDevice
import org.umamo.render.device.RenderTarget

/**
 * Renders an image in tiles and stitches them into one.
 *
 * The image renders at [SNAPSHOT_SUPERSAMPLE] into a surface of its own and is resolved and read back
 * synchronously, in tiles of at most [tileEdge] output pixels (and never past what the device can
 * allocate).  A tile is the same camera re-centered on its own rectangle, so the pieces meet exactly:
 * tile edges fall on whole output pixels, and the resolve never averages across one.
 *
 * Backend-neutral, and it knows nothing of what is drawn: [renderTile] draws one tile, and this decides
 * only where each tile sits and where its pixels land.
 *
 * Must run on the render thread with the device's context current, between frames.
 *
 * @param RenderDevice   device         The backend to render and read back through.
 * @param ViewportCamera camera         The view: the world point at the image's center and the output
 *   pixels per world unit.
 * @param Int            width          The image width in pixels.
 * @param Int            height         The image height in pixels.
 * @param Int            tileEdge       The largest tile edge in output pixels.
 * @param Function       shouldContinue Asked before each tile; false abandons the capture.
 * @param Function       renderTile     Draws one tile: the target to draw into, the tile's camera (already
 *   carrying the supersample in its zoom), and the framebuffer width and height to draw at.
 * @return RasterImage? The pixels, top row first, or null when the capture was abandoned.
 */
internal fun captureSnapshot(
	device: RenderDevice,
	camera: ViewportCamera,
	width: Int,
	height: Int,
	tileEdge: Int,
	shouldContinue: () -> Boolean,
	renderTile: (RenderTarget, ViewportCamera, Int, Int) -> Unit,
): RasterImage? {
	require(width > 0 && height > 0) { "a snapshot needs a positive size, got ${width}x$height" }
	val maximumTile = minOf(tileEdge, device.maxRenderTargetSize() / SNAPSHOT_SUPERSAMPLE).coerceAtLeast(1)
	val surface = SupersampledSurface(device, SNAPSHOT_SUPERSAMPLE)
	val stitched = ByteArray(width * height * 4)

	try {
		for (tileTop in 0 until height step maximumTile) {
			val tileHeight = minOf(maximumTile, height - tileTop)

			for (tileLeft in 0 until width step maximumTile) {
				if (!shouldContinue()) {
					return null
				}

				val tileWidth = minOf(maximumTile, width - tileLeft)
				// The tile's center in whole-image pixels, carried back into world units: x runs right
				// in both, while image rows run down and world z runs up.
				val tileCenterX = camera.centerX + (tileLeft + tileWidth / 2f - width / 2f) / camera.zoom
				val tileCenterY = camera.centerY - (tileTop + tileHeight / 2f - height / 2f) / camera.zoom
				val tileCamera = ViewportCamera(tileCenterX, tileCenterY, camera.zoom * SNAPSHOT_SUPERSAMPLE)
				val drawTarget = surface.ensure(tileWidth, tileHeight)
				renderTile(drawTarget, tileCamera, tileWidth * SNAPSHOT_SUPERSAMPLE, tileHeight * SNAPSHOT_SUPERSAMPLE)
				surface.resolve()
				val tile = device.readPixels(surface.resolveTarget, tileWidth, tileHeight)

				for (tileRow in 0 until tileHeight) {
					tile.rgba.copyInto(
						stitched,
						((tileTop + tileRow) * width + tileLeft) * 4,
						tileRow * tileWidth * 4,
						(tileRow + 1) * tileWidth * 4,
					)
				}
			}
		}
	} finally {
		surface.dispose()
	}

	return RasterImage(width, height, stitched)
}