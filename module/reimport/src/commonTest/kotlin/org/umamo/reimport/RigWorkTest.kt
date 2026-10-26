package org.umamo.reimport

import org.umamo.runtime.model.ArtSourceId
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

/** What counts as rig work over a tile: the rig structures hung off its drawables, never their meshes. */
class RigWorkTest {
	private val source = ArtSourceId("art-0")
	private val tile = AtlasTileId("t")

	/** The birth quad over a 10 x 10 tile whose layer sits at (50, 50). */
	private val birthQuad = DrawableMesh.withLocalEqualToCanvas(floatArrayOf(50f, 50f, 60f, 50f, 60f, 60f, 50f, 60f), floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), intArrayOf(0, 1, 2, 0, 2, 3))

	/** The same quad with one corner dragged. */
	private val moved = DrawableMesh.withLocalEqualToCanvas(floatArrayOf(50f, 50f, 60f, 50f, 61f, 61f, 50f, 60f), floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), intArrayOf(0, 1, 2, 0, 2, 3))

	/** A five-vertex mesh over the same art, the shape a generated birth takes. */
	private val generated =
		DrawableMesh.withLocalEqualToCanvas(
			floatArrayOf(50f, 50f, 60f, 50f, 60f, 60f, 50f, 60f, 55f, 55f),
			floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f, 0.5f, 0.5f),
			intArrayOf(0, 1, 4, 1, 2, 4, 2, 3, 4, 3, 0, 4),
		)

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
	fun aDrawableWithNoRigStructureCarriesNoRigWorkWhateverItsMesh() {
		assertTrue(model(drawable("d")).tileCarriesNoRigWork(tile), "a birth quad")
		assertTrue(model().tileCarriesNoRigWork(tile), "nothing over the tile at all")
		assertTrue(model(drawable("d"), drawable("e")).tileCarriesNoRigWork(tile), "two fresh births")
		assertTrue(model(drawable("d", mesh = moved)).tileCarriesNoRigWork(tile), "a moved vertex is no rig structure")
		assertTrue(model(drawable("d", mesh = generated)).tileCarriesNoRigWork(tile), "nor is a generated mesh")
	}

	@Test
	fun anyRigStructureOverTheTileIsRigWork() {
		assertFalse(model(drawable("d")).tileCarriesNoRigWork(AtlasTileId("nope")), "a tile the model lacks")
		assertFalse(model(drawable("d", mesh = null)).tileCarriesNoRigWork(tile), "no mesh")
		assertFalse(model(drawable("d").copy(parentDeformerId = DeformerId("warp"))).tileCarriesNoRigWork(tile), "a parent deformer")
		assertFalse(model(drawable("d"), drawable("e").copy(parentDeformerId = DeformerId("warp"))).tileCarriesNoRigWork(tile), "one of two under a deformer")
		assertFalse(model(drawable("d").copy(maskedBy = listOf(DrawableId("other")))).tileCarriesNoRigWork(tile), "a clip mask on it")
		assertFalse(model(drawable("d"), drawable("other", tileId = null).copy(maskedBy = listOf(DrawableId("d")))).tileCarriesNoRigWork(tile), "it masking another")
		assertFalse(model(drawable("d").copy(geometryGrid = KeyformGrid(emptyList(), emptyList()))).tileCarriesNoRigWork(tile), "geometry keys")
		assertFalse(
			model(drawable("d").copy(channelGrids = ChannelGrids(mapOf(FormChannel.OPACITY to KeyformGrid(emptyList(), emptyList()))))).tileCarriesNoRigWork(tile),
			"channel keys",
		)
		assertFalse(
			model(drawable("d").copy(blendShapes = listOf(BlendShapeBinding(ParameterId("p"), floatArrayOf(0f), 0, listOf<MeshForm?>(null))))).tileCarriesNoRigWork(tile),
			"a blend shape",
		)
		assertFalse(model(drawable("d"), glues = listOf(Glue(DrawableId("d"), DrawableId("other"), emptyList()))).tileCarriesNoRigWork(tile), "a glue")
	}
}