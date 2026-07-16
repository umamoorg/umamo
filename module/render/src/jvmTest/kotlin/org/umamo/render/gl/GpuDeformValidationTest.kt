package org.umamo.render.gl

import org.junit.Assume
import org.lwjgl.BufferUtils
import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30
import org.lwjgl.system.MemoryUtil
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.render.eval.DeformerWorld
import org.umamo.render.eval.RotationWorld
import org.umamo.render.eval.WarpWorld
import org.umamo.render.eval.WeightedCell
import org.umamo.render.eval.cellsByLinearIndex
import org.umamo.render.eval.deformMeshWorldFromCorners
import org.umamo.render.eval.preparePose
import org.umamo.render.glsl.GlslDialect
import org.umamo.render.glsl.tfDeformVertexShader
import org.umamo.render.glsl.tfDiscardFragmentShader
import org.umamo.runtime.ingest.Cmo3Import
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterId
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Differential validation that the GPU deform shader matches the CPU evaluator per vertex. Runs the
 * shipped [DEFORM_GLSL] (the exact body [PuppetRenderer] uses) in a headless GL context with transform
 * feedback capturing each vertex's world position, then diffs against [deformMeshWorldFromCorners] (the CPU
 * deform, pre-glue) for the same pose. Bounded-ULP is the bar - float32 GPU vs float32 CPU diverge slightly
 * by construction. Glue is excluded (the GPU path skips it), so this compares the morph + cascade math only.
 *
 * Gated on `-Dcmo3.sample` and on a usable GL context, both via JUnit assumptions - so an ungated run
 * reports SKIPPED rather than passing green having asserted nothing. That distinction matters here more
 * than anywhere: this is the only pin on the deform math, and a Metal port will check itself against it.
 * Run: `./gradlew :render:jvmTest -Dcmo3.sample=… --tests *GpuDeformValidationTest`.
 */
class GpuDeformValidationTest {
	// Per-coordinate bound in Umamo canvas units (~4500 across the canvas, so this is well sub-pixel). It
	// tolerates float32 GPU-vs-CPU rounding and the shader's approximate out-of-grid warp extrapolation.
	private val toleranceUnits = 0.5

	@Test
	fun gpuDeformMatchesCpuPerVertex() {
		val file = File(System.getProperty("cmo3.sample") ?: "")
		Assume.assumeTrue("[gpu-tf] no -Dcmo3.sample corpus model", file.isFile)
		val window = createHeadlessGl()
		assumeGlContext("[gpu-tf]", window)
		try {
			val root = Cmo3.read(file).root as? CModelSource ?: return
			val model = Cmo3Import.fromModelSource(root)
			val program = linkTransformFeedbackProgram()
			GL20.glUseProgram(program)
			GL20.glUniform1i(GL20.glGetUniformLocation(program, "deltaTex"), 0)
			GL20.glUniform1i(GL20.glGetUniformLocation(program, "cpTex"), 1)

			val drawableById = model.drawables.associateBy { it.id }
			// Rest plus deformed poses that push warp-bound vertices out of the lattice, exercising warpExtrap.
			val poses =
				listOf(
					"rest" to emptyMap<ParameterId, Float>(),
					"body" to
						mapOf(
							ParameterId("ParamBodyAngleX") to 30f,
							ParameterId("ParamBodyAngleY") to -25f,
							ParameterId("ParamBodyAngleZ") to 10f,
						),
					"tail" to
						mapOf(
							ParameterId("Param_Angle_Rotation") to 25.58f,
							ParameterId("Param_Angle_Rotation2") to 35.51f,
							ParameterId("Param_Angle_Rotation3") to 43.31f,
						),
				)
			var maxError = 0.0
			var worstMesh = ""
			var worstPose = ""
			var meshesChecked = 0
			for ((poseName, parameters) in poses) {
				val inputs = preparePose(model, parameters)
				for (drawableInputs in inputs.drawables) {
					val corners = drawableInputs.corners ?: continue
					if (drawableInputs.isParented && drawableInputs.parentWorld == null) {
						continue
					}
					val drawable = drawableById[drawableInputs.drawableId] ?: continue
					val grid = drawable.keyforms ?: continue
					val base = drawable.mesh?.positions ?: continue
					if (drawable.mesh?.indices?.isEmpty() != false) {
						continue // renderer skips index-less meshes (glue anchors)
					}
					val cpuWorld = deformMeshWorldFromCorners(grid, base, corners, drawableInputs.parentWorld)
					val gpuWorld = gpuDeform(program, base, grid, corners, drawableInputs.parentWorld)
					var meshMax = 0.0
					for (coordIndex in cpuWorld.indices) {
						meshMax = maxOf(meshMax, abs(cpuWorld[coordIndex] - gpuWorld[coordIndex]).toDouble())
					}
					if (meshMax > maxError) {
						maxError = meshMax
						worstMesh = drawableInputs.drawableId.raw
						worstPose = poseName
					}
					meshesChecked++
				}
			}
			println("[gpu-tf] checked $meshesChecked mesh-poses; max GPU-vs-CPU per-coord error = ${"%.5f".format(maxError)} units (worst $worstMesh @ $worstPose)")
			assertTrue(meshesChecked > 0, "no meshes were validated")
			assertTrue(maxError < toleranceUnits, "GPU deform diverges from CPU by $maxError units at $worstMesh @ $worstPose (> $toleranceUnits)")
		} finally {
			GLFW.glfwDestroyWindow(window)
			GLFW.glfwTerminate()
		}
	}

	/**
	 * Deforms one mesh on the GPU and reads back its world positions via transform feedback: builds the
	 * delta texture (+ warp control-point texture), sets the per-pose corner/transform uniforms, draws the
	 * vertices as points with the rasterizer discarded, and returns the captured interleaved x,y.
	 */
	private fun gpuDeform(
		program: Int,
		base: FloatArray,
		grid: KeyformGrid<MeshForm>,
		corners: List<WeightedCell>,
		parent: DeformerWorld?,
	): FloatArray {
		val vertexCount = base.size / 2
		val cellCount = maxOf(1, grid.axes.fold(1) { count, axis -> count * axis.keys.size })

		val vao = GL30.glGenVertexArrays()
		GL30.glBindVertexArray(vao)
		val baseBuffer = BufferUtils.createFloatBuffer(base.size)
		baseBuffer.put(base).flip()
		val baseVbo = GL15.glGenBuffers()
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, baseVbo)
		GL15.glBufferData(GL15.GL_ARRAY_BUFFER, baseBuffer, GL15.GL_STATIC_DRAW)
		GL20.glEnableVertexAttribArray(0)
		GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 2 * Float.SIZE_BYTES, 0L)

		GL13.glActiveTexture(GL13.GL_TEXTURE0)
		val deltaTexture = uploadDeltaTexture(grid, vertexCount, cellCount)

		setCornerUniforms(program, corners)
		when (val parentWorld = parent) {
			is RotationWorld -> {
				val xform = parentWorld.xform
				GL20.glUniform1fv(
					GL20.glGetUniformLocation(program, "rot"),
					floatArrayOf(xform.c12, xform.c13, xform.c14, xform.c15, xform.ox, xform.oy),
				)
				GL20.glUniform1i(GL20.glGetUniformLocation(program, "parentType"), 1)
			}
			is WarpWorld -> {
				GL13.glActiveTexture(GL13.GL_TEXTURE1)
				uploadControlPointTexture(parentWorld)
				GL20.glUniform1i(GL20.glGetUniformLocation(program, "warpCols"), parentWorld.cols)
				GL20.glUniform1i(GL20.glGetUniformLocation(program, "warpRows"), parentWorld.rows)
				GL20.glUniform1i(GL20.glGetUniformLocation(program, "warpBilinear"), if (parentWorld.bilinear) 1 else 0)
				GL20.glUniform1i(GL20.glGetUniformLocation(program, "parentType"), 2)
			}
			else -> GL20.glUniform1i(GL20.glGetUniformLocation(program, "parentType"), 0)
		}

		val feedbackBuffer = GL15.glGenBuffers()
		GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0, feedbackBuffer)
		GL15.glBufferData(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, (vertexCount * 2 * Float.SIZE_BYTES).toLong(), GL15.GL_STATIC_READ)

		GL11.glEnable(GL30.GL_RASTERIZER_DISCARD)
		GL30.glBeginTransformFeedback(GL11.GL_POINTS)
		GL11.glDrawArrays(GL11.GL_POINTS, 0, vertexCount)
		GL30.glEndTransformFeedback()
		GL11.glDisable(GL30.GL_RASTERIZER_DISCARD)
		GL11.glFinish()

		val out = FloatArray(vertexCount * 2)
		val readBuffer = BufferUtils.createFloatBuffer(vertexCount * 2)
		GL15.glGetBufferSubData(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0L, readBuffer)
		readBuffer.get(out)

		GL15.glDeleteBuffers(feedbackBuffer)
		GL15.glDeleteBuffers(baseVbo)
		GL11.glDeleteTextures(deltaTexture)
		GL30.glDeleteVertexArrays(vao)
		return out
	}

	/** Uploads the per-keyform-cell vertex deltas as an RG32F texture (col = cell, row = vertex), matching
	 *  [PuppetRenderer]'s layout so the test exercises the renderer's real delta indexing. */
	private fun uploadDeltaTexture(grid: KeyformGrid<MeshForm>, vertexCount: Int, cellCount: Int): Int {
		val cells = cellsByLinearIndex(grid)
		val data = BufferUtils.createFloatBuffer(vertexCount * cellCount * 2)
		for (vertexIndex in 0 until vertexCount) {
			for (cellIndex in 0 until cellCount) {
				val deltas = cells[cellIndex]?.form?.positionDeltas
				if (deltas != null && vertexIndex * 2 + 1 < deltas.size) {
					data.put(deltas[vertexIndex * 2]).put(deltas[vertexIndex * 2 + 1])
				} else {
					data.put(0f).put(0f)
				}
			}
		}
		data.flip()
		val texture = nearestTexture()
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RG32F, cellCount, vertexCount, 0, GL30.GL_RG, GL11.GL_FLOAT, data)
		return texture
	}

	private fun uploadControlPointTexture(warp: WarpWorld): Int {
		val buffer = BufferUtils.createFloatBuffer(warp.cp.size)
		buffer.put(warp.cp).flip()
		val texture = nearestTexture()
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RG32F, warp.cols + 1, warp.rows + 1, 0, GL30.GL_RG, GL11.GL_FLOAT, buffer)
		return texture
	}

	private fun nearestTexture(): Int {
		val texture = GL11.glGenTextures()
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture)
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST)
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST)
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
		return texture
	}

	private fun setCornerUniforms(program: Int, corners: List<WeightedCell>) {
		val cornerCount = minOf(16, corners.size)
		val cellBuffer = BufferUtils.createIntBuffer(cornerCount)
		val weightBuffer = BufferUtils.createFloatBuffer(cornerCount)
		for (cornerIndex in 0 until cornerCount) {
			cellBuffer.put(corners[cornerIndex].linearIndex)
			weightBuffer.put(corners[cornerIndex].weight)
		}
		cellBuffer.flip()
		weightBuffer.flip()
		GL20.glUniform1i(GL20.glGetUniformLocation(program, "cornerCount"), cornerCount)
		GL20.glUniform1iv(GL20.glGetUniformLocation(program, "cornerCell"), cellBuffer)
		GL20.glUniform1fv(GL20.glGetUniformLocation(program, "cornerWeight"), weightBuffer)
	}

	/** Compiles the deform vertex shader (shared [DEFORM_GLSL]) capturing `outWorld` via transform feedback,
	 *  plus a no-op fragment shader (the rasterizer is discarded). */
	private fun linkTransformFeedbackProgram(): Int {
		val vertexShader = compileShader(GL20.GL_VERTEX_SHADER, tfDeformVertexShader(GlslDialect.Core330))
		val fragmentShader = compileShader(GL20.GL_FRAGMENT_SHADER, tfDiscardFragmentShader(GlslDialect.Core330))
		val program = GL20.glCreateProgram()
		GL20.glAttachShader(program, vertexShader)
		GL20.glAttachShader(program, fragmentShader)
		GL30.glTransformFeedbackVaryings(program, arrayOf("outWorld"), GL30.GL_INTERLEAVED_ATTRIBS)
		GL20.glLinkProgram(program)
		check(GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) != GL11.GL_FALSE) {
			"TF program link failed: ${GL20.glGetProgramInfoLog(program)}"
		}
		return program
	}

	private fun compileShader(stage: Int, source: String): Int {
		val shader = GL20.glCreateShader(stage)
		GL20.glShaderSource(shader, source)
		GL20.glCompileShader(shader)
		check(GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) != GL11.GL_FALSE) {
			"shader compile failed: ${GL20.glGetShaderInfoLog(shader)}"
		}
		return shader
	}

	/** Creates a hidden 1×1 GL 3.3 core window for headless rendering, or 0 if GLFW/GL is unavailable. */
	private fun createHeadlessGl(): Long {
		if (!GLFW.glfwInit()) {
			return 0L
		}
		GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE)
		GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3)
		GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3)
		GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE)
		GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE)
		val window = GLFW.glfwCreateWindow(1, 1, "umamo-gpu-tf", MemoryUtil.NULL, MemoryUtil.NULL)
		if (window == MemoryUtil.NULL) {
			GLFW.glfwTerminate()
			return 0L
		}
		GLFW.glfwMakeContextCurrent(window)
		GL.createCapabilities()
		return window
	}
}
