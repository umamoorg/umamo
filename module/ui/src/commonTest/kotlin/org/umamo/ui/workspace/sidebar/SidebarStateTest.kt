package org.umamo.ui.workspace.sidebar

import androidx.compose.ui.unit.dp
import org.umamo.ui.l10n.UiText
import org.umamo.ui.sidebar.SidebarPanel
import org.umamo.ui.workspace.SpaceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The tabs on every case's rail, top to bottom. */
private val SHOWN = listOf("umamo.item", "umamo.tool", "umamo.view")

/**
 * One area's sidebar state: the tab it shows when the rigger's choice is or is not on the rail, stepping
 * along the rail, and folding panels.
 */
class SidebarStateTest {
	/**
	 * A panel with no rows.
	 *
	 * @param String id The panel's id.
	 * @param Boolean initiallyCollapsed Whether it starts folded.
	 * @return SidebarPanel The panel.
	 */
	private fun panel(id: String, initiallyCollapsed: Boolean = false): SidebarPanel =
		SidebarPanel(id, "umamo.item", UiText.Literal(id), 100, setOf(SpaceKind.Viewport2D), initiallyCollapsed, content = {})

	/** With no choice made the first tab shows; a choice on the rail shows. */
	@Test
	fun theChoiceShowsWhenItIsOnTheRail() {
		val state = SidebarState()
		assertEquals("umamo.item", state.resolvedActiveTab(SHOWN))
		state.activeTabId = "umamo.view"
		assertEquals("umamo.view", state.resolvedActiveTab(SHOWN))
	}

	/** A choice off the rail shows the first tab instead, and is kept for when it comes back. */
	@Test
	fun aChoiceOffTheRailIsKept() {
		val state = SidebarState()
		state.activeTabId = "plugin.bones"
		assertEquals("umamo.item", state.resolvedActiveTab(SHOWN))
		assertEquals("plugin.bones", state.activeTabId, "the choice itself is left as it was")
		assertEquals("plugin.bones", state.resolvedActiveTab(SHOWN + "plugin.bones"), "and shows again when its tab is back")
		assertNull(state.resolvedActiveTab(emptyList()), "no tab shows on an empty rail")
	}

	/** Stepping moves from the tab shown, wrapping past either end of the rail. */
	@Test
	fun steppingWrapsBothWays() {
		val state = SidebarState()
		state.shownTabIds = SHOWN
		state.selectAdjacentTab(-1)
		assertEquals("umamo.view", state.activeTabId, "back from the first tab is the last")
		state.selectAdjacentTab(1)
		assertEquals("umamo.item", state.activeTabId, "on from the last tab is the first")
		state.activeTabId = "plugin.bones"
		state.selectAdjacentTab(1)
		assertEquals("umamo.tool", state.activeTabId, "a choice off the rail steps from the tab actually shown")
	}

	/** With no tab on the rail, stepping does nothing. */
	@Test
	fun steppingAnEmptyRailDoesNothing() {
		val state = SidebarState()
		state.selectAdjacentTab(1)
		assertNull(state.activeTabId)
	}

	/** A panel starts as its default says, and a fold is remembered against it. */
	@Test
	fun foldsStartFromThePanelsDefault() {
		val state = SidebarState()
		val open = panel("open")
		val folded = panel("folded", initiallyCollapsed = true)
		assertTrue(state.isExpanded(open))
		assertFalse(state.isExpanded(folded))
		state.toggleExpanded(open)
		state.toggleExpanded(folded)
		assertFalse(state.isExpanded(open))
		assertTrue(state.isExpanded(folded))
	}

	/** A panel folded and opened again is back at its default and keeps no entry, so nothing is saved for it. */
	@Test
	fun foldingBackToTheDefaultLeavesNoEntry() {
		val state = SidebarState()
		val open = panel("open")
		state.toggleExpanded(open)
		assertEquals(mapOf("open" to false), state.expandedByPanel.toMap())
		state.toggleExpanded(open)
		assertTrue(state.expandedByPanel.isEmpty())
	}

	/** The stack keeps the rigger's width when the area has room, and is held between its limits and the room left. */
	@Test
	fun theStackWidthFitsTheArea() {
		assertEquals(200.dp, sidebarStackWidth(200.dp, 600.dp), "the chosen width fits")
		assertEquals(SIDEBAR_MIN_WIDTH, sidebarStackWidth(100.dp, 600.dp), "never narrower than the minimum")
		assertEquals(SIDEBAR_MAX_WIDTH, sidebarStackWidth(600.dp, 1000.dp), "never wider than the maximum")
		assertEquals(400.dp - SIDEBAR_AREA_RESERVE, sidebarStackWidth(400.dp, 400.dp), "never wider than the area leaves")
		assertNull(sidebarStackWidth(200.dp, SIDEBAR_MIN_WIDTH + SIDEBAR_AREA_RESERVE - 1.dp), "an area too narrow for the narrowest stack fits none")
	}
}