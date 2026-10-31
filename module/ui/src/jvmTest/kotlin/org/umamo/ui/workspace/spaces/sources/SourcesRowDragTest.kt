package org.umamo.ui.workspace.spaces.sources

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.runtime.model.SourceLayerRef
import org.umamo.ui.workspace.spaces.keyformsheet.anyPopupOpen
import org.umamo.ui.workspace.spaces.outliner.longPressAndMove
import org.umamo.ui.workspace.spaces.parameters.moveOn
import org.umamo.ui.workspace.spaces.parameters.popupShows
import org.umamo.ui.workspace.spaces.parameters.popupTextInWindow
import org.umamo.ui.workspace.spaces.parameters.pressKey
import org.umamo.ui.workspace.spaces.parameters.releasePress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins what a dragged row does: a layer dropped on a tile and a tile dropped on a layer each rebind the
 * tile, every other pairing drops nothing, and a row that stands for no layer a tile could take neither
 * lifts nor takes a drop.
 *
 * The rig's files list no key twice, so the row a drop opens here is the row the pointer was on.  Which
 * row a drop opens when a file repeats a key is pinned without a composition, in SourcesDropTest.
 */
@OptIn(ExperimentalTestApi::class)
class SourcesRowDragTest {
	/**
	 * The path of a drag: a first small move that starts it, then the target.
	 *
	 * @param Offset from Where the press landed.
	 * @param Offset target Where the drag ends.
	 * @return List<Offset> The points to move through.
	 */
	private fun pathTo(from: Offset, target: Offset): List<Offset> = listOf(Offset(from.x + 4f, from.y + 6f), target)

	/** A layer row dropped on a tile row binds the tile to the layer. */
	@Test
	fun aLayerDroppedOnATileBindsTheTile() =
		runComposeUiTest {
			val harness = mountSources()
			val sketch = sourcesRowBox(SourcesNames.SKETCH).center

			longPressAndMove(sketch, pathTo(sketch, sourcesRowBox(SourcesNames.LOOSE_ART).center))
			releasePress()

			val request = harness.artworkRequests.relinks.single()
			assertEquals(listOf(SourcesIds.looseArt), request.tileIds)
			assertEquals(SourceLayerRef(SourcesIds.body, SourcesKeys.SKETCH, stableKey = true), request.ref)
			assertTrue(request.retire.isEmpty())
		}

	/** A tile row dropped on a layer row binds the tile to the layer. */
	@Test
	fun aTileDroppedOnALayerBindsTheTile() =
		runComposeUiTest {
			val harness = mountSources()
			val loose = sourcesRowBox(SourcesNames.LOOSE_ART).center

			longPressAndMove(loose, pathTo(loose, sourcesRowBox(SourcesNames.SKETCH).center))
			releasePress()

			val request = harness.artworkRequests.relinks.single()
			assertEquals(listOf(SourcesIds.looseArt), request.tileIds)
			assertEquals(SourceLayerRef(SourcesIds.body, SourcesKeys.SKETCH, stableKey = true), request.ref)
			assertEquals(SourcesKeys.SKETCH, tileOf(harness, SourcesIds.looseArt)?.source?.layerKey, "the binding landed")
		}

	/** A tile dropped on a closed layer opens it, so the tile is seen to land. */
	@Test
	fun aTileDropOpensTheLayerItLandsIn() =
		runComposeUiTest {
			val harness = mountSources()
			val loose = sourcesRowBox(SourcesNames.LOOSE_ART).center
			assertFalse(harness.sourcesViewState.isOpen(SourcesRowKeys.SKETCH), "a layer starts closed")

			longPressAndMove(loose, pathTo(loose, sourcesRowBox(SourcesNames.SKETCH).center))
			releasePress()

			assertTrue(harness.sourcesViewState.isOpen(SourcesRowKeys.SKETCH))
			assertTrue(sourcesDisplays(SourcesNames.LOOSE_ART), "the tile shows under the layer it was bound to")
			assertTrue(sourcesRowBox(SourcesNames.LOOSE_ART).top > sourcesRowBox(SourcesNames.SKETCH).top)
			assertTrue(sourcesRowBox(SourcesNames.LOOSE_ART).top < sourcesRowBox(SourcesNames.NOTES).top, "between its layer and the next")
		}

	/** A layer dropped on a tile opens the layer that was dragged, which is where the tile lands. */
	@Test
	fun aLayerDropOpensTheLayerThatWasDragged() =
		runComposeUiTest {
			val harness = mountSources()
			val sketch = sourcesRowBox(SourcesNames.SKETCH).center

			longPressAndMove(sketch, pathTo(sketch, sourcesRowBox(SourcesNames.LOOSE_ART).center))
			releasePress()

			assertTrue(harness.sourcesViewState.isOpen(SourcesRowKeys.SKETCH))
			assertTrue(sourcesRowBox(SourcesNames.LOOSE_ART).top < sourcesRowBox(SourcesNames.NOTES).top, "the tile shows under the layer it was bound to")
		}

	/** A drop that binds nothing opens nothing. */
	@Test
	fun aDropThatBindsNothingOpensNothing() =
		runComposeUiTest {
			val harness = mountSources()
			val loose = sourcesRowBox(SourcesNames.LOOSE_ART).center

			longPressAndMove(loose, pathTo(loose, sourcesRowBox(SourcesNames.BROW_OLD).center))
			releasePress()

			assertTrue(harness.sourcesViewState.expanded.isEmpty())
		}

	/**
	 * A dragged layer carries the binding its tiles carry: a key its reader marked weak lands weak on the
	 * tile it is dropped on, though its shape reads stable.
	 */
	@Test
	fun aDraggedLayerCarriesItsTilesWordForItsKey() =
		runComposeUiTest {
			val harness = mountSources(model = modelWithHairBoundAsWeak())
			val hair = sourcesRowBox(SourcesNames.HAIR).center

			longPressAndMove(hair, pathTo(hair, sourcesRowBox(SourcesNames.LOOSE_ART).center))
			releasePress()

			assertEquals(SourceLayerRef(SourcesIds.body, SourcesKeys.HAIR, stableKey = false), harness.artworkRequests.relinks.single().ref)
		}

	/** A tile dropped on a tile rebinds nothing. */
	@Test
	fun aTileDroppedOnATileDropsNothing() =
		runComposeUiTest {
			val harness = mountSources()
			openRow(harness, SourcesRowKeys.HAIR)
			val loose = sourcesRowBox(SourcesNames.LOOSE_ART).center

			longPressAndMove(loose, pathTo(loose, sourcesRowBox(SourcesNames.HAIR_ART).center))
			assertTrue(popupShows(SourcesNames.LOOSE_ART), "the drag must really be in flight")
			releasePress()

			assertTrue(harness.artworkRequests.relinks.isEmpty())
		}

	/** A layer dropped on a file rebinds nothing. */
	@Test
	fun aLayerDroppedOnAFileDropsNothing() =
		runComposeUiTest {
			val harness = mountSources()
			val sketch = sourcesRowBox(SourcesNames.SKETCH).center

			longPressAndMove(sketch, pathTo(sketch, sourcesRowBox(SourcesNames.FACE).center))
			assertTrue(popupShows(SourcesNames.SKETCH), "the drag must really be in flight")
			releasePress()

			assertTrue(harness.artworkRequests.relinks.isEmpty())
		}

	/** A tile dropped on a row under review, or on an ignored one, rebinds nothing: neither offers a layer. */
	@Test
	fun aTileDroppedOnAReviewOrAnIgnoredRowDropsNothing() =
		runComposeUiTest {
			val harness = mountSources()
			val loose = sourcesRowBox(SourcesNames.LOOSE_ART).center

			longPressAndMove(loose, pathTo(loose, sourcesRowBox(SourcesNames.BROW_OLD).center))
			releasePress()
			longPressAndMove(loose, pathTo(loose, sourcesRowBox(SourcesNames.NOTES).center))
			releasePress()

			assertTrue(harness.artworkRequests.relinks.isEmpty())
		}

	/** A row under review does not lift: its binding is what is under review, not a layer to offer. */
	@Test
	fun aReviewRowDoesNotLift() =
		runComposeUiTest {
			val harness = mountSources()
			val lost = sourcesRowBox(SourcesNames.BROW_OLD).center

			longPressAndMove(lost, pathTo(lost, sourcesRowBox(SourcesNames.LOOSE_ART).center))
			assertFalse(popupShows(SourcesNames.BROW_OLD), "no chip follows the pointer")
			releasePress()

			assertTrue(harness.artworkRequests.relinks.isEmpty())
		}

	/** An ignored row does not lift: it stays out of the rig until its row says otherwise. */
	@Test
	fun anIgnoredRowDoesNotLift() =
		runComposeUiTest {
			val harness = mountSources()
			val notes = sourcesRowBox(SourcesNames.NOTES).center

			longPressAndMove(notes, pathTo(notes, sourcesRowBox(SourcesNames.LOOSE_ART).center))
			assertFalse(popupShows(SourcesNames.NOTES))
			releasePress()

			assertTrue(harness.artworkRequests.relinks.isEmpty())
		}

	/** A file row does not lift. */
	@Test
	fun aFileRowDoesNotLift() =
		runComposeUiTest {
			val harness = mountSources()
			val face = sourcesRowBox(SourcesNames.FACE).center

			longPressAndMove(face, pathTo(face, sourcesRowBox(SourcesNames.LOOSE_ART).center))
			assertFalse(popupShows(SourcesNames.FACE))
			releasePress()

			assertTrue(harness.artworkRequests.relinks.isEmpty())
		}

	/** The chip at the cursor names the row in hand. */
	@Test
	fun theDragChipNamesTheRow() =
		runComposeUiTest {
			mountSources()
			val loose = sourcesRowBox(SourcesNames.LOOSE_ART).center

			longPressAndMove(loose, pathTo(loose, sourcesRowBox(SourcesNames.SKETCH).center))

			assertTrue(popupShows(SourcesNames.LOOSE_ART))
			releasePress()
			assertFalse(anyPopupOpen(), "the chip goes with the drop")
		}

	/** The chip moves as far as the pointer does, and the same way. */
	@Test
	fun theDragChipFollowsThePointer() =
		runComposeUiTest {
			mountSources()
			val loose = sourcesRowBox(SourcesNames.LOOSE_ART).center
			val first = sourcesRowBox(SourcesNames.SKETCH).center
			val second = Offset(first.x + 37f, sourcesRowBox(SourcesNames.HAIR).center.y)

			longPressAndMove(loose, pathTo(loose, first))
			val chipAtFirst = popupTextInWindow(SourcesNames.LOOSE_ART)
			moveOn(listOf(second))
			val chipAtSecond = popupTextInWindow(SourcesNames.LOOSE_ART)

			assertEquals(second.x - first.x, chipAtSecond.left - chipAtFirst.left, CHIP_TOLERANCE)
			assertEquals(second.y - first.y, chipAtSecond.top - chipAtFirst.top, CHIP_TOLERANCE)
			pressKey(Key.Escape)
			releasePress()
		}

	/** Escape aborts a drag in flight: the chip goes, and the release then drops nothing. */
	@Test
	fun escapeCancelsADragInFlight() =
		runComposeUiTest {
			val harness = mountSources()
			val loose = sourcesRowBox(SourcesNames.LOOSE_ART).center
			longPressAndMove(loose, pathTo(loose, sourcesRowBox(SourcesNames.SKETCH).center))
			assertTrue(popupShows(SourcesNames.LOOSE_ART), "the drag must really be in flight")

			pressKey(Key.Escape)
			assertFalse(anyPopupOpen(), "the chip goes with the drag")
			releasePress()

			assertTrue(harness.artworkRequests.relinks.isEmpty())
		}

	/**
	 * The Sources rig with the Hair layer's tile carrying a weak binding, which its key's shape denies.
	 *
	 * @return PuppetModel The rig.
	 */
	private fun modelWithHairBoundAsWeak() =
		sourcesFixtureModel().let { base ->
			base.copy(
				atlas =
					base.atlas.copy(
						tiles = base.atlas.tiles.map { tile -> if (tile.id == SourcesIds.hairArt) tile.copy(source = tile.source?.copy(stableKey = false)) else tile },
					),
			)
		}

	/** A drag selects nothing: the row's click lands on a release, and the drag takes the release. */
	@Test
	fun aDragSelectsNothing() =
		runComposeUiTest {
			val harness = mountSources()
			val loose = sourcesRowBox(SourcesNames.LOOSE_ART).center

			longPressAndMove(loose, pathTo(loose, sourcesRowBox(SourcesNames.SKETCH).center))
			releasePress()

			assertTrue(harness.selectedDrawables.isEmpty())
		}

	private companion object {
		/** The chip lands on whole pixels, so it may sit up to one off the pointer's own move. */
		const val CHIP_TOLERANCE = 1f
	}
}