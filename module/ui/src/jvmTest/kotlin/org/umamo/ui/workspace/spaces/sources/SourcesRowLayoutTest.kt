package org.umamo.ui.workspace.spaces.sources

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.kit.SCROLLBAR_THICKNESS
import org.umamo.ui.workspace.spaces.parameters.PANEL_SOURCES_HEIGHT_SCROLLING
import org.umamo.ui.workspace.spaces.parameters.clickAt
import org.umamo.ui.workspace.spaces.parameters.drag
import org.umamo.ui.workspace.spaces.parameters.popupShows
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins where a row's chip sits: at one edge on every row, however long the row's label is, and clear of
 * the scrollbar that overlays the list's right edge.  The label and its secondary text share one flexible
 * slot, and the chip follows it.
 */
@OptIn(ExperimentalTestApi::class)
class SourcesRowLayoutTest {
	/** The four kinds of chip end at one edge, far enough inside the table's that the scrollbar covers none. */
	@Test
	fun everyChipEndsAtTheSameEdge() =
		runComposeUiTest {
			val harness = mountSources()
			val table = sourcesBox()

			val edges =
				listOf(
					sourcesSlotBox(harness.text.sourcesFileMenu, SourcesNames.BODY).right,
					sourcesSlotBox(harness.text.sourcesLayerMenu, SourcesNames.SKETCH).right,
					sourcesSlotBox(harness.text.sourcesReview, SourcesNames.BROW_OLD).right,
					sourcesSlotBox(harness.text.sourcesRelink, SourcesNames.LOOSE_ART).right,
				)

			assertTrue(edges.all { edge -> abs(edge - edges.first()) < EDGE_TOLERANCE }, "one edge for every chip, got $edges")
			assertTrue(edges.first() < table.right, "inside the table")
			assertTrue(table.right - edges.first() < with(density) { CHIP_INSET_LIMIT.toPx() }, "and close to its edge")
			assertTrue(table.right - edges.first() >= with(density) { SCROLLBAR_THICKNESS.toPx() }, "clear of the scrollbar, got ${table.right - edges.first()}")
		}

	/** A list too tall for its box scrolls by the bar at its right edge. */
	@Test
	fun theScrollbarScrollsTheList() =
		runComposeUiTest {
			mountSources(height = PANEL_SOURCES_HEIGHT_SCROLLING)
			val table = sourcesBox()
			val barX = table.right - with(density) { SCROLLBAR_THICKNESS.toPx() / 2f }
			assertTrue(sourcesDisplays(SourcesNames.BODY), "the list starts at its top")
			assertTrue(!sourcesDisplays(SourcesNames.LOOSE_ART), "and is too tall for its box")

			drag(Offset(barX, table.top + BAR_GRAB_INSET), listOf(Offset(barX, table.top + table.height / 2f), Offset(barX, table.bottom)))

			assertTrue(sourcesDisplays(SourcesNames.LOOSE_ART), "the last row is in view")
			assertTrue(!sourcesDisplays(SourcesNames.BODY), "and the first has left it")
		}

	/** A chip on a scrolling list opens its menu: the bar beside it takes none of its presses. */
	@Test
	fun aChipOnAScrollingListStillOpens() =
		runComposeUiTest {
			val harness = mountSources(height = PANEL_SOURCES_HEIGHT_SCROLLING)
			val chip = sourcesSlotBox(harness.text.sourcesFileMenu, SourcesNames.BODY)

			clickAt(Offset(chip.right - 1f, chip.center.y))

			assertTrue(popupShows(harness.text.sourcesFileReplace), "a press on the chip's last pixel opens it")
		}

	/** A label too long for its row is cut short and leaves the chip where it was. */
	@Test
	fun aLongLabelLeavesTheChipInPlace() =
		runComposeUiTest {
			val harness = mountSources(model = modelWithALongLayerName())

			val shortRowChip = sourcesSlotBox(harness.text.sourcesLayerMenu, SourcesNames.NOTES)
			val longRowChip = sourcesSlotBox(harness.text.sourcesLayerMenu, LONG_NAME)

			assertTrue(abs(shortRowChip.right - longRowChip.right) < EDGE_TOLERANCE, "one edge, got ${shortRowChip.right} and ${longRowChip.right}")
			assertTrue(abs(shortRowChip.width - longRowChip.width) < EDGE_TOLERANCE, "and one width")
			assertTrue(sourcesBoundsOfText(LONG_NAME).right < longRowChip.left, "the label stops short of the chip")
		}

	/**
	 * The Sources rig with the Sketch layer under a name no row is wide enough for.
	 *
	 * @return PuppetModel The rig.
	 */
	private fun modelWithALongLayerName(): PuppetModel {
		val base = sourcesFixtureModel()

		return base.copy(
			sources =
				base.sources.map { source ->
					source.copy(layers = source.layers.map { layer -> if (layer.key == SourcesKeys.SKETCH) layer.copy(name = LONG_NAME) else layer })
				},
		)
	}

	private companion object {
		/** How far two edges may sit apart and still be the same edge, in pixels. */
		const val EDGE_TOLERANCE = 0.5f

		/** The widest gap a chip may keep from the table's right edge. */
		val CHIP_INSET_LIMIT = 16.dp

		/** How far under the table's top edge a press lands on the scrollbar's thumb, in pixels. */
		const val BAR_GRAB_INSET = 8f

		/** A layer name far wider than the table. */
		const val LONG_NAME = "A rough sketch of the whole figure that the artist kept in the file for reference and never meant for the rig"
	}
}