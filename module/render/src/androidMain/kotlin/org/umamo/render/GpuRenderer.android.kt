package org.umamo.render

import android.opengl.GLES20
import android.opengl.GLES30
import org.umamo.render.glsl.GlslDialect
import org.umamo.render.glsl.axisFragmentShader
import org.umamo.render.glsl.axisVertexShader
import org.umamo.render.glsl.gridFragmentShader
import org.umamo.render.glsl.gridVertexShader

// GLES 3.0 (the Android baseline: VAOs and gl_VertexID need it); the shared backdrop GLSL in
// org.umamo.render.glsl is emitted for it.
private val DIALECT = GlslDialect.Es300

/**
 * Android actual: the platform GLES API (android.opengl). No LWJGL - Android provides GLES
 * directly, which is exactly why this is `expect`/`actual` rather than shared code.
 *
 * Android 実装：プラットフォーム標準の GLES（android.opengl）を使う。
 */
actual class GpuRenderer actual constructor() {
	// Lazily built on the first grid() call (needs a current context). 0 = not yet built.
	private var gridProgram = 0
	private var gridVao = 0

	// Lazily built on the first axisLines() call, mirroring the grid pair above.
	private var axisProgram = 0
	private var axisVao = 0

	actual fun clear(red: Float, green: Float, blue: Float, alpha: Float) {
		GLES20.glClearColor(red, green, blue, alpha)
		GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
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
			val vaos = IntArray(1)
			GLES30.glGenVertexArrays(1, vaos, 0)
			gridVao = vaos[0]
		}
		GLES20.glDisable(GLES20.GL_BLEND) // opaque fill: blend off so it overwrites stale pixels outright
		GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
		GLES20.glUseProgram(gridProgram)
		GLES20.glUniform2f(GLES20.glGetUniformLocation(gridProgram, "viewportSize"), viewportWidth.toFloat(), viewportHeight.toFloat())
		GLES20.glUniform4f(
			GLES20.glGetUniformLocation(gridProgram, "worldToNdc"),
			worldToNdc[0],
			worldToNdc[1],
			worldToNdc[2],
			worldToNdc[3],
		)
		GLES20.glUniform2f(GLES20.glGetUniformLocation(gridProgram, "majorSpacing"), majorSpacingX, majorSpacingY)
		GLES20.glUniform2f(GLES20.glGetUniformLocation(gridProgram, "gridOrigin"), originX, originY)
		GLES20.glUniform1f(GLES20.glGetUniformLocation(gridProgram, "subdivisions"), subdivisions.toFloat())
		GLES20.glUniform1f(GLES20.glGetUniformLocation(gridProgram, "lineWidthPx"), lineWidthPx)
		GLES20.glUniform3f(
			GLES20.glGetUniformLocation(gridProgram, "backgroundColor"),
			colors.backgroundRed,
			colors.backgroundGreen,
			colors.backgroundBlue,
		)
		GLES20.glUniform3f(GLES20.glGetUniformLocation(gridProgram, "majorColor"), colors.majorRed, colors.majorGreen, colors.majorBlue)
		GLES20.glUniform3f(GLES20.glGetUniformLocation(gridProgram, "minorColor"), colors.minorRed, colors.minorGreen, colors.minorBlue)
		GLES30.glBindVertexArray(gridVao)
		GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
		GLES30.glBindVertexArray(0)
		GLES20.glUseProgram(0)
	}

	actual fun axisLines(originNdcX: Float, originNdcY: Float, colors: WorldAxisColors) {
		if (axisProgram == 0) {
			axisProgram = link(axisVertexShader(DIALECT), axisFragmentShader(DIALECT))
			val vaos = IntArray(1)
			GLES30.glGenVertexArrays(1, vaos, 0)
			axisVao = vaos[0]
		}
		GLES20.glDisable(GLES20.GL_BLEND) // opaque 1 px lines over the grid
		GLES20.glUseProgram(axisProgram)
		GLES30.glBindVertexArray(axisVao)
		val positionUniform = GLES20.glGetUniformLocation(axisProgram, "linePositionNdc")
		val verticalUniform = GLES20.glGetUniformLocation(axisProgram, "lineVertical")
		val colorUniform = GLES20.glGetUniformLocation(axisProgram, "lineColor")
		// The horizontal X axis (red) at the origin's y.
		GLES20.glUniform1f(positionUniform, originNdcY)
		GLES20.glUniform1f(verticalUniform, 0f)
		GLES20.glUniform3f(colorUniform, colors.xRed, colors.xGreen, colors.xBlue)
		GLES20.glDrawArrays(GLES20.GL_LINES, 0, 2)
		// The vertical Z axis (blue) at the origin's x.
		GLES20.glUniform1f(positionUniform, originNdcX)
		GLES20.glUniform1f(verticalUniform, 1f)
		GLES20.glUniform3f(colorUniform, colors.zRed, colors.zGreen, colors.zBlue)
		GLES20.glDrawArrays(GLES20.GL_LINES, 0, 2)
		GLES30.glBindVertexArray(0)
		GLES20.glUseProgram(0)
	}

	actual fun describeContext(): String {
		val renderer = GLES20.glGetString(GLES20.GL_RENDERER)
		val version = GLES20.glGetString(GLES20.GL_VERSION)
		val vendor = GLES20.glGetString(GLES20.GL_VENDOR)
		val glsl = GLES20.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION)
		return "renderer=$renderer | version=$version | vendor=$vendor | glsl=$glsl"
	}

	/**
	 * Compiles and links one of this seam's small programs, throwing with the GL info log on any failure.
	 *
	 * @param String vertexSource GLSL ES vertex-stage source.
	 * @param String fragmentSource GLSL ES fragment-stage source.
	 * @return Int the linked program handle.
	 */
	private fun link(vertexSource: String, fragmentSource: String): Int {
		val vertexShader = compile(GLES20.GL_VERTEX_SHADER, vertexSource)
		val fragmentShader = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
		val program = GLES20.glCreateProgram()
		GLES20.glAttachShader(program, vertexShader)
		GLES20.glAttachShader(program, fragmentShader)
		GLES20.glLinkProgram(program)
		val linkStatus = IntArray(1)
		GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
		check(linkStatus[0] != GLES20.GL_FALSE) {
			"GpuRenderer program link failed: ${GLES20.glGetProgramInfoLog(program)}"
		}
		GLES20.glDeleteShader(vertexShader)
		GLES20.glDeleteShader(fragmentShader)
		return program
	}

	/**
	 * Compiles one shader stage, throwing with the GL info log if compilation fails.
	 *
	 * @param Int    stage  GLES shader type (GL_VERTEX_SHADER / GL_FRAGMENT_SHADER).
	 * @param String source GLSL ES source.
	 * @return Int the compiled shader handle.
	 */
	private fun compile(stage: Int, source: String): Int {
		val shader = GLES20.glCreateShader(stage)
		GLES20.glShaderSource(shader, source)
		GLES20.glCompileShader(shader)
		val compileStatus = IntArray(1)
		GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
		check(compileStatus[0] != GLES20.GL_FALSE) {
			"GpuRenderer shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}"
		}
		return shader
	}
}
