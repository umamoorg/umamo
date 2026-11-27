package org.umamo.ui.workspace.spaces.parameters

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.runtime.model.ParameterLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the Edit-mode lock.  Edit mode edits the neutral base mesh and is pinned to the neutral pose, so
 * no pose write may move the session out from under it.  The panel shows the rest pose there, as the
 * viewport does, and the pose Object mode left returns when Edit mode is left.
 *
 * What the lock covers is pose writes only.  A range, a link, a name, a create, and a delete are
 * document edits, and stay available.
 *
 * A control is found by the value it shows, and in Edit mode most rows show the same default, so a case
 * locates its control before it locks the panel.
 */
@OptIn(ExperimentalTestApi::class)
class ParametersEditLockTest {
	/**
	 * A harness mounted and put in Edit mode.
	 *
	 * @param ComposeUiTest test The running UI test.
	 * @param Boolean showHeader Whether to mount the header strip as well.
	 * @return ParametersPanelHarness The harness, in Edit mode.
	 */
	private fun lockedPanel(test: ComposeUiTest, showHeader: Boolean = false): ParametersPanelHarness {
		val harness = ParametersPanelHarness(showHeader = showHeader)
		test.mountParametersPanel(harness)
		lock(test, harness)

		return harness
	}

	/**
	 * Puts a mounted harness in Edit mode.
	 *
	 * @param ComposeUiTest test The running UI test.
	 * @param ParametersPanelHarness harness The mounted harness.
	 */
	private fun lock(test: ComposeUiTest, harness: ParametersPanelHarness) {
		test.runOnIdle { harness.enterEditMode() }
		test.waitForIdle()
	}

	/**
	 * Whether any row shows a value of the pose Object mode left, other than one that sits on its default.
	 *
	 * @param ComposeUiTest test The running UI test.
	 * @return Boolean True when one of those values shows.
	 */
	private fun showsTheObjectModePose(test: ComposeUiTest): Boolean =
		OBJECT_MODE_VALUES.any { valueText -> test.showsText(valueText) }

	/**
	 * Asserts the rows show the rest pose: no value of the pose Object mode left, and no reset glyph, since
	 * every row is on its default.
	 *
	 * @param ComposeUiTest test The running UI test.
	 * @param ParametersPanelHarness harness The mounted harness.
	 */
	private fun assertRowsShowRest(test: ComposeUiTest, harness: ParametersPanelHarness) {
		assertFalse(showsTheObjectModePose(test), "a locked panel shows the rest pose, as the viewport does")
		assertEquals(0, test.countOfDescription(harness.text.reset), "at rest every row is on its default")
	}

	/**
	 * Asserts nothing about the pose moved: not the session, not the renderer, not the row, not the history.
	 *
	 * @param ComposeUiTest test The running UI test.
	 * @param ParametersPanelHarness harness The mounted harness.
	 * @param Int cursorBefore The history position before the gesture.
	 */
	private fun assertPoseUntouched(test: ComposeUiTest, harness: ParametersPanelHarness, cursorBefore: Int) {
		assertEquals(PANEL_FIXTURE_POSE, harness.session.pose.value, "a locked panel must not write the session's pose")
		// Edit mode hands the renderer an empty pose; a preview would have put an entry in it.
		assertNull(harness.live(PanelIds.bodyX), "a locked panel must not preview")
		assertNull(harness.live(PanelIds.angleX))
		assertEquals(cursorBefore, harness.historyCursor, "a locked panel must not record a step")
		assertRowsShowRest(test, harness)
	}

	/** Locked, the rows show the rest pose; unlocked, the pose Object mode left is back. */
	@Test
	fun editModeShowsTheRestPoseAndGivesTheObjectPoseBack() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			assertTrue(OBJECT_MODE_VALUES.all { valueText -> showsText(valueText) }, "the fixture must open on the Object-mode values")

			lock(this, harness)
			assertRowsShowRest(this, harness)

			runOnIdle { harness.leaveEditMode() }
			waitForIdle()
			assertTrue(OBJECT_MODE_VALUES.all { valueText -> showsText(valueText) }, "leaving Edit mode shows the pose Object mode left")
			assertEquals(PANEL_FIXTURE_POSE, harness.session.pose.value)
		}

	/** A slider scrub does nothing. */
	@Test
	fun editModeLocksASliderScrub() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val slider = sliderBox(harness, PanelRows.BODY_X, PanelValues.BODY_X)
			lock(this, harness)
			val cursorBefore = harness.historyCursor

			drag(slider.at(0.6f), listOf(slider.at(0.8f), Offset(slider.right + 40f, slider.center.y)))

			assertPoseUntouched(this, harness, cursorBefore)
		}

	/** A pad drag does nothing. */
	@Test
	fun editModeLocksAPadDrag() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val pad = padBox(harness, PanelRows.ANGLE_PAD, PanelValues.ANGLE_X, PanelValues.ANGLE_Y)
			lock(this, harness)
			val cursorBefore = harness.historyCursor

			drag(pad.center, listOf(pad.at(0.7f, 0.3f), Offset(pad.right + 40f, pad.top - 40f)))

			assertPoseUntouched(this, harness, cursorBefore)
		}

	/** A dial press and a drag round its seam do nothing. */
	@Test
	fun editModeLocksADialScrub() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(model = panelFixtureModel(repeating = setOf(PanelIds.bodyX)))
			mountParametersPanel(harness)
			val dial = dialBox(harness, PanelRows.BODY_X, PanelValues.BODY_X)
			lock(this, harness)
			val cursorBefore = harness.historyCursor

			clickAt(dial.atClock(0.25f))
			drag(dial.atClock(0.85f), listOf(dial.atClock(0.95f), dial.atClock(0.05f), dial.atClock(0.15f)))

			assertPoseUntouched(this, harness, cursorBefore)
		}

	/** A typed value does nothing.  Typed into Eye Open, the one row whose rest value is not 0. */
	@Test
	fun editModeLocksATypedValue() =
		runComposeUiTest {
			val harness = lockedPanel(this)
			val cursorBefore = harness.historyCursor

			typeIntoNumberField(PanelValues.EYE_OPEN, "0.5")

			assertPoseUntouched(this, harness, cursorBefore)
			assertTrue(showsText(PanelValues.EYE_OPEN), "the row still shows its rest value")
		}

	/** A value field's scrub previews nothing and commits nothing.  Scrubbed on Eye Open, whose rest text is its own. */
	@Test
	fun editModeLocksAFieldScrub() =
		runComposeUiTest {
			val harness = lockedPanel(this)
			val cursorBefore = harness.historyCursor
			val field = numberFieldBounds(PanelValues.EYE_OPEN)

			pressAndMove(field.center, listOf(field.besideCenter(-12f), field.besideCenter(-24f)))
			assertNull(harness.live(PanelIds.eyeOpen), "a locked field scrub must not preview")

			releasePress()
			assertPoseUntouched(this, harness, cursorBefore)
			assertTrue(showsText(PanelValues.EYE_OPEN), "the row still shows its rest value")
		}

	/** Every row shows its default in Edit mode, so no row offers a reset to press. */
	@Test
	fun editModeOffersNoReset() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			assertTrue(countOfDescription(harness.text.reset) > 0, "the fixture must open with rows off their defaults")

			lock(this, harness)

			assertEquals(0, countOfDescription(harness.text.reset))
		}

	/** The header's Reset All does nothing: a locked panel must not be writable from its own header. */
	@Test
	fun editModeLocksResetAll() =
		runComposeUiTest {
			val harness = lockedPanel(this, showHeader = true)
			val cursorBefore = harness.historyCursor

			clickDescribed(harness.text.resetAll)

			assertPoseUntouched(this, harness, cursorBefore)
		}

	/** The header's lock follows the mode as the body's does: Reset All is one step again once Edit mode is left. */
	@Test
	fun resetAllFollowsTheModeBothWays() =
		runComposeUiTest {
			val harness = lockedPanel(this, showHeader = true)
			clickDescribed(harness.text.resetAll)
			assertEquals(2f, harness.committed(PanelIds.bodyX), "Edit mode has to lock Reset All")

			runOnIdle { harness.leaveEditMode() }
			waitForIdle()
			val cursorBefore = harness.historyCursor
			clickDescribed(harness.text.resetAll)
			assertEquals(0f, harness.committed(PanelIds.bodyX), "leaving Edit mode has to unlock it again")
			assertEquals(cursorBefore + 1, harness.historyCursor)

			runOnIdle { harness.session.undo() }
			runOnIdle { harness.enterEditMode() }
			waitForIdle()
			clickDescribed(harness.text.resetAll)
			assertEquals(2f, harness.committed(PanelIds.bodyX), "and entering it again has to lock it again")
		}

	/** The lock follows the mode both ways inside one composition, with no stale answer either way. */
	@Test
	fun theLockFollowsTheModeBothWays() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val slider = sliderBox(harness, PanelRows.BODY_X, PanelValues.BODY_X)
			val pastTheEnd = listOf(slider.at(0.8f), Offset(slider.right + 40f, slider.center.y))

			runOnIdle { harness.enterEditMode() }
			waitForIdle()
			drag(slider.at(0.6f), pastTheEnd)
			assertEquals(2f, harness.committed(PanelIds.bodyX), "Edit mode has to lock the scrub")

			runOnIdle { harness.leaveEditMode() }
			waitForIdle()
			val cursorBefore = harness.historyCursor
			drag(slider.at(0.6f), pastTheEnd)
			assertEquals(10f, harness.committed(PanelIds.bodyX), "leaving Edit mode has to unlock it again")
			assertEquals(cursorBefore + 1, harness.historyCursor)

			runOnIdle { harness.enterEditMode() }
			waitForIdle()
			drag(slider.at(0.6f), listOf(slider.at(0.3f), Offset(slider.left - 40f, slider.center.y)))
			assertEquals(10f, harness.committed(PanelIds.bodyX), "and entering it again has to lock it again")
		}

	/** A drag still held when the lock engages is dropped: the row shows the committed pose again at once. */
	@Test
	fun aScrubHeldWhenTheLockEngagesIsDiscarded() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val slider = sliderBox(harness, PanelRows.BODY_X, PanelValues.BODY_X)
			pressAndMove(slider.at(0.5f), listOf(slider.at(0.7f), slider.at(0.8f)))
			assertFalse(showsText(PanelValues.BODY_X), "the drag must really have moved the row off its value")

			runOnIdle { harness.enterEditMode() }
			waitForIdle()
			val cursorLocked = harness.historyCursor
			assertRowsShowRest(this, harness)

			moveOn(listOf(slider.at(0.9f)))
			releasePress()
			assertRowsShowRest(this, harness)
			assertEquals(2f, harness.committed(PanelIds.bodyX), "the rest of the drag must move nothing")
			assertEquals(cursorLocked, harness.historyCursor, "a dropped drag records no step")

			runOnIdle { harness.leaveEditMode() }
			waitForIdle()
			assertTrue(showsText(PanelValues.BODY_X), "the row shows the committed pose, not the dropped drag")
			assertEquals(2f, harness.live(PanelIds.bodyX), "the renderer is handed the committed pose back")
		}

	/** A value field's scrub held across the lock is dropped the same way: its release commits nothing. */
	@Test
	fun aFieldScrubHeldWhenTheLockEngagesIsDiscarded() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val field = numberFieldBounds(PanelValues.BODY_X)
			pressAndMove(field.center, listOf(field.besideCenter(12f), field.besideCenter(24f)))
			assertNotEquals(2f, harness.live(PanelIds.bodyX), "the scrub must really have previewed")

			runOnIdle { harness.enterEditMode() }
			waitForIdle()
			val cursorLocked = harness.historyCursor

			moveOn(listOf(field.besideCenter(36f)))
			releasePress()
			assertRowsShowRest(this, harness)
			assertEquals(2f, harness.committed(PanelIds.bodyX), "the rest of the scrub and its release must move nothing")
			assertEquals(cursorLocked, harness.historyCursor, "a dropped scrub records no step")

			runOnIdle { harness.leaveEditMode() }
			waitForIdle()
			assertTrue(showsText(PanelValues.BODY_X), "the row shows the committed pose, not the dropped scrub")
			assertEquals(2f, harness.live(PanelIds.bodyX), "the renderer is handed the committed pose back")
		}

	/** A pad drag held across the lock is dropped the same way, on both its axes. */
	@Test
	fun aPadDragHeldWhenTheLockEngagesIsDiscarded() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val pad = padBox(harness, PanelRows.ANGLE_PAD, PanelValues.ANGLE_X, PanelValues.ANGLE_Y)
			pressAndMove(pad.center, listOf(pad.at(0.7f, 0.3f)))
			assertFalse(showsText(PanelValues.ANGLE_X))

			runOnIdle { harness.enterEditMode() }
			waitForIdle()

			assertRowsShowRest(this, harness)
			releasePress()
			assertEquals(3f, harness.committed(PanelIds.angleX))
			assertEquals(-4f, harness.committed(PanelIds.angleY))

			runOnIdle { harness.leaveEditMode() }
			waitForIdle()
			assertTrue(showsText(PanelValues.ANGLE_X), "both axes show the committed pose, not the dropped drag")
			assertTrue(showsText(PanelValues.ANGLE_Y))
		}

	/** A range is the document's, not the pose's, so Edit mode leaves it editable. */
	@Test
	fun editModeLeavesTheRangeEditable() =
		runComposeUiTest {
			val harness = lockedPanel(this)
			clickAt(panelBoundsOfText(PanelNames.BODY_X).center)
			val cursorBefore = harness.historyCursor

			typeIntoNumberField("10.00", "20")

			assertEquals(20f, harness.session.model.value.parameters.first { parameter -> parameter.id == PanelIds.bodyX }.max)
			assertEquals(cursorBefore + 1, harness.historyCursor)
		}

	/** Repeat is the document's too, so Edit mode leaves its checkbox working. */
	@Test
	fun editModeLeavesRepeatEditable() =
		runComposeUiTest {
			val harness = lockedPanel(this)
			clickAt(panelBoundsOfText(PanelNames.BODY_X).center)
			val cursorBefore = harness.historyCursor

			clickAt(panelBoundsOfText(harness.text.repeat).center)

			assertTrue(harness.session.model.value.parameters.first { parameter -> parameter.id == PanelIds.bodyX }.repeat)
			assertEquals(cursorBefore + 1, harness.historyCursor)
		}

	/** The note is the document's too, so Edit mode leaves its field working. */
	@Test
	fun editModeLeavesTheNoteEditable() =
		runComposeUiTest {
			val harness = lockedPanel(this)
			clickAt(panelBoundsOfText(PanelNames.BODY_X).center)
			val cursorBefore = harness.historyCursor

			clickAt(panelBoundsOfText(harness.text.notePlaceholder).center)
			onNode(hasSetTextAction() and isFocused(), useUnmergedTree = true).performTextReplacement("Leans the torso")
			waitForIdle()
			pressKey(Key.Enter)

			assertEquals("Leans the torso", harness.session.model.value.parameters.first { parameter -> parameter.id == PanelIds.bodyX }.note)
			assertEquals(cursorBefore + 1, harness.historyCursor)
		}

	/** A link and an unlink are document edits too. */
	@Test
	fun editModeLeavesLinkAndUnlinkAvailable() =
		runComposeUiTest {
			val harness = lockedPanel(this)
			val cursorBefore = harness.historyCursor

			// In list order the link glyphs belong to Eye Open, Smile Shape, and Body X.
			clickDescribed(harness.text.link, index = 2)
			assertTrue(ParameterLink(PanelIds.bodyX, PanelIds.breath) in harness.session.model.value.parameterLinks)
			assertEquals(cursorBefore + 1, harness.historyCursor)

			clickDescribed(harness.text.unlink, index = 0)
			assertFalse(ParameterLink(PanelIds.angleX, PanelIds.angleY) in harness.session.model.value.parameterLinks)
			assertEquals(cursorBefore + 2, harness.historyCursor)
		}

	/** A rename is a document edit. */
	@Test
	fun editModeLeavesRenameAvailable() =
		runComposeUiTest {
			val harness = lockedPanel(this)
			val cursorBefore = harness.historyCursor
			doubleClickAt(panelBoundsOfText(PanelNames.BREATH).center)

			typeIntoRenameField("Air")
			pressKey(Key.Enter)

			assertEquals("Air", harness.session.model.value.parameters.first { parameter -> parameter.id == PanelIds.breath }.name)
			assertEquals(cursorBefore + 1, harness.historyCursor)
		}

	/** A create and a delete are document edits. */
	@Test
	fun editModeLeavesCreateAndDeleteAvailable() =
		runComposeUiTest {
			val harness = lockedPanel(this)
			val parametersBefore = harness.session.model.value.parameters.size
			val cursorBefore = harness.historyCursor

			secondaryClickAt(panelBoundsOfText(PanelNames.BREATH).center)
			clickMenuEntry(harness.text.addKeyFormParameter)
			assertEquals(parametersBefore + 1, harness.session.model.value.parameters.size)
			assertEquals(cursorBefore + 1, harness.historyCursor)
			pressKey(Key.Escape)

			secondaryClickAt(panelBoundsOfText(PanelNames.BREATH).center)
			clickMenuEntry(harness.text.deleteParameter)
			assertTrue(harness.session.model.value.parameters.none { parameter -> parameter.id == PanelIds.breath })
		}

	private companion object {
		/** The values of the pose the fixture opens on that differ from their defaults, so rest shows none of them. */
		val OBJECT_MODE_VALUES =
			listOf(PanelValues.SMILE_SHAPE, PanelValues.SMILE, PanelValues.ANGLE_X, PanelValues.ANGLE_Y, PanelValues.BODY_X, PanelValues.BREATH)
	}
}