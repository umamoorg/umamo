package org.umamo.ui.workspace.spaces.sources

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.ui.workspace.spaces.parameters.clickAt
import org.umamo.ui.workspace.spaces.parameters.panelFixtureModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins what a press on a row does: which drawables a click on each kind of row selects, where a click
 * folds instead, and what a chevron and a name search do to the rows the table shows.
 */
@OptIn(ExperimentalTestApi::class)
class SourcesGestureTest {
	/** A click on a drawable row selects that drawable. */
	@Test
	fun aClickOnADrawableRowSelectsIt() =
		runComposeUiTest {
			val harness = mountSources()
			openRow(harness, SourcesRowKeys.HAIR)
			openRow(harness, SourcesRowKeys.HAIR_ART)

			clickAt(sourcesRowBox(SourcesNames.HAIR_SHADOW).center)

			assertEquals(setOf(SourcesIds.hairShadow), harness.selectedDrawables)
		}

	/** A click on a tile row selects every drawable sampling the tile. */
	@Test
	fun aClickOnATileRowSelectsEveryDrawableOverIt() =
		runComposeUiTest {
			val harness = mountSources()
			openRow(harness, SourcesRowKeys.HAIR)

			clickAt(sourcesRowBox(SourcesNames.HAIR_ART).center)

			assertEquals(setOf(SourcesIds.hairMesh, SourcesIds.hairShadow), harness.selectedDrawables)
		}

	/** A click on a layer row selects every drawable over every tile bound to the layer, open or not. */
	@Test
	fun aClickOnALayerRowSelectsEveryDrawableOverItsTiles() =
		runComposeUiTest {
			val harness = mountSources()

			clickAt(sourcesRowBox(SourcesNames.BROW_OLD).center)

			assertEquals(setOf(SourcesIds.browMeshLeft, SourcesIds.browMeshRight), harness.selectedDrawables)
			assertFalse(sourcesShows(SourcesNames.BROW_ART_LEFT), "selecting opens nothing")
		}

	/** Under a name search a layer row still selects every drawable over every tile bound to it, listed or not. */
	@Test
	fun aLayerClickUnderASearchSelectsEveryDrawableOverItsTiles() =
		runComposeUiTest {
			val harness = mountSources()
			runOnIdle { harness.sourcesViewState.query = SourcesNames.BROW_ART_LEFT }
			waitForIdle()
			assertFalse(sourcesShows(SourcesNames.BROW_ART_RIGHT), "the search must really have left one tile out")

			clickAt(sourcesRowBox(SourcesNames.BROW_OLD).center)

			assertEquals(setOf(SourcesIds.browMeshLeft, SourcesIds.browMeshRight), harness.selectedDrawables)
		}

	/** A file row has nothing to select, so a click folds it and a second opens it again. */
	@Test
	fun aFileRowClickFoldsAndASecondOpens() =
		runComposeUiTest {
			val harness = mountSources()
			assertTrue(sourcesShows(SourcesNames.HAIR), "a file starts open")

			clickAt(sourcesRowBox(SourcesNames.BODY).center)
			assertFalse(sourcesShows(SourcesNames.HAIR))
			assertTrue(sourcesShows(SourcesNames.MOUTH), "the other file keeps its rows")

			clickAt(sourcesRowBox(SourcesNames.BODY).center)
			assertTrue(sourcesShows(SourcesNames.HAIR))
			assertTrue(harness.selectedDrawables.isEmpty())
		}

	/** A chevron press opens and closes its row and selects nothing, though the row itself would. */
	@Test
	fun aChevronPressFoldsAndSelectsNothing() =
		runComposeUiTest {
			val harness = mountSources()
			assertFalse(sourcesShows(SourcesNames.HAIR_ART), "a layer starts closed")

			clickAt(sourcesSlotPoint(harness.text.expand, SourcesNames.HAIR))
			assertTrue(sourcesShows(SourcesNames.HAIR_ART))
			assertTrue(harness.sourcesViewState.isOpen(SourcesRowKeys.HAIR))

			clickAt(sourcesSlotPoint(harness.text.collapse, SourcesNames.HAIR))
			assertFalse(sourcesShows(SourcesNames.HAIR_ART))
			assertTrue(harness.selectedDrawables.isEmpty())
		}

	/**
	 * During a search every row shows open, so a press has nothing to fold: it writes no fold, and the row
	 * is as it was once the search is gone.
	 */
	@Test
	fun aPressDuringASearchFoldsNothing() =
		runComposeUiTest {
			val harness = mountSources()
			runOnIdle { harness.sourcesViewState.query = SourcesNames.HAIR_SHADOW }
			waitForIdle()

			clickAt(sourcesRowBox(SourcesNames.BODY).center)
			clickAt(sourcesSlotPoint(harness.text.collapse, SourcesNames.HAIR_ART))

			assertTrue(sourcesShows(SourcesNames.HAIR_SHADOW), "the search still lists its match")
			assertTrue(harness.sourcesViewState.expanded.isEmpty(), "no fold was written")
			runOnIdle { harness.sourcesViewState.query = "" }
			waitForIdle()
			assertTrue(sourcesShows(SourcesNames.HAIR), "the file is open, as it was before the search")
			assertFalse(sourcesShows(SourcesNames.HAIR_ART), "and the layer closed, as it was")
		}

	/** A row with nothing under it and nothing to select has nothing to fold either, and its click writes no fold. */
	@Test
	fun aClickOnARowWithNothingUnderItWritesNoFold() =
		runComposeUiTest {
			val harness = mountSources()

			clickAt(sourcesRowBox(SourcesNames.SKETCH).center)
			clickAt(sourcesRowBox(SourcesNames.NOTES).center)

			assertTrue(harness.sourcesViewState.expanded.isEmpty())
			assertTrue(harness.selectedDrawables.isEmpty())
		}

	/** A row with nothing under it carries no chevron. */
	@Test
	fun aRowWithNothingUnderItHasNoChevron() =
		runComposeUiTest {
			val harness = mountSources()

			assertFalse(sourcesRowHasSlot(harness.text.expand, SourcesNames.SKETCH))
			assertTrue(sourcesRowHasSlot(harness.text.expand, SourcesNames.HAIR), "the lookup must really find a chevron where there is one")
		}

	/** A name search lists the matching rows under their ancestors, every branch open, and nothing else. */
	@Test
	fun aNameSearchOpensEveryBranchOverItsMatches() =
		runComposeUiTest {
			val harness = mountSources()

			runOnIdle { harness.sourcesViewState.query = SourcesNames.HAIR_SHADOW }
			waitForIdle()

			assertTrue(sourcesShows(SourcesNames.HAIR_SHADOW))
			assertTrue(sourcesShows(SourcesNames.HAIR_ART), "the tile over the match")
			assertTrue(sourcesShows(SourcesNames.HAIR), "the layer over the tile")
			assertTrue(sourcesShows(SourcesNames.BODY), "the file over the layer")
			assertFalse(sourcesShows(SourcesNames.HAIR_MESH), "a sibling that does not match")
			assertFalse(sourcesShows(SourcesNames.SKETCH))
			assertFalse(sourcesShows(SourcesNames.FACE))
		}

	/** Clearing the search returns every branch to the fold it had. */
	@Test
	fun clearingTheSearchReturnsTheFolds() =
		runComposeUiTest {
			val harness = mountSources()
			runOnIdle { harness.sourcesViewState.query = SourcesNames.HAIR_SHADOW }
			waitForIdle()

			runOnIdle { harness.sourcesViewState.query = "" }
			waitForIdle()

			assertFalse(sourcesShows(SourcesNames.HAIR_ART), "the layer is closed again")
			assertTrue(sourcesShows(SourcesNames.SKETCH))
		}

	/** A filter lists the rows of its kind under their ancestors. */
	@Test
	fun aFilterListsTheRowsOfItsKind() =
		runComposeUiTest {
			val harness = mountSources()

			runOnIdle { harness.sourcesViewState.filters = setOf(SourcesFilter.NeedsReview) }
			waitForIdle()

			assertTrue(sourcesShows(SourcesNames.BROW_OLD))
			assertTrue(sourcesShows(SourcesNames.BODY))
			assertFalse(sourcesShows(SourcesNames.HAIR))
			assertFalse(sourcesShows(SourcesNames.FACE))
		}

	/** With no document open the space lists nothing. */
	@Test
	fun noDocumentListsNothing() =
		runComposeUiTest {
			mountSources(provideDocument = false)

			assertFalse(sourcesShows(SourcesNames.BODY))
		}

	/** A document that holds no artwork and no art lists nothing. */
	@Test
	fun aDocumentWithNoArtworkListsNothing() =
		runComposeUiTest {
			val harness = mountSources(model = panelFixtureModel())

			assertFalse(sourcesShows(harness.text.sourcesUnboundArt))
			assertFalse(sourcesShows(SourcesNames.BODY))
		}

	/** The table opens on the rows the fixture says it does, top to bottom. */
	@Test
	fun theTableOpensOnTheRigsRows() =
		runComposeUiTest {
			val harness = mountSources()

			val shown =
				listOf(
					SourcesNames.BODY,
					SourcesNames.HAIR,
					SourcesNames.EYE,
					SourcesNames.SKETCH,
					SourcesNames.NOTES,
					SourcesNames.BROW,
					SourcesNames.BROW_OLD,
					SourcesNames.FACE,
					SourcesNames.MOUTH,
					harness.text.sourcesUnboundArt,
					SourcesNames.LOOSE_ART,
				)
			assertEquals(SOURCES_OPEN_ROWS, shown.size)
			assertTrue(shown.all { label -> sourcesShows(label) }, "every row of the open rig")
			assertEquals(shown, shown.sortedBy { label -> sourcesBoundsOfText(label).top }, "in the table's order")
		}
}