package org.umamo.ui.viewport

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.ui.LocalSettings
import org.umamo.ui.workspace.commands.inMemorySettings
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The mirror keeps an area's overlay state told what the application's grid is, from the settings alone - no
 * render service stands behind it - so the readers that resolve the area's grid through the state see the
 * setting on a platform with no renderer too.
 */
@OptIn(ExperimentalTestApi::class)
class ApplicationGridMirrorTest {
	/** The mirror takes the setting as the state's owner mounts, and the area's readers resolve it. */
	@Test
	fun theMirrorSeedsFromTheSettingOnMount() =
		runComposeUiTest {
			val settings = inMemorySettings()
			settings.setDouble(ViewportSettings.GRID_SCALE_KEY, 50.0)
			val state = ViewportOverlayState(OverlaySurface.Viewport2D)
			setContent {
				CompositionLocalProvider(LocalSettings provides settings) {
					ApplicationGridMirror(state)
				}
			}
			waitForIdle()

			assertEquals(GridConfig(50f, 10), state.applicationGrid, "the setting landed on the mirror")
			assertEquals(GridConfig(50f, 10), state.grid, "which is what the area's readers resolve")
		}

	/** A setting change lands on the mirror; an area with a grid of its own keeps that grid while the mirror follows, for the reset to return to. */
	@Test
	fun aSettingChangeLandsOnTheMirrorUnderAnOwnGrid() =
		runComposeUiTest {
			val settings = inMemorySettings()
			val state = ViewportOverlayState(OverlaySurface.Viewport2D)
			setContent {
				CompositionLocalProvider(LocalSettings provides settings) {
					ApplicationGridMirror(state)
				}
			}
			waitForIdle()
			assertEquals(GridConfig(100f, 10), state.applicationGrid, "the setting's defaults seed the mirror")

			settings.setDouble(ViewportSettings.GRID_SCALE_KEY, 50.0)
			waitForIdle()
			assertEquals(GridConfig(50f, 10), state.applicationGrid, "the changed setting reached the mirror")

			state.gridGeometry = GridConfig(25f, 5)
			settings.setInt(ViewportSettings.GRID_SUBDIVISIONS_KEY, 4)
			waitForIdle()
			assertEquals(GridConfig(25f, 5), state.grid, "the own grid stands against the setting")
			assertEquals(GridConfig(50f, 4), state.applicationGrid, "while the mirror follows it")

			state.gridGeometry = null
			assertEquals(GridConfig(50f, 4), state.grid, "so the reset returns to the current setting")
		}
}