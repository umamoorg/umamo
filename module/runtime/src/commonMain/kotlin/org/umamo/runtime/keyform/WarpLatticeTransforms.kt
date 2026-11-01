package org.umamo.runtime.keyform

import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.WarpForm
import org.umamo.runtime.model.WarpLatticeForm
import org.umamo.runtime.model.latticeLayout

/*
 * Transforms of a warp's whole lattice: every form it carries, at once.
 *
 * A warp keeps no rest lattice; its geometry is the absolute control points of its grid cells, and its
 * blend shapes are absolute lattices measured against the grid at the default pose.  So moving "the
 * lattice" means moving every one of those forms the same way, or the evaluated lattice and the blend-shape
 * deltas stop agreeing.  These are the two ways the editor moves it: a shift that leaves every keyed motion
 * as it was (a rest reshape), and a map applied to every form alike, which for an affine carries the motion
 * along (a whole-deformer transform).
 */

/**
 * This warp with [delta] added to every point of every lattice it carries: each grid cell and each blend-shape
 * form.  The render channels are untouched.
 *
 * The evaluated lattice moves by exactly [delta] at every pose: the grid blend's weights sum to 1, and a
 * blend shape's contribution is its form minus the grid at the default pose, both of which move by [delta].
 * That is what reshaping the lattice at rest means - every keyed motion stays what it was.
 *
 * @param FloatArray delta Interleaved (x, y) offsets, one pair per control point, in the warp's parent space.
 * @return Deformer.Warp The shifted warp, or this when [delta] does not hold one pair per control point.
 */
fun Deformer.Warp.withLatticeShifted(delta: FloatArray): Deformer.Warp {
	if (delta.size != latticeLayout.pointCount * 2) {
		return this
	}

	return withEveryLatticeMapped { points ->
		val shifted = points.copyOf()
		val count = minOf(shifted.size, delta.size)

		for (componentIndex in 0 until count) {
			shifted[componentIndex] += delta[componentIndex]
		}

		shifted
	}
}

/**
 * This warp with [map] applied to the control points of every grid cell and every non-null blend-shape form,
 * the forms' other fields kept: the one walk over every lattice a warp carries, for any edit that must move
 * them all alike (a shift, an affine, a resample onto a new grid, re-expressing a child in a reshaped parent).
 *
 * An affine map applied this way ([org.umamo.runtime.model.applyAffine]) moves the evaluated lattice by that affine at
 * every pose: an affine map commutes with the grid's weighted blend (the weights sum to 1), and a blend shape's
 * contribution, a difference of two lattices, turns with the linear part.
 *
 * @param Function map Maps one form's interleaved control points to new ones (a fresh array).
 * @return Deformer.Warp The mapped warp.
 */
fun Deformer.Warp.withEveryLatticeMapped(map: (FloatArray) -> FloatArray): Deformer.Warp {
	val mappedGrid =
		geometryGrid?.let { grid ->
			KeyformGrid(grid.axes, grid.cells.map { cell -> KeyformCell(cell.coordinate, WarpLatticeForm(map(cell.form.controlPoints))) })
		}
	val mappedBlendShapes =
		blendShapes.map { binding ->
			binding.copy(
				forms =
					binding.forms.map { form ->
						form?.let { WarpForm(map(form.controlPoints), form.opacity, form.multiplyColor, form.screenColor) }
					},
			)
		}

	return copy(geometryGrid = mappedGrid, blendShapes = mappedBlendShapes)
}