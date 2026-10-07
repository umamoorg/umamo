package org.umamo.ui.workspace.spaces.sources

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import org.umamo.edit.atlas.setLayerIgnored
import org.umamo.runtime.model.SourceLayerRef
import org.umamo.ui.workspace.spaces.keyformsheet.anyPopupOpen
import org.umamo.ui.workspace.spaces.parameters.clickAt
import org.umamo.ui.workspace.spaces.parameters.clickMenuEntry
import org.umamo.ui.workspace.spaces.parameters.popupShows
import org.umamo.ui.workspace.spaces.parameters.secondaryClickAt
import org.umamo.ui.workspace.spaces.parameters.shownInOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the menus a row and its chip show, and the request each entry hands the artwork commands: a file's
 * two actions, a layer's ignore toggle, a review's proposal, and a tile's relink list.
 */
@OptIn(ExperimentalTestApi::class)
class SourcesMenuTest {
	/** A file row's context menu lists Replace Artwork and Reload This File, and Replace names the file. */
	@Test
	fun aFileRowsContextMenuReplacesItsFile() =
		runComposeUiTest {
			val harness = mountSources()

			secondaryClickAt(sourcesRowBox(SourcesNames.BODY).center)
			val entries = listOf(harness.text.sourcesFileReplace, harness.text.sourcesFileReload)
			assertEquals(entries, shownInOrder(entries))
			clickMenuEntry(harness.text.sourcesFileReplace)

			assertEquals(listOf(SourcesIds.body), harness.artworkRequests.replaces.map { request -> request.sourceId })
			assertFalse(anyPopupOpen(), "a pick closes the menu")
		}

	/** A file row's chip lists the same two entries, and Reload covers that one file. */
	@Test
	fun aFileRowsChipReloadsItsFile() =
		runComposeUiTest {
			val harness = mountSources()

			clickAt(sourcesSlotPoint(harness.text.sourcesFileMenu, SourcesNames.FACE))
			val entries = listOf(harness.text.sourcesFileReplace, harness.text.sourcesFileReload)
			assertEquals(entries, shownInOrder(entries))
			clickMenuEntry(harness.text.sourcesFileReload)

			assertEquals(listOf(setOf(SourcesIds.face)), harness.artworkRequests.reloads.map { scope -> scope?.sourceIds })
		}

	/** An unbound layer's context menu ignores it, after which the same row offers to stop. */
	@Test
	fun anUnboundLayersContextMenuIgnoresIt() =
		runComposeUiTest {
			val harness = mountSources()

			secondaryClickAt(sourcesRowBox(SourcesNames.SKETCH).center)
			assertTrue(popupShows(harness.text.sourcesLayerIgnore))
			assertFalse(popupShows(harness.text.sourcesLayerUnignore))
			clickMenuEntry(harness.text.sourcesLayerIgnore)

			val request = harness.artworkRequests.ignores.single()
			assertEquals(SourceLayerRef(SourcesIds.body, SourcesKeys.SKETCH, stableKey = true), request.ref)
			assertTrue(request.ignored)
			assertTrue(sourcesRowHasSlot(harness.text.sourcesIgnored, SourcesNames.SKETCH), "the row reads ignored")
			secondaryClickAt(sourcesRowBox(SourcesNames.SKETCH).center)
			assertTrue(popupShows(harness.text.sourcesLayerUnignore))
		}

	/** An ignored layer's chip offers Stop Ignoring, which clears the mark. */
	@Test
	fun anIgnoredLayersChipStopsIgnoringIt() =
		runComposeUiTest {
			val harness = mountSources()

			clickAt(sourcesSlotPoint(harness.text.sourcesLayerMenu, SourcesNames.NOTES))
			assertTrue(popupShows(harness.text.sourcesLayerUnignore))
			assertFalse(popupShows(harness.text.sourcesLayerIgnore))
			clickMenuEntry(harness.text.sourcesLayerUnignore)

			val request = harness.artworkRequests.ignores.single()
			assertEquals(SourceLayerRef(SourcesIds.body, SourcesKeys.NOTES, stableKey = true), request.ref)
			assertFalse(request.ignored)
		}

	/** A bound layer has neither the ignore chip nor a context menu: it is matched by key, so the mark means nothing. */
	@Test
	fun aBoundLayerHasNoMenuAndNoChip() =
		runComposeUiTest {
			val harness = mountSources()

			assertFalse(sourcesRowHasSlot(harness.text.sourcesLayerMenu, SourcesNames.HAIR))
			secondaryClickAt(sourcesRowBox(SourcesNames.HAIR).center)

			assertFalse(anyPopupOpen())
		}

	/** A review row's chip offers the proposal, a relink by hand, and leaving it, in that order. */
	@Test
	fun aReviewChipListsTheProposalThenTheTwoOtherWays() =
		runComposeUiTest {
			val harness = mountSources()

			clickAt(sourcesSlotPoint(harness.text.sourcesReview, SourcesNames.BROW_OLD))

			val entries = listOf(harness.text.sourcesAccept, harness.text.sourcesRelinkByHand, harness.text.sourcesLeave)
			assertEquals(entries, shownInOrder(entries))
			assertFalse(popupShows(harness.text.sourcesAcceptMerge))
		}

	/** Accepting moves every tile bound to the lost layer as one request, and retires nothing when the candidate is unbound. */
	@Test
	fun acceptingRelinksEveryTileOfTheLostLayerAtOnce() =
		runComposeUiTest {
			val harness = mountSources()

			clickAt(sourcesSlotPoint(harness.text.sourcesReview, SourcesNames.BROW_OLD))
			clickMenuEntry(harness.text.sourcesAccept)

			val request = harness.artworkRequests.relinks.single()
			assertEquals(listOf(SourcesIds.browArtLeft, SourcesIds.browArtRight), request.tileIds)
			assertEquals(SourceLayerRef(SourcesIds.body, SourcesKeys.BROW, stableKey = true), request.ref)
			assertTrue(request.retire.isEmpty())
			assertFalse(sourcesShows(SourcesNames.BROW_OLD), "a lost row nothing binds leaves the table")
		}

	/**
	 * A name search lists only the rows that match, and a lost layer's proposal still moves every tile bound
	 * to the layer: the row stands for the binding, not for what the search shows of it.
	 */
	@Test
	fun acceptingUnderASearchRelinksEveryTileOfTheLostLayer() =
		runComposeUiTest {
			val harness = mountSources()
			runOnIdle { harness.sourcesViewState.query = SourcesNames.BROW_ART_LEFT }
			waitForIdle()
			assertTrue(sourcesShows(SourcesNames.BROW_ART_LEFT))
			assertFalse(sourcesShows(SourcesNames.BROW_ART_RIGHT), "the search must really have left one tile out")

			clickAt(sourcesSlotPoint(harness.text.sourcesReview, SourcesNames.BROW_OLD))
			clickMenuEntry(harness.text.sourcesAccept)

			assertEquals(listOf(SourcesIds.browArtLeft, SourcesIds.browArtRight), harness.artworkRequests.relinks.single().tileIds)
		}

	/** A relink by hand under a search moves every tile bound to the lost layer too. */
	@Test
	fun aPickByHandUnderASearchRelinksEveryTileOfTheLostLayer() =
		runComposeUiTest {
			val harness = mountSources()
			runOnIdle { harness.sourcesViewState.query = SourcesNames.BROW_ART_RIGHT }
			waitForIdle()

			clickAt(sourcesSlotPoint(harness.text.sourcesReview, SourcesNames.BROW_OLD))
			clickMenuEntry(harness.text.sourcesRelinkByHand)
			clickMenuEntry(SourcesNames.SKETCH)

			assertEquals(listOf(SourcesIds.browArtLeft, SourcesIds.browArtRight), harness.artworkRequests.relinks.single().tileIds)
		}

	/** A proposal naming a layer under a fresh drawable says so, and accepting names the tile it retires. */
	@Test
	fun acceptingAMergeNamesTheTileItRetires() =
		runComposeUiTest {
			val harness = mountSources(model = sourcesMergeModel())

			clickAt(sourcesSlotPoint(harness.text.sourcesReview, SourcesNames.BROW_OLD))
			assertTrue(popupShows(harness.text.sourcesAcceptMerge))
			assertFalse(popupShows(harness.text.sourcesAccept))
			clickMenuEntry(harness.text.sourcesAcceptMerge)

			val request = harness.artworkRequests.relinks.single()
			assertEquals(listOf(SourcesIds.browArtLeft, SourcesIds.browArtRight), request.tileIds)
			assertEquals(SourcesKeys.BROW, request.ref?.layerKey)
			assertEquals(listOf(SourcesIds.browArtNew), request.retire)
		}

	/**
	 * Accepting types the binding as the tile already bound to the proposed layer does: a key its reader
	 * marked weak stays weak, though its shape reads stable.
	 */
	@Test
	fun acceptingFollowsTheTileAlreadyBoundToTheProposedLayer() =
		runComposeUiTest {
			val harness = mountSources(model = mergeModelWithAWeakCandidate())

			clickAt(sourcesSlotPoint(harness.text.sourcesReview, SourcesNames.BROW_OLD))
			clickMenuEntry(harness.text.sourcesAcceptMerge)

			assertEquals(SourceLayerRef(SourcesIds.body, SourcesKeys.BROW, stableKey = false), harness.artworkRequests.relinks.single().ref)
		}

	/** A review row nothing was proposed for offers only the two other ways. */
	@Test
	fun aReviewChipWithNoProposalOffersNoAccept() =
		runComposeUiTest {
			val harness = mountSources(model = modelWithNoCandidate())
			runOnIdle { harness.publishedSuggestions.value = emptyMap() }
			waitForIdle()

			clickAt(sourcesSlotPoint(harness.text.sourcesReview, SourcesNames.BROW_OLD))

			val entries = listOf(harness.text.sourcesRelinkByHand, harness.text.sourcesLeave)
			assertEquals(entries, shownInOrder(entries))
			assertFalse(popupShows(harness.text.sourcesAccept))
		}

	/** Relink Manually flips the same open chip to the searchable list, which offers no Unbind. */
	@Test
	fun relinkingByHandFlipsTheChipToTheList() =
		runComposeUiTest {
			val harness = mountSources()

			clickAt(sourcesSlotPoint(harness.text.sourcesReview, SourcesNames.BROW_OLD))
			clickMenuEntry(harness.text.sourcesRelinkByHand)

			assertTrue(popupShows(SourcesNames.SKETCH), "the list of layers")
			assertTrue(popupShows(SourcesNames.BODY), "under their file")
			assertFalse(popupShows(harness.text.sourcesUnbind))
			assertFalse(popupShows(harness.text.sourcesLeave), "the proposal page is gone")
			assertTrue(harness.artworkRequests.relinks.isEmpty())
		}

	/** A pick from the by-hand list moves every tile of the lost layer and retires nothing. */
	@Test
	fun aPickByHandRelinksEveryTileOfTheLostLayer() =
		runComposeUiTest {
			val harness = mountSources()

			clickAt(sourcesSlotPoint(harness.text.sourcesReview, SourcesNames.BROW_OLD))
			clickMenuEntry(harness.text.sourcesRelinkByHand)
			clickMenuEntry(SourcesNames.SKETCH)

			val request = harness.artworkRequests.relinks.single()
			assertEquals(listOf(SourcesIds.browArtLeft, SourcesIds.browArtRight), request.tileIds)
			assertEquals(SourceLayerRef(SourcesIds.body, SourcesKeys.SKETCH, stableKey = true), request.ref)
			assertTrue(request.retire.isEmpty())
			assertFalse(anyPopupOpen())
		}

	/** A chip reopened after a relink by hand starts on the proposal page again. */
	@Test
	fun aReopenedReviewChipStartsOnTheProposal() =
		runComposeUiTest {
			val harness = mountSources()
			clickAt(sourcesSlotPoint(harness.text.sourcesReview, SourcesNames.BROW_OLD))
			clickMenuEntry(harness.text.sourcesRelinkByHand)
			pressOutsideTheMenu()
			assertFalse(popupShows(SourcesNames.SKETCH), "the list must really have closed")

			clickAt(sourcesSlotPoint(harness.text.sourcesReview, SourcesNames.BROW_OLD))

			assertTrue(popupShows(harness.text.sourcesLeave))
			assertFalse(popupShows(SourcesNames.SKETCH))
		}

	/** Leave for Now closes the chip and asks for nothing. */
	@Test
	fun leavingAsksForNothing() =
		runComposeUiTest {
			val harness = mountSources()

			clickAt(sourcesSlotPoint(harness.text.sourcesReview, SourcesNames.BROW_OLD))
			clickMenuEntry(harness.text.sourcesLeave)

			assertTrue(harness.artworkRequests.relinks.isEmpty())
			assertFalse(anyPopupOpen())
			assertTrue(sourcesShows(SourcesNames.BROW_OLD))
		}

	/** A bound tile's chip lists the layers a relink may take, under their files, and leaves out the ones it may not. */
	@Test
	fun aTileChipListsTheLayersARelinkMayTake() =
		runComposeUiTest {
			val harness = mountSources()
			openRow(harness, SourcesRowKeys.HAIR)

			clickAt(sourcesSlotPoint(harness.text.sourcesRelink, SourcesNames.HAIR_ART))

			val offered = listOf(SourcesNames.BODY, SourcesNames.HAIR, SourcesNames.EYE, SourcesNames.SKETCH, SourcesNames.BROW, SourcesNames.FACE, SourcesNames.MOUTH)
			assertEquals(offered, shownInOrder(offered))
			assertFalse(popupShows(SourcesNames.NOTES), "an ignored layer is kept out of the rig")
			assertFalse(popupShows(SourcesNames.BROW_OLD), "a layer the file lost is no target")
			assertTrue(popupShows(harness.text.sourcesUnbind), "a bound tile may be unbound")
			assertFalse(popupShows(harness.text.sourcesDeleteArt), "a tile a drawable samples may not leave the atlas")
		}

	/**
	 * An open relink list follows the document: a layer ignored while the list shows leaves it.  The list
	 * sits in a popup under a row of a lazy list, and reads the model there and nowhere above.
	 */
	@Test
	fun anOpenRelinkListDropsALayerIgnoredWhileItShows() =
		runComposeUiTest {
			val harness = mountSources()
			clickAt(sourcesSlotPoint(harness.text.sourcesRelink, SourcesNames.LOOSE_ART))
			assertTrue(popupShows(SourcesNames.SKETCH), "the list must really have opened, with Sketch on it")

			runOnIdle { harness.session.setLayerIgnored(SourceLayerRef(SourcesIds.body, SourcesKeys.SKETCH, stableKey = true), ignored = true) }
			waitForIdle()

			assertFalse(popupShows(SourcesNames.SKETCH), "an ignored layer is kept out of the rig")
			assertTrue(popupShows(SourcesNames.HAIR), "and the list is still open")
		}

	/** The layer a tile is bound to already is listed and inert. */
	@Test
	fun theCurrentBindingIsListedAndInert() =
		runComposeUiTest {
			val harness = mountSources()
			openRow(harness, SourcesRowKeys.HAIR)

			clickAt(sourcesSlotPoint(harness.text.sourcesRelink, SourcesNames.HAIR_ART))
			clickMenuEntry(SourcesNames.HAIR)

			assertTrue(harness.artworkRequests.relinks.isEmpty())
			assertTrue(popupShows(SourcesNames.SKETCH), "the menu stays open")
		}

	/** Unbind asks for the tile's binding to be cleared. */
	@Test
	fun unbindClearsTheTilesBinding() =
		runComposeUiTest {
			val harness = mountSources()
			openRow(harness, SourcesRowKeys.HAIR)

			clickAt(sourcesSlotPoint(harness.text.sourcesRelink, SourcesNames.HAIR_ART))
			clickMenuEntry(harness.text.sourcesUnbind)

			val request = harness.artworkRequests.relinks.single()
			assertEquals(listOf(SourcesIds.hairArt), request.tileIds)
			assertNull(request.ref)
			assertTrue(request.retire.isEmpty())
			assertNull(tileOf(harness, SourcesIds.hairArt)?.source)
		}

	/** An unbound tile's chip offers no Unbind. */
	@Test
	fun anUnboundTileOffersNoUnbind() =
		runComposeUiTest {
			val harness = mountSources()

			clickAt(sourcesSlotPoint(harness.text.sourcesRelink, SourcesNames.LOOSE_ART))

			assertTrue(popupShows(SourcesNames.SKETCH), "the menu must really have opened")
			assertFalse(popupShows(harness.text.sourcesUnbind))
			assertFalse(popupShows(harness.text.sourcesDeleteArt))
		}

	/** A tile no drawable samples offers Delete Art, which names the tile. */
	@Test
	fun aTileNothingSamplesOffersDeleteArt() =
		runComposeUiTest {
			val harness = mountSources()
			openRow(harness, SourcesRowKeys.MOUTH)

			clickAt(sourcesSlotPoint(harness.text.sourcesRelink, SourcesNames.MOUTH_ART))
			assertTrue(popupShows(harness.text.sourcesDeleteArt))
			clickMenuEntry(harness.text.sourcesDeleteArt)

			assertEquals(listOf(SourcesIds.mouthArt), harness.artworkRequests.deletes.map { request -> request.tileId })
			assertNull(tileOf(harness, SourcesIds.mouthArt), "the tile left the atlas")
		}

	/** A pick of an unbound layer types the binding by the key's shape. */
	@Test
	fun aPickOfAnUnboundLayerIsTypedByItsKeysShape() =
		runComposeUiTest {
			val harness = mountSources()

			clickAt(sourcesSlotPoint(harness.text.sourcesRelink, SourcesNames.LOOSE_ART))
			clickMenuEntry(SourcesNames.SKETCH)

			val request = harness.artworkRequests.relinks.single()
			assertEquals(listOf(SourcesIds.looseArt), request.tileIds)
			assertEquals(SourceLayerRef(SourcesIds.body, SourcesKeys.SKETCH, stableKey = true), request.ref)
			assertTrue(request.retire.isEmpty())
			assertFalse(anyPopupOpen())
		}

	/** A pick of a layer some tile already binds takes that tile's word for the key's strength, against its shape. */
	@Test
	fun aPickOfABoundLayerFollowsTheTileAlreadyBoundToIt() =
		runComposeUiTest {
			val harness = mountSources(model = modelWithEyeBoundAsStable())

			clickAt(sourcesSlotPoint(harness.text.sourcesRelink, SourcesNames.LOOSE_ART))
			clickMenuEntry(SourcesNames.EYE)

			val request = harness.artworkRequests.relinks.single()
			assertEquals(SourceLayerRef(SourcesIds.body, SourcesKeys.EYE, stableKey = true), request.ref, "a name key reads weak by its shape")
		}

	/** A search narrows the list to the layers whose name matches, under their file. */
	@Test
	fun aSearchNarrowsTheListByLayerName() =
		runComposeUiTest {
			val harness = mountSources()
			clickAt(sourcesSlotPoint(harness.text.sourcesRelink, SourcesNames.LOOSE_ART))

			typeIntoMenuSearch("sket")

			assertTrue(popupShows(SourcesNames.SKETCH))
			assertTrue(popupShows(SourcesNames.BODY))
			assertFalse(popupShows(SourcesNames.HAIR))
			assertFalse(popupShows(SourcesNames.FACE), "a file with no layer left is dropped")
			assertFalse(popupShows(harness.text.sourcesNoMatches))
		}

	/** A search matching a file's name keeps every layer of that file. */
	@Test
	fun aSearchMatchingAFileKeepsItsLayers() =
		runComposeUiTest {
			val harness = mountSources()
			clickAt(sourcesSlotPoint(harness.text.sourcesRelink, SourcesNames.LOOSE_ART))

			typeIntoMenuSearch("face")

			assertTrue(popupShows(SourcesNames.FACE))
			assertTrue(popupShows(SourcesNames.MOUTH))
			assertFalse(popupShows(SourcesNames.BODY))
		}

	/** A search matching nothing leaves one line saying so. */
	@Test
	fun aSearchMatchingNothingSaysSo() =
		runComposeUiTest {
			val harness = mountSources()
			clickAt(sourcesSlotPoint(harness.text.sourcesRelink, SourcesNames.LOOSE_ART))

			typeIntoMenuSearch("no such layer")

			assertTrue(popupShows(harness.text.sourcesNoMatches))
			assertFalse(popupShows(SourcesNames.BODY))
		}

	/** The search a chip was closed on is the search it opens on. */
	@Test
	fun theSearchSurvivesClosingTheChip() =
		runComposeUiTest {
			val harness = mountSources()
			clickAt(sourcesSlotPoint(harness.text.sourcesRelink, SourcesNames.LOOSE_ART))
			typeIntoMenuSearch("sket")
			pressOutsideTheMenu()
			assertFalse(popupShows(SourcesNames.SKETCH), "the list must really have closed")

			clickAt(sourcesSlotPoint(harness.text.sourcesRelink, SourcesNames.LOOSE_ART))

			assertTrue(menuSearchShows("sket"))
			assertFalse(popupShows(SourcesNames.HAIR))
		}

	/**
	 * The Sources rig with the Eye layer's tile carrying a stable binding, which its name key's shape denies.
	 *
	 * @return PuppetModel The rig.
	 */
	private fun modelWithEyeBoundAsStable() =
		sourcesFixtureModel().let { base ->
			base.copy(
				atlas =
					base.atlas.copy(
						tiles = base.atlas.tiles.map { tile -> if (tile.id == SourcesIds.eyeArt) tile.copy(source = tile.source?.copy(stableKey = true)) else tile },
					),
			)
		}

	/**
	 * The merge rig with the fresh tile over the proposed layer carrying a weak binding, which its key's
	 * shape denies.
	 *
	 * @return PuppetModel The rig.
	 */
	private fun mergeModelWithAWeakCandidate() =
		sourcesMergeModel().let { base ->
			base.copy(
				atlas =
					base.atlas.copy(
						tiles = base.atlas.tiles.map { tile -> if (tile.id == SourcesIds.browArtNew) tile.copy(source = tile.source?.copy(stableKey = false)) else tile },
					),
			)
		}

	/**
	 * The Sources rig with every unbound layer gone from the first file, so nothing can be proposed for the
	 * lost one.
	 *
	 * @return PuppetModel The rig.
	 */
	private fun modelWithNoCandidate() =
		sourcesFixtureModel().let { base ->
			val unbound = setOf(SourcesKeys.SKETCH, SourcesKeys.NOTES, SourcesKeys.BROW)
			base.copy(
				sources = base.sources.map { source -> if (source.id == SourcesIds.body) source.copy(layers = source.layers.filter { layer -> layer.key !in unbound }) else source },
			)
		}
}