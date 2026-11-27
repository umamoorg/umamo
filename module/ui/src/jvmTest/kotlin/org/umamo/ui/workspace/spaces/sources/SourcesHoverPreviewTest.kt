package org.umamo.ui.workspace.spaces.sources

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.ui.workspace.spaces.keyformsheet.anyPopupOpen
import org.umamo.ui.workspace.spaces.outliner.StubThumbnails
import org.umamo.ui.workspace.spaces.parameters.popupShows
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins which rows pop an art preview under a rested pointer: a tile its source art, a layer the art of the
 * tile bound to it, a drawable its atlas crop, and every other row nothing.  Where the preview sits is the
 * shared popup's business, pinned with the Outliner's.
 */
@OptIn(ExperimentalTestApi::class)
class SourcesHoverPreviewTest {
	/** A tile row previews its source art, named as the row is. */
	@Test
	fun aTileRowPreviewsItsSourceArt() =
		runComposeUiTest {
			mountSources(sourceArt = sourcesFixtureArt())

			restAt(sourcesRowBox(SourcesNames.LOOSE_ART).center)

			assertTrue(popupShows(SourcesNames.LOOSE_ART))
		}

	/** A layer some tile is bound to previews that tile's art, and a layer under review still does. */
	@Test
	fun aBoundLayerRowPreviewsItsTilesArt() =
		runComposeUiTest {
			mountSources(sourceArt = sourcesFixtureArt())

			restAt(sourcesRowBox(SourcesNames.HAIR).center)
			assertTrue(popupShows(SourcesNames.HAIR))

			restAt(sourcesRowBox(SourcesNames.BROW_OLD).center)
			assertTrue(popupShows(SourcesNames.BROW_OLD), "the review is about this art")
			assertFalse(popupShows(SourcesNames.HAIR), "one preview for the whole space")
		}

	/** A drawable row previews its crop of the atlas, from the provider the outliner uses. */
	@Test
	fun aDrawableRowPreviewsItsAtlasCrop() =
		runComposeUiTest {
			val harness = mountSources(thumbnails = StubThumbnails)
			openRow(harness, SourcesRowKeys.HAIR)
			openRow(harness, SourcesRowKeys.HAIR_ART)

			restAt(sourcesRowBox(SourcesNames.HAIR_SHADOW).center)

			assertTrue(popupShows(SourcesNames.HAIR_SHADOW))
		}

	/** A file, the unbound group, and a layer no tile binds are no single piece of art, and preview nothing. */
	@Test
	fun rowsWithNoArtOfTheirOwnPreviewNothing() =
		runComposeUiTest {
			val harness = mountSources(thumbnails = StubThumbnails, sourceArt = sourcesFixtureArt())

			restAt(sourcesRowBox(SourcesNames.BODY).center)
			assertFalse(anyPopupOpen())
			restAt(sourcesRowBox(harness.text.sourcesUnboundArt).center)
			assertFalse(anyPopupOpen())
			restAt(sourcesRowBox(SourcesNames.SKETCH).center)
			assertFalse(anyPopupOpen())
		}

	/** Leaving the row withdraws its preview. */
	@Test
	fun leavingTheRowWithdrawsThePreview() =
		runComposeUiTest {
			mountSources(sourceArt = sourcesFixtureArt())
			restAt(sourcesRowBox(SourcesNames.LOOSE_ART).center)
			assertTrue(popupShows(SourcesNames.LOOSE_ART), "the preview must really have popped")

			restAt(sourcesRowBox(SourcesNames.BODY).center)

			assertFalse(anyPopupOpen())
		}

	/** A document that holds no source art previews no tile and no layer. */
	@Test
	fun withoutSourceArtNoTilePreviews() =
		runComposeUiTest {
			mountSources(thumbnails = StubThumbnails)

			restAt(sourcesRowBox(SourcesNames.LOOSE_ART).center)
			assertFalse(anyPopupOpen())
			restAt(sourcesRowBox(SourcesNames.HAIR).center)
			assertFalse(anyPopupOpen())
		}

	/** With neither provider nothing previews. */
	@Test
	fun withNoProviderNothingPreviews() =
		runComposeUiTest {
			val harness = mountSources()
			openRow(harness, SourcesRowKeys.HAIR)
			openRow(harness, SourcesRowKeys.HAIR_ART)

			restAt(sourcesRowBox(SourcesNames.HAIR_ART).center)
			assertFalse(anyPopupOpen())
			restAt(sourcesRowBox(SourcesNames.HAIR_SHADOW).center)
			assertFalse(anyPopupOpen())
		}
}