package org.umamo.ui.tracks

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.ui.kit.field.KEY_SNAP_RADIUS
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The lane's held-Ctrl snap: a scrub or a mark drag latches onto the owner's targets within reach, moves freely
 * out of it, reads Ctrl live so it can join a drag already under way, and leaves a click a click.
 *
 * The fixture's Opacity row (line 1) has marks at -30, 0, and 30 over a -30..30 axis; every target here sits at
 * 12, clear of all three, and every reach test stays half a unit from it, well inside the snap radius.
 */
@OptIn(ExperimentalTestApi::class)
class TrackLaneSnapTest {
	private val axis = TrackAxis(-30f, 30f)

	/** The Opacity row's display line while the group is expanded. */
	private val opacityLine = 1

	/**
	 * The point on the Opacity row's lane where [value] is drawn.
	 *
	 * @param Float value The domain value.
	 * @return Offset The point in sheet pixels.
	 */
	private fun opacityAt(value: Float): Offset = Offset(laneXOf(axis, value), rowCenterY(opacityLine))

	/**
	 * Presses at [from], moves through [through], and releases, all on the sheet.
	 *
	 * @param Offset from Where the press lands.
	 * @param List through The points the pointer moves through, in order.
	 */
	private fun ComposeUiTest.dragOnSheet(from: Offset, through: List<Offset>) {
		onNodeWithTag(SHEET_TAG).performMouseInput {
			moveTo(from)
			press()

			for (point in through) {
				moveTo(point)
			}

			release()
		}
		waitForIdle()
	}

	/**
	 * Holds Ctrl down, or lets it go.
	 *
	 * @param Boolean held Whether Ctrl ends up held.
	 */
	private fun ComposeUiTest.ctrl(held: Boolean) {
		onNodeWithTag(SHEET_TAG).performKeyInput {
			if (held) {
				keyDown(Key.CtrlLeft)
			} else {
				keyUp(Key.CtrlLeft)
			}
		}
		waitForIdle()
	}

	/** A Ctrl scrub that comes within reach of a target lands on it exactly, and the owner was asked once. */
	@Test
	fun aCtrlScrubLatchesOntoATargetWithinReach() =
		runComposeUiTest {
			val harness = TrackSheetHarness()
			mountTrackSheet(harness, axis = axis, snapTargets = { _, _ -> listOf(12f) })

			ctrl(held = true)
			dragOnSheet(opacityAt(-15f), listOf(opacityAt(5f), opacityAt(12.5f)))
			ctrl(held = false)

			assertEquals(12f, harness.scrubs.last().second, "the playhead is drawn on the target")
			assertEquals(12f, harness.scrubEnds.last().second, "and the release commits it")
			assertEquals(listOf(FixtureRows.OPACITY to null), harness.snapRequests.map { (row, mark) -> row.key to mark })
		}

	/** Without Ctrl the same scrub ignores the targets. */
	@Test
	fun withoutCtrlAScrubMovesFreely() =
		runComposeUiTest {
			val harness = TrackSheetHarness()
			mountTrackSheet(harness, axis = axis, snapTargets = { _, _ -> listOf(12f) })

			dragOnSheet(opacityAt(-15f), listOf(opacityAt(5f), opacityAt(12.5f)))

			val released = harness.scrubEnds.last().second
			assertNotEquals(12f, released)
			assertTrue(abs(released - 12.5f) < 0.2f, "the release lands under the pointer, at $released")
		}

	/** Out of reach of every target, a Ctrl scrub follows the pointer. */
	@Test
	fun aCtrlScrubOutOfReachMovesFreely() =
		runComposeUiTest {
			val harness = TrackSheetHarness()
			mountTrackSheet(harness, axis = axis, snapTargets = { _, _ -> listOf(12f) })

			ctrl(held = true)
			dragOnSheet(opacityAt(-15f), listOf(opacityAt(5f), opacityAt(18f)))
			ctrl(held = false)

			assertTrue(abs(harness.scrubEnds.last().second - 18f) < 0.2f)
		}

	/** Ctrl pressed after the drag began still snaps: the way a drag picks up Ctrl on macOS. */
	@Test
	fun ctrlJoiningADragUnderWaySnaps() =
		runComposeUiTest {
			val harness = TrackSheetHarness()
			mountTrackSheet(harness, axis = axis, snapTargets = { _, _ -> listOf(12f) })

			onNodeWithTag(SHEET_TAG).performMouseInput {
				moveTo(opacityAt(-15f))
				press()
				moveTo(opacityAt(5f))
			}
			ctrl(held = true)
			onNodeWithTag(SHEET_TAG).performMouseInput {
				moveTo(opacityAt(12.5f))
				release()
			}
			ctrl(held = false)

			assertEquals(12f, harness.scrubEnds.last().second)
		}

	/** A Ctrl drag of a mark draws it on the target and releases it there, with the pressed mark handed to the owner. */
	@Test
	fun aCtrlMarkDragLatchesOntoATarget() =
		runComposeUiTest {
			val harness = TrackSheetHarness()
			mountTrackSheet(harness, axis = axis, snapTargets = { _, _ -> listOf(12f) })

			ctrl(held = true)
			dragOnSheet(opacityAt(0f), listOf(opacityAt(6f), opacityAt(12.5f)))
			ctrl(held = false)

			assertEquals(12f, harness.markDrags.last().third, "the live drag sits on the target")
			assertEquals(12f, harness.markDragEnds.last().third, "and so does the release")
			assertEquals(0f, harness.snapRequests.single().second?.position, "the owner is asked about the mark pressed")
		}

	/** A Ctrl press on a mark that does not move is still a plain click on it. */
	@Test
	fun aCtrlClickOnAMarkIsStillAClick() =
		runComposeUiTest {
			val harness = TrackSheetHarness()
			mountTrackSheet(harness, axis = axis, snapTargets = { _, _ -> listOf(12f) })

			ctrl(held = true)
			dragOnSheet(opacityAt(0f), emptyList())
			ctrl(held = false)

			assertEquals(0f, harness.markClicks.single().second.position)
			assertTrue(harness.scrubs.isEmpty(), "a click on a mark is no scrub")
		}

	/** Without Ctrl the owner is never asked for targets, for a scrub, a mark drag, or a click. */
	@Test
	fun withoutCtrlTheOwnerIsNeverAsked() =
		runComposeUiTest {
			val harness = TrackSheetHarness()
			mountTrackSheet(harness, axis = axis, snapTargets = { _, _ -> listOf(12f) })

			dragOnSheet(opacityAt(-15f), listOf(opacityAt(5f), opacityAt(12.5f)))
			dragOnSheet(opacityAt(0f), listOf(opacityAt(6f), opacityAt(9f)))
			dragOnSheet(opacityAt(-30f), emptyList())

			assertEquals(1, harness.scrubEnds.size, "the scrub ran")
			assertEquals(1, harness.markDragEnds.size, "and so did the mark drag")
			assertEquals(1, harness.markClicks.size, "and the click")
			assertTrue(harness.snapRequests.isEmpty(), "but nothing asked for targets: ${harness.snapRequests}")
		}

	/**
	 * A target just past the visible window never latches: the value would be clamped back onto the window's
	 * edge, which is neither the target nor the pointer.  The press and the drag both land under the pointer.
	 */
	@Test
	fun aTargetPastTheWindowEdgeNeverLatches() =
		runComposeUiTest {
			val window = TrackAxis(-30f, 10f)
			val reach = laneReach(window, laneWidth().toInt(), TRACK_MARK_RADIUS.value, KEY_SNAP_RADIUS.value)
			val pointer = 10f - 0.5f * reach
			val harness = TrackSheetHarness()
			mountTrackSheet(harness, axis = window, snapTargets = { _, _ -> listOf(10f + 0.3f * reach) })
			val pointAt = { value: Float -> Offset(laneXOf(window, value), rowCenterY(opacityLine)) }

			ctrl(held = true)
			dragOnSheet(pointAt(pointer), emptyList())
			val pressed = harness.scrubs.single().second
			dragOnSheet(pointAt(-15f), listOf(pointAt(5f), pointAt(pointer)))
			ctrl(held = false)

			assertTrue(abs(pressed - pointer) < 0.25f * reach, "the press lands under the pointer at $pointer, at $pressed")
			val released = harness.scrubEnds.last().second
			assertTrue(abs(released - pointer) < 0.25f * reach, "the drag lands under the pointer at $pointer, at $released")
		}

	/** An owner that offers no targets gets no snapping, Ctrl or not. */
	@Test
	fun aSheetWithNoTargetsNeverSnaps() =
		runComposeUiTest {
			val harness = TrackSheetHarness()
			mountTrackSheet(harness, axis = axis)

			ctrl(held = true)
			dragOnSheet(opacityAt(-15f), listOf(opacityAt(-0.5f)))
			ctrl(held = false)

			assertNotEquals(0f, harness.scrubEnds.last().second, "the mark at 0 is no target unless the owner says so")
		}
}