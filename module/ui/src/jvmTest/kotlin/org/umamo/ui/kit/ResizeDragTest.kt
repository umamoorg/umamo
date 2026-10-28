package org.umamo.ui.kit

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.umamo.ui.theme.UmamoTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** The test tag on the box the grip is mounted in. */
private const val ROOT_TAG = "root"

/** The smallest size the grip may set. */
private val GRIP_MIN = 50.dp

/** The largest size the grip may set. */
private val GRIP_MAX = 150.dp

/** Where the press lands, on the grip in the root's top-left corner. */
private val GRIP_POINT = Offset(5f, 5f)

/**
 * The resize grip: a drag changes the size within its limits, and a grip given a collapse size collapses once
 * when the size the pointer asks for falls below it.
 */
class ResizeDragTest {
	/**
	 * Mounts a 10 x 10 grip at density 1 in the corner of a 200 x 200 box, sizing [size] between [GRIP_MIN] and
	 * [GRIP_MAX]; dragging right grows it.
	 *
	 * @param MutableState size The size the grip sets.
	 * @param Dp? collapseBelow The grip's collapse size, or null for none.
	 * @param IntArray collapses Counts the collapses in its one slot.
	 */
	@OptIn(ExperimentalTestApi::class)
	private fun ComposeUiTest.mountGrip(size: MutableState<Dp>, collapseBelow: Dp?, collapses: IntArray) {
		setContent {
			UmamoTheme {
				CompositionLocalProvider(LocalDensity provides Density(1f)) {
					Box(modifier = Modifier.size(200.dp).testTag(ROOT_TAG)) {
						Box(
							modifier =
								Modifier.size(10.dp).dragResizes(
									size = { size.value },
									min = GRIP_MIN,
									max = GRIP_MAX,
									orientation = Orientation.Horizontal,
									hoverIcon = PointerIcon.Default,
									onResize = { resized -> size.value = resized },
									collapseBelow = collapseBelow,
									onCollapse = { collapses[0]++ },
								),
						)
					}
				}
			}
		}
		waitForIdle()
	}

	/**
	 * Drags the grip horizontally through [through], each an offset from where the press lands.
	 *
	 * @param List through The x offsets moved through, in order.
	 */
	@OptIn(ExperimentalTestApi::class)
	private fun ComposeUiTest.dragThrough(through: List<Float>) {
		onNodeWithTag(ROOT_TAG).performMouseInput {
			moveTo(GRIP_POINT)
			press()

			for (offset in through) {
				moveTo(GRIP_POINT + Offset(offset, 0f))
			}

			release()
		}
		waitForIdle()
	}

	/** A drag past either limit stops at it, and a grip with no collapse size never collapses. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun aDragStopsAtTheLimits() =
		runComposeUiTest {
			val size = mutableStateOf(100.dp)
			val collapses = IntArray(1)
			mountGrip(size, collapseBelow = null, collapses = collapses)
			dragThrough(listOf(40f, 100f))
			assertEquals(GRIP_MAX, size.value)
			dragThrough(listOf(-100f, -200f, -300f))
			assertEquals(GRIP_MIN, size.value)
			assertEquals(0, collapses[0], "a grip with no collapse size never collapses")
		}

	/**
	 * A drag that asks for less than the collapse size collapses once, and the rest of the drag changes nothing.
	 * The size stops at the limit until then.
	 */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun aDragBelowTheCollapseSizeCollapsesOnce() =
		runComposeUiTest {
			val size = mutableStateOf(100.dp)
			val collapses = IntArray(1)
			mountGrip(size, collapseBelow = 30.dp, collapses = collapses)
			// 100 less 60 asks for 40: held at the limit, not yet collapsed.
			dragThrough(listOf(-40f, -60f))
			assertEquals(GRIP_MIN, size.value)
			assertEquals(0, collapses[0], "a drag that stops short of the collapse size does not collapse")
			// 50 less 30 asks for 20, then the drag turns back past the limit.
			dragThrough(listOf(-30f, -50f, 60f))
			assertEquals(1, collapses[0], "the drag collapses once")
			assertEquals(GRIP_MIN, size.value, "after the collapse the drag changes nothing")
		}
}