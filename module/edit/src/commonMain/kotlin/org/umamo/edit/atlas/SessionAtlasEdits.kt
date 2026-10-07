package org.umamo.edit.atlas

import org.umamo.edit.DocumentChange
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshOperatorKind
import org.umamo.runtime.model.AtlasComposition
import org.umamo.runtime.model.AtlasPage
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.PuppetModel

/*
 * Atlas edits on an EditorSession, each one undo step via mutate: a tile placement from the UV editor's
 * placement gizmo, the pins from its pin commands, and the repack the atlas repack flow commits.
 */

/**
 * Packs one piece of source art at [placement] as a single undo step, re-mapping every drawable over it
 * so the art keeps meaning what it did - [setAtlasPlacements] over one tile.
 *
 * @param AtlasTileId     tileId    The tile to place.
 * @param AtlasPlacement? placement Where its art now sits, or null to mark it unpacked.
 */
fun EditorSession.setAtlasPlacement(tileId: AtlasTileId, placement: AtlasPlacement?) {
	setAtlasPlacements(mapOf(tileId to placement))
}

/**
 * Packs several pieces of source art at once as ONE undo step - a placement gesture over a
 * multi-tile selection is one edit, not one step per tile - re-mapping every drawable over them so
 * the art keeps meaning what it did.
 *
 * The pages' PIXELS are session state derived from the model, not part of it: the session's page
 * resolver composes the pages the new placements denote once this commit publishes (and re-composes
 * them on undo), so nothing here touches a pixel and no snapshot ever carries one.
 *
 * @param Map               placementByTile Each tile's new placement, keyed by tile, null to mark it unpacked.
 * @param MeshOperatorKind? kind            The placement operator that produced the move (it names the
 *   step), or null for a placement written by no gesture.
 */
fun EditorSession.setAtlasPlacements(
	placementByTile: Map<AtlasTileId, AtlasPlacement?>,
	kind: MeshOperatorKind? = null,
) {
	mutate(DocumentChange.SetAtlasPlacement(placementByTile.keys.toList(), kind)) { model -> model.withAtlasPlacements(placementByTile) }
}

/**
 * Pins or unpins the placed tiles among [tileIds] as one undo step, so a repack keeps (or may move)
 * them - withAtlasPins under the one history push a pin command or checkbox should be.
 *
 * @param Collection<AtlasTileId> tileIds The tiles to pin or unpin.
 * @param Boolean                 pinned  True to pin, false to unpin.
 */
fun EditorSession.setAtlasPins(tileIds: Collection<AtlasTileId>, pinned: Boolean) {
	mutate(DocumentChange.SetAtlasPin(tileIds.toList(), pinned)) { model -> model.withAtlasPins(tileIds, pinned) }
}

/**
 * Repacks the whole atlas as a single undo step: the new page inventory and every tile's placement
 * land together, with every bound drawable's coordinates re-derived over them - withAtlasRepack's
 * one-pass edit under the one history push a repack should be.
 *
 * The page PIXELS are session state the caller swaps in beside this commit, which is why the
 * committed model comes back: the orchestrator pre-warms the session's page resolver with the SAME
 * atlas instance this publishes (the resolver memoizes by identity), so the commit resolves its
 * pages by cache hit - and undo re-resolves them the same way.
 *
 * @param List             pages            The new page inventory.
 * @param Map              placementsByTile Every tile's new placement, keyed by tile, null for unpacked.
 * @param AtlasComposition composition      The trim and extrusion policy the pack composed under.
 * @return PuppetModel? The committed model, or null when the repack restated the atlas exactly.
 */
fun EditorSession.commitAtlasRepack(
	pages: List<AtlasPage>,
	placementsByTile: Map<AtlasTileId, AtlasPlacement?>,
	composition: AtlasComposition = model.value.atlas.composition,
): PuppetModel? {
	val current = model.value
	val repacked = current.withAtlasRepack(pages, placementsByTile, composition)
	if (repacked === current) {
		return null
	}
	val placedCount = placementsByTile.count { entry -> entry.value != null }
	mutate(DocumentChange.RepackAtlas(placedCount, pages.size)) { repacked }
	return repacked
}