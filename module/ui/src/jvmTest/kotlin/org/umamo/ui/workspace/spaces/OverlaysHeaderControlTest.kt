package org.umamo.ui.workspace.spaces

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
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
import org.umamo.ui.viewport.GridConfig
import org.umamo.ui.workspace.AreaScope
import org.umamo.ui.workspace.SpaceKind
import org.umamo.ui.workspace.area.HEADER_TEST_AREA_ID
import org.umamo.ui.workspace.area.emptyHeaderPuppet
import org.umamo.ui.workspace.area.setAreaHeader
import org.umamo.ui.workspace.spaces.parameters.clickDescribed
import org.umamo.ui.workspace.spaces.parameters.clickMenuEntry
import org.umamo.ui.workspace.spaces.parameters.countOfDescription
import org.umamo.ui.workspace.spaces.parameters.popupShows
import org.umamo.ui.workspace.spaces.uv.UV_EDITOR_VIEW_STATE_KEY
import org.umamo.ui.workspace.spaces.uv.UvEditorViewState
import org.umamo.ui.workspace.spaces.viewport2d.VIEWPORT_VIEW_STATE_KEY
import org.umamo.ui.workspace.spaces.viewport2d.Viewport2DViewState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The overlays control on the two work-surface headers, driven through the REAL header strips: the Show
 * Overlays button flips the area's master, a popover row flips the flag it stands for, each header writes its
 * own area's state, and the control follows its host header's no-document convention.
 */
@OptIn(ExperimentalTestApi::class)
class OverlaysHeaderControlTest {
	/** Both halves of the control are on the 2D viewport's strip with a document open. */
	@Test
	fun theViewportHeaderShowsBothPartsOfTheControl() =
		runComposeUiTest {
			setAreaHeader(kind = SpaceKind.Viewport2D, headerWidth = 900.dp, puppet = mutableStateOf(emptyHeaderPuppet()))
			assertTrue(countOfDescription(SHOW_OVERLAYS) > 0, "the toggle button")
			assertTrue(countOfDescription(VIEWPORT_OVERLAYS) > 0, "the popover chevron")
		}

	/** The Show Overlays button flips the area's master and leaves every row's own flag alone. */
	@Test
	fun theMasterButtonFlipsTheAreasMasterOnly() =
		runComposeUiTest {
			val scope = AreaScope(HEADER_TEST_AREA_ID)
			setAreaHeader(kind = SpaceKind.Viewport2D, headerWidth = 900.dp, puppet = mutableStateOf(emptyHeaderPuppet()), scope = scope)
			val overlays = scope.spaceState(VIEWPORT_VIEW_STATE_KEY) { Viewport2DViewState() }.overlays
			assertTrue(overlays.showOverlays)

			clickDescribed(SHOW_OVERLAYS)

			assertFalse(overlays.showOverlays)
			assertTrue(overlays.showCursor && overlays.showInfo, "the rows keep their own flags")
			clickDescribed(SHOW_OVERLAYS)
			assertTrue(overlays.showOverlays)
		}

	/** A popover row flips the flag it stands for, under its section heading, and the popover stays open for the next. */
	@Test
	fun aRowFlipsTheAreasRow() =
		runComposeUiTest {
			val scope = AreaScope(HEADER_TEST_AREA_ID)
			setAreaHeader(kind = SpaceKind.Viewport2D, headerWidth = 900.dp, puppet = mutableStateOf(emptyHeaderPuppet()), scope = scope)
			val overlays = scope.spaceState(VIEWPORT_VIEW_STATE_KEY) { Viewport2DViewState() }.overlays

			clickDescribed(VIEWPORT_OVERLAYS)
			assertTrue(popupShows(GUIDES) && popupShows(TEXT) && popupShows(GEOMETRY), "the sections head their rows")
			assertTrue(popupShows(GRID_ROW) && popupShows(AXES_ROW) && popupShows(WIREFRAME_ROW), "a 2D viewport offers the grid, the axes, and the wireframe")
			clickMenuEntry(CURSOR_ROW)

			assertFalse(overlays.showCursor)
			assertTrue(overlays.showInfo && overlays.showOverlays, "the other row and the master are untouched")
			clickMenuEntry(INFO_ROW)
			assertFalse(overlays.showInfo)
			clickMenuEntry(GRID_ROW)
			assertFalse(overlays.showGrid)
			clickMenuEntry(AXES_ROW)
			assertFalse(overlays.showAxes)
			clickMenuEntry(WIREFRAME_ROW)
			assertTrue(overlays.showWireframe, "the wireframe row starts off and switches on")
		}

	/** The UV editor's popover offers the grid row but no axis row and no Geometry section, since its surface has neither. */
	@Test
	fun theUvHeaderOffersGridButNoAxisAndNoWireframe() =
		runComposeUiTest {
			val scope = AreaScope(HEADER_TEST_AREA_ID)
			setAreaHeader(kind = SpaceKind.UvEditor, headerWidth = 900.dp, puppet = mutableStateOf(emptyHeaderPuppet()), scope = scope)
			val overlays = scope.spaceState(UV_EDITOR_VIEW_STATE_KEY) { UvEditorViewState() }.overlays

			clickDescribed(VIEWPORT_OVERLAYS)

			assertTrue(popupShows(GRID_ROW), "the grid row is offered")
			assertFalse(popupShows(AXES_ROW), "the axis row is not")
			assertFalse(popupShows(GEOMETRY) || popupShows(WIREFRAME_ROW), "nor the Geometry section with its wireframe row")
			assertTrue(popupShows(SUBDIVISIONS_FIELD) && !popupShows(SCALE_FIELD), "the grid fields are the subdivisions alone: the major spacing is the shown image")
			clickMenuEntry(GRID_ROW)
			assertFalse(overlays.showGrid)
		}

	/** Typing a scale into the popover's field gives the area a grid of its own, and the reset beside it takes it back. */
	@Test
	fun theScaleFieldGivesTheAreaItsOwnGridAndTheResetTakesItBack() =
		runComposeUiTest {
			val scope = AreaScope(HEADER_TEST_AREA_ID)
			setAreaHeader(kind = SpaceKind.Viewport2D, headerWidth = 900.dp, puppet = mutableStateOf(emptyHeaderPuppet()), scope = scope)
			val overlays = scope.spaceState(VIEWPORT_VIEW_STATE_KEY) { Viewport2DViewState() }.overlays

			clickDescribed(VIEWPORT_OVERLAYS)
			assertTrue(popupShows(SCALE_FIELD) && popupShows(SUBDIVISIONS_FIELD), "a 2D viewport offers both grid fields")
			assertEquals(0, countOfDescription(FOLLOW_APPLICATION), "following the application, there is nothing to reset")

			onNode(hasText(SCALE_SHOWN) and hasAnyAncestor(isPopup()), useUnmergedTree = true).performClick()
			waitForIdle()
			onNode(hasSetTextAction() and isFocused()).performTextReplacement("50")
			onNode(hasSetTextAction() and isFocused()).performKeyInput { pressKey(Key.Enter) }
			waitForIdle()

			assertEquals(GridConfig(50f, 10), overlays.gridGeometry, "the edit gives the area its own grid")
			assertEquals(1, countOfDescription(FOLLOW_APPLICATION), "which shows the reset")
			clickDescribed(FOLLOW_APPLICATION)
			assertNull(overlays.gridGeometry, "the reset returns the area to the application's grid")
			assertEquals(0, countOfDescription(FOLLOW_APPLICATION), "and goes away")
		}

	/** An area that opens with a grid of its own shows the reset at once; the reset alone takes the grid back. */
	@Test
	fun anOwnGridShowsTheResetWhichTakesItBack() =
		runComposeUiTest {
			val scope = AreaScope(HEADER_TEST_AREA_ID)
			setAreaHeader(kind = SpaceKind.Viewport2D, headerWidth = 900.dp, puppet = mutableStateOf(emptyHeaderPuppet()), scope = scope)
			val overlays = scope.spaceState(VIEWPORT_VIEW_STATE_KEY) { Viewport2DViewState() }.overlays
			overlays.gridGeometry = GridConfig(50f, 4)

			clickDescribed(VIEWPORT_OVERLAYS)
			assertEquals(1, countOfDescription(FOLLOW_APPLICATION))

			clickDescribed(FOLLOW_APPLICATION)

			assertNull(overlays.gridGeometry)
			assertEquals(0, countOfDescription(FOLLOW_APPLICATION))
		}

	/** The UV editor's header drives its own view state's overlays. */
	@Test
	fun theUvHeaderDrivesTheUvStatesOverlays() =
		runComposeUiTest {
			val scope = AreaScope(HEADER_TEST_AREA_ID)
			setAreaHeader(kind = SpaceKind.UvEditor, headerWidth = 900.dp, puppet = mutableStateOf(emptyHeaderPuppet()), scope = scope)
			val overlays = scope.spaceState(UV_EDITOR_VIEW_STATE_KEY) { UvEditorViewState() }.overlays

			clickDescribed(SHOW_OVERLAYS)
			assertFalse(overlays.showOverlays)

			clickDescribed(VIEWPORT_OVERLAYS)
			clickMenuEntry(INFO_ROW)
			assertFalse(overlays.showInfo)
			assertTrue(overlays.showCursor)
		}

	/** With no document the 2D viewport's control is on the strip but disabled, like the header's other chips. */
	@Test
	fun withNoDocumentTheViewportControlIsDisabled() =
		runComposeUiTest {
			val scope = AreaScope(HEADER_TEST_AREA_ID)
			setAreaHeader(kind = SpaceKind.Viewport2D, headerWidth = 900.dp, scope = scope)
			assertTrue(countOfDescription(SHOW_OVERLAYS) > 0)

			clickDescribed(SHOW_OVERLAYS)

			assertTrue(scope.spaceState(VIEWPORT_VIEW_STATE_KEY) { Viewport2DViewState() }.overlays.showOverlays, "a disabled button flips nothing")
		}

	/** With no document the UV editor's control is absent, like the header's other items. */
	@Test
	fun withNoDocumentTheUvControlIsAbsent() =
		runComposeUiTest {
			setAreaHeader(kind = SpaceKind.UvEditor, headerWidth = 900.dp)
			assertEquals(0, countOfDescription(SHOW_OVERLAYS))
			assertEquals(0, countOfDescription(VIEWPORT_OVERLAYS))
		}

	/** Squeezed into the overflow panel, the control still works: the dots chip opens it and the button flips the master. */
	@Test
	fun theControlStillWorksFromTheOverflowPanel() =
		runComposeUiTest {
			val scope = AreaScope(HEADER_TEST_AREA_ID)
			setAreaHeader(kind = SpaceKind.Viewport2D, headerWidth = 48.dp, puppet = mutableStateOf(emptyHeaderPuppet()), scope = scope)
			val overlays = scope.spaceState(VIEWPORT_VIEW_STATE_KEY) { Viewport2DViewState() }.overlays

			clickDescribed(MORE)
			clickDescribed(SHOW_OVERLAYS)

			assertFalse(overlays.showOverlays)
		}

	private companion object {
		/** The toggle button's English name; it doubles as its accessible label. */
		const val SHOW_OVERLAYS = "Show Overlays"

		/** The popover chevron's English name; it doubles as its accessible label. */
		const val VIEWPORT_OVERLAYS = "Viewport Overlays"

		/** The two section headings and the rows, in English. */
		const val GUIDES = "Guides"
		const val TEXT = "Text"
		const val GEOMETRY = "Geometry"
		const val GRID_ROW = "Grid"
		const val AXES_ROW = "X/Z Axis"
		const val CURSOR_ROW = "2D Cursor"
		const val INFO_ROW = "General Information"
		const val WIREFRAME_ROW = "Wireframe"

		/** The grid fields' labels, the scale the default grid shows, and the reset icon's English name. */
		const val SCALE_FIELD = "Scale"
		const val SUBDIVISIONS_FIELD = "Subdivisions"
		const val SCALE_SHOWN = "100.00"
		const val FOLLOW_APPLICATION = "Follow Application Grid"

		/** The overflow chip's English name. */
		const val MORE = "More"
	}
}