package org.umamo.render.gl

import org.lwjgl.opengl.GL11
import org.umamo.format.raster.RasterImage
import org.umamo.render.device.DeformUniforms
import org.umamo.render.device.DeformedPositionStore
import org.umamo.render.device.DrawTextures
import org.umamo.render.device.GpuMesh
import org.umamo.render.device.GpuTexture
import org.umamo.render.device.LoadAction
import org.umamo.render.device.MeshSpec
import org.umamo.render.device.OverlayDrawUniforms
import org.umamo.render.device.OverlayMeshBuffers
import org.umamo.render.device.OverlayMeshSpec
import org.umamo.render.device.PipelineBlend
import org.umamo.render.device.PipelinePurpose
import org.umamo.render.device.RenderPassEncoder
import org.umamo.render.device.RenderPassSpec
import org.umamo.render.device.RenderPipelineSpec
import org.umamo.render.device.RenderTarget
import org.umamo.render.device.RenderTargetSpec
import org.umamo.render.device.TextureFilter
import org.umamo.render.device.TextureFormat
import org.umamo.render.device.WorldToNdc
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the mesh overlay's GL pieces at the DEVICE level, with no renderer: a hand-built capture of one
 * triangle into a store, then each overlay draw against it, read back pixel by pixel.  The frame is 64
 * pixels square behind a camera that maps world x to column 32 + x and (through the deform's Y negation)
 * mesh y to top-first row 32 + y.  Opaque test colors (idle red, selected green, active blue) and generous
 * sizes keep every sampled pixel inside a solid core.  Skips without a GL context.
 */
class OverlayDrawGlTest {
	private val viewportSize = 64
	private val trianglePositions = floatArrayOf(-20f, -20f, 20f, -20f, -20f, 20f)
	private val triangleIndices = intArrayOf(0, 1, 2)
	private val twoEdges = intArrayOf(0, 1, 1, 2)
	private val red = floatArrayOf(1f, 0f, 0f, 1f)
	private val green = floatArrayOf(0f, 1f, 0f, 1f)
	private val blue = floatArrayOf(0f, 0f, 1f, 1f)
	private val black = intArrayOf(0, 0, 0, 255)

	@Test
	fun theFaceFillPaintsByFlagAndFillRule() {
		val rig = rig()
		assertEquals(listOf(255, 0, 0, 255), rig.render { pass -> rig.drawFaces(pass, fillIdle = true) }.at(25, 25).toList(), "an idle face fills in Face mode")
		assertEquals(black.toList(), rig.render { pass -> rig.drawFaces(pass, fillIdle = false) }.at(25, 25).toList(), "an idle face is clipped outside Face mode")
		rig.device.updateOverlayMeshFlags(rig.buffers, ByteArray(3), ByteArray(2), byteArrayOf(1))
		assertEquals(listOf(0, 255, 0, 255), rig.render { pass -> rig.drawFaces(pass, fillIdle = false) }.at(25, 25).toList(), "a selected face fills in every mode")
		rig.dispose()
	}

	@Test
	fun theEdgesPaintABandAndLeaveTheActiveOneToItsOwnDraw() {
		val rig = rig()
		val batch = rig.render { pass -> rig.drawEdges(pass, activeEdge = null) }
		assertEquals(listOf(255, 0, 0, 255), batch.at(32, 12).toList(), "the first edge's midpoint is on the band")
		assertEquals(listOf(255, 0, 0, 255), batch.at(32, 32).toList(), "the second edge's midpoint is on its band too (each instance reads its own endpoints)")
		assertEquals(black.toList(), batch.at(32, 18).toList(), "six pixels off the line is past the band and its fade")

		rig.device.updateOverlayMeshFlags(rig.buffers, ByteArray(3), byteArrayOf(2, 0), ByteArray(1))
		val activeHeld = rig.render { pass -> rig.drawEdges(pass, activeEdge = null) }
		assertEquals(black.toList(), activeHeld.at(32, 12).toList(), "a batched instance flagged active is left to the active draw")
		assertEquals(listOf(255, 0, 0, 255), activeHeld.at(32, 32).toList(), "the other edge still draws")
		val activeDrawn = rig.render { pass -> rig.drawEdges(pass, activeEdge = 0 to 1) }
		assertEquals(listOf(0, 0, 255, 255), activeDrawn.at(32, 12).toList(), "the active draw paints the one edge in the active color")
		rig.dispose()
	}

	@Test
	fun theVertexDotsPaintRoundAndPremultiplied() {
		val rig = rig()
		val dots = rig.render { pass -> rig.drawVertexDots(pass, idle = red, activeVertex = null) }
		assertEquals(listOf(255, 0, 0, 255), dots.at(12, 52).toList(), "the dot covers its vertex")
		assertEquals(black.toList(), dots.at(12, 59).toList(), "three pixels past the radius and its fade is clear")
		val half = rig.render { pass -> rig.drawVertexDots(pass, idle = floatArrayOf(1f, 0f, 0f, 0.5f), activeVertex = null) }
		assertEquals(listOf(128, 0, 0, 255), half.at(12, 52).toList(), "a half-alpha color lands premultiplied over the opaque clear")
		val active = rig.render { pass -> rig.drawVertexDots(pass, idle = red, activeVertex = 2) }
		assertEquals(listOf(0, 0, 255, 255), active.at(12, 52).toList(), "the active draw paints the one vertex in the active color")
		rig.dispose()
	}

	@Test
	fun theFaceDotsPaintAtTheCentroid() {
		val rig = rig()
		assertEquals(listOf(255, 0, 0, 255), rig.render { pass -> rig.drawFaceDots(pass, activeFace = false) }.at(25, 25).toList(), "the dot sits at the triangle's centroid")
		assertEquals(listOf(0, 0, 255, 255), rig.render { pass -> rig.drawFaceDots(pass, activeFace = true) }.at(25, 25).toList(), "the active draw paints the one face dot in the active color")
		rig.dispose()
	}

	/**
	 * Positions uploaded straight into a store (a UV scene's overlay) feed the draws from the offset they
	 * were written at, with no capture: a decoy block fills the store, the triangle overwrites it from
	 * vertex 1, and the dots drawn from base offset 1 land on the triangle's corners and never on the decoy.
	 */
	@Test
	fun uploadedPositionsFeedTheDraw() {
		val rig = rig()
		val uploaded = rig.device.createDeformedPositionStore(5)
		rig.device.updateDeformedPositions(uploaded, 0, FloatArray(10) { 28f })
		// World positions, y up: the space a capture writes, so these corners land where captured ones would.
		rig.device.updateDeformedPositions(uploaded, 1, floatArrayOf(-20f, -20f, 20f, -20f, -20f, 20f))

		val dots = rig.renderPass { pass -> rig.drawVertexDots(pass, idle = red, activeVertex = null, positions = uploaded, baseOffset = 1) }

		assertEquals(listOf(255, 0, 0, 255), dots.at(12, 52).toList(), "the first uploaded vertex")
		assertEquals(listOf(255, 0, 0, 255), dots.at(52, 52).toList(), "the second")
		assertEquals(listOf(255, 0, 0, 255), dots.at(12, 12).toList(), "the third")
		assertEquals(black.toList(), dots.at(60, 4).toList(), "the decoy left at vertex 0 is never read")
		rig.device.destroyDeformedPositionStore(uploaded)
		rig.dispose()
	}

	@Test
	fun freeingTheOverlayResourcesLeavesNoGlError() {
		val rig = rig()
		rig.render { pass -> rig.drawEdges(pass, activeEdge = null) }
		rig.dispose()
		assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "destroying the buffers and the store is clean")
	}

	/**
	 * One triangle captured into a store of three vertices, its overlay buffers over two edges and one
	 * triangle, and the frame target: everything a case draws against.
	 *
	 * @return OverlayRig The rig.
	 */
	private fun rig(): OverlayRig {
		requireHeadlessGl("[overlay-draw]")
		val device = GlRenderDevice()
		val mesh = device.createMesh(MeshSpec(trianglePositions, FloatArray(trianglePositions.size), triangleIndices, glueAttributes = null))
		// One zero-delta keyform cell: cellCount x vertexCount RG32F, as the residency uploads it.
		val deltaTexture = device.createFloatTexture(1, 3, TextureFilter.Nearest, FloatArray(6))
		val store = device.createDeformedPositionStore(3)
		val buffers = device.createOverlayMeshBuffers(OverlayMeshSpec(twoEdges, triangleIndices, ByteArray(3), ByteArray(2), ByteArray(1)))
		val target = device.createRenderTarget(RenderTargetSpec(viewportSize, viewportSize, TextureFormat.Rgba8, sampled = true))
		return OverlayRig(device, mesh, deltaTexture, store, buffers, target)
	}

	/**
	 * The device objects a case draws with, and the draws themselves.
	 *
	 * @property GlRenderDevice device The device.
	 * @property GpuMesh mesh The triangle mesh.
	 * @property GpuTexture deltaTexture Its zero-delta table.
	 * @property DeformedPositionStore store The store the capture writes.
	 * @property OverlayMeshBuffers buffers The triangle's overlay buffers.
	 * @property RenderTarget target The frame target.
	 */
	private inner class OverlayRig(
		val device: GlRenderDevice,
		val mesh: GpuMesh,
		val deltaTexture: GpuTexture,
		val store: DeformedPositionStore,
		val buffers: OverlayMeshBuffers,
		val target: RenderTarget,
	) {
		private val uniforms = OverlayDrawUniforms()

		/**
		 * One frame: the capture into the store, a barrier, a pass cleared to opaque black, [draw], and the
		 * read-back.
		 *
		 * @param Function draw The overlay draws to issue into the open pass.
		 * @return RasterImage The frame, top row first.
		 */
		fun render(draw: (RenderPassEncoder) -> Unit): RasterImage {
			val frame = device.beginFrame()
			val capture = frame.beginDeformCapturePass(device.createDeformCapturePipeline(), store)
			val deform = DeformUniforms()
			deform.cornerCount = 1
			deform.cornerCell[0] = 0
			deform.cornerWeight[0] = 1f
			deform.parentType = 0
			val textures = DrawTextures()
			textures.deltaTexture = deltaTexture
			capture.captureDeformedPositions(mesh, deform, textures, 0, 3)
			capture.end()
			frame.barrier(store)
			val pass = frame.beginRenderPass(RenderPassSpec(target, LoadAction.Clear, viewportSize, viewportSize, clearAlpha = 1f))
			draw(pass)
			pass.end()
			frame.endFrame()
			return device.readPixels(target)
		}

		/**
		 * One frame with no capture: a pass cleared to opaque black, [draw], and the read-back.
		 *
		 * @param Function draw The overlay draws to issue into the open pass.
		 * @return RasterImage The frame, top row first.
		 */
		fun renderPass(draw: (RenderPassEncoder) -> Unit): RasterImage {
			val frame = device.beginFrame()
			val pass = frame.beginRenderPass(RenderPassSpec(target, LoadAction.Clear, viewportSize, viewportSize, clearAlpha = 1f))
			draw(pass)
			pass.end()
			frame.endFrame()
			return device.readPixels(target)
		}

		/**
		 * Draws the triangle's face fill with the idle color red and the selected color green.
		 *
		 * @param RenderPassEncoder pass The open pass.
		 * @param Boolean fillIdle Whether idle faces fill.
		 */
		fun drawFaces(pass: RenderPassEncoder, fillIdle: Boolean) {
			bind(pass, PipelinePurpose.OverlayFaceFill)
			fill(sizePx = 0f, fillIdle = fillIdle, idle = red, active = null)
			pass.drawOverlayFaceFill(buffers, store, uniforms)
		}

		/**
		 * Draws the two edges as a batch, then, when given, the active edge on top.
		 *
		 * @param RenderPassEncoder pass The open pass.
		 * @param Pair<Int, Int>? activeEdge The active edge's endpoints, or null for the batch alone.
		 */
		fun drawEdges(pass: RenderPassEncoder, activeEdge: Pair<Int, Int>?) {
			bind(pass, PipelinePurpose.OverlayEdge)
			fill(sizePx = 3f, fillIdle = true, idle = red, active = null)
			pass.drawOverlayEdges(buffers, store, uniforms)
			if (activeEdge != null) {
				fill(sizePx = 3f, fillIdle = true, idle = red, active = intArrayOf(activeEdge.first, activeEdge.second, -1))
				pass.drawOverlayEdges(buffers, store, uniforms)
			}
		}

		/**
		 * Draws the three vertex dots, then, when given, the active vertex on top.
		 *
		 * @param RenderPassEncoder pass The open pass.
		 * @param FloatArray idle The idle color.
		 * @param Int? activeVertex The active vertex, or null for the batch alone.
		 * @param DeformedPositionStore positions The store the positions are read from.
		 * @param Int baseOffset The triangle's first vertex in that store.
		 */
		fun drawVertexDots(
			pass: RenderPassEncoder,
			idle: FloatArray,
			activeVertex: Int?,
			positions: DeformedPositionStore = store,
			baseOffset: Int = 0,
		) {
			bind(pass, PipelinePurpose.OverlayVertexDot)
			fill(sizePx = 4f, fillIdle = true, idle = idle, active = null, baseOffset = baseOffset)
			pass.drawOverlayVertexDots(buffers, positions, uniforms)
			if (activeVertex != null) {
				fill(sizePx = 4f, fillIdle = true, idle = idle, active = intArrayOf(activeVertex, -1, -1), baseOffset = baseOffset)
				pass.drawOverlayVertexDots(buffers, positions, uniforms)
			}
		}

		/**
		 * Draws the triangle's centroid dot, as the batch or as the active draw.
		 *
		 * @param RenderPassEncoder pass The open pass.
		 * @param Boolean activeFace True to draw it as the active face.
		 */
		fun drawFaceDots(pass: RenderPassEncoder, activeFace: Boolean) {
			bind(pass, PipelinePurpose.OverlayFaceDot)
			fill(sizePx = 4f, fillIdle = true, idle = red, active = if (activeFace) intArrayOf(0, 1, 2) else null)
			pass.drawOverlayFaceDots(buffers, store, uniforms)
		}

		/** Frees everything the rig created. */
		fun dispose() {
			device.destroyOverlayMeshBuffers(buffers)
			device.destroyDeformedPositionStore(store)
			device.destroyRenderTarget(target)
			device.destroyTexture(deltaTexture)
			device.destroyMesh(mesh)
		}

		/**
		 * Binds the pipeline of [purpose] and the 64-pixel camera.
		 *
		 * @param RenderPassEncoder pass The open pass.
		 * @param PipelinePurpose purpose The overlay domain.
		 */
		private fun bind(pass: RenderPassEncoder, purpose: PipelinePurpose) {
			pass.setPipeline(device.createRenderPipeline(RenderPipelineSpec(purpose, PipelineBlend.Normal)))
			pass.setCamera(WorldToNdc(2f / viewportSize, 2f / viewportSize, 0f, 0f), viewportSize, viewportSize)
		}

		/**
		 * Fills the reused uniform struct for one draw.
		 *
		 * @param Float sizePx The half-width or radius.
		 * @param Boolean fillIdle Whether idle faces fill.
		 * @param FloatArray idle The idle color.
		 * @param IntArray? active The active primitive's indices for an active draw, or null for a batch.
		 * @param Int baseOffset The mesh's first vertex in the store.
		 */
		private fun fill(sizePx: Float, fillIdle: Boolean, idle: FloatArray, active: IntArray?, baseOffset: Int = 0) {
			uniforms.baseOffset = baseOffset
			uniforms.viewportWidth = viewportSize.toFloat()
			uniforms.viewportHeight = viewportSize.toFloat()
			uniforms.sizePx = sizePx
			uniforms.fillIdle = fillIdle
			idle.copyInto(uniforms.idleColor)
			green.copyInto(uniforms.selectedColor)
			blue.copyInto(uniforms.activeColor)
			uniforms.activeDraw = active != null
			uniforms.activeIndexA = active?.get(0) ?: -1
			uniforms.activeIndexB = active?.get(1) ?: -1
			uniforms.activeIndexC = active?.get(2) ?: -1
		}
	}

	/**
	 * One pixel's channels, top row first.
	 *
	 * @param Int column The column.
	 * @param Int row The row from the top.
	 * @return IntArray Red, green, blue, alpha in 0..255.
	 */
	private fun RasterImage.at(column: Int, row: Int): IntArray {
		val offset = (row * viewportSize + column) * 4
		return IntArray(4) { channelIndex -> rgba[offset + channelIndex].toInt() and 0xFF }
	}
}