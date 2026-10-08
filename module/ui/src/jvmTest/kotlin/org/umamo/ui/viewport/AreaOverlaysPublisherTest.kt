package org.umamo.ui.viewport

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.edit.GridConfig
import org.umamo.render.FrameOverlays
import org.umamo.ui.LocalSettings
import org.umamo.ui.workspace.commands.inMemorySettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The publisher hands the render service an area's render options as the area mounts and whenever its
 * overlay state or the application's grid setting changes, as one value the engine compares whole.
 */
@OptIn(ExperimentalTestApi::class)
class AreaOverlaysPublisherTest {
	/** The first push lands as the area mounts, carrying the state's flags over the setting's geometry. */
	@Test
	fun thePublisherSeedsTheAreaOnMount() =
		runComposeUiTest {
			val service = StubPuppetViewportService()
			val state = ViewportOverlayState(OverlaySurface.Viewport2D)
			setContent {
				CompositionLocalProvider(LocalSettings provides inMemorySettings()) {
					AreaOverlaysPublisher(service, "area", state)
				}
			}
			waitForIdle()

			assertTrue(service.pushedAreaOverlays.isNotEmpty(), "the area is seeded")
			assertEquals("area" to AreaOverlays.Default, service.pushedAreaOverlays.first())
		}

	/** A flipped row and the master each push a new value; the master off hides every frame flag. */
	@Test
	fun aFlipPushesTheNewValue() =
		runComposeUiTest {
			val service = StubPuppetViewportService()
			val state = ViewportOverlayState(OverlaySurface.Viewport2D)
			setContent {
				CompositionLocalProvider(LocalSettings provides inMemorySettings()) {
					AreaOverlaysPublisher(service, "area", state)
				}
			}
			waitForIdle()

			state.showGrid = false
			waitForIdle()
			assertEquals(FrameOverlays(gridLines = false, axes = true, meshOverlay = true), service.areaOverlays("area")?.frame, "the grid row reached the service")

			state.showOverlays = false
			waitForIdle()
			assertEquals(FrameOverlays(gridLines = false, axes = false, meshOverlay = false), service.areaOverlays("area")?.frame, "the master off hides every frame flag")
		}

	/** A change to the application's grid setting pushes the new geometry. */
	@Test
	fun aGridSettingChangePushesTheGeometry() =
		runComposeUiTest {
			val service = StubPuppetViewportService()
			val settings = inMemorySettings()
			setContent {
				CompositionLocalProvider(LocalSettings provides settings) {
					AreaOverlaysPublisher(service, "area", ViewportOverlayState(OverlaySurface.Viewport2D))
				}
			}
			waitForIdle()
			assertEquals(GridConfig(100f, 10), service.areaOverlays("area")?.grid, "the setting's defaults seed the geometry")

			settings.setDouble(ViewportSettings.GRID_SCALE_KEY, 50.0)
			waitForIdle()

			assertEquals(GridConfig(50f, 10), service.areaOverlays("area")?.grid, "the changed setting reached the service")
		}

	/** A UV editor's area never asks for the world axes. */
	@Test
	fun aUvAreaNeverAsksForAxes() =
		runComposeUiTest {
			val service = StubPuppetViewportService()
			setContent {
				CompositionLocalProvider(LocalSettings provides inMemorySettings()) {
					AreaOverlaysPublisher(service, "uv", ViewportOverlayState(OverlaySurface.UvEditor))
				}
			}
			waitForIdle()

			assertEquals(FrameOverlays(gridLines = true, axes = false, meshOverlay = true), service.areaOverlays("uv")?.frame)
		}
}