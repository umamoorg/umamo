package org.umamo.ui.workspace.spaces.keyformsheet

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.edit.ParameterSelection
import org.umamo.edit.TrackKeyRef
import org.umamo.ui.tracks.TrackWindow
import org.umamo.ui.workspace.spaces.parameters.PANEL_SHEET_AREA_ID
import org.umamo.ui.workspace.spaces.parameters.PanelIds
import org.umamo.ui.workspace.spaces.parameters.clickAt
import org.umamo.ui.workspace.spaces.parameters.drag
import org.umamo.ui.workspace.spaces.parameters.pressAndMove
import org.umamo.ui.workspace.spaces.parameters.pressKey
import org.umamo.ui.workspace.spaces.parameters.releasePress
import org.umamo.ui.workspace.spaces.parameters.secondaryClickAt
import org.umamo.ui.workspace.spaces.parameters.showsText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the sheet offers the shell: the command surface it registers (nudge, delete selected, frame all,
 * and the box-select marquee Escape can cancel), and the notices it shows when it has nothing to draw.
 */
@OptIn(ExperimentalTestApi::class)
class KeyformSheetSurfaceTest {
	private val geometryKey0 = TrackKeyRef(PanelIds.bodyX, SheetRows.GEOMETRY, 0)
	private val geometryKey1 = TrackKeyRef(PanelIds.bodyX, SheetRows.GEOMETRY, 1)
	private val geometryKey2 = TrackKeyRef(PanelIds.bodyX, SheetRows.GEOMETRY, 2)

	/** The nudge moves the selection by a hundredth of its range, as one step, through the same path a drag commits on. */
	@Test
	fun theNudgeMovesTheSelectionAsOneStep() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.bodyX))
			clickAt(lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, -5f))
			val cursorBefore = harness.historyCursor

			runOnIdle { harness.registry.invoke("keyform.nudgeKeyRight") }
			waitForIdle()

			// A hundredth of Body X's twenty.
			assertKeysNear(listOf(-4.8f, 0f, 5f), geometryKeysOf(harness, PanelIds.drawable, PanelIds.bodyX), "the selected key moved")
			assertEquals(-4.8f, geometryKeysOf(harness, PanelIds.drawable, PanelIds.bodyX).first(), 1e-4f)
			assertEquals(setOf(geometryKey0), harness.session.keySelection.value)
			assertEquals(cursorBefore + 1, harness.historyCursor)
		}

	/** Delete Selected Keys removes every selected key across the sheet's tracks, and the selection with them. */
	@Test
	fun deleteSelectedKeysRemovesTheSelection() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.bodyX))
			clickAt(lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, -5f))
			shiftClickAt(lanePoint(harness, SheetRows.OPACITY, PanelIds.bodyX, 5f))
			val cursorBefore = harness.historyCursor

			runOnIdle { harness.registry.invoke("keyform.deleteSelectedKeys") }
			waitForIdle()

			assertEquals(listOf(0f, 5f), geometryKeysOf(harness, PanelIds.drawable, PanelIds.bodyX))
			assertEquals(listOf(0f, 8f), opacityKeysOf(harness, PanelIds.drawable, PanelIds.bodyX))
			assertEquals(emptySet(), harness.session.keySelection.value)
			assertEquals(cursorBefore + 1, harness.historyCursor)
		}

	/** Frame All returns a zoomed sheet to the whole range. */
	@Test
	fun frameAllShowsTheWholeRange() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.bodyX))
			runOnIdle { harness.sheetViewState.window = TrackWindow(0.25f, 0.75f) }
			waitForIdle()

			runOnIdle { harness.registry.invoke("keyform.frameAll") }
			waitForIdle()

			assertEquals(TrackWindow.Full, harness.sheetViewState.window)
		}

	/** Box Select arms the marquee, and the band selects the keys it encloses and disarms. */
	@Test
	fun theMarqueeSelectsTheKeysItEncloses() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.bodyX))

			runOnIdle { harness.registry.invoke("mesh.boxSelect") }
			waitForIdle()
			assertTrue(harness.sheetViewState.boxSelectArmed)

			// A band inside the geometry lane, from left of its first key to right of its second.
			val lane = laneBox(harness, SheetRows.GEOMETRY)
			val start = lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, -6f)
			val end = lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, 1f)
			drag(Offset(start.x, lane.top + 2f), listOf(Offset((start.x + end.x) / 2f, lane.center.y), Offset(end.x, lane.bottom - 2f)))

			assertEquals(setOf(geometryKey0, geometryKey1), harness.session.keySelection.value)
			assertFalse(harness.sheetViewState.boxSelectArmed, "one band, then the marquee is spent")
		}

	/** Escape cancels an armed marquee, which hides the pointer and would otherwise strand it. */
	@Test
	fun escapeDisarmsTheMarquee() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.bodyX))
			runOnIdle { harness.registry.invoke("mesh.boxSelect") }
			waitForIdle()
			assertTrue(harness.sheetViewState.boxSelectArmed)

			pressKey(Key.Escape)

			assertFalse(harness.sheetViewState.boxSelectArmed)
		}

	/** An armed click is a click, not a box: it only disarms, leaving the selection as it was. */
	@Test
	fun aMarqueeClickKeepsTheSelection() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.bodyX))
			clickAt(lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, 5f))
			assertEquals(setOf(geometryKey2), harness.session.keySelection.value)
			runOnIdle { harness.registry.invoke("mesh.boxSelect") }
			waitForIdle()
			assertTrue(harness.sheetViewState.boxSelectArmed)

			// Empty track, between the keys at -5 and 0.
			clickAt(lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, -2f))

			assertEquals(setOf(geometryKey2), harness.session.keySelection.value, "a bare click encloses nothing and replaces nothing")
			assertFalse(harness.sheetViewState.boxSelectArmed, "and the marquee is spent")
		}

	/** A right-click mid-drag abandons the band: nothing lands, and the marquee disarms. */
	@Test
	fun aRightClickMidMarqueeSelectsNothing() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.bodyX))
			runOnIdle { harness.registry.invoke("mesh.boxSelect") }
			waitForIdle()
			val lane = laneBox(harness, SheetRows.GEOMETRY)
			val start = lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, -6f)
			val end = lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, 1f)

			pressAndMove(Offset(start.x, lane.top + 2f), listOf(Offset(end.x, lane.bottom - 2f)))
			secondaryClickAt(Offset(end.x, lane.bottom - 2f))
			releasePress()

			assertEquals(emptySet(), harness.session.keySelection.value, "an abandoned band selects nothing")
			assertFalse(harness.sheetViewState.boxSelectArmed, "and the right-click disarms")
		}

	/**
	 * Escape mid-drag lands nothing.  The shell ladder disarms the marquee, which leaves composition under
	 * the pressed pointer; the synthetic release Compose sends the loop on its way out arrives already
	 * consumed and is not the user's release, so the band is abandoned rather than landed on the keys it
	 * happened to cover.
	 */
	@Test
	fun escapeMidMarqueeSelectsNothing() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.bodyX))
			runOnIdle { harness.registry.invoke("mesh.boxSelect") }
			waitForIdle()
			val lane = laneBox(harness, SheetRows.GEOMETRY)
			val start = lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, -6f)
			val end = lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, 1f)

			pressAndMove(Offset(start.x, lane.top + 2f), listOf(Offset(end.x, lane.bottom - 2f)))
			pressKey(Key.Escape)
			releasePress()

			assertEquals(emptySet(), harness.session.keySelection.value, "a cancelled band selects nothing")
			assertFalse(harness.sheetViewState.boxSelectArmed)
		}

	/** A parameter nothing is keyed on shows a notice, and registers no command surface until it has tracks. */
	@Test
	fun anEmptySheetSaysSoAndRegistersNoSurface() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.breath))
			assertTrue(showsText(harness.text.sheetNoTracks))
			assertNull(harness.keyformSheetViews.resolve(PANEL_SHEET_AREA_ID), "an empty sheet offers the commands nothing")

			runOnIdle { harness.session.setParameterSelection(ParameterSelection.of(PanelIds.bodyX)) }
			waitForIdle()
			assertFalse(showsText(harness.text.sheetNoTracks))
			assertNotNull(harness.keyformSheetViews.resolve(PANEL_SHEET_AREA_ID))
		}

	/** A filter that hides every track says so, rather than claiming nothing is keyed. */
	@Test
	fun aSheetEmptiedByItsFiltersSaysSo() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.bodyX))
			runOnIdle {
				harness.sheetViewState.showGeometry = false
				harness.sheetViewState.showChannels = false
			}
			waitForIdle()

			assertTrue(showsText(harness.text.sheetAllFiltered))
			assertFalse(showsText(harness.text.sheetNoTracks))
		}

	/** An empty section beside a keyed one shows its own notice and leaves the other's rows alone. */
	@Test
	fun anEmptySectionShowsItsOwnNotice() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.bodyX, PanelIds.breath))

			assertTrue(showsText(harness.text.sheetNoTracks), "the Breath section has nothing keyed")
			assertEquals(1, sheetCountOfText(harness.text.trackGeometry), "the Body X section still lists its tracks")
		}
}