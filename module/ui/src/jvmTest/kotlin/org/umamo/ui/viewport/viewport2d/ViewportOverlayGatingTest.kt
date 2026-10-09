package org.umamo.ui.viewport.viewport2d

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.edit.EditorSession
import org.umamo.render.ViewportCamera
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.theme.UmamoTheme
import org.umamo.ui.viewport.LocalAreaOverlays
import org.umamo.ui.viewport.OverlaySurface
import org.umamo.ui.viewport.ViewportOverlayState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The 2D viewport's cursor and HUD overlays gate themselves on the area's overlay state through
 * LocalAreaOverlays: hidden by their own row or by the Show Overlays master, shown with no state provided at
 * all (a standalone shell), and each area answering to its own state.
 */
@OptIn(ExperimentalTestApi::class)
class ViewportOverlayGatingTest {
	/** The zoom readout (the General Information chips) shows with no state and with a default state, and hides under the row or the master. */
	@Test
	fun theInfoChipsFollowTheInfoFlagUnderTheMaster() =
		runComposeUiTest {
			val session = emptySession()
			val shown = ViewportOverlayState(OverlaySurface.Viewport2D)
			val infoOff = ViewportOverlayState(OverlaySurface.Viewport2D).also { state -> state.showInfo = false }
			val masterOff = ViewportOverlayState(OverlaySurface.Viewport2D).also { state -> state.showOverlays = false }
			setContent {
				UmamoTheme {
					ViewportHudOverlay(areaId = "bare", session = session, liveCamera = ViewportCamera(0f, 0f, 1f))
					for (state in listOf(shown, infoOff, masterOff)) {
						CompositionLocalProvider(LocalAreaOverlays provides state) {
							ViewportHudOverlay(areaId = "area", session = session, liveCamera = ViewportCamera(0f, 0f, 1f))
						}
					}
				}
			}
			waitForIdle()

			assertEquals(2, onAllNodesWithText(ZOOM_READOUT, useUnmergedTree = true).fetchSemanticsNodes().size, "no state and a default state show it; the row and the master hide it")

			shown.showInfo = false
			waitForIdle()
			assertEquals(1, onAllNodesWithText(ZOOM_READOUT, useUnmergedTree = true).fetchSemanticsNodes().size, "a flip hides that area's chips alone")
		}

	/** The cursor marker shows with no state and with a default state, and hides under its row or the master; a flip reaches one area only. */
	@Test
	fun theCursorMarkerFollowsTheCursorFlagUnderTheMaster() =
		runComposeUiTest {
			val session = emptySession()
			session.setCursor2d(0f, 0f)
			val shown = ViewportOverlayState(OverlaySurface.Viewport2D)
			val cursorOff = ViewportOverlayState(OverlaySurface.Viewport2D).also { state -> state.showCursor = false }
			val masterOff = ViewportOverlayState(OverlaySurface.Viewport2D).also { state -> state.showOverlays = false }
			setContent {
				UmamoTheme {
					Cursor2dOverlay(session = session, camera = ViewportCamera(0f, 0f, 1f), widthPx = 200, heightPx = 200)
					for (state in listOf(shown, cursorOff, masterOff)) {
						CompositionLocalProvider(LocalAreaOverlays provides state) {
							Cursor2dOverlay(session = session, camera = ViewportCamera(0f, 0f, 1f), widthPx = 200, heightPx = 200)
						}
					}
				}
			}
			waitForIdle()

			assertEquals(2, onAllNodesWithContentDescription(CURSOR_LABEL, useUnmergedTree = true).fetchSemanticsNodes().size)

			shown.showCursor = false
			waitForIdle()
			assertEquals(1, onAllNodesWithContentDescription(CURSOR_LABEL, useUnmergedTree = true).fetchSemanticsNodes().size, "a flip hides that area's marker alone")
		}

	/**
	 * A session over an empty document: the overlays here read only the cursor and the camera.
	 *
	 * @return EditorSession The session.
	 */
	private fun emptySession(): EditorSession =
		EditorSession(
			PuppetModel(
				parameters = emptyList(),
				parts = emptyList(),
				deformers = emptyList(),
				drawables = emptyList(),
				rootChildren = emptyList(),
				rootPartId = null,
			),
		)

	private companion object {
		/** The zoom badge's text at a 1x camera. */
		const val ZOOM_READOUT = "100%"

		/** The cursor marker's accessible name, the 2D Cursor row's English label. */
		const val CURSOR_LABEL = "2D Cursor"
	}
}