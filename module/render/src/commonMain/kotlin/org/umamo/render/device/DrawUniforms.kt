package org.umamo.render.device

import org.umamo.render.GridColors
import org.umamo.render.glsl.MAX_CORNERS

// The per-draw and per-pass shader inputs, as structs rather than named scalars.
//
// The renderer names no uniform. Each backend hand-writes one marshaller per struct: the GL family caches
// the locations once at pipeline creation and issues glUniform*; a Metal backend would write a mirroring
// MSL struct and setVertexBytes it. Deliberately NOT reflection-driven - kotlin-reflect does not exist on
// Kotlin/Native - and deliberately not a writeTo(sink), which would force this shared code to commit to a
// memory layout when std140 and MSL alignment disagree. commonMain supplies VALUES; layout is the
// backend's. That split is the cleanest line in the design.

/** The camera's world→NDC affine: `ndc = world * (scaleX, scaleY) + (offsetX, offsetY)`. */
internal data class WorldToNdc(val scaleX: Float, val scaleY: Float, val offsetX: Float, val offsetY: Float)

/**
 * One drawable's per-pose deform inputs: the active morph corners and the baked parent transform.
 *
 * MUTABLE AND REUSED. The renderer refills one instance per draw rather than allocating ~60 of these a
 * frame. A device implementation MUST therefore marshal every field before returning and MUST NOT retain
 * the instance. Both `glUniform*` and Metal's `setVertexBytes` copy, so both can honour that; a backend
 * that wanted to record and replay a command buffer could not, and would need its own copy.
 *
 * Getting this wrong renders garbage that looks exactly like a driver bug, which is why it is stated here
 * rather than left to be discovered.
 *
 * @property Int        cornerCount  Active corners, 0..MAX_CORNERS.
 * @property IntArray   cornerCell   Each active corner's keyform cell linear index; length MAX_CORNERS.
 * @property FloatArray cornerWeight Each active corner's blend weight; length MAX_CORNERS.
 * @property Int        parentType   0 = direct, 1 = rotation, 2 = warp.
 * @property FloatArray rotation     The rotation affine (c12,c13,c14,c15,ox,oy); read when parentType==1.
 * @property Int        warpColumns  Warp lattice columns;  read when parentType==2.
 * @property Int        warpRows     Warp lattice rows;     read when parentType==2.
 * @property Boolean    warpBilinear Bilinear vs triangle blend; read when parentType==2.
 */
internal class DeformUniforms {
	var cornerCount: Int = 0
	val cornerCell: IntArray = IntArray(MAX_CORNERS)
	val cornerWeight: FloatArray = FloatArray(MAX_CORNERS)
	var parentType: Int = 0
	val rotation: FloatArray = FloatArray(6)
	var warpColumns: Int = 0
	var warpRows: Int = 0
	var warpBilinear: Boolean = false
}

/**
 * One draw's fragment inputs, shared by the puppet and atlas-page pipelines.
 *
 * Mutable and reused on the same contract as [DeformUniforms].
 *
 * @property Boolean useTexture   Sample [DrawTextures.atlas] rather than the flat colour below.
 * @property Float   colorRed     Flat colour red,   used when [useTexture] is false.
 * @property Float   colorGreen   Flat colour green, used when [useTexture] is false.
 * @property Float   colorBlue    Flat colour blue,  used when [useTexture] is false.
 * @property Float   colorAlpha   Flat colour alpha, used when [useTexture] is false.
 * @property Float   opacity      The pose-blended opacity, multiplied into alpha.
 * @property Boolean useMask      Multiply alpha by the mask coverage.
 * @property Boolean invertMask   Use 1 - coverage instead.
 * @property Float   highlight    How far to tint toward the highlight colour (0 = untinted).
 * @property Float   highlightRed   Highlight tint red.
 * @property Float   highlightGreen Highlight tint green.
 * @property Float   highlightBlue  Highlight tint blue.
 */
internal class FragmentUniforms {
	var useTexture: Boolean = false
	var colorRed: Float = 0f
	var colorGreen: Float = 0f
	var colorBlue: Float = 0f
	var colorAlpha: Float = 0f
	var opacity: Float = 1f
	var useMask: Boolean = false
	var invertMask: Boolean = false
	var highlight: Float = 0f
	var highlightRed: Float = 0f
	var highlightGreen: Float = 0f
	var highlightBlue: Float = 0f
}

/**
 * The textures one draw samples.  Null means the draw does not use that slot.
 *
 * @property GpuTexture? atlas             The art atlas page, or null to draw the flat colour.
 * @property GpuTexture? maskCoverage      The clip mask's coverage, or null when unmasked.
 * @property GpuTexture? deltaTexture      The mesh's morph delta table (deform draws only).
 * @property GpuTexture? warpControlPoints The parent warp's baked control points, or null when the parent
 *   is not a warp.
 */
internal class DrawTextures(
	val atlas: GpuTexture? = null,
	val maskCoverage: GpuTexture? = null,
	val deltaTexture: GpuTexture? = null,
	val warpControlPoints: GpuTexture? = null,
)

/**
 * The grid backdrop's inputs for one pass.
 *
 * @property WorldToNdc worldToNdc    The affine the fragment inverts to recover its world position.
 * @property Float      originX       World x the lattice is anchored on (a major line crosses it).
 * @property Float      originY       World y the lattice is anchored on.
 * @property Float      majorSpacingX Major line spacing along X, in world units.
 * @property Float      majorSpacingY Major line spacing along Y, in world units.
 * @property Int        subdivisions  Minor lines per major cell.
 * @property Float      lineWidthPx   Line half-width in framebuffer pixels (accounts for supersampling).
 * @property GridColors colors        The background / major / minor colours.
 */
internal data class GridUniforms(
	val worldToNdc: WorldToNdc,
	val originX: Float,
	val originY: Float,
	val majorSpacingX: Float,
	val majorSpacingY: Float,
	val subdivisions: Int,
	val lineWidthPx: Float,
	val colors: GridColors,
)

/**
 * One axis line's inputs.
 *
 * @property Float   linePositionNdc The line's fixed coordinate, in NDC.
 * @property Boolean vertical        True for the vertical axis, false for the horizontal one.
 * @property Float   red             Line red.
 * @property Float   green           Line green.
 * @property Float   blue            Line blue.
 */
internal data class AxisLineUniforms(
	val linePositionNdc: Float,
	val vertical: Boolean,
	val red: Float,
	val green: Float,
	val blue: Float,
)
