package org.umamo.ui.workspace.spaces

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
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
			assertTrue(popupShows(GUIDES) && popupShows(TEXT), "the sections head their rows")
			assertTrue(popupShows(GRID_ROW) && popupShows(AXES_ROW), "a 2D viewport offers the grid and the axes")
			clickMenuEntry(CURSOR_ROW)

			assertFalse(overlays.showCursor)
			assertTrue(overlays.showInfo && overlays.showOverlays, "the other row and the master are untouched")
			clickMenuEntry(INFO_ROW)
			assertFalse(overlays.showInfo)
			clickMenuEntry(GRID_ROW)
			assertFalse(overlays.showGrid)
			clickMenuEntry(AXES_ROW)
			assertFalse(overlays.showAxes)
		}

	/** The UV editor's popover offers the grid row but no axis row, since its surface has no world axes. */
	@Test
	fun theUvHeaderOffersGridButNoAxis() =
		runComposeUiTest {
			val scope = AreaScope(HEADER_TEST_AREA_ID)
			setAreaHeader(kind = SpaceKind.UvEditor, headerWidth = 900.dp, puppet = mutableStateOf(emptyHeaderPuppet()), scope = scope)
			val overlays = scope.spaceState(UV_EDITOR_VIEW_STATE_KEY) { UvEditorViewState() }.overlays

			clickDescribed(VIEWPORT_OVERLAYS)

			assertTrue(popupShows(GRID_ROW), "the grid row is offered")
			assertFalse(popupShows(AXES_ROW), "the axis row is not")
			clickMenuEntry(GRID_ROW)
			assertFalse(overlays.showGrid)
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
		const val GRID_ROW = "Grid"
		const val AXES_ROW = "X/Z Axis"
		const val CURSOR_ROW = "2D Cursor"
		const val INFO_ROW = "General Information"

		/** The overflow chip's English name. */
		const val MORE = "More"
	}
}