package org.umamo.ui.workspace.spaces.viewport2d

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ScrollWheel
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.ui.LocalSettings
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.action.LocalKeymap
import org.umamo.ui.action.defaultKeymap
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.theme.UmamoTheme
import org.umamo.ui.workspace.AreaScope
import org.umamo.ui.workspace.HoveredSurface
import org.umamo.ui.workspace.HoveredSurfaceTracker
import org.umamo.ui.workspace.LocalHoveredSurfaceTracker
import org.umamo.ui.workspace.LocalSpaceRegistry
import org.umamo.ui.workspace.LocalViewportChrome
import org.umamo.ui.workspace.LocalViewportHost
import org.umamo.ui.workspace.SpaceKind
import org.umamo.ui.workspace.ViewportChromeState
import org.umamo.ui.workspace.ViewportHost
import org.umamo.ui.workspace.area.AreaLeaf
import org.umamo.ui.workspace.commands.commandFixtureSession
import org.umamo.ui.workspace.commands.inMemorySettings
import org.umamo.ui.workspace.layout.LeafArea
import org.umamo.ui.workspace.shell.defaultSpaceRegistry
import kotlin.test.Test
import kotlin.test.assertEquals

/** The test tag on the box the 2D viewport body is mounted in. */
private const val BODY_TAG = "viewportBody"

/** The id of the area the body is mounted as. */
private const val AREA_ID = "area-1"

/*
 * The body is mounted 600 x 400 at density 1, so every point below is in dp and px alike.
 *
 * The sidebar drawer is a Row hugging the right edge: the pull-tab column (x 386..400, the 14 x 48 chip at
 * y 176..224) and then the 200-wide panel (x 400..600, its 8 dp padding ending at x 408, its text at the top).
 * The toolbar is centered on the left edge behind the caller's 6 dp margin: a Column at x 6..40, y 123..277
 * holding five 28 dp buttons 2 dp apart inside 3 dp of padding, so its top padding is y 123..126 and the
 * first gap y 154..156.
 */

/** A point on the open surface, clear of every piece of chrome. */
private val OPEN_SURFACE = Offset(300f, 200f)

/** A point on the drawer panel's empty body, below its text. */
private val PANEL_BODY = Offset(500f, 300f)

/** A point in the drawer panel's left padding, above the pull tab's span. */
private val PANEL_PADDING = Offset(404f, 100f)

/** A point in the toolbar's top padding. */
private val TOOLBAR_TOP_PADDING = Offset(23f, 124.5f)

/** A point in the gap between the toolbar's first two buttons. */
private val TOOLBAR_GAP = Offset(23f, 155f)

/** A point in the toolbar's start padding. */
private val TOOLBAR_START_PADDING = Offset(7.5f, 200f)

/** A point in the caller's margin to the left of the toolbar, which the toolbar does not own. */
private val TOOLBAR_MARGIN = Offset(3f, 200f)

/** A point in the pull tab's column away from the chip, which is transparent over the surface. */
private val PULL_TAB_COLUMN = Offset(393f, 100f)

/**
 * A stand-in for the render host: its surface counts the presses and wheel turns that reach it, which is
 * what the real surface's navigation loop and gizmo overlays would act on.
 */
private class RecordingViewportHost : ViewportHost {
	/** The presses that reached the surface. */
	var presses = 0

	/** The wheel turns that reached the surface. */
	var scrolls = 0

	/**
	 * A surface that records the presses and wheel turns it receives on the main pass, as the real surface's
	 * navigation loop does.
	 *
	 * @param String areaId The hosting area's id (unused; one surface).
	 * @param Modifier modifier The layout modifier the body passes.
	 */
	@Composable
	override fun Viewport2D(areaId: String, modifier: Modifier) {
		Box(
			modifier =
				modifier.pointerInput(Unit) {
					awaitPointerEventScope {
						while (true) {
							val event = awaitPointerEvent()

							when (event.type) {
								PointerEventType.Press -> {
									presses++
								}

								PointerEventType.Scroll -> {
									scrolls++
								}

								else -> {}
							}
						}
					}
				},
		)
	}
}

/**
 * The 2D viewport's floating chrome stops the pointer from reaching the surface under it, and lets it
 * through again while one of this area's gestures owns the pointer.
 *
 * A press is claimed among siblings by the first one with a pointer node under the pointer, so a panel
 * drawn over the surface with no pointer node of its own hands every click on its empty body to the
 * surface beneath.  These cases mount the real body over a recording surface to pin which points belong
 * to the chrome and which stay the surface's.
 */
class ViewportChromePointerIsolationTest {
	/**
	 * Mounts the 2D viewport body with the toolbar and the drawer open over a recording surface.
	 *
	 * @param RecordingViewportHost host The surface stand-in.
	 * @param EditorSession? session The session to provide, or null for the no-document state.
	 */
	@OptIn(ExperimentalTestApi::class)
	private fun ComposeUiTest.mountBody(host: RecordingViewportHost, session: EditorSession? = null) {
		val settings = inMemorySettings()
		setContent {
			UmamoTheme {
				CompositionLocalProvider(
					LocalDensity provides Density(1f),
					LocalSettings provides settings,
					LocalCommands provides CommandRegistry(),
					LocalEditorSession provides session,
					LocalViewportChrome provides ViewportChromeState(showToolbar = true, showSidebar = true),
					LocalViewportHost provides host,
				) {
					Box(modifier = Modifier.size(600.dp, 400.dp).testTag(BODY_TAG)) {
						Viewport2DBody(AreaScope(AREA_ID))
					}
				}
			}
		}
		waitForIdle()
	}

	/**
	 * Clicks the primary button at [point] in the body.
	 *
	 * @param Offset point Where to click, in the body's px.
	 */
	@OptIn(ExperimentalTestApi::class)
	private fun ComposeUiTest.clickAt(point: Offset) {
		onNodeWithTag(BODY_TAG).performMouseInput { click(point) }
		waitForIdle()
	}

	/**
	 * Turns the wheel one step at [point] in the body.
	 *
	 * @param Offset point Where the pointer rests while the wheel turns, in the body's px.
	 */
	@OptIn(ExperimentalTestApi::class)
	private fun ComposeUiTest.scrollAt(point: Offset) {
		onNodeWithTag(BODY_TAG).performMouseInput {
			moveTo(point)
			scroll(1f, ScrollWheel.Vertical)
		}
		waitForIdle()
	}

	/**
	 * The control every other case leans on: the surface itself receives a press and a wheel turn, so a
	 * count of zero elsewhere means the chrome stopped them rather than that the recorder saw nothing.
	 */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun theOpenSurfaceReceivesPressAndWheel() =
		runComposeUiTest {
			val host = RecordingViewportHost()
			mountBody(host)
			clickAt(OPEN_SURFACE)
			scrollAt(OPEN_SURFACE)
			assertEquals(1, host.presses, "a click on the open surface reaches it")
			assertEquals(1, host.scrolls, "a wheel turn over the open surface reaches it")
		}

	/** A click or a wheel turn on the drawer panel's empty body stays with the drawer. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun thePanelBodySwallowsPressAndWheel() =
		runComposeUiTest {
			val host = RecordingViewportHost()
			mountBody(host)
			clickAt(PANEL_BODY)
			scrollAt(PANEL_BODY)
			assertEquals(0, host.presses, "a click on the panel must not select or deselect under it")
			assertEquals(0, host.scrolls, "a wheel turn over the panel must not zoom the view under it")
		}

	/** The panel's padding belongs to the panel too. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun thePanelPaddingSwallowsPress() =
		runComposeUiTest {
			val host = RecordingViewportHost()
			mountBody(host)
			clickAt(PANEL_PADDING)
			assertEquals(0, host.presses, "a click in the panel's padding must not reach the surface")
		}

	/** The toolbar's padding and the gaps between its buttons belong to the toolbar. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun theToolbarPaddingAndGapsSwallowPressAndWheel() =
		runComposeUiTest {
			val host = RecordingViewportHost()
			mountBody(host)

			for (point in listOf(TOOLBAR_TOP_PADDING, TOOLBAR_GAP, TOOLBAR_START_PADDING)) {
				clickAt(point)
				scrollAt(point)
			}

			assertEquals(0, host.presses, "no click inside the toolbar's outline reaches the surface")
			assertEquals(0, host.scrolls, "no wheel turn inside the toolbar's outline reaches the surface")
		}

	/**
	 * What the chrome does not own stays the surface's: the margin the caller puts around the toolbar, and
	 * the transparent column the pull tab sits in, away from the chip.
	 */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun theToolbarMarginAndPullTabColumnStayTransparent() =
		runComposeUiTest {
			val host = RecordingViewportHost()
			mountBody(host)
			clickAt(TOOLBAR_MARGIN)
			clickAt(PULL_TAB_COLUMN)
			assertEquals(2, host.presses, "both points show the surface, so both clicks reach it")
		}

	/**
	 * While a gesture of this area owns the pointer, the chrome lets it through: a modal transform or an
	 * armed tool keeps following the pointer across the panel and is confirmed or cancelled there.
	 */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun aGestureOwnedByThisAreaPassesThroughThePanel() =
		runComposeUiTest {
			val host = RecordingViewportHost()
			val session = commandFixtureSession(EditorMode.Object)
			session.armZoomRegion(AREA_ID)
			mountBody(host, session)
			clickAt(PANEL_BODY)
			assertEquals(1, host.presses, "the armed Zoom Region of this area receives the click over the panel")
		}

	/** A gesture owned by another area leaves this area's chrome blocking. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun aGestureInAnotherAreaKeepsThePanelBlocking() =
		runComposeUiTest {
			val host = RecordingViewportHost()
			val session = commandFixtureSession(EditorMode.Object)
			session.armZoomRegion("area-2")
			mountBody(host, session)
			clickAt(PANEL_BODY)
			assertEquals(0, host.presses, "another area's gesture does not open this area's panel")
		}

	/**
	 * The chrome blocks only its siblings: the area around it still sees the pointer, so hovering the panel
	 * stamps the area as the hovered surface and a right click there still opens the area's context menu.
	 */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun thePanelStillStampsTheAreaAndOpensItsMenu() =
		runComposeUiTest {
			val host = RecordingViewportHost()
			val tracker = HoveredSurfaceTracker()
			val settings = inMemorySettings()
			setContent {
				UmamoTheme {
					CompositionLocalProvider(
						LocalDensity provides Density(1f),
						LocalSettings provides settings,
						LocalCommands provides CommandRegistry(),
						LocalKeymap provides defaultKeymap(),
						LocalSpaceRegistry provides defaultSpaceRegistry(),
						LocalHoveredSurfaceTracker provides tracker,
						LocalViewportChrome provides ViewportChromeState(showToolbar = true, showSidebar = true),
						LocalViewportHost provides host,
					) {
						Box(modifier = Modifier.size(600.dp, 400.dp).testTag(BODY_TAG)) {
							AreaLeaf(area = LeafArea(AREA_ID, SpaceKind.Viewport2D), onCommand = {})
						}
					}
				}
			}
			waitForIdle()
			// Below the header whatever its height, and inside the panel's 200 dp at the right edge.
			val panelPoint = Offset(550f, 320f)
			onNodeWithTag(BODY_TAG).performMouseInput { moveTo(panelPoint) }
			waitForIdle()
			assertEquals(HoveredSurface(AREA_ID, SpaceKind.Viewport2D), tracker.lastTouched, "hovering the panel stamps its area")
			onNodeWithTag(BODY_TAG).performMouseInput { rightClick(panelPoint) }
			waitForIdle()
			onAllNodes(isPopup()).assertCountEquals(1)
			assertEquals(0, host.presses, "the right click opened the menu without reaching the surface")
		}
}