package org.umamo.ui.kit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.umamo.ui.theme.UmamoTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** The test tag on the box everything is mounted in. */
private const val ROOT_TAG = "root"

/** A point on the button inside the shield. */
private val ON_BUTTON = Offset(25f, 25f)

/** A point inside the shield but off its button. */
private val OFF_BUTTON = Offset(75f, 75f)

/**
 * What the presses landed on: the surface beneath the shield and the button inside it.
 *
 * @property Int surfacePresses The presses the surface beneath received.
 * @property Int buttonClicks The clicks the button inside the shield handled.
 */
private class Landings(var surfacePresses: Int = 0, var buttonClicks: Int = 0)

/**
 * The pointer shield: blocking, its own controls answer the pointer and the space between them keeps it
 * from the surface beneath; passing through, the surface gets every press and the controls stay inert.
 */
class PointerShieldTest {
	/**
	 * Mounts a 200 x 200 surface with a 100 x 100 shield in its top-left corner, holding a 50 x 50 button in
	 * the shield's own top-left corner, at density 1.
	 *
	 * @param Boolean passPointerThrough Whether the shield lets the pointer through.
	 * @param Landings landings Where the presses are counted.
	 */
	@OptIn(ExperimentalTestApi::class)
	private fun ComposeUiTest.mountShield(passPointerThrough: Boolean, landings: Landings) {
		setContent {
			UmamoTheme {
				CompositionLocalProvider(LocalDensity provides Density(1f)) {
					Box(modifier = Modifier.size(200.dp).testTag(ROOT_TAG)) {
						Box(
							modifier =
								Modifier.fillMaxSize().pointerInput(Unit) {
									awaitPointerEventScope {
										while (true) {
											val event = awaitPointerEvent()

											if (event.type == PointerEventType.Press) {
												landings.surfacePresses++
											}
										}
									}
								},
						)
						PointerShield(passPointerThrough = passPointerThrough, modifier = Modifier.size(100.dp)) {
							Box(modifier = Modifier.size(50.dp).clickable { landings.buttonClicks++ })
						}
					}
				}
			}
		}
		waitForIdle()
	}

	/**
	 * Clicks the primary button at [point].
	 *
	 * @param Offset point Where to click, in the root's px.
	 */
	@OptIn(ExperimentalTestApi::class)
	private fun ComposeUiTest.clickAt(point: Offset) {
		onNodeWithTag(ROOT_TAG).performMouseInput { click(point) }
		waitForIdle()
	}

	/** Blocking, the button handles its click and the empty space beside it keeps the surface from the pointer. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun aBlockingShieldKeepsThePointer() =
		runComposeUiTest {
			val landings = Landings()
			mountShield(passPointerThrough = false, landings = landings)
			clickAt(ON_BUTTON)
			clickAt(OFF_BUTTON)
			assertEquals(1, landings.buttonClicks, "the button handles its own click")
			assertEquals(0, landings.surfacePresses, "nothing inside the shield reaches the surface")
		}

	/** Passing through, every press reaches the surface and the button handles none. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun aPassingShieldHandsThePointerOn() =
		runComposeUiTest {
			val landings = Landings()
			mountShield(passPointerThrough = true, landings = landings)
			clickAt(ON_BUTTON)
			clickAt(OFF_BUTTON)
			assertEquals(2, landings.surfacePresses, "the surface receives both presses")
			assertEquals(0, landings.buttonClicks, "the button under the cover stays inert")
		}
}