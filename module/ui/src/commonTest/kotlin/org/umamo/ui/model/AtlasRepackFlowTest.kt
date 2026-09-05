package org.umamo.ui.model

import org.umamo.format.atlas.AtlasPackFixed
import org.umamo.format.atlas.AtlasPackItem
import org.umamo.format.atlas.AtlasPackSkip
import org.umamo.format.atlas.AtlasPackSkipReason
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
import kotlin.test.assertTrue

/**
 * Pins the repack's abort rule: a BOUND tile the pack cannot carry refuses the whole repack - a
 * skipped tile, undecodable art, a degenerate placement, or a placement naming a missing page - while
 * an unbound tile never refuses (nothing samples it, so nothing can be stranded).
 */
class AtlasRepackFlowTest {
	private val boundId = AtlasTileId("bound")
	private val freeId = AtlasTileId("free")

	private fun placement(pageIndex: Int = 0, scaleX: Float = 1f): AtlasPlacement =
		AtlasPlacement(pageIndex, 4f, 4f, scaleX = scaleX, scaleY = 1f, rotationDegrees = 0f)

	private fun modelWith(boundTile: AtlasTile, freeTile: AtlasTile): PuppetModel {
		val drawable =
			Drawable(
				id = DrawableId("d"),
				name = "d",
				parentDeformerId = null,
				blendMode = BlendMode.Normal,
				maskedBy = emptyList(),
				mesh = null,
				geometryGrid = null,
				atlasTileId = boundId,
			)
		return PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = listOf(drawable),
			rootChildren = emptyList(),
			rootPartId = null,
			canvasWidth = 100f,
			canvasHeight = 100f,
			worldOriginX = 50f,
			worldOriginY = 50f,
			atlas = PuppetAtlas(pages = listOf(AtlasPage(64, 64)), tiles = listOf(boundTile, freeTile)),
		)
	}

	@Test
	fun anUnboundTileNeverRefusesAndABoundSkipDoes() {
		val model =
			modelWith(
				AtlasTile(boundId, "Bound Art", 16, 16, placement()),
				AtlasTile(freeId, "Free Art", 16, 16, placement()),
			)

		val cleanRefusals = repackRefusals(model, emptyList(), emptySet())
		assertTrue(cleanRefusals.isEmpty(), "a healthy pack refuses nothing")

		val freeSkipped = repackRefusals(model, listOf(AtlasPackSkip(freeId.raw, AtlasPackSkipReason.NoOpaquePixels)), emptySet())
		assertTrue(freeSkipped.isEmpty(), "an unbound tile skips freely")

		val boundSkipped = repackRefusals(model, listOf(AtlasPackSkip(boundId.raw, AtlasPackSkipReason.LargerThanPage)), emptySet())
		assertEquals(1, boundSkipped.size)
		assertEquals("Bound Art", boundSkipped.single().tileName, "the report names the tile verbatim")
		assertEquals(AtlasRepackRefusalReason.LargerThanPage, boundSkipped.single().reason)
	}

	@Test
	fun undecodableArtRefusesOnlyWhenBound() {
		val model =
			modelWith(
				AtlasTile(boundId, "Bound Art", 16, 16, placement()),
				AtlasTile(freeId, "Free Art", 16, 16, placement()),
			)

		assertTrue(repackRefusals(model, emptyList(), setOf(freeId)).isEmpty())
		assertEquals(
			AtlasRepackRefusalReason.Undecodable,
			repackRefusals(model, emptyList(), setOf(boundId)).single().reason,
		)
	}

	@Test
	fun aDegenerateOrDanglingPlacementRefusesTheRederivation() {
		val zeroScaled =
			modelWith(
				AtlasTile(boundId, "Bound Art", 16, 16, placement(scaleX = 0f)),
				AtlasTile(freeId, "Free Art", 16, 16, placement()),
			)
		assertEquals(
			AtlasRepackRefusalReason.DegeneratePlacement,
			repackRefusals(zeroScaled, emptyList(), emptySet()).single().reason,
		)

		val danglingPage =
			modelWith(
				AtlasTile(boundId, "Bound Art", 16, 16, placement(pageIndex = 7)),
				AtlasTile(freeId, "Free Art", 16, 16, placement()),
			)
		assertEquals(
			AtlasRepackRefusalReason.DegeneratePlacement,
			repackRefusals(danglingPage, emptyList(), emptySet()).single().reason,
		)

		// A bound but UNPLACED tile has an identity mapping, which always inverts.
		val unplaced =
			modelWith(
				AtlasTile(boundId, "Bound Art", 16, 16, placement = null),
				AtlasTile(freeId, "Free Art", 16, 16, placement()),
			)
		assertTrue(repackRefusals(unplaced, emptyList(), emptySet()).isEmpty())
	}

	/**
	 * The three ways one decoded input is handed to the packer: every tile free, the pinned tiles
	 * fixed, or EVERY placed tile fixed (art added to an open document packs into the gaps).  The
	 * fixed forms are the packer's, keyed by tile, and a free item stays the same instance.
	 */
	@Test
	fun theInputHandsThePackerFreePinnedOrEveryPlacedTileFixed() {
		val pinnedForm = AtlasPackFixed(0, floatArrayOf(1f, 0f, 4f, 0f, 1f, 4f))
		val placedForm = AtlasPackFixed(0, floatArrayOf(1f, 0f, 20f, 0f, 1f, 20f))
		val pinned = AtlasPackItem("pinned", 1, 1, ByteArray(4))
		val placed = AtlasPackItem("placed", 1, 1, ByteArray(4))
		val fresh = AtlasPackItem("fresh", 1, 1, ByteArray(4))
		val input =
			RepackPackInput(
				items = listOf(pinned, placed, fresh),
				fixedByKey = mapOf("pinned" to pinnedForm),
				placedByKey = mapOf("pinned" to pinnedForm, "placed" to placedForm),
				undecodableTileIds = emptySet(),
				reserveByTile = emptyMap(),
			)

		assertTrue(input.itemsFor(keepPinned = false).all { item -> item.fixed == null }, "pins ignored: every tile free")
		val keepingPins = input.itemsFor(keepPinned = true)
		assertEquals(listOf(pinnedForm, null, null), keepingPins.map { item -> item.fixed }, "pins kept: only the pinned tile is fixed")
		val fixingPlaced = input.itemsFor(keepPinned = true, fixPlaced = true)
		assertEquals(listOf(pinnedForm, placedForm, null), fixingPlaced.map { item -> item.fixed }, "placed fixed: every placed tile stays, the fresh one packs")
		assertTrue(fixingPlaced[2] === fresh, "a free item is the same instance")
	}
}