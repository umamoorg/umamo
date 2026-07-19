package org.umamo.render.eval

import org.umamo.runtime.eval.MeshScalars
import org.umamo.runtime.eval.WeightedCell
import org.umamo.runtime.eval.blendScalarsFromCorners
import org.umamo.runtime.eval.cellsByLinearIndex
import org.umamo.runtime.eval.gridCorners
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.GlueForm
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PartForm

/*
 * Grid sampling over the runtime's shared multilinear corner selection. The corner selection,
 * bracket, and pose-sampling primitives (bindBracket / gridCorners / cellsByLinearIndex /
 * blendScalarsFromCorners, plus the blend-shape default-pose reference helpers) live in
 * org.umamo.runtime.eval (KeyformGridSampling.kt) so Moc3Import shares them - see the note there.
 * This file keeps the render-side blends built on top of them.
 */

/**
 * Samples an art-mesh keyform grid into local (parent-deformer-space) vertex positions:
 * `base + Σ wᵢ·Δᵢ` (interleaved x,y). Because the corner weights sum to 1, this equals the
 * Umamo C++ Runtime's absolute-keyform blend. Returns null when the mesh is hidden at
 * these parameters.
 *
 * @param KeyformGrid grid       The mesh's keyform grid.
 * @param FloatArray  base       The mesh's rest-pose positions.
 * @param Function    paramValue Current value for a given parameter id.
 * @return FloatArray? The local vertex positions, or null when hidden.
 */
internal fun sampleMeshLocal(
	grid: KeyformGrid<MeshForm>,
	base: FloatArray,
	paramValue: (ParameterId) -> Float,
): FloatArray? {
	val corners = gridCorners(grid, paramValue) ?: return null
	return blendLocalFromCorners(grid, base, corners)
}

/**
 * Blends a mesh's local positions from precomputed corners: `base + Σ wᵢ·Δᵢ`. Splitting corner selection
 * out of [sampleMeshLocal] lets `preparePose` compute the weights once - backend-neutrally - and feed both
 * the CPU apply path and (later) the GPU shader the same corner set.
 *
 * @param KeyformGrid        grid    The mesh's keyform grid (source of the cells' deltas).
 * @param FloatArray         base    The mesh's rest-pose positions (interleaved x,y).
 * @param List<WeightedCell> corners The active keyform corners + weights (from [gridCorners]).
 * @return FloatArray The blended local positions (interleaved x,y).
 */
internal fun blendLocalFromCorners(grid: KeyformGrid<MeshForm>, base: FloatArray, corners: List<WeightedCell>): FloatArray {
	val cells = cellsByLinearIndex(grid)
	val out = base.copyOf()
	for (corner in corners) {
		val delta = cells[corner.linearIndex]?.form?.positionDeltas ?: continue
		val count = minOf(out.size, delta.size)
		for (coordIndex in 0 until count) {
			out[coordIndex] += corner.weight * delta[coordIndex]
		}
	}
	return out
}

/**
 * Blends an art-mesh keyform grid's scalar attributes (draw order, opacity) at the current parameters,
 * reusing the same multilinear corner weights as [sampleMeshLocal] (so the scalars track the geometry).
 * Returns null when the mesh is hidden - the same out-of-range condition that hides the geometry.
 *
 * @param KeyformGrid grid       The mesh's keyform grid.
 * @param Function    paramValue Current value for a given parameter id.
 * @return MeshScalars? The blended draw order + opacity, or null when hidden.
 */
internal fun sampleMeshScalars(
	grid: KeyformGrid<MeshForm>,
	paramValue: (ParameterId) -> Float,
): MeshScalars? {
	val corners = gridCorners(grid, paramValue) ?: return null
	return blendScalarsFromCorners(grid, corners)
}

/**
 * Blends a glue's weld intensity over its keyform grid at the current parameters. Returns 1 (full weld)
 * when the controlling axis is out of range - constant single-key glues always blend to their cell value.
 *
 * @param KeyformGrid grid       The glue's intensity keyform grid.
 * @param Function    paramValue Current value for a given parameter id.
 * @return Float The blended weld intensity.
 */
internal fun sampleGlueIntensity(grid: KeyformGrid<GlueForm>, paramValue: (ParameterId) -> Float): Float {
	val corners = gridCorners(grid, paramValue) ?: return 1f
	val cells = cellsByLinearIndex(grid)
	var intensity = 0f
	for (corner in corners) {
		intensity += corner.weight * (cells[corner.linearIndex]?.form?.intensity ?: 0f)
	}
	return intensity
}

/**
 * Blends a draw-order group part's draw order over its keyform grid at the current parameters - the
 * animated part draw order for a parameter-driven group part. Returns null when the
 * controlling axis is out of range, so the caller falls back to the part's static draw order.
 *
 * @param KeyformGrid grid       The group part's keyform grid.
 * @param Function    paramValue Current value for a given parameter id.
 * @return Float? The blended part draw order, or null when out of range.
 */
internal fun samplePartDrawOrder(grid: KeyformGrid<PartForm>, paramValue: (ParameterId) -> Float): Float? {
	val corners = gridCorners(grid, paramValue) ?: return null
	val cells = cellsByLinearIndex(grid)
	var drawOrder = 0f
	for (corner in corners) {
		drawOrder += corner.weight * (cells[corner.linearIndex]?.form?.drawOrder ?: 0f)
	}
	return drawOrder
}

/**
 * An offscreen part's pose-blended composite channels - what the renderer applies when it
 * composites the part's subtree buffer back into the scene.
 *
 * @property Float    opacity       The composite opacity (0..1).
 * @property ColorRgb multiplyColor The composite multiply color.
 * @property ColorRgb screenColor   The composite screen color.
 */
internal class PartRenderState(
	val opacity: Float,
	val multiplyColor: ColorRgb,
	val screenColor: ColorRgb,
)

/**
 * Blends an offscreen part's composite channels (opacity, multiply/screen colors) over its keyform
 * grid at the current parameters - the same multilinear weights as [samplePartDrawOrder], applied
 * per channel.  Returns null when the controlling axis is out of range, so the caller falls back
 * to the static channels on the part's PartOffscreen.
 *
 * @param KeyformGrid grid       The part's keyform grid.
 * @param Function    paramValue Current value for a given parameter id.
 * @return PartRenderState? The blended channels, or null when out of range.
 */
internal fun samplePartRenderState(grid: KeyformGrid<PartForm>, paramValue: (ParameterId) -> Float): PartRenderState? {
	val corners = gridCorners(grid, paramValue) ?: return null
	val cells = cellsByLinearIndex(grid)
	var opacity = 0f
	var multiplyRed = 0f
	var multiplyGreen = 0f
	var multiplyBlue = 0f
	var screenRed = 0f
	var screenGreen = 0f
	var screenBlue = 0f
	for (corner in corners) {
		val form = cells[corner.linearIndex]?.form ?: continue
		opacity += corner.weight * form.opacity
		multiplyRed += corner.weight * form.multiplyColor.red
		multiplyGreen += corner.weight * form.multiplyColor.green
		multiplyBlue += corner.weight * form.multiplyColor.blue
		screenRed += corner.weight * form.screenColor.red
		screenGreen += corner.weight * form.screenColor.green
		screenBlue += corner.weight * form.screenColor.blue
	}
	return PartRenderState(
		opacity = opacity,
		multiplyColor = ColorRgb(multiplyRed, multiplyGreen, multiplyBlue),
		screenColor = ColorRgb(screenRed, screenGreen, screenBlue),
	)
}

/**
 * Evaluates a direct (deformer-less) art mesh into world positions: the keyform-blended local
 * vertices with the Y component negated (`vp = (x, −y)`; only Y flips). This matches the CMO3/MOC3
 * world-space convention, which is Y-down relative to the local mesh space Umamo blends in - required for
 * preview parity with the official Cubism Editor. Returns null when the mesh is hidden at these
 * parameters. Deformer-parented meshes go through the cascade instead, which applies the same negation
 * after composing through the parent transform.
 *
 * @param KeyformGrid grid       The mesh's keyform grid.
 * @param FloatArray  base       The mesh's rest-pose positions (interleaved x,y).
 * @param Function    paramValue Current value for a given parameter id.
 * @return FloatArray? World positions (interleaved x,y), or null when hidden.
 */
internal fun evalDirectMeshWorld(
	grid: KeyformGrid<MeshForm>,
	base: FloatArray,
	paramValue: (ParameterId) -> Float,
): FloatArray? {
	val world = sampleMeshLocal(grid, base, paramValue) ?: return null
	var yIndex = 1
	while (yIndex < world.size) {
		world[yIndex] = -world[yIndex]
		yIndex += 2
	}
	return world
}
