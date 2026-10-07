package org.umamo.interop.cmo3

import org.umamo.runtime.model.AtlasPage
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins how a fresh-graph synthesis gets from a document's render page numbering - every image a drawable
 * samples, in the order a CMO3 loader met it, an unpacked drawable's raster among them - to the model's own
 * page order, which is what a placed tile's entry counts in.
 */
class Cmo3ConversionPagesTest {
	/**
	 * A drawable over [tileId], sharing [textureSourceId]'s page when it is a session duplicate.
	 *
	 * @param String  id              The drawable's id.
	 * @param String  tileId          Its tile.
	 * @param String? textureSourceId The drawable whose page it shares, or null.
	 * @return Drawable The drawable.
	 */
	private fun drawable(id: String, tileId: String, textureSourceId: String? = null): Drawable =
		Drawable(DrawableId(id), id, null, BlendMode.Normal, emptyList(), null, null, textureSourceId = textureSourceId?.let(::DrawableId), atlasTileId = AtlasTileId(tileId))

	/**
	 * Two model pages: `placedA` on page 0, `placedB` on page 1, and `loose` on no page, with a drawable over each
	 * and a duplicate of the first.
	 *
	 * @return PuppetModel The model.
	 */
	private fun model(): PuppetModel {
		val drawables = listOf(drawable("overLoose", "loose"), drawable("overA", "placedA"), drawable("overB", "placedB"), drawable("copyOfA", "placedA", textureSourceId = "overA"))
		return PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = drawables,
			rootChildren = emptyList(),
			rootPartId = null,
			atlas =
				PuppetAtlas(
					pages = listOf(AtlasPage(64, 64), AtlasPage(32, 32)),
					tiles =
						listOf(
							AtlasTile(AtlasTileId("placedA"), "A", 4, 4, placement = AtlasPlacement(0, 1f, 1f, 1f, 1f, 0f)),
							AtlasTile(AtlasTileId("placedB"), "B", 4, 4, placement = AtlasPlacement(1, 1f, 1f, 1f, 1f, 0f)),
							AtlasTile(AtlasTileId("loose"), "Loose", 4, 4),
						),
				),
		)
	}

	// The loader met the loose drawable's raster first, then page 1's image, then page 0's; the duplicate is a
	// session drawable the file never listed.
	private val renderPageByDrawableId = mapOf("overLoose" to 0, "overB" to 1, "overA" to 2)

	/** Each model page is the render page its placed drawables sample, in the model's order. */
	@Test
	fun modelPagesTakeTheRenderPageTheirDrawablesSample() {
		assertEquals(listOf<Int?>(2, 1), modelPageRenderIndices(model(), renderPageByDrawableId))
	}

	/** A drawable keeps a page only when its render page shows a model page; a duplicate reaches it through its source. */
	@Test
	fun drawablesTakeTheModelPageTheirRenderPageShows() {
		val renderIndices = modelPageRenderIndices(model(), renderPageByDrawableId)
		assertEquals(mapOf("overA" to 0, "overB" to 1, "copyOfA" to 0), modelPageIndexByDrawableId(model(), renderIndices, renderPageByDrawableId), "the loose drawable's raster is no model page")
	}

	/** A model page no drawable over a placed tile resolves stays unresolved, for the caller to fill. */
	@Test
	fun aPageNoDrawableResolvesIsNull() {
		assertEquals(listOf<Int?>(2, null), modelPageRenderIndices(model(), mapOf("overA" to 2)))
	}

	/** The resolution handed to the synthesis: each model page takes its render page, an unresolved one the fallback, and the map is renumbered. */
	@Test
	fun renderPagesAreResolvedIntoTheModelsOrder() {
		val renderPages = listOf("looseRaster", "pageB", "pageA")
		val resolved = modelOrderPages(model(), renderPages, renderPageByDrawableId) { modelPageIndex -> "blank$modelPageIndex" }
		assertEquals(listOf("pageA", "pageB"), resolved.pages)
		assertEquals(mapOf("overA" to 0, "overB" to 1, "copyOfA" to 0), resolved.pageIndexByDrawableId)
		val partial = modelOrderPages(model(), renderPages, mapOf("overA" to 2)) { modelPageIndex -> "blank$modelPageIndex" }
		assertEquals(listOf("pageA", "blank1"), partial.pages, "the page nothing shows takes the fallback")
		assertEquals(mapOf("overA" to 0, "copyOfA" to 0), partial.pageIndexByDrawableId)
	}

	/** A model with no atlas pages keeps its render pages and their map as they are. */
	@Test
	fun aModelWithNoPagesKeepsItsRenderPages() {
		val noPages = model().copy(atlas = PuppetAtlas(pages = emptyList(), tiles = emptyList()))
		val resolved = modelOrderPages(noPages, listOf("first", "second"), mapOf("overA" to 1)) { error("a model with no pages asks for no fallback") }
		assertEquals(listOf("first", "second"), resolved.pages)
		assertEquals(mapOf("overA" to 1), resolved.pageIndexByDrawableId)
	}
}