package org.umamo.reimport

import org.umamo.runtime.model.ArtSource
import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.ArtSourceLayer
import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.SourceLayerRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The lost-row retention and the per-row suggestions over a model alone. */
class SuggestionsTest {
	private val source = ArtSourceId("art-0")

	private fun row(key: String, name: String, left: Int, top: Int, present: Boolean = true): ArtSourceLayer =
		ArtSourceLayer(key, name, "", left, top, 10, 10, visible = true, present = present)

	@Test
	fun theRefreshKeepsOnlyTheLostRowsATileStillBinds() {
		val previous = listOf(row("a", "A", 0, 0), row("b", "B", 0, 0), row("c", "C", 0, 0, present = false))
		val fresh = listOf(row("a", "A", 5, 5), row("d", "D", 0, 0))
		val refreshed = inventoryWithMissing(previous, fresh, boundKeys = setOf("a", "b", "c"))
		assertEquals(listOf("a", "d", "b", "c"), refreshed.map { layer -> layer.key })
		assertEquals(listOf(true, true, false, false), refreshed.map { layer -> layer.present })
		assertEquals(5, refreshed.first().left, "the fresh row wins over the previous one")
		assertEquals(listOf("a", "d"), inventoryWithMissing(previous, fresh, boundKeys = setOf("a")).map { layer -> layer.key }, "an unbound lost row is dropped")
		val untouched = inventoryWithMissing(previous, fresh, boundKeys = setOf("a"), untouchedKeys = setOf("a"))
		assertEquals(0, untouched.first().left, "a bound layer the plan left alone keeps its previous row, so the next reload still sees its change")
	}

	@Test
	fun eachLostRowGetsItsBestUnboundCandidate() {
		val model =
			PuppetModel(
				parameters = emptyList(),
				parts = emptyList(),
				deformers = emptyList(),
				drawables = emptyList(),
				rootChildren = emptyList(),
				rootPartId = null,
				atlas =
					PuppetAtlas(
						tiles =
							listOf(
								AtlasTile(AtlasTileId("t1"), "Eye L", 10, 10, source = SourceLayerRef(source, "name:Eye L#1", false)),
								AtlasTile(AtlasTileId("t2"), "Mouth", 10, 10, source = SourceLayerRef(source, "lyid:9", true)),
								AtlasTile(AtlasTileId("t3"), "Nose", 10, 10, source = SourceLayerRef(source, "lyid:3", true)),
								AtlasTile(AtlasTileId("t4"), "Brow", 10, 10, source = SourceLayerRef(source, "lyid:77", true)),
							),
					),
				sources =
					listOf(
						ArtSource(
							source,
							"a.psd",
							null,
							"psd",
							listOf(
								row("lyid:3", "Nose", 50, 50),
								row("lyid:4", "Eye Left", 0, 0),
								row("lyid:5", "Mouth Open", 100, 100),
								row("lyid:6", "Brow Left", 20, 0),
								row("name:Eye L#1", "Eye L", 0, 0, present = false),
								row("lyid:9", "Mouth", 100, 100, present = false),
							),
						),
					),
			)
		val suggestions = suggestionsFor(model, source)
		assertEquals(setOf("name:Eye L#1", "lyid:9", "lyid:77"), suggestions.keys)
		assertEquals("lyid:4", suggestions.getValue("name:Eye L#1").key, "the bound Nose is never a candidate")
		assertEquals("lyid:5", suggestions.getValue("lyid:9").key)
		assertEquals("lyid:6", suggestions.getValue("lyid:77").key, "a binding the inventory never listed is scored from its tile")
		assertTrue(suggestionsFor(model, ArtSourceId("art-9")).isEmpty(), "an unknown file proposes nothing")
	}
}