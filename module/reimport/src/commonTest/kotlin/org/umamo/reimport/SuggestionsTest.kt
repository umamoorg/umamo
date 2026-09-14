package org.umamo.reimport

import org.umamo.runtime.model.ArtSource
import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.ArtSourceLayer
import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.SourceLayerRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The lost-row retention, the per-row suggestions over a model alone and against a read, and the confident picks. */
class SuggestionsTest {
	private val source = ArtSourceId("art-0")

	private fun row(key: String, name: String, left: Int, top: Int, present: Boolean = true): ArtSourceLayer =
		ArtSourceLayer(key, name, "", left, top, 10, 10, visible = true, present = present)

	/** A birth quad over a 10 x 10 tile whose layer sits at (50, 50): what an import mints, and nothing more. */
	private val freshQuad = DrawableMesh(floatArrayOf(50f, 50f, 60f, 50f, 60f, 60f, 50f, 60f), floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), intArrayOf(0, 1, 2, 0, 2, 3))

	/** A three-vertex mesh: edited, so rig work. */
	private val editedMesh = DrawableMesh(floatArrayOf(50f, 50f, 51f, 50f, 50f, 51f), floatArrayOf(0f, 0f, 0.1f, 0f, 0f, 0.1f), intArrayOf(0, 1, 2))

	private fun drawable(id: String, tileId: String, mesh: DrawableMesh): Drawable =
		Drawable(DrawableId(id), id, null, BlendMode.Normal, emptyList(), mesh, null, atlasTileId = AtlasTileId(tileId))

	private fun model(drawables: List<Drawable>, tiles: List<AtlasTile>, layers: List<ArtSourceLayer>): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = drawables,
			rootChildren = emptyList(),
			rootPartId = null,
			atlas = PuppetAtlas(tiles = tiles),
			sources = listOf(ArtSource(source, "a.psd", null, "psd", layers)),
		)

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

		// An erased layer saves with a collapsed rectangle; its row keeps the frame the art last had.
		val erasedFresh = listOf(row("a", "A", 0, 0).copy(width = 0, height = 0, empty = true, contentHash = "blank"))
		val erased = inventoryWithMissing(listOf(row("a", "A", 30, 40)), erasedFresh, boundKeys = setOf("a")).single()
		assertEquals(listOf(30, 40, 10, 10), listOf(erased.left, erased.top, erased.width, erased.height), "the frame the art last had")
		assertTrue(erased.empty && erased.contentHash == "blank", "while the erasure itself is recorded")
		val stillErased = inventoryWithMissing(listOf(erased), erasedFresh, boundKeys = setOf("a")).single()
		assertEquals(listOf(30, 40, 10, 10), listOf(stillErased.left, stillErased.top, stillErased.width, stillErased.height), "and keeps it across further saves")

		// A row lost to Replace Artwork says so; one lost before the replace keeps the deletion reading.
		val replaced = inventoryWithMissing(previous, fresh, boundKeys = setOf("a", "b", "c"), lostByReplacement = true)
		assertEquals(listOf(false, false, true, false), replaced.map { layer -> layer.replaced }, "only the row lost in this refresh reads as replaced")
		assertTrue(inventoryWithMissing(previous, fresh, boundKeys = setOf("a", "b", "c")).none { layer -> layer.replaced }, "a plain reload records a deletion")
		// The rigger's ignore mark is the document's, not the file's: it rides the fresh row, and a new layer starts unmarked.
		val marked = inventoryWithMissing(listOf(row("a", "A", 0, 0).copy(ignored = true)), fresh, boundKeys = emptySet())
		assertEquals(listOf(true, false), marked.map { layer -> layer.ignored })
		val markedErased = inventoryWithMissing(listOf(row("a", "A", 30, 40).copy(ignored = true)), erasedFresh, boundKeys = emptySet()).single()
		assertTrue(markedErased.ignored && markedErased.left == 30, "the mark and the kept frame ride together")
	}

	@Test
	fun eachLostRowGetsItsBestUnboundCandidate() {
		// The Nose has an edited mesh over its tile: rig work, so it is never a candidate.
		val model =
			model(
				drawables = listOf(drawable("nose", "t3", editedMesh)),
				tiles =
					listOf(
						AtlasTile(AtlasTileId("t1"), "Eye L", 10, 10, source = SourceLayerRef(source, "name:Eye L#1", false)),
						AtlasTile(AtlasTileId("t2"), "Mouth", 10, 10, source = SourceLayerRef(source, "lyid:9", true)),
						AtlasTile(AtlasTileId("t3"), "Nose", 10, 10, source = SourceLayerRef(source, "lyid:3", true)),
						AtlasTile(AtlasTileId("t4"), "Brow", 10, 10, source = SourceLayerRef(source, "lyid:77", true)),
					),
				layers =
					listOf(
						row("lyid:3", "Nose", 50, 50),
						row("lyid:4", "Eye Left", 0, 0),
						row("lyid:5", "Mouth Open", 100, 100),
						row("lyid:6", "Brow Left", 20, 0),
						row("name:Eye L#1", "Eye L", 0, 0, present = false),
						row("lyid:9", "Mouth", 100, 100, present = false),
					),
			)
		val suggestions = suggestionsFor(model, source)
		assertEquals(setOf("name:Eye L#1", "lyid:9", "lyid:77"), suggestions.keys)
		assertEquals("lyid:4", suggestions.getValue("name:Eye L#1").key, "the bound Nose, under rig work, is never a candidate")
		assertEquals("lyid:5", suggestions.getValue("lyid:9").key)
		assertEquals("lyid:6", suggestions.getValue("lyid:77").key, "a binding the inventory never listed is scored from its tile")

		// A layer erased to nothing is reviewed like a lost one, and never offered as a candidate.
		val erasedModel =
			model.copy(
				sources =
					listOf(
						model.sources.single().copy(
							layers =
								listOf(
									row("lyid:3", "Nose", 50, 50).copy(empty = true),
									row("lyid:4", "Eye Left", 0, 0),
									row("lyid:8", "Nose (blank)", 50, 50).copy(empty = true),
									row("lyid:9", "Mouth", 100, 100, present = false),
									row("lyid:5", "Mouth Open", 100, 100),
								),
						),
					),
			)
		val erased = suggestionsFor(erasedModel, source)
		val proposedForNose = erased.getValue("lyid:3").key
		assertTrue(proposedForNose == "lyid:4" || proposedForNose == "lyid:5", "the emptied Nose is proposed a layer with art, got $proposedForNose")
		assertTrue(erased.values.none { match -> match.key == "lyid:8" }, "the blank namesake is never a candidate")
		// An ignored layer is never a candidate either: the rigger keeps it out of the rig.
		val ignoredModel =
			model.copy(sources = listOf(model.sources.single().copy(layers = model.sources.single().layers.map { layer -> if (layer.key == "lyid:4") layer.copy(ignored = true) else layer })))
		assertTrue(suggestionsFor(ignoredModel, source).values.none { match -> match.key == "lyid:4" }, "an ignored layer is no candidate")
		assertTrue(suggestionsFor(model, ArtSourceId("art-9")).isEmpty(), "an unknown file proposes nothing")
	}

	@Test
	fun aBoundLayerIsNeverProposedOnTheModelAloneAndTheConfidentPicksTakeEachCandidateOnce() {
		// The lost Nose was re-created as lyid:3 and a reload minted a fresh drawable for it.  On the
		// model alone the layer is bound and so not a candidate - nothing here tells that fresh drawable
		// from any other untouched one - but a proposal that names it (the reload's own, scored before it
		// minted) retires exactly that tile, and never a tile with rig work over it.
		val fresh = drawable("fresh", "t3", freshQuad)
		val tiles =
			listOf(
				AtlasTile(AtlasTileId("t1"), "Nose", 10, 10, source = SourceLayerRef(source, "lyid:1", true)),
				AtlasTile(AtlasTileId("t3"), "Nose", 10, 10, source = SourceLayerRef(source, "lyid:3", true)),
			)
		val layers = listOf(row("lyid:3", "Nose", 50, 50), row("lyid:1", "Nose", 50, 50, present = false))
		val model = model(listOf(fresh), tiles, layers)
		assertTrue(suggestionsFor(model, source).isEmpty(), "a bound layer is no candidate on the model alone")
		assertEquals(listOf(AtlasTileId("t3")), retirableTiles(model, source, "lyid:3", except = setOf(AtlasTileId("t1"))), "what a proposal naming the layer retires")
		val deformed = model.copy(drawables = listOf(fresh.copy(parentDeformerId = DeformerId("warp"))))
		assertTrue(retirableTiles(deformed, source, "lyid:3", except = setOf(AtlasTileId("t1"))).isEmpty(), "under a deformer it is rig work, and stays")
		assertTrue(retirableTiles(model, source, "lyid:3", except = setOf(AtlasTileId("t3"))).isEmpty(), "the tiles doing the claiming are never retired")

		val signals = MatchSignals(1f, 1f, 1f, 1f, null, hashEqual = false)
		val suggestions =
			mapOf(
				"a" to LayerMatch("x", 0.9f, signals),
				"b" to LayerMatch("x", 0.95f, signals),
				"c" to LayerMatch("y", 0.7f, signals),
				"d" to LayerMatch("z", 0.69f, signals),
			)
		assertEquals(mapOf("b" to "x", "c" to "y"), confidentMatches(suggestions, 0.7f), "best first, each candidate once, the bar inclusive")
		assertEquals(listOf("b", "c"), confidentMatches(suggestions, 0.7f).keys.toList(), "most confident first")
		assertTrue(confidentMatches(suggestions, 1f).isEmpty())
	}

	@Test
	fun scoringAgainstAReadRefreshesTheInventoryFirst() {
		// The model still lists lyid:1 as present (the file was never reloaded); the art as read has
		// lyid:3 in its place.  On the model alone nothing is lost; against the read, the binding is.
		val model = model(emptyList(), listOf(AtlasTile(AtlasTileId("t1"), "Nose", 10, 10, source = SourceLayerRef(source, "lyid:1", true))), listOf(row("lyid:1", "Nose", 50, 50)))
		val read = listOf(row("lyid:3", "Nose", 50, 50))
		assertTrue(suggestionsFor(model, source).isEmpty())
		assertEquals("lyid:3", suggestionsAgainstRead(model, source, read).getValue("lyid:1").key)
		assertTrue(suggestionsAgainstRead(model, ArtSourceId("art-9"), read).isEmpty(), "an unknown file proposes nothing")
	}
}