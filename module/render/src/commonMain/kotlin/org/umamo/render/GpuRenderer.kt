package org.umamo.render

/**
 * The three colors of the viewport grid backdrop, as 0..1 linear RGB triples: the flat canvas fill, the
 * major grid line color, and the minor (subdivision) grid line color.  Kept as plain floats (not a Compose
 * Color) so :render stays free of any UI dependency; the editor maps its themed palette into this at the
 * call site.  [Classic] is a neutral grey grid, used as the default so a caller that does not theme the
 * backdrop still gets a sensible surface.
 *
 * ビューポートのグリッド背景の 3 色（0..1 の RGB）。背景・主線・副線。:render を UI 非依存に保つため素の float。
 *
 * @property Float backgroundRed   Red of the flat canvas fill.
 * @property Float backgroundGreen Green of the flat canvas fill.
 * @property Float backgroundBlue  Blue of the flat canvas fill.
 * @property Float majorRed        Red of the major grid line.
 * @property Float majorGreen      Green of the major grid line.
 * @property Float majorBlue       Blue of the major grid line.
 * @property Float minorRed        Red of the minor (subdivision) grid line.
 * @property Float minorGreen      Green of the minor grid line.
 * @property Float minorBlue       Blue of the minor grid line.
 */
data class GridColors(
	val backgroundRed: Float,
	val backgroundGreen: Float,
	val backgroundBlue: Float,
	val majorRed: Float,
	val majorGreen: Float,
	val majorBlue: Float,
	val minorRed: Float,
	val minorGreen: Float,
	val minorBlue: Float,
) {
	companion object {
		/** A neutral dark-grey grid: #2E2E2E fill, #484848 major lines, #3A3A3A minor lines. */
		val Classic: GridColors =
			GridColors(
				0.204f,
				0.204f,
				0.204f,
				0.317f,
				0.317f,
				0.317f,
				0.270f,
				0.270f,
				0.270f,
			)
	}
}

/**
 * The two world-axis line colors, as 0..1 linear RGB triples - the red X axis and the blue Z axis
 * drawn through the world origin behind the puppet (the project's display convention is Y+ forward,
 * Z+ up, so the vertical world axis is presented as Z).  Plain floats for the same reason as
 * [GridColors]: :render stays free of any UI dependency.
 *
 * ワールド軸線の 2 色（0..1 の RGB）。横が赤い X 軸、縦が青い Z 軸（表示規約は Y+ 前、Z+ 上）。
 *
 * @property Float xRed   Red of the horizontal X axis line.
 * @property Float xGreen Green of the horizontal X axis line.
 * @property Float xBlue  Blue of the horizontal X axis line.
 * @property Float zRed   Red of the vertical Z axis line.
 * @property Float zGreen Green of the vertical Z axis line.
 * @property Float zBlue  Blue of the vertical Z axis line.
 */
data class WorldAxisColors(
	val xRed: Float,
	val xGreen: Float,
	val xBlue: Float,
	val zRed: Float,
	val zGreen: Float,
	val zBlue: Float,
) {
	companion object {
		/** Blender-like axis colors: a muted red X and a muted blue Z. */
		val Classic: WorldAxisColors = WorldAxisColors(0.84f, 0.31f, 0.36f, 0.31f, 0.44f, 0.85f)
	}
}

/**
 * The platform GPU surface seam.
 *
 * The real per-puppet renderer runs the morph-blend delta-sum (`p = base + Σ wᵢ·Δᵢ`) in a vertex
 * shader (see [org.umamo.render.gl.GlPuppetRenderer] on desktop). This seam covers the backdrop and
 * the low-level primitives both backends - LWJGL/desktop and GLES/Android - share verbatim: [clear],
 * [grid] (the canvas backdrop), and [axisLines] (the world-origin axes).
 *
 * `expect` declares a platform-provided class; each target supplies an `actual` (the modern
 * KMP replacement for `#ifdef`/per-platform reflection). The compiler fails the build if any
 * target is missing its `actual`.
 *
 * GPU サーフェスの継ぎ目。expect/actual で各プラットフォーム実装を切り替える。
 */
expect class GpuRenderer() {
	/** Clears the current GL framebuffer to the given color. Assumes a context is current. */
	fun clear(red: Float, green: Float, blue: Float, alpha: Float)

	/**
	 * Fills the current framebuffer with the viewport grid backdrop: a flat background fill overlaid with
	 * anti-aliased major and minor (subdivision) grid lines.  The grid is world-aligned: the lines sit at
	 * world coordinates that are multiples of the spacings, so they scale and pan with the camera and land
	 * exactly on the coordinates the grid snap rounds to.  The caller passes
	 * the same world-to-NDC affine [worldToNdc] the puppet is projected through (`ndc = world * (scaleX,
	 * scaleY) + (offsetX, offsetY)`); the shader inverts it per fragment to recover the fragment's world
	 * position.  The lattice is anchored on ([originX], [originY]) - the world origin the axes cross - so a
	 * major line always passes through the axes rather than falling mid-cell for an arbitrary canvas size.
	 * Major lines are drawn every [majorSpacingX] / [majorSpacingY] world units; between them
	 * [subdivisions] minor lines subdivide each cell (minor spacing = major / subdivisions).  Opaque, so it
	 * both clears and paints in one pass.  Assumes a context is current.
	 *
	 * ワールド整列のグリッド背景。主線・副線を反ズーム追従で描く。worldToNdc をシェーダで逆変換して各フラグメントの
	 * ワールド座標を求める。副線間隔 = 主線間隔 / subdivisions。不透明なのでクリアと描画を 1 パスで兼ねる。
	 *
	 * @param Int       viewportWidth  Framebuffer width in pixels.
	 * @param Int       viewportHeight Framebuffer height in pixels.
	 * @param FloatArray worldToNdc    The (scaleX, scaleY, offsetX, offsetY) world-to-NDC affine.
	 * @param Float     originX        World x the lattice is anchored on (a major line passes through it).
	 * @param Float     originY        World y the lattice is anchored on (a major line passes through it).
	 * @param Float     majorSpacingX  Major grid line spacing along X, in world units.
	 * @param Float     majorSpacingY  Major grid line spacing along Y, in world units.
	 * @param Int       subdivisions   Minor lines per major cell (minor spacing = major / subdivisions).
	 * @param Float     lineWidthPx    Grid line half-width in framebuffer pixels (accounts for supersampling).
	 * @param GridColors colors        The background / major / minor colors; defaults to [GridColors.Classic].
	 */
	fun grid(
		viewportWidth: Int,
		viewportHeight: Int,
		worldToNdc: FloatArray,
		originX: Float,
		originY: Float,
		majorSpacingX: Float,
		majorSpacingY: Float,
		subdivisions: Int,
		lineWidthPx: Float,
		colors: GridColors = GridColors.Classic,
	)

	/**
	 * Draws the world-origin axis lines over the backdrop: a full-width horizontal X axis line at
	 * [originNdcY] and a full-height vertical Z axis line at [originNdcX] (both 1 px, opaque).  The
	 * origin is passed in NDC so the caller owns the world-to-view projection; a line whose NDC
	 * position is outside the [-1, 1] view is clipped away by the GPU.  Drawn between the
	 * grid backdrop and the drawables, so the axes sit behind the puppet.  Assumes a context is
	 * current.
	 *
	 * ワールド原点の軸線を描く。横に赤い X 軸、縦に青い Z 軸（NDC 指定、パペットの背面）。
	 *
	 * @param Float originNdcX The world origin's x in NDC (the vertical Z axis line's position).
	 * @param Float originNdcY The world origin's y in NDC (the horizontal X axis line's position).
	 * @param WorldAxisColors colors The two axis colors; defaults to [WorldAxisColors.Classic].
	 */
	fun axisLines(originNdcX: Float, originNdcY: Float, colors: WorldAxisColors = WorldAxisColors.Classic)

	/**
	 * Returns the current GL context's renderer/version/vendor as one line - a startup
	 * diagnostic to confirm which backend actually drives the viewport (e.g. a hardware Mesa
	 * driver vs `llvmpipe` software). Must be called with a context current.
	 */
	fun describeContext(): String
}
