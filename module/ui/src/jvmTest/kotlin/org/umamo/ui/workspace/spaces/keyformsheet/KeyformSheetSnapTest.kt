package org.umamo.ui.workspace.spaces.keyformsheet

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.runtime.model.ParameterId
import org.umamo.ui.kit.field.KEY_SNAP_RADIUS
import org.umamo.ui.tracks.TRACK_MARK_RADIUS
import org.umamo.ui.tracks.TrackAxis
import org.umamo.ui.workspace.spaces.parameters.PanelIds
import org.umamo.ui.workspace.spaces.parameters.ParametersPanelHarness
import org.umamo.ui.workspace.spaces.parameters.clickAt
import org.umamo.ui.workspace.spaces.parameters.drag
import org.umamo.ui.workspace.spaces.parameters.setCtrlHeld
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The sheet's held-Ctrl snap, end to end: a scrub latches onto the parameter's keys, and a key drag onto
 * another row's key or the playhead, but never onto a key its own track keeps.
 *
 * Body X, -10 to 10: geometry keys at -5, 0, and 5; opacity at 0, 5, and 8; the part's opacity at -8 and 8.
 * Every approach stops 0.4 of the snap reach short of its target, so it is in reach whatever the lane's width.
 */
@OptIn(ExperimentalTestApi::class)
class KeyformSheetSnapTest {
	/**
	 * The snap reach on the row keyed [rowKey], in [parameterId]'s units: the snap radius over the lane's
	 * travel band, the same mapping the lane draws with.
	 *
	 * @param ParametersPanelHarness harness The mounted harness.
	 * @param String rowKey The row.
	 * @param ParameterId parameterId The parameter of the row's section.
	 * @return Float The reach.
	 */
	private fun ComposeUiTest.snapReach(harness: ParametersPanelHarness, rowKey: String, parameterId: ParameterId): Float {
		val lane = laneBox(harness, rowKey)
		val parameter = harness.session.model.value.parameters.first { candidate -> candidate.id == parameterId }
		val (domainStart, domainEnd) = parameterDomain(parameter)
		val axis = harness.sheetViewState.window.axisOver(TrackAxis(domainStart, domainEnd))
		val usable = lane.width - 2f * with(density) { TRACK_MARK_RADIUS.toPx() }

		return abs(axis.span) * with(density) { KEY_SNAP_RADIUS.toPx() } / usable
	}

	/** A Ctrl scrub on one row latches onto a key on another: the playhead lands on the opacity key at 8. */
	@Test
	fun aCtrlScrubLatchesOntoAKey() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.bodyX))
			val near = 8f - 0.4f * snapReach(harness, SheetRows.GEOMETRY, PanelIds.bodyX)

			setCtrlHeld(true)
			drag(
				lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, -2.5f),
				listOf(lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, 6.5f), lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, near)),
			)
			setCtrlHeld(false)

			assertEquals(8f, harness.committed(PanelIds.bodyX))
		}

	/**
	 * The same scrub without Ctrl lands where the pointer let go: nearer the pointer than the key, which also
	 * proves the scrub landed at all rather than leaving the pose where it began.
	 */
	@Test
	fun withoutCtrlAScrubIgnoresTheKeys() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.bodyX))
			val reach = snapReach(harness, SheetRows.GEOMETRY, PanelIds.bodyX)
			val near = 8f - 0.4f * reach

			drag(
				lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, -2.5f),
				listOf(lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, 6.5f), lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, near)),
			)

			val committed = assertNotNull(harness.committed(PanelIds.bodyX))
			assertTrue(abs(committed - near) < 0.2f * reach, "no snap without Ctrl: the release lands under the pointer at $near, got $committed")
		}

	/** A Ctrl drag of a key lands it exactly on another row's key, so the two line up. */
	@Test
	fun aCtrlKeyDragLinesUpWithAnotherRowsKey() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.bodyX))
			val near = 8f - 0.4f * snapReach(harness, SheetRows.GEOMETRY, PanelIds.bodyX)

			setCtrlHeld(true)
			drag(
				lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, 5f),
				listOf(lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, 6.5f), lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, near)),
			)
			setCtrlHeld(false)

			assertEquals(listOf(-5f, 0f, 8f), geometryKeysOf(harness, PanelIds.drawable, PanelIds.bodyX))
		}

	/**
	 * A key dragged with Ctrl past an opacity key that shares its place with one of its own track's keys does
	 * not latch there, so its track never ends up with two keys in one place.
	 */
	@Test
	fun aCtrlKeyDragNeverStacksOnItsOwnTracksKey() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.bodyX))
			val near = 0.4f * snapReach(harness, SheetRows.GEOMETRY, PanelIds.bodyX)

			setCtrlHeld(true)
			drag(
				lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, -5f),
				listOf(lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, -2.5f), lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, near)),
			)
			setCtrlHeld(false)

			val keys = geometryKeysOf(harness, PanelIds.drawable, PanelIds.bodyX)
			assertEquals(3, keys.size)
			assertTrue(keys.zipWithNext().all { (lower, upper) -> upper - lower > 0.01f }, "two keys stacked in one place: $keys")
		}

	/** A Ctrl drag of a key lands it on the playhead, the pose on screen. */
	@Test
	fun aCtrlKeyDragLatchesOntoThePlayhead() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.bodyX))
			clickAt(lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, 2.5f))
			val playhead = harness.committed(PanelIds.bodyX)!!
			val near = playhead + 0.4f * snapReach(harness, SheetRows.GEOMETRY, PanelIds.bodyX)

			setCtrlHeld(true)
			drag(
				lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, -5f),
				listOf(lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, -2.5f), lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, near)),
			)
			setCtrlHeld(false)

			assertEquals(listOf(0f, playhead, 5f), geometryKeysOf(harness, PanelIds.drawable, PanelIds.bodyX))
		}

	/**
	 * A Ctrl drag of one key of a selection lands that key on the target and moves the rest by the same share,
	 * and the selected part key at -8 is no target for the key carrying it along.
	 */
	@Test
	fun aCtrlGroupDragPutsTheHandOnTheTarget() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.bodyX))
			clickAt(lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, -5f))
			shiftClickAt(lanePoint(harness, SheetRows.PART_OPACITY, PanelIds.bodyX, -8f))
			val near = 8f - 0.4f * snapReach(harness, SheetRows.GEOMETRY, PanelIds.bodyX)

			setCtrlHeld(true)
			drag(
				lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, -5f),
				listOf(lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, -2.5f), lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, near)),
			)
			setCtrlHeld(false)

			val geometryKeys = geometryKeysOf(harness, PanelIds.drawable, PanelIds.bodyX)
			assertEquals(8f, geometryKeys.last(), "the key under the hand lands exactly on the opacity key at 8: $geometryKeys")
			assertKeysNear(listOf(5f, 8f), partOpacityKeysOf(harness), "the part key travels the same thirteen units")
		}
}