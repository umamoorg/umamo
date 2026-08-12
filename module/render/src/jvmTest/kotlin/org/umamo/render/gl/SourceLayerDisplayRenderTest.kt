package org.umamo.render.gl

import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import org.umamo.render.DecodedImage
import org.umamo.render.DrawableLayerDraw
import org.umamo.render.LayerDrawPlan
import org.umamo.render.LayerRasterBatch
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
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Proves the puppet actually displays from SOURCE ARTWORK rather than the packed atlas, and keeps
 * doing so across an unrelated edit.
 *
 * The probe samples a red atlas; its artwork is a green image of its own.  Switching the display
 * source must turn the drawn art green without moving it - the mapping is carried by a uv affine, not
 * by moving geometry.
 *
 * The second half is the regression the whole uniform approach exists for.  The obvious alternative -
 * re-uploading each mesh's UV buffer on the switch - breaks against the renderer's diff, which
 * compares UV arrays by REFERENCE identity: an edit that leaves the arrays untouched produces no
 * re-upload, and one that touches them rewrites the atlas-frame values, so the display would
 * silently revert mid-session.  So this pushes an unrelated Keep edit through updateModel while
 * displaying from artwork and asserts the frame is unchanged.
 *
 * Self-skips in a display-less environment (no GL context), like [UvReuploadTest].
 */
class SourceLayerDisplayRenderTest {
	private val viewportSize = 200
	private val paramA = ParameterId("A")
	private val probeId = DrawableId("LayerProbe")

	// A 120x120 quad centered at the origin; at zoom 1 it covers pixels ~40..160 in the 200x200 viewport.
	private val quadPositions = floatArrayOf(-60f, -60f, 60f, -60f, -60f, 60f, 60f, 60f)
	private val quadIndices = intArrayOf(0, 1, 2, 1, 3, 2)

	// Sampled well inside the image so a filtered lookup never straddles an edge.
	private val quadUvs = floatArrayOf(0.05f, 0.95f, 0.95f, 0.95f, 0.05f, 0.05f, 0.95f, 0.05f)

	// The same quad reaching half its own width past the art on every side, which is the shape a real
	// drawable has: an art mesh rings outside the opaque region, so its coordinates leave [0, 1].  The art
	// therefore covers the middle 50% of each axis - a quarter of the quad's pixels.
	private val overhangUvs = floatArrayOf(-0.5f, 1.5f, 1.5f, 1.5f, -0.5f, -0.5f, 1.5f, -0.5f)
	private val overhangArtAreaFraction = 0.25f

	private fun solidImage(red: Int, green: Int, size: Int = 8): DecodedImage {
		val rgba = ByteArray(size * size * 4)
		for (pixel in rgba.indices step 4) {
			rgba[pixel] = red.toByte()
			rgba[pixel + 1] = green.toByte()
			rgba[pixel + 2] = 0
			rgba[pixel + 3] = 0xFF.toByte()
		}
		return DecodedImage(rgba, size, size)
	}

	private fun probeModel(uvs: FloatArray = quadUvs): PuppetModel {
		val drawable =
			Drawable(
				id = probeId,
				name = "LayerProbe",
				parentDeformerId = null,
				blendMode = BlendMode.Normal,
				maskedBy = emptyList(),
				mesh = DrawableMesh(quadPositions.copyOf(), uvs.copyOf(), quadIndices),
				// One zero-delta keyform so the drawable is keyed; the base mesh alone drives its shape.
				geometryGrid =
					KeyformGrid(
						listOf(KeyformAxis(paramA, floatArrayOf(0f))),
						listOf(KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(quadPositions.size)))),
					),
			)
		return PuppetModel(
			parameters = listOf(Parameter(paramA, "A", -1f, 1f, 0f)),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = listOf(drawable),
			rootChildren = listOf(OrgChild.Drawable(drawable.id)),
			rootPartId = null,
		)
	}

	/** The mapping pointing the probe at its own green artwork, with an identity affine. */
	private fun artworkPlan(imageSize: Int = 8): LayerDrawPlan =
		LayerDrawPlan(
			drawsByDrawableId = mapOf(probeId.raw to DrawableLayerDraw("green", floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f))),
			layerByteCostByKey = mapOf("green" to imageSize.toLong() * imageSize.toLong() * 4L),
		)

	/** The decoded pixels answering that mapping. */
	private fun artworkBatch(imageSize: Int = 8): LayerRasterBatch =
		LayerRasterBatch(rastersByLayerKey = mapOf("green" to solidImage(red = 0x00, green = 0xFF, size = imageSize)))

	/**
	 * Pushes the mapping and then its pixels, the way the producer does.
	 *
	 * The renderer only uploads what it has asked for, so the plan must land before the batch - which is
	 * also the ordering the engine's render loop enforces.
	 */
	private fun PuppetRenderer.displayFromArtwork(imageSize: Int = 8) {
		setSourceLayerPlan(artworkPlan(imageSize))
		deliverSourceLayerRasters(artworkBatch(imageSize))
	}

	/**
	 * The same model with a composite-only change (the culling toggle): positions, uvs, indices, and
	 * keyforms all shared BY REFERENCE, which is exactly the shape that produces a Keep with no buffer
	 * work.
	 */
	private fun compositeEditedModel(source: PuppetModel): PuppetModel {
		val drawable = source.drawables.single()
		return source.copy(drawables = listOf(drawable.copy(culling = !drawable.culling)))
	}

	@Test
	fun puppetDisplaysFromArtworkAndSurvivesAnUnrelatedEdit() {
		requireHeadlessGl("[layer-display]")
		val source = probeModel()
		val device = GlRenderDevice()
		val renderer =
			PuppetRenderer(
				source,
				PuppetTextures(listOf(solidImage(red = 0xFF, green = 0x00)), mapOf(probeId.raw to 0), premultipliedAlpha = false),
				device,
			)
		renderer.initGl()
		val target = device.createRenderTarget(RenderTargetSpec(viewportSize, viewportSize, TextureFormat.Rgba8, sampled = true))
		val framebuffer = (target as GlRenderTarget).framebuffer
		// A fixed 1:1 camera centered on the origin, so the quad never moves - only its texels do.
		renderer.setCamera(ViewportCamera(0f, 0f, 1f))

		fun frame(): ByteBuffer {
			renderer.setPose(emptyMap())
			GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
			renderer.render(target, viewportSize, viewportSize)
			return readPixels(viewportSize, viewportSize)
		}

		// Background only, so the art can be isolated by differencing against it.
		renderer.setShownDrawables(emptySet())
		val background = frame()

		// Frame A: displaying from the atlas (red).
		renderer.setShownDrawables(setOf(probeId))
		val statsAtlas = artColorStats(frame(), background, viewportSize, viewportSize)

		// Frame B: displaying from the artwork (green).
		renderer.displayFromArtwork()
		val statsArtwork = artColorStats(frame(), background, viewportSize, viewportSize)

		// Frame C: an unrelated composite edit while still displaying from the artwork.
		renderer.updateModel(compositeEditedModel(source))
		val statsAfterEdit = artColorStats(frame(), background, viewportSize, viewportSize)

		// Frame D: back to the atlas.
		renderer.setSourceLayerPlan(LayerDrawPlan.EMPTY)
		val statsBack = artColorStats(frame(), background, viewportSize, viewportSize)

		println(
			"[layer-display] atlas r=${statsAtlas.meanRed} g=${statsAtlas.meanGreen} | " +
				"artwork r=${statsArtwork.meanRed} g=${statsArtwork.meanGreen} | " +
				"afterEdit r=${statsAfterEdit.meanRed} g=${statsAfterEdit.meanGreen} | " +
				"back r=${statsBack.meanRed} g=${statsBack.meanGreen}",
		)

		assertTrue(statsAtlas.mass > 1000, "the probe did not render from the atlas (mass ${statsAtlas.mass})")
		assertTrue(
			statsAtlas.meanRed > 200f && statsAtlas.meanGreen < 60f,
			"the atlas frame should sample the red page (r=${statsAtlas.meanRed} g=${statsAtlas.meanGreen})",
		)
		assertTrue(statsArtwork.mass > 1000, "the probe did not render from its artwork (mass ${statsArtwork.mass})")
		assertTrue(
			statsArtwork.meanGreen > 200f && statsArtwork.meanRed < 60f,
			"the artwork frame should sample the green image (r=${statsArtwork.meanRed} g=${statsArtwork.meanGreen})",
		)
		// The display source moves texels, never geometry.
		assertTrue(abs(statsArtwork.centroidX - statsAtlas.centroidX) < 2f, "the art moved in x when the display source changed")
		assertTrue(abs(statsArtwork.centroidY - statsAtlas.centroidY) < 2f, "the art moved in y when the display source changed")

		// THE regression: an unrelated edit must not knock the display back to the atlas.
		assertTrue(
			statsAfterEdit.meanGreen > 200f && statsAfterEdit.meanRed < 60f,
			"an unrelated edit reverted the display to the atlas (r=${statsAfterEdit.meanRed} g=${statsAfterEdit.meanGreen})",
		)

		// And the switch is reversible.
		assertTrue(
			statsBack.meanRed > 200f && statsBack.meanGreen < 60f,
			"clearing the artwork should return to the atlas (r=${statsBack.meanRed} g=${statsBack.meanGreen})",
		)
	}

	/**
	 * A mesh overhanging the art it samples must show NOTHING past the art's frame - the streak regression.
	 *
	 * Every corpus drawable overhangs its layer image (the auto-mesh rings outside the opaque region and
	 * authored meshes extend further for deformation coverage), by up to 443 layer pixels.  On an atlas page
	 * that overhang lands on the packer's transparent padding; a layer image has no padding, so an
	 * edge-clamped sampler repeats its border row and column across the overhang as streaks that follow the
	 * mesh and deform with it.  The official editor's own layered display shows nothing there, and so must
	 * this: the layer texture wraps to a transparent border.
	 *
	 * The probe reaches half its width past the art on all four sides, so the art covers a QUARTER of the
	 * quad's pixels.  Edge clamping fills the whole quad instead - four times the mass - which is a margin
	 * no filtering tolerance can blur away.
	 */
	@Test
	fun artworkDisplayShowsNothingWhereTheMeshOverhangsItsArt() {
		requireHeadlessGl("[layer-overhang]")
		val source = probeModel(overhangUvs)
		val device = GlRenderDevice()
		val renderer =
			PuppetRenderer(
				source,
				PuppetTextures(listOf(solidImage(red = 0xFF, green = 0x00)), mapOf(probeId.raw to 0), premultipliedAlpha = false),
				device,
			)
		renderer.initGl()
		val target = device.createRenderTarget(RenderTargetSpec(viewportSize, viewportSize, TextureFormat.Rgba8, sampled = true))
		val framebuffer = (target as GlRenderTarget).framebuffer
		renderer.setCamera(ViewportCamera(0f, 0f, 1f))

		fun frame(): ByteBuffer {
			renderer.setPose(emptyMap())
			GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
			renderer.render(target, viewportSize, viewportSize)
			return readPixels(viewportSize, viewportSize)
		}

		renderer.setShownDrawables(emptySet())
		val background = frame()
		renderer.setShownDrawables(setOf(probeId))

		// A layer image large enough that the border blend spans well under a pixel, so the measured mass is
		// the art's own extent rather than a filtering skirt around it.
		renderer.displayFromArtwork(imageSize = 256)
		val statsArtwork = artColorStats(frame(), background, viewportSize, viewportSize)

		// The quad is 120x120 world units at zoom 1, so its full screen footprint is 120x120 pixels.
		val quadPixels = 120 * 120
		val expectedArtPixels = quadPixels * overhangArtAreaFraction
		println(
			"[layer-overhang] artwork mass ${statsArtwork.mass} px (quad $quadPixels, art ~$expectedArtPixels) " +
				"r=${statsArtwork.meanRed} g=${statsArtwork.meanGreen}",
		)

		assertTrue(statsArtwork.mass > expectedArtPixels * 0.7f, "the art did not render at all (mass ${statsArtwork.mass})")
		assertTrue(
			statsArtwork.mass < expectedArtPixels * 1.4f,
			"the mesh's overhang sampled the layer's border instead of nothing: ${statsArtwork.mass} px lit, " +
				"~$expectedArtPixels expected (a whole-quad $quadPixels means edge clamping)",
		)
		assertTrue(
			statsArtwork.meanGreen > 200f && statsArtwork.meanRed < 60f,
			"what did render is not the artwork (r=${statsArtwork.meanRed} g=${statsArtwork.meanGreen})",
		)
	}

	/**
	 * The mode never shows a puppet half in artwork and half on its atlas.
	 *
	 * Source-artwork display exists so a rigger can INSPECT the art, so a mixed view is not a degraded
	 * success - it is untrustworthy, because nothing on screen says which drawables are showing which.
	 * The plan therefore engages nothing until every layer it maps has landed.
	 *
	 * Here the plan maps two layers and only one is delivered, so the probe must still be RED (its atlas),
	 * never green.  It flips only once the second lands.
	 */
	@Test
	fun artworkDisplayWaitsForEveryMappedLayer() {
		requireHeadlessGl("[layer-allornothing]")
		val source = probeModel()
		val device = GlRenderDevice()
		val renderer =
			PuppetRenderer(
				source,
				PuppetTextures(listOf(solidImage(red = 0xFF, green = 0x00)), mapOf(probeId.raw to 0), premultipliedAlpha = false),
				device,
			)
		renderer.initGl()
		val target = device.createRenderTarget(RenderTargetSpec(viewportSize, viewportSize, TextureFormat.Rgba8, sampled = true))
		val framebuffer = (target as GlRenderTarget).framebuffer
		renderer.setCamera(ViewportCamera(0f, 0f, 1f))

		fun frame(): ByteBuffer {
			renderer.setPose(emptyMap())
			GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
			renderer.render(target, viewportSize, viewportSize)
			return readPixels(viewportSize, viewportSize)
		}

		renderer.setShownDrawables(emptySet())
		val background = frame()
		renderer.setShownDrawables(setOf(probeId))

		// The probe draws from "green", but the plan also maps a second layer some other drawable needs.
		renderer.setSourceLayerPlan(
			LayerDrawPlan(
				drawsByDrawableId = mapOf(probeId.raw to DrawableLayerDraw("green", floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f))),
				layerByteCostByKey = mapOf("green" to 256L, "absent" to 256L),
			),
		)
		renderer.deliverSourceLayerRasters(artworkBatch())
		val statsPartial = artColorStats(frame(), background, viewportSize, viewportSize)
		val (readyPartial, _, _) = renderer.sourceLayerDisplayState()

		// The straggler lands: now the whole plan is covered and the mode engages.
		renderer.deliverSourceLayerRasters(
			LayerRasterBatch(rastersByLayerKey = mapOf("absent" to solidImage(red = 0x00, green = 0xFF))),
		)
		val statsComplete = artColorStats(frame(), background, viewportSize, viewportSize)
		val (readyComplete, _, _) = renderer.sourceLayerDisplayState()

		println(
			"[layer-allornothing] partial ready=$readyPartial r=${statsPartial.meanRed} g=${statsPartial.meanGreen} | " +
				"complete ready=$readyComplete r=${statsComplete.meanRed} g=${statsComplete.meanGreen}",
		)

		assertTrue(!readyPartial, "the mode must not engage while a mapped layer is still missing")
		assertTrue(
			statsPartial.meanRed > 200f && statsPartial.meanGreen < 60f,
			"an incompletely covered plan must leave the puppet wholly on its atlas, not partly on artwork " +
				"(r=${statsPartial.meanRed} g=${statsPartial.meanGreen})",
		)
		assertTrue(readyComplete, "the mode engages once every mapped layer has landed")
		assertTrue(
			statsComplete.meanGreen > 200f && statsComplete.meanRed < 60f,
			"and then the puppet displays from its artwork (r=${statsComplete.meanRed} g=${statsComplete.meanGreen})",
		)
	}

	/** The mean color and pixel centroid of the art (every pixel differing from [background]). */
	private class ArtColorStats(val mass: Int, val meanRed: Float, val meanGreen: Float, val centroidX: Float, val centroidY: Float)

	/**
	 * Isolates the art as every pixel that differs from [background] by more than a flat threshold on
	 * any channel, and returns its mean red / green plus its pixel centroid.
	 *
	 * @param ByteBuffer frame The rendered frame's RGBA pixels.
	 * @param ByteBuffer background The art-hidden frame's RGBA pixels.
	 * @param Int width The frame width in pixels.
	 * @param Int height The frame height in pixels.
	 * @return ArtColorStats The art's mass, mean red / green, and centroid.
	 */
	private fun artColorStats(frame: ByteBuffer, background: ByteBuffer, width: Int, height: Int): ArtColorStats {
		val threshold = 20
		var sumRed = 0.0
		var sumGreen = 0.0
		var sumX = 0.0
		var sumY = 0.0
		var count = 0
		for (row in 0 until height) {
			for (col in 0 until width) {
				val pixel = (row * width + col) * 4
				val red = frame.get(pixel).toInt() and 0xFF
				val green = frame.get(pixel + 1).toInt() and 0xFF
				val blue = frame.get(pixel + 2).toInt() and 0xFF
				val deltaRed = abs(red - (background.get(pixel).toInt() and 0xFF))
				val deltaGreen = abs(green - (background.get(pixel + 1).toInt() and 0xFF))
				val deltaBlue = abs(blue - (background.get(pixel + 2).toInt() and 0xFF))
				if (maxOf(deltaRed, deltaGreen, deltaBlue) > threshold) {
					sumRed += red
					sumGreen += green
					sumX += col
					sumY += row
					count++
				}
			}
		}
		if (count == 0) {
			return ArtColorStats(0, 0f, 0f, 0f, 0f)
		}
		return ArtColorStats(count, (sumRed / count).toFloat(), (sumGreen / count).toFloat(), (sumX / count).toFloat(), (sumY / count).toFloat())
	}

	/** Reads the bound framebuffer's RGBA pixels into a fresh buffer (bottom-up rows; consistent across frames). */
	private fun readPixels(width: Int, height: Int): ByteBuffer {
		val buffer = BufferUtils.createByteBuffer(width * height * 4)
		GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer)
		return buffer
	}
}