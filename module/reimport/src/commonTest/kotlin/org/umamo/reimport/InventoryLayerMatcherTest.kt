package org.umamo.reimport

import org.umamo.format.art.LayerRaster
import org.umamo.runtime.model.ArtSourceLayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The inventory matcher's signals and their combination: a hash match is certain, a rename at the
 * same place scores high, a namesake far away scores low, pixels count when they can be read, and
 * only the best few candidates pay for a pixel comparison.
 */
class InventoryLayerMatcherTest {
	private fun row(key: String, name: String, left: Int, top: Int, width: Int, height: Int, groupPath: String = "", hash: String? = null): ArtSourceLayer =
		ArtSourceLayer(key, name, groupPath, left, top, width, height, visible = true, contentHash = hash)

	private fun candidate(row: ArtSourceLayer, raster: LayerRaster? = null): MatchCandidate = MatchCandidate(row) { raster }

	@Test
	fun aContentHashMatchIsCertainWhateverTheNameAndPlace() {
		val missing = row("lyid:1", "Hair Front", 10, 20, 40, 40, hash = "abc")
		val ranked =
			InventoryLayerMatcher.rank(
				missing,
				null,
				listOf(candidate(row("lyid:7", "Hair Front", 12, 22, 40, 40)), candidate(row("lyid:9", "Untitled 3", 500, 500, 8, 8, hash = "abc"))),
			)
		assertEquals("lyid:9", ranked.first().key)
		assertEquals(1f, ranked.first().score)
		assertTrue(ranked.first().signals.hashEqual)
	}

	@Test
	fun aRenamedLayerAtTheSamePlaceScoresAboveTheThresholdAndANamesakeFarAwayBelowIt() {
		val missing = row("lyid:1", "Hair Front", 10, 20, 40, 40, groupPath = "Head")
		val ranked =
			InventoryLayerMatcher.rank(
				missing,
				null,
				listOf(candidate(row("lyid:2", "Hair Front 2", 10, 20, 40, 40, groupPath = "Head")), candidate(row("lyid:3", "Hair Front", 900, 900, 400, 10, groupPath = "Body"))),
			)
		assertEquals(listOf("lyid:2", "lyid:3"), ranked.map { match -> match.key })
		assertTrue(ranked[0].score >= InventoryLayerMatcher.DEFAULT_THRESHOLD, "renamed in place: ${ranked[0].score}")
		assertTrue(ranked[1].score < InventoryLayerMatcher.DEFAULT_THRESHOLD, "a namesake elsewhere: ${ranked[1].score}")
		assertNull(ranked[0].signals.pixels, "no pixels were read")
	}

	@Test
	fun aRowWithNoKnownExtentScoresOnNameAndPathAlone() {
		val missing = row("lyid:1", "Hair Front", 0, 0, 0, 0)
		val match = InventoryLayerMatcher.rank(missing, null, listOf(candidate(row("lyid:2", "Hair Front", 300, 300, 40, 40)))).single()
		assertNull(match.signals.bounds)
		assertNull(match.signals.size)
		assertEquals(1f, match.score, "the name and path agree, and nothing else could be scored")
	}

	@Test
	fun nameSimilarityIgnoresCaseAndSpacing() {
		assertEquals(1f, nameSimilarity("Hair Front", "hair   front"))
		assertEquals(1f, nameSimilarity("", ""))
		assertTrue(nameSimilarity("Hair Front", "Hair Front 2") > 0.8f)
		assertTrue(nameSimilarity("Eye L", "Mouth") < 0.3f)
	}

	@Test
	fun pathAgreementCountsSharedLeadingFolders() {
		assertEquals(1f, pathAgreement("", ""))
		assertEquals(1f, pathAgreement("Head/Hair", "Head/Hair"))
		assertEquals(0.5f, pathAgreement("Head", "Head/Hair"))
		assertEquals(0f, pathAgreement("Head", "Body"))
	}

	@Test
	fun pixelSimilarityCountsShapeAndColorAcrossSizes() {
		val white = solidRaster(4, 4, 0xFF.toByte())
		assertEquals(1f, pixelSimilarity(white, white))
		assertEquals(1f, pixelSimilarity(white, solidRaster(16, 8, 0xFF.toByte())), "the same picture at another size resamples alike")
		val black = solidRaster(4, 4, 0)
		assertEquals(0.5f, pixelSimilarity(white, black), "the same shape, opposite colors")
		val empty = LayerRaster(4, 4, ByteArray(64))
		assertEquals(0f, pixelSimilarity(white, empty), "nothing in common")
		assertEquals(1f, pixelSimilarity(empty, empty))
		assertEquals(0f, pixelSimilarity(white, LayerRaster(0, 0, ByteArray(0))))
	}

	@Test
	fun pixelsJoinTheScoreWhenReadAndOnlyForTheBestFewCandidates() {
		val missing = row("lyid:1", "Hair", 0, 0, 4, 4)
		val missingRaster = solidRaster(4, 4, 0xFF.toByte())
		var reads = 0
		val candidates =
			(0 until 8).map { index ->
				MatchCandidate(row("lyid:${index + 2}", "Hair $index", 0, 0, 4, 4)) {
					reads++
					if (index == 6) missingRaster else solidRaster(4, 4, 0)
				}
			}
		val ranked = InventoryLayerMatcher.rank(missing, missingRaster, candidates)
		assertEquals(5, reads, "only the top five metadata candidates are pixel-scored")
		assertEquals(5, ranked.count { match -> match.signals.pixels != null })
		val withPixels = ranked.filter { match -> match.signals.pixels != null }
		val without = ranked.filter { match -> match.signals.pixels == null }
		assertTrue(withPixels.all { match -> match.score < without.first().score }, "opposite pixels pull a candidate below one whose pixels were not read")
		assertTrue(without.map { match -> match.key }.contains("lyid:8"), "the seventh candidate by name was never read")
	}
}