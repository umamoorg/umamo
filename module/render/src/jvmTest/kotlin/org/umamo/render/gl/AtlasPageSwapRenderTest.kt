package org.umamo.render.gl

import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import org.umamo.render.DecodedImage
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
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Proves a mid-session atlas page swap actually re-points the residents: setAtlasPages must destroy
 * and re-upload the page handles AND re-stamp every resident through the NEW index map, because a
 * repack can move a drawable to a different page.  The swap moves the probe from page 0 of a
 * one-page set to page 1 of a two-page set whose page 0 is a decoy color - a renderer that re-uploads
 * handles but keeps the old index shows the decoy, and one that skips the swap entirely shows the
 * original.  The page view is then checked at the ORIGINAL index, which must show the new texels
 * (same index, new pixels - the freshness case the engine's atlas bump exists for).
 *
 * Self-skips in a display-less environment (no GL context), like [SourceLayerDisplayRenderTest].
 */
class AtlasPageSwapRenderTest {
	private val viewportSize = 200
	private val paramA = ParameterId("A")
	private val probeId = DrawableId("SwapProbe")

	// A 120x120 quad centered at the origin; at zoom 1 it covers pixels ~40..160 in the 200x200 viewport.
	private val quadPositions = floatArrayOf(-60f, -60f, 60f, -60f, -60f, 60f, 60f, 60f)
	private val quadIndices = intArrayOf(0, 1, 2, 1, 3, 2)

	// Sampled well inside the image so a filtered lookup never straddles an edge.
	private val quadUvs = floatArrayOf(0.05f, 0.95f, 0.95f, 0.95f, 0.05f, 0.05f, 0.95f, 0.05f)

	private fun solidImage(red: Int, green: Int, blue: Int, size: Int = 8): DecodedImage {
		val rgba = ByteArray(size * size * 4)
		for (pixel in rgba.indices step 4) {
			rgba[pixel] = red.toByte()
			rgba[pixel + 1] = green.toByte()
			rgba[pixel + 2] = blue.toByte()
			rgba[pixel + 3] = 0xFF.toByte()
		}
		return DecodedImage(rgba, size, size)
	}

	private fun probeModel(): PuppetModel {
		val drawable =
			Drawable(
				id = probeId,
				name = "SwapProbe",
				parentDeformerId = null,
				blendMode = BlendMode.Normal,
				maskedBy = emptyList(),
				mesh = DrawableMesh(quadPositions.copyOf(), quadUvs.copyOf(), quadIndices),
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

	@Test
	fun swappingPagesRestampsResidentsThroughTheNewIndexMap() {
		requireHeadlessGl("[atlas-swap]")
		val device = GlRenderDevice()
		val renderer =
			PuppetRenderer(
				probeModel(),
				PuppetTextures(listOf(solidImage(0xFF, 0x00, 0x00)), mapOf(probeId.raw to 0), premultipliedAlpha = false),
				device,
			)
		renderer.initGl()
		val target = device.createRenderTarget(RenderTargetSpec(viewportSize, viewportSize, TextureFormat.Rgba8, sampled = true))
		val framebuffer = (target as GlRenderTarget).framebuffer
		// A fixed 1:1 camera centered on the origin, so the quad never moves - only its texels do.
		renderer.setCamera(ViewportCamera(0f, 0f, 1f))
		renderer.setShownDrawables(setOf(probeId))

		fun frame(): ByteBuffer {
			renderer.setPose(emptyMap())
			GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
			renderer.render(target, viewportSize, viewportSize)
			return readPixels(viewportSize, viewportSize)
		}

		assertCenterChannel(frame(), "red", "before the swap the probe samples its original red page")

		// The swap: two pages, the probe on page 1.  Page 0 turns into a green decoy, so keeping the old
		// index (or the old handles) is visibly distinct from following the new map to blue.
		renderer.setAtlasPages(
			PuppetTextures(
				listOf(solidImage(0x00, 0xFF, 0x00), solidImage(0x00, 0x00, 0xFF)),
				mapOf(probeId.raw to 1),
				premultipliedAlpha = false,
			),
		)
		assertCenterChannel(frame(), "blue", "after the swap the probe samples page 1 of the new set")

		// The page view at the ORIGINAL index shows the new texels: same index, new pixels.
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
		renderer.renderAtlasPage(target, 0, viewportSize, viewportSize)
		assertCenterChannel(readPixels(viewportSize, viewportSize), "green", "page index 0 now shows the swapped-in texels")
	}

	/** Asserts the given channel dominates at the viewport center (well inside the probe quad and the page). */
	private fun assertCenterChannel(frame: ByteBuffer, channel: String, message: String) {
		val pixel = (viewportSize / 2 * viewportSize + viewportSize / 2) * 4
		val red = frame.get(pixel).toInt() and 0xFF
		val green = frame.get(pixel + 1).toInt() and 0xFF
		val blue = frame.get(pixel + 2).toInt() and 0xFF
		val dominant =
			when (channel) {
				"red" -> red > green + 60 && red > blue + 60
				"green" -> green > red + 60 && green > blue + 60
				"blue" -> blue > red + 60 && blue > green + 60
				else -> false
			}
		assertTrue(dominant, "$message - got rgb=($red,$green,$blue)")
	}

	/** Reads the bound framebuffer's RGBA pixels into a fresh buffer (bottom-up rows). */
	private fun readPixels(width: Int, height: Int): ByteBuffer {
		val buffer = BufferUtils.createByteBuffer(width * height * 4)
		GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer)
		return buffer
	}
}