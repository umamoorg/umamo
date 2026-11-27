package org.umamo.ui.workspace.spaces

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.umamo.edit.TransformPivotMode
import org.umamo.ui.action.CommandFamily
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.viewport.viewport2d.gizmoEditSession
import org.umamo.ui.workspace.SpaceKind
import org.umamo.ui.workspace.area.setAreaHeader
import org.umamo.ui.workspace.commands.shellCommandTables
import org.umamo.ui.workspace.spaces.parameters.clickDescribed
import org.umamo.ui.workspace.spaces.parameters.clickMenuEntry
import org.umamo.ui.workspace.spaces.parameters.popupShows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The transform pivot control on the 2D viewport's REAL header strip, with the real pivot commands: the chip
 * wears the current pivot's glyph and no label, the panel opens under its Transform Pivot Point heading with
 * one option per pivot and the current one lit, and a pick sets the pivot while the panel stays open.  The
 * options are the pivot family as the registry lists it.
 */
@OptIn(ExperimentalTestApi::class)
class PivotHeaderControlTest {
	/** The chip carries no label, the panel heads its options with the current one lit, and a pick becomes the pivot. */
	@Test
	fun thePanelHeadsItsOptionsAndAPickSetsThePivot() =
		runComposeUiTest {
			val session = gizmoEditSession()
			setAreaHeader(kind = SpaceKind.Viewport2D, headerWidth = 1200.dp, puppet = mutableStateOf(session.model.value), session = session)
			assertTrue(onAllNodes(hasText(MEDIAN)).fetchSemanticsNodes().isEmpty(), "the chip names no pivot: its glyph says which")

			clickDescribed(TITLE)

			assertTrue(popupShows(TITLE), "the panel opens under its heading")

			for (option in listOf(MEDIAN, INDIVIDUAL, ACTIVE, CURSOR)) {
				assertTrue(popupShows(option), "the $option option")
			}

			option(MEDIAN).assertIsSelected()
			option(ACTIVE).assertIsNotSelected()
			clickMenuEntry(ACTIVE)
			assertEquals(TransformPivotMode.ActiveElement, session.pivotMode.value, "the pick is the pivot")
			assertTrue(popupShows(MEDIAN), "and the panel stays open")
			option(ACTIVE).assertIsSelected()
			option(MEDIAN).assertIsNotSelected()
		}

	/** A pivot set anywhere else - the Period pie, the palette - is the one the open panel lights. */
	@Test
	fun aPivotSetElsewhereIsTheOneThePanelLights() =
		runComposeUiTest {
			val session = gizmoEditSession()
			setAreaHeader(kind = SpaceKind.Viewport2D, headerWidth = 1200.dp, puppet = mutableStateOf(session.model.value), session = session)
			clickDescribed(TITLE)
			option(MEDIAN).assertIsSelected()

			runOnIdle { session.setPivotMode(TransformPivotMode.Cursor) }
			waitForIdle()

			option(CURSOR).assertIsSelected()
			option(MEDIAN).assertIsNotSelected()
		}

	/** The panel offers the pivots the registry lists and no others: the pivot family's table is its one source. */
	@Test
	fun thePanelOffersWhatThePivotFamilyHolds() =
		runComposeUiTest {
			val session = gizmoEditSession()
			val commands = CommandRegistry()
			shellCommandTables(session)
				.filter { command -> command.group == CommandFamily.PivotMode }
				.take(2)
				.forEach { pivot -> commands.register(pivot) }
			setAreaHeader(kind = SpaceKind.Viewport2D, headerWidth = 1200.dp, puppet = mutableStateOf(session.model.value), session = session, commands = commands)

			clickDescribed(TITLE)

			assertTrue(popupShows(MEDIAN))
			assertTrue(popupShows(INDIVIDUAL))
			assertFalse(popupShows(ACTIVE), "a pivot no table registered has no option")
			assertFalse(popupShows(CURSOR))
		}

	/**
	 * The panel's option for a pivot.
	 *
	 * @param String label The pivot's label.
	 * @return SemanticsNodeInteraction The option.
	 */
	private fun ComposeUiTest.option(label: String): SemanticsNodeInteraction = onNode(hasText(label) and hasAnyAncestor(isPopup()))

	private companion object {
		/** The chip's accessible name and the panel's heading. */
		const val TITLE = "Transform Pivot Point"

		/** The Median Point option, the default pivot. */
		const val MEDIAN = "Median Point"

		/** The Individual Origins option. */
		const val INDIVIDUAL = "Individual Origins"

		/** The Active Element option. */
		const val ACTIVE = "Active Element"

		/** The 2D Cursor option. */
		const val CURSOR = "2D Cursor"
	}
}