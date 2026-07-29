package org.umamo.ui.workspace.spaces

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

	private val trackRowKey = "glue:a:b/GLUE_INTENSITY"
	private val groupRowKey = "glue:a:b"

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
	 * A collapsed group's child keys are NOT selectable, even though their lane bounds are still on record.
	 *
	 * laneBounds is never pruned - a lane that leaves composition simply stops reporting - so a collapsed
	 * group's children keep their last rectangles forever.  Walking the tree blind kept hitting them, and a
	 * following Delete then edited tracks the user could not see.
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
}
