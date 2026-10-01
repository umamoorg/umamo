package org.umamo.interop.cmo3

import org.umamo.runtime.model.PuppetModel

/*
 * The two page numberings a document can carry, and how a fresh-graph synthesis gets from one to the
 * other.  A document still sampling the images it was imported with numbers its render pages in the order
 * its loader met them - `cmo3AtlasPages` lists each distinct image a drawable samples, the raster of every
 * drawable over never-packed art included - while the model's atlas numbers its pages as the file listed
 * its atlases (docs/format/UMA.md §5.5).  The synthesis writes one CTextureAtlas per page it is handed and
 * addresses a placed tile's entry by the MODEL's page index, so it must be handed the model's pages in the
 * model's order; a raster handed over as a page would become an atlas of its own.
 */

/**
 * The render page that shows each of [puppet]'s atlas pages: the page any drawable over a tile placed on it
 * samples, which is that page's image by construction (`cmo3AtlasPages` shows a placed tile's drawable from
 * its page).  A session duplicate keys its render page through the drawable it copies.
 *
 * @param PuppetModel puppet                 The model.
 * @param Map         renderPageByDrawableId Each drawable's render page, keyed by the ids the page set knows.
 * @return List<Int?> Per model page, the render page index, or null when no drawable over a placed tile resolves one.
 */
public fun modelPageRenderIndices(puppet: PuppetModel, renderPageByDrawableId: Map<String, Int>): List<Int?> {
	val renderIndices = MutableList<Int?>(puppet.atlas.pages.size) { null }
	for (drawable in puppet.drawables) {
		val tile = drawable.atlasTileId?.let(puppet.atlas.tileById::get) ?: continue
		val pageIndex = tile.placement?.pageIndex ?: continue
		if (pageIndex !in renderIndices.indices || renderIndices[pageIndex] != null) {
			continue
		}
		renderIndices[pageIndex] = renderPageByDrawableId[drawable.id.raw] ?: drawable.textureSourceId?.let { sourceId -> renderPageByDrawableId[sourceId.raw] } ?: continue
	}
	return renderIndices
}

/**
 * Each drawable's page in the model's numbering, from its render page: a drawable whose render page shows a
 * model page takes that page, and one whose render page shows no model page - a drawable over a raster
 * rather than a page - takes none, so the synthesis binds it by its own art or reports it.
 *
 * @param PuppetModel puppet                 The model.
 * @param List        renderIndices          Per model page, its render page ([modelPageRenderIndices]).
 * @param Map         renderPageByDrawableId Each drawable's render page, keyed by the ids the page set knows.
 * @return Map<String, Int> Each drawable id's model page.
 */
public fun modelPageIndexByDrawableId(puppet: PuppetModel, renderIndices: List<Int?>, renderPageByDrawableId: Map<String, Int>): Map<String, Int> {
	val modelPageByRenderPage = HashMap<Int, Int>()
	for ((modelPageIndex, renderIndex) in renderIndices.withIndex()) {
		if (renderIndex != null) {
			modelPageByRenderPage.getOrPut(renderIndex) { modelPageIndex }
		}
	}
	val modelPageByDrawableId = LinkedHashMap<String, Int>()
	for (drawable in puppet.drawables) {
		val renderIndex = renderPageByDrawableId[drawable.id.raw] ?: drawable.textureSourceId?.let { sourceId -> renderPageByDrawableId[sourceId.raw] } ?: continue
		modelPageByRenderPage[renderIndex]?.let { modelPageIndex -> modelPageByDrawableId[drawable.id.raw] = modelPageIndex }
	}
	return modelPageByDrawableId
}