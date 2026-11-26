package org.umamo.interop

import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.isFlat

/**
 * The one notice an export raises for the warps whose lattices carry editing depth, which no format but UMA holds, or
 * null when none of [warps] does.  The written file keeps their 2D points, the projection the deformation uses, so
 * only the depth is lost; both the CMO3 and the MOC3 export raise it through here.
 *
 * @param Iterable<Deformer.Warp> warps The exported warps, in document order.
 * @return ExportNotice? The document-level notice naming the warps with depth, or null.
 */
internal fun latticeDepthFlattenedNotice(warps: Iterable<Deformer.Warp>): ExportNotice? {
	val warpNames = warps.filter { warp -> warp.carriesDepth }.map { warp -> warp.name }

	if (warpNames.isEmpty()) {
		return null
	}

	return ExportNotice.UnsupportedChange(ExportEntityCategory.Deformer, null, ExportNoticeReason.LatticeDepthFlattened(warpNames))
}

/** Whether any keyform of this warp's lattice sits off the art plane. */
internal val Deformer.Warp.carriesDepth: Boolean
	get() = geometryGrid?.cells.orEmpty().any { cell -> !cell.form.depth.isFlat }