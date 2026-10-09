package org.umamo.ui.workspace.spaces

import org.umamo.ui.viewport.GridConfig
import org.umamo.ui.viewport.OverlaySurface
import org.umamo.ui.viewport.ViewportOverlayState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the overlays popover's catalog and the state it edits: which rows each surface offers, that a row reads
 * and writes its one flag, and that the Show Overlays master gates every effect while leaving each flag as set.
 */
class OverlayCatalogTest {
	/**
	 * The 2D viewport offers every row; the UV editor every row but the axes, the selection tint, and the
	 * wireframe, which its surface has none of.  Both offer the Opacity field, the UV editor's under a Geometry
	 * heading with no row of its own.
	 */
	@Test
	fun eachSurfaceOffersItsRowsAndFieldsInSectionOrder() {
		assertEquals(
			listOf(OverlayToggle.Grid, OverlayToggle.Axes, OverlayToggle.Cursor, OverlayToggle.Info, OverlayToggle.SelectionTint, OverlayToggle.Wireframe),
			overlayRowsFor(OverlaySurface.Viewport2D),
		)
		assertEquals(listOf(OverlayToggle.Grid, OverlayToggle.Cursor, OverlayToggle.Info), overlayRowsFor(OverlaySurface.UvEditor))
		assertEquals(OverlaySection.Guides, OverlayToggle.Grid.section)
		assertEquals(OverlaySection.Guides, OverlayToggle.Axes.section)
		assertEquals(OverlaySection.Guides, OverlayToggle.Cursor.section)
		assertEquals(OverlaySection.Text, OverlayToggle.Info.section)
		assertEquals(OverlaySection.Objects, OverlayToggle.SelectionTint.section)
		assertEquals(OverlaySection.Geometry, OverlayToggle.Wireframe.section)
		assertEquals(listOf(OverlaySection.Guides, OverlaySection.Text, OverlaySection.Objects, OverlaySection.Geometry), OverlaySection.entries)
		assertEquals(listOf(OverlayField.WireframeOpacity), overlayFieldsFor(OverlaySurface.Viewport2D))
		assertEquals(listOf(OverlayField.WireframeOpacity), overlayFieldsFor(OverlaySurface.UvEditor), "the UV editor's islands fade by the same field")
		assertEquals(OverlaySection.Geometry, OverlayField.WireframeOpacity.section)
	}

	/** The selection tint row reads and writes its flag and no other. */
	@Test
	fun theSelectionTintRowReadsAndWritesItsFlag() {
		val state = ViewportOverlayState(OverlaySurface.Viewport2D)
		assertTrue(OverlayToggle.SelectionTint.isOn(state), "the tint starts on")

		OverlayToggle.SelectionTint.set(state, false)

		assertFalse(state.showSelectionTint)
		assertFalse(OverlayToggle.SelectionTint.isOn(state))
		assertTrue(state.showGrid && state.showAxes && state.showCursor && state.showInfo, "every other row is untouched")
		assertFalse(state.showWireframe)
	}

	/** The grid, axis, and wireframe rows read and write their own flags. */
	@Test
	fun theGridAxisAndWireframeRowsReadAndWriteTheirFlags() {
		val state = ViewportOverlayState(OverlaySurface.Viewport2D)
		OverlayToggle.Grid.set(state, false)
		assertFalse(state.showGrid)
		assertFalse(OverlayToggle.Grid.isOn(state))
		assertTrue(state.showAxes && OverlayToggle.Axes.isOn(state), "the axes are untouched")
		OverlayToggle.Axes.set(state, false)
		assertFalse(state.showAxes)
		assertTrue(state.showCursor && state.showInfo, "and so are the other rows")
		assertFalse(OverlayToggle.Wireframe.isOn(state), "the wireframe row starts off")
		OverlayToggle.Wireframe.set(state, true)
		assertTrue(state.showWireframe && OverlayToggle.Wireframe.isOn(state))
		assertFalse(state.showGrid || state.showAxes, "and flips nothing else")
	}

	/** A row reads and writes its own flag and no other. */
	@Test
	fun aRowReadsAndWritesItsOwnFlagOnly() {
		val state = ViewportOverlayState(OverlaySurface.Viewport2D)
		assertTrue(OverlayToggle.Cursor.isOn(state) && OverlayToggle.Info.isOn(state))

		OverlayToggle.Cursor.set(state, false)

		assertFalse(state.showCursor)
		assertFalse(OverlayToggle.Cursor.isOn(state))
		assertTrue(state.showInfo && OverlayToggle.Info.isOn(state), "the other row is untouched")
		OverlayToggle.Info.set(state, false)
		OverlayToggle.Cursor.set(state, true)
		assertTrue(state.showCursor)
		assertFalse(state.showInfo)
	}

	/** The master gates every effect and keeps every flag, so switching it back restores the set. */
	@Test
	fun theMasterGatesEveryEffectAndKeepsEachFlag() {
		val state = ViewportOverlayState(OverlaySurface.UvEditor)
		state.showWireframe = true

		state.showOverlays = false

		assertFalse(state.effectiveCursor || state.effectiveInfo || state.effectiveGrid || state.effectiveAxes || state.effectiveWireframe || state.effectiveSelectionTint)
		assertTrue(state.showCursor && state.showInfo && state.showGrid && state.showAxes && state.showWireframe && state.showSelectionTint, "the flags keep what the rigger set")
		assertTrue(OverlayToggle.Cursor.isOn(state), "a row shows its flag, not its effect")
		state.showOverlays = true
		assertTrue(state.effectiveCursor && state.effectiveInfo && state.effectiveGrid && state.effectiveAxes && state.effectiveWireframe && state.effectiveSelectionTint)
	}

	/** Everything is on by default but the wireframe, the opacity is whole, and a reset returns there. */
	@Test
	fun defaultsAreEverythingOnButTheWireframe() {
		val state = ViewportOverlayState(OverlaySurface.Viewport2D)
		assertTrue(state.isAtDefaults)
		assertTrue(state.showOverlays && state.showGrid && state.showAxes && state.showCursor && state.showInfo && state.showSelectionTint)
		assertFalse(state.showWireframe)
		assertEquals(1f, state.wireframeOpacity)

		state.showGrid = false
		assertFalse(state.isAtDefaults)
		state.reset()
		assertTrue(state.isAtDefaults)

		state.wireframeOpacity = 0.5f
		assertFalse(state.isAtDefaults, "an opacity off whole is a deviation")
		state.showSelectionTint = false
		state.reset()
		assertTrue(state.isAtDefaults, "a reset returns the opacity and the tint too")
		assertEquals(1f, state.wireframeOpacity)
		assertTrue(state.showSelectionTint)
	}

	/** The area's grid is its own over the application's, whole on both surfaces. */
	@Test
	fun theGridIsTheAreasOwnOverTheApplications() {
		val application = GridConfig(100f, 10)
		val viewport = ViewportOverlayState(OverlaySurface.Viewport2D)
		assertEquals(application, viewport.gridOver(application), "following, the application's grid")

		viewport.gridGeometry = GridConfig(50f, 4)
		assertEquals(GridConfig(50f, 4), viewport.gridOver(application), "its own replaces it whole")
		viewport.applicationGrid = application
		assertEquals(GridConfig(50f, 4), viewport.grid, "and is what the area's readers resolve")
		assertFalse(viewport.isAtDefaults, "an own grid is a deviation")
		viewport.reset()
		assertNull(viewport.gridGeometry, "a reset returns the area to following")
		assertTrue(viewport.isAtDefaults)

		val uvEditor = ViewportOverlayState(OverlaySurface.UvEditor)
		uvEditor.gridGeometry = GridConfig(50f, 4)
		assertEquals(GridConfig(50f, 4), uvEditor.gridOver(application), "a UV editor's own grid is its own whole, the scale in texels")
	}
}