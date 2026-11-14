package org.umamo.ui.workspace.spaces

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.umamo.edit.EditorMode
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.viewport.viewport2d.gizmoEditSession
import org.umamo.ui.viewport.viewport2d.gizmoObjectSession
import org.umamo.ui.workspace.SpaceKind
import org.umamo.ui.workspace.area.setAreaHeader
import org.umamo.ui.workspace.spaces.parameters.clickDescribed
import org.umamo.ui.workspace.spaces.parameters.clickMenuEntry
import org.umamo.ui.workspace.spaces.parameters.countOfDescription
import org.umamo.ui.workspace.spaces.parameters.shownInOrder
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The editor-mode dropdown on the 2D viewport's REAL header strip, with the real mode commands: the chip is named
 * for the current mode, its menu lists the editor-mode family as the registry holds it, and a pick sets the mode.
 */
@OptIn(ExperimentalTestApi::class)
class EditorModeHeaderControlTest {
	/** The chip names the mode the session is in, the menu lists every mode in the family's order, and a pick sets it. */
	@Test
	fun theChipNamesTheModeAndAPickSetsIt() =
		runComposeUiTest {
			val session = gizmoEditSession()
			setAreaHeader(kind = SpaceKind.Viewport2D, headerWidth = 1200.dp, puppet = mutableStateOf(session.model.value), session = session)
			assertEquals(1, countOfDescription(EDIT), "the chip is named for the session's mode")
			assertEquals(0, countOfDescription(OBJECT))

			clickDescribed(EDIT)

			assertEquals(listOf(OBJECT, EDIT, POSE), shownInOrder(listOf(POSE, EDIT, OBJECT)), "the family's order")

			clickMenuEntry(OBJECT)

			assertEquals(EditorMode.Object, session.mode.value, "the pick is the mode")
			assertEquals(1, countOfDescription(OBJECT), "and the chip is named for it")
			assertEquals(0, countOfDescription(EDIT))
		}

	/** A mode entered anywhere else - Tab, the palette - is the one the chip names. */
	@Test
	fun aModeEnteredElsewhereIsTheOneTheChipNames() =
		runComposeUiTest {
			val session = gizmoObjectSession()
			setAreaHeader(kind = SpaceKind.Viewport2D, headerWidth = 1200.dp, puppet = mutableStateOf(session.model.value), session = session)
			assertEquals(1, countOfDescription(OBJECT))

			runOnIdle { session.setMode(EditorMode.Edit) }
			waitForIdle()

			assertEquals(EditorMode.Edit, session.mode.value, "the rig lets Edit mode in")
			assertEquals(1, countOfDescription(EDIT))
			assertEquals(0, countOfDescription(OBJECT))
		}

	/** With no document open the chip still shows Object mode, the mode a session starts in. */
	@Test
	fun withNoDocumentTheChipShowsObjectMode() =
		runComposeUiTest {
			setAreaHeader(kind = SpaceKind.Viewport2D, headerWidth = 1200.dp)

			assertEquals(1, countOfDescription(OBJECT))
		}

	/** The chip takes its face from the family, so with no mode command registered it is named by the family alone. */
	@Test
	fun aChipWithNoModeToShowIsNamedByTheFamily() =
		runComposeUiTest {
			val session = gizmoObjectSession()
			setAreaHeader(kind = SpaceKind.Viewport2D, headerWidth = 1200.dp, puppet = mutableStateOf(session.model.value), session = session, commands = CommandRegistry())

			assertEquals(1, countOfDescription(FAMILY))
			assertEquals(0, countOfDescription(OBJECT))
		}

	private companion object {
		/** Object mode's label, its command's title. */
		const val OBJECT = "Object Mode"

		/** Edit mode's label. */
		const val EDIT = "Edit Mode"

		/** Pose mode's label. */
		const val POSE = "Pose Mode"

		/** The editor-mode family's heading. */
		const val FAMILY = "Mode"
	}
}