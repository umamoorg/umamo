package org.umamo.format.atlas

import org.umamo.format.art.analyzeAlpha
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers the packer end to end: trimming, page composition, gutters and extrusion, page sizing, the
 * placement/skip partition, and determinism.  The bin-packing geometry alone is [RectPackerTest].
 */
class AtlasPackTest {
	@Test
	fun tilesAreTrimmedToTheirOpaqueBounds() {
		val item =
			packItemOfRows(
				"a",
				".....",
				"..##.",
				"..##.",
				".....",
			)

		val result = packAtlas(listOf(item))

		val placement = result.placements.single()
		val expected = assertNotNull(analyzeAlpha(item.width, item.height, item.rgba))
		assertEquals(expected.opaqueBounds.left, placement.trimLeft)
		assertEquals(expected.opaqueBounds.top, placement.trimTop)
		assertEquals(expected.opaqueBounds.width, placement.trimWidth)
		assertEquals(expected.opaqueBounds.height, placement.trimHeight)
	}

	@Test
	fun everyTileReadsBackOutOfItsPageByteForByte() {
		val items =
			listOf(
				opaquePackItem("wide", 12, 4),
				opaquePackItem("tall", 4, 12),
				opaquePackItem("square", 7, 7),
				packItemOfRows("holed", "###", "#.#", "###"),
			)

		val result = packAtlas(items, AtlasPackOptions(maxPageSize = 64))

		assertEquals(items.size, result.placements.size)
		assertTilesRoundTripByteExact(items, result)
		assertNoTileOverlap(result, gutter = 2)
	}

	@Test
	fun aQuarterTurnedTileReadsBackOutOfItsPageByteForByte() {
		// Sized so the second tile only fits the strip the first leaves behind when turned.
		val items = listOf(opaquePackItem("a", 16, 8), opaquePackItem("b", 8, 16))

		val result = packAtlas(items, AtlasPackOptions(maxPageSize = 16, gutter = 0, extrude = 0, allowRotation = true))

		assertEquals(1, result.pages.size, "both tiles must share one page")
		assertEquals(1, result.placements.count { placement -> placement.quarterTurns == 1 })
		assertTilesRoundTripByteExact(items, result)
	}

	@Test
	fun composingFromPlacementsAloneReproducesThePackedPages() {
		val items =
			listOf(
				opaquePackItem("wide", 12, 4),
				opaquePackItem("tall", 4, 12),
				opaquePackItem("square", 7, 7),
				packItemOfRows("holed", "###", "#.#", "###"),
			)
		val options = AtlasPackOptions(maxPageSize = 64)

		val result = packAtlas(items, options)
		val composed =
			composeAtlasPages(
				pageWidths = IntArray(result.pages.size) { pageIndex -> result.pages[pageIndex].width },
				pageHeights = IntArray(result.pages.size) { pageIndex -> result.pages[pageIndex].height },
				items = items,
				placements = result.placements,
				extrude = options.extrude,
			)

		assertEquals(result.pages.size, composed.size)
		for (pageIndex in result.pages.indices) {
			assertContentEquals(result.pages[pageIndex].rgba, composed[pageIndex].rgba, "page $pageIndex pixels")
		}
	}

	@Test
	fun composingFromPlacementsReproducesAQuarterTurnedPage() {
		val items = listOf(opaquePackItem("a", 16, 8), opaquePackItem("b", 8, 16))
		val options = AtlasPackOptions(maxPageSize = 16, gutter = 0, extrude = 0, allowRotation = true)

		val result = packAtlas(items, options)
		val composed =
			composeAtlasPages(
				pageWidths = intArrayOf(result.pages.single().width),
				pageHeights = intArrayOf(result.pages.single().height),
				items = items,
				placements = result.placements,
				extrude = options.extrude,
			)

		assertEquals(1, result.placements.count { placement -> placement.quarterTurns == 1 })
		assertContentEquals(result.pages.single().rgba, composed.single().rgba)
	}

	@Test
	fun aReserveSpacesNeighborsWhileThePlacementStaysOnTheTrim() {
		// Two 4x4 opaque tiles; the first reserves a mesh reach of 20px on every side.  Without the
		// reserve they pack shoulder to shoulder (gutter apart); with it, nothing may sit inside the
		// first tile's reserved rect - which extends 20px past its trim in page space.
		val reach = 20
		val items =
			listOf(
				AtlasPackItem(
					"reaching",
					4,
					4,
					opaquePackItem("reaching", 4, 4).rgba,
					AtlasPackReserve(-reach, -reach, 4 + reach, 4 + reach),
				),
				opaquePackItem("plain", 4, 4),
			)

		val result = packAtlas(items, AtlasPackOptions(maxPageSize = 256))

		assertEquals(2, result.placements.size)
		assertTilesRoundTripByteExact(items, result)
		val reaching = result.placements.first { placement -> placement.key == "reaching" }
		val plain = result.placements.first { placement -> placement.key == "plain" }
		// The placement anchors the TRIM, so the reserved margin lies around it on the page.
		val reservedLeft = reaching.pageX - reach
		val reservedTop = reaching.pageY - reach
		val reservedRight = reaching.pageX + reaching.trimWidth + reach
		val reservedBottom = reaching.pageY + reaching.trimHeight + reach
		assertTrue(reservedLeft >= 0 && reservedTop >= 0, "the reserve stays on the page")
		val plainRight = plain.pageX + plain.trimWidth
		val plainBottom = plain.pageY + plain.trimHeight
		val outside =
			plain.pageX >= reservedRight ||
				plainRight <= reservedLeft ||
				plain.pageY >= reservedBottom ||
				plainBottom <= reservedTop
		assertTrue(
			outside,
			"the neighbor must sit outside the reserved rect: plain at (${plain.pageX},${plain.pageY})," +
				" reserve $reservedLeft..$reservedRight x $reservedTop..$reservedBottom",
		)
	}

	@Test
	fun aReserveLargerThanThePageSkipsTheTile() {
		val item =
			AtlasPackItem("vast", 4, 4, opaquePackItem("vast", 4, 4).rgba, AtlasPackReserve(-300, -300, 300, 300))

		val result = packAtlas(listOf(item), AtlasPackOptions(maxPageSize = 128))

		assertEquals(AtlasPackSkipReason.LargerThanPage, result.skipped.single().reason)
	}

	@Test
	fun aReserveTurnsWithItsTile() {
		// A 19x8 tile packs first and leaves a 19x11 strip; the 8x12 tile with a 2 px reserve to its RIGHT
		// (a 10x12 reservation) fits that strip only turned.  The turn sends the reservation's right-hand
		// band ABOVE the placed trim, so the trim lands two rows below the strip's top, not on it.
		val wide = opaquePackItem("wide", 19, 8)
		val reaching = AtlasPackItem("reaching", 8, 12, opaquePackItem("reaching", 8, 12).rgba, AtlasPackReserve(0, 0, 10, 12))
		val items = listOf(wide, reaching)

		val result = packAtlas(items, AtlasPackOptions(maxPageSize = 19, gutter = 0, extrude = 0, allowRotation = true))

		assertEquals(1, result.pages.size, "both tiles share one page")
		val turned = result.placements.first { placement -> placement.key == "reaching" }
		val upright = result.placements.first { placement -> placement.key == "wide" }
		assertEquals(0, upright.quarterTurns)
		assertEquals(1, turned.quarterTurns, "the reaching tile only fits turned")
		assertEquals(0, turned.pageX)
		assertEquals(upright.pageY + upright.trimHeight + 2, turned.pageY, "the turned reservation's band sits above the trim")
		assertTilesRoundTripByteExact(items, result)
		assertNoTileOverlap(result, gutter = 0)
	}

	@Test
	fun composingRefusesAPlacementWhoseExtrusionLeavesThePage() {
		val item = opaquePackItem("a", 4, 4)
		val placement = AtlasPackPlacement("a", 0, 1, 1, 0, 0, 4, 4, quarterTurns = 0)

		assertFailsWith<IllegalArgumentException> {
			composeAtlasPages(intArrayOf(16), intArrayOf(16), listOf(item), listOf(placement), extrude = 2)
		}
	}

	@Test
	fun aTurnedPlacementSwapsItsOnPageExtent() {
		val placement = AtlasPackPlacement("a", 0, 0, 0, 0, 0, 12, 5, quarterTurns = 1)

		assertEquals(5, placement.pageWidth)
		assertEquals(12, placement.pageHeight)
	}

	@Test
	fun extrusionReplicatesTheTileEdgeAndNothingElse() {
		val item = opaquePackItem("a", 3, 2)

		val result = packAtlas(listOf(item), AtlasPackOptions(maxPageSize = 64, gutter = 2, extrude = 2))

		val placement = result.placements.single()
		val page = result.pages.single()
		for (rowOffset in -2 until placement.trimHeight + 2) {
			for (columnOffset in -2 until placement.trimWidth + 2) {
				val clampedColumn = columnOffset.coerceIn(0, placement.trimWidth - 1)
				val clampedRow = rowOffset.coerceIn(0, placement.trimHeight - 1)
				assertEquals(
					itemPixel(item, placement.trimLeft + clampedColumn, placement.trimTop + clampedRow),
					pagePixel(page, placement.pageX + columnOffset, placement.pageY + rowOffset),
					"extruded pixel ($columnOffset, $rowOffset)",
				)
			}
		}
	}

	@Test
	fun withoutExtrusionEveryPixelOutsideATileStaysTransparent() {
		val items = listOf(opaquePackItem("a", 5, 3), opaquePackItem("b", 3, 5))

		val result = packAtlas(items, AtlasPackOptions(maxPageSize = 64, gutter = 2, extrude = 0))

		for ((pageIndex, page) in result.pages.withIndex()) {
			val covered = HashSet<Int>()
			for (placement in result.placements.filter { placement -> placement.pageIndex == pageIndex }) {
				for (tileY in 0 until placement.pageHeight) {
					for (tileX in 0 until placement.pageWidth) {
						covered.add((placement.pageY + tileY) * page.width + placement.pageX + tileX)
					}
				}
			}
			for (pixelIndex in 0 until page.width * page.height) {
				if (pixelIndex in covered) {
					continue
				}
				assertEquals(0, pagePixel(page, pixelIndex % page.width, pixelIndex / page.width), "pixel $pixelIndex")
			}
		}
	}

	@Test
	fun placementsAndSkipsPartitionTheInputExactly() {
		val items =
			listOf(
				opaquePackItem("packed", 4, 4),
				packItemOfRows("empty", "...", "..."),
				opaquePackItem("huge", 200, 4),
				packItemOfRows("sliver", ".#."),
			)

		val result = packAtlas(items, AtlasPackOptions(maxPageSize = 32))

		assertEquals(
			items.map { item -> item.key }.sorted(),
			(result.placements.map { placement -> placement.key } + result.skipped.map { skip -> skip.key }).sorted(),
		)
		assertEquals(
			listOf(
				AtlasPackSkip("empty", AtlasPackSkipReason.NoOpaquePixels),
				AtlasPackSkip("huge", AtlasPackSkipReason.LargerThanPage),
			),
			result.skipped,
		)
		assertEquals(listOf("packed", "sliver"), result.placements.map { placement -> placement.key })
	}

	@Test
	fun aTileBelowTheMinimumCoverageIsReportedRatherThanPacked() {
		val items = listOf(opaquePackItem("solid", 4, 4), packItemOfRows("speck", ".#.", "..."))

		val result = packAtlas(items, AtlasPackOptions(maxPageSize = 32, minimumOpaquePixels = 2))

		assertEquals(listOf(AtlasPackSkip("speck", AtlasPackSkipReason.BelowMinimumCoverage)), result.skipped)
		assertEquals(listOf("solid"), result.placements.map { placement -> placement.key })
	}

	@Test
	fun skipsAndPlacementsComeBackInTheCallerSInputOrder() {
		val items =
			listOf(
				packItemOfRows("first-empty", ".."),
				opaquePackItem("zeta", 3, 3),
				packItemOfRows("second-empty", ".."),
				opaquePackItem("alpha", 9, 9),
			)

		val result = packAtlas(items, AtlasPackOptions(maxPageSize = 64))

		assertEquals(listOf("zeta", "alpha"), result.placements.map { placement -> placement.key })
		assertEquals(listOf("first-empty", "second-empty"), result.skipped.map { skip -> skip.key })
	}

	@Test
	fun pagesShrinkToWhatTheyUseAndRoundUpToAPowerOfTwo() {
		val result = packAtlas(listOf(opaquePackItem("a", 2, 2)), AtlasPackOptions(maxPageSize = 4096, gutter = 2))

		// A 2x2 tile with a 2 px gutter on every side uses 6x6, which squares and rounds to 8.
		val page = result.pages.single()
		assertEquals(8, page.width)
		assertEquals(8, page.height)
		assertEquals(2, result.placements.single().pageX)
		assertEquals(2, result.placements.single().pageY)
	}

	@Test
	fun shrinkingCanBeTurnedOffToKeepFullSizePages() {
		val result =
			packAtlas(
				listOf(opaquePackItem("a", 2, 2)),
				AtlasPackOptions(maxPageSize = 128, gutter = 2, shrinkPages = false),
			)

		assertEquals(128, result.pages.single().width)
		assertEquals(128, result.pages.single().height)
	}

	@Test
	fun nonSquarePagesKeepTheirOwnAxes() {
		val result =
			packAtlas(
				listOf(opaquePackItem("a", 30, 2)),
				AtlasPackOptions(maxPageSize = 4096, gutter = 1, extrude = 1, squarePages = false),
			)

		val page = result.pages.single()
		assertEquals(32, page.width)
		assertEquals(4, page.height)
	}

	@Test
	fun occupancyMeasuresPackedTilePixelsAgainstThePage() {
		val result =
			packAtlas(
				listOf(opaquePackItem("a", 4, 4)),
				AtlasPackOptions(maxPageSize = 64, gutter = 0, extrude = 0),
			)

		// A 4x4 tile alone on a 4x4 page covers all of it.
		assertEquals(4, result.pages.single().width)
		assertEquals(1.0f, result.pageOccupancy(0))
	}

	@Test
	fun theCallerSItemOrderDoesNotChangeThePacking() {
		val items =
			listOf(
				opaquePackItem("a", 13, 7),
				opaquePackItem("b", 7, 13),
				opaquePackItem("c", 11, 11),
				opaquePackItem("d", 5, 21),
				opaquePackItem("e", 21, 5),
				opaquePackItem("f", 3, 3),
			)

		val forward = packAtlas(items, AtlasPackOptions(maxPageSize = 32))
		val reversed = packAtlas(items.reversed(), AtlasPackOptions(maxPageSize = 32))

		assertContentEquals(
			forward.placements.sortedBy { placement -> placement.key },
			reversed.placements.sortedBy { placement -> placement.key },
		)
		assertEquals(forward.pages.size, reversed.pages.size)
		for (pageIndex in forward.pages.indices) {
			assertContentEquals(forward.pages[pageIndex].rgba, reversed.pages[pageIndex].rgba, "page $pageIndex pixels")
		}
	}

	@Test
	fun packingSpillsToFurtherPagesAndKeepsEveryTileReadable() {
		val items = (0 until 6).map { itemIndex -> opaquePackItem("tile$itemIndex", 20, 20) }

		val result = packAtlas(items, AtlasPackOptions(maxPageSize = 32, gutter = 2, extrude = 2))

		assertEquals(6, result.pages.size, "a 32 page holds one 24x24 footprint")
		assertTrue(result.skipped.isEmpty())
		assertTilesRoundTripByteExact(items, result)
		assertNoTileOverlap(result, gutter = 2)
	}

	@Test
	fun anEmptyItemListPacksToNothing() {
		val result = packAtlas(emptyList())

		assertTrue(result.pages.isEmpty())
		assertTrue(result.placements.isEmpty())
		assertTrue(result.skipped.isEmpty())
	}

	@Test
	fun anExtrusionWiderThanTheGutterIsRejected() {
		val failure =
			assertFailsWith<IllegalArgumentException> {
				packAtlas(listOf(opaquePackItem("a", 2, 2)), AtlasPackOptions(gutter = 1, extrude = 2))
			}

		assertTrue(failure.message.orEmpty().contains("extrude"), "the message must name the offending option")
	}

	@Test
	fun duplicateKeysAreRejectedBecauseTheyMakeTheOrderAmbiguous() {
		assertFailsWith<IllegalArgumentException> {
			packAtlas(listOf(opaquePackItem("a", 2, 2), opaquePackItem("a", 3, 3)))
		}
	}

	@Test
	fun anItemWhosePixelsDoNotMatchItsDimensionsIsRejected() {
		assertFailsWith<IllegalArgumentException> {
			AtlasPackItem("a", 4, 4, ByteArray(4 * 4 * 3))
		}
	}
}