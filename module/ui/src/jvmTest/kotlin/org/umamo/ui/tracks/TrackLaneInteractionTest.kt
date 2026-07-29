package org.umamo.ui.tracks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The lane's tap-versus-drag resolution, driven with real pointer input against a real composition.
 *
 * These paths are not reachable from a pure-function test: the lane decides between a click and a drag by
 * reading one raw pointer stream, and every interaction bug reported against the keyform sheet so far has
 * been in that decision or in the state it writes to - never in the pure projection the unit tests cover.
 */
class TrackLaneInteractionTest {
	/** A sheet 400px wide over a -30..30 domain with marks at both ends and the middle. */
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

	/** A press-and-release on a mark reports it as a CLICK, not as a zero-length drag. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun pressingAMarkReportsAClick() =
		runComposeUiTest {
			var clicked: TrackKeyMark? = null
			var trackClicked = false
			var dragEnded: Float? = null
			setContent {
				Box(modifier = Modifier.size(width = 600.dp, height = 200.dp).testTag("sheet")) {
					TrackSheet(
						rows = rows,
						axis = axis,
						playhead = null,
						modifier = Modifier.fillMaxSize(),
						expandedKeys = setOf("owner"),
						onMarkClick = { _, mark -> clicked = mark },
						onTrackScrub = { _, _ -> trackClicked = true },
						onMarkDragEnd = { _, _, released -> dragEnded = released },
					)
				}
			}
			// The child row sits under the ruler and the group row; click the middle mark on it.
			onNodeWithTag("sheet").performMouseInput {
				val laneCenterX = (width + labelColumnEdge()) / 2f
				moveTo(Offset(laneCenterX, childRowCenterY()))
				press()
				release()
			}
			waitForIdle()
			assertNotNull(clicked, "a press on a mark must report a mark click")
			assertEquals(0f, assertNotNull(clicked).position)
			assertTrue(!trackClicked, "a mark click must not also read as an empty-track click")
			assertNull(dragEnded, "a click is not a drag")
		}

	/** A press on empty track reports an empty-track click carrying the domain value under the pointer. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun pressingEmptyTrackReportsTheDomainValue() =
		runComposeUiTest {
			var clicked: TrackKeyMark? = null
			var trackValue: Float? = null
			setContent {
				Box(modifier = Modifier.size(width = 600.dp, height = 200.dp).testTag("sheet")) {
					TrackSheet(
						rows = rows,
						axis = axis,
						playhead = null,
						modifier = Modifier.fillMaxSize(),
						expandedKeys = setOf("owner"),
						onMarkClick = { _, mark -> clicked = mark },
						onTrackScrubEnd = { _, value -> trackValue = value },
					)
				}
			}
			onNodeWithTag("sheet").performMouseInput {
				// A quarter of the way along the lane: nowhere near a mark at -30 / 0 / 30.
				val laneStart = labelColumnEdge()
				moveTo(Offset(laneStart + (width - laneStart) * 0.25f, childRowCenterY()))
				press()
				release()
			}
			waitForIdle()
			assertNull(clicked, "a click away from every mark must not report a mark")
			assertNotNull(trackValue, "an empty-track click must report where it landed")
		}

	/** Dragging a mark reports the release position, so the caller can commit the move. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun draggingAMarkReportsItsReleasePosition() =
		runComposeUiTest {
			var draggedMark: TrackKeyMark? = null
			var releasedAt: Float? = null
			var clicked: TrackKeyMark? = null
			setContent {
				Box(modifier = Modifier.size(width = 600.dp, height = 200.dp).testTag("sheet")) {
					TrackSheet(
						rows = rows,
						axis = axis,
						playhead = null,
						modifier = Modifier.fillMaxSize(),
						expandedKeys = setOf("owner"),
						onMarkClick = { _, mark -> clicked = mark },
						onMarkDragEnd = { _, mark, released ->
							draggedMark = mark
							releasedAt = released
						},
					)
				}
			}
			onNodeWithTag("sheet").performMouseInput {
				val laneCenterX = (width + labelColumnEdge()) / 2f
				val rowY = childRowCenterY()
				moveTo(Offset(laneCenterX, rowY))
				press()
				moveTo(Offset(laneCenterX + 60f, rowY))
				moveTo(Offset(laneCenterX + 120f, rowY))
				release()
			}
			waitForIdle()
			assertEquals(0f, assertNotNull(draggedMark).position, "the mark under the press is the one dragged")
			assertNull(clicked, "a drag must not also report a click")
			assertTrue(assertNotNull(releasedAt) > 0f, "releasing to the right must report a larger domain value")
		}

	/** A secondary click over a mark resolves to THAT mark, so the menu can offer to remove it. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun rightClickingAMarkResolvesToIt() =
		runComposeUiTest {
			var hit: TrackLaneHit? = null
			var markClicked = false
			var trackClicked = false
			setContent {
				Box(
					modifier =
						Modifier
							.size(width = 600.dp, height = 200.dp)
							.testTag("sheet")
							.verticalScroll(rememberScrollState()),
				) {
					TrackSheet(
						rows = rows,
						axis = axis,
						playhead = null,
						modifier = Modifier.fillMaxWidth(),
						expandedKeys = setOf("owner"),
						// The tap handlers are live, as they are in the sheet: two pointer-input modifiers on
						// one node is exactly what a secondary press has to survive.
						onMarkClick = { _, _ -> markClicked = true },
						onTrackScrub = { _, _ -> trackClicked = true },
						onMarkDragEnd = { _, _, _ -> },
						laneMenuItems = { laneHit ->
							hit = laneHit
							emptyList()
						},
					)
				}
			}
			onNodeWithTag("sheet").performMouseInput {
				val laneCenterX = (width + labelColumnEdge()) / 2f
				moveTo(Offset(laneCenterX, childRowCenterY()))
				press(MouseButton.Secondary)
				release(MouseButton.Secondary)
			}
			waitForIdle()
			val resolved = assertNotNull(hit, "a secondary click must reach the lane's menu hook")
			assertEquals(0f, assertNotNull(resolved.mark).position, "it must resolve to the mark under the pointer")
			assertTrue(!markClicked, "a secondary press must not also scrub onto the mark")
		}

	/** A secondary click away from every mark resolves to empty track, so the menu offers to insert. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun rightClickingEmptyTrackResolvesToNoMark() =
		runComposeUiTest {
			var hit: TrackLaneHit? = null
			var markClicked = false
			var trackClicked = false
			setContent {
				Box(
					modifier =
						Modifier
							.size(width = 600.dp, height = 200.dp)
							.testTag("sheet")
							.verticalScroll(rememberScrollState()),
				) {
					TrackSheet(
						rows = rows,
						axis = axis,
						playhead = null,
						modifier = Modifier.fillMaxWidth(),
						expandedKeys = setOf("owner"),
						// The tap handlers are live, as they are in the sheet: two pointer-input modifiers on
						// one node is exactly what a secondary press has to survive.
						onMarkClick = { _, _ -> markClicked = true },
						onTrackScrub = { _, _ -> trackClicked = true },
						onMarkDragEnd = { _, _, _ -> },
						laneMenuItems = { laneHit ->
							hit = laneHit
							emptyList()
						},
					)
				}
			}
			onNodeWithTag("sheet").performMouseInput {
				val laneStart = labelColumnEdge()
				moveTo(Offset(laneStart + (width - laneStart) * 0.25f, childRowCenterY()))
				press(MouseButton.Secondary)
				release(MouseButton.Secondary)
			}
			waitForIdle()
			assertNull(assertNotNull(hit, "a secondary click must reach the menu hook").mark)
			assertTrue(!trackClicked, "a secondary press must not also clear the selection")
		}

	/** A drag stops at the mark's neighbour instead of running past it and snapping back on release. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun aDragIsClampedAtItsNeighbour() =
		runComposeUiTest {
			var releasedAt: Float? = null
			setContent {
				Box(modifier = Modifier.size(width = 600.dp, height = 200.dp).testTag("sheet")) {
					TrackSheet(
						rows = rows,
						axis = axis,
						playhead = null,
						modifier = Modifier.fillMaxSize(),
						expandedKeys = setOf("owner"),
						onMarkDragEnd = { _, _, released -> releasedAt = released },
					)
				}
			}
			onNodeWithTag("sheet").performMouseInput {
				// Grab the middle mark (at 0) and haul it far past the one at 30.
				val laneCenterX = (width + labelColumnEdge()) / 2f
				val rowY = childRowCenterY()
				moveTo(Offset(laneCenterX, rowY))
				press()
				moveTo(Offset(width + 500f, rowY))
				release()
			}
			waitForIdle()
			assertEquals(30f, assertNotNull(releasedAt), "the drag must stop at the neighbour, not run past it")
		}

	/** An endpoint drag stops at the axis end rather than leaving the track. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun aDragIsClampedAtTheAxisEnd() =
		runComposeUiTest {
			var releasedAt: Float? = null
			setContent {
				Box(modifier = Modifier.size(width = 600.dp, height = 200.dp).testTag("sheet")) {
					TrackSheet(
						rows = rows,
						axis = axis,
						playhead = null,
						modifier = Modifier.fillMaxSize(),
						expandedKeys = setOf("owner"),
						onMarkDragEnd = { _, _, released -> releasedAt = released },
					)
				}
			}
			onNodeWithTag("sheet").performMouseInput {
				// Grab the LAST mark (at 30, hard against the right edge) and haul it further right.
				val rowY = childRowCenterY()
				moveTo(Offset(width - 6f, rowY))
				press()
				moveTo(Offset(width + 500f, rowY))
				release()
			}
			waitForIdle()
			assertEquals(30f, assertNotNull(releasedAt), "an endpoint must stop at the axis end")
		}

	/** Pressing empty track scrubs immediately, dragging keeps scrubbing, and releasing commits. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun draggingEmptyTrackScrubsContinuously() =
		runComposeUiTest {
			val scrubbed = mutableListOf<Float>()
			var committedAt: Float? = null
			setContent {
				Box(modifier = Modifier.size(width = 600.dp, height = 200.dp).testTag("sheet")) {
					TrackSheet(
						rows = rows,
						axis = axis,
						playhead = null,
						modifier = Modifier.fillMaxSize(),
						expandedKeys = setOf("owner"),
						onTrackScrub = { _, value -> scrubbed.add(value) },
						onTrackScrubEnd = { _, value -> committedAt = value },
					)
				}
			}
			onNodeWithTag("sheet").performMouseInput {
				// Start a quarter along - nowhere near the marks at -30 / 0 / 30 - and drag right.
				val laneStart = labelColumnEdge()
				val startX = laneStart + (width - laneStart) * 0.25f
				val rowY = childRowCenterY()
				moveTo(Offset(startX, rowY))
				press()
				moveTo(Offset(startX + 40f, rowY))
				moveTo(Offset(startX + 90f, rowY))
				release()
			}
			waitForIdle()
			assertTrue(scrubbed.size >= 3, "the press and each move must scrub (got ${scrubbed.size})")
			assertTrue(scrubbed.last() > scrubbed.first(), "dragging right must raise the scrubbed value")
			assertEquals(scrubbed.last(), assertNotNull(committedAt), "the commit lands where the drag ended")
		}

	/**
	 * A COLLAPSED group's summary mark is draggable, and reports its SUMMARY ordinal.
	 *
	 * That ordinal is the name its owner maps back to every child key stacked at that value, so one drag
	 * moves the whole stack instead of guessing at which channel was meant.
	 */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun summaryMarksAreDraggable() =
		runComposeUiTest {
			var draggedMark: TrackKeyMark? = null
			var releasedAt: Float? = null
			setContent {
				Box(modifier = Modifier.size(width = 600.dp, height = 200.dp).testTag("sheet")) {
					TrackSheet(
						rows = rows,
						axis = axis,
						playhead = null,
						modifier = Modifier.fillMaxSize(),
						// COLLAPSED, so the group row draws its subtree's summary marks.
						expandedKeys = emptySet(),
						onMarkDragEnd = { _, mark, released ->
							draggedMark = mark
							releasedAt = released
						},
					)
				}
			}
			onNodeWithTag("sheet").performMouseInput {
				// The group row is the first line under the ruler, and its middle summary mark sits at 0.
				val laneCenterX = (width + labelColumnEdge()) / 2f
				val groupRowY = 20f + 16f
				moveTo(Offset(laneCenterX, groupRowY))
				press()
				moveTo(Offset(laneCenterX + 120f, groupRowY))
				release()
			}
			waitForIdle()
			assertEquals(1, assertNotNull(draggedMark).keyIndex, "the middle of three summary marks")
			assertTrue(assertNotNull(releasedAt) > 0f, "dragging right raises the destination")
		}

	/** A scrub is clamped to the axis: dragging off the end of the track cannot push the pose out of range. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun anEmptyTrackScrubIsClampedToTheAxis() =
		runComposeUiTest {
			val scrubbed = mutableListOf<Float>()
			var committedAt: Float? = null
			setContent {
				Box(modifier = Modifier.size(width = 600.dp, height = 200.dp).testTag("sheet")) {
					TrackSheet(
						rows = rows,
						axis = axis,
						playhead = null,
						modifier = Modifier.fillMaxSize(),
						expandedKeys = setOf("owner"),
						onTrackScrub = { _, value -> scrubbed.add(value) },
						onTrackScrubEnd = { _, value -> committedAt = value },
					)
				}
			}
			onNodeWithTag("sheet").performMouseInput {
				val laneStart = labelColumnEdge()
				val rowY = childRowCenterY()
				// A quarter along, clear of the marks at -30 / 0 / 30, so this reads as a scrub.
				moveTo(Offset(laneStart + (width - laneStart) * 0.25f, rowY))
				press()
				// Far past the right edge of the lane, and then far past the left.
				moveTo(Offset(width + 400f, rowY))
				moveTo(Offset(laneStart - 400f, rowY))
				release()
			}
			waitForIdle()
			assertTrue(scrubbed.all { value -> value in -30f..30f }, "out of range: ${scrubbed.filter { it !in -30f..30f }}")
			assertTrue(assertNotNull(committedAt) in -30f..30f, "the commit is in range too")
		}

	/**
	 * Hover reports WHERE on the lane the pointer is, and which mark it is on, live as it moves.
	 *
	 * This is what `I` / `Alt+I` aim with over a track: pointing at a spot is a statement about which
	 * spot, so a row-level "the pointer is somewhere on this row" would not be enough to act on.
	 */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun hoverReportsThePositionAndMarkUnderThePointer() =
		runComposeUiTest {
			val hits = mutableListOf<TrackLaneHit?>()
			setContent {
				Box(modifier = Modifier.size(width = 600.dp, height = 200.dp).testTag("sheet")) {
					TrackSheet(
						rows = rows,
						axis = axis,
						playhead = null,
						modifier = Modifier.fillMaxSize(),
						expandedKeys = setOf("owner"),
						onLaneHover = { _, hit -> hits.add(hit) },
					)
				}
			}
			onNodeWithTag("sheet").performMouseInput {
				val laneStart = labelColumnEdge()
				val rowY = childRowCenterY()
				// A quarter along - clear of every mark - then onto the middle mark at 0.
				moveTo(Offset(laneStart + (width - laneStart) * 0.25f, rowY))
				moveTo(Offset((width + laneStart) / 2f, rowY))
			}
			waitForIdle()
			val reported = hits.filterNotNull()
			assertTrue(reported.size >= 2, "hover must report as the pointer moves, not once on enter")
			assertNull(reported.first().mark, "a quarter along is clear of every mark")
			assertEquals(0f, assertNotNull(reported.last().mark).position, "the middle mark is at 0")
			assertTrue(reported.last().value > reported.first().value, "and the reported value follows the pointer")
		}

	/** Leaving the lane reports null, so a stale target cannot outlive the pointer. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun leavingTheLaneClearsTheHover() =
		runComposeUiTest {
			val hits = mutableListOf<TrackLaneHit?>()
			setContent {
				Box(modifier = Modifier.size(width = 600.dp, height = 200.dp).testTag("sheet")) {
					TrackSheet(
						rows = rows,
						axis = axis,
						playhead = null,
						modifier = Modifier.fillMaxSize(),
						expandedKeys = setOf("owner"),
						onLaneHover = { _, hit -> hits.add(hit) },
					)
				}
			}
			onNodeWithTag("sheet").performMouseInput {
				moveTo(Offset((width + labelColumnEdge()) / 2f, childRowCenterY()))
				// Up into the ruler, off every lane.
				moveTo(Offset((width + labelColumnEdge()) / 2f, 4f))
			}
			waitForIdle()
			assertNull(hits.last(), "leaving the lane must clear what it reported")
		}
}

/** The x just right of the label column and its separator, in pixels. */
private fun androidx.compose.ui.test.MouseInjectionScope.labelColumnEdge(): Float = 190f

/** The y at the middle of the single child row: ruler, then the group row, then half a row. */
private fun androidx.compose.ui.test.MouseInjectionScope.childRowCenterY(): Float = 20f + 32f + 16f
