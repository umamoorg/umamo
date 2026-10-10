package org.umamo.render

import org.umamo.format.atlas.AtlasPackReserve
import org.umamo.runtime.model.AtlasPage
import org.umamo.runtime.model.AtlasPlacement
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
import kotlin.test.assertEquals

/**
 * The mesh reserve ([meshReserveByTile]) rounds a tile's mesh reach out to whole art pixels, but a bound
 * within float noise of a pixel edge counts as on it: stored coordinates are re-derived whenever a placement
 * moves, and the noise must not widen the reserve, while a mesh that really reaches past an edge still does.
 */
class MeshReserveTest {
	/** A mesh exactly over its tile, its stored corners a hair to the outside, keeps the tile's own reserve. */
	@Test
	fun floatNoiseAtAPixelEdgeDoesNotWidenTheReserve() {
		val model = modelWithQuadOver(left = 0f, top = 0f, right = TILE_SIDE.toFloat(), bottom = TILE_SIDE.toFloat(), noise = STORED_NOISE)

		assertEquals(AtlasPackReserve(0, 0, TILE_SIDE, TILE_SIDE), meshReserveByTile(model).getValue(TILE))
	}

	/** A mesh reaching half a pixel past every edge still rounds out to the next whole pixel. */
	@Test
	fun aRealReachPastAnEdgeStillRoundsOut() {
		val model = modelWithQuadOver(left = -0.5f, top = -0.5f, right = TILE_SIDE + 0.5f, bottom = TILE_SIDE + 0.5f, noise = 0f)

		assertEquals(AtlasPackReserve(-1, -1, TILE_SIDE + 1, TILE_SIDE + 1), meshReserveByTile(model).getValue(TILE))
	}

	/**
	 * A model with one tile placed upright at unit scale and one quad bound to it whose mesh spans the given
	 * art-frame rectangle, its stored coordinates pushed outward by [noise] (in stored units).
	 *
	 * @param Float left The quad's left edge in art pixels.
	 * @param Float top The quad's top edge in art pixels.
	 * @param Float right The quad's right edge in art pixels.
	 * @param Float bottom The quad's bottom edge in art pixels.
	 * @param Float noise How far each stored coordinate is pushed outward.
	 * @return PuppetModel The model.
	 */
	private fun modelWithQuadOver(left: Float, top: Float, right: Float, bottom: Float, noise: Float): PuppetModel {
		// Stored coordinates address the page with v = 0 on its top row: art (x, y) lands at the position plus (x, y).
		val leftU = (PLACEMENT_X + left) / PAGE_SIDE - noise
		val rightU = (PLACEMENT_X + right) / PAGE_SIDE + noise
		val topV = (PLACEMENT_Y + top) / PAGE_SIDE - noise
		val bottomV = (PLACEMENT_Y + bottom) / PAGE_SIDE + noise
		val uvs = floatArrayOf(leftU, topV, rightU, topV, rightU, bottomV, leftU, bottomV)
		val quad =
			Drawable(
				id = QUAD,
				name = QUAD.raw,
				parentDeformerId = null,
				blendMode = BlendMode.Normal,
				maskedBy = emptyList(),
				mesh = DrawableMesh.withLocalEqualToCanvas(FloatArray(uvs.size), uvs, intArrayOf(0, 1, 2, 0, 2, 3)),
				geometryGrid = null,
			).copy(atlasTileId = TILE)
		val placement = AtlasPlacement(pageIndex = 0, positionX = PLACEMENT_X, positionY = PLACEMENT_Y, scaleX = 1f, scaleY = 1f, rotationDegrees = 0f)
		return PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = listOf(quad),
			rootChildren = listOf(OrgChild.Drawable(QUAD)),
			rootPartId = null,
		).copy(atlas = PuppetAtlas(pages = listOf(AtlasPage(PAGE_SIDE, PAGE_SIDE)), tiles = listOf(AtlasTile(TILE, TILE.raw, TILE_SIDE, TILE_SIDE, placement))))
	}

	private companion object {
		/** The quad. */
		val QUAD = DrawableId("quad")

		/** The quad's tile. */
		val TILE = AtlasTileId("quadTile")

		/** The page's side in pixels. */
		const val PAGE_SIDE = 256

		/** The tile's side in pixels. */
		const val TILE_SIDE = 20

		/** The tile's left edge on the page. */
		const val PLACEMENT_X = 50f

		/** The tile's top edge on the page. */
		const val PLACEMENT_Y = 196f

		/** Float noise in stored units: about a quarter of the tolerance, in art pixels, on this page. */
		const val STORED_NOISE = 1e-6f
	}
}