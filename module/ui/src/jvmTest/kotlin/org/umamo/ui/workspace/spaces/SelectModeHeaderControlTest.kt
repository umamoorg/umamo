package org.umamo.ui.workspace.spaces

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.umamo.edit.MeshSelectMode
import org.umamo.edit.setMeshSelectMode
import org.umamo.ui.action.CommandFamily
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.viewport.viewport2d.gizmoEditSession
import org.umamo.ui.viewport.viewport2d.gizmoObjectSession
import org.umamo.ui.workspace.SpaceKind
import org.umamo.ui.workspace.area.setAreaHeader
import org.umamo.ui.workspace.commands.shellCommandTables
import org.umamo.ui.workspace.spaces.parameters.clickDescribed
import org.umamo.ui.workspace.spaces.parameters.countOfDescription
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The select-mode buttons on the 2D viewport's REAL header strip, with the real select-mode commands: one button
 * per mesh domain with the selection's lit, a pick makes it the selection's, and the buttons are the select-mode
 * family as the registry lists it.
 */
@OptIn(ExperimentalTestApi::class)
class SelectModeHeaderControlTest {
	/** There is a button per domain, the selection's is lit, and a pick sets the selection's mode. */
	@Test
	fun theButtonsLightTheSelectionsModeAndAPickSetsIt() =
		runComposeUiTest {
			val session = gizmoEditSession()
			setAreaHeader(kind = SpaceKind.Viewport2D, headerWidth = 1200.dp, puppet = mutableStateOf(session.model.value), session = session)

			for (domain in listOf(VERTEX, EDGE, FACE)) {
				assertEquals(1, countOfDescription(domain), "the $domain button")
			}

			button(VERTEX).assertIsSelected()
			button(FACE).assertIsNotSelected()

			clickDescribed(FACE)

			assertEquals(MeshSelectMode.Face, session.meshSelection.value.selectMode, "the pick is the selection's mode")
			button(FACE).assertIsSelected()
			button(VERTEX).assertIsNotSelected()
		}

	/** A mode set anywhere else - the 1 / 2 / 3 keys, the palette - is the one the buttons light. */
	@Test
	fun aModeSetElsewhereIsTheOneTheButtonsLight() =
		runComposeUiTest {
			val session = gizmoEditSession()
			setAreaHeader(kind = SpaceKind.Viewport2D, headerWidth = 1200.dp, puppet = mutableStateOf(session.model.value), session = session)

			runOnIdle { session.setMeshSelectMode(MeshSelectMode.Edge) }
			waitForIdle()

			button(EDGE).assertIsSelected()
			button(VERTEX).assertIsNotSelected()
		}

	/** Object mode selects whole objects, which have no domain to pick. */
	@Test
	fun objectModeShowsNoButtons() =
		runComposeUiTest {
			val session = gizmoObjectSession()
			setAreaHeader(kind = SpaceKind.Viewport2D, headerWidth = 1200.dp, puppet = mutableStateOf(session.model.value), session = session)

			for (domain in listOf(VERTEX, EDGE, FACE)) {
				assertEquals(0, countOfDescription(domain), "the $domain button")
			}
		}

	/** The buttons are whatever the registry lists for the family, in its order: the select table is their one source. */
	@Test
	fun theButtonsAreWhatTheSelectModeFamilyHolds() =
		runComposeUiTest {
			val session = gizmoEditSession()
			val commands = CommandRegistry()
			shellCommandTables(session)
				.filter { command -> command.group == CommandFamily.SelectMode }
				.drop(1)
				.forEach { mode -> commands.register(mode) }
			setAreaHeader(kind = SpaceKind.Viewport2D, headerWidth = 1200.dp, puppet = mutableStateOf(session.model.value), session = session, commands = commands)

			assertEquals(0, countOfDescription(VERTEX), "a mode no table registered has no button")
			assertEquals(1, countOfDescription(EDGE))
			assertEquals(1, countOfDescription(FACE))
		}

	/**
	 * The button for a mesh domain.
	 *
	 * @param String domain The button's accessible name.
	 * @return SemanticsNodeInteraction The button.
	 */
	private fun ComposeUiTest.button(domain: String): SemanticsNodeInteraction = onNode(hasContentDescription(domain))

	private companion object {
		/** The vertex button's accessible name, its command's title. */
		const val VERTEX = "Vertex Select Mode"

		/** The edge button's accessible name. */
		const val EDGE = "Edge Select Mode"

		/** The face button's accessible name. */
		const val FACE = "Face Select Mode"
	}
}