package org.umamo.render.puppet

import org.umamo.render.DecodedImage
import org.umamo.render.WorldAxisColors
import org.umamo.render.device.AxisLineUniforms
import org.umamo.render.device.FragmentUniforms
import org.umamo.render.device.GpuTexture
import org.umamo.render.device.GridUniforms
import org.umamo.render.device.RenderDevice
import org.umamo.render.device.RenderPassEncoder
import org.umamo.render.device.TextureFilter
import org.umamo.render.device.TextureFormat
import org.umamo.render.device.WorldToNdc

/**
 * How many on-demand underlay images stay uploaded at once.  Small on purpose: a UV editor shows one
 * layer at a time, so this covers a few areas plus the layer they were just looking at, and bounds
 * GPU memory against a document carrying hundreds of layers.
 */
private const val UNDERLAY_TEXTURE_CACHE_SIZE = 4

/**
 * What is drawn behind and instead of the puppet: the grid backdrop, the world-origin axis lines, and
 * the flat image underlay a UV-editor area shows.
 *
 * It holds no view settings.  The grid's colors, spacing, and anchor arrive ready-built with each draw,
 * so the caller that owns them stays the one place they are set.  What it does hold is the underlay
 * texture cache.
 *
 * Render thread only.  Constructing it touches no device; every other call must run with the device's
 * context current.
 *
 * @property RenderDevice  device    The backend to draw through.
 * @property DrawPipelines pipelines The grid, axis, and underlay pipelines.
 */
internal class BackdropEncoder(
	private val device: RenderDevice,
	private val pipelines: DrawPipelines,
) {
	// Underlay images uploaded on demand (the UV editor's source-layer view), keyed by image identity and
	// insertion-ordered so eviction drops the oldest. Unlike the atlas pages, which upload and free as one
	// whole page set (at init, or wholesale on a page swap), these arrive and are created or destroyed one
	// at a time across the renderer's life - as the source-artwork textures also are.
	private val underlayTextures = LinkedHashMap<DecodedImage, GpuTexture>()

	// Reused per underlay draw rather than allocated. Render-thread only.
	private val fragmentScratch = FragmentUniforms()

	/**
	 * Fills an open pass with the grid backdrop.
	 *
	 * @param RenderPassEncoder pass The open pass.
	 * @param GridUniforms      grid The grid's inputs.
	 */
	fun encodeGrid(pass: RenderPassEncoder, grid: GridUniforms) {
		pass.setPipeline(pipelines.grid)
		pass.drawGrid(grid)
	}

	/**
	 * Draws the world-origin axis lines into an open pass.  The axes sit between the backdrop and the
	 * drawables, reading as part of the canvas.
	 *
	 * @param RenderPassEncoder pass         The open pass.
	 * @param WorldToNdc        affine       The camera affine.
	 * @param Float             worldOriginX The world origin's x, where the vertical axis stands.
	 * @param Float             worldOriginZ The world origin's z, where the horizontal axis lies.
	 */
	fun encodeWorldAxes(pass: RenderPassEncoder, affine: WorldToNdc, worldOriginX: Float, worldOriginZ: Float) {
		val originNdcX = affine.scaleX * worldOriginX + affine.offsetX
		val originNdcY = affine.scaleY * worldOriginZ + affine.offsetY
		val axisColors = WorldAxisColors.Classic
		pass.setPipeline(pipelines.axis)
		pass.drawAxisLine(AxisLineUniforms(originNdcY, vertical = false, axisColors.xRed, axisColors.xGreen, axisColors.xBlue))
		pass.drawAxisLine(AxisLineUniforms(originNdcX, vertical = true, axisColors.zRed, axisColors.zGreen, axisColors.zBlue))
	}

	/**
	 * Fills an open pass with the flat underlay both UV scenes share: the themed grid backdrop (bounded by
	 * the shown surface when the grid carries one), then the image as a single textured quad at the world
	 * origin.  A null image or handle paints the grid alone.  The caller owns the frame and the pass, so
	 * what a scene draws over its surface lands in the same pass.
	 *
	 * The quad samples through the same premultiplied fragment shader the puppet uses, so an underlay
	 * matches the puppet's texel rendering exactly.
	 *
	 * @param RenderPassEncoder pass   The open pass on the area's target.
	 * @param DecodedImage?     image  The image whose extent the quad takes, or null.
	 * @param GpuTexture?       handle The uploaded texture for [image], or null.
	 * @param GridUniforms      grid   The grid's inputs, carrying the camera affine and the viewport size.
	 */
	fun encodeUnderlay(pass: RenderPassEncoder, image: DecodedImage?, handle: GpuTexture?, grid: GridUniforms) {
		encodeGrid(pass, grid)
		if (image != null && handle != null) {
			pass.setPipeline(pipelines.atlasPage)
			pass.setCamera(grid.worldToNdc, grid.viewportWidth, grid.viewportHeight)
			fragmentScratch.reset()
			fragmentScratch.useTexture = true
			pass.drawAtlasPage(handle, image.width.toFloat(), image.height.toFloat(), fragmentScratch)
		}
	}

	/**
	 * The uploaded texture for an underlay image, uploading it on first sight.
	 *
	 * Keyed on image IDENTITY, not content: the store hands out the same decoded instance for a given
	 * layer, so identity is both correct and free.  The cache is deliberately tiny - a UV editor shows
	 * one layer at a time and only a handful of areas can exist - and evicts in insertion order, which
	 * costs a re-upload in the pathological case and bounds GPU memory in every other.  Rendering the
	 * whole document's layers at once is a different problem with a different answer (paging or
	 * rebatching), and it belongs to the viewport's layer display mode, not here.
	 *
	 * Render thread only: it creates GPU resources, so it must run with the context current.
	 *
	 * @param DecodedImage image The image to upload.
	 * @return GpuTexture The texture handle.
	 */
	fun underlayTextureFor(image: DecodedImage): GpuTexture {
		underlayTextures[image]?.let { existing ->
			return existing
		}
		while (underlayTextures.size >= UNDERLAY_TEXTURE_CACHE_SIZE) {
			val evicted = underlayTextures.keys.first()
			underlayTextures.remove(evicted)?.let { handle -> device.destroyTexture(handle) }
		}
		val created = device.createTexture(image.width, image.height, TextureFormat.Rgba8, TextureFilter.Linear, image.rgba)
		underlayTextures[image] = created
		return created
	}

	/**
	 * Frees every cached underlay texture.  Must run with the device's context current.
	 *
	 * The underlay cache is created and destroyed across the renderer's life rather than uploaded once,
	 * so letting it die with the context is not enough: an engine that outlives one renderer would leak
	 * everything the previous one had admitted.
	 */
	fun dispose() {
		for (texture in underlayTextures.values) {
			device.destroyTexture(texture)
		}
		underlayTextures.clear()
	}
}