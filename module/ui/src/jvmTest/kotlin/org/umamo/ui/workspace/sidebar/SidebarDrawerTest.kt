package org.umamo.ui.workspace.sidebar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
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
import org.umamo.edit.transform.beginBoxSelect
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.kit.Text
import org.umamo.ui.l10n.LocalAppLocale
import org.umamo.ui.l10n.UiText
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.model.LocalPuppet
import org.umamo.ui.sidebar.LocalSidebarRegistry
import org.umamo.ui.sidebar.SidebarPanel
import org.umamo.ui.sidebar.SidebarRegistry
import org.umamo.ui.sidebar.SidebarTab
import org.umamo.ui.sidebar.registerBuiltInSidebar
import org.umamo.ui.theme.UmamoTheme
import org.umamo.ui.workspace.LocalViewportChrome
import org.umamo.ui.workspace.SpaceKind
import org.umamo.ui.workspace.ViewportChromeState
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

/**
 * The open sidebar drawer: Umamo's own tabs and their panels, tabs a contributor registers while the drawer
 * shows, per-language labels following the UI language, folding, and stepping tabs with Ctrl+wheel.
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
	 */
	@OptIn(ExperimentalTestApi::class)
	private fun ComposeUiTest.mountDrawer(
		registry: SidebarRegistry = SidebarRegistry().also { built -> registerBuiltInSidebar(built) },
		state: SidebarState = SidebarState(),
		session: EditorSession = commandFixtureSession(EditorMode.Object),
		languageTag: MutableState<String> = mutableStateOf("en"),
		areaWidth: Dp = 320.dp,
	) {
		setContent {
			UmamoTheme {
				CompositionLocalProvider(
					LocalDensity provides Density(1f),
					LocalAppLocale provides languageTag.value,
					LocalCommands provides CommandRegistry(),
					LocalViewportChrome provides ViewportChromeState(showSidebar = true),
					LocalPuppet provides session.model.value,
					LocalEditorSession provides session,
					LocalSidebarRegistry provides registry,
				) {
					Box(modifier = Modifier.size(areaWidth, 400.dp).testTag(DRAWER_TAG)) {
						SidebarDrawer(
							areaId = AREA_ID,
							space = SpaceKind.Viewport2D,
							state = state,
							passPointerThrough = false,
							modifier = Modifier.align(Alignment.CenterEnd),
						)
					}
				}
			}
		}
		waitForIdle()
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
			mountDrawer(state = state)
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
			val itemTab = onNodeWithText("Item").getUnclippedBoundsInRoot()
			val railPoint = Offset((itemTab.left.value + itemTab.right.value) / 2f, (itemTab.top.value + itemTab.bottom.value) / 2f)
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
	 * Drags the resize grip on the stack's inner edge horizontally through [through], each an offset from
	 * where the press lands.  The grip sits at the stack's left edge, the stack's width left of the rail.
	 *
	 * @param SidebarState state The sidebar's state, whose width places the stack.
	 * @param List through The x offsets moved through, in order.
	 */
	@OptIn(ExperimentalTestApi::class)
	private fun ComposeUiTest.dragGrip(state: SidebarState, through: List<Float>) {
		val railLeft = onNodeWithText("Item").getUnclippedBoundsInRoot().left.value
		val gripX = railLeft - state.width.value + 2f
		onNodeWithTag(DRAWER_TAG).performMouseInput {
			moveTo(Offset(gripX, 300f))
			press()

			for (offset in through) {
				moveTo(Offset(gripX + offset, 300f))
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
}