package org.umamo.ui.workspace.spaces.keyformsheet

import androidx.compose.ui.geometry.Rect
import org.umamo.edit.TrackKeyRef
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.Glue
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.tracks.TrackWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The box-select marquee's region -> keys resolution.
 *
 * Worth its own suite because the mapping has to agree with what is DRAWN, not with the model: a marquee
 * that reads the full parameter domain while the lanes draw a zoomed window selects the wrong keys, and one
 * that walks the whole row tree selects keys inside groups the user has folded away.  Neither is visible
 * from outside the function, and neither shows up as a crash.
 */
class KeyformSheetMarqueeTest {
	private val angleX = ParameterId("ParamAngleX")
	private val parameter = Parameter(angleX, "ParamAngleX", min = -10f, max = 10f, default = 0f)

	/** Chrome-free labels: the projection is Compose-free, so tests inject plain strings. */
	private fun labels(): KeyformTrackLabels =
		KeyformTrackLabels(
			channelName = { channel -> channel.name },
			geometry = "Geometry",
			blendShape = "Blend Shape",
			ownerKindName = { kind -> kind.name },
		)

	/** A glue with one intensity track keyed at -10, 0 and 10 - three marks spread across the domain. */
	private fun model(): PuppetModel =
		PuppetModel(
			parameters = listOf(parameter),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = emptyList(),
			rootChildren = emptyList(),
			rootPartId = null,
			glues =
				listOf(
					Glue(
						DrawableId("a"),
						DrawableId("b"),
						emptyList(),
						ChannelGrids(
							mapOf(
								FormChannel.GLUE_INTENSITY to
									KeyformGrid(
										listOf(KeyformAxis(angleX, floatArrayOf(-10f, 0f, 10f))),
										listOf<KeyformCell<ChannelValue>>(
											KeyformCell(intArrayOf(0), ChannelValue.Scalar(0f)),
											KeyformCell(intArrayOf(1), ChannelValue.Scalar(0.5f)),
											KeyformCell(intArrayOf(2), ChannelValue.Scalar(1f)),
										),
									),
							),
						),
					),
				),
		)

	/**
	 * A grid keyed on [angleX] at [keys], every cell at 0.
	 *
	 * @param FloatArray keys The key positions.
	 * @return KeyformGrid The grid.
	 */
	private fun scalarGrid(vararg keys: Float): KeyformGrid<ChannelValue> =
		KeyformGrid(
			listOf(KeyformAxis(angleX, keys)),
			keys.indices.map { keyIndex -> KeyformCell<ChannelValue>(intArrayOf(keyIndex), ChannelValue.Scalar(0f)) },
		)

	/** A part keying opacity at -10, 0 and 10 and draw order at 0 and 10, so two of its summary marks stand for two keys. */
	private fun twoTrackModel(): PuppetModel =
		PuppetModel(
			parameters = listOf(parameter),
			parts =
				listOf(
					Part(
						PartId("p"),
						"Part",
						emptyList(),
						channelGrids =
							ChannelGrids(
								mapOf(
									FormChannel.OPACITY to scalarGrid(-10f, 0f, 10f),
									FormChannel.DRAW_ORDER to scalarGrid(0f, 10f),
								),
							),
					),
				),
			deformers = emptyList(),
			drawables = emptyList(),
			rootChildren = emptyList(),
			rootPartId = null,
		)

	private val trackRowKey = "glue:a:b/GLUE_INTENSITY"
	private val groupRowKey = "glue:a:b"
	private val partRowKey = "part:p"

	/** A 100px-wide lane at the window origin, the shape the marquee resolves against. */
	private val laneRect = Rect(0f, 0f, 100f, 20f)

	/**
	 * A zoomed sheet resolves marks against the WINDOW, not the full range.
	 *
	 * The lanes draw `window.axisOver(fullAxis)`, so reading the full domain here put every mark at the x
	 * it would have had unzoomed - selecting a different key, or none.
	 */
	@Test
	fun aZoomedSheetResolvesAgainstTheVisibleWindow() {
		val projection = keyformSheetRows(model(), angleX, labels())
		val bounds = mapOf(trackRowKey to laneRect)
		// The right half of -10..10, so the visible domain is 0..10 and the key at 0 sits at the far LEFT.
		val zoomed = TrackWindow(start = 0.5f, end = 1f)
		val leftEdge = Rect(0f, 0f, 20f, 20f)

		val enclosed =
			keysWithin(leftEdge, listOf(parameter to projection), bounds, markRadiusPx = 4f, window = zoomed, expandedKeys = setOf(groupRowKey), collapsedParameters = emptySet())
		assertEquals(setOf(TrackKeyRef(angleX, trackRowKey, 1)), enclosed, "the key at 0 is what the left edge shows when zoomed right")

		val unzoomed =
			keysWithin(leftEdge, listOf(parameter to projection), bounds, markRadiusPx = 4f, window = TrackWindow.Full, expandedKeys = setOf(groupRowKey), collapsedParameters = emptySet())
		assertEquals(setOf(TrackKeyRef(angleX, trackRowKey, 0)), unzoomed, "unzoomed the same band shows the key at -10")
	}

	/**
	 * A collapsed group's child lanes are NOT selectable, even though their bounds are still on record.
	 *
	 * laneBounds is never pruned - a lane that leaves composition simply stops reporting - so a collapsed
	 * group's children keep their last rectangles forever.  Their keys are reachable only through the
	 * summary the group row draws, where the user can see them.
	 */
	@Test
	fun collapsedRowsAreNotSelectable() {
		val projection = keyformSheetRows(model(), angleX, labels())
		val bounds = mapOf(trackRowKey to laneRect)
		val wholeLane = Rect(0f, 0f, 100f, 20f)

		val expanded =
			keysWithin(wholeLane, listOf(parameter to projection), bounds, markRadiusPx = 4f, window = TrackWindow.Full, expandedKeys = setOf(groupRowKey), collapsedParameters = emptySet())
		assertTrue(expanded.isNotEmpty(), "expanded, the track's keys are in reach")

		val collapsed =
			keysWithin(wholeLane, listOf(parameter to projection), bounds, markRadiusPx = 4f, window = TrackWindow.Full, expandedKeys = emptySet(), collapsedParameters = emptySet())
		assertTrue(collapsed.isEmpty(), "collapsed, its stale lane bounds must not put hidden keys in the selection")

		val foldedSection =
			keysWithin(wholeLane, listOf(parameter to projection), bounds, markRadiusPx = 4f, window = TrackWindow.Full, expandedKeys = setOf(groupRowKey), collapsedParameters = setOf(angleX))
		assertTrue(foldedSection.isEmpty(), "a folded section is not on screen at all")
	}

	/** A box over a collapsed group's summary mark selects every key stacked under it, the way a click on it does. */
	@Test
	fun aCollapsedGroupsSummarySelectsItsWholeStack() {
		val projection = keyformSheetRows(twoTrackModel(), angleX, labels())
		val bounds = mapOf(partRowKey to laneRect)
		// The middle of the lane, where the summary mark at 0 is drawn.
		val middle = Rect(40f, 0f, 60f, 20f)

		val enclosed =
			keysWithin(middle, listOf(parameter to projection), bounds, markRadiusPx = 4f, window = TrackWindow.Full, expandedKeys = emptySet(), collapsedParameters = emptySet())
		assertEquals(
			setOf(TrackKeyRef(angleX, "$partRowKey/OPACITY", 1), TrackKeyRef(angleX, "$partRowKey/DRAW_ORDER", 0)),
			enclosed,
			"the summary at 0 stands for the opacity and draw order keys there",
		)
		assertEquals(
			projection.keysOfMark(angleX, partRowKey, 1).toSet(),
			enclosed,
			"the box selects what a click on the same summary mark does",
		)

		val wholeLane =
			keysWithin(laneRect, listOf(parameter to projection), bounds, markRadiusPx = 4f, window = TrackWindow.Full, expandedKeys = emptySet(), collapsedParameters = emptySet())
		assertEquals(5, wholeLane.size, "every summary across the lane, each with its whole stack")
	}

	/** An expanded group row draws no summary, so a box over it selects nothing; its keys are on the child lanes. */
	@Test
	fun anExpandedGroupRowHasNoSummaryToSelect() {
		val projection = keyformSheetRows(twoTrackModel(), angleX, labels())
		val bounds = mapOf(partRowKey to laneRect)

		val enclosed =
			keysWithin(laneRect, listOf(parameter to projection), bounds, markRadiusPx = 4f, window = TrackWindow.Full, expandedKeys = setOf(partRowKey), collapsedParameters = emptySet())
		assertTrue(enclosed.isEmpty(), "the open group row shows no marks of its own")
	}
}