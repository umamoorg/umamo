package org.umamo.ui.viewport

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.render.FrameOverlays
import org.umamo.ui.LocalSettings
import org.umamo.ui.workspace.LocalRelationPick
import org.umamo.ui.workspace.PickKind
import org.umamo.ui.workspace.RelationPickController
import org.umamo.ui.workspace.commands.inMemorySettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The publisher hands the render service an area's render options as the area mounts and whenever its
 * overlay state or the application's grid setting changes, as one value the engine compares whole.
 */
@OptIn(ExperimentalTestApi::class)
class AreaOverlaysPublisherTest {
	/** What a default 2D area asks for: everything but the wireframe and every deformer, whose rows are off by default. */
	private val viewportDefaults = FrameOverlays(gridLines = true, axes = true, meshOverlay = true, wireframe = false, allLattices = false)

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
			assertEquals("area" to AreaOverlays(GridConfig(), viewportDefaults), service.pushedAreaOverlays.first())
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
			assertEquals(viewportDefaults.copy(gridLines = false), service.areaOverlays("area")?.frame, "the grid row reached the service")

			state.showWireframe = true
			waitForIdle()
			assertEquals(viewportDefaults.copy(gridLines = false, wireframe = true), service.areaOverlays("area")?.frame, "the wireframe row reached the service")

			state.showOverlays = true
			state.showSelectionTint = false
			waitForIdle()
			assertEquals(viewportDefaults.copy(gridLines = false, wireframe = true, selectionTint = false), service.areaOverlays("area")?.frame, "the tint row reached the service")

			state.wireframeOpacity = 0.25f
			waitForIdle()
			assertEquals(viewportDefaults.copy(gridLines = false, wireframe = true, selectionTint = false, wireframeOpacity = 0.25f), service.areaOverlays("area")?.frame, "the opacity reached the service")

			state.cullHiddenWireframe = false
			waitForIdle()
			assertEquals(viewportDefaults.copy(gridLines = false, wireframe = true, selectionTint = false, wireframeOpacity = 0.25f, wireframeCulling = false), service.areaOverlays("area")?.frame, "the culling row reached the service")

			state.showDeformers = true
			waitForIdle()
			assertEquals(
				viewportDefaults.copy(gridLines = false, wireframe = true, selectionTint = false, wireframeOpacity = 0.25f, wireframeCulling = false, allLattices = true),
				service.areaOverlays("area")?.frame,
				"the deformers row reached the service",
			)

			state.showOverlays = false
			waitForIdle()
			assertEquals(
				FrameOverlays(gridLines = false, axes = false, meshOverlay = false, wireframe = false, selectionTint = false, wireframeOpacity = 0.25f, wireframeCulling = false, allLattices = false),
				service.areaOverlays("area")?.frame,
				"the master off hides every frame flag",
			)
		}

	/** A change to the application's grid setting pushes the new geometry to an area that follows it. */
	@Test
	fun aGridSettingChangePushesTheGeometry() =
		runComposeUiTest {
			val service = StubPuppetViewportService()
			val settings = inMemorySettings()
			val state = ViewportOverlayState(OverlaySurface.Viewport2D)
			setContent {
				CompositionLocalProvider(LocalSettings provides settings) {
					AreaOverlaysPublisher(service, "area", state)
				}
			}
			waitForIdle()
			assertEquals(GridConfig(100f, 10), service.areaOverlays("area")?.grid, "the setting's defaults seed the geometry")

			settings.setDouble(ViewportSettings.GRID_SCALE_KEY, 50.0)
			waitForIdle()

			assertEquals(GridConfig(50f, 10), service.areaOverlays("area")?.grid, "the changed setting reached the service")
		}

	/** An area given a grid of its own pushes that grid, and a setting change no longer reaches what it draws. */
	@Test
	fun anOwnGridIsPushedAndHoldsAgainstTheSetting() =
		runComposeUiTest {
			val service = StubPuppetViewportService()
			val settings = inMemorySettings()
			val state = ViewportOverlayState(OverlaySurface.Viewport2D)
			setContent {
				CompositionLocalProvider(LocalSettings provides settings) {
					AreaOverlaysPublisher(service, "area", state)
				}
			}
			waitForIdle()

			state.gridGeometry = GridConfig(25f, 5)
			waitForIdle()
			assertEquals(GridConfig(25f, 5), service.areaOverlays("area")?.grid, "the own grid reached the service")

			settings.setDouble(ViewportSettings.GRID_SCALE_KEY, 50.0)
			waitForIdle()
			assertEquals(GridConfig(25f, 5), service.areaOverlays("area")?.grid, "the setting no longer reaches what the area draws")

			state.gridGeometry = null
			waitForIdle()
			assertEquals(GridConfig(50f, 10), service.areaOverlays("area")?.grid, "following again, the area draws the current setting")
		}

	/** A UV editor's area is pushed the UV grid pair: 256 texels in 8 by default, moving with a UV key and never with a 2D key. */
	@Test
	fun aUvAreaFollowsTheUvGridSetting() =
		runComposeUiTest {
			val service = StubPuppetViewportService()
			val settings = inMemorySettings()
			val state = ViewportOverlayState(OverlaySurface.UvEditor)
			setContent {
				CompositionLocalProvider(LocalSettings provides settings) {
					AreaOverlaysPublisher(service, "uv", state)
				}
			}
			waitForIdle()
			assertEquals(GridConfig(256f, 8), service.areaOverlays("uv")?.grid, "the UV setting's defaults seed the geometry")
			val pushesBefore = service.pushedAreaOverlays.size

			settings.setDouble(ViewportSettings.GRID_SCALE_KEY, 50.0)
			waitForIdle()
			assertEquals(GridConfig(256f, 8), service.areaOverlays("uv")?.grid, "the 2D viewport's setting never reaches a UV area")
			assertEquals(pushesBefore, service.pushedAreaOverlays.size, "and pushes nothing new to it")

			settings.setDouble(ViewportSettings.UV_GRID_SCALE_KEY, 512.0)
			waitForIdle()
			assertEquals(GridConfig(512f, 8), service.areaOverlays("uv")?.grid, "the changed UV setting reached the service")
		}

	/** A UV editor's area never asks for the world axes, the wireframe, or every deformer. */
	@Test
	fun aUvAreaNeverAsksForAxesOrTheWireframe() =
		runComposeUiTest {
			val service = StubPuppetViewportService()
			setContent {
				CompositionLocalProvider(LocalSettings provides inMemorySettings()) {
					AreaOverlaysPublisher(service, "uv", ViewportOverlayState(OverlaySurface.UvEditor).apply { showWireframe = true })
				}
			}
			waitForIdle()

			assertEquals(FrameOverlays(gridLines = true, axes = false, meshOverlay = true, wireframe = false, allLattices = false), service.areaOverlays("uv")?.frame)
		}

	/** A pick that takes a deformer draws every lattice in a 2D area while it is armed; the area's own value returns after. */
	@Test
	fun anArmedDeformerPickDrawsEveryLattice() =
		runComposeUiTest {
			val service = StubPuppetViewportService()
			val relationPick = RelationPickController()
			val viewportState = ViewportOverlayState(OverlaySurface.Viewport2D)
			val uvState = ViewportOverlayState(OverlaySurface.UvEditor)
			setContent {
				CompositionLocalProvider(LocalSettings provides inMemorySettings(), LocalRelationPick provides relationPick) {
					AreaOverlaysPublisher(service, "area", viewportState)
					AreaOverlaysPublisher(service, "uv", uvState)
				}
			}
			waitForIdle()
			val uvBefore = service.areaOverlays("uv")

			relationPick.arm(setOf(PickKind.Part)) {}
			waitForIdle()
			assertFalse(assertNotNull(service.areaOverlays("area")).frame.allLattices, "a part pick has no lattice to click")

			relationPick.arm(setOf(PickKind.Deformer)) {}
			waitForIdle()
			assertTrue(assertNotNull(service.areaOverlays("area")).frame.allLattices, "every lattice is there to click")
			assertEquals(uvBefore, service.areaOverlays("uv"), "a UV area draws no lattice")

			relationPick.cancel()
			waitForIdle()
			assertEquals(viewportDefaults, service.areaOverlays("area")?.frame, "the area's own value returns")
		}
}