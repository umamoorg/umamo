package org.umamo.ui.workspace.sidebar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.Selection
import org.umamo.edit.transform.beginBoxSelect
import org.umamo.ui.kit.Text
import org.umamo.ui.l10n.LocalAppLocale
import org.umamo.ui.l10n.UiText
import org.umamo.ui.sidebar.ProvideSidebarLocals
import org.umamo.ui.sidebar.SidebarDrawerInArea
import org.umamo.ui.sidebar.SidebarPanel
import org.umamo.ui.sidebar.SidebarRegistry
import org.umamo.ui.sidebar.SidebarTab
import org.umamo.ui.sidebar.builtInSidebarRegistry
import org.umamo.ui.sidebar.registerBuiltInSidebar
import org.umamo.ui.theme.UmamoTheme
import org.umamo.ui.workspace.SpaceKind
import org.umamo.ui.workspace.commands.commandFixtureSession
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The test tag on the box the drawer is mounted in. */
private const val DRAWER_TAG = "drawer"

/** The area the drawer belongs to. */
private const val AREA_ID = "area-1"

/** The pull tab's accessible label, the toggle command's title. */
private const val PULL_TAB_LABEL = "Toggle Sidebar"

/**
 * What reached the surface beneath the drawer.
 *
 * @property Int presses The presses the surface received.
 * @property Int scrolls The wheel turns the surface received.
 */
private class SurfaceLandings(var presses: Int = 0, var scrolls: Int = 0)

/**
 * The sidebar drawer: Umamo's own tabs and their panels, tabs a contributor registers while the drawer shows,
 * per-language labels following the UI language, folding, stepping tabs with Ctrl+wheel, dragging the stack's
 * width and dragging it closed, an area too narrow for the stack, the area's own open choice, the pull tab
 * showing only while closed, and the surface keeping the pointer everywhere around the floating cards and tabs.
 */
class SidebarDrawerTest {
	/**
	 * Mounts an open drawer at density 1 over a document, with Umamo's own tabs in [registry].
	 *
	 * @param SidebarRegistry registry The window's registry, the built-ins already in it.
	 * @param SidebarState state The area's sidebar state.
	 * @param EditorSession session The session to provide.
	 * @param MutableState languageTag The UI language, which a case may switch.
	 * @param Dp areaWidth The width of the area the drawer hugs the right edge of.
	 * @param SurfaceLandings landings Where the surface beneath the drawer counts what reaches it.
	 * @param Boolean passPointerThrough Whether the drawer lets the pointer through, as while a gesture owns it.
	 */
	@OptIn(ExperimentalTestApi::class)
	private fun ComposeUiTest.mountDrawer(
		registry: SidebarRegistry = builtInSidebarRegistry(),
		state: SidebarState = SidebarState(),
		session: EditorSession = commandFixtureSession(EditorMode.Object),
		languageTag: MutableState<String> = mutableStateOf("en"),
		areaWidth: Dp = 320.dp,
		landings: SurfaceLandings = SurfaceLandings(),
		passPointerThrough: Boolean = false,
	) {
		setContent {
			UmamoTheme {
				ProvideSidebarLocals(session, registry, LocalDensity provides Density(1f), LocalAppLocale provides languageTag.value) {
					SidebarDrawerInArea(
						AREA_ID,
						state,
						width = areaWidth,
						height = 400.dp,
						modifier = Modifier.testTag(DRAWER_TAG),
						passPointerThrough = passPointerThrough,
						surface = { RecordingSurface(landings) },
					)
				}
			}
		}
		waitForIdle()
	}

	/**
	 * A surface filling the area that counts the presses and wheel turns reaching it.
	 *
	 * @param SurfaceLandings landings Where they are counted.
	 */
	@Composable
	private fun RecordingSurface(landings: SurfaceLandings) {
		Box(
			modifier =
				Modifier.fillMaxSize().pointerInput(Unit) {
					awaitPointerEventScope {
						while (true) {
							val event = awaitPointerEvent()

							if (event.type == PointerEventType.Press) {
								landings.presses++
							}

							if (event.type == PointerEventType.Scroll) {
								landings.scrolls++
							}
						}
					}
				},
		)
	}

	/**
	 * A session with nothing selected, so the Item tab's Transform panel is a short card holding one line.
	 *
	 * @return EditorSession The session.
	 */
	private fun emptySelectionSession(): EditorSession = commandFixtureSession(EditorMode.Object).also { emptied -> emptied.setSelection(Selection()) }

	/**
	 * The center of the node showing [text], in the root's px at density 1.
	 *
	 * @param String text The node's text.
	 * @return Offset The node's center.
	 */
	@OptIn(ExperimentalTestApi::class)
	private fun ComposeUiTest.centerOf(text: String): Offset {
		val bounds = onNodeWithText(text).getUnclippedBoundsInRoot()

		return Offset((bounds.left.value + bounds.right.value) / 2f, (bounds.top.value + bounds.bottom.value) / 2f)
	}

	/**
	 * Registers a contributor's tab with one panel holding a single line of text.
	 *
	 * @param SidebarRegistry registry The registry.
	 * @param UiText label The tab's label.
	 * @return Function Withdraws the tab and its panel.
	 */
	private fun registerBonesTab(registry: SidebarRegistry, label: UiText): () -> Unit {
		val tab = registry.register(SidebarTab("plugin.bones", label, order = 1000))
		val panel =
			registry.register(
				SidebarPanel(
					id = "plugin.bones.chains",
					tabId = "plugin.bones",
					title = UiText.Literal("Bone Chains"),
					order = 100,
					spaces = setOf(SpaceKind.Viewport2D),
					content = { Text("Chain rows") },
				),
			)

		return {
			panel.dispose()
			tab.dispose()
		}
	}

	/** Umamo's tabs show Item first; choosing Tool swaps the stack to its Active Tool panel. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun theBuiltInTabsShowAndSwitch() =
		runComposeUiTest {
			mountDrawer()
			onNodeWithText("Item").assertIsSelected()
			onNodeWithText("Transform").assertExists()
			onNodeWithText("Tool").performClick()
			waitForIdle()
			onNodeWithText("Tool").assertIsSelected()
			onNodeWithText("Active Tool").assertExists()
			onNodeWithText("Tool settings will appear here.").assertExists()
			onNodeWithText("Transform").assertDoesNotExist()
		}

	/** A tab a contributor registers while the drawer shows appears on the rail, and leaves with its handle. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun aContributedTabComesAndGoes() =
		runComposeUiTest {
			val registry = SidebarRegistry().also { built -> registerBuiltInSidebar(built) }
			mountDrawer(registry = registry)
			val withdraw = runOnIdle { registerBonesTab(registry, UiText.Literal("Bones")) }
			waitForIdle()
			onNodeWithText("Bones").performClick()
			waitForIdle()
			onNodeWithText("Chain rows").assertExists()
			runOnIdle { withdraw() }
			waitForIdle()
			onNodeWithText("Bones").assertDoesNotExist()
			onNodeWithText("Item").assertIsSelected()
		}

	/** A contributor's per-language label follows a live switch of the UI language. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun aLocalizedLabelFollowsTheUiLanguage() =
		runComposeUiTest {
			val registry = SidebarRegistry().also { built -> registerBuiltInSidebar(built) }
			registerBonesTab(registry, UiText.Localized(linkedMapOf("en" to "Bones", "ja" to "ボーン")))
			val languageTag = mutableStateOf("en")
			mountDrawer(registry = registry, languageTag = languageTag)
			onNodeWithText("Bones").assertExists()
			languageTag.value = "ja"
			waitForIdle()
			onNodeWithText("ボーン").assertExists()
			onNodeWithText("Bones").assertDoesNotExist()
		}

	/** Folding a panel hides its rows, and the fold is the area's. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun foldingHidesAPanelsRows() =
		runComposeUiTest {
			val state = SidebarState()
			// Nothing selected, so the Item tab's Transform panel holds the one line this case watches.
			mountDrawer(state = state, session = emptySelectionSession())
			onNodeWithText("Nothing selected.").assertExists()
			onNodeWithText("Transform").performClick()
			waitForIdle()
			onNodeWithText("Nothing selected.").assertDoesNotExist()
		}

	/** Ctrl+wheel over the rail steps through the tabs; a plain wheel turn does not. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun ctrlWheelStepsTheTabs() =
		runComposeUiTest {
			val state = SidebarState()
			mountDrawer(state = state)
			val railPoint = centerOf("Item")
			onNodeWithTag(DRAWER_TAG).performMouseInput {
				moveTo(railPoint)
				scroll(1f)
			}
			waitForIdle()
			onNodeWithText("Item").assertIsSelected()
			onNodeWithTag(DRAWER_TAG).performKeyInput { keyDown(Key.CtrlLeft) }
			onNodeWithTag(DRAWER_TAG).performMouseInput { scroll(1f) }
			onNodeWithTag(DRAWER_TAG).performKeyInput { keyUp(Key.CtrlLeft) }
			waitForIdle()
			onNodeWithText("Tool").assertIsSelected()
			assertEquals("umamo.tool", state.activeTabId)
		}

	/** Active Tool names the tool armed in its own area, and not one armed in another. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun activeToolNamesOnlyThisAreasTool() =
		runComposeUiTest {
			val state = SidebarState()
			state.activeTabId = "umamo.tool"
			val session = commandFixtureSession(EditorMode.Object)
			mountDrawer(state = state, session = session)
			runOnIdle { session.beginBoxSelect("area-2") }
			waitForIdle()
			onNodeWithText("Tool settings will appear here.").assertExists()
			runOnIdle { session.beginBoxSelect(AREA_ID) }
			waitForIdle()
			onNodeWithText("Box Select").assertExists()
			assertFalse(state.shownTabIds.isEmpty(), "the open drawer publishes its rail for the tab commands")
		}

	/**
	 * Drags the resize grip on the cards' inner edge horizontally through [through], each an offset from where
	 * the press lands, level with the Transform card's header.  The grip starts at the stack's left edge, the
	 * stack's width and the tab gap left of the rail.
	 *
	 * @param SidebarState state The sidebar's state, whose width places the stack.
	 * @param List through The x offsets moved through, in order.
	 */
	@OptIn(ExperimentalTestApi::class)
	private fun ComposeUiTest.dragGrip(state: SidebarState, through: List<Float>) {
		val railLeft = onNodeWithText("Item").getUnclippedBoundsInRoot().left.value
		val gripX = railLeft - SIDEBAR_TAB_GAP.value - state.width.value + 2f
		val gripY = centerOf("Transform").y
		onNodeWithTag(DRAWER_TAG).performMouseInput {
			moveTo(Offset(gripX, gripY))
			press()

			for (offset in through) {
				moveTo(Offset(gripX + offset, gripY))
			}

			release()
		}
		waitForIdle()
	}

	/** Dragging the grip left widens the sidebar's panel stack by the drag. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun draggingTheGripWidensTheSidebar() =
		runComposeUiTest {
			val state = SidebarState()
			mountDrawer(state = state, areaWidth = 600.dp)
			dragGrip(state, listOf(-20f, -40f, -60f))
			// A mouse drag gives up a fraction of a pixel to the pointer slop before its first step.
			assertTrue(abs(state.width.value - 260f) < 1f, "a 60 px drag widens the stack by about 60 dp (got ${state.width})")
		}

	/** A drag past the room the area leaves stops at that room. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun aDragStopsAtTheRoomTheAreaLeaves() =
		runComposeUiTest {
			val state = SidebarState()
			mountDrawer(state = state, areaWidth = 400.dp)
			dragGrip(state, listOf(-100f, -200f, -300f))
			assertEquals(400.dp - SIDEBAR_AREA_RESERVE, state.width)
		}

	/** An area too narrow for the narrowest sidebar shows only the pull tab, and the sidebar stays open for later. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun aNarrowAreaShowsOnlyThePullTab() =
		runComposeUiTest {
			val state = SidebarState()
			mountDrawer(state = state, areaWidth = SIDEBAR_MIN_WIDTH + SIDEBAR_AREA_RESERVE - 10.dp)
			onNodeWithText("Item").assertDoesNotExist()
			onNodeWithContentDescription(PULL_TAB_LABEL).assertExists()
			assertTrue(state.shownTabIds.isEmpty(), "a sidebar with no room shows no tab to step through")
			assertTrue(state.visibility.isShown(default = true), "the sidebar is still open, waiting for room")
		}

	/** The area's own choice wins over the application's default. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun theAreasOwnChoiceWinsOverTheDefault() =
		runComposeUiTest {
			val state = SidebarState()
			state.visibility.choice = false
			mountDrawer(state = state)
			onNodeWithText("Item").assertDoesNotExist()
		}

	/** Dragging the cards' edge far past the narrowest closes the sidebar, and puts back the width it began from. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun draggingFarPastTheMinimumCollapsesTheSidebar() =
		runComposeUiTest {
			val state = SidebarState()
			mountDrawer(state = state, areaWidth = 600.dp)
			// 200 dp less 130 asks for 70, under the collapse width.
			dragGrip(state, listOf(40f, 80f, 130f))
			assertEquals(false, state.visibility.choice, "the drag closed the sidebar as the area's own choice")
			assertEquals(SIDEBAR_DEFAULT_WIDTH, state.width, "the width the drag began from comes back for the reopen")
			onNodeWithText("Item").assertDoesNotExist()
			onNodeWithContentDescription(PULL_TAB_LABEL).assertExists()
		}

	/** A drag that stops short of the collapse width only narrows the stack to its narrowest. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun aDragThatStopsShortOfTheCollapseOnlyNarrows() =
		runComposeUiTest {
			val state = SidebarState()
			mountDrawer(state = state, areaWidth = 600.dp)
			// 200 dp less 110 asks for 90, over the collapse width.
			dragGrip(state, listOf(40f, 80f, 110f))
			assertEquals(SIDEBAR_MIN_WIDTH, state.width)
			assertEquals(null, state.visibility.choice, "the sidebar is still open on the default")
			onNodeWithText("Item").assertExists()
		}

	/** The pull tab shows only while the sidebar is closed, at the top of the area's right edge. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun thePullTabShowsOnlyWhileClosed() =
		runComposeUiTest {
			val state = SidebarState()
			mountDrawer(state = state)
			onNodeWithContentDescription(PULL_TAB_LABEL).assertDoesNotExist()
			runOnIdle { state.visibility.choice = false }
			waitForIdle()
			val pullTab = onNodeWithContentDescription(PULL_TAB_LABEL).getUnclippedBoundsInRoot()
			assertEquals(320.dp, pullTab.right, "the pull tab hugs the area's right edge")
			assertTrue(pullTab.top < 20.dp, "the pull tab sits at the top of the edge (got ${pullTab.top})")
		}

	/** A press or a wheel turn below the floating cards reaches the surface; over a card or a tab it does not. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun theSurfaceKeepsThePointerAroundTheCardsAndTabs() =
		runComposeUiTest {
			val landings = SurfaceLandings()
			mountDrawer(session = emptySelectionSession(), landings = landings)
			val belowTheCards = Offset(centerOf("Transform").x, 300f)
			val onTheCard = centerOf("Nothing selected.")
			val onTheTab = centerOf("Item")
			onNodeWithTag(DRAWER_TAG).performMouseInput {
				click(belowTheCards)
				moveTo(belowTheCards)
				scroll(1f)
			}
			waitForIdle()
			assertEquals(1, landings.presses, "a press below the cards reaches the surface")
			assertEquals(1, landings.scrolls, "a wheel turn below the cards reaches the surface")
			onNodeWithTag(DRAWER_TAG).performMouseInput {
				click(onTheCard)
				moveTo(onTheCard)
				scroll(1f)
				click(onTheTab)
			}
			waitForIdle()
			assertEquals(1, landings.presses, "a press on a card or a tab stays with the sidebar")
			assertEquals(1, landings.scrolls, "a wheel turn over a card stays with the sidebar")
		}

	/** While a gesture owns the pointer, a press on a card reaches the surface and leaves the card as it was. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun aPassingDrawerHandsCardPressesToTheSurface() =
		runComposeUiTest {
			val landings = SurfaceLandings()
			mountDrawer(session = emptySelectionSession(), landings = landings, passPointerThrough = true)
			val onTheHeader = centerOf("Transform")
			onNodeWithTag(DRAWER_TAG).performMouseInput { click(onTheHeader) }
			waitForIdle()
			assertEquals(1, landings.presses, "the surface receives the press")
			onNodeWithText("Nothing selected.").assertExists()
		}
}