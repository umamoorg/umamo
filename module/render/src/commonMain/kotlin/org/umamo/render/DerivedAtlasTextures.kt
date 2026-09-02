package org.umamo.render

import org.umamo.format.art.LayerBounds
import org.umamo.format.art.analyzeAlpha
import org.umamo.format.atlas.AtlasPackItem
import org.umamo.format.atlas.AtlasPackOptions
import org.umamo.format.atlas.AtlasPackPlacement
import org.umamo.format.atlas.AtlasTilePlacement
import org.umamo.format.atlas.composeAtlasPagesAffine
import org.umamo.format.raster.RasterImage
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.placementAffine

/*
 * Page derivation for a GENERATED atlas: the pixels a model's authored placements denote, composed
 * from the source art.  An imported document's original pages never come through here while its
 * atlas is the baseline - the session's page resolver short-circuits that value to the document's own
 * decoded pages - but the moment a rigger moves one tile the whole page set derives from here, imported
 * placements included, so nothing in this file may assume the packer authored every placement.
 *
 * Two steps on purpose: PLAN resolves what will be drawn (every placed tile's pixels, trim, and page
 * affine) without touching a page buffer, and COMPOSE draws it.  The plan is cheap and is also the
 * footprint inventory the placement gizmo reads before it lets a gesture commit, so the drag and the
 * resolver cannot disagree about what a placement will paint.
 */

/**
 * The trim and extrusion policy a derivation reconstructs a pack under, and the policy the repack
 * packs with.
 *
 * A placement stores only where a tile's art sits, not which opaque sub-rectangle the packer trimmed
 * it to, so the derivation re-runs the packer's own trim analysis on the same pixels.  That couples
 * this value to the repack: both sides read it, and a future options dialog must thread its choices
 * into both.
 */
public val derivedPackPolicy: AtlasPackOptions = AtlasPackOptions()

/**
 * The trim a derivation draws of [raster]: the packer's own opaque-bounds analysis under
 * [derivedPackPolicy], or null when nothing in the raster is opaque.
 *
 * @param DecodedImage raster The tile's decoded source art.
 * @return LayerBounds? The opaque sub-rectangle, raster-local.
 */
public fun derivedTileTrim(raster: DecodedImage): LayerBounds? =
	analyzeAlpha(raster.width, raster.height, raster.rgba, derivedPackPolicy.alphaThreshold)?.opaqueBounds

/**
 * An [AtlasPackPlacement] lowered onto the model's placement transform: the trim origin folds into
 * the position, scale stays 1, rotation stays 0.
 *
 * The quarter-turned form is deliberately refused rather than lowered: the packer's turn and the
 * placement's rotation run in opposite senses about different origins, and a silently wrong lowering
 * would re-point every bound mesh.  The adapter grows the turned case when the repack switches
 * rotation on.
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
 * What a derivation will compose: every placed tile's pixels, trim, and page affine, resolved
 * before any page buffer is allocated.
 *
 * @property List items      The placed tiles' pixels, keyed by tile id.
 * @property List placements Each placed tile's trim and tile-to-page affine.
 * @property Map  trimByTile Each drawn tile's trim, for callers reasoning about footprints.
 */
class AtlasDerivationPlan(
	val items: List<AtlasPackItem>,
	val placements: List<AtlasTilePlacement>,
	val trimByTile: Map<AtlasTileId, LayerBounds>,
)

/**
 * Resolves what [model]'s atlas denotes without composing a pixel.
 *
 * Null when the atlas cannot be derived at all: a placement naming a page the atlas lacks, art that
 * will not decode, or art whose size disagrees with its tile - faults in the document, not in the
 * placement.  A placed tile with nothing opaque is skipped rather than refused (there is nothing to
 * draw), and scale, rotation, fractional positions, and footprints past the page edge all derive:
 * the composer resamples and clips them.
 *
 * Thread-safe: reads pixels through [SourceArtRasters.decodeRaster] and shares nothing, so callers
 * run it off the UI thread.
 *
 * @param PuppetModel      model      The model whose atlas to resolve.
 * @param SourceArtRasters artRasters The source-art pixel store.
 * @return AtlasDerivationPlan? The plan, or null when the atlas cannot be derived.
 */
fun planAtlasDerivation(model: PuppetModel, artRasters: SourceArtRasters): AtlasDerivationPlan? {
	val atlas = model.atlas
	val items = ArrayList<AtlasPackItem>()
	val placements = ArrayList<AtlasTilePlacement>()
	val trimByTile = HashMap<AtlasTileId, LayerBounds>()
	for (tile in atlas.tiles) {
		val placement = tile.placement ?: continue
		if (placement.pageIndex !in atlas.pages.indices) {
			return null
		}
		val raster = artRasters.decodeRaster(tile.id) ?: return null
		if (raster.width != tile.width || raster.height != tile.height) {
			return null
		}
		// The packer trimmed each tile to its opaque bounds before placing it; the placement holds only
		// the untrimmed origin, so the same analysis on the same pixels reconstructs the trim exactly.
		val trim = derivedTileTrim(raster) ?: continue
		trimByTile[tile.id] = trim
		items.add(AtlasPackItem(tile.id.raw, raster.width, raster.height, raster.rgba))
		placements.add(AtlasTilePlacement(tile.id.raw, placement.pageIndex, trim, placementAffine(placement)))
	}
	return AtlasDerivationPlan(items, placements, trimByTile)
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
 * A placement the packer authored (an integer translation with room for its extrusion) composes
 * through the packer's own blit, byte-identical to the pages the repack produced; every other
 * placement resamples through the affine composer.  Null exactly when [planAtlasDerivation] is null;
 * the caller treats that as "keep showing the pages you have".
 *
 * Thread-safe, like the plan.
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
	val plan = planAtlasDerivation(model, artRasters) ?: return null
	val atlas = model.atlas
	val pages =
		composeAtlasPagesAffine(
			pageWidths = IntArray(atlas.pages.size) { pageIndex -> atlas.pages[pageIndex].width },
			pageHeights = IntArray(atlas.pages.size) { pageIndex -> atlas.pages[pageIndex].height },
			items = plan.items,
			placements = plan.placements,
			extrude = derivedPackPolicy.extrude,
		)
	return generatedPuppetTextures(pages, model, premultipliedAlpha)
}

/**
 * The [PuppetTextures] a GENERATED page set is: [pages] wrapped as decoded images, index-parallel to
 * `PuppetAtlas.pages`, under the drawable map of [generatedAtlasIndexByDrawableId].
 *
 * One function used by the repack (from the pack it just ran), the derivation above (for an undone
 * generation), and the export gate, so no caller assembles the wrapper on its own and the page cache's
 * identity-keyed sets are always built the same way.
 *
 * @param List        pages              The composed pages, in the model's page order.
 * @param PuppetModel model              The model whose bindings key the drawable map.
 * @param Boolean     premultipliedAlpha The document's texture-convention flag, carried through.
 * @return PuppetTextures The page set.
 */
fun generatedPuppetTextures(
	pages: List<RasterImage>,
	model: PuppetModel,
	premultipliedAlpha: Boolean,
): PuppetTextures =
	PuppetTextures(
		atlases = pages.map { page -> DecodedImage(page.rgba, page.width, page.height) },
		atlasIndexByDrawableId = generatedAtlasIndexByDrawableId(model),
		premultipliedAlpha = premultipliedAlpha,
	)

/**
 * The drawable-to-page map a GENERATED page set carries: every drawable bound to a placed tile maps
 * to that tile's own page index, so the map shares the model's page numbering.
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