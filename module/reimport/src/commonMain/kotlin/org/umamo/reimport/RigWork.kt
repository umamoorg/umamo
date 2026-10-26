package org.umamo.reimport

import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.PuppetModel

/*
 * What a merge may remove.  A reload that finds a layer no tile binds mints a fresh drawable for it;
 * when that layer turns out to be a lost layer re-created (a duplicate, a paste), the rigged tile
 * takes the layer and the fresh drawable is redundant - but only while no rig structure hangs off it.
 * One predicate says so, read by the planner, the suggestions, and the Sources space alike, so the
 * proposal, the step, and the chip's wording agree on what goes.
 */

/**
 * Whether no drawable over [tileId] carries rig structure: no parent deformer, no clip mask either way,
 * no glue, no geometry or channel keys, and no blend shapes.  The mesh does not count, however it was
 * reshaped: a mesh is made again in one step, the merge that removes it says so, and the removal is
 * one undo away.  A tile no drawable samples qualifies, since there is nothing over it to lose; a
 * drawable with no mesh, or a tile the model lacks, does not.
 *
 * @param AtlasTileId tileId The tile whose drawables are judged.
 * @return Boolean True when removing the tile's drawables would lose no rig work.
 */
fun PuppetModel.tileCarriesNoRigWork(tileId: AtlasTileId): Boolean {
	if (atlas.tileById[tileId] == null) {
		return false
	}

	val over = drawables.filter { drawable -> drawable.atlasTileId == tileId }

	if (over.isEmpty()) {
		return true
	}

	val overIds = over.mapTo(HashSet()) { drawable -> drawable.id }
	val masksAnother = drawables.any { drawable -> drawable.id !in overIds && drawable.maskedBy.any { maskId -> maskId in overIds } }
	val glued = glues.any { glue -> glue.meshA in overIds || glue.meshB in overIds }

	if (masksAnother || glued) {
		return false
	}

	return over.all { drawable ->
		drawable.mesh != null &&
			drawable.parentDeformerId == null &&
			drawable.maskedBy.isEmpty() &&
			drawable.geometryGrid == null &&
			drawable.channelGrids.isEmpty &&
			drawable.blendShapes.isEmpty()
	}
}