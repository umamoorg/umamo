package org.umamo.render

import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30

// Full-screen pass with no vertex buffer: the three corners of a screen-covering triangle are derived
// from gl_VertexID, so no attributes (and on core profile, only an empty VAO) are needed.
// 頂点バッファ無しの全画面パス。三角形の頂点は gl_VertexID から生成する。
private const val GRID_VERTEX_SHADER =
	"""
	#version 330 core
	void main() {
		// id 0 -> (-1,-1), 1 -> (-1,3), 2 -> (3,-1): one oversized triangle covering the viewport.
		gl_Position = vec4(float(gl_VertexID / 2) * 4.0 - 1.0, float(gl_VertexID % 2) * 4.0 - 1.0, 0.0, 1.0);
	}
	"""

// The world-aligned grid backdrop.  The fragment recovers its world position by inverting the same
// world-to-NDC affine the puppet is projected through, then draws anti-aliased lines wherever that
// world coord is near a multiple of the major or minor spacing.  Colors and spacings arrive as uniforms
// so the backdrop follows the editor theme and the per-document grid config.
// ワールド整列グリッド。worldToNdc を逆変換して各フラグメントのワールド座標を求め、主線・副線を描く。
private const val GRID_FRAGMENT_SHADER =
	"""
	#version 330 core
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
		// World units per framebuffer pixel, from the affine: an NDC span of 2 covers viewportSize pixels
		// and worldToNdc.xy world units per NDC unit.  abs() drops the Y-axis flip sign.
		vec2 worldPerPixel = abs((2.0 / viewportSize) / worldToNdc.xy);
		// Anchor the lattice on the world origin (the axes), so a major line always crosses the axes
		// rather than falling mid-cell for an arbitrary canvas size.
		vec2 gridWorld = world - gridOrigin;
		vec2 minorSpacing = majorSpacing / max(subdivisions, 1.0);
		// Fade the minor lines out as their on-screen spacing collapses, so a zoomed-out grid does not
		// alias into a solid wash: minorScreenPx is the minor spacing measured in framebuffer pixels.
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
	#version 330 core
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
	#version 330 core
	out vec4 fragColor;
	uniform vec3 lineColor;
	void main() {
		fragColor = vec4(lineColor, 1.0);
	}
	"""

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
			gridProgram = link(GRID_VERTEX_SHADER, GRID_FRAGMENT_SHADER)
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
			axisProgram = link(AXIS_VERTEX_SHADER, AXIS_FRAGMENT_SHADER)
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
		val vertexShader = compile(GL20.GL_VERTEX_SHADER, vertexSource.trimIndent())
		val fragmentShader = compile(GL20.GL_FRAGMENT_SHADER, fragmentSource.trimIndent())
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
