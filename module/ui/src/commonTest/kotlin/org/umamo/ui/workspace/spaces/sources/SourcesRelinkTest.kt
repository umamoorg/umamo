package org.umamo.ui.workspace.spaces.sources

import org.umamo.runtime.model.ArtSource
import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.ArtSourceLayer
import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.SourceLayerRef
import org.umamo.ui.model.artwork.percentOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the relink list's grouping by file, which layers it may offer, the binding a relink to a layer
 * makes, and how a proposal's confidence reads.  None needs a composition.
 */
class SourcesRelinkTest {
	private val artA = ArtSourceId("art-0")
	private val artB = ArtSourceId("art-1")

	private fun layer(key: String, name: String): ArtSourceLayer = ArtSourceLayer(key, name, "", 0, 0, 4, 4, true)

	private fun tile(id: String, ref: SourceLayerRef?): AtlasTile = AtlasTile(AtlasTileId(id), id, 4, 4, source = ref)

	/** The relink list groups by file; the query keeps a layer by name or a whole file by its name. */
	@Test
	fun relinkGroupsByFileAndFiltersByLayerOrFileName() {
		val sources =
			listOf(
				ArtSource(artA, "Erica Tamamo.psd", null, "psd", listOf(layer("lyid:1", "Hair front"), layer("lyid:2", "Eye L"), layer("lyid:3", "Hair back"))),
				ArtSource(ArtSourceId("art-1"), "Extra parts.psd", null, "psd", listOf(layer("lyid:7", "Background"))),
				ArtSource(ArtSourceId("art-2"), "Empty.psd", null, "psd", emptyList()),
			)
		val all = relinkGroups(sources, "  ")
		assertEquals(listOf("Erica Tamamo.psd", "Extra parts.psd"), all.map { group -> group.source.name }, "a blank query lists every file that has layers")
		assertEquals(listOf("Hair front", "Eye L", "Hair back"), all[0].layers.map { layer -> layer.name })
		val byLayer = relinkGroups(sources, "hair")
		assertEquals(listOf("Erica Tamamo.psd"), byLayer.map { group -> group.source.name }, "a file with no matching layer is dropped")
		assertEquals(listOf("Hair front", "Hair back"), byLayer[0].layers.map { layer -> layer.name }, "only the matching layers survive, in order")
		val byFile = relinkGroups(sources, "EXTRA")
		assertEquals(listOf("Extra parts.psd"), byFile.map { group -> group.source.name })
		assertEquals(listOf("Background"), byFile[0].layers.map { layer -> layer.name }, "a file-name match keeps every layer of that file")
		assertTrue(relinkGroups(sources, "nothing here").isEmpty())
	}

	/** A row the file lost is kept for review, never offered as a relink target; nor is an erased or an ignored layer. */
	@Test
	fun lostRowsAreNeverRelinkTargets() {
		val sources =
			listOf(
				ArtSource(
					artA,
					"a.psd",
					null,
					"psd",
					listOf(layer("lyid:1", "Hair"), layer("lyid:2", "Old hair").copy(present = false), layer("lyid:3", "Blank").copy(empty = true), layer("lyid:4", "Sketch").copy(ignored = true)),
				),
			)
		assertEquals(listOf("Hair"), relinkGroups(sources, "").single().layers.map { layer -> layer.name }, "neither a lost row, an erased one, nor an ignored one is a target")
		assertTrue(relinkGroups(sources, "old").isEmpty())
		assertTrue(relinkGroups(sources, "sketch").isEmpty())
	}

	/** The chip's confidence is a whole percentage, rounded, never past the ends. */
	@Test
	fun suggestionScoresReadAsWholePercentages() {
		assertEquals(92, percentOf(0.924f))
		assertEquals(93, percentOf(0.925f))
		assertEquals(100, percentOf(1.2f))
		assertEquals(0, percentOf(-0.1f))
	}

	/** A layer no tile binds has only its key's shape to say how strong the key is. */
	@Test
	fun aRelinkToAnUnboundLayerIsTypedByItsKeysShape() {
		assertEquals(SourceLayerRef(artA, "lyid:7", stableKey = true), relinkTargetRef(emptyList(), artA, "lyid:7"))
		assertEquals(SourceLayerRef(artA, "name:Eye", stableKey = false), relinkTargetRef(emptyList(), artA, "name:Eye"))
		assertEquals(SourceLayerRef(artA, "Eye#2", stableKey = false), relinkTargetRef(emptyList(), artA, "Eye#2"))
	}

	/** A layer some tile binds takes that tile's word for its key, whatever the key's shape says. */
	@Test
	fun aRelinkToABoundLayerTakesTheTilesWord() {
		// A flat image's layer is keyed by its file name and marked weak by its reader; the shape reads stable.
		val weakByReader = listOf(tile("hat", SourceLayerRef(artA, "hat.png", stableKey = false)))
		assertEquals(SourceLayerRef(artA, "hat.png", stableKey = false), relinkTargetRef(weakByReader, artA, "hat.png"))

		val stableByReader = listOf(tile("eye", SourceLayerRef(artA, "name:Eye", stableKey = true)))
		assertEquals(SourceLayerRef(artA, "name:Eye", stableKey = true), relinkTargetRef(stableByReader, artA, "name:Eye"))
	}

	/** A layer is stable when any tile bound to it says so, which is what its row's status reads. */
	@Test
	fun aLayerIsStableWhenAnyTileBoundToItSaysSo() {
		val weakThenStable = listOf(tile("first", SourceLayerRef(artA, "hat.png", stableKey = false)), tile("second", SourceLayerRef(artA, "hat.png", stableKey = true)))
		val allWeak = listOf(tile("first", SourceLayerRef(artA, "hat.png", stableKey = false)), tile("second", SourceLayerRef(artA, "hat.png", stableKey = false)))

		assertEquals(SourceLayerRef(artA, "hat.png", stableKey = true), relinkTargetRef(weakThenStable, artA, "hat.png"))
		assertEquals(SourceLayerRef(artA, "hat.png", stableKey = false), relinkTargetRef(allWeak, artA, "hat.png"))
	}

	/** Only a tile bound to the very layer is asked: the same key in another file, and a tile bound to nothing, say nothing. */
	@Test
	fun aTileBoundElsewhereSaysNothing() {
		val tiles = listOf(tile("loose", null), tile("other", SourceLayerRef(artB, "hat.png", stableKey = false)), tile("near", SourceLayerRef(artA, "cap.png", stableKey = false)))

		assertEquals(SourceLayerRef(artA, "hat.png", stableKey = true), relinkTargetRef(tiles, artA, "hat.png"))
	}
}