package org.umamo.ui.workspace.spaces.viewport2d

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.ParameterChange
import org.umamo.edit.ParameterSelection
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.Deformer
import org.umamo.settings.Settings
import org.umamo.storage.OkioAppStorage
import org.umamo.ui.LocalSettings
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.defaultSettingsJson
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.model.LocalPuppet
import org.umamo.ui.viewport.viewport2d.LATTICE_RIG_BODY
import org.umamo.ui.viewport.viewport2d.LATTICE_RIG_PARAMETER
import org.umamo.ui.viewport.viewport2d.latticeRigSession
import org.umamo.ui.workspace.ViewportHost
import org.umamo.ui.workspace.commands.commandFixtureSession
import org.umamo.ui.workspace.shell.EditorShell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * A stand-in for the render host whose surface is tagged, so a case can right-click it, and counts the presses that
 * reach it.
 */
private class TaggedViewportHost : ViewportHost {
	/** The presses that reached the surface. */
	var presses = 0

	/**
	 * A tagged surface that records the presses it receives on the main pass.
	 *
	 * @param String areaId The hosting area's id (unused; one surface).
	 * @param Modifier modifier The layout modifier the body passes.
	 */
	@Composable
	override fun Viewport2D(areaId: String, modifier: Modifier) {
		Box(
			modifier =
				modifier.testTag(SURFACE_TAG).pointerInput(Unit) {
					awaitPointerEventScope {
						while (true) {
							if (awaitPointerEvent().type == PointerEventType.Press) {
								presses++
							}
						}
					}
				},
		)
	}
}

/** The surface's test tag. */
private const val SURFACE_TAG = "viewportSurface"

/**
 * The 2D viewport's context menu, through the real shell: an idle right-click over the surface opens the mode's own
 * rows rather than the area menu, a row runs its command where the pointer was, a gesture live anywhere keeps the menu
 * shut, and the keyboard still reaches the shell once the menu has closed.
 */
@OptIn(ExperimentalTestApi::class)
class ViewportContextMenuTest {
	/**
	 * Mounts the shell over the bundled default settings with [session] open and a tagged viewport surface.
	 *
	 * @param EditorSession session The open document's session.
	 * @return TaggedViewportHost The surface stand-in.
	 */
	private fun ComposeUiTest.mount(session: EditorSession): TaggedViewportHost {
		val host = TaggedViewportHost()
		val settings = bundledSettings()
		setContent {
			CompositionLocalProvider(
				LocalSettings provides settings,
				LocalEditorSession provides session,
				LocalPuppet provides session.model.value,
			) {
				EditorShell(viewportHost = host, commandRegistry = CommandRegistry())
			}
		}
		waitForIdle()

		return host
	}

	/** Right-clicks the middle of the viewport surface. */
	private fun ComposeUiTest.rightClickTheSurface() {
		onNodeWithTag(SURFACE_TAG).performMouseInput { rightClick(center) }
		waitForIdle()
	}

	/**
	 * How many menu rows read [label], counting only those that can be picked when [pickable].
	 *
	 * @param String label The row's text.
	 * @param Boolean pickable Whether to count only enabled rows.
	 * @return Int The count.
	 */
	private fun ComposeUiTest.rowsReading(
		label: String,
		pickable: Boolean = false,
	): Int {
		val matcher = if (pickable) hasText(label) and hasClickAction() else hasText(label)

		return onAllNodes(matcher).fetchSemanticsNodes().size
	}

	/** The number of open popups. */
	private fun ComposeUiTest.popups(): Int = onAllNodes(isPopup()).fetchSemanticsNodes().size

	@Test
	fun objectModeOffersItsOwnRowsInsteadOfTheAreaMenu() =
		runComposeUiTest {
			val host = mount(commandFixtureSession(EditorMode.Object))

			rightClickTheSurface()

			assertEquals(1, popups())
			assertEquals(1, rowsReading("Duplicate"))
			assertEquals(1, rowsReading("Toggle Visibility"))
			assertEquals(1, rowsReading("Snap"))
			assertEquals(1, rowsReading("Frame Selected"))
			assertEquals(0, rowsReading("Subdivide Lattice"))
			assertEquals(0, rowsReading("Insert Keyframe"), "keying is Pose mode's")
			assertEquals(0, rowsReading("Close Area"), "the area menu stays on the header")
			assertEquals(1, host.presses, "the surface still saw the press")
		}

	@Test
	fun editModeOffersTheTopologyRows() =
		runComposeUiTest {
			mount(commandFixtureSession(EditorMode.Edit))

			rightClickTheSurface()

			assertEquals(1, rowsReading("Merge Vertices"))
			assertEquals(1, rowsReading("Connect Vertices"))
			assertEquals(1, rowsReading("Select Linked"))
			assertEquals(1, rowsReading("Subdivide Lattice"))
			assertEquals(0, rowsReading("Toggle Visibility"))
		}

	@Test
	fun poseModeOffersTheKeyingRows() =
		runComposeUiTest {
			mount(posedBody())

			rightClickTheSurface()

			assertEquals(1, rowsReading("Insert Keyframe", pickable = true))
			assertEquals(1, rowsReading("Delete Keyframe"))
			assertEquals(1, rowsReading("Insert Key by Type"))
			assertEquals(1, rowsReading("Clear Pose Edits"))
			assertEquals(0, rowsReading("Clear Pose Edits", pickable = true), "nothing is held to clear")
			assertEquals(0, rowsReading("Subdivide Lattice"), "Pose mode rewrites no lattice")
		}

	@Test
	fun insertKeyframeKeysThePosedWarpWhereThePointerWas() =
		runComposeUiTest {
			val session = posedBody()
			mount(session)

			rightClickTheSurface()
			onAllNodes(hasText("Insert Keyframe") and hasClickAction()).onFirst().performMouseInput { click(center) }
			waitForIdle()

			val grid = assertNotNull((session.model.value.deformerById[LATTICE_RIG_BODY] as Deformer.Warp).geometryGrid)
			assertEquals(listOf(-1f, 0.5f, 1f), grid.axes.single().keys.toList(), "keyed at the pose")
			assertEquals(0, popups(), "the menu closed behind the pick")
		}

	@Test
	fun aGestureLiveAnywhereKeepsTheMenuShut() =
		runComposeUiTest {
			val session = commandFixtureSession(EditorMode.Object)
			mount(session)
			runOnIdle { session.armZoomRegion("another area") }
			waitForIdle()

			rightClickTheSurface()

			assertEquals(0, popups(), "neither the viewport's menu nor the area's")
		}

	@Test
	fun theKeyboardReachesTheShellAfterThePickedMenuCloses() =
		runComposeUiTest {
			val session = commandFixtureSession(EditorMode.Object)
			mount(session)
			runOnIdle { session.setSelection(Selection()) }

			rightClickTheSurface()
			onAllNodes(hasText("Toggle Visibility") and hasClickAction()).onFirst().performMouseInput { click(center) }
			waitForIdle()
			assertEquals(0, popups(), "the pick closed the menu")
			onRoot().performKeyInput { pressKey(Key.A) }
			waitForIdle()

			assertFalse(session.selection.value.isEmpty, "A still selects all")
		}

	@Test
	fun theKeyboardReachesTheShellAfterAClickAwayClosesTheMenu() =
		runComposeUiTest {
			val session = commandFixtureSession(EditorMode.Object)
			mount(session)
			runOnIdle { session.setSelection(Selection()) }

			rightClickTheSurface()
			onNodeWithTag(SURFACE_TAG).performMouseInput { click(topLeft + Offset(4f, 4f)) }
			waitForIdle()
			assertEquals(0, popups(), "the click away closed the menu")
			onRoot().performKeyInput { pressKey(Key.A) }
			waitForIdle()

			assertFalse(session.selection.value.isEmpty, "A still selects all")
		}

	/**
	 * A session over the lattice rig with the body warp posed at 0.5 on the rig's parameter, which is targeted for
	 * keying.
	 *
	 * @return EditorSession The session, in Pose mode.
	 */
	private fun posedBody(): EditorSession {
		val session = latticeRigSession(listOf(SelectionTarget.Deformer(LATTICE_RIG_BODY)))
		session.commitPose(ParameterChange.SetValue(listOf(LATTICE_RIG_PARAMETER)), mapOf(LATTICE_RIG_PARAMETER to 0.5f))
		session.setParameterSelection(ParameterSelection(setOf(LATTICE_RIG_PARAMETER), LATTICE_RIG_PARAMETER))
		session.setMode(EditorMode.Pose)
		assertEquals(EditorMode.Pose, session.mode.value)

		return session
	}

	/**
	 * Settings over the bundled defaults and an empty in-memory config directory - a first run's.
	 *
	 * @return Settings The loaded settings.
	 */
	private fun bundledSettings(): Settings {
		val fileSystem = FakeFileSystem()
		fileSystem.createDirectories("/config".toPath())

		return Settings.load(OkioAppStorage(fileSystem, "/config".toPath(), "/data".toPath()), runBlocking { defaultSettingsJson() })
	}
}