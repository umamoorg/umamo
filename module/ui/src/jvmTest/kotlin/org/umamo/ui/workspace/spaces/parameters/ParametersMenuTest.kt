package org.umamo.ui.workspace.spaces.parameters

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.DpSize
import org.umamo.edit.ParameterControl
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.RuntimeTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the panel's context menus: what each row kind offers, what the bare panel offers, and what
 * choosing an entry does.
 */
@OptIn(ExperimentalTestApi::class)
class ParametersMenuTest {
	/**
	 * Every label any of the panel's menus can show, for reading a menu's entries back in order.
	 *
	 * @param ParametersPanelHarness harness The mounted harness.
	 * @return List<String> The labels.
	 */
	private fun everyEntry(harness: ParametersPanelHarness): List<String> =
		listOf(
			harness.text.rename,
			harness.text.toggleRepeat,
			harness.text.control,
			harness.text.deleteParameter,
			harness.text.deleteGroup,
			harness.text.addKeyFormParameter,
			harness.text.addBlendShapeParameter,
			harness.text.newGroup,
		)

	/** A slider row offers its own four entries, then the entries every menu ends with. */
	@Test
	fun aSliderRowsMenu() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)

			secondaryClickAt(panelBoundsOfText(PanelNames.BODY_X).center)

			assertEquals(
				listOf(
					harness.text.rename,
					harness.text.toggleRepeat,
					harness.text.control,
					harness.text.deleteParameter,
					harness.text.addKeyFormParameter,
					harness.text.addBlendShapeParameter,
					harness.text.newGroup,
				),
				shownInOrder(everyEntry(harness)),
			)
		}

	/** A pad holds two parameters, so its rename and delete each ask which. */
	@Test
	fun aPadRowsMenuAsksWhichAxis() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)

			secondaryClickAt(panelBoundsOfText(PanelNames.ANGLE_X).center)
			hoverMenuEntry(harness.text.rename)
			assertTrue(popupShows(PanelNames.ANGLE_X), "the flyout has to list both axes by name")
			assertTrue(popupShows(PanelNames.ANGLE_Y))
			clickMenuEntry(PanelNames.ANGLE_Y)
			assertEquals(PanelIds.angleY, harness.viewState.renamingParameterId)
			pressKey(Key.Escape)

			secondaryClickAt(panelBoundsOfText(PanelNames.ANGLE_X).center)
			hoverMenuEntry(harness.text.deleteParameter)
			clickMenuEntry(PanelNames.ANGLE_Y)
			assertTrue(harness.session.model.value.parameters.none { parameter -> parameter.id == PanelIds.angleY })
			assertNotNull(harness.session.model.value.parameters.firstOrNull { parameter -> parameter.id == PanelIds.angleX })
		}

	/** Toggle Repeat from a slider's menu flips that parameter's flag, a step each way. */
	@Test
	fun toggleRepeatFromASlidersMenu() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val cursorBefore = harness.historyCursor

			secondaryClickAt(panelBoundsOfText(PanelNames.BODY_X).center)
			clickMenuEntry(harness.text.toggleRepeat)

			assertTrue(parameterOf(harness, PanelIds.bodyX).repeat)
			assertEquals(cursorBefore + 1, harness.historyCursor)

			secondaryClickAt(panelBoundsOfText(PanelNames.BODY_X).center)
			clickMenuEntry(harness.text.toggleRepeat)

			assertFalse(parameterOf(harness, PanelIds.bodyX).repeat)
			assertEquals(cursorBefore + 2, harness.historyCursor)
		}

	/** A pad's Toggle Repeat asks which axis, and sets that one alone. */
	@Test
	fun aPadsToggleRepeatAsksWhichAxis() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)

			secondaryClickAt(panelBoundsOfText(PanelNames.ANGLE_X).center)
			hoverMenuEntry(harness.text.toggleRepeat)
			assertTrue(popupShows(PanelNames.ANGLE_X), "the flyout has to list both axes by name")
			assertTrue(popupShows(PanelNames.ANGLE_Y))
			clickMenuEntry(PanelNames.ANGLE_Y)

			assertTrue(parameterOf(harness, PanelIds.angleY).repeat)
			assertFalse(parameterOf(harness, PanelIds.angleX).repeat)
		}

	/** A pad's menu holds the pair's Control submenu between Toggle Repeat and Delete Parameter, as a slider's does. */
	@Test
	fun aPadRowsMenuHoldsThePairsControl() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)

			secondaryClickAt(panelBoundsOfText(PanelNames.ANGLE_X).center)

			assertEquals(
				listOf(harness.text.rename, harness.text.toggleRepeat, harness.text.control, harness.text.deleteParameter),
				shownInOrder(everyEntry(harness)).take(4),
			)

			hoverMenuEntry(harness.text.control)

			assertEquals(listOf(harness.text.controlFollow, harness.text.controlPad, harness.text.controlDials), shownInOrder(pairControlRows(harness)))
		}

	/**
	 * A repeating slider's Control submenu ticks Follow Settings until a pick, a pick swaps the control at once with no
	 * History step, the tick follows the pick, and Follow Settings puts the preference's dial back.
	 */
	@Test
	fun aSlidersControlPicksTheControlItShows() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(model = panelFixtureModel(repeating = setOf(PanelIds.bodyX)))
			mountParametersPanel(harness)
			val cursorBefore = harness.historyCursor

			openBodyXControl(harness)
			assertControlTicked(harness.text.controlFollow, among = singleControlRows(harness))
			clickMenuEntry(harness.text.controlSlider)

			assertEquals(cursorBefore, harness.historyCursor, "a pick is no History step")
			assertFalse(harness.session.dirty.value, "and no change to the document")
			clickAt(sliderBox(harness, PanelRows.BODY_X, PanelValues.BODY_X).at(0.75f))
			assertNear(5f, harness.committed(PanelIds.bodyX), "three quarters along the slider's -10..10, where the dial's preference would not reach")
			undoToTheFixturePose(harness)

			openBodyXControl(harness)
			assertControlTicked(harness.text.controlSlider, among = singleControlRows(harness))
			clickMenuEntry(harness.text.controlFollow)

			clickAt(dialBox(harness, PanelRows.BODY_X, PanelValues.BODY_X).atClock(0.25f))
			assertNear(-5f, harness.committed(PanelIds.bodyX), "a quarter turn round -10..10: following the preference shows the dial again")
		}

	/** Any parameter can pick the dial: on one that does not repeat it is a dial all the same, which stops at its seam. */
	@Test
	fun aParameterThatDoesNotRepeatCanPickTheDial() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)

			openBodyXControl(harness)
			clickMenuEntry(harness.text.controlDial)
			clickAt(dialBox(harness, PanelRows.BODY_X, PanelValues.BODY_X).atClock(0.25f))

			assertNear(-5f, harness.committed(PanelIds.bodyX), "a quarter turn round -10..10")
			assertFalse(parameterOf(harness, PanelIds.bodyX).repeat, "the pick leaves Repeat alone")
		}

	/** A pad's Control picks the pair's two dials, keyed on its upper axis, and Pad brings the pad back. */
	@Test
	fun aPadsControlPicksTwoDialsAndBack() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			harness.panelSize = DpSize(PANEL_WIDTH, PANEL_HEIGHT_WITH_SPACE)
			mountParametersPanel(harness)

			secondaryClickAt(panelBoundsOfText(PanelNames.ANGLE_X).center)
			hoverMenuEntry(harness.text.control)
			clickMenuEntry(harness.text.controlDials)

			assertEquals(mapOf(PanelIds.angleX to ParameterControl.Dial), harness.session.parameterControls.value, "the pair keys on its upper axis")
			val (upperDial, _) = pairDialBoxes(harness, PanelRows.ANGLE_PAD, PanelValues.ANGLE_X, PanelValues.ANGLE_Y)
			clickAt(upperDial.atClock(0.25f))
			assertNear(-15f, harness.committed(PanelIds.angleX), "a quarter turn round -30..30 on the upper axis's dial")
			undoToTheFixturePose(harness)

			// Two dials caption each axis by name under its dial, so the name shows twice; either is inside the pad's menu.
			secondaryClickAt(panelBoundsOf(onAllNodesWithText(PanelNames.ANGLE_X, useUnmergedTree = true)[0]).center)
			hoverMenuEntry(harness.text.control)
			assertControlTicked(harness.text.controlDials, among = pairControlRows(harness))
			clickMenuEntry(harness.text.controlPad)

			clickAt(padPointOf(padBox(harness, PanelRows.ANGLE_PAD, PanelValues.ANGLE_X, PanelValues.ANGLE_Y), 12f, -6f))
			assertNear(12f, harness.committed(PanelIds.angleX), "the pad is back")
		}

	/**
	 * Takes back the press a case just made, so the fields show the fixture's values again and a control can be found
	 * by them.
	 *
	 * @param ParametersPanelHarness harness The mounted harness.
	 */
	private fun ComposeUiTest.undoToTheFixturePose(harness: ParametersPanelHarness) {
		runOnIdle { harness.session.undo() }
		waitForIdle()
	}

	/**
	 * Opens Body X's menu and rests on its Control submenu.
	 *
	 * @param ParametersPanelHarness harness The mounted harness.
	 */
	private fun ComposeUiTest.openBodyXControl(harness: ParametersPanelHarness) {
		secondaryClickAt(panelBoundsOfText(PanelNames.BODY_X).center)
		hoverMenuEntry(harness.text.control)
	}

	/**
	 * A single row's Control rows, top to bottom.
	 *
	 * @param ParametersPanelHarness harness The mounted harness.
	 * @return List<String> The labels.
	 */
	private fun singleControlRows(harness: ParametersPanelHarness): List<String> = listOf(harness.text.controlFollow, harness.text.controlSlider, harness.text.controlDial)

	/**
	 * A pad's Control rows, top to bottom.
	 *
	 * @param ParametersPanelHarness harness The mounted harness.
	 * @return List<String> The labels.
	 */
	private fun pairControlRows(harness: ParametersPanelHarness): List<String> = listOf(harness.text.controlFollow, harness.text.controlPad, harness.text.controlDials)

	/**
	 * Asserts that the open Control submenu ticks [ticked] and no other of [among].
	 *
	 * @param String ticked The row that must be the one in effect.
	 * @param List<String> among Every row of the submenu.
	 */
	private fun ComposeUiTest.assertControlTicked(ticked: String, among: List<String>) {
		for (label in among) {
			val row = onNode(hasText(label) and hasAnyAncestor(isPopup()))

			if (label == ticked) {
				row.assertIsSelected()
			} else {
				row.assertIsNotSelected()
			}
		}
	}

	/** The oldest runtime target still offers Toggle Repeat, since no target restricts repeat. */
	@Test
	fun theOldestRuntimeOffersTheToggle() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(runtimeTarget = RuntimeTarget.Cubism30)
			mountParametersPanel(harness)

			secondaryClickAt(panelBoundsOfText(PanelNames.BODY_X).center)
			clickMenuEntry(harness.text.toggleRepeat)

			assertTrue(parameterOf(harness, PanelIds.bodyX).repeat)
		}

	/** A group header offers group entries only. */
	@Test
	fun aGroupHeadersMenu() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)

			secondaryClickAt(panelBoundsOfText(PanelNames.FACE).center)

			assertEquals(
				listOf(harness.text.newGroup, harness.text.rename, harness.text.deleteGroup),
				shownInOrder(everyEntry(harness)),
			)
		}

	/** The bare panel offers the creates, so the first parameter can be made with no row to press on. */
	@Test
	fun theBarePanelsMenu() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			harness.panelSize = DpSize(PANEL_WIDTH, PANEL_HEIGHT_WITH_SPACE)
			mountParametersPanel(harness)
			val underTheLastRow = Offset(panelBoundsOfText(PanelNames.BODY).center.x, gripBounds(harness, PanelRows.BODY).bottom + 40f)

			secondaryClickAt(underTheLastRow)

			assertEquals(
				listOf(harness.text.addKeyFormParameter, harness.text.addBlendShapeParameter, harness.text.newGroup),
				shownInOrder(everyEntry(harness)),
			)
		}

	/** A runtime with no blend-shape parameters offers none to create, and still shows the ones it has. */
	@Test
	fun aRuntimeWithoutBlendShapesOffersNoneToCreateFromARow() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(runtimeTarget = RuntimeTarget.Cubism40)
			mountParametersPanel(harness)
			assertTrue(showsText(PanelNames.SMILE), "a blend-shape parameter the rig already has keeps its row")

			secondaryClickAt(panelBoundsOfText(PanelNames.BODY_X).center)

			assertTrue(popupShows(harness.text.addKeyFormParameter), "the menu has to be open for its absence to mean anything")
			assertFalse(popupShows(harness.text.addBlendShapeParameter))
		}

	/** The bare panel's menu is a second way in, and must not offer what the first one hides. */
	@Test
	fun aRuntimeWithoutBlendShapesOffersNoneToCreateFromTheBarePanel() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(runtimeTarget = RuntimeTarget.Cubism40)
			harness.panelSize = DpSize(PANEL_WIDTH, PANEL_HEIGHT_WITH_SPACE)
			mountParametersPanel(harness)

			secondaryClickAt(Offset(panelBoundsOfText(PanelNames.BODY).center.x, gripBounds(harness, PanelRows.BODY).bottom + 40f))

			assertTrue(popupShows(harness.text.addKeyFormParameter), "the menu has to be open for its absence to mean anything")
			assertFalse(popupShows(harness.text.addBlendShapeParameter))
		}

	/** A press on a row opens the row's menu and not the panel's under it. */
	@Test
	fun aRowsMenuReplacesThePanels() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)

			secondaryClickAt(panelBoundsOfText(PanelNames.BODY_X).center)

			val newGroupEntries =
				onAllNodes(hasText(harness.text.newGroup) and hasAnyAncestor(isPopup()), useUnmergedTree = true).fetchSemanticsNodes()
			assertEquals(1, newGroupEntries.size, "one menu, not the row's over the panel's")
		}

	/** Rename from the menu opens the same field a double click does. */
	@Test
	fun renameFromTheMenuOpensTheField() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)

			secondaryClickAt(panelBoundsOfText(PanelNames.BODY_X).center)
			clickMenuEntry(harness.text.rename)

			assertEquals(PanelIds.bodyX, harness.viewState.renamingParameterId)
			assertTrue(renameFieldOpen())
		}

	/** A delete is one step, and the keyboard still works once the row is gone. */
	@Test
	fun deleteIsOneStepAndLeavesTheShortcutsWorking() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			secondaryClickAt(panelBoundsOfText(PanelNames.BREATH).center)
			val cursorBefore = harness.historyCursor

			clickMenuEntry(harness.text.deleteParameter)

			assertTrue(harness.session.model.value.parameters.none { parameter -> parameter.id == PanelIds.breath })
			assertEquals(cursorBefore + 1, harness.historyCursor)
			assertFalse(showsText(PanelNames.BREATH))
			assertTrue(harness.rootFocused, "deleting a row must not take the keyboard with it")
			pressBoundChord()
			assertEquals(1, harness.shortcutRuns)
		}

	/** Deleting a group removes the group, never what was in it. */
	@Test
	fun deleteGroupKeepsItsParameters() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val parametersBefore = harness.session.model.value.parameters.map { parameter -> parameter.id }

			secondaryClickAt(panelBoundsOfText(PanelNames.FACE).center)
			clickMenuEntry(harness.text.deleteGroup)

			assertFalse(showsText(PanelNames.FACE))
			assertEquals(parametersBefore, harness.session.model.value.parameters.map { parameter -> parameter.id })
			assertTrue(showsText(PanelNames.EYE_OPEN), "the group's rows stay in the list")
			assertTrue(PanelIds.eyeOpen.raw in rootOrderOf(harness.session.model.value), "and move up to where the group was")
		}

	/** A create opens the new row for naming, of the kind that was asked for. */
	@Test
	fun createOpensTheNewRowForNaming() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val idsBefore = harness.session.model.value.parameters.map { parameter -> parameter.id }.toSet()

			secondaryClickAt(panelBoundsOfText(PanelNames.BODY_X).center)
			clickMenuEntry(harness.text.addBlendShapeParameter)

			val created = harness.session.model.value.parameters.single { parameter -> parameter.id !in idsBefore }
			assertEquals(ParameterKind.BLEND_SHAPE, created.kind)
			assertEquals(harness.text.defaultParameterName, created.name)
			assertEquals(created.id, harness.viewState.renamingParameterId)
			assertTrue(renameFieldOpen())
		}

	/** New Group does the same for a group. */
	@Test
	fun newGroupOpensTheNewGroupForNaming() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)

			secondaryClickAt(panelBoundsOfText(PanelNames.FACE).center)
			clickMenuEntry(harness.text.newGroup)

			assertNotNull(harness.viewState.renamingGroupId)
			assertTrue(renameFieldOpen())
		}

	/**
	 * A created row is put at the top of the list.  With the list scrolled down that is off screen, where
	 * a lazy row cannot open its field at all until the list scrolls it into view.
	 */
	@Test
	fun createScrollsTheNewRowIntoView() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			harness.panelSize = DpSize(PANEL_WIDTH, PANEL_HEIGHT_SCROLLING)
			mountParametersPanel(harness)
			onNode(hasScrollToIndexAction()).performScrollToIndex(PanelRows.COUNT - 1)
			waitForIdle()
			assertFalse(showsText(PanelNames.FACE), "the list has to be scrolled off its top for this to mean anything")

			secondaryClickAt(panelBoundsOfText(PanelNames.BREATH).center)
			clickMenuEntry(harness.text.addKeyFormParameter)

			assertNotNull(harness.viewState.renamingParameterId)
			onNode(hasSetTextAction() and isFocused()).assertIsDisplayed()
		}

	/**
	 * The reveal waits for its row.  A created parameter reaches the list a recomposition after it was
	 * marked for naming, so a reveal that looked once would find nothing and never look again.  Here the
	 * row is late for another reason - its group is folded - which holds it back just the same.
	 */
	@Test
	fun theRevealWaitsForARowThatArrivesLate() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			harness.panelSize = DpSize(PANEL_WIDTH, PANEL_HEIGHT_SCROLLING)
			mountParametersPanel(harness)

			runOnIdle { harness.viewState.renamingParameterId = PanelIds.arm }
			waitForIdle()
			assertFalse(renameFieldOpen(), "the row is inside a folded group, so there is nothing to open yet")

			runOnIdle { harness.viewState.expandedGroups[PanelIds.body] = true }
			waitForIdle()

			onNode(hasSetTextAction() and isFocused()).assertIsDisplayed()
		}

	/** With no session there is nothing to edit: the entries are there and do nothing, and no link is offered. */
	@Test
	fun withNoSessionNothingEdits() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(provideSession = false)
			mountParametersPanel(harness)
			val modelBefore = harness.session.model.value
			assertEquals(0, countOfDescription(harness.text.link), "a link needs a session to write through")
			assertEquals(0, countOfDescription(harness.text.unlink))

			secondaryClickAt(panelBoundsOfText(PanelNames.BODY_X).center)
			assertTrue(popupShows(harness.text.deleteParameter))
			clickMenuEntry(harness.text.deleteParameter)

			assertTrue(harness.session.model.value === modelBefore, "a disabled entry must not reach the document")
		}
}