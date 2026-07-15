package org.umamo.render

import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30
import org.umamo.render.glsl.GlslDialect
import org.umamo.render.glsl.axisFragmentShader
import org.umamo.render.glsl.axisVertexShader
import org.umamo.render.glsl.gridFragmentShader
import org.umamo.render.glsl.gridVertexShader

// Desktop OpenGL 3.3 core; the shared backdrop GLSL in org.umamo.render.glsl is emitted for it.
private val DIALECT = GlslDialect.Core330

/**
 * Desktop actual: LWJGL bindings to the host's OpenGL. Callers (the offscreen viewport
 * service) must have made a GL context current on the calling thread before invoking these.
 *
 * デスクトップ実装：LWJGL 経由でホストの OpenGL を叩く。
 */
actual class GpuRenderer actual constructor() {
	// Lazily built on the first grid() call (needs a current context). 0 = not yet built. These
	// live for the owning instance, so transient `GpuRenderer().describeContext()` uses allocate nothing.
	private var gridProgram = 0
	private var gridVao = 0

	// Lazily built on the first axisLines() call, mirroring the grid pair above.
	private var axisProgram = 0
	private var axisVao = 0

	actual fun clear(red: Float, green: Float, blue: Float, alpha: Float) {
		GL11.glClearColor(red, green, blue, alpha)
		GL11.glClear(GL11.GL_COLOR_BUFFER_BIT or GL11.GL_DEPTH_BUFFER_BIT)
	}

	actual fun grid(
		viewportWidth: Int,
		viewportHeight: Int,
		worldToNdc: FloatArray,
		originX: Float,
		originY: Float,
		majorSpacingX: Float,
		majorSpacingY: Float,
		subdivisions: Int,
		lineWidthPx: Float,
		colors: GridColors,
	) {
		if (gridProgram == 0) {
			gridProgram = link(gridVertexShader(DIALECT), gridFragmentShader(DIALECT))
			gridVao = GL30.glGenVertexArrays() // core profile requires a bound VAO even for an attribute-less draw
		}
		GL11.glDisable(GL11.GL_BLEND) // opaque fill: blend off so it overwrites stale pixels outright
		GL11.glViewport(0, 0, viewportWidth, viewportHeight)
		GL20.glUseProgram(gridProgram)
		GL20.glUniform2f(GL20.glGetUniformLocation(gridProgram, "viewportSize"), viewportWidth.toFloat(), viewportHeight.toFloat())
		GL20.glUniform4f(
			GL20.glGetUniformLocation(gridProgram, "worldToNdc"),
			worldToNdc[0],
			worldToNdc[1],
			worldToNdc[2],
			worldToNdc[3],
		)
		GL20.glUniform2f(GL20.glGetUniformLocation(gridProgram, "majorSpacing"), majorSpacingX, majorSpacingY)
		GL20.glUniform2f(GL20.glGetUniformLocation(gridProgram, "gridOrigin"), originX, originY)
		GL20.glUniform1f(GL20.glGetUniformLocation(gridProgram, "subdivisions"), subdivisions.toFloat())
		GL20.glUniform1f(GL20.glGetUniformLocation(gridProgram, "lineWidthPx"), lineWidthPx)
		GL20.glUniform3f(
			GL20.glGetUniformLocation(gridProgram, "backgroundColor"),
			colors.backgroundRed,
			colors.backgroundGreen,
			colors.backgroundBlue,
		)
		GL20.glUniform3f(GL20.glGetUniformLocation(gridProgram, "majorColor"), colors.majorRed, colors.majorGreen, colors.majorBlue)
		GL20.glUniform3f(GL20.glGetUniformLocation(gridProgram, "minorColor"), colors.minorRed, colors.minorGreen, colors.minorBlue)
		GL30.glBindVertexArray(gridVao)
		GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3)
		GL30.glBindVertexArray(0)
		GL20.glUseProgram(0)
	}

	actual fun axisLines(originNdcX: Float, originNdcY: Float, colors: WorldAxisColors) {
		if (axisProgram == 0) {
			axisProgram = link(axisVertexShader(DIALECT), axisFragmentShader(DIALECT))
			axisVao = GL30.glGenVertexArrays() // core profile requires a bound VAO even for an attribute-less draw
		}
		GL11.glDisable(GL11.GL_BLEND) // opaque 1 px lines over the grid
		GL20.glUseProgram(axisProgram)
		GL30.glBindVertexArray(axisVao)
		val positionUniform = GL20.glGetUniformLocation(axisProgram, "linePositionNdc")
		val verticalUniform = GL20.glGetUniformLocation(axisProgram, "lineVertical")
		val colorUniform = GL20.glGetUniformLocation(axisProgram, "lineColor")
		// The horizontal X axis (red) at the origin's y.
		GL20.glUniform1f(positionUniform, originNdcY)
		GL20.glUniform1f(verticalUniform, 0f)
		GL20.glUniform3f(colorUniform, colors.xRed, colors.xGreen, colors.xBlue)
		GL11.glDrawArrays(GL11.GL_LINES, 0, 2)
		// The vertical Z axis (blue) at the origin's x.
		GL20.glUniform1f(positionUniform, originNdcX)
		GL20.glUniform1f(verticalUniform, 1f)
		GL20.glUniform3f(colorUniform, colors.zRed, colors.zGreen, colors.zBlue)
		GL11.glDrawArrays(GL11.GL_LINES, 0, 2)
		GL30.glBindVertexArray(0)
		GL20.glUseProgram(0)
	}

	actual fun describeContext(): String {
		val renderer = GL11.glGetString(GL11.GL_RENDERER)
		val version = GL11.glGetString(GL11.GL_VERSION)
		val vendor = GL11.glGetString(GL11.GL_VENDOR)
		val glsl = GL11.glGetString(GL20.GL_SHADING_LANGUAGE_VERSION)
		return "renderer=$renderer | version=$version | vendor=$vendor | glsl=$glsl"
	}

	/**
	 * Compiles and links one of this seam's small programs, throwing with the GL info log on any failure.
	 *
	 * @param String vertexSource GLSL vertex-stage source.
	 * @param String fragmentSource GLSL fragment-stage source.
	 * @return Int the linked program handle.
	 */
	private fun link(vertexSource: String, fragmentSource: String): Int {
		val vertexShader = compile(GL20.GL_VERTEX_SHADER, vertexSource)
		val fragmentShader = compile(GL20.GL_FRAGMENT_SHADER, fragmentSource)
		val program = GL20.glCreateProgram()
		GL20.glAttachShader(program, vertexShader)
		GL20.glAttachShader(program, fragmentShader)
		GL20.glLinkProgram(program)
		check(GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) != GL11.GL_FALSE) {
			"GpuRenderer program link failed: ${GL20.glGetProgramInfoLog(program)}"
		}
		GL20.glDeleteShader(vertexShader)
		GL20.glDeleteShader(fragmentShader)
		return program
	}

	/**
	 * Compiles one shader stage, throwing with the GL info log if compilation fails.
	 *
	 * @param Int    stage  GL shader type (GL_VERTEX_SHADER / GL_FRAGMENT_SHADER).
	 * @param String source GLSL source.
	 * @return Int the compiled shader handle.
	 */
	private fun compile(stage: Int, source: String): Int {
		val shader = GL20.glCreateShader(stage)
		GL20.glShaderSource(shader, source)
		GL20.glCompileShader(shader)
		check(GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) != GL11.GL_FALSE) {
			"GpuRenderer shader compile failed: ${GL20.glGetShaderInfoLog(shader)}"
		}
		return shader
	}
}
