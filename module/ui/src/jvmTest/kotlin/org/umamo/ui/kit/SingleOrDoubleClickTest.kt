package org.umamo.ui.kit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The press-timing modifier under real pointer input: what reaches the single action, what reaches the
 * double action, and what reaches neither.
 */
@OptIn(ExperimentalTestApi::class)
class SingleOrDoubleClickTest {
	/** What the modifier dispatched, in order: "single", "single+ctrl", "single+shift", or "double". */
	private val dispatched = mutableListOf<String>()

	/**
	 * Mounts a 200 x 100 target carrying the modifier, with a 40 x 40 child in its top-left corner that
	 * consumes its own presses the way the outliner's chevron and eye do.
	 *
	 * @param ComposeUiTest test    The running UI test.
	 * @param Function      enabled Reads whether the modifier is enabled.
	 */
	private fun mount(test: ComposeUiTest, enabled: () -> Boolean = { true }) {
		test.setContent {
			Box(
				modifier =
					Modifier
						.size(width = 200.dp, height = 100.dp)
						.testTag(TARGET_TAG)
						.singleOrDoubleClick(
							enabled = enabled(),
							onSingle = { modifiers ->
								dispatched +=
									when {
										modifiers.isCtrlPressed -> "single+ctrl"
										modifiers.isShiftPressed -> "single+shift"
										else -> "single"
									}
							},
							onDouble = { dispatched += "double" },
						),
			) {
				Box(
					modifier =
						Modifier
							.align(Alignment.TopStart)
							.size(40.dp)
							.pointerInput(Unit) {
								awaitPointerEventScope {
									while (true) {
										val event = awaitPointerEvent()

										if (event.type == PointerEventType.Press) {
											event.changes.forEach { change -> change.consume() }
										}
									}
								}
							},
				)
			}
		}
	}

	/** The single action fires on the press itself, before the release. */
	@Test
	fun theSingleActionFiresOnPress() =
		runComposeUiTest {
			mount(this)
			onNodeWithTag(TARGET_TAG).performMouseInput {
				moveTo(Offset(150f, 50f))
				press()
			}
			waitForIdle()
			assertEquals(listOf("single"), dispatched, "dispatched before any release")
			onNodeWithTag(TARGET_TAG).performMouseInput { release() }
		}

	/** A second press inside the window runs the double action after the single one. */
	@Test
	fun twoQuickClicksRunSingleThenDouble() =
		runComposeUiTest {
			mount(this)
			onNodeWithTag(TARGET_TAG).performMouseInput {
				click(Offset(150f, 50f))
				advanceEventTime(100L)
				click(Offset(150f, 50f))
			}
			waitForIdle()
			assertEquals(listOf("single", "double"), dispatched)
		}

	/** A right press is the context-menu gesture and a child's consumed press is the child's own. */
	@Test
	fun secondaryAndConsumedPressesAreIgnored() =
		runComposeUiTest {
			mount(this)
			onNodeWithTag(TARGET_TAG).performMouseInput {
				moveTo(Offset(150f, 50f))
				press(MouseButton.Secondary)
				release(MouseButton.Secondary)
				advanceEventTime(500L)
				click(Offset(20f, 20f))
			}
			waitForIdle()
			assertEquals(emptyList(), dispatched)
		}

	/** A disabled modifier handles nothing, and turning it back on starts with no pending press. */
	@Test
	fun aDisabledModifierHandlesNothing() =
		runComposeUiTest {
			var enabled by mutableStateOf(false)
			mount(this) { enabled }
			onNodeWithTag(TARGET_TAG).performMouseInput { click(Offset(150f, 50f)) }
			waitForIdle()
			assertEquals(emptyList(), dispatched, "nothing while disabled")

			enabled = true
			waitForIdle()
			onNodeWithTag(TARGET_TAG).performMouseInput { click(Offset(150f, 50f)) }
			waitForIdle()
			assertEquals(listOf("single"), dispatched, "the first press after enabling is a single")
		}

	/**
	 * A press holding a selection modifier reaches the single action with that modifier, and never takes
	 * part in a double click on either side.
	 */
	@Test
	fun aModifiedPressIsAlwaysASingle() =
		runComposeUiTest {
			mount(this)
			onNodeWithTag(TARGET_TAG).performKeyInput { keyDown(Key.CtrlLeft) }
			onNodeWithTag(TARGET_TAG).performMouseInput {
				click(Offset(150f, 50f))
				advanceEventTime(100L)
				click(Offset(150f, 50f))
			}
			onNodeWithTag(TARGET_TAG).performKeyInput { keyUp(Key.CtrlLeft) }
			onNodeWithTag(TARGET_TAG).performMouseInput {
				advanceEventTime(100L)
				click(Offset(150f, 50f))
			}
			onNodeWithTag(TARGET_TAG).performKeyInput { keyDown(Key.ShiftLeft) }
			onNodeWithTag(TARGET_TAG).performMouseInput {
				advanceEventTime(100L)
				click(Offset(150f, 50f))
			}
			onNodeWithTag(TARGET_TAG).performKeyInput { keyUp(Key.ShiftLeft) }
			waitForIdle()
			assertEquals(
				listOf("single+ctrl", "single+ctrl", "single", "single+shift"),
				dispatched,
				"Ctrl twice toggles twice, a plain click after Ctrl selects, and Shift never completes a double",
			)
		}

	/**
	 * Mounts the target inside a clickable, the way a control sits inside a surface with a click of its own.
	 *
	 * @param ComposeUiTest test         The running UI test.
	 * @param Boolean       consumePress Whether the target claims the presses it handles.
	 * @param Function      onAncestor   Runs when the clickable around the target takes a click.
	 */
	private fun mountInsideAClickable(test: ComposeUiTest, consumePress: Boolean, onAncestor: () -> Unit) {
		test.setContent {
			Box(modifier = Modifier.size(width = 300.dp, height = 200.dp).clickable(onClick = onAncestor)) {
				Box(
					modifier =
						Modifier
							.size(width = 200.dp, height = 100.dp)
							.testTag(TARGET_TAG)
							.singleOrDoubleClick(
								onSingle = { dispatched += "single" },
								onDouble = { dispatched += "double" },
								consumePress = consumePress,
							),
				)
			}
		}
	}

	/** Left alone, a handled press goes on up, and a clickable around the target clicks as well. */
	@Test
	fun anUnclaimedPressReachesAClickableAncestor() =
		runComposeUiTest {
			var ancestorClicks = 0
			mountInsideAClickable(this, consumePress = false) { ancestorClicks += 1 }

			// A clickable merges what sits inside it into one node, so the target is found in the unmerged tree.
			onNodeWithTag(TARGET_TAG, useUnmergedTree = true).performMouseInput { click(Offset(150f, 50f)) }
			waitForIdle()

			assertEquals(listOf("single"), dispatched)
			assertEquals(1, ancestorClicks)
		}

	/** A claimed press is the target's alone, on the single and on the double. */
	@Test
	fun aClaimedPressStaysFromAClickableAncestor() =
		runComposeUiTest {
			var ancestorClicks = 0
			mountInsideAClickable(this, consumePress = true) { ancestorClicks += 1 }

			// A clickable merges what sits inside it into one node, so the target is found in the unmerged tree.
			onNodeWithTag(TARGET_TAG, useUnmergedTree = true).performMouseInput {
				click(Offset(150f, 50f))
				advanceEventTime(100L)
				click(Offset(150f, 50f))
			}
			waitForIdle()

			assertEquals(listOf("single", "double"), dispatched)
			assertEquals(0, ancestorClicks)
		}

	/** A claim covers what the target handles and nothing else: a right press still goes on up. */
	@Test
	fun aClaimLeavesASecondaryPressAlone() =
		runComposeUiTest {
			var secondaryPresses = 0
			setContent {
				Box(
					modifier =
						Modifier
							.size(width = 300.dp, height = 200.dp)
							.pointerInput(Unit) {
								awaitPointerEventScope {
									while (true) {
										val event = awaitPointerEvent()
										val unconsumed = event.changes.none { change -> change.isConsumed }

										if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed && unconsumed) {
											secondaryPresses += 1
										}
									}
								}
							},
				) {
					Box(
						modifier =
							Modifier
								.size(width = 200.dp, height = 100.dp)
								.testTag(TARGET_TAG)
								.singleOrDoubleClick(onSingle = { dispatched += "single" }, onDouble = { dispatched += "double" }, consumePress = true),
					)
				}
			}

			onNodeWithTag(TARGET_TAG).performMouseInput {
				moveTo(Offset(150f, 50f))
				press(MouseButton.Secondary)
				release(MouseButton.Secondary)
			}
			waitForIdle()

			assertEquals(emptyList(), dispatched)
			assertEquals(1, secondaryPresses)
		}

	private companion object {
		const val TARGET_TAG = "target"
	}
}