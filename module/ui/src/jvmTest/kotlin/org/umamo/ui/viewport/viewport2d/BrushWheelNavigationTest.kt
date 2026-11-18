package org.umamo.ui.viewport.viewport2d

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.umamo.edit.CIRCLE_RADIUS_STEP_PX
import org.umamo.edit.DEFAULT_BRUSH_RADIUS_WORLD
import org.umamo.edit.EditorSession
import org.umamo.edit.transform.beginBrush
import org.umamo.render.PuppetTextures
import org.umamo.render.SourceArtRasters
import org.umamo.ui.LocalSettings
import org.umamo.ui.theme.UmamoTheme
import org.umamo.ui.viewport.AtlasPageBinding
import org.umamo.ui.viewport.LiveParams
import org.umamo.ui.viewport.StubPuppetViewportService
import org.umamo.ui.viewport.gizmo.LEFT_AREA
import org.umamo.ui.viewport.gizmo.RIGHT_AREA
import org.umamo.ui.viewport.gizmo.gizmoAreaTag
import org.umamo.ui.viewport.gizmo.moveIn
import org.umamo.ui.viewport.gizmo.scrollIn
import org.umamo.ui.workspace.commands.inMemorySettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/*
 * The deform brush's wheel: it resizes the brush, and the area the brush is armed in does not zoom.
 *
 * Two fixtures, one behaviour each.  The gizmo fixture mounts the overlays the app mounts, so the brush's own
 * wheel-resize runs there.  The viewport fixture mounts the real 2D viewport surface over the stub render
 * service, so the area's navigation loop - the one that pans and zooms - runs there.
 */

/**
 * The deform brush's wheel: it resizes the brush, and the area the brush is armed in does not zoom with it.
 */
@OptIn(ExperimentalTestApi::class)
class BrushWheelNavigationTest {
	/**
	 * Mounts the real 2D viewport surface over [session], with the stub render service behind it, so the area's
	 * navigation loop runs the way it does in the app.
	 *
	 * @param EditorSession session The session the area runs over.
	 * @param StubPuppetViewportService service The render service the area's navigation drives.
	 */
	private fun ComposeUiTest.mountViewport(session: EditorSession, service: StubPuppetViewportService) {
		val settings = inMemorySettings()
		val puppet = session.model.value
		val textures = PuppetTextures(emptyList(), emptyMap(), false)

		setContent {
			UmamoTheme {
				CompositionLocalProvider(
					LocalDensity provides Density(1f),
					LocalSettings provides settings,
				) {
					val liveParams = remember { LiveParams(emptyMap()) }
					val binding =
						rememberPuppetViewportHost(
							puppet = puppet,
							atlasPages = AtlasPageBinding(puppet.atlas, textures),
							artRasters = SourceArtRasters { null },
							liveParams = liveParams,
							session = session,
							serviceFactory = { _, _, _ -> service },
						)

					Box(modifier = Modifier.size(RIG_AREA_WIDTH.dp, RIG_AREA_HEIGHT.dp).testTag(gizmoAreaTag(LEFT_AREA))) {
						binding.host.Viewport2D(LEFT_AREA, Modifier.fillMaxSize())
					}
				}
			}
		}
		waitForIdle()
	}

	/** The wheel over an armed brush resizes the brush. */
	@Test
	fun theWheelResizesTheArmedBrush() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoEditSession())
			fixture.session.beginBrush(LEFT_AREA)
			waitForIdle()

			moveIn(LEFT_AREA, listOf(Offset(40f, 40f)))
			scrollIn(LEFT_AREA, -1f)

			val brush = assertNotNull(fixture.session.activeBrush.value, "the brush stays armed")
			assertEquals(DEFAULT_BRUSH_RADIUS_WORLD + CIRCLE_RADIUS_STEP_PX, brush.radiusWorld, "wheel-up grows the brush")
		}

	/** The area the brush is armed in does not zoom: the wheel belongs to the brush there. */
	@Test
	fun theArmedBrushKeepsTheWheelFromZooming() =
		runComposeUiTest {
			val service = StubPuppetViewportService()
			val session = gizmoEditSession()
			mountViewport(session, service)
			session.beginBrush(LEFT_AREA)
			waitForIdle()

			moveIn(LEFT_AREA, listOf(Offset(40f, 40f)))
			scrollIn(LEFT_AREA, 1f)

			assertTrue(service.zoomRequests.isEmpty(), "the armed brush's area does not zoom")
		}

	/** A brush armed in another area leaves this area zooming: the gate is per-area. */
	@Test
	fun theBrushInAnotherAreaLeavesThisAreaZooming() =
		runComposeUiTest {
			val service = StubPuppetViewportService()
			val session = gizmoEditSession()
			mountViewport(session, service)
			session.beginBrush(RIGHT_AREA)
			waitForIdle()

			moveIn(LEFT_AREA, listOf(Offset(40f, 40f)))
			scrollIn(LEFT_AREA, 1f)

			assertEquals(listOf(LEFT_AREA), service.zoomRequests, "the wheel still zooms this area")
		}
}