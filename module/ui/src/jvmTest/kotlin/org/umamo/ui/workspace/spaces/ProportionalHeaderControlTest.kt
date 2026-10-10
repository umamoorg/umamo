package org.umamo.ui.workspace.spaces

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.umamo.edit.DEFAULT_PROPORTIONAL_RADIUS_WORLD
import org.umamo.edit.EditorSession
import org.umamo.edit.ProportionalFalloff
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.viewport.viewport2d.gizmoEditSession
import org.umamo.ui.viewport.viewport2d.gizmoObjectSession
import org.umamo.ui.workspace.AreaScope
import org.umamo.ui.workspace.SpaceKind
import org.umamo.ui.workspace.area.HEADER_TEST_AREA_ID
import org.umamo.ui.workspace.area.setAreaHeader
import org.umamo.ui.workspace.commands.SessionAvailability
import org.umamo.ui.workspace.commands.proportionalCommands
import org.umamo.ui.workspace.spaces.parameters.clickDescribed
import org.umamo.ui.workspace.spaces.parameters.clickMenuEntry
import org.umamo.ui.workspace.spaces.parameters.countOfDescription
import org.umamo.ui.workspace.spaces.parameters.popupShows
import org.umamo.ui.workspace.spaces.uv.UV_EDITOR_VIEW_STATE_KEY
import org.umamo.ui.workspace.spaces.uv.UvEditorViewState
import org.umamo.ui.workspace.spaces.uv.UvRadiusSurfaceKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The proportional control on the two work-surface headers, driven through the REAL header strips with the
 * real proportional commands: the glyph half toggles the tool, the chevron half opens the settings panel
 * (Connected Only, the eight curves, Proportional Size), a setting changed while the tool is off waits for
 * the next toggle, each surface's size row writes its own radius, and the control is Edit mode's alone.
 */
@OptIn(ExperimentalTestApi::class)
class ProportionalHeaderControlTest {
	/** The glyph half switches proportional editing on and off. */
	@Test
	fun theGlyphHalfTogglesProportionalEditing() =
		runComposeUiTest {
			val session = gizmoEditSession()
			mountHeader(SpaceKind.Viewport2D, session)
			assertNull(session.proportionalEdit.value, "proportional editing starts off")

			clickDescribed(TOGGLE)
			assertNotNull(session.proportionalEdit.value, "the glyph half switched it on")
			clickDescribed(TOGGLE)
			assertNull(session.proportionalEdit.value, "and off again")
		}

	/** The chevron half opens Blender's settings: Connected Only, the eight curves with the active one lit, and the size. */
	@Test
	fun thePanelListsTheSettingsWithTheActiveCurveSelected() =
		runComposeUiTest {
			val session = gizmoEditSession()
			mountHeader(SpaceKind.Viewport2D, session)

			clickDescribed(PANEL)

			assertTrue(popupShows(CONNECTED_ONLY) && popupShows(PROPORTIONAL_SIZE), "Connected Only and the size row")
			for (label in CURVE_LABELS) {
				assertTrue(popupShows(label), "the $label curve")
			}
			curveRow(SMOOTH).assertIsSelected()
			curveRow(SHARP).assertIsNotSelected()
		}

	/** A curve picked while the tool is off waits for the next toggle, and the panel stays open for the next pick. */
	@Test
	fun aCurvePickedWhileOffWaitsForTheToggle() =
		runComposeUiTest {
			val session = gizmoEditSession()
			mountHeader(SpaceKind.Viewport2D, session)

			clickDescribed(PANEL)
			clickMenuEntry(SHARP)

			assertEquals(ProportionalFalloff.Sharp, session.proportionalSettings.value.falloff, "the pick is the setting now")
			assertNull(session.proportionalEdit.value, "and the tool stays off")
			assertTrue(popupShows(SMOOTH), "the panel stays open")
			curveRow(SHARP).assertIsSelected()
			// The first click outside the open panel only closes it; the second reaches the glyph half.
			clickDescribed(TOGGLE)
			assertFalse(popupShows(SMOOTH), "a click outside closed the panel")
			clickDescribed(TOGGLE)
			assertEquals(ProportionalFalloff.Sharp, session.proportionalEdit.value?.falloff, "the next toggle brings the curve in")
		}

	/** Connected Only flipped while the tool is off waits for the next toggle too. */
	@Test
	fun connectedOnlyWhileOffWaitsForTheToggle() =
		runComposeUiTest {
			val session = gizmoEditSession()
			mountHeader(SpaceKind.Viewport2D, session)

			clickDescribed(PANEL)
			clickMenuEntry(CONNECTED_ONLY)

			assertTrue(session.proportionalSettings.value.connectedOnly, "the flag is set")
			assertNull(session.proportionalEdit.value, "and the tool stays off")
		}

	/** On the 2D viewport the size row is the session's world radius. */
	@Test
	fun theViewportSizeRowWritesTheSessionRadius() =
		runComposeUiTest {
			val session = gizmoEditSession()
			mountHeader(SpaceKind.Viewport2D, session)

			clickDescribed(PANEL)
			typeIntoField(shown = SESSION_RADIUS_SHOWN, typed = "350")

			assertEquals(350f, session.proportionalSettings.value.radiusWorld, "the session's radius took the edit")
			assertNull(session.proportionalEdit.value, "without switching the tool on")
		}

	/** On a UV editor the size row is the shown surface's own texel radius, seeded from its size, never the session's. */
	@Test
	fun theUvSizeRowWritesTheShownSurfacesRadius() =
		runComposeUiTest {
			val session = gizmoEditSession()
			val scope = AreaScope(HEADER_TEST_AREA_ID)
			val radii = scope.spaceState(UV_EDITOR_VIEW_STATE_KEY) { UvEditorViewState() }.proportionalRadii
			radii.shownSurface = UV_SURFACE
			mountHeader(SpaceKind.UvEditor, session, scope)

			clickDescribed(PANEL)
			typeIntoField(shown = UV_SEED_SHOWN, typed = "100")

			assertEquals(100f, radii.stateFor(UV_SURFACE).value, "the surface's radius took the edit")
			assertEquals(DEFAULT_PROPORTIONAL_RADIUS_WORLD, session.proportionalSettings.value.radiusWorld, "the session's world radius is untouched")
		}

	/** Outside Edit mode the header carries no proportional control. */
	@Test
	fun objectModeShowsNoProportionalControl() =
		runComposeUiTest {
			mountHeader(SpaceKind.Viewport2D, gizmoObjectSession())

			assertEquals(0, countOfDescription(TOGGLE))
			assertEquals(0, countOfDescription(PANEL))
		}

	/**
	 * Mounts a work-surface header over [session] with the real proportional commands registered.
	 *
	 * @param SpaceKind kind The work surface whose header to mount.
	 * @param EditorSession session The session the header reads and the commands drive.
	 * @param AreaScope scope The hosting area's scope.
	 */
	private fun ComposeUiTest.mountHeader(kind: SpaceKind, session: EditorSession, scope: AreaScope = AreaScope(HEADER_TEST_AREA_ID)) {
		val commands = CommandRegistry()
		for (command in proportionalCommands(session, SessionAvailability(session))) {
			commands.register(command)
		}
		setAreaHeader(kind = kind, headerWidth = 1200.dp, puppet = mutableStateOf(session.model.value), scope = scope, session = session, commands = commands)
	}

	/**
	 * The panel's row for a curve.
	 *
	 * @param String label The curve's label.
	 * @return SemanticsNodeInteraction The row.
	 */
	private fun ComposeUiTest.curveRow(label: String): SemanticsNodeInteraction = onNode(hasText(label) and hasAnyAncestor(isPopup()))

	/**
	 * Types a value into the panel field showing [shown] and commits it with Enter.
	 *
	 * @param String shown The text the field shows before the edit.
	 * @param String typed The text to type.
	 */
	private fun ComposeUiTest.typeIntoField(shown: String, typed: String) {
		onNode(hasText(shown, substring = true) and hasAnyAncestor(isPopup()), useUnmergedTree = true).performClick()
		waitForIdle()
		onNode(hasSetTextAction() and isFocused()).performTextReplacement(typed)
		onNode(hasSetTextAction() and isFocused()).performKeyInput { pressKey(Key.Enter) }
		waitForIdle()
	}

	private companion object {
		/** The glyph half's accessible name. */
		const val TOGGLE = "Toggle Proportional Editing"

		/** The chevron half's accessible name. */
		const val PANEL = "Proportional Falloff"

		/** The Connected Only checkbox. */
		const val CONNECTED_ONLY = "Connected Only"

		/** The size row's label. */
		const val PROPORTIONAL_SIZE = "Proportional Size"

		/** The Smooth curve, the default. */
		const val SMOOTH = "Smooth"

		/** The Sharp curve. */
		const val SHARP = "Sharp"

		val CURVE_LABELS = listOf(SMOOTH, "Sphere", "Root", "Inverse Square", SHARP, "Linear", "Constant", "Random")

		/** The session's default world radius as the field shows it. */
		const val SESSION_RADIUS_SHOWN = "200.00"

		/** The UV surface the size row tests show. */
		val UV_SURFACE = UvRadiusSurfaceKey(512, 512, null)

		/** That surface's seeded radius, an eighth of its shorter side, as the field shows it. */
		const val UV_SEED_SHOWN = "64.00"
	}
}