package org.umamo.ui.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The box gesture's rules under real pointer input, over a surface that only records what the flow asked
 * of it: what starts a box, what lands or abandons it, what a click and a right-click do armed and not,
 * and what reaches the surface with no box in flight.
 */
@OptIn(ExperimentalTestApi::class)
class BoxGestureFlowTest {
	/** What the flow asked of the surface, in order; drags are many and are filtered out by [askedExceptDrags]. */
	private val asked = mutableListOf<String>()

	/** The corners the last landed box carried. */
	private var landed: Pair<Offset, Offset>? = null

	/** What the surface answers a press test with. */
	private var pressSelectsAnswer = false

	/** The event types the flow consumed, and the ones it left alone. */
	private val consumed = mutableListOf<String>()
	private val unconsumed = mutableListOf<String>()

	private var armed by mutableStateOf(false)
	private var mounted by mutableStateOf(true)
	private lateinit var flow: BoxGestureFlow<Unit>

	private val surface =
		object : BoxGestureSurface<Unit> {
			override fun beginBox(position: Offset) {
				asked += "begin@${position.x.toInt()},${position.y.toInt()}"
			}

			override fun dragBox(position: Offset) {
				asked += "drag"
			}

			override fun landBox(start: Offset, end: Offset, additive: Boolean, frame: Unit) {
				asked += if (additive) "land+" else "land"
				landed = start to end
			}

			override fun abandonBox() {
				asked += "abandon"
			}

			override fun click(event: PointerEvent, change: PointerInputChange) {
				asked += "click"
			}

			override fun disarm() {
				asked += "disarm"
			}

			override fun pressSelects(event: PointerEvent, change: PointerInputChange, frame: Unit): Boolean {
				if (pressSelectsAnswer) {
					asked += "selected"
				}
				return pressSelectsAnswer
			}

			override fun idleShiftSecondary(change: PointerInputChange, frame: Unit) {
				asked += "idleShift"
			}

			override fun setGestureActive(active: Boolean) {
				asked += if (active) "active" else "inactive"
			}
		}

	/**
	 * Mounts a 300 x 200 target whose pointer loop feeds every event to a fresh flow, reading the armed
	 * flag per event; the mounted flag lets a test take the target out of composition mid-drag.
	 *
	 * @param ComposeUiTest test The running UI test.
	 */
	private fun mount(test: ComposeUiTest) {
		flow = BoxGestureFlow(surface)
		test.setContent {
			if (mounted) {
				Box(
					modifier =
						Modifier
							.size(width = 300.dp, height = 200.dp)
							.testTag(TARGET_TAG)
							.pointerInput(Unit) {
								awaitPointerEventScope {
									while (true) {
										val event = awaitPointerEvent()
										val change = event.changes.firstOrNull() ?: continue
										flow.handleEvent(event, change, armed, Unit)
										if (change.isConsumed) {
											consumed += event.type.toString()
										} else {
											unconsumed += event.type.toString()
										}
									}
								}
							},
				)
			}
		}
	}

	/** What was asked, minus the per-move drags. */
	private fun askedExceptDrags(): List<String> = asked.filter { entry -> entry != "drag" }

	/** A primary drag starts the box, rubber-bands it, and lands it on the release, with the flag up in between. */
	@Test
	fun aDragLandsTheBox() =
		runComposeUiTest {
			mount(this)
			onNodeWithTag(TARGET_TAG).performMouseInput {
				moveTo(Offset(100f, 40f))
				press()
				moveTo(Offset(200f, 120f))
				release()
			}
			waitForIdle()
			assertEquals(listOf("begin@100,40", "active", "land", "inactive"), askedExceptDrags())
			assertEquals(Offset(100f, 40f) to Offset(200f, 120f), landed)
			assertTrue(asked.contains("drag"), "the move reached the band")
			assertEquals(listOf("Press", "Move", "Release"), consumed.distinct())
		}

	/** Shift at the RELEASE makes the landing additive, whatever was held at the press. */
	@Test
	fun shiftAtTheReleaseLandsAdditively() =
		runComposeUiTest {
			mount(this)
			onNodeWithTag(TARGET_TAG).performMouseInput {
				moveTo(Offset(100f, 40f))
				press()
				moveTo(Offset(200f, 120f))
			}
			onNodeWithTag(TARGET_TAG).performKeyInput { keyDown(Key.ShiftLeft) }
			onNodeWithTag(TARGET_TAG).performMouseInput { release() }
			onNodeWithTag(TARGET_TAG).performKeyInput { keyUp(Key.ShiftLeft) }
			waitForIdle()
			assertEquals(listOf("begin@100,40", "active", "land+", "inactive"), askedExceptDrags())
		}

	/** Un-armed, a release under the click threshold is the surface's click, and nothing lands. */
	@Test
	fun aSubThresholdReleaseIsAClickUnArmed() =
		runComposeUiTest {
			mount(this)
			onNodeWithTag(TARGET_TAG).performMouseInput {
				moveTo(Offset(100f, 40f))
				press()
				moveTo(Offset(101f, 41f))
				release()
			}
			waitForIdle()
			assertEquals(listOf("begin@100,40", "active", "inactive", "click"), askedExceptDrags())
		}

	/** Armed, the same sub-threshold release only disarms: no click, no landing, the selection untouched. */
	@Test
	fun aSubThresholdReleaseOnlyDisarmsArmed() =
		runComposeUiTest {
			armed = true
			mount(this)
			onNodeWithTag(TARGET_TAG).performMouseInput {
				moveTo(Offset(100f, 40f))
				press()
				moveTo(Offset(101f, 41f))
				release()
			}
			waitForIdle()
			assertEquals(listOf("begin@100,40", "active", "inactive", "disarm"), askedExceptDrags())
		}

	/** An armed box is one-shot: it lands and then disarms. */
	@Test
	fun anArmedReleaseDisarmsAfterLanding() =
		runComposeUiTest {
			armed = true
			mount(this)
			onNodeWithTag(TARGET_TAG).performMouseInput {
				moveTo(Offset(100f, 40f))
				press()
				moveTo(Offset(200f, 120f))
				release()
			}
			waitForIdle()
			assertEquals(listOf("begin@100,40", "active", "land", "inactive", "disarm"), askedExceptDrags())
		}

	/** A right-click mid-drag abandons the box (consumed), and the primary release afterwards lands nothing. */
	@Test
	fun aRightClickMidDragAbandons() =
		runComposeUiTest {
			mount(this)
			onNodeWithTag(TARGET_TAG).performMouseInput {
				moveTo(Offset(100f, 40f))
				press()
				moveTo(Offset(200f, 120f))
				press(MouseButton.Secondary)
				release(MouseButton.Secondary)
				release()
			}
			waitForIdle()
			assertEquals(listOf("begin@100,40", "active", "abandon", "inactive"), askedExceptDrags())
			assertEquals(2, consumed.count { type -> type == "Press" }, "the primary press and the right-click are both the flow's")
		}

	/** Armed, the mid-drag right-click abandons AND disarms. */
	@Test
	fun aRightClickMidArmedDragAbandonsAndDisarms() =
		runComposeUiTest {
			armed = true
			mount(this)
			onNodeWithTag(TARGET_TAG).performMouseInput {
				moveTo(Offset(100f, 40f))
				press()
				moveTo(Offset(200f, 120f))
				press(MouseButton.Secondary)
				release(MouseButton.Secondary)
				release()
			}
			waitForIdle()
			assertEquals(listOf("begin@100,40", "active", "abandon", "inactive", "disarm"), askedExceptDrags())
		}

	/** With no box in flight, an armed right-click disarms. */
	@Test
	fun anIdleArmedRightClickDisarms() =
		runComposeUiTest {
			armed = true
			mount(this)
			onNodeWithTag(TARGET_TAG).performMouseInput {
				moveTo(Offset(100f, 40f))
				press(MouseButton.Secondary)
				release(MouseButton.Secondary)
			}
			waitForIdle()
			assertEquals(listOf("disarm"), askedExceptDrags())
		}

	/** With no box in flight and nothing armed, Shift+RightClick reaches the surface and a bare right-click does not. */
	@Test
	fun anIdleShiftRightClickReachesTheSurface() =
		runComposeUiTest {
			mount(this)
			onNodeWithTag(TARGET_TAG).performMouseInput {
				moveTo(Offset(100f, 40f))
				press(MouseButton.Secondary)
				release(MouseButton.Secondary)
			}
			waitForIdle()
			assertEquals(emptyList(), askedExceptDrags(), "a bare right-click is not the flow's")
			assertTrue(unconsumed.contains("Press"), "and it falls through")
			onNodeWithTag(TARGET_TAG).performKeyInput { keyDown(Key.ShiftLeft) }
			onNodeWithTag(TARGET_TAG).performMouseInput {
				press(MouseButton.Secondary)
				release(MouseButton.Secondary)
			}
			onNodeWithTag(TARGET_TAG).performKeyInput { keyUp(Key.ShiftLeft) }
			waitForIdle()
			assertEquals(listOf("idleShift"), askedExceptDrags())
		}

	/** An un-armed press the surface selects on starts no box; armed, the press is never offered to the surface. */
	@Test
	fun aSelectingPressStartsNoBox() =
		runComposeUiTest {
			pressSelectsAnswer = true
			mount(this)
			onNodeWithTag(TARGET_TAG).performMouseInput {
				moveTo(Offset(100f, 40f))
				press()
				moveTo(Offset(200f, 120f))
				release()
			}
			waitForIdle()
			assertEquals(listOf("selected"), askedExceptDrags())
			assertTrue(consumed.contains("Press"), "the selecting press is consumed")
			assertFalse(asked.contains("drag"), "and no band follows the pointer")

			asked.clear()
			armed = true
			onNodeWithTag(TARGET_TAG).performMouseInput {
				moveTo(Offset(100f, 40f))
				press()
				moveTo(Offset(200f, 120f))
				release()
			}
			waitForIdle()
			assertEquals(listOf("begin@100,40", "active", "land", "inactive", "disarm"), askedExceptDrags())
		}

	/** The tool arming or clearing under an in-flight box abandons it; the release afterwards lands nothing. */
	@Test
	fun anArmedChangeMidDragAbandons() =
		runComposeUiTest {
			mount(this)
			onNodeWithTag(TARGET_TAG).performMouseInput {
				moveTo(Offset(100f, 40f))
				press()
				moveTo(Offset(150f, 80f))
			}
			runOnIdle { armed = true }
			waitForIdle()
			onNodeWithTag(TARGET_TAG).performMouseInput {
				moveTo(Offset(200f, 120f))
				release()
			}
			waitForIdle()
			assertEquals(listOf("begin@100,40", "active", "abandon", "inactive"), askedExceptDrags())
		}

	/**
	 * A pointer loop whose node leaves composition under a pressed pointer gets a synthetic release that
	 * arrives already consumed; the flow abandons the box on it rather than landing a band the user never
	 * let go of.
	 */
	@Test
	fun unmountingMidDragAbandons() =
		runComposeUiTest {
			mount(this)
			onNodeWithTag(TARGET_TAG).performMouseInput {
				moveTo(Offset(100f, 40f))
				press()
				moveTo(Offset(150f, 80f))
			}
			runOnIdle { mounted = false }
			waitForIdle()
			assertEquals(listOf("begin@100,40", "active", "abandon", "inactive"), askedExceptDrags())
			assertFalse(asked.contains("land"), "nothing landed on the way out")
		}

	/** A middle press is the navigation layer's: it starts nothing and is left unconsumed. */
	@Test
	fun aMiddlePressStartsNothing() =
		runComposeUiTest {
			mount(this)
			onNodeWithTag(TARGET_TAG).performMouseInput {
				moveTo(Offset(100f, 40f))
				press(MouseButton.Tertiary)
				moveTo(Offset(200f, 120f))
				release(MouseButton.Tertiary)
			}
			waitForIdle()
			assertEquals(emptyList(), asked)
			assertTrue(unconsumed.contains("Press"))
		}

	/** cancel() drops an in-flight box and is a no-op with none in flight. */
	@Test
	fun cancelDropsTheBoxAndIsANoOpIdle() =
		runComposeUiTest {
			mount(this)
			runOnIdle { flow.cancel() }
			assertEquals(emptyList(), asked, "nothing in flight, nothing asked")
			onNodeWithTag(TARGET_TAG).performMouseInput {
				moveTo(Offset(100f, 40f))
				press()
				moveTo(Offset(150f, 80f))
			}
			runOnIdle { flow.cancel() }
			assertEquals(listOf("begin@100,40", "active", "abandon", "inactive"), askedExceptDrags())
			runOnIdle { flow.cancel() }
			assertEquals(listOf("begin@100,40", "active", "abandon", "inactive"), askedExceptDrags(), "a second cancel asks nothing")
			onNodeWithTag(TARGET_TAG).performMouseInput { release() }
			waitForIdle()
			assertFalse(asked.contains("land"))
		}
}

/** The test tag of the box carrying the flow's pointer loop. */
private const val TARGET_TAG = "box-gesture"