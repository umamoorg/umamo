package org.umamo.render.gl

import org.lwjgl.BufferUtils
import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import org.lwjgl.system.MemoryUtil
import org.umamo.render.PuppetTextures
import org.umamo.render.ViewportCamera
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Proves [GlPuppetRenderer.updateModel]'s STRUCTURAL reconcile: a session-created drawable (e.g. an
 * Object-mode duplicate) must reach the GPU, not just have position VBOs patched on drawables it already
 * knew about, or it renders blank; likewise a remesh must re-upload the element buffer, or the
 * newly-referenced geometry never draws. Renders flat-color art into an offscreen FBO and asserts by
 * covered-pixel mass that:
 *
 * - a drawable ADDED after init (the duplicate) draws, and its removal (the undo) stops drawing it;
 * - a REMESH of an existing drawable (new indices growing one triangle to two) re-uploads the element
 *   buffer, so the newly-referenced geometry actually draws.
 *
 * Self-skips in a display-less environment (no GL context), like [GpuDeformValidationTest].
 */
class StructuralReconcileTest {
	private val viewportSize = 400
	private val paramA = ParameterId("A")
	private val sourceId = DrawableId("Probe")
	private val copyId = DrawableId("Probe.001")

	// A 120x120 quad centered at (-80, 0); its duplicate shifts to (+80, 0) so the two never overlap and
	// the covered mass simply doubles when both draw.
	private val quadPositions = floatArrayOf(-140f, -60f, -20f, -60f, -140f, 60f, -20f, 60f)
	private val quadUvs = FloatArray(8)
	private val quadIndices = intArrayOf(0, 1, 2, 1, 3, 2)

	private fun meshAxis() = listOf(KeyformAxis(paramA, floatArrayOf(0f)))

	private fun drawable(id: DrawableId, positions: FloatArray, indices: IntArray, textureSourceId: DrawableId? = null): Drawable =
		Drawable(
			id = id,
			name = id.raw,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = DrawableMesh(positions, quadUvs, indices),
			// A single zero-delta keyform so the drawable is "keyed" (an unkeyed drawable is skipped).
			keyforms = KeyformGrid(meshAxis(), listOf(KeyformCell(intArrayOf(0), MeshForm(FloatArray(positions.size))))),
			textureSourceId = textureSourceId,
		)

	private fun model(drawables: List<Drawable>): PuppetModel =
		PuppetModel(
			parameters = listOf(Parameter(paramA, "A", -1f, 1f, 0f)),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = drawables,
			rootChildren = drawables.map { OrgChild.Drawable(it.id) },
			rootPartId = null,
		)

	/** An Object-mode duplicate appears on the next reconcile, and its undo removes it again. */
	@Test
	fun duplicatedDrawableAppearsAndItsUndoRemovesIt() {
		val window = createHeadlessGl()
		if (window == 0L) {
			println("[structural-reconcile] no GL context (display-less env); skip duplicate case")
			return
		}
		try {
			val original = model(listOf(drawable(sourceId, quadPositions.copyOf(), quadIndices)))
			val renderer = GlPuppetRenderer(original, PuppetTextures(emptyList(), emptyMap(), premultipliedAlpha = false))
			renderer.initGl()
			val framebuffer = createColorFbo(viewportSize, viewportSize)
			renderer.setCamera(ViewportCamera(0f, 0f, 1f))

			renderer.setShownDrawables(emptySet())
			renderer.setPose(emptyMap())
			GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
			renderer.render(viewportSize, viewportSize)
			val background = readPixels(viewportSize, viewportSize)

			renderer.setShownDrawables(setOf(sourceId))
			renderer.setPose(emptyMap())
			GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
			renderer.render(viewportSize, viewportSize)
			val massBefore = coveredMass(readPixels(viewportSize, viewportSize), background)

			// The duplicate: the source's mesh shifted fully clear of it, added the way DuplicateEdits does
			// (fresh id, textureSourceId pointing home).
			val copyPositions = FloatArray(quadPositions.size) { coordIndex -> quadPositions[coordIndex] + if (coordIndex % 2 == 0) 160f else 0f }
			val duplicated =
				model(
					listOf(
						drawable(sourceId, quadPositions.copyOf(), quadIndices),
						drawable(copyId, copyPositions, quadIndices, textureSourceId = sourceId),
					),
				)
			renderer.updateModel(duplicated)
			renderer.setShownDrawables(setOf(sourceId, copyId))
			renderer.setPose(emptyMap())
			GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
			renderer.render(viewportSize, viewportSize)
			val massWithCopy = coveredMass(readPixels(viewportSize, viewportSize), background)

			// The undo: back to the original model - the copy's GPU objects are freed and it stops drawing.
			renderer.updateModel(original)
			renderer.setShownDrawables(setOf(sourceId))
			renderer.setPose(emptyMap())
			GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
			renderer.render(viewportSize, viewportSize)
			val massAfterUndo = coveredMass(readPixels(viewportSize, viewportSize), background)

			println("[structural-reconcile] duplicate: before=$massBefore withCopy=$massWithCopy afterUndo=$massAfterUndo")
			assertTrue(massBefore > 1000, "the probe did not render at all (mass $massBefore)")
			assertTrue(
				abs(massWithCopy - 2 * massBefore) < massBefore / 4,
				"the duplicate did not draw (mass $massWithCopy, expected ~${2 * massBefore})",
			)
			assertTrue(
				abs(massAfterUndo - massBefore) < massBefore / 4,
				"the undone duplicate kept drawing (mass $massAfterUndo, expected ~$massBefore)",
			)
		} finally {
			GLFW.glfwDestroyWindow(window)
			GLFW.glfwTerminate()
		}
	}

	/** A remesh (fresh indices referencing more of the mesh) re-uploads the element buffer and draws. */
	@Test
	fun remeshedDrawableDrawsItsNewTopology() {
		val window = createHeadlessGl()
		if (window == 0L) {
			println("[structural-reconcile] no GL context (display-less env); skip remesh case")
			return
		}
		try {
			// Start with HALF the quad (one triangle); the remesh grows it to the full quad with a fresh
			// indices array - the covered mass roughly doubles only if the EBO was rebuilt.
			val halfQuad = model(listOf(drawable(sourceId, quadPositions.copyOf(), intArrayOf(0, 1, 2))))
			val renderer = GlPuppetRenderer(halfQuad, PuppetTextures(emptyList(), emptyMap(), premultipliedAlpha = false))
			renderer.initGl()
			val framebuffer = createColorFbo(viewportSize, viewportSize)
			renderer.setCamera(ViewportCamera(0f, 0f, 1f))

			renderer.setShownDrawables(emptySet())
			renderer.setPose(emptyMap())
			GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
			renderer.render(viewportSize, viewportSize)
			val background = readPixels(viewportSize, viewportSize)

			renderer.setShownDrawables(setOf(sourceId))
			renderer.setPose(emptyMap())
			GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
			renderer.render(viewportSize, viewportSize)
			val massHalf = coveredMass(readPixels(viewportSize, viewportSize), background)

			renderer.updateModel(model(listOf(drawable(sourceId, quadPositions.copyOf(), quadIndices.copyOf()))))
			renderer.setPose(emptyMap())
			GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
			renderer.render(viewportSize, viewportSize)
			val massFull = coveredMass(readPixels(viewportSize, viewportSize), background)

			println("[structural-reconcile] remesh: half=$massHalf full=$massFull")
			assertTrue(massHalf > 1000, "the half quad did not render (mass $massHalf)")
			assertTrue(
				abs(massFull - 2 * massHalf) < massHalf / 4,
				"the remeshed topology did not draw (mass $massFull, expected ~${2 * massHalf})",
			)
		} finally {
			GLFW.glfwDestroyWindow(window)
			GLFW.glfwTerminate()
		}
	}

	/** The count of pixels differing from [background] by more than a flat threshold on any channel. */
	private fun coveredMass(frame: ByteBuffer, background: ByteBuffer): Int {
		val threshold = 20
		var count = 0
		for (pixelIndex in 0 until viewportSize * viewportSize) {
			val byteIndex = pixelIndex * 4
			val deltaRed = abs((frame.get(byteIndex).toInt() and 0xFF) - (background.get(byteIndex).toInt() and 0xFF))
			val deltaGreen = abs((frame.get(byteIndex + 1).toInt() and 0xFF) - (background.get(byteIndex + 1).toInt() and 0xFF))
			val deltaBlue = abs((frame.get(byteIndex + 2).toInt() and 0xFF) - (background.get(byteIndex + 2).toInt() and 0xFF))
			if (maxOf(deltaRed, deltaGreen, deltaBlue) > threshold) {
				count++
			}
		}
		return count
	}

	/** Reads the bound framebuffer's RGBA pixels into a fresh buffer. */
	private fun readPixels(width: Int, height: Int): ByteBuffer {
		val buffer = BufferUtils.createByteBuffer(width * height * 4)
		GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer)
		return buffer
	}

	/** Creates and binds an RGBA8 offscreen framebuffer to render the puppet into for read-back. */
	private fun createColorFbo(width: Int, height: Int): Int {
		val framebuffer = GL30.glGenFramebuffers()
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
		val colorTexture = GL11.glGenTextures()
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTexture)
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, null as ByteBuffer?)
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
		GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, colorTexture, 0)
		return framebuffer
	}

	/** Creates a hidden 1x1 GL 3.3 core window for headless rendering, or 0 if GLFW/GL is unavailable. */
	private fun createHeadlessGl(): Long {
		if (!GLFW.glfwInit()) {
			return 0L
		}
		GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE)
		GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3)
		GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3)
		GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE)
		GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE)
		val window = GLFW.glfwCreateWindow(1, 1, "umamo-structural-reconcile", MemoryUtil.NULL, MemoryUtil.NULL)
		if (window == MemoryUtil.NULL) {
			GLFW.glfwTerminate()
			return 0L
		}
		GLFW.glfwMakeContextCurrent(window)
		GL.createCapabilities()
		return window
	}
}
