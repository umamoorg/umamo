package org.umamo.ui.workspace.spaces.keyformsheet

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.DpSize
import org.umamo.edit.ParameterSelection
import org.umamo.ui.workspace.spaces.parameters.PANEL_FIXTURE_POSE
import org.umamo.ui.workspace.spaces.parameters.PANEL_HEIGHT_SCROLLING
import org.umamo.ui.workspace.spaces.parameters.PANEL_SHEET_TAG
import org.umamo.ui.workspace.spaces.parameters.PANEL_WIDTH
import org.umamo.ui.workspace.spaces.parameters.PanelIds
import org.umamo.ui.workspace.spaces.parameters.ParametersPanelHarness
import org.umamo.ui.workspace.spaces.parameters.clickAt
import org.umamo.ui.workspace.spaces.parameters.drag
import org.umamo.ui.workspace.spaces.parameters.mountParametersPanel
import org.umamo.ui.workspace.spaces.parameters.panelBoundsOf
import org.umamo.ui.workspace.spaces.parameters.panelBoundsOfText
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins what the keyform sheet may do to the pose.  Its track is a second way to scrub a parameter and a
 * click on a key lands the pose on it, so in Edit mode, where the pose is pinned, both must leave the
 * pose alone - the pose the rig holds, and the rest pose the viewport is showing.
 *
 * The sheet is mounted beside the Parameters panel, over the same session and the same pose hand-off,
 * which is how the two sit in the editor.
 */
@OptIn(ExperimentalTestApi::class)
class KeyformSheetPoseLockTest {
	/**
	 * Mounts the panel and the sheet, with Body X targeted so the sheet has a section to show.
	 *
	 * @return ParametersPanelHarness The mounted harness.
	 */
	private fun ComposeUiTest.mountWithSheet(): ParametersPanelHarness {
		val harness = ParametersPanelHarness(showKeyformSheet = true)
		harness.panelSize = DpSize(PANEL_WIDTH, PANEL_HEIGHT_SCROLLING)
		mountParametersPanel(harness)
		runOnIdle { harness.session.setParameterSelection(ParameterSelection.of(PanelIds.bodyX)) }
		waitForIdle()

		return harness
	}

	/**
	 * A point on the track of the row labelled [rowLabel].  A track is a bare canvas, so it is worked out
	 * from the row's label and the sheet's own edges: the track runs from the label column to the sheet's
	 * right edge, near enough.  The fixture drawable's keys are on its geometry track, the one row under
	 * its group; the group's own row shows them only while it is folded.
	 *
	 * @param ParametersPanelHarness harness The mounted harness.
	 * @param String rowLabel The label of the row whose track is meant.
	 * @param Float across How far along the track, 0 at its left end.
	 * @return Offset The point, in the panel body's pixels, which is what the gesture helpers take.
	 */
	private fun ComposeUiTest.trackPoint(harness: ParametersPanelHarness, rowLabel: String, across: Float): Offset {
		val sheet = panelBoundsOf(onNodeWithTag(PANEL_SHEET_TAG))
		val label = panelBoundsOfText(rowLabel)
		val sheetViewState = harness.sheetScope.spaceState(KEYFORM_SHEET_VIEW_STATE_KEY) { KeyformSheetViewState() }
		val trackLeft = sheet.left + with(density) { sheetViewState.labelColumnWidth.toPx() }

		return Offset(trackLeft + (sheet.right - trackLeft) * across, label.center.y)
	}

	/**
	 * Asserts a value a track press produced, to within what the track's own insets leave unknown.
	 *
	 * @param Float expected The value the press should have produced.
	 * @param Float? actual The value it produced.
	 */
	private fun assertAbout(expected: Float, actual: Float?) {
		assertTrue(actual != null && abs(actual - expected) <= TRACK_TOLERANCE, "expected about $expected, got $actual")
	}

	/** In Object mode a press on the track scrubs the parameter, as one step.  The cases below mean nothing unless this holds. */
	@Test
	fun inObjectModeATrackPressScrubsThePose() =
		runComposeUiTest {
			val harness = mountWithSheet()
			val cursorBefore = harness.historyCursor

			clickAt(trackPoint(harness, harness.text.trackGeometry, across = 0.8f))

			// Body X runs from -10 to 10, so four fifths of the way along it is 6.
			assertAbout(6f, harness.committed(PanelIds.bodyX))
			assertEquals(cursorBefore + 1, harness.historyCursor)
		}

	/** In Edit mode the same press moves nothing: not the rig's pose, not the pose the viewport shows. */
	@Test
	fun inEditModeATrackPressMovesNothing() =
		runComposeUiTest {
			val harness = mountWithSheet()
			runOnIdle { harness.enterEditMode() }
			waitForIdle()
			val cursorBefore = harness.historyCursor

			clickAt(trackPoint(harness, harness.text.trackGeometry, across = 0.8f))

			assertEquals(PANEL_FIXTURE_POSE, harness.session.pose.value, "every parameter must hold the value Object mode left it")
			assertNull(harness.live(PanelIds.bodyX), "Edit mode shows the rig at rest, and a scrub must not pose it")
			assertEquals(cursorBefore, harness.historyCursor)
		}

	/** A drag along the track is held to the same, for every frame of it and for its release. */
	@Test
	fun inEditModeATrackDragMovesNothing() =
		runComposeUiTest {
			val harness = mountWithSheet()
			runOnIdle { harness.enterEditMode() }
			waitForIdle()
			val cursorBefore = harness.historyCursor

			drag(
				trackPoint(harness, harness.text.trackGeometry, across = 0.2f),
				listOf(trackPoint(harness, harness.text.trackGeometry, across = 0.4f), trackPoint(harness, harness.text.trackGeometry, across = 0.9f)),
			)

			assertEquals(PANEL_FIXTURE_POSE, harness.session.pose.value)
			assertNull(harness.live(PanelIds.bodyX))
			assertEquals(cursorBefore, harness.historyCursor)
		}

	/** In Object mode a click on a key selects it and lands the pose on it, as one step. */
	@Test
	fun inObjectModeAKeyClickSelectsAndLandsThePoseOnIt() =
		runComposeUiTest {
			val harness = mountWithSheet()
			val cursorBefore = harness.historyCursor

			// The fixture's one key sits on Body X at 0, the middle of its range.
			clickAt(trackPoint(harness, harness.text.trackGeometry, across = 0.5f))

			assertTrue(harness.session.keySelection.value.isNotEmpty(), "the press must really have landed on the key")
			assertEquals(0f, harness.committed(PanelIds.bodyX))
			assertEquals(cursorBefore + 1, harness.historyCursor)
		}

	/** In Edit mode the same click still selects the key, which is a selection and no pose move, and leaves the pose. */
	@Test
	fun inEditModeAKeyClickSelectsAndLeavesThePose() =
		runComposeUiTest {
			val harness = mountWithSheet()
			runOnIdle { harness.enterEditMode() }
			waitForIdle()
			val cursorBefore = harness.historyCursor

			clickAt(trackPoint(harness, harness.text.trackGeometry, across = 0.5f))

			assertTrue(harness.session.keySelection.value.isNotEmpty(), "the press must really have landed on the key")
			assertEquals(PANEL_FIXTURE_POSE, harness.session.pose.value)
			assertNull(harness.live(PanelIds.bodyX))
			assertEquals(cursorBefore + 1, harness.historyCursor, "the selection is a step of its own")
		}

	/** The sheet's lock follows the mode both ways inside one composition. */
	@Test
	fun theSheetsLockFollowsTheModeBothWays() =
		runComposeUiTest {
			val harness = mountWithSheet()
			runOnIdle { harness.enterEditMode() }
			waitForIdle()
			clickAt(trackPoint(harness, harness.text.trackGeometry, across = 0.8f))
			assertEquals(PANEL_FIXTURE_POSE, harness.session.pose.value, "Edit mode has to lock the track")

			runOnIdle { harness.leaveEditMode() }
			waitForIdle()
			clickAt(trackPoint(harness, harness.text.trackGeometry, across = 0.8f))
			assertAbout(6f, harness.committed(PanelIds.bodyX))
			assertEquals(PANEL_FIXTURE_POSE - PanelIds.bodyX, harness.session.pose.value - PanelIds.bodyX, "and the scrub moves the one parameter")

			runOnIdle { harness.enterEditMode() }
			waitForIdle()
			val poseBefore = harness.session.pose.value
			clickAt(trackPoint(harness, harness.text.trackGeometry, across = 0.2f))
			assertEquals(poseBefore, harness.session.pose.value, "and entering it again has to lock it again")
		}

	private companion object {
		/** How far a track press may land from the value its fraction names: the track is inset at both ends. */
		const val TRACK_TOLERANCE = 1f
	}
}