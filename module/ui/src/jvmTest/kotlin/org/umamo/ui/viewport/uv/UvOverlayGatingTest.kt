package org.umamo.ui.viewport.uv

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
 * The UV editor's cursor and HUD overlays gate themselves on the area's overlay state the way the 2D
 * viewport's do: hidden by their own row or by the Show Overlays master, shown with no state provided.
 */
@OptIn(ExperimentalTestApi::class)
class UvOverlayGatingTest {
	/** The zoom readout shows with no state and with a default state, and hides under the info row or the master. */
	@Test
	fun theInfoChipsFollowTheInfoFlagUnderTheMaster() =
		runComposeUiTest {
			val session = emptySession()
			val shown = ViewportOverlayState(OverlaySurface.UvEditor)
			val infoOff = ViewportOverlayState(OverlaySurface.UvEditor).also { state -> state.showInfo = false }
			val masterOff = ViewportOverlayState(OverlaySurface.UvEditor).also { state -> state.showOverlays = false }
			setContent {
				UmamoTheme {
					UvHudOverlay(areaId = "bare", session = session, liveCamera = ViewportCamera(0f, 0f, 1f), proportionalRadiusDisplay = null, placementDragStatus = null)
					for (state in listOf(shown, infoOff, masterOff)) {
						CompositionLocalProvider(LocalAreaOverlays provides state) {
							UvHudOverlay(areaId = "area", session = session, liveCamera = ViewportCamera(0f, 0f, 1f), proportionalRadiusDisplay = null, placementDragStatus = null)
						}
					}
				}
			}
			waitForIdle()

			assertEquals(2, onAllNodesWithText(ZOOM_READOUT, useUnmergedTree = true).fetchSemanticsNodes().size)
		}

	/** The UV cursor marker shows with no state and with a default state, and hides under its row or the master. */
	@Test
	fun theCursorMarkerFollowsTheCursorFlagUnderTheMaster() =
		runComposeUiTest {
			val session = emptySession()
			session.setUvCursor(0.5f, 0.5f)
			val frame = atlasPageEditFrame(256, 256)
			val shown = ViewportOverlayState(OverlaySurface.UvEditor)
			val cursorOff = ViewportOverlayState(OverlaySurface.UvEditor).also { state -> state.showCursor = false }
			val masterOff = ViewportOverlayState(OverlaySurface.UvEditor).also { state -> state.showOverlays = false }
			setContent {
				UmamoTheme {
					UvCursorOverlay(session = session, frame = frame, camera = ViewportCamera(0f, 0f, 1f), widthPx = 200, heightPx = 200)
					for (state in listOf(shown, cursorOff, masterOff)) {
						CompositionLocalProvider(LocalAreaOverlays provides state) {
							UvCursorOverlay(session = session, frame = frame, camera = ViewportCamera(0f, 0f, 1f), widthPx = 200, heightPx = 200)
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