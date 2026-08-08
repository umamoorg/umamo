package org.umamo.ui.tracks

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.ScrollWheel
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The sheet-wide zoom / pan gestures, driven with real pointer input over a real scroll container.
 *
 * The scroll container is the point: these gestures live on an ancestor of it, and the pass they claim
 * events on is what decides whether the wheel zooms or merely scrolls, and whether a middle press reaches
 * the pan at all or is eaten by the lanes underneath.  None of that is visible without the ancestor
 * relationship in place.
 */
class TrackWindowGestureTest {
	/** A middle-button drag over the track region pans the window. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun middleDragPansTheWindow() =
		runComposeUiTest {
			var window by mutableStateOf(TrackWindow(0.4f, 0.6f))
			setContent {
				Box(
					modifier =
						Modifier
							.size(width = 600.dp, height = 200.dp)
							.testTag("sheet")
							.trackWindowGestures(
								window = window,
								onWindowChange = { updated -> window = updated },
								labelColumnWidth = 100.dp,
							),
				) {
					// A scrolling child, exactly as the sheet has: the gestures must beat it to the event.
					Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
						Box(modifier = Modifier.size(width = 600.dp, height = 800.dp))
					}
				}
			}
			onNodeWithTag("sheet").performMouseInput {
				// Well right of the 100dp label column, then drag left, which moves the window right.
				moveTo(Offset(400f, 100f))
				press(MouseButton.Tertiary)
				moveTo(Offset(340f, 100f))
				moveTo(Offset(280f, 100f))
				release(MouseButton.Tertiary)
			}
			waitForIdle()
			assertTrue(window.start > 0.4f, "dragging left must move the window right (got $window)")
			assertTrue(window.span in 0.19f..0.21f, "a pan keeps the span (got ${window.span})")
		}

	/** A middle-button drag over the LABEL column is not a pan - that region is not the track. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun middleDragOverTheLabelsDoesNothing() =
		runComposeUiTest {
			var window by mutableStateOf(TrackWindow(0.4f, 0.6f))
			setContent {
				Box(
					modifier =
						Modifier
							.size(width = 600.dp, height = 200.dp)
							.testTag("sheet")
							.trackWindowGestures(
								window = window,
								onWindowChange = { updated -> window = updated },
								labelColumnWidth = 100.dp,
							),
				) {
					Box(modifier = Modifier.fillMaxSize().height(200.dp))
				}
			}
			onNodeWithTag("sheet").performMouseInput {
				moveTo(Offset(40f, 100f))
				press(MouseButton.Tertiary)
				moveTo(Offset(20f, 100f))
				release(MouseButton.Tertiary)
			}
			waitForIdle()
			assertTrue(window == TrackWindow(0.4f, 0.6f), "the label column does not pan (got $window)")
		}

	/** Ctrl+wheel zooms in about the pointer, and takes the event so the sheet underneath does not scroll. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun ctrlWheelZoomsWithoutScrolling() =
		runComposeUiTest {
			var window by mutableStateOf(TrackWindow.Full)
			lateinit var scrollState: ScrollState
			setContent {
				scrollState = rememberScrollState()
				Box(
					modifier =
						Modifier
							.size(width = 600.dp, height = 200.dp)
							.testTag("sheet")
							.trackWindowGestures(
								window = window,
								onWindowChange = { updated -> window = updated },
								labelColumnWidth = 100.dp,
							),
				) {
					Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState)) {
						Box(modifier = Modifier.size(width = 600.dp, height = 800.dp))
					}
				}
			}
			onNodeWithTag("sheet").performMouseInput { moveTo(Offset(400f, 100f)) }
			onNodeWithTag("sheet").performKeyInput { keyDown(Key.CtrlLeft) }
			// Negative is wheel-UP, which zooms in; positive zooms out, and from a fully framed window
			// that is correctly a no-op.
			onNodeWithTag("sheet").performMouseInput { scroll(-1f) }
			onNodeWithTag("sheet").performKeyInput { keyUp(Key.CtrlLeft) }
			waitForIdle()
			assertTrue(window.span < 1f, "Ctrl+wheel must zoom in (got $window)")
			assertEquals(0, scrollState.value, "and must not also scroll the sheet")
		}

	/** A plain wheel is left alone, so the sheet still scrolls vertically through its tracks. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun plainWheelStillScrolls() =
		runComposeUiTest {
			var window by mutableStateOf(TrackWindow.Full)
			lateinit var scrollState: ScrollState
			setContent {
				scrollState = rememberScrollState()
				Box(
					modifier =
						Modifier
							.size(width = 600.dp, height = 200.dp)
							.testTag("sheet")
							.trackWindowGestures(
								window = window,
								onWindowChange = { updated -> window = updated },
								labelColumnWidth = 100.dp,
							),
				) {
					Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState)) {
						Box(modifier = Modifier.size(width = 600.dp, height = 800.dp))
					}
				}
			}
			onNodeWithTag("sheet").performMouseInput {
				moveTo(Offset(400f, 100f))
				scroll(1f)
			}
			waitForIdle()
			assertEquals(TrackWindow.Full, window, "an unmodified wheel must not zoom")
			assertTrue(scrollState.value > 0, "it scrolls the sheet instead")
		}

	/** A horizontal wheel pans the tracks, with no modifier needed - nothing else uses that axis here. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun horizontalWheelPansTheTracks() =
		runComposeUiTest {
			var window by mutableStateOf(TrackWindow(0.4f, 0.6f))
			setContent {
				Box(
					modifier =
						Modifier
							.size(width = 600.dp, height = 200.dp)
							.testTag("sheet")
							.trackWindowGestures(
								window = window,
								onWindowChange = { updated -> window = updated },
								labelColumnWidth = 100.dp,
							),
				) {
					Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
						Box(modifier = Modifier.size(width = 600.dp, height = 800.dp))
					}
				}
			}
			onNodeWithTag("sheet").performMouseInput {
				moveTo(Offset(400f, 100f))
				scroll(1f, ScrollWheel.Horizontal)
			}
			waitForIdle()
			assertTrue(window.start > 0.4f, "a horizontal wheel must pan the window (got $window)")
			assertTrue(window.span in 0.19f..0.21f, "and keep its span (got ${window.span})")
		}
}