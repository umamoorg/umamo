package org.umamo.edit

import org.umamo.runtime.model.AtlasPage
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableLayerBinding
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.applyUvAffine
import org.umamo.runtime.model.invertUvAffine
import org.umamo.runtime.model.layerUvAffineOf
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the whole-atlas repack edit: pages and placements install atomically with every bound
 * drawable's coordinates re-derived against the NEW page inventory (the exact read a per-tile fold
 * gets wrong), unchanged mappings pass arrays through by reference, the session commit is one undo
 * step under its own label, and malformed input throws instead of half-applying.
 */
class AtlasRepackEditsTest {
	private val tileAId = AtlasTileId("tileA")
	private val tileBId = AtlasTileId("tileB")

	private fun placement(pageIndex: Int, x: Float, y: Float): AtlasPlacement =
		AtlasPlacement(pageIndex, x, y, scaleX = 1f, scaleY = 1f, rotationDegrees = 0f)

	private fun meshedDrawable(id: String, tileId: AtlasTileId, uvs: FloatArray): Drawable =
		Drawable(
			id = DrawableId(id),
			name = id,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = DrawableMesh(floatArrayOf(0f, 0f, 1f, 1f), uvs, intArrayOf(0, 0, 0)),
			geometryGrid = null,
			atlasTileId = tileId,
		)

	private fun packedModel(atlas: PuppetAtlas, drawables: List<Drawable>): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = drawables,
			rootChildren = emptyList(),
			rootPartId = null,
			canvasWidth = 100f,
			canvasHeight = 200f,
			worldOriginX = 50f,
			worldOriginY = 100f,
			atlas = atlas,
		)

	/**
	 * The stored coordinates a tile's art uvs take under one placement, built independently of the
	 * edit's internals from the same corpus-proven affine algebra.
	 *
	 * @param FloatArray      artUvs    Coordinates in the tile's own frame.
	 * @param AtlasTileId     tileId    The tile they belong to.
	 * @param AtlasPlacement? placed    Where the tile sits.
	 * @param AtlasPage       page      The page the placement names.
	 * @param Int             tileSide  The square tile's side in pixels.
	 * @return FloatArray The page-addressed stored coordinates.
	 */
	private fun storedUvsAt(
		artUvs: FloatArray,
		tileId: AtlasTileId,
		placed: AtlasPlacement?,
		page: AtlasPage,
		tileSide: Int,
	): FloatArray {
		val binding = DrawableLayerBinding(tileId.raw, placed, page.width, page.height)
		val storedToArt = assertNotNull(layerUvAffineOf(binding, tileSide, tileSide))
		return applyUvAffine(artUvs, assertNotNull(invertUvAffine(storedToArt)))
	}

	private fun assertUvsClose(expected: FloatArray, actual: FloatArray, message: String) {
		assertEquals(expected.size, actual.size, message)
		for (componentIndex in expected.indices) {
			assertTrue(
				abs(expected[componentIndex] - actual[componentIndex]) < 1e-5f,
				"$message: component $componentIndex expected ${expected[componentIndex]}, was ${actual[componentIndex]}",
			)
		}
	}

	@Test
	fun repackInstallsPagesAndPlacementsAndKeepsEveryVertexOnItsArtPixel() {
		val oldPage = AtlasPage(64, 64)
		val newPage = AtlasPage(128, 128)
		val oldA = placement(0, 4f, 4f)
		val oldB = placement(0, 40f, 40f)
		val atlas =
			PuppetAtlas(
				pages = listOf(oldPage),
				tiles = listOf(AtlasTile(tileAId, "A", 16, 16, oldA), AtlasTile(tileBId, "B", 16, 16, oldB)),
			)
		val artUvsA = floatArrayOf(0.25f, 0.5f, 0.75f, 0.125f)
		val artUvsB = floatArrayOf(0f, 0f, 1f, 1f)
		val base =
			packedModel(
				atlas,
				listOf(
					meshedDrawable("dA", tileAId, storedUvsAt(artUvsA, tileAId, oldA, oldPage, 16)),
					meshedDrawable("dB", tileBId, storedUvsAt(artUvsB, tileBId, oldB, oldPage, 16)),
				),
			)
		val newA = placement(0, 100f, 8f)
		val newB = placement(0, 8f, 100f)

		val repacked = base.withAtlasRepack(listOf(newPage), mapOf(tileAId to newA, tileBId to newB))

		assertEquals(listOf(newPage), repacked.atlas.pages, "the page inventory was replaced")
		assertEquals(newA, repacked.atlas.tiles[0].placement)
		assertEquals(newB, repacked.atlas.tiles[1].placement)
		assertUvsClose(
			storedUvsAt(artUvsA, tileAId, newA, newPage, 16),
			repacked.drawables[0].mesh!!.uvs,
			"tile A's drawable still addresses the same art pixels at the new spot",
		)
		assertUvsClose(
			storedUvsAt(artUvsB, tileBId, newB, newPage, 16),
			repacked.drawables[1].mesh!!.uvs,
			"tile B's drawable still addresses the same art pixels at the new spot",
		)
		assertSame(
			base.drawables[0].mesh!!.positions,
			repacked.drawables[0].mesh!!.positions,
			"moving art on a page never moves the mesh",
		)
	}

	@Test
	fun repackRederivesAgainstTheNewPageDimensionsNotTheOld() {
		// The placement does not move at all; only the page grows.  A fold through the per-tile edit
		// would read the new mapping off the old 64px inventory and leave these coordinates untouched.
		val samePlacement = placement(0, 4f, 4f)
		val atlas =
			PuppetAtlas(
				pages = listOf(AtlasPage(64, 64)),
				tiles = listOf(AtlasTile(tileAId, "A", 16, 16, samePlacement)),
			)
		val artUvs = floatArrayOf(0.5f, 0.5f)
		val base =
			packedModel(
				atlas,
				listOf(meshedDrawable("dA", tileAId, storedUvsAt(artUvs, tileAId, samePlacement, AtlasPage(64, 64), 16))),
			)

		val repacked = base.withAtlasRepack(listOf(AtlasPage(128, 128)), mapOf(tileAId to samePlacement))

		assertUvsClose(
			storedUvsAt(artUvs, tileAId, samePlacement, AtlasPage(128, 128), 16),
			repacked.drawables.single().mesh!!.uvs,
			"the coordinates renormalized against the new page size",
		)
	}

	@Test
	fun anUnchangedMappingPassesItsCoordinateArraysThroughByReference() {
		val oldPage = AtlasPage(64, 64)
		val stayA = placement(0, 4f, 4f)
		val oldB = placement(0, 40f, 40f)
		val atlas =
			PuppetAtlas(
				pages = listOf(oldPage),
				tiles = listOf(AtlasTile(tileAId, "A", 16, 16, stayA), AtlasTile(tileBId, "B", 16, 16, oldB)),
			)
		val base =
			packedModel(
				atlas,
				listOf(
					meshedDrawable("dA", tileAId, floatArrayOf(0.1f, 0.1f)),
					meshedDrawable("dB", tileBId, floatArrayOf(0.7f, 0.7f)),
				),
			)

		val repacked = base.withAtlasRepack(listOf(oldPage), mapOf(tileAId to stayA, tileBId to placement(0, 8f, 40f)))

		assertSame(base.drawables[0].mesh, repacked.drawables[0].mesh, "an unmoved tile's drawable is untouched")
		assertFalse(
			base.drawables[1].mesh!!.uvs.contentEquals(repacked.drawables[1].mesh!!.uvs),
			"the moved tile's drawable re-derived",
		)

		val unchanged = base.withAtlasRepack(listOf(oldPage), mapOf(tileAId to stayA, tileBId to oldB))
		assertSame(base, unchanged, "restating the atlas exactly is a no-op")
	}

	@Test
	fun anUnpackedTileLowersItsArtFrameCoordinatesOntoThePage() {
		val page = AtlasPage(64, 64)
		val atlas =
			PuppetAtlas(
				pages = listOf(page),
				tiles = listOf(AtlasTile(tileAId, "A", 16, 16, placement = null)),
			)
		// Unpacked art stores its coordinates in the tile's own frame.
		val artUvs = floatArrayOf(0.25f, 0.5f)
		val base = packedModel(atlas, listOf(meshedDrawable("dA", tileAId, artUvs.copyOf())))
		val placed = placement(0, 32f, 16f)

		val repacked = base.withAtlasRepack(listOf(page), mapOf(tileAId to placed))

		assertUvsClose(
			storedUvsAt(artUvs, tileAId, placed, page, 16),
			repacked.drawables.single().mesh!!.uvs,
			"packing never-packed art lowers its coordinates onto the page",
		)
	}

	@Test
	fun sessionCommitIsOneUndoStepUnderItsOwnLabel() {
		val oldPage = AtlasPage(64, 64)
		val oldA = placement(0, 4f, 4f)
		val atlas = PuppetAtlas(pages = listOf(oldPage), tiles = listOf(AtlasTile(tileAId, "A", 16, 16, oldA)))
		val base = packedModel(atlas, listOf(meshedDrawable("dA", tileAId, floatArrayOf(0.2f, 0.3f))))
		val session = EditorSession(base)
		val storedBefore = session.model.value.drawables.single().mesh!!.uvs.copyOf()

		session.commitAtlasRepack(listOf(AtlasPage(128, 128)), mapOf(tileAId to placement(0, 60f, 60f)))
		assertEquals(listOf(AtlasPage(128, 128)), session.model.value.atlas.pages)
		assertTrue(session.dirty.value, "a repack is document content, so it dirties")
		assertEquals("change.document.atlasRepack", DocumentChange.RepackAtlas(1, 1).labelKey)

		session.undo()
		assertEquals(listOf(oldPage), session.model.value.atlas.pages, "one undo restores the whole repack")
		assertContentEquals(storedBefore, session.model.value.drawables.single().mesh!!.uvs)
		assertFalse(session.dirty.value, "undo restores the saved model instance")

		// Restating the current state commits nothing.
		session.commitAtlasRepack(listOf(oldPage), mapOf(tileAId to oldA))
		assertFalse(session.canUndo.value)
	}

	@Test
	fun malformedInputThrowsInsteadOfHalfApplying() {
		val page = AtlasPage(64, 64)
		val atlas =
			PuppetAtlas(
				pages = listOf(page),
				tiles = listOf(AtlasTile(tileAId, "A", 16, 16, placement(0, 4f, 4f))),
			)
		val base = packedModel(atlas, listOf(meshedDrawable("dA", tileAId, floatArrayOf(0.2f, 0.3f))))

		assertFailsWith<IllegalArgumentException>("a repack must restate every tile") {
			base.withAtlasRepack(listOf(page), emptyMap())
		}
		assertFailsWith<IllegalArgumentException>("a placement must name a page the repack ships") {
			base.withAtlasRepack(listOf(page), mapOf(tileAId to placement(3, 4f, 4f)))
		}

		// A bound tile whose old mapping cannot invert has no expressible re-derivation.
		val degenerate =
			base.copy(
				atlas =
					atlas.copy(
						tiles = listOf(AtlasTile(tileAId, "A", 16, 16, AtlasPlacement(0, 4f, 4f, 0f, 0f, 0f))),
					),
			)
		assertFailsWith<IllegalArgumentException>("a bound tile with a degenerate mapping is refused") {
			degenerate.withAtlasRepack(listOf(page), mapOf(tileAId to placement(0, 8f, 8f)))
		}
	}
}