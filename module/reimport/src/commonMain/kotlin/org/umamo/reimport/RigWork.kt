package org.umamo.reimport

import org.umamo.runtime.model.ArtSourceLayer
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.applyUvAffine
import org.umamo.runtime.model.isUntouchedBirthQuad
import org.umamo.runtime.model.storedToArtAffineForTile

/*
 * What a merge may remove.  A reload that finds a layer no tile binds mints a fresh drawable for it;
 * when that layer turns out to be a lost layer re-created (a duplicate, a paste), the rigged tile
 * takes the layer and the fresh drawable is redundant - but only while nothing has been done to it.
 * One predicate says so, read by the planner, the suggestions, and the Sources space alike, so the
 * proposal, the step, and the chip's wording agree on what goes.
 */

/**
 * Whether every drawable over [tileId] is exactly what an import mints and nothing more: a birth quad
 * that still samples the whole tile at the layer's canvas place, no parent deformer, no clip mask
 * either way, no glue, no geometry or channel keys, and no blend shapes.  A tile no drawable samples
 * qualifies too, since there is nothing over it to lose.  Without [row], or without a readable mapping
 * for the tile, the answer is false: the quad cannot be told untouched, so the drawable is kept; so is
 * a tile the model lacks.
 *
 * @param AtlasTileId     tileId The tile whose drawables are judged.
 * @param ArtSourceLayer? row    The tile's layer as the inventory records it, for the canvas origin the
 *   birth quad was born at; null when the inventory has no row for it.
 * @return Boolean True when removing the tile's drawables would lose no rig work.
 */
fun PuppetModel.tileCarriesNoRigWork(tileId: AtlasTileId, row: ArtSourceLayer?): Boolean {
	val tile = atlas.tileById[tileId] ?: return false
	val over = drawables.filter { drawable -> drawable.atlasTileId == tileId }
	if (over.isEmpty()) {
		return true
	}
	if (row == null) {
		return false
	}
	val storedToArt = atlas.storedToArtAffineForTile(tileId) ?: return false
	val overIds = over.mapTo(HashSet()) { drawable -> drawable.id }
	val masksAnother = drawables.any { drawable -> drawable.id !in overIds && drawable.maskedBy.any { maskId -> maskId in overIds } }
	val glued = glues.any { glue -> glue.meshA in overIds || glue.meshB in overIds }
	if (masksAnother || glued) {
		return false
	}
	return over.all { drawable ->
		val mesh = drawable.mesh ?: return@all false
		drawable.parentDeformerId == null &&
			drawable.maskedBy.isEmpty() &&
			drawable.geometryGrid == null &&
			drawable.channelGrids.isEmpty &&
			drawable.blendShapes.isEmpty() &&
			mesh.isUntouchedBirthQuad(applyUvAffine(mesh.uvs, storedToArt), tile.width, tile.height, row.left.toFloat(), row.top.toFloat())
	}
}