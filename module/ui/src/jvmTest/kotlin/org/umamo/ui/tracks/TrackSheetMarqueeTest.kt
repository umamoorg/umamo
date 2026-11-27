package org.umamo.ui.tracks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The box-select marquee over a track sheet, driven with real pointer input: what a band reports and in
 * which coordinates, and what abandons it - the rules every box select in the editor answers to.
 */
@OptIn(ExperimentalTestApi::class)
class TrackSheetMarqueeTest {
	/** A -30..30 domain with one group row over one track, marks at both ends and the middle. */
	private val axis = TrackAxis(-30f, 30f)

	private val rows =
		listOf(
			TrackRow(
				key = "owner",
				label = "Owner",
				tone = TrackRowTone.Group,
				children =
					listOf(
						TrackRow(
							key = "owner/opacity",
							label = "Opacity",
							marks = listOf(TrackKeyMark(0, -30f), TrackKeyMark(1, 0f), TrackKeyMark(2, 30f)),
						),
					),
			),
		)

	/** What the overlay reported: the band, whether it was additive, and how often it dismissed. */
	private var region: Rect? = null
	private var additive: Boolean? = null
	private var dismissals = 0

	private var armed by mutableStateOf(true)

	/**
	 * Mounts the marquee alone in a 600 x 200 box, reading the armed flag from composition so a test can
	 * disarm it mid-drag.
	 *
	 * @param ComposeUiTest test The running UI test.
	 */
	private fun mountMarquee(test: ComposeUiTest) {
		test.setContent {
			Box(modifier = Modifier.size(width = 600.dp, height = 200.dp).testTag("sheet")) {
				TrackSheetMarqueeOverlay(
					armed = armed,
					onSelect = { enclosed, wasAdditive ->
						region = enclosed
						additive = wasAdditive
					},
					onDismiss = { dismissals++ },
				)
			}
		}
	}

	/** An armed marquee reports the region it enclosed, in window coordinates, and then disarms. */
	@Test
	fun anArmedMarqueeReportsItsRegion() =
		runComposeUiTest {
			mountMarquee(this)
			onNodeWithTag("sheet").performMouseInput {
				moveTo(Offset(100f, 40f))
				press()
				moveTo(Offset(300f, 120f))
				release()
			}
			waitForIdle()
			val enclosed = assertNotNull(region, "a drag must report its region")
			assertEquals(200f, enclosed.width, "the region spans the drag horizontally")
			assertEquals(80f, enclosed.height, "and vertically")
			assertEquals(false, additive, "an unmodified drag replaces the selection")
			assertEquals(1, dismissals, "and the marquee disarms itself afterwards")
		}

	/** A marquee dragged up-and-left still reports an ascending rectangle. */
	@Test
	fun aMarqueeNormalizesItsRegion() =
		runComposeUiTest {
			mountMarquee(this)
			onNodeWithTag("sheet").performMouseInput {
				moveTo(Offset(300f, 120f))
				press()
				moveTo(Offset(100f, 40f))
				release()
			}
			waitForIdle()
			val enclosed = assertNotNull(region)
			assertTrue(enclosed.left < enclosed.right && enclosed.top < enclosed.bottom, "got $enclosed")
		}

	/** A disarmed marquee is not in the way at all - it composes nothing and takes no pointer input. */
	@Test
	fun aDisarmedMarqueeTakesNoInput() =
		runComposeUiTest {
			var trackScrubbed = false
			setContent {
				Box(modifier = Modifier.size(width = 600.dp, height = 200.dp).testTag("sheet")) {
					TrackSheet(
						rows = rows,
						axis = axis,
						playhead = null,
						modifier = Modifier.fillMaxSize(),
						expandedKeys = setOf("owner"),
						onTrackScrub = { _, _, _ -> trackScrubbed = true },
					)
					TrackSheetMarqueeOverlay(
						armed = false,
						onSelect = { enclosed, _ -> region = enclosed },
						onDismiss = {},
					)
				}
			}
			onNodeWithTag("sheet").performMouseInput {
				moveTo(Offset(LANE_START_X + (width - LANE_START_X) * 0.25f, CHILD_ROW_CENTER_Y))
				press()
				release()
			}
			waitForIdle()
			assertNull(region, "a disarmed marquee reports nothing")
			assertTrue(trackScrubbed, "and the lane underneath still gets its gesture")
		}

	/** A right-click mid-drag abandons the band and disarms; the primary release afterwards lands nothing. */
	@Test
	fun aRightClickMidDragAbandonsTheBand() =
		runComposeUiTest {
			mountMarquee(this)
			onNodeWithTag("sheet").performMouseInput {
				moveTo(Offset(100f, 40f))
				press()
				moveTo(Offset(300f, 120f))
				press(MouseButton.Secondary)
				release(MouseButton.Secondary)
				release()
			}
			waitForIdle()
			assertNull(region, "an abandoned band reports nothing")
			assertEquals(1, dismissals, "and the right-click disarms")
		}

	/** A release under the click threshold is a click, not a box: it only disarms, reporting no region. */
	@Test
	fun aClickUnderTheThresholdOnlyDisarms() =
		runComposeUiTest {
			mountMarquee(this)
			onNodeWithTag("sheet").performMouseInput {
				moveTo(Offset(100f, 40f))
				press()
				moveTo(Offset(101f, 41f))
				release()
			}
			waitForIdle()
			assertNull(region, "a click encloses nothing, so it reports nothing")
			assertEquals(1, dismissals)
		}

	/** Shift is read at the RELEASE, as the viewport's box reads it, not at the press. */
	@Test
	fun shiftIsReadAtTheRelease() =
		runComposeUiTest {
			mountMarquee(this)
			onNodeWithTag("sheet").performMouseInput {
				moveTo(Offset(100f, 40f))
				press()
				moveTo(Offset(300f, 120f))
			}
			onNodeWithTag("sheet").performKeyInput { keyDown(Key.ShiftLeft) }
			onNodeWithTag("sheet").performMouseInput { release() }
			onNodeWithTag("sheet").performKeyInput { keyUp(Key.ShiftLeft) }
			waitForIdle()
			assertNotNull(region)
			assertEquals(true, additive, "Shift held at the release adds")
		}

	/**
	 * Disarming mid-drag (what Escape does through the shell) lands nothing.  The overlay leaves composition
	 * under the pressed pointer, and the synthetic release Compose sends the loop on its way out arrives
	 * already consumed: it is not the user's release, so the band is abandoned on it.
	 */
	@Test
	fun disarmingMidDragLandsNothing() =
		runComposeUiTest {
			mountMarquee(this)
			onNodeWithTag("sheet").performMouseInput {
				moveTo(Offset(100f, 40f))
				press()
				moveTo(Offset(300f, 120f))
			}
			runOnIdle { armed = false }
			waitForIdle()
			assertNull(region, "a disarmed band reports nothing")
			onNodeWithTag("sheet").performMouseInput { release() }
			waitForIdle()
			assertNull(region, "and the release afterwards reaches no marquee")
		}
}

/** The x just right of the sheet's label column and its separator, in pixels at density 1. */
private const val LANE_START_X = 190f

/** The y at the middle of the single child row: ruler, then the group row, then half a row. */
private const val CHILD_ROW_CENTER_Y = 20f + 32f + 16f