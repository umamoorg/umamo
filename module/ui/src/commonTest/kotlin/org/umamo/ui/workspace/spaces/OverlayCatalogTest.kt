package org.umamo.ui.workspace.spaces

import org.umamo.ui.viewport.OverlaySurface
import org.umamo.ui.viewport.ViewportOverlayState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the overlays popover's catalog and the state it edits: which rows each surface offers, that a row reads
 * and writes its one flag, and that the Show Overlays master gates every effect while leaving each flag as set.
 */
class OverlayCatalogTest {
	/** The 2D viewport offers every row; the UV editor every row but the axes and the wireframe, which its surface has none of. */
	@Test
	fun eachSurfaceOffersItsRowsInSectionOrder() {
		assertEquals(
			listOf(OverlayToggle.Grid, OverlayToggle.Axes, OverlayToggle.Cursor, OverlayToggle.Info, OverlayToggle.Wireframe),
			overlayRowsFor(OverlaySurface.Viewport2D),
		)
		assertEquals(listOf(OverlayToggle.Grid, OverlayToggle.Cursor, OverlayToggle.Info), overlayRowsFor(OverlaySurface.UvEditor))
		assertEquals(OverlaySection.Guides, OverlayToggle.Grid.section)
		assertEquals(OverlaySection.Guides, OverlayToggle.Axes.section)
		assertEquals(OverlaySection.Guides, OverlayToggle.Cursor.section)
		assertEquals(OverlaySection.Text, OverlayToggle.Info.section)
		assertEquals(OverlaySection.Geometry, OverlayToggle.Wireframe.section)
		assertEquals(listOf(OverlaySection.Guides, OverlaySection.Text, OverlaySection.Geometry), OverlaySection.entries)
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

		assertFalse(state.effectiveCursor || state.effectiveInfo || state.effectiveGrid || state.effectiveAxes || state.effectiveWireframe)
		assertTrue(state.showCursor && state.showInfo && state.showGrid && state.showAxes && state.showWireframe, "the flags keep what the rigger set")
		assertTrue(OverlayToggle.Cursor.isOn(state), "a row shows its flag, not its effect")
		state.showOverlays = true
		assertTrue(state.effectiveCursor && state.effectiveInfo && state.effectiveGrid && state.effectiveAxes && state.effectiveWireframe)
	}

	/** Everything is on by default but the wireframe, and a reset returns there. */
	@Test
	fun defaultsAreEverythingOnButTheWireframe() {
		val state = ViewportOverlayState(OverlaySurface.Viewport2D)
		assertTrue(state.isAtDefaults)
		assertTrue(state.showOverlays && state.showGrid && state.showAxes && state.showCursor && state.showInfo)
		assertFalse(state.showWireframe)

		state.showGrid = false
		assertFalse(state.isAtDefaults)
		state.reset()
		assertTrue(state.isAtDefaults)
	}
}