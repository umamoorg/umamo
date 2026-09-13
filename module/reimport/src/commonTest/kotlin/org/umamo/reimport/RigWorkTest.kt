package org.umamo.reimport

import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.ArtSourceLayer
import org.umamo.runtime.model.AtlasPage
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.Glue
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.SourceLayerRef
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What counts as rig work over a tile: anything beyond the birth an import mints. */
class RigWorkTest {
	private val source = ArtSourceId("art-0")
	private val tile = AtlasTileId("t")
	private val row = ArtSourceLayer("k", "K", "", 50, 50, 10, 10, visible = true)

	/** The birth quad over a 10 x 10 tile whose layer sits at (50, 50). */
	private val birthQuad = DrawableMesh(floatArrayOf(50f, 50f, 60f, 50f, 60f, 60f, 50f, 60f), floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), intArrayOf(0, 1, 2, 0, 2, 3))

	/** The same quad with one corner dragged. */
	private val moved = DrawableMesh(floatArrayOf(50f, 50f, 60f, 50f, 61f, 61f, 50f, 60f), floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), intArrayOf(0, 1, 2, 0, 2, 3))

	private val boundTile = AtlasTile(tile, "K", 10, 10, source = SourceLayerRef(source, "k", true))

	private fun drawable(id: String, mesh: DrawableMesh? = birthQuad, tileId: AtlasTileId? = tile): Drawable =
		Drawable(DrawableId(id), id, null, BlendMode.Normal, emptyList(), mesh, null, atlasTileId = tileId)

	private fun model(vararg drawables: Drawable, atlas: PuppetAtlas = PuppetAtlas(tiles = listOf(boundTile)), glues: List<Glue> = emptyList()): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = drawables.toList(),
			rootChildren = emptyList(),
			rootPartId = null,
			atlas = atlas,
			glues = glues,
			sources = emptyList(),
		)

	@Test
	fun aFreshBirthOverTheTileCarriesNoRigWorkAndAnythingMoreDoes() {
		assertTrue(model(drawable("d")).tileCarriesNoRigWork(tile, row), "a birth quad sampling the whole tile at the layer's place")
		assertTrue(model().tileCarriesNoRigWork(tile, row), "nothing over the tile at all")
		assertTrue(model(drawable("d"), drawable("e")).tileCarriesNoRigWork(tile, row), "two fresh births")
		assertFalse(model(drawable("d")).tileCarriesNoRigWork(tile, row = null), "without the row the quad cannot be told untouched")
		assertFalse(model(drawable("d")).tileCarriesNoRigWork(AtlasTileId("nope"), row), "a tile the model lacks")
		assertFalse(model(drawable("d", mesh = moved)).tileCarriesNoRigWork(tile, row), "a moved vertex")
		assertFalse(model(drawable("d"), drawable("e", mesh = moved)).tileCarriesNoRigWork(tile, row), "one of two moved")
		assertFalse(model(drawable("d", mesh = null)).tileCarriesNoRigWork(tile, row), "no mesh to judge")
		assertFalse(model(drawable("d").copy(parentDeformerId = DeformerId("warp"))).tileCarriesNoRigWork(tile, row), "a parent deformer")
		assertFalse(model(drawable("d").copy(maskedBy = listOf(DrawableId("other")))).tileCarriesNoRigWork(tile, row), "a clip mask on it")
		assertFalse(model(drawable("d"), drawable("other", tileId = null).copy(maskedBy = listOf(DrawableId("d")))).tileCarriesNoRigWork(tile, row), "it masking another")
		assertFalse(model(drawable("d").copy(geometryGrid = KeyformGrid(emptyList(), emptyList()))).tileCarriesNoRigWork(tile, row), "geometry keys")
		assertFalse(
			model(drawable("d").copy(channelGrids = ChannelGrids(mapOf(FormChannel.OPACITY to KeyformGrid(emptyList(), emptyList()))))).tileCarriesNoRigWork(tile, row),
			"a channel track",
		)
		assertFalse(
			model(drawable("d").copy(blendShapes = listOf(BlendShapeBinding(ParameterId("p"), floatArrayOf(0f), 0, listOf<MeshForm?>(null))))).tileCarriesNoRigWork(tile, row),
			"a blend shape",
		)
		assertFalse(model(drawable("d"), glues = listOf(Glue(DrawableId("d"), DrawableId("other"), emptyList()))).tileCarriesNoRigWork(tile, row), "a glue")
		val degenerate = PuppetAtlas(pages = listOf(AtlasPage(16, 16)), tiles = listOf(boundTile.copy(placement = AtlasPlacement(0, 0f, 0f, 0f, 0f, 0f))))
		assertFalse(model(drawable("d"), atlas = degenerate).tileCarriesNoRigWork(tile, row), "a mapping that cannot be read")
	}
}