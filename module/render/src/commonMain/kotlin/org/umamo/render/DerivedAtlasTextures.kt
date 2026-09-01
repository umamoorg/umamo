package org.umamo.render

import org.umamo.format.art.analyzeAlpha
import org.umamo.format.atlas.AtlasPackItem
import org.umamo.format.atlas.AtlasPackOptions
import org.umamo.format.atlas.AtlasPackPlacement
import org.umamo.format.atlas.composeAtlasPages
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.PuppetModel
import kotlin.math.roundToInt

/*
 * Page derivation for a GENERATED atlas: the pixels a model's authored placements denote, composed
 * from the source art.  An imported document's original pages never come through here - the session's
 * page resolver short-circuits its baseline atlas to the document's own decoded pages - so everything
 * in this file may assume the placements were authored by Umamo's packer.
 */

/**
 * The trim and extrusion policy a derivation reconstructs a pack under.
 *
 * A placement stores only where a tile's art sits, not which opaque sub-rectangle the packer trimmed
 * it to, so the derivation re-runs the packer's own trim analysis on the same pixels.  That couples
 * this file to the pack options the repack ran with: both sides read THIS value, and a future options
 * dialog must thread its choices into both.
 */
private val derivedPackPolicy = AtlasPackOptions()

/**
 * An [AtlasPackPlacement] lowered onto the model's placement transform: the trim origin folds into
 * the position, scale stays 1, rotation stays 0.
 *
 * The quarter-turned form is deliberately refused rather than lowered: the rotation's origin shift
 * has a pixel-center subtlety this session does not need (packing runs with rotation off), and a
 * silently wrong lowering would re-point every bound mesh.  The adapter grows the turned case when
 * rotation is switched on.
 *
 * @param AtlasPackPlacement packPlacement Where the packer put one tile.
 * @return AtlasPlacement The equivalent authored placement.
 */
fun atlasPlacementFromPack(packPlacement: AtlasPackPlacement): AtlasPlacement {
	require(packPlacement.quarterTurns == 0) {
		"tile '${packPlacement.key}' is quarter-turned; the placement lowering does not carry rotation yet"
	}
	return AtlasPlacement(
		pageIndex = packPlacement.pageIndex,
		positionX = (packPlacement.pageX - packPlacement.trimLeft).toFloat(),
		positionY = (packPlacement.pageY - packPlacement.trimTop).toFloat(),
		scaleX = 1f,
		scaleY = 1f,
		rotationDegrees = 0f,
	)
}

/**
 * The [PuppetTextures] a generated atlas value denotes: pages composed from the model's tiles,
 * placements, and decoded source art.
 *
 * For a page set built here, the model's page numbering and the renderer's COINCIDE by construction:
 * [PuppetTextures.atlases] is index-parallel to `PuppetAtlas.pages`, and the drawable map carries
 * each placement's own page index.  That guarantee holds for GENERATED sets only - an imported
 * document's two numberings stay independent (see [AtlasPlacement]), because its renderer pages were
 * collected by a different walk than its document pages.
 *
 * Returns null when the atlas is not derivable: a placement scaled, rotated, or off the pixel grid
 * (nothing Umamo's packer authors), art that will not decode, art whose size disagrees with its
 * tile, or a placement without room for its extrusion.  The caller treats null as "keep showing the
 * pages you have" - for an atlas the repack authored, every one of these is unreachable.
 *
 * Thread-safe: reads pixels through [SourceArtRasters.decodeRaster] and shares nothing, so the
 * session runs it off the UI thread.
 *
 * @param PuppetModel      model              The model whose atlas to compose.
 * @param SourceArtRasters artRasters         The source-art pixel store.
 * @param Boolean          premultipliedAlpha The document's texture-convention flag, carried through.
 * @return PuppetTextures? The composed page set, or null when the atlas cannot be derived.
 */
fun deriveAtlasTextures(
	model: PuppetModel,
	artRasters: SourceArtRasters,
	premultipliedAlpha: Boolean,
): PuppetTextures? {
	val atlas = model.atlas
	val extrude = derivedPackPolicy.extrude
	val items = ArrayList<AtlasPackItem>()
	val packPlacements = ArrayList<AtlasPackPlacement>()
	for (tile in atlas.tiles) {
		val placement = tile.placement ?: continue
		if (placement.scaleX != 1f || placement.scaleY != 1f || placement.rotationDegrees != 0f) {
			return null
		}
		val page = atlas.pages.getOrNull(placement.pageIndex) ?: return null
		val raster = artRasters.decodeRaster(tile.id) ?: return null
		if (raster.width != tile.width || raster.height != tile.height) {
			return null
		}
		// The packer trimmed each tile to its opaque bounds before placing it; the placement holds only
		// the untrimmed origin, so the same analysis on the same pixels reconstructs the trim exactly.
		val trim =
			analyzeAlpha(raster.width, raster.height, raster.rgba, derivedPackPolicy.alphaThreshold)
				?.opaqueBounds ?: return null
		val pageX = placement.positionX + trim.left
		val pageY = placement.positionY + trim.top
		val pageXPixel = pageX.roundToInt()
		val pageYPixel = pageY.roundToInt()
		if (pageXPixel.toFloat() != pageX || pageYPixel.toFloat() != pageY) {
			return null
		}
		if (pageXPixel - extrude < 0 ||
			pageYPixel - extrude < 0 ||
			pageXPixel + trim.width + extrude > page.width ||
			pageYPixel + trim.height + extrude > page.height
		) {
			return null
		}
		items.add(AtlasPackItem(tile.id.raw, raster.width, raster.height, raster.rgba))
		packPlacements.add(
			AtlasPackPlacement(
				key = tile.id.raw,
				pageIndex = placement.pageIndex,
				pageX = pageXPixel,
				pageY = pageYPixel,
				trimLeft = trim.left,
				trimTop = trim.top,
				trimWidth = trim.width,
				trimHeight = trim.height,
				quarterTurns = 0,
			),
		)
	}

	val pages =
		composeAtlasPages(
			pageWidths = IntArray(atlas.pages.size) { pageIndex -> atlas.pages[pageIndex].width },
			pageHeights = IntArray(atlas.pages.size) { pageIndex -> atlas.pages[pageIndex].height },
			items = items,
			placements = packPlacements,
			extrude = extrude,
		)

	return PuppetTextures(
		atlases = pages.map { page -> DecodedImage(page.rgba, page.width, page.height) },
		atlasIndexByDrawableId = generatedAtlasIndexByDrawableId(model),
		premultipliedAlpha = premultipliedAlpha,
	)
}

/**
 * The drawable-to-page map a GENERATED page set carries: every drawable bound to a placed tile maps
 * to that tile's own page index, so the map shares the model's page numbering.
 *
 * One function used by both the repack (building the set from the pack it just ran) and the
 * derivation above (rebuilding it for an undone generation), so the two can never key differently.
 *
 * @param PuppetModel model The model whose bindings to map.
 * @return Map The renderer's drawable-to-page lookup.
 */
fun generatedAtlasIndexByDrawableId(model: PuppetModel): Map<String, Int> {
	val placementByTileId = model.atlas.tiles.associateBy({ tile -> tile.id }, { tile -> tile.placement })
	val atlasIndexByDrawableId = HashMap<String, Int>()
	for (drawable in model.drawables) {
		val tileId = drawable.atlasTileId ?: continue
		val placement = placementByTileId[tileId] ?: continue
		// Keyed the way the renderer looks it up: a duplicated drawable resolves its page through the
		// drawable it was duplicated from (Drawable.textureSourceId), and shared sources share a tile.
		atlasIndexByDrawableId[(drawable.textureSourceId ?: drawable.id).raw] = placement.pageIndex
	}
	return atlasIndexByDrawableId
}