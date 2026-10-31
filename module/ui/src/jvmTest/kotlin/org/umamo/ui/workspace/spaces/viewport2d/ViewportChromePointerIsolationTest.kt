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
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.ui.LocalSettings
import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.action.LocalKeymap
import org.umamo.ui.action.defaultKeymap
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.model.LocalPuppet
import org.umamo.ui.sidebar.LocalSidebarRegistry
import org.umamo.ui.sidebar.SidebarRegistry
import org.umamo.ui.sidebar.registerBuiltInSidebar
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
import org.umamo.ui.workspace.sidebar.SIDEBAR_DEFAULT_WIDTH
import org.umamo.ui.workspace.sidebar.SIDEBAR_TAB_GAP
import kotlin.test.Test
import kotlin.test.assertEquals

/** The test tag on the box the 2D viewport body is mounted in. */
private const val BODY_TAG = "viewportBody"

/** The id of the area the body is mounted as. */
private const val AREA_ID = "area-1"

/*
 * The body is mounted 600 x 400 at density 1, so every point below is in dp and px alike.
 *
 * The open sidebar floats at the top of the right edge with no background: the 200-wide panel stack, from
 * y 4, holding the active tab's one card behind a 3 dp gutter, then a 3 dp gap, then the tab rail against the
 * edge.  The resize grip straddles the card's left edge, over the gutter and the card's first 3 dp.  The
 * stack's points are found from its nodes (the rail's Item tab places the stack; the card reads "Nothing
 * selected.", since no selection handle is provided), and everything around the card and the tabs is the
 * surface's.
 * The toolbar is centered on the left edge behind the caller's 6 dp margin: a Column at x 6..40, y 123..277
 * holding five 28 dp buttons 2 dp apart inside 3 dp of padding, so its top padding is y 123..126 and the
 * first gap y 154..156.
 */

/** A point on the open surface, clear of every piece of chrome. */
private val OPEN_SURFACE = Offset(300f, 200f)

/** The muted line the card shows with nothing selected, a point on the card that is no control. */
private const val CARD_TEXT = "Nothing selected."

/** A point in the toolbar's top padding. */
private val TOOLBAR_TOP_PADDING = Offset(23f, 124.5f)

/** A point in the gap between the toolbar's first two buttons. */
private val TOOLBAR_GAP = Offset(23f, 155f)

/** A point in the toolbar's start padding. */
private val TOOLBAR_START_PADDING = Offset(7.5f, 200f)

/** A point in the caller's margin to the left of the toolbar, which the toolbar does not own. */
private val TOOLBAR_MARGIN = Offset(3f, 200f)

/**
 * The center of a node's bounds, in the root's px at density 1.
 *
 * @return Offset The center.
 */
private fun DpRect.center(): Offset = Offset((left.value + right.value) / 2f, (top.value + bottom.value) / 2f)

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
	 * Mounts the 2D viewport body with the toolbar and the drawer open over a recording surface, over a
	 * document and with Umamo's own sidebar tabs, so the drawer has the geometry it has in the app.
	 *
	 * @param RecordingViewportHost host The surface stand-in.
	 * @param EditorSession session The session to provide.
	 * @param CommandRegistry commands The registry the toolbar's buttons dispatch through.
	 */
	@OptIn(ExperimentalTestApi::class)
	private fun ComposeUiTest.mountBody(
		host: RecordingViewportHost,
		session: EditorSession = commandFixtureSession(EditorMode.Object),
		commands: CommandRegistry = CommandRegistry(),
	) {
		val settings = inMemorySettings()
		val sidebarRegistry = SidebarRegistry().also { registry -> registerBuiltInSidebar(registry) }
		setContent {
			UmamoTheme {
				CompositionLocalProvider(
					LocalDensity provides Density(1f),
					LocalSettings provides settings,
					LocalCommands provides commands,
					LocalKeymap provides defaultKeymap(),
					LocalEditorSession provides session,
					LocalPuppet provides session.model.value,
					LocalSidebarRegistry provides sidebarRegistry,
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
	 * A point on the sidebar's card, on its muted line rather than on a control.
	 *
	 * @return Offset The point, in the body's px.
	 */
	@OptIn(ExperimentalTestApi::class)
	private fun ComposeUiTest.onTheCard(): Offset = onNodeWithText(CARD_TEXT).getUnclippedBoundsInRoot().center()

	/**
	 * The left edge of the sidebar's panel stack, where the resize grip starts: the stack's default width and
	 * the tab gap left of the rail's Item tab.
	 *
	 * @return Float The edge's x, in the body's px.
	 */
	@OptIn(ExperimentalTestApi::class)
	private fun ComposeUiTest.stackLeft(): Float =
		onNodeWithText("Item").getUnclippedBoundsInRoot().left.value - SIDEBAR_TAB_GAP.value - SIDEBAR_DEFAULT_WIDTH.value

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

	/** A click or a wheel turn on the sidebar's card stays with the sidebar. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun theCardSwallowsPressAndWheel() =
		runComposeUiTest {
			val host = RecordingViewportHost()
			mountBody(host)
			val cardPoint = onTheCard()
			clickAt(cardPoint)
			scrollAt(cardPoint)
			assertEquals(0, host.presses, "a click on the card must not select or deselect under it")
			assertEquals(0, host.scrolls, "a wheel turn over the card must not zoom the view under it")
		}

	/**
	 * The sidebar has no background: above and below its card, in the gap before the tabs, and below the tabs,
	 * a click or a wheel turn reaches the surface.
	 */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun theSpaceAroundTheCardAndTabsStaysTheSurfaces() =
		runComposeUiTest {
			val host = RecordingViewportHost()
			mountBody(host)
			val cardPoint = onTheCard()
			val itemTab = onNodeWithText("Item").getUnclippedBoundsInRoot()
			val clearOfTheSidebar =
				listOf(
					Offset(cardPoint.x, 2f),
					Offset(cardPoint.x, 380f),
					Offset(itemTab.left.value - SIDEBAR_TAB_GAP.value / 2f, cardPoint.y),
					Offset(itemTab.center().x, 380f),
				)

			for (point in clearOfTheSidebar) {
				clickAt(point)
				scrollAt(point)
			}

			assertEquals(clearOfTheSidebar.size, host.presses, "every click around the card and the tabs reaches the surface")
			assertEquals(clearOfTheSidebar.size, host.scrolls, "every wheel turn around the card and the tabs reaches the surface")
		}

	/** The resize grip belongs to the sidebar: a press on it starts a resize, never a click on the surface. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun theResizeGripSwallowsPress() =
		runComposeUiTest {
			val host = RecordingViewportHost()
			mountBody(host)
			clickAt(Offset(stackLeft() + 1f, onTheCard().y))
			assertEquals(0, host.presses, "a click on the grip must not reach the surface")
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

	/** What the chrome does not own stays the surface's: the margin the caller puts around the toolbar. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun theToolbarMarginStaysTransparent() =
		runComposeUiTest {
			val host = RecordingViewportHost()
			mountBody(host)
			clickAt(TOOLBAR_MARGIN)
			assertEquals(1, host.presses, "the margin shows the surface, so the click reaches it")
		}

	/**
	 * While a gesture of this area owns the pointer, the chrome lets it through: a modal transform or an
	 * armed tool keeps following the pointer across the card and is confirmed or cancelled there.
	 */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun aGestureOwnedByThisAreaPassesThroughTheCard() =
		runComposeUiTest {
			val host = RecordingViewportHost()
			val session = commandFixtureSession(EditorMode.Object)
			session.armZoomRegion(AREA_ID)
			mountBody(host, session)
			clickAt(onTheCard())
			assertEquals(1, host.presses, "the armed Zoom Region of this area receives the click over the card")
		}

	/**
	 * The rail's tabs answer the pointer themselves, without it reaching the surface: a click selects the tab.
	 */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun theRailSelectsItsTabWithoutReachingTheSurface() =
		runComposeUiTest {
			val host = RecordingViewportHost()
			mountBody(host)
			clickAt(onNodeWithText("Tool").getUnclippedBoundsInRoot().center())
			onNodeWithText("Tool").assertIsSelected()
			assertEquals(0, host.presses, "the click on the tab stayed with the rail")
		}

	/**
	 * While a gesture of this area owns the pointer, the rail's tabs step aside too: the click reaches the
	 * surface, which confirms the gesture there, and selects no tab on its way.
	 */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun aGestureOwnedByThisAreaPassesThroughTheRail() =
		runComposeUiTest {
			val host = RecordingViewportHost()
			val session = commandFixtureSession(EditorMode.Object)
			session.armZoomRegion(AREA_ID)
			mountBody(host, session)
			clickAt(onNodeWithText("Tool").getUnclippedBoundsInRoot().center())
			assertEquals(1, host.presses, "the armed Zoom Region receives the click over the tab")
			onNodeWithText("Tool").assertIsNotSelected()
		}

	/**
	 * While a gesture of this area owns the pointer, the toolbar's buttons step aside: the click reaches the
	 * surface and the button under it dispatches nothing.
	 */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun aGestureOwnedByThisAreaPassesThroughTheToolbarButtons() =
		runComposeUiTest {
			val host = RecordingViewportHost()
			val session = commandFixtureSession(EditorMode.Object)
			var grabs = 0
			val commands = CommandRegistry()
			commands.register(Command("mesh.grab", title = null) { grabs++ })
			session.armZoomRegion(AREA_ID)
			mountBody(host, session, commands)
			val grabButton = onNodeWithContentDescription("Grab Vertices").getUnclippedBoundsInRoot().center()
			clickAt(grabButton)
			assertEquals(1, host.presses, "the armed Zoom Region receives the click over the button")
			assertEquals(0, grabs, "the button under the click dispatches nothing")
		}

	/** A gesture owned by another area leaves this area's chrome blocking. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun aGestureInAnotherAreaKeepsTheCardBlocking() =
		runComposeUiTest {
			val host = RecordingViewportHost()
			val session = commandFixtureSession(EditorMode.Object)
			session.armZoomRegion("area-2")
			mountBody(host, session)
			clickAt(onTheCard())
			assertEquals(0, host.presses, "another area's gesture does not open this area's card")
		}

	/**
	 * The chrome blocks only its siblings: the area around it still sees the pointer, so hovering the card
	 * stamps the area as the hovered surface and a right click there still opens the area's context menu.
	 */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun theCardStillStampsTheAreaAndOpensItsMenu() =
		runComposeUiTest {
			val host = RecordingViewportHost()
			val tracker = HoveredSurfaceTracker()
			val settings = inMemorySettings()
			val session = commandFixtureSession(EditorMode.Object)
			val sidebarRegistry = SidebarRegistry().also { registry -> registerBuiltInSidebar(registry) }
			setContent {
				UmamoTheme {
					CompositionLocalProvider(
						LocalDensity provides Density(1f),
						LocalSettings provides settings,
						LocalEditorSession provides session,
						LocalPuppet provides session.model.value,
						LocalSidebarRegistry provides sidebarRegistry,
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
			// Below the header whatever its height: the card's node is placed under it.
			val cardPoint = onTheCard()
			onNodeWithTag(BODY_TAG).performMouseInput { moveTo(cardPoint) }
			waitForIdle()
			assertEquals(HoveredSurface(AREA_ID, SpaceKind.Viewport2D), tracker.lastTouched, "hovering the card stamps its area")
			onNodeWithTag(BODY_TAG).performMouseInput { rightClick(cardPoint) }
			waitForIdle()
			onAllNodes(isPopup()).assertCountEquals(1)
			assertEquals(0, host.presses, "the right click opened the menu without reaching the surface")
		}
}