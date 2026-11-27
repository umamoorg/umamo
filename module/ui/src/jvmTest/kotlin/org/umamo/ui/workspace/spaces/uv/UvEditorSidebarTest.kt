package org.umamo.ui.workspace.spaces.uv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.umamo.edit.EditorSession
import org.umamo.ui.LocalSettings
import org.umamo.ui.model.LocalPuppetViewportService
import org.umamo.ui.sidebar.ProvideSidebarLocals
import org.umamo.ui.sidebar.builtInSidebarRegistry
import org.umamo.ui.theme.UmamoTheme
import org.umamo.ui.viewport.StubPuppetViewportService
import org.umamo.ui.viewport.uv.uvEditSession
import org.umamo.ui.workspace.AreaScope
import org.umamo.ui.workspace.commands.inMemorySettings
import org.umamo.ui.workspace.sidebar.AreaSidebarHub
import org.umamo.ui.workspace.sidebar.LocalAreaSidebarHub
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The UV editor area under test. */
private const val UV_AREA = "uv-area"

/** The test tag on the UV editor area's box. */
private const val AREA_TAG = "uvArea"

/**
 * The UV editor hosts the sidebar as the 2D viewport does: its area registers its sidebar state where N finds it,
 * the drawer opens on the shared tabs, and a wheel turn over the open drawer never reaches the editor's navigation,
 * though one over the surface still zooms it.
 */
@OptIn(ExperimentalTestApi::class)
class UvEditorSidebarTest {
	/**
	 * Mounts a UV editor area over [session] in a 600 by 400 box at density 1, the application's default opening
	 * the sidebar, with the stub render service.
	 *
	 * @param EditorSession session The Edit-mode session.
	 * @param AreaSidebarHub hub The hub the area registers its sidebar into.
	 * @param StubPuppetViewportService service The render service the area's navigation drives.
	 */
	private fun ComposeUiTest.mountUvEditor(session: EditorSession, hub: AreaSidebarHub, service: StubPuppetViewportService) {
		val registry = builtInSidebarRegistry()
		val settings = inMemorySettings()
		setContent {
			UmamoTheme {
				ProvideSidebarLocals(
					session,
					registry,
					LocalDensity provides Density(1f),
					LocalSettings provides settings,
					LocalPuppetViewportService provides service,
					LocalAreaSidebarHub provides hub,
				) {
					Box(modifier = Modifier.size(600.dp, 400.dp).testTag(AREA_TAG)) {
						UvEditorSpace(AreaScope(UV_AREA))
					}
				}
			}
		}
		waitForIdle()
	}

	/** The area registers its sidebar state where N finds it, and the open drawer shows the shared tabs. */
	@Test
	fun theAreaRegistersItsSidebarAndShowsTheTabs() =
		runComposeUiTest {
			val hub = AreaSidebarHub()
			mountUvEditor(uvEditSession(), hub, StubPuppetViewportService())
			val state = assertNotNull(hub.forArea(UV_AREA), "the area's sidebar is registered")
			onNodeWithText("Item").assertExists()
			onNodeWithText("Tool").assertExists()
			onNodeWithText("View").assertExists()

			runOnIdle { state.visibility.toggle(true) }
			waitForIdle()

			onAllNodesWithText("Item").assertCountEquals(0)
		}

	/** A wheel turn over the open drawer never zooms the surface; one over the surface does. */
	@Test
	fun aWheelOverTheDrawerNeverZoomsTheSurface() =
		runComposeUiTest {
			val service = StubPuppetViewportService()
			mountUvEditor(uvEditSession(), AreaSidebarHub(), service)
			val itemTab = onNodeWithText("Item").fetchSemanticsNode().boundsInRoot
			val area = onNodeWithTag(AREA_TAG).fetchSemanticsNode().boundsInRoot

			onNodeWithTag(AREA_TAG).performMouseInput {
				moveTo(itemTab.center - area.topLeft)
				scroll(1f)
			}
			waitForIdle()
			assertTrue(service.zoomRequests.isEmpty(), "the drawer kept the wheel")

			onNodeWithTag(AREA_TAG).performMouseInput {
				moveTo(Offset(60f, 200f))
				scroll(1f)
			}
			waitForIdle()
			assertEquals(listOf(UV_AREA), service.zoomRequests, "the surface beside it still zooms")
		}
}