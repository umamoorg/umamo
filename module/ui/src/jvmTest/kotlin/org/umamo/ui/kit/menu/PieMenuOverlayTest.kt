package org.umamo.ui.kit.menu

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.menu_file_new
import org.umamo.ui.theme.UmamoTheme
import kotlin.math.abs
import kotlin.math.sign
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The pie numbers its choices clockwise from West, and a pick is by direction: the chip a number is drawn on sits
 * where that number's slot is, and a release in a direction picks the entry numbered there.  A pie with fewer than
 * eight choices fills the first slots and leaves the rest of the ring open.
 */
@OptIn(ExperimentalTestApi::class)
class PieMenuOverlayTest {
	/**
	 * Mounts a pie of [entryCount] choices centered in a 1200 x 800 box, each choice recording its number when picked.
	 *
	 * @param Int entryCount How many choices the pie has.
	 * @param MutableList<Int> picked Collects the number of each choice picked, from 1.
	 */
	private fun ComposeUiTest.mountPie(entryCount: Int, picked: MutableList<Int>) {
		val commands = CommandRegistry()
		val entries =
			(1..entryCount).map { number ->
				commands.register(Command("test.$number", title = null, handler = { picked.add(number) }))

				PieMenuEntry("test.$number", Res.string.menu_file_new)
			}
		setContent {
			UmamoTheme {
				CompositionLocalProvider(LocalCommands provides commands) {
					Box(modifier = Modifier.size(1200.dp, 800.dp).testTag(PIE_TAG)) {
						PieMenuOverlay(entries = entries, center = CENTER, onDismiss = {})
					}
				}
			}
		}
		waitForIdle()
	}

	/**
	 * Clicks [reach] pixels from the pie's center toward [direction], which is where a flick that way lets go.
	 *
	 * @param Offset direction The direction, as a step of -1, 0, or 1 along each axis (screen space, y down).
	 * @param Float reach How far from the center the click lands.
	 */
	private fun ComposeUiTest.clickToward(direction: Offset, reach: Float = 80f) {
		val length = direction.getDistance()
		onNodeWithTag(PIE_TAG).performMouseInput { click(CENTER + direction / length * reach) }
		waitForIdle()
	}

	/**
	 * Where the chip numbered [number] sits from the pie's center, as a step of -1, 0, or 1 along each axis.
	 *
	 * @param Int number The chip's number, from 1.
	 * @return Offset The direction.
	 */
	private fun ComposeUiTest.directionOfChip(number: Int): Offset {
		val bounds = onNodeWithText("$number", useUnmergedTree = true).getBoundsInRoot()
		val fromCenter = Offset((bounds.left.value + bounds.right.value) / 2f, (bounds.top.value + bounds.bottom.value) / 2f) - CENTER
		// A chip due North or South is centered on the pie, so its number sits a little to one side of the axis;
		// anything within a third of the ring is on it.
		val side = { coordinate: Float -> if (abs(coordinate) < 32f) 0f else sign(coordinate) }

		return Offset(side(fromCenter.x), side(fromCenter.y))
	}

	@Test
	fun theChipsAreNumberedClockwiseFromWest() =
		runComposeUiTest {
			mountPie(entryCount = 8, picked = ArrayList())

			val drawn = (1..8).map { number -> directionOfChip(number) }

			// The chips of the East half start at the ring and grow right, so each number sits at the far end of
			// its chip and its column is still East; only the row is asked of the diagonals.
			assertEquals(CLOCKWISE_FROM_WEST.map { direction -> direction.y }, drawn.map { direction -> direction.y }, "the rows: W, NW, N, NE, E, SE, S, SW")
			assertEquals(listOf(-1f, -1f), listOf(drawn[0].x, drawn[1].x), "1 and 2 are on the West side")
			assertEquals(listOf(1f, 1f, 1f), listOf(drawn[3].x, drawn[4].x, drawn[5].x), "4, 5, and 6 are on the East side")
			assertEquals(-1f, drawn[7].x, "and 8 is back on the West side")
		}

	/** A release in a direction picks the entry whose number is drawn there. */
	@Test
	fun aReleaseInADirectionPicksTheEntryNumberedThere() =
		runComposeUiTest {
			val picked = ArrayList<Int>()
			mountPie(entryCount = 8, picked)

			for (direction in CLOCKWISE_FROM_WEST) {
				clickToward(direction)
			}

			assertEquals((1..8).toList(), picked)
		}

	/** A release inside the dead zone picks nothing: it is how a pie is closed without choosing. */
	@Test
	fun aReleaseAtTheCenterPicksNothing() =
		runComposeUiTest {
			val picked = ArrayList<Int>()
			mountPie(entryCount = 8, picked)

			clickToward(Offset(1f, 0f), reach = 10f)

			assertEquals(emptyList(), picked)
		}

	/**
	 * A pie of three fills West, North-West, and North.  The open side of the ring is split between the two chips at
	 * its ends, each taking the half nearer to it, so a release anywhere still picks something.
	 */
	@Test
	fun aSmallPieFillsTheFirstSlotsAndSplitsTheOpenSide() =
		runComposeUiTest {
			val picked = ArrayList<Int>()
			mountPie(entryCount = 3, picked)

			clickToward(Offset(-1f, 0f))
			clickToward(Offset(-1f, -1f))
			clickToward(Offset(0f, -1f))

			assertEquals(listOf(1, 2, 3), picked, "W, NW, N")

			picked.clear()
			clickToward(Offset(1f, 0f))
			clickToward(Offset(0f, 1f))

			assertEquals(listOf(3, 1), picked, "East is nearer North's chip, South is nearer West's")
		}

	private companion object {
		/** The tag on the box the pie fills. */
		const val PIE_TAG = "pie"

		/** The pie's center in the box, far enough from every edge that nothing is clamped. */
		val CENTER = Offset(600f, 400f)

		/** The eight slots' directions in slot order, as a step along each axis (screen space, y down). */
		val CLOCKWISE_FROM_WEST =
			listOf(
				Offset(-1f, 0f),
				Offset(-1f, -1f),
				Offset(0f, -1f),
				Offset(1f, -1f),
				Offset(1f, 0f),
				Offset(1f, 1f),
				Offset(0f, 1f),
				Offset(-1f, 1f),
			)
	}
}