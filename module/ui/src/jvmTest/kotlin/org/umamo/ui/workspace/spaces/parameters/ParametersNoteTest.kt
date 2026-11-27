package org.umamo.ui.workspace.spaces.parameters

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.DpSize
import org.umamo.edit.parameter.withParameterNote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the note field at the foot of a parameter's range editor: where it shows, that an edit of it is one
 * step when it ends, and that the keyboard comes back to the shell however the edit ends.
 */
@OptIn(ExperimentalTestApi::class)
class ParametersNoteTest {
	/** The field sits under the Repeat checkbox, empty with its hint, and a typed note commits on Enter as one step. */
	@Test
	fun aTypedNoteCommitsOnEnterAsOneStep() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			clickAt(panelBoundsOfText(PanelNames.BODY_X).center)
			assertEquals(1, noteFields().fetchSemanticsNodes().size)
			assertTrue(panelBoundsOfText(harness.text.notePlaceholder).top > panelBoundsOfText(harness.text.repeat).bottom, "the note comes after Repeat")
			val cursorBefore = harness.historyCursor

			typeIntoNote(0, "Leans the torso toward the viewer's right")
			pressKey(Key.Enter)

			assertEquals("Leans the torso toward the viewer's right", parameterOf(harness, PanelIds.bodyX).note)
			assertEquals(cursorBefore + 1, harness.historyCursor)
			assertEquals("change.parameter.note", harness.session.historyView.value.steps.last().labelKey)
			assertEquals("Leans the torso toward the viewer's right", shownNote(0))
		}

	/** After an Enter commit the keyboard is the shell's again, so a shortcut runs. */
	@Test
	fun shortcutsWorkAfterAnEnterCommit() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			clickAt(panelBoundsOfText(PanelNames.BODY_X).center)

			typeIntoNote(0, "Leans the torso")
			pressKey(Key.Enter)

			assertTrue(harness.rootFocused, "the commit must hand the keyboard back to the root")
			pressBoundChord()
			assertEquals(1, harness.shortcutRuns)
		}

	/** A pad's editor holds a note per axis, and each sets its own axis. */
	@Test
	fun aPadHoldsANotePerAxis() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			harness.panelSize = DpSize(PANEL_WIDTH, PANEL_HEIGHT_WITH_SPACE)
			mountParametersPanel(harness)
			// Folding the group above lifts the pad, so the editor under it sits well inside the panel.
			clickAt(panelBoundsOfText(PanelNames.FACE).center)
			clickAt(panelBoundsOfText(PanelNames.ANGLE_X).center)
			assertEquals(2, noteFields().fetchSemanticsNodes().size)

			typeIntoNote(1, "Nods the head")
			pressKey(Key.Enter)

			assertEquals("Nods the head", parameterOf(harness, PanelIds.angleY).note)
			assertEquals("", parameterOf(harness, PanelIds.angleX).note)
		}

	/** Escape throws the draft away: no step, the note as it was, and the keyboard back with the shell. */
	@Test
	fun escapeDiscardsTheDraft() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			clickAt(panelBoundsOfText(PanelNames.BODY_X).center)
			val cursorBefore = harness.historyCursor

			typeIntoNote(0, "Leans the torso")
			pressKey(Key.Escape)

			assertEquals("", parameterOf(harness, PanelIds.bodyX).note)
			assertEquals(cursorBefore, harness.historyCursor)
			assertEquals(1, onAllNodesWithText(harness.text.notePlaceholder, useUnmergedTree = true).fetchSemanticsNodes().size, "the field shows the note as it was")
			assertTrue(harness.rootFocused)
		}

	/** A press anywhere else ends the edit, and commits it. */
	@Test
	fun aPressElsewhereCommits() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			clickAt(panelBoundsOfText(PanelNames.BODY_X).center)

			typeIntoNote(0, "Leans the torso")
			clickAt(panelBoundsOf(onNodeWithTag(PANEL_ELSEWHERE_TAG)).center)

			assertEquals("Leans the torso", parameterOf(harness, PanelIds.bodyX).note)
			assertTrue(harness.rootFocused)
		}

	/** A press on the parameter's name while typing closes the editor, and the note commits on the way out. */
	@Test
	fun closingTheEditorByItsNameCommitsFirst() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			clickAt(panelBoundsOfText(PanelNames.BODY_X).center)

			typeIntoNote(0, "Leans the torso")
			clickAt(panelBoundsOfText(PanelNames.BODY_X).center)

			assertEquals(0, noteFields().fetchSemanticsNodes().size, "the press on the name closes the editor")
			assertEquals("Leans the torso", parameterOf(harness, PanelIds.bodyX).note)
			assertTrue(harness.rootFocused)
		}

	/** An editor closed under a field being typed in keeps the note and hands the keyboard back. */
	@Test
	fun anEditorClosedUnderTheFieldKeepsTheNote() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			clickAt(panelBoundsOfText(PanelNames.BODY_X).center)

			typeIntoNote(0, "Leans the torso")
			runOnIdle { harness.viewState.openRangeEditors[PanelIds.bodyX] = false }
			waitForIdle()

			assertEquals("Leans the torso", parameterOf(harness, PanelIds.bodyX).note)
			assertNull(harness.inlineEditController.cancel, "the closed field must not leave the shell yielding the keyboard")
			assertTrue(harness.rootFocused)
			pressBoundChord()
			assertEquals(1, harness.shortcutRuns)
		}

	/** A click into the note is the field's, so it leaves the keyform target alone. */
	@Test
	fun aClickIntoTheNoteLeavesTheKeyformTargetAlone() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			clickAt(panelBoundsOfText(PanelNames.BODY_X).center)
			val targetBefore = harness.session.parameterSelection.value
			assertFalse(PanelIds.bodyX in targetBefore.ids, "Body X must not be the target already, or the case shows nothing")

			clickAt(panelBoundsOf(noteFields()[0]).center)

			assertEquals(targetBefore, harness.session.parameterSelection.value, "a click on the note must not reach the island")
		}

	/** A typed note is trimmed when it commits, and the field shows what was kept. */
	@Test
	fun theFieldShowsTheTrimmedNote() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			clickAt(panelBoundsOfText(PanelNames.BODY_X).center)

			typeIntoNote(0, "  Leans the torso  ")
			pressKey(Key.Enter)

			assertEquals("Leans the torso", parameterOf(harness, PanelIds.bodyX).note)
			assertEquals("Leans the torso", shownNote(0))
		}

	/** A note the document brought shows as written, edge whitespace and all, and opening it to look changes nothing. */
	@Test
	fun aDocumentsNoteShowsAsWritten() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(model = panelFixtureModel().withParameterNote(PanelIds.bodyX, " Kept as written "))
			mountParametersPanel(harness)
			clickAt(panelBoundsOfText(PanelNames.BODY_X).center)
			val cursorBefore = harness.historyCursor

			assertEquals(" Kept as written ", shownNote(0))

			clickAt(panelBoundsOf(noteFields()[0]).center)
			pressKey(Key.Enter)

			assertEquals(" Kept as written ", parameterOf(harness, PanelIds.bodyX).note)
			assertEquals(cursorBefore, harness.historyCursor)
		}

	/**
	 * The note fields on screen, top to bottom.  The only text fields an idle panel holds, since a range
	 * field is a text field only while it is being typed in.
	 *
	 * @return SemanticsNodeInteractionCollection The fields.
	 */
	private fun ComposeUiTest.noteFields(): SemanticsNodeInteractionCollection = onAllNodes(hasSetTextAction(), useUnmergedTree = true)

	/**
	 * Clicks into one note field and replaces its text, as typing it would.
	 *
	 * @param Int index The field, 0 for the topmost.
	 * @param String text The text to type.
	 */
	private fun ComposeUiTest.typeIntoNote(index: Int, text: String) {
		clickAt(panelBoundsOf(noteFields()[index]).center)
		onNode(hasSetTextAction() and isFocused(), useUnmergedTree = true).performTextReplacement(text)
		waitForIdle()
	}

	/**
	 * The text one note field shows.
	 *
	 * @param Int index The field, 0 for the topmost.
	 * @return String The field's text.
	 */
	private fun ComposeUiTest.shownNote(index: Int): String = noteFields()[index].fetchSemanticsNode().config[SemanticsProperties.EditableText].text
}