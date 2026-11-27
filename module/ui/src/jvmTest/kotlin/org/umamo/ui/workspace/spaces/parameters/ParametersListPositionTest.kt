package org.umamo.ui.workspace.spaces.parameters

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.DpSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins where the list sits once its rows have changed.  A lazy list holds its place on the row that was
 * first in view, which is right for a list scrolled part way down and wrong for one resting at its top:
 * a row that arrives above the first one would be put out of sight, above a list that looks unscrolled.
 */
@OptIn(ExperimentalTestApi::class)
class ParametersListPositionTest {
	/**
	 * How far the list is scrolled, as the list itself reports it.
	 *
	 * @return Float Zero with the list resting at its very top, and more than zero anywhere else.
	 */
	private fun ComposeUiTest.listScrollOffset(): Float =
		onNode(hasScrollToIndexAction())
			.fetchSemanticsNode()
			.config[SemanticsProperties.VerticalScrollAxisRange]
			.value()

	/** A parameter created with the list at its top is shown whole, from its upper edge down. */
	@Test
	fun aParameterCreatedAtTheTopIsShownWhole() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			assertEquals(0f, listScrollOffset())

			secondaryClickAt(panelBoundsOfText(PanelNames.BODY_X).center)
			clickMenuEntry(harness.text.addKeyFormParameter)

			assertTrue(renameFieldOpen())
			assertEquals(0f, listScrollOffset(), "the new row is the first one, and the list must rest on its upper edge")
		}

	/** A group likewise. */
	@Test
	fun aGroupCreatedAtTheTopIsShownWhole() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)

			secondaryClickAt(panelBoundsOfText(PanelNames.BODY_X).center)
			clickMenuEntry(harness.text.newGroup)

			assertTrue(renameFieldOpen())
			assertEquals(0f, listScrollOffset())
		}

	/** With the list scrolled down the new row is brought into view, and whole. */
	@Test
	fun aParameterCreatedWithTheListScrolledIsShownWhole() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			harness.panelSize = DpSize(PANEL_WIDTH, PANEL_HEIGHT_SCROLLING)
			mountParametersPanel(harness)
			onNode(hasScrollToIndexAction()).performScrollToIndex(PanelRows.COUNT - 1)
			waitForIdle()
			assertTrue(listScrollOffset() > 0f, "the list has to be scrolled off its top for this to mean anything")

			secondaryClickAt(panelBoundsOfText(PanelNames.BREATH).center)
			clickMenuEntry(harness.text.addKeyFormParameter)

			assertTrue(renameFieldOpen())
			assertEquals(0f, listScrollOffset())
		}

	/** A group created with the list scrolled down likewise. */
	@Test
	fun aGroupCreatedWithTheListScrolledIsShownWhole() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			harness.panelSize = DpSize(PANEL_WIDTH, PANEL_HEIGHT_SCROLLING)
			mountParametersPanel(harness)
			onNode(hasScrollToIndexAction()).performScrollToIndex(PanelRows.COUNT - 1)
			waitForIdle()

			secondaryClickAt(panelBoundsOfText(PanelNames.BREATH).center)
			clickMenuEntry(harness.text.newGroup)

			assertTrue(renameFieldOpen())
			assertEquals(0f, listScrollOffset())
		}

	/**
	 * A first row that comes back by undo comes back into view.  In a panel too short for its rows: a list
	 * that fits has nowhere to scroll to, and shows its first row whatever it holds its place on.
	 */
	@Test
	fun aFirstRowRestoredByUndoIsShown() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			harness.panelSize = DpSize(PANEL_WIDTH, PANEL_HEIGHT_SCROLLING)
			mountParametersPanel(harness)
			secondaryClickAt(panelBoundsOfText(PanelNames.FACE).center)
			clickMenuEntry(harness.text.deleteGroup)
			assertFalse(showsText(PanelNames.FACE))

			runOnIdle { harness.session.undo() }
			waitForIdle()

			assertTrue(showsText(PanelNames.FACE), "the restored row is the first one, and must not sit above the list")
			assertEquals(0f, listScrollOffset())
		}

	/** A row dragged to the top of a list resting at its top is shown where it landed. */
	@Test
	fun aRowMovedToTheTopIsShown() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			harness.panelSize = DpSize(PANEL_WIDTH, PANEL_HEIGHT_SCROLLING)
			mountParametersPanel(harness)
			val grip = gripBounds(harness, PanelRows.EYE_OPEN)
			val first = gripBounds(harness, PanelRows.FACE)

			drag(grip.center, listOf(first.center, first.at(0.5f, 0.1f)))

			assertEquals(PanelIds.eyeOpen.raw, rootOrderOf(harness.session.model.value).first(), "the drop must really have put the row first")
			assertEquals(0f, listScrollOffset())
			assertTrue(panelBoundsOfText(PanelNames.EYE_OPEN).top < panelBoundsOfText(PanelNames.FACE).top)
		}

	/** A list scrolled part way down keeps its place when a row arrives above it. */
	@Test
	fun aScrolledListKeepsItsPlaceWhenARowArrivesAbove() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			harness.panelSize = DpSize(PANEL_WIDTH, PANEL_HEIGHT_SCROLLING)
			mountParametersPanel(harness)
			secondaryClickAt(panelBoundsOfText(PanelNames.FACE).center)
			clickMenuEntry(harness.text.deleteGroup)
			onNode(hasScrollToIndexAction()).performScrollToIndex(PanelRows.COUNT - 2)
			waitForIdle()
			val breathBefore = panelBoundsOfText(PanelNames.BREATH).top

			runOnIdle { harness.session.undo() }
			waitForIdle()

			assertNotNull(harness.session.model.value.parameterTree.firstOrNull(), "the undo must really have restored the group")
			assertEquals(breathBefore, panelBoundsOfText(PanelNames.BREATH).top, "the rows in view must not move under the pointer")
		}
}