package org.umamo.ui.workspace.spaces.parameters

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.RuntimeTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the panel's header strip: what it holds, and that each of its controls reaches the same document
 * and the same view state the body does.  The header and the body are sibling subtrees, so the view
 * state parked on the area is the only thing they share.
 */
@OptIn(ExperimentalTestApi::class)
class ParametersHeaderControlsTest {
	/** With a document open the strip holds its five controls. */
	@Test
	fun theHeaderHoldsItsFiveControls() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(showHeader = true)
			mountParametersPanel(harness)

			assertEquals(1, countOfDescription(harness.text.addParameter))
			assertEquals(1, countOfDescription(harness.text.newGroup))
			assertEquals(1, countOfDescription(harness.text.resetAll))
			assertEquals(1, countOfDescription(harness.text.filters))
			assertEquals(1, onAllNodes(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes().size, "and the search field")
		}

	/** With no document the strip holds nothing, and the body is bare. */
	@Test
	fun withNoDocumentTheHeaderAndTheBodyAreEmpty() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(showHeader = true, provideDocument = false)
			mountParametersPanel(harness)

			assertEquals(0, countOfDescription(harness.text.addParameter))
			assertEquals(0, countOfDescription(harness.text.newGroup))
			assertEquals(0, countOfDescription(harness.text.resetAll))
			assertEquals(0, countOfDescription(harness.text.filters))
			assertEquals(0, onAllNodes(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes().size)
			assertEquals(0, countOfDescription(harness.text.reorderHandle))
		}

	/** Add Parameter asks for the kind, creates it, and opens the new row in the body for naming. */
	@Test
	fun addParameterCreatesAKeyFormParameter() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(showHeader = true)
			mountParametersPanel(harness)
			val idsBefore = harness.session.model.value.parameters.map { parameter -> parameter.id }.toSet()
			val cursorBefore = harness.historyCursor

			clickDescribed(harness.text.addParameter)
			clickMenuEntry(harness.text.addKeyFormParameter)

			val created = harness.session.model.value.parameters.single { parameter -> parameter.id !in idsBefore }
			assertEquals(ParameterKind.NORMAL, created.kind)
			assertEquals(cursorBefore + 1, harness.historyCursor)
			assertEquals(created.id, harness.viewState.renamingParameterId)
			assertTrue(renameFieldOpen(), "the body has to open the row the header made")
		}

	/** The same chip makes a blend-shape parameter. */
	@Test
	fun addParameterCreatesABlendShapeParameter() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(showHeader = true)
			mountParametersPanel(harness)
			val idsBefore = harness.session.model.value.parameters.map { parameter -> parameter.id }.toSet()

			clickDescribed(harness.text.addParameter)
			clickMenuEntry(harness.text.addBlendShapeParameter)

			val created = harness.session.model.value.parameters.single { parameter -> parameter.id !in idsBefore }
			assertEquals(ParameterKind.BLEND_SHAPE, created.kind)
		}

	/** A runtime with no blend-shape parameters offers none from the header either. */
	@Test
	fun aRuntimeWithoutBlendShapesOffersNoneFromTheHeader() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(runtimeTarget = RuntimeTarget.Cubism40, showHeader = true)
			mountParametersPanel(harness)

			clickDescribed(harness.text.addParameter)

			assertTrue(popupShows(harness.text.addKeyFormParameter), "the menu has to be open for its absence to mean anything")
			assertFalse(popupShows(harness.text.addBlendShapeParameter))
		}

	/** New Group creates a group and opens it in the body for naming. */
	@Test
	fun newGroupCreatesAGroup() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(showHeader = true)
			mountParametersPanel(harness)
			val groupsBefore = harness.session.model.value.parameterTree.filterIsInstance<ParameterNode.Group>().size
			val cursorBefore = harness.historyCursor

			clickDescribed(harness.text.newGroup)

			assertEquals(groupsBefore + 1, harness.session.model.value.parameterTree.filterIsInstance<ParameterNode.Group>().size)
			assertEquals(cursorBefore + 1, harness.historyCursor)
			assertNotNull(harness.viewState.renamingGroupId)
			assertTrue(renameFieldOpen())
		}

	/** Reset All returns every parameter to its default, as one step. */
	@Test
	fun resetAllIsOneStepToEveryDefault() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(showHeader = true)
			mountParametersPanel(harness)
			val cursorBefore = harness.historyCursor

			clickDescribed(harness.text.resetAll)

			for (parameter in harness.session.model.value.parameters) {
				assertEquals(parameter.default, harness.committed(parameter.id), "${parameter.name} has to be back on its default")
			}

			assertEquals(cursorBefore + 1, harness.historyCursor, "however many parameters moved, it is one step")
			assertEquals(0, countOfDescription(harness.text.reset), "and no row is left with anything to reset")
		}

	/** What is typed in the header's search is what the body filters by. */
	@Test
	fun theSearchFieldFiltersTheBody() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(showHeader = true)
			mountParametersPanel(harness)

			onNode(hasSetTextAction()).performClick()
			waitForIdle()
			onNode(hasSetTextAction()).performTextInput("breath")
			waitForIdle()

			assertEquals("breath", harness.viewState.query)
			assertTrue(showsText(PanelNames.BREATH))
			assertFalse(showsText(PanelNames.BODY_X))
		}

	/** A press on the body after searching gives the keyboard back, and keeps the search. */
	@Test
	fun leavingTheSearchFieldGivesTheKeyboardBack() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(showHeader = true)
			mountParametersPanel(harness)
			onNode(hasSetTextAction()).performClick()
			waitForIdle()
			onNode(hasSetTextAction()).performTextInput("breath")
			waitForIdle()

			onNodeWithTag(PANEL_ELSEWHERE_TAG).performClick()
			waitForIdle()

			assertTrue(harness.rootFocused)
			assertEquals("breath", harness.viewState.query)
			pressBoundChord()
			assertEquals(1, harness.shortcutRuns)
		}

	/** The filter chip's checkbox is the body's selection filter. */
	@Test
	fun theFilterChipSetsTheSelectionFilter() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(showHeader = true)
			mountParametersPanel(harness)

			clickDescribed(harness.text.filters)
			clickMenuEntry(harness.text.filterSelected)

			assertTrue(harness.viewState.showOnlySelected)
		}

	/** A strip too narrow for its controls folds them into the overflow chip, where they still work. */
	@Test
	fun aNarrowHeaderKeepsItsControlsWorking() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(showHeader = true)
			harness.headerWidth = 48.dp
			mountParametersPanel(harness)
			assertEquals(1, countOfDescription(harness.text.more), "the strip has to have folded for this to mean anything")
			val cursorBefore = harness.historyCursor

			clickDescribed(harness.text.more)
			clickDescribed(harness.text.resetAll)

			assertEquals(0f, harness.committed(PanelIds.bodyX))
			assertEquals(cursorBefore + 1, harness.historyCursor)
		}

	/** A rename the header opened is abandoned by Escape, which leaves the created parameter in place. */
	@Test
	fun escapeAfterACreateKeepsTheParameterUnderItsDefaultName() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(showHeader = true)
			mountParametersPanel(harness)
			val parametersBefore = harness.session.model.value.parameters.size

			clickDescribed(harness.text.addParameter)
			clickMenuEntry(harness.text.addKeyFormParameter)
			pressKey(Key.Escape)

			assertEquals(parametersBefore + 1, harness.session.model.value.parameters.size)
			assertTrue(showsText(harness.text.defaultParameterName))
			assertFalse(renameFieldOpen())
		}
}