package org.umamo.ui.sidebar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshElement
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.transform.beginMeshOperator
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.action.LocalKeymap
import org.umamo.ui.action.defaultKeymap
import org.umamo.ui.kit.field.LocalScrubCancel
import org.umamo.ui.kit.field.ScrubCancelController
import org.umamo.ui.model.LocalEditorMode
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.model.LocalPuppet
import org.umamo.ui.model.LocalPuppetRenderSync
import org.umamo.ui.model.LocalSelection
import org.umamo.ui.model.PuppetRenderSync
import org.umamo.ui.model.rememberSessionEditorState
import org.umamo.ui.viewport.gizmo.LEFT_AREA
import org.umamo.ui.viewport.gizmo.RIGHT_AREA
import org.umamo.ui.viewport.gizmo.moveIn
import org.umamo.ui.viewport.uv.RecordingPuppetRenderSync
import org.umamo.ui.viewport.viewport2d.GizmoOverlayFixture
import org.umamo.ui.viewport.viewport2d.RIG_QUAD
import org.umamo.ui.viewport.viewport2d.gizmoEditSession
import org.umamo.ui.viewport.viewport2d.mountGizmoOverlays
import org.umamo.ui.viewport.viewport2d.rigPositionsOf
import org.umamo.ui.workspace.LocalViewportChrome
import org.umamo.ui.workspace.SpaceKind
import org.umamo.ui.workspace.ViewportChromeState
import org.umamo.ui.workspace.sidebar.SidebarDrawer
import org.umamo.ui.workspace.sidebar.SidebarState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The area the sidebar belongs to, where its strip registrations show. */
private const val SIDEBAR_AREA = "sidebar-area"

/**
 * The Item tab in Edit mode: it names one covered vertex or their median, typing moves the covered vertices,
 * a cancelled scrub hands the renderer back, and while a modal Grab runs in a viewport the numbers follow the
 * frames it publishes, falling back to the committed ones when the gesture is abandoned.  The rig's quad has
 * vertex 0 at world (0, 0) and vertex 1 at (20, 0).
 */
@OptIn(ExperimentalTestApi::class)
class EditMedianPanelTest {
	/** Where the pointer rests before a Grab latches. */
	private val gestureStart = Offset(200f, 150f)

	/** Forty pixels right of [gestureStart]: ten world units at the rig's zoom. */
	private val tenUnitsRight = Offset(240f, 150f)

	/**
	 * Mounts the rig's two viewport areas over [session] with an open sidebar beside them, on its Item tab.
	 *
	 * @param EditorSession session The Edit-mode session.
	 * @param PuppetRenderSync? renderSync The render sync a scrub previews through, or null for none.
	 * @param ScrubCancelController scrubCancel The shell's scrub seam, so a case can cancel the way Escape does.
	 * @return GizmoOverlayFixture The overlays' fixture.
	 */
	private fun ComposeUiTest.mountWithSidebar(
		session: EditorSession,
		renderSync: PuppetRenderSync? = null,
		scrubCancel: ScrubCancelController = ScrubCancelController(),
	): GizmoOverlayFixture {
		val sidebarRegistry = SidebarRegistry().also { registry -> registerBuiltInSidebar(registry) }

		return mountGizmoOverlays(session) {
			val puppet by session.model.collectAsState()
			val editorState = rememberSessionEditorState(session)
			CompositionLocalProvider(
				LocalCommands provides CommandRegistry(),
				LocalKeymap provides defaultKeymap(),
				LocalSidebarRegistry provides sidebarRegistry,
				LocalViewportChrome provides ViewportChromeState(showSidebar = true),
				LocalPuppetRenderSync provides renderSync,
				LocalScrubCancel provides scrubCancel,
				LocalPuppet provides puppet,
				LocalEditorSession provides session,
				LocalSelection provides editorState,
				LocalEditorMode provides editorState,
			) {
				Box(modifier = Modifier.size(420.dp, 300.dp)) {
					SidebarDrawer(
						areaId = SIDEBAR_AREA,
						space = SpaceKind.Viewport2D,
						state = SidebarState(),
						passPointerThrough = false,
						modifier = Modifier.align(Alignment.CenterEnd),
					)
				}
			}
		}
	}

	/**
	 * Selects [vertices] on the quad, replacing the mesh selection.
	 *
	 * @param EditorSession session The session.
	 * @param List<Int> vertices The quad's vertices to select.
	 */
	private fun ComposeUiTest.selectQuadVertices(session: EditorSession, vertices: List<Int>) {
		runOnIdle {
			session.setMeshSelection(
				session.meshSelection.value.copy(
					elementsByDrawable = mapOf(RIG_QUAD to vertices.map { vertexIndex -> MeshElement.Vertex(vertexIndex) }.toSet()),
					activeElement = null,
				),
			)
		}
		waitForIdle()
	}

	/** One covered vertex reads as the vertex, several as their median, and none as nothing selected. */
	@Test
	fun theLabelNamesTheVertexOrTheMedian() =
		runComposeUiTest {
			val session = gizmoEditSession(elements = listOf(MeshElement.Vertex(1)))
			mountWithSidebar(session)
			onNodeWithText("Vertex:").assertExists()
			onNodeWithText("20.0").assertExists()

			selectQuadVertices(session, listOf(0, 1))
			onNodeWithText("Median:").assertExists()
			onNodeWithText("10.0").assertExists()

			selectQuadVertices(session, emptyList())
			onNodeWithText("Nothing selected.").assertExists()
		}

	/** A typed X moves the covered vertices together and registers the strip in the sidebar's area. */
	@Test
	fun aTypedValueMovesTheCoveredVertices() =
		runComposeUiTest {
			val session = gizmoEditSession(elements = listOf(MeshElement.Vertex(0), MeshElement.Vertex(1)))
			mountWithSidebar(session)

			onNodeWithText("10.0").performMouseInput { click() }
			waitForIdle()
			onNode(hasSetTextAction() and isFocused()).performTextReplacement("50")
			onNode(hasSetTextAction() and isFocused()).performKeyInput { pressKey(Key.Enter) }
			waitForIdle()

			assertEquals(listOf(40f, 0f, 60f, 0f, 20f, 20f, 0f, 20f), rigPositionsOf(session, RIG_QUAD))
			assertEquals(SIDEBAR_AREA, assertNotNull(session.adjustableOperation.value).areaId)
			onNodeWithText("50.0").assertExists()
		}

	/** A scrub previews through the render sync, and cancelling it through the shell's seam resyncs and commits nothing. */
	@Test
	fun aCancelledScrubResyncsAndCommitsNothing() =
		runComposeUiTest {
			val session = gizmoEditSession(elements = listOf(MeshElement.Vertex(0), MeshElement.Vertex(1)))
			val renderSync = RecordingPuppetRenderSync()
			val scrubCancel = ScrubCancelController()
			mountWithSidebar(session, renderSync, scrubCancel)
			val before = session.model.value

			onNodeWithText("10.0").performMouseInput {
				advanceEventTime(GESTURE_GAP_MILLIS)
				moveTo(center)
				press()

				for (stepIndex in 1..SCRUB_STEPS) {
					advanceEventTime(GESTURE_STEP_MILLIS)
					moveTo(center + Offset(SCRUB_PIXELS * stepIndex / SCRUB_STEPS, 0f))
				}
			}
			waitForIdle()
			assertTrue(renderSync.previewed.isNotEmpty(), "precondition: the scrub previewed")
			val parked = assertNotNull(scrubCancel.cancel, "the scrub parks its cancel for Escape")
			runOnIdle { parked() }
			waitForIdle()

			assertEquals(1, renderSync.resyncs, "the cancel hands the renderer back")
			assertSame(before, session.model.value, "a cancelled scrub commits nothing")
			onNodeWithText("10.0").assertExists()
		}

	/**
	 * While a Grab runs in a viewport the fields show each frame it publishes, not the pointer: with the drive held
	 * they keep the committed value, and once it lands they show the moved one.
	 */
	@Test
	fun theFieldsFollowALiveGrab() =
		runComposeUiTest {
			val session = gizmoEditSession(elements = listOf(MeshElement.Vertex(0)))
			val fixture = mountWithSidebar(session)
			moveIn(LEFT_AREA, listOf(gestureStart))
			session.beginMeshOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitForIdle()
			fixture.driveDispatcher.hold()

			moveIn(LEFT_AREA, listOf(tenUnitsRight))
			onAllNodesWithText("0.0").assertCountEquals(2)
			fixture.driveDispatcher.release()
			waitForIdle()

			onNodeWithText("10.0").assertExists()
			onAllNodesWithText("0.0").assertCountEquals(1)
			assertEquals(0f, rigPositionsOf(session, RIG_QUAD)[0], "nothing is committed yet")
		}

	/** A gesture abandoned mid-drag takes its preview with it, and the fields fall back to the committed median. */
	@Test
	fun anAbandonedGrabFallsBackToTheCommittedMedian() =
		runComposeUiTest {
			val session = gizmoEditSession(elements = listOf(MeshElement.Vertex(0)))
			val fixture = mountWithSidebar(session)
			moveIn(LEFT_AREA, listOf(gestureStart))
			session.beginMeshOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitForIdle()
			moveIn(LEFT_AREA, listOf(tenUnitsRight))
			waitForIdle()
			onNodeWithText("10.0").assertExists()

			fixture.mountedAreas.value = setOf(RIGHT_AREA)
			waitForIdle()

			onAllNodesWithText("0.0").assertCountEquals(2)
			assertEquals(0f, rigPositionsOf(session, RIG_QUAD)[0])
		}

	private companion object {
		const val GESTURE_GAP_MILLIS = 1000L
		const val GESTURE_STEP_MILLIS = 16L
		const val SCRUB_PIXELS = 90f
		const val SCRUB_STEPS = 6
	}
}