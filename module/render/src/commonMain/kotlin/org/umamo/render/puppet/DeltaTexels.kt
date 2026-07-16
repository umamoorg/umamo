package org.umamo.render.puppet

import org.umamo.render.eval.cellsByLinearIndex
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshForm

/**
 * The linear cell extent of a keyform grid - the product of each axis's key count.
 *
 * This is the delta texture's width: one column per keyform cell, addressed by the cell's linear index
 * (the same index [org.umamo.render.eval.WeightedCell.linearIndex] carries, which is how the vertex
 * shader's active corners find their column).
 *
 * @param KeyformGrid grid The mesh's keyform grid.
 * @return Int The cell count, floored at 1 so an axis-less grid still gets its single rest cell.
 */
internal fun keyformCellCount(grid: KeyformGrid<MeshForm>): Int = maxOf(1, grid.axes.fold(1) { count, axis -> count * axis.keys.size })

/**
 * Builds a mesh's per-keyform-cell vertex deltas as RG texels: row = vertex id, column = cell linear
 * index, RG = (Δx, Δy).  This is the `p = base + Σ wᵢ·Δᵢ` morph's Δ table, which the vertex shader
 * texel-fetches per active corner.
 *
 * Backend-neutral by construction: it returns plain texels, so each backend uploads them its own way
 * (an RG32F 2D texture on the GL family; whatever Metal prefers).  Emitting a backend's buffer type here
 * would put a texture-upload decision in shared code.
 *
 * A cell with no keyform, or one whose delta array is too short for this vertex, contributes (0, 0) -
 * i.e. that cell leaves the vertex at its rest position.  Both cases are reachable in real models, so
 * they are a normal absence rather than an error.
 *
 * @param KeyformGrid grid        The mesh's keyform grid.
 * @param Int         vertexCount The mesh's vertex count (the texture height).
 * @param Int         cellCount   The grid's linear cell extent from [keyformCellCount] (the width).
 * @return FloatArray The texels, row-major, length `vertexCount * cellCount * 2`.
 */
internal fun buildDeltaTexels(grid: KeyformGrid<MeshForm>, vertexCount: Int, cellCount: Int): FloatArray {
	val cells = cellsByLinearIndex(grid)
	val texels = FloatArray(vertexCount * cellCount * 2)
	var writeIndex = 0
	for (vertexIndex in 0 until vertexCount) {
		for (cellIndex in 0 until cellCount) {
			val deltas = cells[cellIndex]?.form?.positionDeltas
			if (deltas != null && vertexIndex * 2 + 1 < deltas.size) {
				texels[writeIndex] = deltas[vertexIndex * 2]
				texels[writeIndex + 1] = deltas[vertexIndex * 2 + 1]
			}
			// else: leave (0, 0) - a missing cell or a short delta array means no offset from rest.
			writeIndex += 2
		}
	}
	return texels
}
