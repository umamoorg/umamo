package org.umamo.render

import android.opengl.GLES20
import android.opengl.GLES30

// Full-screen pass with no vertex buffer: the screen-covering triangle's corners come from gl_VertexID.
// GLES 3.0 (#version 300 es) is required for gl_VertexID and vertex-array objects.
// 頂点バッファ無しの全画面パス。三角形の頂点は gl_VertexID から生成する（GLES 3.0 が必要）。
private const val GRID_VERTEX_SHADER =
	"""
	#version 300 es
	void main() {
		gl_Position = vec4(float(gl_VertexID / 2) * 4.0 - 1.0, float(gl_VertexID % 2) * 4.0 - 1.0, 0.0, 1.0);
	}
	"""

// The world-aligned grid backdrop.  The fragment recovers its world position by inverting the same
// world-to-NDC affine the puppet is projected through, then draws anti-aliased major / minor lines where
// that world coord is near a multiple of the spacing.  The pixel scale comes from the affine, not GLSL
// derivatives, so it does not depend on any driver's fwidth accuracy.
// ワールド整列グリッド。worldToNdc を逆変換して各フラグメントのワールド座標を求め、主線・副線を描く。
private const val GRID_FRAGMENT_SHADER =
	"""
	#version 300 es
	precision highp float;
	out vec4 fragColor;
	uniform vec2 viewportSize;
	uniform vec4 worldToNdc;      // scaleX, scaleY, offsetX, offsetY
	uniform vec2 majorSpacing;    // world units per major cell, per axis
	uniform float subdivisions;   // minor lines per major cell
	uniform float lineWidthPx;    // line half-width in framebuffer pixels
	uniform vec3 backgroundColor;
	uniform vec3 majorColor;
	uniform vec3 minorColor;
	uniform vec2 gridOrigin;   // world point a major line passes through (the world axes)

	// Anti-aliased line coverage for a coordinate on a lattice of the given spacing.  cellDist is the
	// fractional distance to the nearest line in cell units (0 on a line, 0.5 mid-cell); scaling it by
	// spacing / worldPerPixel converts it to framebuffer pixels, so the band stays a constant on-screen
	// width at any zoom - derived from the affine, not GLSL derivatives, so it is driver-independent.
	float lineCoverage(vec2 world, vec2 spacing, vec2 worldPerPixel) {
		vec2 cell = world / spacing;
		vec2 cellDist = abs(fract(cell - 0.5) - 0.5);
		vec2 pixelDist = cellDist * spacing / worldPerPixel;
		float nearestPx = min(pixelDist.x, pixelDist.y);
		return 1.0 - smoothstep(0.0, lineWidthPx, nearestPx);
	}

	void main() {
		vec2 ndc = (gl_FragCoord.xy / viewportSize) * 2.0 - 1.0;
		vec2 world = (ndc - worldToNdc.zw) / worldToNdc.xy;
		vec2 worldPerPixel = abs((2.0 / viewportSize) / worldToNdc.xy);
		// Anchor the lattice on the world origin (the axes), so a major line always crosses the axes
		// rather than falling mid-cell for an arbitrary canvas size.
		vec2 gridWorld = world - gridOrigin;
		vec2 minorSpacing = majorSpacing / max(subdivisions, 1.0);
		vec2 minorScreenPx = minorSpacing / worldPerPixel;
		float minorFade = clamp((min(minorScreenPx.x, minorScreenPx.y) - 4.0) / 8.0, 0.0, 1.0);
		float minor = lineCoverage(gridWorld, minorSpacing, worldPerPixel) * minorFade;
		float major = lineCoverage(gridWorld, majorSpacing, worldPerPixel);
		vec3 color = mix(backgroundColor, minorColor, minor);
		color = mix(color, majorColor, major);
		fragColor = vec4(color, 1.0);
	}
	"""

// One axis line per draw: the two endpoints come from gl_VertexID (-1 / +1 along the line), the
// fixed coordinate and the orientation come in as uniforms, so one program draws both axes.
// 軸線 1 本を描くパス。端点は gl_VertexID から生成し、固定座標と向きは uniform で受け取る。
private const val AXIS_VERTEX_SHADER =
	"""
	#version 300 es
	uniform float linePositionNdc;
	uniform float lineVertical;
	void main() {
		float along = float(gl_VertexID) * 2.0 - 1.0;
		vec2 position = mix(vec2(along, linePositionNdc), vec2(linePositionNdc, along), lineVertical);
		gl_Position = vec4(position, 0.0, 1.0);
	}
	"""

private const val AXIS_FRAGMENT_SHADER =
	"""
	#version 300 es
	precision highp float;
	out vec4 fragColor;
	uniform vec3 lineColor;
	void main() {
		fragColor = vec4(lineColor, 1.0);
	}
	"""

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
			gridProgram = link(GRID_VERTEX_SHADER, GRID_FRAGMENT_SHADER)
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
			axisProgram = link(AXIS_VERTEX_SHADER, AXIS_FRAGMENT_SHADER)
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
		val vertexShader = compile(GLES20.GL_VERTEX_SHADER, vertexSource.trimIndent())
		val fragmentShader = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource.trimIndent())
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
