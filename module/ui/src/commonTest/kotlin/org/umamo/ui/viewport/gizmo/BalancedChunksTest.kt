package org.umamo.ui.viewport.gizmo

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the parallel drive's chunk planning ([balancedChunks]) and the map over it
 * ([mapInBalancedChunks]): contiguous runs covering every item once, closed as they reach the chunk
 * weight, a light load as one run, and results back in item order.
 */
class BalancedChunksTest {
	/**
	 * Asserts [chunks] are contiguous, in order, and cover exactly [itemCount] items.
	 *
	 * @param List<IntRange> chunks The planned runs.
	 * @param Int itemCount How many items were planned.
	 */
	private fun assertCoversOnce(chunks: List<IntRange>, itemCount: Int) {
		var expectedStart = 0
		for (chunk in chunks) {
			assertEquals(expectedStart, chunk.first, "runs are contiguous and in order")
			assertTrue(chunk.last >= chunk.first, "no run is empty")
			expectedStart = chunk.last + 1
		}
		assertEquals(itemCount, expectedStart, "the runs cover every item")
	}

	/** No items plan no runs. */
	@Test
	fun noItemsPlanNoChunks() {
		assertTrue(balancedChunks(emptyList<Int>(), { 1 }).isEmpty())
	}

	/** A load within one chunk's weight plans as one run, which the map takes inline. */
	@Test
	fun aLightLoadIsOneChunk() {
		val items = List(10) { itemIndex -> itemIndex }
		assertEquals(listOf(items.indices), balancedChunks(items, { 1 }, minChunkWeight = 4096))
	}

	/** The least chunk weight bounds how small a run gets, the last taking what is left. */
	@Test
	fun theLeastChunkWeightBoundsTheRuns() {
		val items = List(100) { itemIndex -> itemIndex }
		assertEquals(listOf(0..29, 30..59, 60..89, 90..99), balancedChunks(items, { 1 }, minChunkWeight = 30, targetChunks = 64))
	}

	/** A heavy load splits into about the target count of runs, each closing as it reaches the chunk weight. */
	@Test
	fun aHeavyLoadSplitsNearTheTarget() {
		val items = List(1000) { itemIndex -> itemIndex % 7 + 1 }
		val targetChunks = 8
		val chunks = balancedChunks(items, { weight -> weight }, minChunkWeight = 10, targetChunks = targetChunks)
		assertCoversOnce(chunks, items.size)
		val totalWeight = items.sum()
		val chunkWeight = (totalWeight + targetChunks - 1) / targetChunks
		assertTrue(chunks.size in 2..targetChunks, "planned ${chunks.size} runs")
		for (chunk in chunks.dropLast(1)) {
			val weight = chunk.sumOf { itemIndex -> items[itemIndex] }
			assertTrue(weight >= chunkWeight, "a closed run reached the chunk weight")
			assertTrue(weight - items[chunk.last] < chunkWeight, "and closed on the item that reached it")
		}
	}

	/** The map over many runs returns every item's result once, in item order. */
	@Test
	fun theMapKeepsItemOrderAcrossChunks() =
		runTest {
			val items = List(1000) { itemIndex -> itemIndex }
			val transformedCounts = IntArray(items.size)
			val results =
				mapInBalancedChunks(items, { item -> item % 5 + 1 }, minChunkWeight = 10) { item ->
					transformedCounts[item]++
					item * 3
				}
			assertEquals(items.map { item -> item * 3 }, results)
			assertTrue(transformedCounts.all { count -> count == 1 }, "every item is transformed once")
		}
}