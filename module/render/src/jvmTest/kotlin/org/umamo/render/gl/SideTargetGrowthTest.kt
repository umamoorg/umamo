package org.umamo.render.gl

import org.umamo.format.raster.RasterImage
import org.umamo.render.GridColors
import org.umamo.render.PuppetTextures
import org.umamo.render.ViewportCamera
import org.umamo.render.device.RenderTargetSpec
import org.umamo.render.device.TextureFormat
import org.umamo.render.puppet.PuppetRenderer
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * Pins the grow-only side targets (mask coverage + composite snapshot/pool): one renderer rendering
 * 96 -> 64 -> 96 must produce, at each size, pixels identical to a fresh renderer whose side targets
 * are exactly that size.  The 64 render is the load-bearing case - the long-lived renderer's side
 * targets stay at 96-capacity, so it exercises the capacity-sized screenTexSize divisor (mask AND
 * composite sampling) and the used-height flip in the destination-snapshot resolve.  The scene has a
 * clip-masked quad and a Darken (5.3 extended blend) quad so both the mask path and the composite
 * path are on screen.
 */
class SideTargetGrowthTest {
	private val largeSize = 96
	private val smallSize = 64
	private val paramA = ParameterId("A")
	private val maskId = DrawableId("mask_source")
	private val maskedId = DrawableId("masked_quad")
	private val darkenId = DrawableId("darken_quad")

	// A mid-grey backdrop so the Darken composite has a destination to blend against.
	private val greyGrid = GridColors(0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f)

	private fun restGrid(positions: FloatArray): KeyformGrid<MeshDeltaForm> =
		KeyformGrid(
			listOf(KeyformAxis(paramA, floatArrayOf(0f))),
			listOf(KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(positions.size)))),
		)

	/**
	 * A two-triangle quad drawable spanning the given world rectangle.
	 *
	 * @param DrawableId id The drawable id.
	 * @param BlendMode blendMode The color blend mode.
	 * @param List<DrawableId> maskedBy The clipping mask sources, empty for none.
	 * @param FloatArray corners The world rect as (minX, minY, maxX, maxY).
	 * @return Drawable The drawable.
	 */
	private fun quad(id: DrawableId, blendMode: BlendMode, maskedBy: List<DrawableId>, corners: FloatArray): Drawable {
		val positions =
			floatArrayOf(
				corners[0],
				corners[1],
				corners[2],
				corners[1],
				corners[0],
				corners[3],
				corners[2],
				corners[3],
			)
		return Drawable(
			id = id,
			name = id.raw,
			parentDeformerId = null,
			blendMode = blendMode,
			maskedBy = maskedBy,
			mesh = DrawableMesh(positions, FloatArray(positions.size), intArrayOf(0, 1, 2, 1, 3, 2)),
			geometryGrid = restGrid(positions),
		)
	}

	/** The test scene: a mask source, a quad clipped by it, and a Darken (extended-blend) quad. */
	private fun model(): PuppetModel {
		// Off-center, partial-viewport rects so a wrong screen-space divisor shifts visible edges.
		val maskSource = quad(maskId, BlendMode.Normal, emptyList(), floatArrayOf(-24f, -24f, 8f, 8f))
		val masked = quad(maskedId, BlendMode.Normal, listOf(maskId), floatArrayOf(-32f, -32f, 32f, 32f))
		val darken = quad(darkenId, BlendMode.Darken, emptyList(), floatArrayOf(-8f, -8f, 24f, 24f))
		return PuppetModel(
			parameters = listOf(Parameter(paramA, "A", -1f, 1f, 0f)),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = listOf(maskSource, masked, darken),
			rootChildren = listOf(OrgChild.Drawable(maskSource.id), OrgChild.Drawable(masked.id), OrgChild.Drawable(darken.id)),
			rootPartId = null,
			canvasWidth = 0f,
			canvasHeight = 0f,
			worldOriginX = 0f,
			worldOriginY = 0f,
		)
	}

	@Test
	fun shrinkAndRegrowMatchExactSizeRenders() {
		requireHeadlessGl("[side-target-growth]")
		val device = GlRenderDevice()
		val longLived = newRenderer(device)

		// Establish the 96-capacity side targets, and pin the scene is not degenerate: some red
		// byte must differ from the 0.5-grey backdrop (alpha bytes are excluded - they are 255
		// everywhere and would satisfy a whole-array check vacuously).
		val firstLarge = renderAt(device, longLived, largeSize)
		val drewSomething = (firstLarge.rgba.indices step 4).any { redIndex -> (firstLarge.rgba[redIndex].toInt() and 0xFF) != 0x80 }
		assertTrue(drewSomething, "the scene draws over the backdrop")

		// The load-bearing case: 96-capacity side targets rendering a 64 viewport.
		val shrunk = renderAt(device, longLived, smallSize)
		val exactSmall = renderAt(device, newRenderer(device), smallSize)
		assertContentEquals(exactSmall.rgba, shrunk.rgba, "a 64 render off 96-capacity side targets matches an exact-size renderer")

		// Regrow: back at capacity, still identical to a fresh exact-size renderer.
		val regrown = renderAt(device, longLived, largeSize)
		val exactLarge = renderAt(device, newRenderer(device), largeSize)
		assertContentEquals(exactLarge.rgba, regrown.rgba, "the regrown 96 render matches an exact-size renderer")
	}

	/** Builds and initializes a renderer for the shared test scene on [device]. */
	private fun newRenderer(device: GlRenderDevice): PuppetRenderer {
		val renderer = PuppetRenderer(model(), PuppetTextures(emptyList(), emptyMap(), premultipliedAlpha = false), device)
		renderer.initGl()
		renderer.setGrid(greyGrid, 100f, 10)
		renderer.setCamera(ViewportCamera(0f, 0f, 1f))
		renderer.setPose(emptyMap())
		return renderer
	}

	/**
	 * Renders one frame at [size] x [size] into a fresh exact-size main target and reads it back.
	 *
	 * @param GlRenderDevice device The shared device.
	 * @param PuppetRenderer renderer The renderer under test.
	 * @param Int size The square viewport extent in pixels.
	 * @return RasterImage The frame, top row first.
	 */
	private fun renderAt(device: GlRenderDevice, renderer: PuppetRenderer, size: Int): RasterImage {
		val target = device.createRenderTarget(RenderTargetSpec(size, size, TextureFormat.Rgba8, sampled = true))
		renderer.render(target, size, size)
		val image = device.readPixels(target)
		device.destroyRenderTarget(target)
		return image
	}
}
