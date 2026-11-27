package org.umamo.render

import org.umamo.format.atlas.AtlasPackReserve
import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One drawable's share of a tile: [meshReserveOf] and [drawableMeshTrianglesOf] measure a single
 * drawable by the same rule the per-tile [meshReserveByTile] and [meshTrianglesOf] use, so the halves of
 * a tile split between two drawables union back to the tile's own reserve and triangles.
 */
class DrawableMeshReachTest {
	private val tileId = AtlasTileId("tile")
	private val leftId = DrawableId("left")
	private val rightId = DrawableId("right")
	private val looseId = DrawableId("loose")

	@Test
	fun eachHalfMeasuresOnlyItself() {
		val model = splitModel()
		assertEquals(AtlasPackReserve(-2, 0, 32, 64), meshReserveOf(model, leftId))
		assertEquals(AtlasPackReserve(32, 0, 66, 64), meshReserveOf(model, rightId))
		assertEquals(AtlasPackReserve(-2, 0, 66, 64), meshReserveByTile(model).getValue(tileId))
		val leftTriangles = drawableMeshTrianglesOf(model, leftId)
		assertEquals(2, leftTriangles.size)
		assertContentEquals(floatArrayOf(-2f, 0f, 32f, 0f, 32f, 64f), leftTriangles[0])
		assertTrue(leftTriangles.all { triangle -> (0 until 3).all { corner -> triangle[2 * corner] <= 32f } })
		assertEquals(4, meshTrianglesOf(model, tileId).size)
	}

	@Test
	fun aDrawableWithoutATileMeasuresNothing() {
		val model = splitModel()
		assertNull(meshReserveOf(model, looseId))
		assertTrue(drawableMeshTrianglesOf(model, looseId).isEmpty())
		assertNull(meshReserveOf(model, DrawableId("missing")))
	}

	/**
	 * A 64 px tile, unplaced so stored UVs are art UVs, split between two quads that each reach 2 px past
	 * their outer edge, plus a quad bound to no tile.
	 *
	 * @return PuppetModel The model.
	 */
	private fun splitModel(): PuppetModel {
		val drawables =
			listOf(
				quad(leftId, -2f, 0f, 32f, 64f, tileId),
				quad(rightId, 32f, 0f, 66f, 64f, tileId),
				quad(looseId, 0f, 0f, 8f, 8f, null),
			)

		return PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = drawables,
			rootChildren = drawables.map { drawable -> OrgChild.Drawable(drawable.id) },
			rootPartId = null,
		).copy(atlas = PuppetAtlas(tiles = listOf(AtlasTile(tileId, "tile", 64, 64))))
	}

	/**
	 * A quad drawable over an art-pixel rectangle of a 64 px tile.
	 *
	 * @param DrawableId   id     The drawable's id.
	 * @param Float        left   The left edge, art pixels.
	 * @param Float        top    The top edge.
	 * @param Float        right  The right edge.
	 * @param Float        bottom The bottom edge.
	 * @param AtlasTileId? tile   The tile it samples, or null.
	 * @return Drawable The drawable.
	 */
	private fun quad(id: DrawableId, left: Float, top: Float, right: Float, bottom: Float, tile: AtlasTileId?): Drawable {
		val corners = floatArrayOf(left, top, right, top, right, bottom, left, bottom)
		val uvs = FloatArray(corners.size) { component -> corners[component] / 64f }

		return Drawable(
			id = id,
			name = id.raw,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = DrawableMesh.withLocalEqualToCanvas(corners, uvs, intArrayOf(0, 1, 2, 0, 2, 3)),
			geometryGrid = null,
		).copy(atlasTileId = tile)
	}
}