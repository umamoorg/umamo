package org.umamo.ui.kit.field

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.DrawableId
import org.umamo.ui.model.SelectionHandle
import org.umamo.ui.theme.UmamoTheme
import org.umamo.ui.workspace.ShellOverlayState
import org.umamo.ui.workspace.shell.ShellModalState
import org.umamo.ui.workspace.shell.handleModalKeyLadder
import org.umamo.ui.workspace.shell.toShellKeyStroke
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A drag-scrub under the real key ladder: Escape cancels the scrub - the field returns to its value, nothing
 * commits, and the selection that put the field on screen survives - instead of falling through to the
 * arms that would unmount the field mid-drag.
 *
 * Driven with real presses and real key events because the bug lived between the two: the field claimed no
 * key, so the shell's clear-selection branch took Escape, the row left the composition, and the gesture's
 * cancel committed the draft.
 */
@OptIn(ExperimentalTestApi::class)
class NumberFieldScrubCancelTest {
	/** The shell-side state one case shares between its composition and its assertions. */
	private class Harness {
		val overlays = ShellOverlayState()
		val scrubCancel = ScrubCancelController()
		val selection = RecordingSelection()
		val rootFocus = FocusRequester()

		/** Whether the root itself holds focus. */
		var rootFocused by mutableStateOf(false)

		/** Every value the field committed, in order. */
		val committed = ArrayList<Float>()

		/** Every value the field previewed, in order. */
		val previewed = ArrayList<Float>()

		/** How many times the field reported a cancelled scrub. */
		var cancels = 0

		/** Whether the field is in the composition; flipping it unmounts the field mid-drag. */
		var fieldMounted by mutableStateOf(true)
	}

	/** A selection handle over a mutable slot, non-empty to start, so the clear-selection arm's effect shows. */
	private class RecordingSelection : SelectionHandle {
		override var selection: Selection =
			SelectionTarget.Drawable(DrawableId("a")).let { target -> Selection(setOf(target), target) }
			private set

		/** How many times the shell replaced the selection. */
		var sets = 0

		override fun set(selection: Selection) {
			sets++
			this.selection = selection
		}
	}

	/** Escape restores the field's value, commits nothing, releases the seam, and leaves the selection alone. */
	@Test
	fun escapeCancelsTheScrubAndKeepsTheSelection() =
		runComposeUiTest {
			val harness = Harness()
			mount(this, harness)

			pressAndScrub(this)
			assertTrue(harness.previewed.isNotEmpty(), "precondition: the scrub previewed")
			onNodeWithText(START_TEXT).assertDoesNotExist()
			assertTrue(harness.rootFocused, "precondition: the root holds the keyboard, as the shell's does")

			pressEscape(this)

			onNodeWithText(START_TEXT).assertExists()
			assertEquals(1, harness.cancels)
			assertNull(harness.scrubCancel.cancel, "the seam is released with the scrub")
			assertEquals(0, harness.selection.sets, "Escape went to the scrub, not to the clear-selection arm")
			assertFalse(harness.selection.selection.isEmpty)
			release(this)
			assertTrue(harness.committed.isEmpty(), "and the release commits nothing")
		}

	/** After a cancel the rest of the drag is dead, and the next scrub is a fresh one that commits once. */
	@Test
	fun aCancelledDragIsDeadUntilReleasedAndTheNextScrubCommits() =
		runComposeUiTest {
			val harness = Harness()
			mount(this, harness)

			pressAndScrub(this)
			pressEscape(this)
			val previewsAtCancel = harness.previewed.size
			moveOn(this)
			assertEquals(previewsAtCancel, harness.previewed.size, "a cancelled drag previews nothing more")
			onNodeWithText(START_TEXT).assertExists()
			release(this)
			assertTrue(harness.committed.isEmpty())

			pressAndScrub(this)
			release(this)

			assertEquals(1, harness.committed.size, "the next scrub is a whole gesture of its own")
			assertTrue(harness.committed.single() > START_VALUE)
			assertEquals(1, harness.cancels, "and it reports no cancel")
		}

	/** With no scrub in flight, Escape is the shell's as before: here, the clear-selection arm. */
	@Test
	fun escapeWithNoScrubFallsThrough() =
		runComposeUiTest {
			val harness = Harness()
			mount(this, harness)

			pressEscape(this)

			assertEquals(1, harness.selection.sets)
			assertTrue(harness.selection.selection.isEmpty)
			assertEquals(0, harness.cancels)
		}

	/** A scrub inside a self-focused overlay cancels on Escape; the overlay closes on the next one. */
	@Test
	fun aScrubInsideAnOverlayCancelsBeforeTheOverlayCloses() =
		runComposeUiTest {
			val harness = Harness()
			harness.overlays.settingsVisible = true
			mount(this, harness)

			pressAndScrub(this)
			pressEscape(this)

			assertEquals(1, harness.cancels)
			assertTrue(harness.overlays.settingsVisible, "the overlay is still open")
			release(this)

			pressEscape(this)
			assertFalse(harness.overlays.settingsVisible, "the second Escape is the overlay's")
		}

	/** A field that leaves the composition mid-drag cancels: nothing lands, and the seam is released. */
	@Test
	fun unmountingMidScrubCancels() =
		runComposeUiTest {
			val harness = Harness()
			mount(this, harness)

			pressAndScrub(this)
			runOnIdle { harness.fieldMounted = false }
			waitForIdle()

			assertEquals(1, harness.cancels)
			assertNull(harness.scrubCancel.cancel)
			release(this)
			assertTrue(harness.committed.isEmpty())
		}

	/** A chevron press never takes focus off the root, so the chevron's disappearance strands nothing. */
	@Test
	fun aChevronPressLeavesTheRootFocused() =
		runComposeUiTest {
			val harness = Harness()
			mount(this, harness)
			val field = onNodeWithTag(FIELD_TAG).fetchSemanticsNode().boundsInRoot

			// Three strokes with a frame between: the chevron composes on hover, is pressed, then goes with
			// the hover - the sequence that strands focus on a focusable chevron.
			onNodeWithTag(ROOT_TAG).performMouseInput {
				advanceEventTime(GESTURE_GAP_MILLIS)
				moveTo(Offset(field.right - CHEVRON_INSET_PX, field.center.y))
			}
			waitForIdle()
			onNodeWithTag(ROOT_TAG).performMouseInput {
				advanceEventTime(GESTURE_STEP_MILLIS)
				press()
				advanceEventTime(GESTURE_STEP_MILLIS)
				release()
			}
			waitForIdle()
			onNodeWithTag(ROOT_TAG).performMouseInput {
				advanceEventTime(GESTURE_STEP_MILLIS)
				moveTo(Offset(field.right + CHEVRON_INSET_PX, field.bottom + CHEVRON_INSET_PX))
			}
			waitForIdle()

			assertEquals(listOf(START_VALUE + 1f), harness.committed, "precondition: the chevron stepped the value")
			assertTrue(harness.rootFocused, "the keyboard root still holds focus")
		}

	/**
	 * Mounts a scrubbable field under a root that behaves like the shell's keyboard root: focused, carrying
	 * the real modal ladder over the harness's state.
	 *
	 * @param ComposeUiTest test The running UI test.
	 * @param Harness harness The state this case shares with its composition.
	 */
	private fun mount(test: ComposeUiTest, harness: Harness) {
		test.setContent {
			UmamoTheme {
				CompositionLocalProvider(LocalScrubCancel provides harness.scrubCancel) {
					// The shell's root takes focus on open; performKeyInput delivers to the focused node and
					// focuses nothing itself.
					LaunchedEffect(Unit) {
						harness.rootFocus.requestFocus()
					}
					Box(
						modifier =
							Modifier
								.fillMaxSize()
								.testTag(ROOT_TAG)
								.onFocusChanged { focusState -> harness.rootFocused = focusState.isFocused }
								.focusRequester(harness.rootFocus)
								.focusable()
								.onPreviewKeyEvent { event ->
									handleModalKeyLadder(
										event.toShellKeyStroke(),
										ShellModalState(
											overlays = harness.overlays,
											scrubCancel = harness.scrubCancel,
											selection = harness.selection,
										),
									)
								},
					) {
						Column {
							if (harness.fieldMounted) {
								ScrubbableField(harness)
							}
						}
					}
				}
			}
		}
		test.waitForIdle()
	}

	/**
	 * The field under test, recording what it commits, previews, and cancels.
	 *
	 * @param Harness harness The recorder.
	 */
	@Composable
	private fun ScrubbableField(harness: Harness) {
		NumberField(
			value = START_VALUE,
			onValueChange = { committed -> harness.committed.add(committed) },
			range = 0f..1000f,
			modifier = Modifier.width(FIELD_WIDTH).testTag(FIELD_TAG),
			decimals = 1,
			showFill = false,
			onPreview = { previewed -> harness.previewed.add(previewed) },
			onScrubCancel = { harness.cancels++ },
		)
	}

	/**
	 * Presses on the field's center and drags right, leaving the button held.
	 *
	 * @param ComposeUiTest test The running UI test.
	 */
	private fun pressAndScrub(test: ComposeUiTest) {
		val field = test.onNodeWithTag(FIELD_TAG).fetchSemanticsNode().boundsInRoot
		test.onNodeWithTag(ROOT_TAG).performMouseInput {
			advanceEventTime(GESTURE_GAP_MILLIS)
			moveTo(field.center)
			press()
			for (stepIndex in 1..SCRUB_STEPS) {
				advanceEventTime(GESTURE_STEP_MILLIS)
				moveTo(field.center + Offset(SCRUB_PIXELS * stepIndex / SCRUB_STEPS, 0f))
			}
		}
		test.waitForIdle()
	}

	/**
	 * Drags further right with the button still held.
	 *
	 * @param ComposeUiTest test The running UI test.
	 */
	private fun moveOn(test: ComposeUiTest) {
		val field = test.onNodeWithTag(FIELD_TAG).fetchSemanticsNode().boundsInRoot
		test.onNodeWithTag(ROOT_TAG).performMouseInput {
			for (stepIndex in 1..SCRUB_STEPS) {
				advanceEventTime(GESTURE_STEP_MILLIS)
				moveTo(field.center + Offset(SCRUB_PIXELS + SCRUB_PIXELS * stepIndex / SCRUB_STEPS, 0f))
			}
		}
		test.waitForIdle()
	}

	/**
	 * Releases the held button.
	 *
	 * @param ComposeUiTest test The running UI test.
	 */
	private fun release(test: ComposeUiTest) {
		test.onNodeWithTag(ROOT_TAG).performMouseInput {
			advanceEventTime(GESTURE_STEP_MILLIS)
			release()
		}
		test.waitForIdle()
	}

	/**
	 * Sends Escape to the focused root, the way the window does.
	 *
	 * @param ComposeUiTest test The running UI test.
	 */
	private fun pressEscape(test: ComposeUiTest) {
		test.onNodeWithTag(ROOT_TAG).performKeyInput {
			keyDown(Key.Escape)
			keyUp(Key.Escape)
		}
		test.waitForIdle()
	}

	private companion object {
		const val ROOT_TAG = "root"
		const val FIELD_TAG = "field"
		const val START_VALUE = 10f
		const val START_TEXT = "10.0"
		val FIELD_WIDTH = 120.dp

		/** Far enough past the drag slop to move the value by several steps. */
		const val SCRUB_PIXELS = 90f
		const val SCRUB_STEPS = 6

		/** Inside the 16dp chevron at the field's right edge, and past it to end the hover. */
		const val CHEVRON_INSET_PX = 6f

		const val GESTURE_STEP_MILLIS = 16L
		const val GESTURE_GAP_MILLIS = 1_000L
	}
}