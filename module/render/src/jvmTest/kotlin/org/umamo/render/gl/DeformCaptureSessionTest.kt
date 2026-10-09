package org.umamo.render.gl

import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL31
import org.umamo.render.device.DeformUniforms
import org.umamo.render.device.DeformedPositionStore
import org.umamo.render.device.DrawTextures
import org.umamo.render.device.GpuMesh
import org.umamo.render.device.MeshSpec
import org.umamo.render.device.TextureFilter
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The deform capture's feedback session: captures that continue where the last one ended append under one
 * session and land at their offsets exactly as a capture opened at that offset would, a capture past a gap
 * opens a session of its own at its offset, and the gap keeps what the store held.  Device level, no
 * renderer; skips without a GL context.
 */
class DeformCaptureSessionTest {
	private val triangleIndices = intArrayOf(0, 1, 2)

	/**
	 * Three meshes captured into one store, two contiguous and one past a gap, land exactly where each
	 * captured alone into a store of its own lands, and the gap keeps what the store held.
	 */
	@Test
	fun appendedCapturesLandAtTheirOffsetsAndAGapIsLeftAlone() {
		requireHeadlessGl("[deform-capture]")
		val device = GlRenderDevice()
		val meshes = listOf(0f, 10f, 20f).map { shift -> device.createMesh(MeshSpec(triangle(shift), FloatArray(6), triangleIndices, glueAttributes = null)) }
		val deltaTexture = device.createFloatTexture(1, 3, TextureFilter.Nearest, FloatArray(6))
		val shared = device.createDeformedPositionStore(12)
		device.updateDeformedPositions(shared, 0, FloatArray(24) { 99f })

		// A at 0 and B appended at 3 share one session; C at 9 opens its own past the gap at 6..9.
		captureAll(device, shared, listOf(meshes[0] to 0, meshes[1] to 3, meshes[2] to 9), deltaTexture)
		val appended = readStore(shared, 12)

		// Each mesh captured alone, into its own store at offset 0: what any capture must write.
		val alone = meshes.map { mesh -> device.createDeformedPositionStore(3).also { store -> captureAll(device, store, listOf(mesh to 0), deltaTexture) } }
		assertEquals(readStore(alone[0], 3).toList(), appended.copyOfRange(0, 6).toList(), "A at its offset")
		assertEquals(readStore(alone[1], 3).toList(), appended.copyOfRange(6, 12).toList(), "B appended at 3")
		assertEquals(List(6) { 99f }, appended.copyOfRange(12, 18).toList(), "the gap at 6..9 keeps the store's contents")
		assertEquals(readStore(alone[2], 3).toList(), appended.copyOfRange(18, 24).toList(), "C at 9 past the gap")
		assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "the sessions open and close cleanly")

		alone.forEach { store -> device.destroyDeformedPositionStore(store) }
		device.destroyDeformedPositionStore(shared)
	}

	/**
	 * One capture pass writing each mesh at its offset, then the barrier.
	 *
	 * @param GlRenderDevice device The device.
	 * @param DeformedPositionStore store The store to write.
	 * @param List<Pair<GpuMesh, Int>> captures Each mesh and its offset, in order.
	 * @param org.umamo.render.device.GpuTexture deltaTexture The zero-delta table every mesh deforms by.
	 */
	private fun captureAll(device: GlRenderDevice, store: DeformedPositionStore, captures: List<Pair<GpuMesh, Int>>, deltaTexture: org.umamo.render.device.GpuTexture) {
		val frame = device.beginFrame()
		val capture = frame.beginDeformCapturePass(device.createDeformCapturePipeline(), store)
		val deform = DeformUniforms()
		deform.cornerCount = 1
		deform.cornerCell[0] = 0
		deform.cornerWeight[0] = 1f
		deform.parentType = 0
		val textures = DrawTextures()
		textures.deltaTexture = deltaTexture
		for ((mesh, offset) in captures) {
			capture.captureDeformedPositions(mesh, deform, textures, offset, 3)
		}
		capture.end()
		frame.barrier(store)
		frame.endFrame()
	}

	/**
	 * The store's positions, read back from its buffer.
	 *
	 * @param DeformedPositionStore store The store.
	 * @param Int vertexCount How many vertices to read.
	 * @return FloatArray The x, y pairs.
	 */
	private fun readStore(store: DeformedPositionStore, vertexCount: Int): FloatArray {
		val buffer = BufferUtils.createFloatBuffer(vertexCount * 2)
		GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, (store as GlDeformedPositionStore).buffer)
		GL15.glGetBufferSubData(GL31.GL_TEXTURE_BUFFER, 0L, buffer)
		return FloatArray(vertexCount * 2) { index -> buffer.get(index) }
	}

	/**
	 * A triangle shifted along x, so each mesh's positions tell apart.
	 *
	 * @param Float shift The x shift.
	 * @return FloatArray The three corners.
	 */
	private fun triangle(shift: Float): FloatArray = floatArrayOf(-20f + shift, -20f, 20f + shift, -20f, -20f + shift, 20f)
}