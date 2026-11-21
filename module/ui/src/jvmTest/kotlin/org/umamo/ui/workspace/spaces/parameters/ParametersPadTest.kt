package org.umamo.ui.workspace.spaces.parameters

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.umamo.edit.ParameterSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the linked pair's pad: a press jumps to its point, Ctrl latches each axis onto its keys by itself, a pad
 * gesture never retargets the keyform sheet, and a secondary press is left to the row's menu.
 *
 * Angle X and Angle Y both run -30..30.  The keyed rigs here key both at -15 and 15, clear of the center and the
 * edges, so a snap cannot pass for a clamp.
 */
@OptIn(ExperimentalTestApi::class)
class ParametersPadTest {
	/** A press previews the values at its point before the release, and the release commits both as one step. */
	@Test
	fun aPressJumpsToItsPoint() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val pad = padBox(harness, PanelRows.ANGLE_PAD, PanelValues.ANGLE_X, PanelValues.ANGLE_Y)
			val cursorBefore = harness.historyCursor

			pressAndMove(padPointOf(pad, 12f, 18f), emptyList())

			assertNear(12f, harness.live(PanelIds.angleX), "the press moves the handle under the pointer")
			assertNear(18f, harness.live(PanelIds.angleY), "on both axes")
			assertEquals(3f, harness.committed(PanelIds.angleX), "the press previews; only the release commits")

			releasePress()

			assertNear(12f, harness.committed(PanelIds.angleX), "the release commits where the press jumped")
			assertNear(18f, harness.committed(PanelIds.angleY), "on both axes")
			assertEquals(cursorBefore + 1, harness.historyCursor, "a press is one step for both axes")
		}

	/** With Ctrl held, a drag ending beside a crossing lands on both its keys. */
	@Test
	fun aCtrlDragNearACrossingLandsOnBothKeys() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(model = keyedAngles())
			mountParametersPanel(harness)
			val pad = padBox(harness, PanelRows.ANGLE_PAD, PanelValues.ANGLE_X, PanelValues.ANGLE_Y)
			val nudge = with(density) { NUDGE.toPx() }

			setCtrlHeld(true)
			drag(padPointOf(pad, 0f, 0f), listOf(padPointOf(pad, 10f, 10f), padPointOf(pad, 15f, 15f) + Offset(nudge, -nudge)))
			setCtrlHeld(false)

			assertEquals(15f, harness.committed(PanelIds.angleX), "the x key beside the release catches the handle")
			assertEquals(15f, harness.committed(PanelIds.angleY), "and so does the y key")
		}

	/** With Ctrl held, a press beside a crossing lands on it, as a drag does. */
	@Test
	fun aCtrlPressNearACrossingLandsOnIt() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(model = keyedAngles())
			mountParametersPanel(harness)
			val pad = padBox(harness, PanelRows.ANGLE_PAD, PanelValues.ANGLE_X, PanelValues.ANGLE_Y)
			val nudge = with(density) { NUDGE.toPx() }

			setCtrlHeld(true)
			clickAt(padPointOf(pad, -15f, -15f) + Offset(-nudge, nudge))
			setCtrlHeld(false)

			assertEquals(-15f, harness.committed(PanelIds.angleX))
			assertEquals(-15f, harness.committed(PanelIds.angleY))
		}

	/** Beside one key line and far from the other axis's keys, Ctrl latches that axis and leaves the other free. */
	@Test
	fun aCtrlDragNearOneLineLatchesThatAxisAlone() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(model = keyedAngles())
			mountParametersPanel(harness)
			val pad = padBox(harness, PanelRows.ANGLE_PAD, PanelValues.ANGLE_X, PanelValues.ANGLE_Y)
			val nudge = with(density) { NUDGE.toPx() }

			setCtrlHeld(true)
			drag(padPointOf(pad, 0f, 0f), listOf(padPointOf(pad, 10f, -2f), padPointOf(pad, 15f, -2f) + Offset(nudge, 0f)))
			setCtrlHeld(false)

			assertEquals(15f, harness.committed(PanelIds.angleX), "the line beside the release catches its axis")
			assertNear(-2f, harness.committed(PanelIds.angleY), "the other axis lands under the pointer")
		}

	/** The same drag without Ctrl lands under the pointer on both axes, a little past the keys. */
	@Test
	fun withoutCtrlAPadDragIgnoresTheKeys() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(model = keyedAngles())
			mountParametersPanel(harness)
			val pad = padBox(harness, PanelRows.ANGLE_PAD, PanelValues.ANGLE_X, PanelValues.ANGLE_Y)
			val nudge = with(density) { NUDGE.toPx() }

			drag(padPointOf(pad, 0f, 0f), listOf(padPointOf(pad, 10f, 10f), padPointOf(pad, 15f, 15f) + Offset(nudge, -nudge)))

			val x = harness.committed(PanelIds.angleX)
			val y = harness.committed(PanelIds.angleY)
			assertTrue(x != null && x > PAST_THE_KEY.start && x < PAST_THE_KEY.endInclusive, "no snap without Ctrl: x lands past its key, got $x")
			assertTrue(y != null && y > PAST_THE_KEY.start && y < PAST_THE_KEY.endInclusive, "nor does y, got $y")
		}

	/** Ctrl pressed after the drag began still snaps. */
	@Test
	fun ctrlJoiningAPadDragUnderWaySnaps() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(model = keyedAngles())
			mountParametersPanel(harness)
			val pad = padBox(harness, PanelRows.ANGLE_PAD, PanelValues.ANGLE_X, PanelValues.ANGLE_Y)
			val nudge = with(density) { NUDGE.toPx() }

			pressAndMove(padPointOf(pad, 0f, 0f), listOf(padPointOf(pad, 10f, 10f)))
			setCtrlHeld(true)
			moveOn(listOf(padPointOf(pad, 15f, 15f) + Offset(nudge, -nudge)))
			releasePress()
			setCtrlHeld(false)

			assertEquals(15f, harness.committed(PanelIds.angleX))
			assertEquals(15f, harness.committed(PanelIds.angleY))
		}

	/** A pad gesture is a pose gesture: neither a press nor a drag retargets the keyform sheet. */
	@Test
	fun aPadGestureDoesNotRetarget() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val pad = padBox(harness, PanelRows.ANGLE_PAD, PanelValues.ANGLE_X, PanelValues.ANGLE_Y)

			clickAt(padPointOf(pad, 10f, 10f))
			assertEquals(ParameterSelection(), harness.session.parameterSelection.value, "a press must not reach the island")

			drag(padPointOf(pad, -10f, -10f), listOf(padPointOf(pad, -5f, -5f), padPointOf(pad, 0f, 0f)))
			assertEquals(ParameterSelection(), harness.session.parameterSelection.value, "nor must a drag")
		}

	/** A secondary press on the pad opens the row's menu and leaves both axes where they were. */
	@Test
	fun aSecondaryPressOpensTheMenuAndMovesNothing() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val pad = padBox(harness, PanelRows.ANGLE_PAD, PanelValues.ANGLE_X, PanelValues.ANGLE_Y)
			val cursorBefore = harness.historyCursor

			secondaryClickAt(padPointOf(pad, 20f, 20f))

			assertEquals(3f, harness.live(PanelIds.angleX), "a secondary press must not move the handle")
			assertEquals(-4f, harness.live(PanelIds.angleY))
			assertEquals(3f, harness.committed(PanelIds.angleX))
			assertEquals(cursorBefore, harness.historyCursor, "nor record a step")
			assertTrue(popupShows(harness.text.rename), "the press opens the pad's menu")
		}

	private companion object {
		/** How far beside a key a pointer ends: inside the 6 dp snap reach on either axis of the pad. */
		val NUDGE = 3.dp

		/**
		 * Where an unsnapped release three dp past the key at 15 lands: a little past it on either axis, the pad
		 * being wider than it is tall, and never on the key.
		 */
		val PAST_THE_KEY = 15.2f..16.5f

		/**
		 * The fixture rig with both angle axes keyed at -15 and 15.
		 *
		 * @return PuppetModel The rig.
		 */
		fun keyedAngles() = panelFixtureModel(angleXKeys = floatArrayOf(-15f, 15f), angleYKeys = floatArrayOf(-15f, 15f))
	}
}